package io.grpc.examples.header;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;

public class Otel {

    static {
        //GlobalOpenTelemetry.set(OpenTelemetry.propagating(ContextPropagators.create(W3CTraceContextPropagator.getInstance())));
        tracer =  GlobalOpenTelemetry
                .getTracerProvider()
                .tracerBuilder("opentelemetry-event-tracing")
                .build();
    }

    public static final Tracer tracer;
}
