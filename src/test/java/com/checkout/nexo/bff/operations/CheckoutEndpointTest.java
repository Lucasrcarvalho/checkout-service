package com.checkout.nexo.bff.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.checkout.nexo.bff.error.ErrorCatalog;
import com.checkout.nexo.bff.operations.dto.CardRequest;
import com.checkout.nexo.bff.operations.dto.CheckoutRequest;
import com.checkout.nexo.bff.operations.dto.PaymentMethodRequest;
import com.checkout.nexo.checkout.application.ApprovedCheckout;
import com.checkout.nexo.checkout.application.CheckoutCommand;
import com.checkout.nexo.checkout.application.CheckoutOutcome;
import com.checkout.nexo.checkout.application.CheckoutService;
import com.checkout.nexo.checkout.application.PreCheckoutService;
import com.checkout.nexo.checkout.domain.CardData;
import com.checkout.nexo.checkout.domain.CartItem;
import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.checkout.nexo.checkout.domain.CheckoutException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /operations/checkout} (CHK-01, CHK-02, CHK-05, CHK-06, CHK-08 a CHK-12, CHK-14,
 * ERR-03, DATA-03) com o servico simulado. Os JSONs esperados sao os literais do spec (CHK-01,
 * CHK-02 e a tabela de ERR-02), nao derivados do codigo.
 */
@WebMvcTest(controllers = OperationsController.class)
@Import(ErrorCatalog.class)
class CheckoutEndpointTest {

	private static final String URL = "/operations/checkout";

	private static final UUID CART_ID = UUID.fromString("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");

	private static final Instant PAID_AT = Instant.parse("2026-10-14T16:00:00Z");

	private static final CartItem PREMIUM = new CartItem("premium", "Plano Premium",
			"Assinatura mensal com catálogo completo", new BigDecimal("39.90"), "BRL");

	private static final CartItem BASICO = new CartItem("basico", "Plano Básico",
			"Assinatura mensal com o essencial do catálogo", new BigDecimal("19.90"), "BRL");

	private static final CartItem FAMILIA = new CartItem("familia", "Plano Família", "Assinatura mensal para até 4 perfis",
			new BigDecimal("59.90"), "BRL");

	private static final String CARD_JSON = "{\"holderName\":\"Ana Souza\",\"number\":\"4111111111114242\","
			+ "\"expiry\":\"12/28\",\"cvv\":\"123\"}";

	private static final CardData CARD = new CardData("Ana Souza", "4111111111114242", "12/28", "123");

	/** Sucesso com cartao de CHK-01 (carrinho com o plano premium). */
	private static final String CARD_SUCCESS_BODY = "{\"cartId\":\"7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33\","
			+ "\"status\":\"APROVADO\",\"paidAt\":\"2026-10-14T16:00:00Z\","
			+ "\"payment\":{\"method\":\"Cartão de Crédito\",\"lastDigits\":\"4242\",\"amount\":39.90,\"currency\":\"BRL\"},"
			+ "\"products\":[{\"slug\":\"premium\",\"name\":\"Plano Premium\",\"price\":39.90}]}";

	/** Sucesso com Pix de CHK-02: {@code pixCode} no lugar de {@code lastDigits}. */
	private static final String PIX_SUCCESS_BODY = "{\"cartId\":\"7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33\","
			+ "\"status\":\"APROVADO\",\"paidAt\":\"2026-10-14T16:00:00Z\","
			+ "\"payment\":{\"method\":\"Pix\",\"pixCode\":\"PIX-SIMULADO-7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33\","
			+ "\"amount\":39.90,\"currency\":\"BRL\"},"
			+ "\"products\":[{\"slug\":\"premium\",\"name\":\"Plano Premium\",\"price\":39.90}]}";

	private static final String REQUEST_CART_ID_JSON = "\"7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33\"";

	private static final String INVALID_REQUEST_JSON = error("BLOCK", "INVALID_REQUEST", "null", "R-1001",
			"Não foi possível processar o pedido",
			"Os dados enviados estão incompletos ou em formato inválido. Tente novamente.", "Tentar novamente",
			"RETRY", "null");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	/** Dependencia do mesmo controller; este teste nao a exercita. */
	@MockitoBean
	private PreCheckoutService preCheckoutService;

	@MockitoBean
	private CheckoutService checkoutService;

