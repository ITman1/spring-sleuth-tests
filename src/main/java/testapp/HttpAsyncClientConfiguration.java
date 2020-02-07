package testapp;

import brave.http.HttpTracing;
import brave.httpasyncclient.TracingHttpAsyncClientBuilder;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * Possible fix for Apache HTTP Async Client
 * Comment out abstract to test
 */
@Configuration
public abstract class HttpAsyncClientConfiguration {

    @Bean
    public HttpAsyncClientBuilder httpAsyncClientBuilderSupplier(HttpTracing httpTracing) {
        return new PatchedTracingHttpAsyncClientBuilder(TracingHttpAsyncClientBuilder.create(httpTracing));
    }
}
