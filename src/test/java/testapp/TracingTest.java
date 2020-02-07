package testapp;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.propagation.TraceContext;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.util.EntityUtils;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TracingTest {

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @Autowired
    private HttpClientBuilder httpClientBuilder;

    @Autowired
    private HttpAsyncClientBuilder httpAsyncClientBuilder;

    @LocalServerPort
    private int port;

    @Test
    public void webClient() {
        Span rootSpan = Tracing.currentTracer().newTrace().start();
        try (Tracer.SpanInScope ignored = Tracing.currentTracer().withSpanInScope(rootSpan)) {
            AtomicReference<String> spanIdInReactorThread = new AtomicReference<>();
            AtomicReference<String> spanIdInReactorContext = new AtomicReference<>();
            TraceContext contextBeforeCall = Tracing.currentTracer().currentSpan().context();
            String parentSpanId = webClientBuilder
                    .build()
                    .get()
                    .uri("http://localhost:" + port + "/parent-id")
                    .header("hcitrace", "some-hci-trace")
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnNext(response -> spanIdInReactorThread.set(Tracing.currentTracer().currentSpan().context().spanIdString()))
                    .flatMap(response -> Mono.subscriberContext()
                            .doOnNext(context -> spanIdInReactorContext.set(context.get(Span.class).context().spanIdString()))
                            .then(Mono.just(response)))
                    .block();
            TraceContext contextAfterCall = Tracing.currentTracer().currentSpan().context();

            Assert.assertEquals("Root Span ID in MVC Controller should be equal to span ID in test body", rootSpan.context().spanIdString(), parentSpanId);
            Assert.assertEquals("Span ID in Reactor thread should be the same as in test body", rootSpan.context().spanIdString(), spanIdInReactorThread.get());
            Assert.assertEquals("Span ID in Reactor Context should be the same as in test body", rootSpan.context().spanIdString(), spanIdInReactorContext.get());
            Assert.assertEquals("Trace context before and after HTTP blocking call should be the same", contextBeforeCall, contextAfterCall);
        } finally {
            rootSpan.finish();
        }
    }

    @Test
    public void restTemplate() {
        Span rootSpan = Tracing.currentTracer().newTrace().start();
        try (Tracer.SpanInScope ignored = Tracing.currentTracer().withSpanInScope(rootSpan)) {
            TraceContext contextBeforeCall = Tracing.currentTracer().currentSpan().context();
            String parentSpanId = restTemplateBuilder
                    .build()
                    .getForObject("http://localhost:" + port + "/parent-id", String.class);
            TraceContext contextAfterCall = Tracing.currentTracer().currentSpan().context();

            Assert.assertEquals("Root Span ID in MVC Controller should be equal to span ID in test body", rootSpan.context().spanIdString(), parentSpanId);
            Assert.assertEquals("Trace context before and after HTTP blocking call should be the same", contextBeforeCall, contextAfterCall);
        } finally {
            rootSpan.finish();
        }
    }

    @Test
    public void apacheHttpClient() throws IOException {
        Span rootSpan = Tracing.currentTracer().newTrace().start();
        try (Tracer.SpanInScope ignored = Tracing.currentTracer().withSpanInScope(rootSpan)) {
            TraceContext contextBeforeCall = Tracing.currentTracer().currentSpan().context();
            String parentSpanId = EntityUtils.toString(httpClientBuilder
                    .build()
                    .execute(new HttpGet("http://localhost:" + port + "/parent-id"))
                    .getEntity());
            TraceContext contextAfterCall = Tracing.currentTracer().currentSpan().context();

            Assert.assertEquals("Root Span ID in MVC Controller should be equal to span ID in test body", rootSpan.context().spanIdString(), parentSpanId);
            Assert.assertEquals("Trace context before and after HTTP blocking call should be the same", contextBeforeCall, contextAfterCall);
        } finally {
            rootSpan.finish();
        }
    }

    @Test
    public void apacheHttpAsyncClient() throws InterruptedException {
        Span rootSpan = Tracing.currentTracer().newTrace().start();
        try (Tracer.SpanInScope ignored = Tracing.currentTracer().withSpanInScope(rootSpan)) {
            CloseableHttpAsyncClient httpAsyncClient = httpAsyncClientBuilder.build();
            httpAsyncClient.start();
            CountDownLatch finishWaiter = new CountDownLatch(1);
            AtomicReference<String> response = new AtomicReference<>();
            AtomicReference<String> spanIdInCallback = new AtomicReference<>();
            TraceContext contextBeforeCall = Tracing.currentTracer().currentSpan().context();
            httpAsyncClient
                    .execute(new HttpGet("http://localhost:" + port + "/parent-id"), new FutureCallback<HttpResponse>() {
                        @Override
                        public void completed(HttpResponse httpResponse) {
                            try {
                                String responseString = EntityUtils.toString(httpResponse.getEntity());
                                response.set(responseString);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }

                            Span span = Tracing.currentTracer().currentSpan();
                            String spanId = span.context().spanIdString();
                            spanIdInCallback.set(spanId);
                            finishWaiter.countDown();
                        }

                        @Override
                        public void failed(Exception e) {
                        }

                        @Override
                        public void cancelled() {
                        }
                    });
            TraceContext contextAfterCall = Tracing.currentTracer().currentSpan().context();

            finishWaiter.await(10, TimeUnit.SECONDS);

            Assert.assertEquals("Root Span ID in MVC Controller should be equal to span ID in test body", rootSpan.context().spanIdString(), response.get());
            Assert.assertEquals("Span ID in ApacheClient callback should be the same as in test body", rootSpan.context().spanIdString(), spanIdInCallback.get());
            Assert.assertEquals("Trace context before and after HTTP NIO call should be the same", contextBeforeCall, contextAfterCall);
        } finally {
            rootSpan.finish();
        }
    }
}
