package io.grpc.examples.header;

import io.grpc.Context;

public class Constant {

    public static final Context.Key<String> TRACE_CTX = Context.key("traceparent");
}
