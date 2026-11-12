package com.hic.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    @Primary
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            @Value("${isapi.api-key:}") String isapiApiKey,
            @Value("${isapi.base-url}") String isapiBaseUrl) {
        return bridgeClient(builder, isapiApiKey, isapiBaseUrl, null);
    }

    @Bean
    public RestTemplate anprRestTemplate(RestTemplateBuilder builder,
            @Value("${isapi.api-key:}") String isapiApiKey,
            @Value("${isapi.base-url}") String isapiBaseUrl) {
        return bridgeClient(builder, isapiApiKey, isapiBaseUrl, Duration.ofMinutes(5));
    }

    @Bean
    public RestTemplate anprCapabilitiesRestTemplate(RestTemplateBuilder builder,
            @Value("${isapi.api-key:}") String isapiApiKey,
            @Value("${isapi.base-url}") String isapiBaseUrl) {
        return bridgeClient(builder, isapiApiKey, isapiBaseUrl, Duration.ofSeconds(75));
    }

    @Bean
    public RestTemplate anprEventsRestTemplate(RestTemplateBuilder builder,
            @Value("${isapi.api-key:}") String isapiApiKey,
            @Value("${isapi.base-url}") String isapiBaseUrl) {
        return bridgeClient(builder, isapiApiKey, isapiBaseUrl, Duration.ofSeconds(10));
    }

    private RestTemplate bridgeClient(RestTemplateBuilder builder, String isapiApiKey, String isapiBaseUrl,
                                      Duration readTimeout) {
        URI bridge = URI.create(isapiBaseUrl);
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(10)).build());
        if (readTimeout != null) factory.setReadTimeout(readTimeout);
        RestTemplate restTemplate = builder.requestFactory(() -> factory).build();
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(restTemplate.getInterceptors());
        interceptors.add((request, body, execution) -> {
            // The legacy terminal fallback shares this client. Never give a terminal the bridge key.
            request.getHeaders().remove("X-API-Key");
            if (StringUtils.hasText(isapiApiKey) && sameOrigin(bridge, request.getURI())) {
                request.getHeaders().set("X-API-Key", isapiApiKey);
            }
            return execution.execute(request, body);
        });
        restTemplate.setInterceptors(interceptors);
        return restTemplate;
    }

    private boolean sameOrigin(URI trusted, URI requested) {
        return trusted.getHost() != null && requested.getHost() != null
                && requested.getRawUserInfo() == null
                && trusted.getScheme().equalsIgnoreCase(requested.getScheme())
                && trusted.getHost().equalsIgnoreCase(requested.getHost())
                && port(trusted) == port(requested);
    }

    private int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
