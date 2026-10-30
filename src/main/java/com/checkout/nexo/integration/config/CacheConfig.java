package com.checkout.nexo.integration.config;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import com.checkout.nexo.checkout.port.CatalogPaymentMethod;
import com.checkout.nexo.checkout.port.CatalogProduct;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache do catalogo (CACHE-01 a CACHE-05). O {@code CacheManager} continua sendo o da
 * auto-configuracao do Boot, entao {@code spring.cache.type=none} desliga o cache e dispensa o
 * Redis (CACHE-05); aqui so se registram os dois caches, cada um com TTL de {@code app.cache.ttl}
 * e um serializer JSON tipado pelo proprio valor, sem default typing global.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig implements CachingConfigurer {

	public static final String PRODUCTS_CACHE = "catalog-products";

	public static final String PAYMENT_METHODS_CACHE = "catalog-payment-methods";

	private static final Logger LOG = LoggerFactory.getLogger(CacheConfig.class);

	/**
	 * As escritas sao imediatas: por padrao o Spring Data Redis 4.1 com Lettuce grava de forma
	 * assincrona, e uma falha do Redis num {@code put} nem chegaria ao {@link #errorHandler()}.
	 */
	@Bean
	RedisCacheManagerBuilderCustomizer catalogCaches(CacheProperties properties,
			RedisConnectionFactory connectionFactory) {
		JsonMapper mapper = JsonMapper.builder().build();
		return builder -> builder
				.cacheWriter(RedisCacheWriter.create(connectionFactory, writer -> writer.immediateWrites(true)))
				.withCacheConfiguration(PRODUCTS_CACHE,
						catalogCache(mapper, mapper.getTypeFactory().constructCollectionType(List.class,
								CatalogProduct.class), properties.ttl()))
				.withCacheConfiguration(PAYMENT_METHODS_CACHE,
						catalogCache(mapper, mapper.getTypeFactory().constructCollectionType(List.class,
								CatalogPaymentMethod.class), properties.ttl()));
	}

	/**
	 * Falha do Redis nunca derruba o pre-checkout (CACHE-03): o erro vira um WARN e a chamada segue
	 * como se o cache nao existisse. O log traz o nome do cache, a operacao e o tipo da excecao; nem
	 * a chave, nem o valor, nem a mensagem da excecao (pode trazer o endereco do Redis).
	 */
	@Override
	public CacheErrorHandler errorHandler() {
		return new WarnAndContinueErrorHandler();
	}

	private static RedisCacheConfiguration catalogCache(JsonMapper mapper, JavaType valueType, Duration ttl) {
		return RedisCacheConfiguration.defaultCacheConfig().entryTtl(ttl)
				.serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(mapper, valueType)));
	}

	private static final class WarnAndContinueErrorHandler implements CacheErrorHandler {

		@Override
		public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
			warn("get", cache, exception);
		}

		@Override
		public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
			warn("put", cache, exception);
		}

		@Override
		public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
			warn("evict", cache, exception);
		}

		@Override
		public void handleCacheClearError(RuntimeException exception, Cache cache) {
			warn("clear", cache, exception);
		}

		private static void warn(String operation, Cache cache, RuntimeException exception) {
			LOG.warn("Cache '{}' indisponivel na operacao {} ({}); seguindo sem cache", cache.getName(), operation,
					exception.getClass().getSimpleName());
		}

	}

}