	/** Monta o corpo de erro de ERR-01 com os literais da tabela de ERR-02. */
	private static String error(String displayType, String errorCode, String cartIdJson, String code, String title,
			String description, String primaryLabel, String primaryUrl, String secondaryButtonJson) {
		return "{\"displayType\":\"" + displayType + "\",\"errorCode\":\"" + errorCode + "\",\"cartId\":" + cartIdJson
				+ ",\"frontendError\":{\"code\":\"" + code + "\",\"title\":\"" + title + "\",\"description\":\""
				+ description + "\",\"primaryButton\":{\"label\":\"" + primaryLabel + "\",\"url\":\"" + primaryUrl
				+ "\"},\"secondaryButton\":" + secondaryButtonJson + "}}";
	}

	private static String requestBody(String cartIdJson, String paymentMethodJson) {
		return "{\"cartId\":" + cartIdJson + ",\"paymentMethod\":" + paymentMethodJson + "}";
	}

	private static String creditRequestBody() {
		return requestBody(REQUEST_CART_ID_JSON, "{\"name\":\"Cartão de Crédito\",\"card\":" + CARD_JSON + "}");
	}

	private ResultActions postJson(String body) throws Exception {
		return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String bodyOf(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private static CheckoutOutcome premiumCardOutcome(Instant paidAt) {
		return new CheckoutOutcome.Approved(new ApprovedCheckout(CART_ID, paidAt, "Cartão de Crédito", "4242", null,
				new BigDecimal("39.90"), "BRL", List.of(PREMIUM)));
	}

	// ---- Sucesso com cartao (CHK-01) --------------------------------------------------------

	@Test
	void approvedCardIs200WithTheExactChk01BodyAndTheCommandBuiltFromTheRequest() throws Exception {
		when(checkoutService.checkout(new CheckoutCommand(CART_ID, "Cartão de Crédito", CARD)))
				.thenReturn(premiumCardOutcome(PAID_AT));

		ResultActions result = postJson(creditRequestBody()).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

		assertThat(bodyOf(result)).isEqualTo(CARD_SUCCESS_BODY);
	}

	@Test
	void theBodyAndItsNestedObjectsHaveExactlyTheContractKeysInOrderAndNoPixCodeKeyForCards() throws Exception {
		when(checkoutService.checkout(any())).thenReturn(premiumCardOutcome(PAID_AT));

		Map<String, Object> body = jsonMapper.readValue(bodyOf(postJson(creditRequestBody())),
				new TypeReference<Map<String, Object>>() {
				});

		assertThat(body.keySet()).containsExactly("cartId", "status", "paidAt", "payment", "products");
		assertThat(asObject(body.get("payment")).keySet()).containsExactly("method", "lastDigits", "amount",
				"currency");
		assertThat(asObjects(body.get("products"))).singleElement()
				.satisfies(product -> assertThat(product.keySet()).containsExactly("slug", "name", "price"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asObject(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> asObjects(Object value) {
		return (List<Map<String, Object>>) value;
	}

	@Test
	void theProductsComeInTheCartOrderWithTheirPricesInScaleTwo() throws Exception {
		when(checkoutService.checkout(any())).thenReturn(new CheckoutOutcome.Approved(new ApprovedCheckout(CART_ID,
				PAID_AT, "Cartão de Crédito", "4242", null, new BigDecimal("79.80"), "BRL", List.of(FAMILIA, BASICO))));

		String body = bodyOf(postJson(creditRequestBody()).andExpect(status().isOk()));

		assertThat(body).contains("\"products\":[{\"slug\":\"familia\",\"name\":\"Plano Família\",\"price\":59.90},"
				+ "{\"slug\":\"basico\",\"name\":\"Plano Básico\",\"price\":19.90}]").contains("\"amount\":79.80");
	}

	// ---- paidAt (CHK-05) --------------------------------------------------------------------

	@Test
	void paidAtWithAFractionIsTruncatedToSecondsBeforeFormatting() throws Exception {
		when(checkoutService.checkout(any())).thenReturn(premiumCardOutcome(Instant.parse("2026-10-14T16:00:00.987654Z")));

		String body = bodyOf(postJson(creditRequestBody()).andExpect(status().isOk()));

		assertThat(body).contains("\"paidAt\":\"2026-10-14T16:00:00Z\"");
	}

	// ---- Sucesso com Pix (CHK-02) -----------------------------------------------------------

	@Test
	void approvedPixHasThePixCodeInPlaceOfLastDigitsAndTheServiceReceivesNoCard() throws Exception {
		when(checkoutService.checkout(new CheckoutCommand(CART_ID, "Pix", null)))
				.thenReturn(new CheckoutOutcome.Approved(new ApprovedCheckout(CART_ID, PAID_AT, "Pix", null,
						"PIX-SIMULADO-" + CART_ID, new BigDecimal("39.90"), "BRL", List.of(PREMIUM))));

		ResultActions result = postJson(requestBody(REQUEST_CART_ID_JSON, "{\"name\":\"Pix\"}"))
				.andExpect(status().isOk());

		assertThat(bodyOf(result)).isEqualTo(PIX_SUCCESS_BODY).doesNotContain("lastDigits");
	}

	// ---- Recusa e erros de negocio (CHK-06, CHK-08 a CHK-12) --------------------------------

	@Test
	void aDeclinedOutcomeIs422WithTheExactPaymentDeclinedBodyAndTheRequestCartId() throws Exception {
		when(checkoutService.checkout(any())).thenReturn(new CheckoutOutcome.Declined(CART_ID));

		ResultActions result = postJson(creditRequestBody()).andExpect(status().isUnprocessableContent());

		assertThat(bodyOf(result)).isEqualTo(error("BLOCK", "PAYMENT_DECLINED", REQUEST_CART_ID_JSON, "P-1001",
				"Pagamento não aprovado",
				"Não foi possível concluir o pagamento. Tente novamente ou escolha outro meio.", "Tentar novamente",
				"RETRY", "{\"label\":\"Trocar meio de pagamento\",\"url\":\"/checkout/pagamento\"}"));
	}

	static Stream<Arguments> businessErrors() {
		return Stream.of(
				Arguments.of(CheckoutErrorCode.CART_NOT_FOUND, 404,
						error("BLOCK", "CART_NOT_FOUND", REQUEST_CART_ID_JSON, "C-1002", "Carrinho não encontrado",
								"Não encontramos este carrinho. Recomece o pedido para continuar.", "Recomeçar",
								"RESTART", "null")),
				Arguments.of(CheckoutErrorCode.CART_ALREADY_PAID, 409,
						error("BLOCK", "CART_ALREADY_PAID", REQUEST_CART_ID_JSON, "P-1004", "Pagamento já confirmado",
								"Este pedido já foi pago. Não é possível pagar novamente.", "Voltar ao início",
								"RESTART", "null")),
				Arguments.of(CheckoutErrorCode.PAYMENT_METHOD_NOT_ALLOWED, 422,
						error("INTERVENTION", "PAYMENT_METHOD_NOT_ALLOWED", REQUEST_CART_ID_JSON, "P-1003",
								"Meio de pagamento indisponível",
								"Este meio de pagamento não está disponível para o seu carrinho. Escolha outro meio.",
								"Escolher outro meio", "/checkout/pagamento", "null")),
				Arguments.of(CheckoutErrorCode.INVALID_PAYMENT_DATA, 422,
						error("INTERVENTION", "INVALID_PAYMENT_DATA", REQUEST_CART_ID_JSON, "P-1002",
								"Confira os dados do pagamento",
								"Alguns dados do pagamento estão incorretos. Corrija e tente novamente.",
								"Corrigir dados", "DISMISS", "null")));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("businessErrors")
	void aBusinessErrorFromTheServiceKeepsItsHttpStatusAndTheExactErr02BodyWithTheRequestCartId(
			CheckoutErrorCode code, int httpStatus, String expectedBody) throws Exception {
		when(checkoutService.checkout(any())).thenThrow(new CheckoutException(code, CART_ID));

		ResultActions result = postJson(creditRequestBody()).andExpect(status().is(httpStatus));

		assertThat(bodyOf(result)).isEqualTo(expectedBody);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("businessErrors")
	void aBusinessErrorWithoutItsOwnCartIdStillCarriesTheCartIdOfTheRequest(CheckoutErrorCode code, int httpStatus,
			String expectedBody) throws Exception {
		when(checkoutService.checkout(any())).thenThrow(new CheckoutException(code));

		ResultActions result = postJson(creditRequestBody()).andExpect(status().is(httpStatus));

		assertThat(bodyOf(result)).isEqualTo(expectedBody);
	}

	// ---- Corpo estruturalmente invalido (CHK-14) --------------------------------------------

	static Stream<Arguments> structurallyInvalidBodies() {
		String cartId = REQUEST_CART_ID_JSON;
		return Stream.of(
				Arguments.of("JSON malformado com cartId valido",
						"{\"cartId\":" + cartId + ",\"paymentMethod\":{\"name\": }"),
				Arguments.of("objeto vazio", "{}"),
				Arguments.of("cartId ausente", "{\"paymentMethod\":{\"name\":\"Pix\"}}"),
				Arguments.of("cartId nulo", requestBody("null", "{\"name\":\"Pix\"}")),
				Arguments.of("cartId que nao e UUID", requestBody("\"nao-e-um-uuid\"", "{\"name\":\"Pix\"}")),
				Arguments.of("cartId numerico", requestBody("12345", "{\"name\":\"Pix\"}")),
				Arguments.of("paymentMethod ausente", "{\"cartId\":" + cartId + "}"),
				Arguments.of("paymentMethod nulo", requestBody(cartId, "null")),
				Arguments.of("name ausente", requestBody(cartId, "{}")),
				Arguments.of("name nulo", requestBody(cartId, "{\"name\":null}")),
				Arguments.of("name vazio", requestBody(cartId, "{\"name\":\"\"}")),
				Arguments.of("name em branco", requestBody(cartId, "{\"name\":\"   \"}")));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("structurallyInvalidBodies")
	void aStructurallyInvalidBodyIs400InvalidRequestWithNullCartIdAndNeverReachesTheService(String description,
			String body) throws Exception {
		ResultActions result = postJson(body).andExpect(status().isBadRequest());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
		verifyNoInteractions(checkoutService);
	}

	@Test
	void aWrongContentTypeIs415InvalidRequestAndNeverReachesTheService() throws Exception {
		ResultActions result = mockMvc.perform(post(URL).contentType(MediaType.TEXT_PLAIN).content(creditRequestBody()))
				.andExpect(status().isUnsupportedMediaType());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
		verifyNoInteractions(checkoutService);
	}

	@Test
	void anUppercaseCartIdIsTheSameCartAsTheLowercaseOne() throws Exception {
		when(checkoutService.checkout(new CheckoutCommand(CART_ID, "Cartão de Crédito", CARD)))
				.thenReturn(premiumCardOutcome(PAID_AT));

		postJson(requestBody("\"" + CART_ID.toString().toUpperCase() + "\"",
				"{\"name\":\"Cartão de Crédito\",\"card\":" + CARD_JSON + "}")).andExpect(status().isOk());

		verify(checkoutService).checkout(new CheckoutCommand(CART_ID, "Cartão de Crédito", CARD));
	}

	// ---- cartId em UUID canonico estrito (CHK-14, F2) ---------------------------------------

	static Stream<String> nonCanonicalCartIds() {
		String canonical = CART_ID.toString();
		return Stream.of(
				"AAAAAAAAAAAAAAAAAAAAAA==",
				"1-1-1-1-1",
				" " + canonical,
				canonical + " ",
				canonical.substring(0, 35),
				canonical + "3",
				"g" + canonical.substring(1),
				canonical.replace("-", ""));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("nonCanonicalCartIds")
	void aCartIdThatIsNotACanonicalUuidIs400InvalidRequestWithNullCartIdAndNeverReachesTheService(String cartId)
			throws Exception {
		ResultActions result = postJson(requestBody("\"" + cartId + "\"", "{\"name\":\"Pix\"}"))
				.andExpect(status().isBadRequest());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
		verifyNoInteractions(checkoutService);
	}

	@Test
	void aMixedCaseCanonicalCartIdReachesTheServiceAsTheSameUuid() throws Exception {
		when(checkoutService.checkout(new CheckoutCommand(CART_ID, "Pix", null)))
				.thenReturn(premiumCardOutcome(PAID_AT));

		postJson(requestBody("\"7F3C2A10-5b9e-4D1C-8a46-0E2F9b7D1c33\"", "{\"name\":\"Pix\"}"))
				.andExpect(status().isOk());

		verify(checkoutService).checkout(new CheckoutCommand(CART_ID, "Pix", null));
	}

	// ---- O cartao nao tem Bean Validation (AD-008) ------------------------------------------

	@Test
	void anInvalidCardNumberReachesTheServiceInsteadOfBeingRejectedByTheController() throws Exception {
		CardData invalidCard = new CardData("Ana Souza", "num-invalido-7731", "12/28", "123");
		when(checkoutService.checkout(new CheckoutCommand(CART_ID, "Cartão de Crédito", invalidCard)))
				.thenThrow(new CheckoutException(CheckoutErrorCode.INVALID_PAYMENT_DATA, CART_ID));

		ResultActions result = postJson(requestBody(REQUEST_CART_ID_JSON, "{\"name\":\"Cartão de Crédito\",\"card\":"
				+ "{\"holderName\":\"Ana Souza\",\"number\":\"num-invalido-7731\",\"expiry\":\"12/28\",\"cvv\":\"123\"}}"))
				.andExpect(status().isUnprocessableContent());

		verify(checkoutService).checkout(new CheckoutCommand(CART_ID, "Cartão de Crédito", invalidCard));
		assertThat(bodyOf(result)).contains("\"errorCode\":\"INVALID_PAYMENT_DATA\"")
				.doesNotContain("num-invalido-7731");
	}

	@Test
	void aCardWithMissingFieldsAlsoReachesTheServiceWithNulls() throws Exception {
		CardData emptyCard = new CardData(null, null, null, null);
		when(checkoutService.checkout(new CheckoutCommand(CART_ID, "Cartão de Crédito", emptyCard)))
				.thenThrow(new CheckoutException(CheckoutErrorCode.INVALID_PAYMENT_DATA, CART_ID));

		postJson(requestBody(REQUEST_CART_ID_JSON, "{\"name\":\"Cartão de Crédito\",\"card\":{}}"))
				.andExpect(status().isUnprocessableContent());

		verify(checkoutService).checkout(new CheckoutCommand(CART_ID, "Cartão de Crédito", emptyCard));
	}

	@Test
	void cardRequestAndTheRequestsThatHoldItNeverRevealTheNumberTheCvvNorTheHolderInToString() {
		CardRequest card = new CardRequest("Fulana Distintiva", "4111111111114242", "11/29", "9876");
		CheckoutRequest request = new CheckoutRequest(CART_ID.toString(),
				new PaymentMethodRequest("Cartão de Crédito", card));

		assertThat(card.toString()).doesNotContainPattern("[0-9]").doesNotContain("Fulana").doesNotContain("Distintiva");
		assertThat(request.toString()).doesNotContain("4111111111114242").doesNotContain("4242").doesNotContain("9876")
				.doesNotContain("Fulana").doesNotContain("11/29");
	}

	// ---- cartId da requisicao em falhas que nao sao CheckoutException (ERR-03) --------------

	@Test
	void aQueryTimeoutIs503WithTheRequestCartId() throws Exception {
		when(checkoutService.checkout(any())).thenThrow(new QueryTimeoutException("timeout"));

		ResultActions result = postJson(creditRequestBody()).andExpect(status().isServiceUnavailable());

		assertThat(bodyOf(result)).isEqualTo(error("BLOCK", "SERVICE_UNAVAILABLE", REQUEST_CART_ID_JSON, "S-1001",
				"Serviço temporariamente indisponível", "Não conseguimos concluir agora. Tente novamente em instantes.",
				"Tentar novamente", "RETRY", "null"));
	}

	@Test
	void aPessimisticLockFailureIs503WithTheRequestCartId() throws Exception {
		when(checkoutService.checkout(any())).thenThrow(new PessimisticLockingFailureException("lock"));

		ResultActions result = postJson(creditRequestBody()).andExpect(status().isServiceUnavailable());

		assertThat(bodyOf(result)).contains("\"errorCode\":\"SERVICE_UNAVAILABLE\",\"cartId\":" + REQUEST_CART_ID_JSON);
	}

	@Test
	void anUnexpectedRuntimeExceptionIs500WithTheRequestCartIdAndNoMessage() throws Exception {
		when(checkoutService.checkout(any())).thenThrow(new RuntimeException("mensagem-interna-5e1a"));

		ResultActions result = postJson(creditRequestBody()).andExpect(status().isInternalServerError());

		assertThat(bodyOf(result)).isEqualTo(error("BLOCK", "INTERNAL_ERROR", REQUEST_CART_ID_JSON, "S-1002",
				"Algo deu errado", "Ocorreu um erro inesperado. Tente novamente.", "Tentar novamente", "RETRY", "null"))
				.doesNotContain("mensagem-interna-5e1a");
	}

}
