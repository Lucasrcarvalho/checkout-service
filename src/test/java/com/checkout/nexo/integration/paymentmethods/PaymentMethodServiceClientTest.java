package com.checkout.nexo.integration.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.checkout.nexo.checkout.port.CatalogPaymentMethod;
import com.checkout.nexo.checkout.port.ExternalServiceUnavailableException;

@RestClientTest(PaymentMethodServiceClient.class)
@Import(PaymentMethodServiceClientTest.RestClientFromTheMockedBuilder.class)
class PaymentMethodServiceClientTest {

	private static final String BASE_URL = "http://payment-methods.test";

	private static final String PAYMENT_METHODS_URL = BASE_URL + "/mock-services/payment-methods";

	private static final String THREE_METHODS = """
			[{"code":"CREDIT_CARD","name":"Cartão de Crédito","requiresCard":true},
			 {"code":"DEBIT_CARD","name":"Cartão de Débito","requiresCard":true},
			 {"code":"PIX","name":"Pix","requiresCard":false}]
			""";

	@Autowired
	private PaymentMethodServiceClient client;

	@Autowired
	private MockRestServiceServer server;

	/** O {@code RestClient} sai do builder que o {@code MockRestServiceServer} controla. */
	@TestConfiguration
	static class RestClientFromTheMockedBuilder {

		@Bean
		RestClient paymentMethodsRestClient(RestClient.Builder builder) {
			return builder.baseUrl(BASE_URL).build();
		}

	}

	private static String methodWithout(String omittedField) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("code", "\"PIX\"");
		fields.put("name", "\"Pix\"");
		fields.put("requiresCard", "false");
		fields.remove(omittedField);
		return fields.entrySet().stream().map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
				.collect(Collectors.joining(",", "[{", "}]"));
	}

	// ---- Mapeamento do contrato -------------------------------------------------------------

	@Test
	void aValidArrayBecomesTheCatalogPaymentMethodsInTheSameOrder() {
		server.expect(requestTo(PAYMENT_METHODS_URL))
				.andRespond(withSuccess(THREE_METHODS, MediaType.APPLICATION_JSON));

		List<CatalogPaymentMethod> methods = client.findAll();

		assertThat(methods).containsExactly(new CatalogPaymentMethod("CREDIT_CARD", "Cartão de Crédito", true),
				new CatalogPaymentMethod("DEBIT_CARD", "Cartão de Débito", true),
				new CatalogPaymentMethod("PIX", "Pix", false));
	}

	@Test
	void theCallIsAGetToThePaymentMethodsPathOfTheBaseUrlExactlyOnce() {
		server.expect(ExpectedCount.once(), requestTo(PAYMENT_METHODS_URL)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		client.findAll();

		server.verify();
	}

	// ---- Falhas do servico (EXT-04) ---------------------------------------------------------

	@Test
	void aServerErrorBecomesExternalServiceUnavailableKeepingTheCause() {
		server.expect(requestTo(PAYMENT_METHODS_URL)).andRespond(withServerError());

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class)
				.hasCauseInstanceOf(HttpServerErrorException.class);
	}

	@Test
	void aTimeoutBecomesExternalServiceUnavailableKeepingTheCause() {
		server.expect(requestTo(PAYMENT_METHODS_URL))
				.andRespond(withException(new SocketTimeoutException("Read timed out")));

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class)
				.hasCauseInstanceOf(ResourceAccessException.class);
	}

	// ---- Corpo fora do contrato (EXT-04) ----------------------------------------------------

	@Test
	void anEmptyBodyBecomesExternalServiceUnavailable() {
		server.expect(requestTo(PAYMENT_METHODS_URL)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class);
	}

	@Test
	void anInvalidJsonBecomesExternalServiceUnavailable() {
		server.expect(requestTo(PAYMENT_METHODS_URL))
				.andRespond(withSuccess("[{\"code\":", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class);
	}

	/** {@code requiresCard} ausente nao pode virar {@code false} em silencio. */
	@ParameterizedTest
	@ValueSource(strings = { "code", "name", "requiresCard" })
	void anItemMissingARequiredFieldBecomesExternalServiceUnavailable(String missingField) {
		server.expect(requestTo(PAYMENT_METHODS_URL))
				.andRespond(withSuccess(methodWithout(missingField), MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class);
	}

	@Test
	void aNullItemBecomesExternalServiceUnavailable() {
		server.expect(requestTo(PAYMENT_METHODS_URL)).andRespond(withSuccess("[null]", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.findAll()).isInstanceOf(ExternalServiceUnavailableException.class);
	}

}
