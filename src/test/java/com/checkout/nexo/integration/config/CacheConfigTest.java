package com.checkout.nexo.integration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import com.checkout.nexo.checkout.port.CatalogPaymentMethod;
import com.checkout.nexo.checkout.port.CatalogProduct;

/**
 * Configuracao do cache do catalogo (CACHE-02) e o tratador que nunca deixa uma falha do Redis
 * chegar ao pre-checkout (CACHE-03). Sem Docker: a conexao com o Redis e simulada, e o que se
 * observa e a configuracao do {@code RedisCacheManager} (nomes, TTL, serializer), nao o Redis.
 */
@ExtendWith(OutputCaptureExtension.class)
class CacheConfigTest {

	private static final String PRODUCTS_CACHE = "catalog-products";

	private static final String PAYMENT_METHODS_CACHE = "catalog-payment-methods";

	private static final List<CatalogProduct> PRODUCTS = List.of(
			new CatalogProduct("basico", "Plano Básico", "Assinatura mensal com o essencial do catálogo",
					new BigDecimal("19.90"), "BRL"),
			new CatalogProduct("premium", "Plano Premium", "Assinatura mensal com catálogo completo",
					new BigDecimal("39.90"), "BRL"),
			new CatalogProduct("familia", "Plano Família", "Assinatura mensal para até 4 perfis",
					new BigDecimal("59.90"), "BRL"));

