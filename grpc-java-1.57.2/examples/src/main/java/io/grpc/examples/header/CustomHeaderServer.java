/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.header;

import io.grpc.*;
import io.grpc.examples.manualflowcontrol.HelloReply;
import io.grpc.examples.manualflowcontrol.HelloRequest;
import io.grpc.examples.manualflowcontrol.StreamingGreeterGrpc.StreamingGreeterImplBase;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.nats.client.*;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * A simple server that like {@link io.grpc.examples.helloworld.HelloWorldServer}.
 * You can get and response any header in {@link io.grpc.examples.header.HeaderServerInterceptor}.
 */
public class CustomHeaderServer {
  private static final Logger logger = Logger.getLogger(CustomHeaderServer.class.getName());

  /* The port on which the server should run */
  private static final int PORT = 50051;
  private Server server;

  private void start() throws IOException, InterruptedException {
    server = Grpc.newServerBuilderForPort(PORT, InsecureServerCredentials.create())
        .addService(ServerInterceptors.intercept(new GreeterImpl(), new HeaderServerInterceptor()))
        .build()
        .start();
    logger.info("Server started, listening on " + PORT);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        try {
          CustomHeaderServer.this.stop();
        } catch (InterruptedException e) {
          e.printStackTrace(System.err);
        }
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  /**
   * Await termination on the main thread since the grpc library uses daemon threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    final CustomHeaderServer server = new CustomHeaderServer();
    server.start();
    server.blockUntilShutdown();
  }

  private static class GreeterImpl extends StreamingGreeterImplBase {

    private ExecutorService executorService = Executors.newFixedThreadPool(8);
    private Connection connection;

    private TextMapGetter<Headers> getter =
            new TextMapGetter<Headers>() {
              @Override
              public String get(Headers carrier, String key) {
                if (carrier!=null && carrier.containsKey(key)) {
                  return carrier.get(key).get(0);
                }
                return null;
              }

              @Override
              public Iterable<String> keys(Headers carrier) {
                return carrier.keySet();
              }
            };

    private TextMapSetter<Headers> setter = (carrier, key, value) -> {
      if(!key.equals("x-dynatrace")){
        carrier.put(key, value);
      }
    };

    private GreeterImpl() throws IOException, InterruptedException {
      Options.Builder b = new Options.Builder().server("127.0.0.1:14222")
              .userInfo("dmp-1", "dmp-1").connectionTimeout(Duration.ofDays(1));
      connection = Nats.connect(b.build());

    }

    @Override
    public StreamObserver<HelloRequest> sayHelloStreaming(final StreamObserver<HelloReply> responseObserver) {
      // Set up manual flow control for the request stream. It feels backwards to configure the request
      // stream's flow control using the response stream's observer, but this is the way it is.
      final ServerCallStreamObserver<HelloReply> serverCallStreamObserver =
              (ServerCallStreamObserver<HelloReply>) responseObserver;
      serverCallStreamObserver.disableAutoRequest();

      // Set up a back-pressure-aware consumer for the request stream. The onReadyHandler will be invoked
      // when the consuming side has enough buffer space to receive more messages.
      //
      // Note: the onReadyHandler's invocation is serialized on the same thread pool as the incoming StreamObserver's
      // onNext(), onError(), and onComplete() handlers. Blocking the onReadyHandler will prevent additional messages
      // from being processed by the incoming StreamObserver. The onReadyHandler must return in a timely manner or
      // else message processing throughput will suffer.
      class OnReadyHandler implements Runnable {
        // Guard against spurious onReady() calls caused by a race between onNext() and onReady(). If the transport
        // toggles isReady() from false to true while onNext() is executing, but before onNext() checks isReady(),
        // request(1) would be called twice - once by onNext() and once by the onReady() scheduled during onNext()'s
        // execution.
        private boolean wasReady = false;

        @Override
        public void run() {
          if (serverCallStreamObserver.isReady() && !wasReady) {
            wasReady = true;
            logger.info("READY");
            // Signal the request sender to send one message. This happens when isReady() turns true, signaling that
            // the receive buffer has enough free space to receive more messages. Calling request() serves to prime
            // the message pump.
            serverCallStreamObserver.request(1);
          }
        }
      }
      final OnReadyHandler onReadyHandler = new OnReadyHandler();
      serverCallStreamObserver.setOnReadyHandler(onReadyHandler);

      // Give gRPC a StreamObserver that can observe and process incoming requests.
      return new StreamObserver<HelloRequest>() {

        @Override
        public void onNext(HelloRequest request) {

          //Process response once event received from the EDA
          processResponseAsync(responseObserver);

          //Process request by sending an event to the EDA.
          processRequest(request);
        }

        private void processRequest(HelloRequest request) {
          Span componentSpan = Otel.tracer
                  .spanBuilder("EGW.GRPC.REQ (Event Gateway)")
                  .setSpanKind(SpanKind.SERVER)
                  .startSpan();

          try(Scope scope = componentSpan.makeCurrent())  {

            // Accept and enqueue the request.
            String name = request.getName();
            logger.info(" REQ RECV --> " + name);

            // Simulate server "work"
            Thread.sleep(100);

            final String message = "Hello " + name;

            Headers headers = new Headers();

            GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()
                    .inject(Context.current().with(componentSpan), headers, setter);

            Message msg = NatsMessage.builder().subject("eda.fn.req").data(message.getBytes())
                    .headers(headers).build();
            connection.publish(msg);

            // Check the provided ServerCallStreamObserver to see if it is still ready to accept more messages.
            if (serverCallStreamObserver.isReady()) {
              // Signal the sender to send another request. As long as isReady() stays true, the server will keep
              // cycling through the loop of onNext() -> request(1)...onNext() -> request(1)... until the client runs
              // out of messages and ends the loop (via onCompleted()).
              //
              // If request() was called here with the argument of more than 1, the server might runs out of receive
              // buffer space, and isReady() will turn false. When the receive buffer has sufficiently drained,
              // isReady() will turn true, and the serverCallStreamObserver's onReadyHandler will be called to restart
              // the message pump.
              serverCallStreamObserver.request(1);
            } else {
              // If not, note that back-pressure has begun.
              onReadyHandler.wasReady = false;
            }
          } catch (Throwable throwable) {
            throwable.printStackTrace();
            responseObserver.onError(
                    Status.UNKNOWN.withDescription("Error handling request").withCause(throwable).asException());
          } finally {
            componentSpan.end();
          }
        }

        private void processResponseAsync(StreamObserver<HelloReply> responseObserver) {
          Dispatcher d = connection.createDispatcher();
          d.subscribe("eda.fn.res", "q1", (msg) -> {

            Context extractedContext = GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()
                    .extract(Context.current(), msg.getHeaders(), getter);

            Span componentSpan = null;

            try (Scope scope1 = extractedContext.makeCurrent()){

              componentSpan = Otel.tracer
                      .spanBuilder("EGW.GRPC.RES (Event Gateway)")
                      .setSpanKind(SpanKind.CONSUMER)
                      .startSpan();

              logger.info("RES SENT <-- " + new String(msg.getData(), StandardCharsets.UTF_8));
              HelloReply reply = HelloReply.newBuilder()
                      .setMessage(new String(msg.getData(), StandardCharsets.UTF_8)).build();
              responseObserver.onNext(reply);
            }
            finally {
              if(componentSpan!=null){
                componentSpan.end();
              }
            }
          });
        }

        @Override
        public void onError(Throwable t) {
          // End the response stream if the client presents an error.
          t.printStackTrace();
          responseObserver.onCompleted();
        }

        @Override
        public void onCompleted() {
          // Signal the end of work when the client ends the request stream.
          logger.info("COMPLETED");
          responseObserver.onCompleted();
        }
      };
    }
  }
}
