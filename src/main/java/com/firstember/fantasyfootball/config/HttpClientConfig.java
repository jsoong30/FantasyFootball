package com.firstember.fantasyfootball.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Overrides Spring Boot's auto-detected RestTemplate backend. With no Apache HttpClient on the
 * classpath, Spring Boot defaults RestTemplateBuilder to the JDK's built-in java.net.http.HttpClient,
 * whose async connection layer does not reliably honor -Djava.net.preferIPv4Stack=true the way the
 * older HttpURLConnection-based client does. On some machines (confirmed on a real Windows dev
 * machine, not just a sandboxed environment) this surfaces as every outbound call to Sleeper/Fantasy
 * Football Calculator failing with java.nio.channels.UnresolvedAddressException, even though the
 * same host resolves fine via curl/browser. SimpleClientHttpRequestFactory sidesteps this entirely.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplateBuilder restTemplateBuilder() {
        return new RestTemplateBuilder()
                .requestFactory(SimpleClientHttpRequestFactory::new);
    }
}
