package com.checkout.nexo.integration.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Um {@link RestClient} por servico externo, com timeouts explicitos (EXT-04). O cliente do JDK
 * fica em HTTP/1.1: o padrao (HTTP/2) tenta um upgrade h2c que o servidor local nao precisa.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServicesProperties.class)
public class RestClientConfig {

	@Bean
	RestClient productsRestClient(RestClient.Builder builder, ServicesProperties properties) {
		return restClient(builder, properties.products().baseUrl(), properties);
	}

	@Bean
	RestClient paymentMethodsRestClient(RestClient.Builder builder, ServicesProperties properties) {
		return restClient(builder, properties.paymentMethods().baseUrl(), properties);
	}

	static HttpClient httpClient(Duration connectTimeout) {
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build();
	}

	private static RestClient restClient(RestClient.Builder builder, String baseUrl, ServicesProperties properties) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				httpClient(properties.connectTimeout()));
		requestFactory.setReadTimeout(properties.readTimeout());
		return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
	}

}
