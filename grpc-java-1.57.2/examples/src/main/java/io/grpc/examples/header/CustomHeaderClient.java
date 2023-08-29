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
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.examples.manualflowcontrol.StreamingGreeterGrpc;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple client that like {@link io.grpc.examples.helloworld.HelloWorldClient}.
 * This client can help you create custom headers.
 */
public class CustomHeaderClient {
  private static final Logger logger = Logger.getLogger(CustomHeaderClient.class.getName());

  private final ManagedChannel originChannel;
  //private final GreeterGrpc.GreeterBlockingStub blockingStub;
  private final StreamingGreeterGrpc.StreamingGreeterStub asyncStub;
  /**
   * A custom client.
   */
  private CustomHeaderClient(String host, int port) {
    originChannel = Grpc
        .newChannelBuilderForAddress(host, port, InsecureChannelCredentials.create())
        .build();
    ClientInterceptor interceptor = new HeaderClientInterceptor();
    Channel channel = ClientInterceptors.intercept(originChannel, interceptor);
    //blockingStub = GreeterGrpc.newBlockingStub(channel);

    asyncStub = StreamingGreeterGrpc.newStub(channel);
  }

  private void shutdown() throws InterruptedException {
    originChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /**
   * A simple client method that like {@link io.grpc.examples.helloworld.HelloWorldClient}.
   */
  private void greet(String name) throws InterruptedException {

    final CountDownLatch done = new CountDownLatch(1);

    logger.info("Will try to greet " + name + " ...");


    ClientResponseObserver<io.grpc.examples.manualflowcontrol.HelloRequest, io.grpc.examples.manualflowcontrol.HelloReply> clientResponseObserver =
            new ClientResponseObserver<io.grpc.examples.manualflowcontrol.HelloRequest, io.grpc.examples.manualflowcontrol.HelloReply>() {

              ClientCallStreamObserver<io.grpc.examples.manualflowcontrol.HelloRequest> requestStream;

              @Override
              public void beforeStart(final ClientCallStreamObserver<io.grpc.examples.manualflowcontrol.HelloRequest> requestStream) {
                this.requestStream = requestStream;
                // Set up manual flow control for the response stream. It feels backwards to configure the response
                // stream's flow control using the request stream's observer, but this is the way it is.
                requestStream.disableAutoRequestWithInitial(1);

                // Set up a back-pressure-aware producer for the request stream. The onReadyHandler will be invoked
                // when the consuming side has enough buffer space to receive more messages.
                //
                // Messages are serialized into a transport-specific transmit buffer. Depending on the size of this buffer,
                // MANY messages may be buffered, however, they haven't yet been sent to the server. The server must call
                // request() to pull a buffered message from the client.
                //
                // Note: the onReadyHandler's invocation is serialized on the same thread pool as the incoming
                // StreamObserver's onNext(), onError(), and onComplete() handlers. Blocking the onReadyHandler will prevent
                // additional messages from being processed by the incoming StreamObserver. The onReadyHandler must return
                // in a timely manner or else message processing throughput will suffer.
                requestStream.setOnReadyHandler(new Runnable() {
                  // An iterator is used so we can pause and resume iteration of the request data.
                  Iterator<String> iterator = names().iterator();

                  @Override
                  public void run() {
                    // Start generating values from where we left off on a non-gRPC thread.
                    while (requestStream.isReady()) {
                      if (iterator.hasNext()) {
                        // Send more messages if there are more messages to send.
                        String name = iterator.next();
                        logger.info("--> " + name);
                        io.grpc.examples.manualflowcontrol.HelloRequest request = io.grpc.examples.manualflowcontrol.HelloRequest.newBuilder().setName(name).build();
                        requestStream.onNext(request);
                      } else {
                        // Signal completion if there is nothing left to send.
                        //requestStream.onCompleted();
                      }
                    }
                  }
                });
              }

              @Override
              public void onNext(io.grpc.examples.manualflowcontrol.HelloReply value) {
                logger.info("<-- " + value.getMessage());
                // Signal the sender to send one message.
                requestStream.request(1);
              }

              @Override
              public void onError(Throwable t) {
                t.printStackTrace();
                done.countDown();
              }

              @Override
              public void onCompleted() {
                logger.info("All Done");
                done.countDown();
              }
            };

    asyncStub.sayHelloStreaming(clientResponseObserver);

    done.await();

    /*HelloReply response;
    try {
      response = blockingStub.sayHello(request);
    } catch (StatusRuntimeException e) {
      logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      return;
    }
    logger.info("Greeting: " + response.getMessage());*/

  }

  /**
   * Main start the client from the command line.
   */
  public static void main(String[] args) throws Exception {
    // Access a service running on the local machine on port 50051
    CustomHeaderClient client = new CustomHeaderClient("localhost", 50051);
    try {
      String user = "world";
      // Use the arg as the name to greet if provided
      if (args.length > 0) {
        user = args[0]; 
      }
      client.greet(user);
    } finally {
      client.shutdown();
    }
  }

  private static List<String> names() {
    return Arrays.asList(
            "Sophia",
            "Jackson",
            "Emma",
            "Aiden",
            "Olivia",
            "Lucas",
            "Ava",
            "Liam",
            "Mia",
            "Noah",
            "Isabella",
            "Ethan",
            "Riley",
            "Mason",
            "Aria",
            "Caden",
            "Zoe",
            "Oliver",
            "Charlotte",
            "Elijah",
            "Lily",
            "Grayson",
            "Layla",
            "Jacob",
            "Amelia",
            "Michael",
            "Emily",
            "Benjamin",
            "Madelyn",
            "Carter",
            "Aubrey",
            "James",
            "Adalyn",
            "Jayden",
            "Madison",
            "Logan",
            "Chloe",
            "Alexander",
            "Harper",
            "Caleb",
            "Abigail",
            "Ryan",
            "Aaliyah",
            "Luke",
            "Avery",
            "Daniel",
            "Evelyn",
            "Jack",
            "Kaylee",
            "William",
            "Ella",
            "Owen",
            "Ellie",
            "Gabriel",
            "Scarlett",
            "Matthew",
            "Arianna",
            "Connor",
            "Hailey",
            "Jayce",
            "Nora",
            "Isaac",
            "Addison",
            "Sebastian",
            "Brooklyn",
            "Henry",
            "Hannah",
            "Muhammad",
            "Mila",
            "Cameron",
            "Leah",
            "Wyatt",
            "Elizabeth",
            "Dylan",
            "Sarah",
            "Nathan",
            "Eliana",
            "Nicholas",
            "Mackenzie",
            "Julian",
            "Peyton",
            "Eli",
            "Maria",
            "Levi",
            "Grace",
            "Isaiah",
            "Adeline",
            "Landon",
            "Elena",
            "David",
            "Anna",
            "Christian",
            "Victoria",
            "Andrew",
            "Camilla",
            "Brayden",
            "Lillian",
            "John",
            "Natalie",
            "Lincoln"
    );
  }
}