	private static final List<CatalogPaymentMethod> PAYMENT_METHODS = List.of(
			new CatalogPaymentMethod("CREDIT_CARD", "Cartão de Crédito", true),
			new CatalogPaymentMethod("DEBIT_CARD", "Cartão de Débito", true),
			new CatalogPaymentMethod("PIX", "Pix", false));

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class))
			.withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
			.withUserConfiguration(CacheConfig.class);

	private static RedisCacheConfiguration configurationOf(RedisCacheManager cacheManager, String cacheName) {
		RedisCacheConfiguration configuration = cacheManager.getCacheConfigurations().get(cacheName);
		assertThat(configuration).as("configuracao do cache %s", cacheName).isNotNull();
		return configuration;
	}

	private static byte[] bytesOf(ByteBuffer buffer) {
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		return bytes;
	}

	// ---- Caches e TTL (CACHE-02) -------------------------------------------------------------

	@Test
	void theTwoCatalogCachesExistInTheRedisCacheManager() {
		runner.run(context -> {
			CacheManager cacheManager = context.getBean(CacheManager.class);

			assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
			assertThat(((RedisCacheManager) cacheManager).getCacheConfigurations()).containsOnlyKeys(PRODUCTS_CACHE,
					PAYMENT_METHODS_CACHE);
			assertThat(cacheManager.getCache(PRODUCTS_CACHE)).isNotNull();
			assertThat(cacheManager.getCache(PAYMENT_METHODS_CACHE)).isNotNull();
		});
	}

	@Test
	void theDefaultTtlOfBothCachesIsTenMinutes() {
		runner.run(context -> {
			RedisCacheManager cacheManager = context.getBean(RedisCacheManager.class);

			assertThat(configurationOf(cacheManager, PRODUCTS_CACHE).getTtlFunction().getTimeToLive("all", PRODUCTS))
					.isEqualTo(Duration.ofMinutes(10));
			assertThat(configurationOf(cacheManager, PAYMENT_METHODS_CACHE).getTtlFunction().getTimeToLive("all",
					PAYMENT_METHODS)).isEqualTo(Duration.ofMinutes(10));
		});
	}

	@Test
	void theTtlOfBothCachesFollowsTheAppCacheTtlProperty() {
		runner.withPropertyValues("app.cache.ttl=45s").run(context -> {
			RedisCacheManager cacheManager = context.getBean(RedisCacheManager.class);

			assertThat(configurationOf(cacheManager, PRODUCTS_CACHE).getTtlFunction().getTimeToLive("all", PRODUCTS))
					.isEqualTo(Duration.ofSeconds(45));
			assertThat(configurationOf(cacheManager, PAYMENT_METHODS_CACHE).getTtlFunction().getTimeToLive("all",
					PAYMENT_METHODS)).isEqualTo(Duration.ofSeconds(45));
		});
	}

	// ---- Serializer tipado por cache, sem default typing -------------------------------------

	@Test
	void theProductsCacheSerializerRoundTripsTheListOfCatalogProducts() {
		runner.run(context -> {
			RedisCacheConfiguration configuration = configurationOf(context.getBean(RedisCacheManager.class),
					PRODUCTS_CACHE);

			Object restored = configuration.getValueSerializationPair()
					.read(configuration.getValueSerializationPair().write(PRODUCTS));

			assertThat(restored).isEqualTo(PRODUCTS);
		});
	}

	@Test
	void thePaymentMethodsCacheSerializerRoundTripsTheListOfCatalogPaymentMethods() {
		runner.run(context -> {
			RedisCacheConfiguration configuration = configurationOf(context.getBean(RedisCacheManager.class),
					PAYMENT_METHODS_CACHE);

			Object restored = configuration.getValueSerializationPair()
					.read(configuration.getValueSerializationPair().write(PAYMENT_METHODS));

			assertThat(restored).isEqualTo(PAYMENT_METHODS);
		});
	}

	@Test
	void theStoredValueIsPlainJsonWithoutTypeInformation() {
		runner.run(context -> {
			RedisCacheConfiguration configuration = configurationOf(context.getBean(RedisCacheManager.class),
					PRODUCTS_CACHE);

			String stored = new String(bytesOf(configuration.getValueSerializationPair().write(PRODUCTS)),
					StandardCharsets.UTF_8);

			assertThat(stored).startsWith("[{\"slug\":\"basico\",\"name\":\"Plano Básico\"")
					.contains("\"price\":19.90").doesNotContain("@class").doesNotContain("@type");
		});
	}

	// ---- Tratador de erros (CACHE-03) --------------------------------------------------------

	private static CacheErrorHandler errorHandler() {
		return new CacheConfig().errorHandler();
	}

	private static final RuntimeException REDIS_DOWN = new IllegalStateException("redis-secret-host:6379 caiu");

	private static final Cache CACHE = new ConcurrentMapCache(PRODUCTS_CACHE);

	@Test
	void aFailedGetDoesNotThrowAndIsLoggedAsWarnWithTheCacheName(CapturedOutput output) {
		assertThatCode(() -> errorHandler().handleCacheGetError(REDIS_DOWN, CACHE, "chave-sensivel-1"))
				.doesNotThrowAnyException();

		assertThat(output.getOut()).contains("WARN").contains(PRODUCTS_CACHE)
				.doesNotContain("chave-sensivel-1").doesNotContain("redis-secret-host");
	}

	@Test
	void aFailedPutDoesNotThrowAndIsLoggedAsWarnWithTheCacheName(CapturedOutput output) {
		assertThatCode(() -> errorHandler().handleCachePutError(REDIS_DOWN, CACHE, "chave-sensivel-2", PRODUCTS))
				.doesNotThrowAnyException();

		assertThat(output.getOut()).contains("WARN").contains(PRODUCTS_CACHE)
				.doesNotContain("chave-sensivel-2").doesNotContain("Plano Premium");
	}

	@Test
	void aFailedEvictDoesNotThrowAndIsLoggedAsWarnWithTheCacheName(CapturedOutput output) {
		assertThatCode(() -> errorHandler().handleCacheEvictError(REDIS_DOWN, CACHE, "chave-sensivel-3"))
				.doesNotThrowAnyException();

		assertThat(output.getOut()).contains("WARN").contains(PRODUCTS_CACHE).doesNotContain("chave-sensivel-3");
	}

	@Test
	void aFailedClearDoesNotThrowAndIsLoggedAsWarnWithTheCacheName(CapturedOutput output) {
		assertThatCode(() -> errorHandler().handleCacheClearError(REDIS_DOWN, CACHE)).doesNotThrowAnyException();

		assertThat(output.getOut()).contains("WARN").contains(PRODUCTS_CACHE);
	}

	// ---- application.yaml: TTL e timeouts do Redis -------------------------------------------

	@Test
	void theApplicationYamlSetsATenMinuteTtlAndHalfSecondRedisTimeouts() {
		new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
				.run(context -> {
					Binder binder = Binder.get(context.getEnvironment());

					assertThat(binder.bind("app.cache.ttl", Duration.class).get()).isEqualTo(Duration.ofMinutes(10));
					assertThat(binder.bind("spring.data.redis.timeout", Duration.class).get())
							.isEqualTo(Duration.ofMillis(500));
					assertThat(binder.bind("spring.data.redis.connect-timeout", Duration.class).get())
							.isEqualTo(Duration.ofMillis(500));
				});
	}

}
