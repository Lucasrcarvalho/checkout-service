package com.checkout.nexo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.web.client.RestClient;

import com.checkout.nexo.AbstractIT.MockServiceCallCounter;
import com.checkout.nexo.TestcontainersConfiguration;

/**
 * CACHE-05: com {@code spring.cache.type=none} cada pre-checkout chama os servicos e a aplicacao
 * inicia sem Redis. E a unica excecao documentada ao contexto Spring unico dos {@code *IT}: o
 * contexto tem so PostgreSQL (nenhum container Redis) e a porta do Redis aponta para um endereco
 * onde nada escuta, entao qualquer uso do Redis falharia.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = "spring.cache.type=none")
@Import({ TestcontainersConfiguration.Postgres.class, CacheDisabledIT.MockServiceCalls.class })
@ExtendWith(OutputCaptureExtension.class)
class CacheDisabledIT {

	private static final int PORT = TestSocketUtils.findAvailableTcpPort();

	private static final int REDIS_PORT_WITH_NOTHING_LISTENING = otherFreePortThan(PORT);

	private static final String PRODUCTS_PATH = "/mock-services/products";

	private static final String PAYMENT_METHODS_PATH = "/mock-services/payment-methods";

	@DynamicPropertySource
	static void applicationAndRedisPorts(DynamicPropertyRegistry registry) {
		registry.add("server.port", () -> PORT);
		registry.add("app.services.products.base-url", () -> "http://localhost:" + PORT);
		registry.add("app.services.payment-methods.base-url", () -> "http://localhost:" + PORT);
		registry.add("spring.data.redis.port", () -> REDIS_PORT_WITH_NOTHING_LISTENING);
	}

	@Autowired
	private MockServiceCallCounter calls;

	@Autowired
	private CacheManager cacheManager;

	private final RestClient http = RestClient.builder().baseUrl("http://localhost:" + PORT)
			.requestFactory(new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();

	private static int otherFreePortThan(int port) {
		int candidate = TestSocketUtils.findAvailableTcpPort();
		while (candidate == port) {
			candidate = TestSocketUtils.findAvailableTcpPort();
		}
		return candidate;
	}

	private int preCheckoutStatus() {
		return http.post().uri("/operations/pre-checkout").contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
				.body("{\"products\":[{\"slug\":\"premium\"}]}").exchange((request, response) -> {
					new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
					return response.getStatusCode().value();
				});
	}

	@Test
	void everyPreCheckoutCallsBothServicesAndTheApplicationRunsWithoutRedis(CapturedOutput output) {
		calls.reset();

		assertThat(preCheckoutStatus()).isEqualTo(201);
		assertThat(preCheckoutStatus()).isEqualTo(201);

		assertThat(cacheManager).isInstanceOf(NoOpCacheManager.class);
		assertThat(calls.count(PRODUCTS_PATH)).isEqualTo(2);
		assertThat(calls.count(PAYMENT_METHODS_PATH)).isEqualTo(2);
		assertThat(output.getOut()).doesNotContainPattern("WARN.*CacheConfig");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MockServiceCalls {

		@Bean
		MockServiceCallCounter mockServiceCallCounter() {
			return new MockServiceCallCounter();
		}

	}

}
