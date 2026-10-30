package com.checkout.nexo.bff.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Origens do navegador autorizadas a chamar {@code /operations/**} (CORS-01).
 *
 * @param allowedOrigins {@code app.cors.allowed-origins}, padrao o frontend Angular local
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(@DefaultValue("http://localhost:4200") List<String> allowedOrigins) {
}
