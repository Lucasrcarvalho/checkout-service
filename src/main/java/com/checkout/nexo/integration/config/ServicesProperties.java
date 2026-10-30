package com.checkout.nexo.integration.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Enderecos e timeouts dos servicos externos (EXT-03, EXT-04). Os timeouts valem para os dois
 * servicos; o padrao dos enderecos esta no {@code application.yaml}.
 *
 * @param products servico de produtos ({@code app.services.products.base-url})
 * @param paymentMethods servico de meios de pagamento ({@code app.services.payment-methods.base-url})
 * @param connectTimeout tempo maximo para conectar
 * @param readTimeout tempo maximo para receber a resposta
 */
@ConfigurationProperties(prefix = "app.services")
public record ServicesProperties(
		Service products,
		Service paymentMethods,
		@DefaultValue("1s") Duration connectTimeout,
		@DefaultValue("2s") Duration readTimeout) {

	public record Service(String baseUrl) {
	}

}
