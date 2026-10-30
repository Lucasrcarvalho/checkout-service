package com.checkout.nexo.integration.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracao do cache do catalogo (CACHE-02).
 *
 * @param ttl tempo de vida de cada entrada ({@code app.cache.ttl}, padrao 10 minutos)
 */
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(@DefaultValue("3m") Duration ttl) {
}
