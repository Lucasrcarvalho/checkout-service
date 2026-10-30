package com.checkout.nexo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import com.checkout.nexo.AbstractIT;
import com.github.dockerjava.api.DockerClient;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache do catalogo contra Redis real e os servicos simulados por HTTP real (CACHE-01 a CACHE-04).
 * O CACHE-05 (cache desligado, sem Redis) precisa de outro contexto e esta em
 * {@link CacheDisabledIT}. Cada teste comeca com o cache vazio ({@code AbstractIT}); o Redis
 * pausado e sempre retomado no {@code finally}.
 */
@ExtendWith(OutputCaptureExtension.class)
class CacheIT extends AbstractIT {

	private static final String PRODUCTS_PATH = "/mock-services/products";

	private static final String PAYMENT_METHODS_PATH = "/mock-services/payment-methods";

	private static final String PRODUCTS_KEY = "catalog-products::all";

	private static final String PAYMENT_METHODS_KEY = "catalog-payment-methods::all";

	private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	private static final String PRE_CHECKOUT_BODY = "{\"products\":[{\"slug\":\"premium\"}]}";

	private record HttpResult(int status, String body) {
	}

	@Autowired
	private MockServiceCallCounter calls;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	@Qualifier("redisContainer")
	private GenericContainer<?> redisContainer;

	private final RestClient http = RestClient.builder().baseUrl(baseUrl())
			.requestFactory(new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();

	@BeforeEach
	void resetTheCallCounter() {
		calls.reset();
	}

	private HttpResult preCheckout() {
		return http.post().uri("/operations/pre-checkout").contentType(JSON_UTF8).body(PRE_CHECKOUT_BODY).exchange(
				(request, response) -> new HttpResult(response.getStatusCode().value(),
						new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
	}

	private Map<String, Object> asMap(String json) {
		return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {
		});
	}

	private long cartCount() {
		return jdbc.queryForObject("select count(*) from cart", Long.class);
	}

	// ---- CACHE-01, CACHE-02 ------------------------------------------------------------------

	@Test
	void twoPreCheckoutsInsideTheTtlCallEachServiceOnceAndAnswerTheSameCatalog() {
		assertThat(redis.hasKey(PRODUCTS_KEY)).isFalse();
		assertThat(redis.hasKey(PAYMENT_METHODS_KEY)).isFalse();

		HttpResult first = preCheckout();
		HttpResult second = preCheckout();

		assertThat(first.status()).isEqualTo(201);
		assertThat(second.status()).isEqualTo(201);
		assertThat(calls.count(PRODUCTS_PATH)).isEqualTo(1);
		assertThat(calls.count(PAYMENT_METHODS_PATH)).isEqualTo(1);
		assertThat(asMap(second.body()).get("products")).isEqualTo(asMap(first.body()).get("products"));
		assertThat(asMap(second.body()).get("paymentMethods")).isEqualTo(asMap(first.body()).get("paymentMethods"));
		assertThat(second.body()).contains("\"price\":39.90");
		assertThat(redis.hasKey(PRODUCTS_KEY)).isTrue();
		assertThat(redis.hasKey(PAYMENT_METHODS_KEY)).isTrue();
		assertThat(redis.getExpire(PRODUCTS_KEY, TimeUnit.SECONDS)).isBetween(590L, 600L);
		assertThat(redis.getExpire(PAYMENT_METHODS_KEY, TimeUnit.SECONDS)).isBetween(590L, 600L);
	}

	// ---- CACHE-04 e EXT-04 ponta a ponta -----------------------------------------------------

	@ParameterizedTest
	@CsvSource({ PRODUCTS_PATH + "," + PRODUCTS_KEY, PAYMENT_METHODS_PATH + "," + PAYMENT_METHODS_KEY })
	void aFailingServiceIsAnswered503WithoutACartAndTheFailureIsNotCached(String servicePath, String cacheKey) {
		long cartsBefore = cartCount();
		calls.failWith(servicePath, 503);

		HttpResult failed = preCheckout();

		assertThat(failed.status()).isEqualTo(503);
		assertThat(asMap(failed.body())).containsEntry("errorCode", "SERVICE_UNAVAILABLE").containsEntry("cartId",
				null);
		assertThat(cartCount()).isEqualTo(cartsBefore);
		assertThat(calls.count(servicePath)).isEqualTo(1);
		assertThat(redis.hasKey(cacheKey)).isFalse();

		calls.clearFaults();
		HttpResult recovered = preCheckout();

		assertThat(recovered.status()).isEqualTo(201);
		assertThat(calls.count(servicePath)).isEqualTo(2);
		assertThat(cartCount()).isEqualTo(cartsBefore + 1);
		assertThat(redis.hasKey(cacheKey)).isTrue();
	}

	// ---- CACHE-03 ----------------------------------------------------------------------------

	@Test
	void withRedisPausedThePreCheckoutStillAnswers201QuicklyWithAWarnAndTheCacheWorksAgainAfterTheUnpause(
			CapturedOutput output) {
		DockerClient docker = DockerClientFactory.instance().client();
		String redisId = redisContainer.getContainerId();

		docker.pauseContainerCmd(redisId).exec();
		try {
			long startedAt = System.nanoTime();
			HttpResult whileDown = preCheckout();
			Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

			assertThat(whileDown.status()).isEqualTo(201);
			assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
			assertThat(calls.count(PRODUCTS_PATH)).isEqualTo(1);
			assertThat(calls.count(PAYMENT_METHODS_PATH)).isEqualTo(1);
			assertThat(output.getOut()).containsPattern("WARN.*CacheConfig.*catalog-products.*get")
					.containsPattern("WARN.*CacheConfig.*catalog-payment-methods.*get")
					.containsPattern("WARN.*CacheConfig.*catalog-products.*put")
					.containsPattern("WARN.*CacheConfig.*catalog-payment-methods.*put");
		}
		finally {
			docker.unpauseContainerCmd(redisId).exec();
		}

		// Comandos gravados no socket durante a pausa podem chegar ao Redis depois do unpause:
		// parte-se de um cache vazio para provar que ele volta a funcionar.
		startFromAColdCatalogCacheWithoutInjectedFaults();
		calls.reset();
		HttpResult afterUnpause = preCheckout();
		HttpResult cached = preCheckout();

		assertThat(afterUnpause.status()).isEqualTo(201);
		assertThat(cached.status()).isEqualTo(201);
		assertThat(calls.count(PRODUCTS_PATH)).isEqualTo(1);
		assertThat(calls.count(PAYMENT_METHODS_PATH)).isEqualTo(1);
		assertThat(redis.hasKey(PRODUCTS_KEY)).isTrue();
	}

}
