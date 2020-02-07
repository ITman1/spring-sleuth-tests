package testapp;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.propagation.TraceContext;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.util.concurrent.Future;

// TODO: Try to investigate deeper if this is necessary or fixed in next releases
public class PatchedTracingHttpAsyncClientBuilder extends HttpAsyncClientBuilder {
    private final HttpAsyncClientBuilder delegate;

    public PatchedTracingHttpAsyncClientBuilder(HttpAsyncClientBuilder delegate) {
        this.delegate = delegate;
    }

    @Override
    public CloseableHttpAsyncClient build() {
        CloseableHttpAsyncClient client = delegate.build();
        return new PatchedTracingHttpAsyncClient(client);
    }

    static class PatchedTracingHttpAsyncClient extends CloseableHttpAsyncClient {

        private final CloseableHttpAsyncClient delegate;

        PatchedTracingHttpAsyncClient(CloseableHttpAsyncClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public <T> Future<T> execute(HttpAsyncRequestProducer requestProducer, HttpAsyncResponseConsumer<T> responseConsumer, HttpContext context, FutureCallback<T> callback) {
            FutureCallback<T> patchedCallback = new FutureCallback<T>() {
                @Override
                public void completed(T t) {
                    rollbackContext(() -> callback.completed(t));
                }

                @Override
                public void failed(Exception e) {
                    rollbackContext(() -> callback.failed(e));
                }

                @Override
                public void cancelled() {
                    rollbackContext(callback::cancelled);
                }

                private void rollbackContext(Runnable runnable) {
                    TraceContext currentTraceContext = (TraceContext) context.getAttribute(TraceContext.class.getName());
                    if (currentTraceContext != null) { // Tracing was not active in calling thread
                        Span currentSpan = Tracing.currentTracer().toSpan(currentTraceContext);
                        try (Tracer.SpanInScope ignored = Tracing.currentTracer().withSpanInScope(currentSpan)) {
                            runnable.run();
                        }
                    } else {
                        runnable.run();
                    }
                }
            };

            return delegate.execute(requestProducer, responseConsumer, context, patchedCallback);
        }
    }
}
