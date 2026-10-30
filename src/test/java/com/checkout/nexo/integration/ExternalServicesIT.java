package com.checkout.nexo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.checkout.nexo.AbstractIT;
import com.checkout.nexo.checkout.port.CatalogPaymentMethod;
import com.checkout.nexo.checkout.port.CatalogProduct;
import com.checkout.nexo.checkout.port.PaymentMethodCatalog;
import com.checkout.nexo.checkout.port.ProductCatalog;
import com.checkout.nexo.integration.config.ServicesProperties;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Os adaptadores contra os mocks, por HTTP real e sem nenhum override de profile (EXT-01, EXT-02,
 * EXT-03, EXT-05).
 */
class ExternalServicesIT extends AbstractIT {

	private static final String PRODUCTS_PATH = "/mock-services/products";

	private static final String PAYMENT_METHODS_PATH = "/mock-services/payment-methods";

	@Autowired
	private ProductCatalog productCatalog;

	@Autowired
	private PaymentMethodCatalog paymentMethodCatalog;

	@Autowired
	private MockServiceCallCounter calls;

	@Autowired
	private ServicesProperties services;

	@Autowired
	private Environment environment;

	@Autowired
	private JsonMapper jsonMapper;

	private final RestClient http = RestClient.create(baseUrl());

	@BeforeEach
	void resetTheCallCounter() {
		calls.reset();
	}

	private List<Map<String, Object>> asListOfObjects(String json) {
		return jsonMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
		});
	}

	// ---- Mocks no ar sem override de profile (EXT-05, EXT-02) --------------------------------

	@Test
	void theMockServicesProfileIsOnByDefault() {
		assertThat(environment.getActiveProfiles()).contains("mock-services");
	}

	@Test
	void theProductsMockAnswersOverHttpWithTheThreePlans() {
		ResponseEntity<String> response = http.get().uri(PRODUCTS_PATH).retrieve().toEntity(String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(asListOfObjects(response.getBody())).extracting(item -> item.get("slug"))
				.containsExactly("basico", "premium", "familia");
		assertThat(response.getBody()).contains("\"price\":19.90").contains("\"price\":39.90")
				.contains("\"price\":59.90");
	}

	@Test
	void thePaymentMethodsMockAnswersOverHttpWithTheThreeMethods() {
		ResponseEntity<String> response = http.get().uri(PAYMENT_METHODS_PATH).retrieve().toEntity(String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(asListOfObjects(response.getBody())).extracting(item -> item.get("code"))
				.containsExactly("CREDIT_CARD", "DEBIT_CARD", "PIX");
	}

	// ---- Adaptadores pelo caminho HTTP real (EXT-01, EXT-03) ---------------------------------

	@Test
	void theInjectedProductCatalogReturnsThePlansThroughARealHttpCall() {
		List<CatalogProduct> products = productCatalog.findAll();

		assertThat(products).containsExactly(
				new CatalogProduct("basico", "Plano Básico", "Assinatura mensal com o essencial do catálogo",
						new BigDecimal("19.90"), "BRL"),
				new CatalogProduct("premium", "Plano Premium", "Assinatura mensal com catálogo completo",
						new BigDecimal("39.90"), "BRL"),
				new CatalogProduct("familia", "Plano Família", "Assinatura mensal para até 4 perfis",
						new BigDecimal("59.90"), "BRL"));
		assertThat(calls.count(PRODUCTS_PATH)).isEqualTo(1);
		assertThat(calls.count(PAYMENT_METHODS_PATH)).isZero();
	}

	@Test
	void theInjectedPaymentMethodCatalogReturnsTheMethodsThroughARealHttpCall() {
		List<CatalogPaymentMethod> methods = paymentMethodCatalog.findAll();

		assertThat(methods).containsExactly(new CatalogPaymentMethod("CREDIT_CARD", "Cartão de Crédito", true),
				new CatalogPaymentMethod("DEBIT_CARD", "Cartão de Débito", true),
				new CatalogPaymentMethod("PIX", "Pix", false));
		assertThat(calls.count(PAYMENT_METHODS_PATH)).isEqualTo(1);
		assertThat(calls.count(PRODUCTS_PATH)).isZero();
	}

	@Test
	void theApplicationListensOnThePortThatTheBaseUrlsPointTo() {
		assertThat("http://localhost:" + environment.getProperty("local.server.port")).isEqualTo(baseUrl());
		assertThat(services.products().baseUrl()).isEqualTo(baseUrl());
		assertThat(services.paymentMethods().baseUrl()).isEqualTo(baseUrl());
	}

}
