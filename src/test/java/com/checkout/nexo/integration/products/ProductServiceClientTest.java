package com.checkout.nexo.integration.products;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.checkout.nexo.checkout.port.CatalogProduct;
import com.checkout.nexo.checkout.port.ExternalServiceUnavailableException;

@RestClientTest(ProductServiceClient.class)
@Import(ProductServiceClientTest.RestClientFromTheMockedBuilder.class)
class ProductServiceClientTest {

	private static final String BASE_URL = "http://products.test";

	private static final String PRODUCTS_URL = BASE_URL + "/mock-services/products";

	private static final String THREE_PRODUCTS = """
			[{"slug":"basico","name":"Plano Básico","description":"Assinatura mensal com o essencial do catálogo","price":19.90,"currency":"BRL"},
			 {"slug":"premium","name":"Plano Premium","description":"Assinatura mensal com catálogo completo","price":39.90,"currency":"BRL"},
			 {"slug":"familia","name":"Plano Família","description":"Assinatura mensal para até 4 perfis","price":59.90,"currency":"BRL"}]
			""";

	@Autowired
	private ProductServiceClient client;

	@Autowired
	private MockRestServiceServer server;

	/** O {@code RestClient} sai do builder que o {@code MockRestServiceServer} controla. */
	@TestConfiguration
	static class RestClientFromTheMockedBuilder {

		@Bean
		RestClient productsRestClient(RestClient.Builder builder) {
			return builder.baseUrl(BASE_URL).build();
		}

	}

	private static String productWithout(String omittedField) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("slug", "\"premium\"");
		fields.put("name", "\"Plano Premium\"");
		fields.put("description", "\"Assinatura mensal com catálogo completo\"");
		fields.put("price", "39.90");
		fields.put("currency", "\"BRL\"");
		fields.remove(omittedField);
		return fields.entrySet().stream().map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
				.collect(Collectors.joining(",", "[{", "}]"));
	}

	// ---- Mapeamento do contrato -------------------------------------------------------------

	@Test
	void aValidArrayBecomesTheCatalogProductsInTheSameOrder() {
		server.expect(requestTo(PRODUCTS_URL)).andRespond(withSuccess(THREE_PRODUCTS, MediaType.APPLICATION_JSON));

		List<CatalogProduct> products = client.findAll();

		assertThat(products).containsExactly(
				new CatalogProduct("basico", "Plano Básico", "Assinatura mensal com o essencial do catálogo",
						new BigDecimal("19.90"), "BRL"),
				new CatalogProduct("premium", "Plano Premium", "Assinatura mensal com catálogo completo",
						new BigDecimal("39.90"), "BRL"),
				new CatalogProduct("familia", "Plano Família", "Assinatura mensal para até 4 perfis",
						new BigDecimal("59.90"), "BRL"));
	}

	@Test
	void aPriceWithSmallerScaleIsNormalizedToScaleTwo() {
		server.expect(requestTo(PRODUCTS_URL)).andRespond(withSuccess(
				"[{\"slug\":\"premium\",\"name\":\"Plano Premium\",\"description\":\"d\",\"price\":39.9,\"currency\":\"BRL\"}]",
				MediaType.APPLICATION_JSON));

		List<CatalogProduct> products = client.findAll();

		assertThat(products).hasSize(1);
		assertThat(products.getFirst().price()).isEqualTo(new BigDecimal("39.90"));
	}

	@Test
	void anEmptyArrayBecomesAnEmptyList() {
		server.expect(requestTo(PRODUCTS_URL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		assertThat(client.findAll()).isEmpty();
	}

	@Test
	void theCallIsAGetToTheProductsPathOfTheBaseUrlExactlyOnce() {
		server.expect(ExpectedCount.once(), requestTo(PRODUCTS_URL)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		client.findAll();

		server.verify();
	}

	// ---- Falhas do servico (EXT-04) ---------------------------------------------------------

	@Test
	void aServerErrorBecomesExternalServiceUnavailableKeepingTheCause() {
		server.expect(requestTo(PRODUCTS_URL)).andRespond(withServerError());

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class)
				.hasCauseInstanceOf(HttpServerErrorException.class);
	}

	@Test
	void aNotFoundBecomesExternalServiceUnavailableKeepingTheCause() {
		server.expect(requestTo(PRODUCTS_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class)
				.hasCauseInstanceOf(HttpClientErrorException.class);
	}

	@Test
	void aTimeoutBecomesExternalServiceUnavailableKeepingTheCause() {
		server.expect(requestTo(PRODUCTS_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class)
				.hasCauseInstanceOf(ResourceAccessException.class);
	}

	// ---- Corpo fora do contrato (EXT-04) ----------------------------------------------------

	@Test
	void anEmptyBodyBecomesExternalServiceUnavailable() {
		server.expect(requestTo(PRODUCTS_URL)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class);
	}

	@Test
	void anInvalidJsonBecomesExternalServiceUnavailable() {
		server.expect(requestTo(PRODUCTS_URL)).andRespond(withSuccess("[{\"slug\":", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "slug", "name", "description", "price", "currency" })
	void anItemMissingARequiredFieldBecomesExternalServiceUnavailable(String missingField) {
		server.expect(requestTo(PRODUCTS_URL))
				.andRespond(withSuccess(productWithout(missingField), MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class);
	}

	@Test
	void aNullItemBecomesExternalServiceUnavailable() {
		server.expect(requestTo(PRODUCTS_URL)).andRespond(withSuccess("[null]", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class);
	}

	@Test
	void aPriceThatDoesNotFitScaleTwoBecomesExternalServiceUnavailable() {
		server.expect(requestTo(PRODUCTS_URL)).andRespond(withSuccess(
				"[{\"slug\":\"premium\",\"name\":\"Plano Premium\",\"description\":\"d\",\"price\":39.999,\"currency\":\"BRL\"}]",
				MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class);
	}

}
