package com.checkout.nexo.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import com.checkout.nexo.AbstractIT;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fluxo ponta a ponta contra PostgreSQL real e os servicos simulados por HTTP real (PRE-01, PRE-02,
 * PRE-05, CHK-01 a CHK-11, DUP-01 sequencial). Cada cenario cria o proprio carrinho pelo
 * pre-checkout e nenhum depende de ordem de execucao. O estado final e lido do banco por SQL, nao
 * pelos repositorios nem por espioes.
 */
class CheckoutFlowIT extends AbstractIT {

	private static final String PRODUCTS_PATH = "/mock-services/products";

	private static final String PAYMENT_METHODS_PATH = "/mock-services/payment-methods";

	private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	private static final String VALID_NUMBER = "4111111111114242";

	private static final String DECLINED_NUMBER = "4111111111110002";

	private static final String PREMIUM_PRODUCTS_JSON = "[{\"slug\":\"premium\",\"name\":\"Plano Premium\","
			+ "\"description\":\"Assinatura mensal com catálogo completo\",\"price\":39.90,\"currency\":\"BRL\"}]";

	private static final String METHOD_NAMES_JSON = "[{\"name\":\"Cartão de Crédito\"},{\"name\":\"Cartão de Débito\"},"
			+ "{\"name\":\"Pix\"}]";

	private static final String ISO_INSTANT_SECONDS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z";

	private record HttpResult(int status, String body) {
	}

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private MockServiceCallCounter calls;

	private final RestClient http = RestClient.builder().baseUrl(baseUrl())
			.requestFactory(new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();

	@BeforeEach
	void resetTheCallCounter() {
		calls.reset();
	}

	// ---- HTTP -------------------------------------------------------------------------------

	private HttpResult post(String path, String json) {
		return http.post().uri(path).contentType(JSON_UTF8).body(json).exchange(
				(request, response) -> new HttpResult(response.getStatusCode().value(),
						new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
	}

	private HttpResult preCheckoutRequest(String... slugs) {
		String items = String.join(",", Arrays.stream(slugs).map(slug -> "{\"slug\":\"" + slug + "\"}").toList());
		return post("/operations/pre-checkout", "{\"products\":[" + items + "]}");
	}

	/** Pre-checkout que deve dar certo; devolve o cartId do carrinho novo. */
	private UUID createCart(String... slugs) {
		HttpResult result = preCheckoutRequest(slugs);
		assertThat(result.status()).isEqualTo(201);
		return UUID.fromString((String) asMap(result.body()).get("cartId"));
	}

	private HttpResult checkoutRequest(String cartId, String methodName, String cardJson) {
		String method = "{\"name\":\"" + methodName + "\"" + (cardJson == null ? "" : ",\"card\":" + cardJson) + "}";
		return post("/operations/checkout", "{\"cartId\":\"" + cartId + "\",\"paymentMethod\":" + method + "}");
	}

	private HttpResult checkoutRequest(UUID cartId, String methodName, String cardJson) {
		return checkoutRequest(cartId.toString(), methodName, cardJson);
	}

	private static String cardJson(String number) {
		return "{\"holderName\":\"Ana Souza\",\"number\":\"" + number + "\",\"expiry\":\"12/99\",\"cvv\":\"123\"}";
	}

	private Map<String, Object> asMap(String json) {
		return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {
		});
	}

	/** Monta o corpo de erro de ERR-01 com os literais da tabela de ERR-02. */
	private static String error(String displayType, String errorCode, String cartIdJson, String code, String title,
			String description, String primaryLabel, String primaryUrl, String secondaryButtonJson) {
		return "{\"displayType\":\"" + displayType + "\",\"errorCode\":\"" + errorCode + "\",\"cartId\":" + cartIdJson
				+ ",\"frontendError\":{\"code\":\"" + code + "\",\"title\":\"" + title + "\",\"description\":\""
				+ description + "\",\"primaryButton\":{\"label\":\"" + primaryLabel + "\",\"url\":\"" + primaryUrl
				+ "\"},\"secondaryButton\":" + secondaryButtonJson + "}}";
	}

	// ---- Estado do banco (SQL puro) ---------------------------------------------------------

	private Map<String, Object> cartRow(UUID cartId) {
		return jdbc.queryForMap(
				"select status, total_amount, currency, created_at, paid_at from cart where id = ?", cartId);
	}

	private List<Map<String, Object>> attemptsOf(UUID cartId) {
		return jdbc.queryForList("select status, method_code, last_digits, pix_code, amount, currency "
				+ "from payment_attempt where cart_id = ? order by id", cartId);
	}

	private long cartCount() {
		return jdbc.queryForObject("select count(*) from cart", Long.class);
	}

	// ---- Pre-checkout (PRE-01, PRE-02, PRE-05) ----------------------------------------------

	@Test
	void preCheckoutRespondsWith201AndTheExactPre01Body() {
		HttpResult result = preCheckoutRequest("premium");

		assertThat(result.status()).isEqualTo(201);
		UUID cartId = UUID.fromString((String) asMap(result.body()).get("cartId"));
		assertThat(result.body()).isEqualTo("{\"cartId\":\"" + cartId + "\",\"products\":" + PREMIUM_PRODUCTS_JSON
				+ ",\"paymentMethods\":" + METHOD_NAMES_JSON + "}");
	}

	@Test
	void preCheckoutPersistsTheOpenCartWithTheSnapshotInTheRequestOrderAndTheTotal() {
		Instant before = Instant.now();

		HttpResult result = preCheckoutRequest("familia", "basico");

		Instant after = Instant.now();
		assertThat(result.status()).isEqualTo(201);
		Map<String, Object> body = asMap(result.body());
		UUID cartId = UUID.fromString((String) body.get("cartId"));
		assertThat(result.body()).contains("\"products\":[{\"slug\":\"familia\",\"name\":\"Plano Família\","
				+ "\"description\":\"Assinatura mensal para até 4 perfis\",\"price\":59.90,\"currency\":\"BRL\"},"
				+ "{\"slug\":\"basico\"");

		Map<String, Object> cart = cartRow(cartId);
		assertThat(cart.get("status")).isEqualTo("OPEN");
		assertThat(cart.get("total_amount")).isEqualTo(new BigDecimal("79.80"));
		assertThat(cart.get("currency")).isEqualTo("BRL");
		assertThat(cart.get("paid_at")).isNull();
		assertThat(((Timestamp) cart.get("created_at")).toInstant()).isBetween(before.minusSeconds(1),
				after.plusSeconds(1));
		assertThat(jdbc.queryForList("select line_no, slug, name, description, unit_price, currency from cart_item "
				+ "where cart_id = ? order by line_no", cartId)).hasSize(2)
				.satisfiesExactly(
						first -> {
							assertThat(first.get("line_no")).isEqualTo(0);
							assertThat(first.get("slug")).isEqualTo("familia");
							assertThat(first.get("name")).isEqualTo("Plano Família");
							assertThat(first.get("description")).isEqualTo("Assinatura mensal para até 4 perfis");
							assertThat(first.get("unit_price")).isEqualTo(new BigDecimal("59.90"));
							assertThat(first.get("currency")).isEqualTo("BRL");
						},
						second -> {
							assertThat(second.get("line_no")).isEqualTo(1);
							assertThat(second.get("slug")).isEqualTo("basico");
							assertThat(second.get("unit_price")).isEqualTo(new BigDecimal("19.90"));
						});
		assertThat(jdbc.queryForList("select code, name, requires_card from cart_payment_method where cart_id = ? "
				+ "order by code", cartId)).extracting(row -> row.get("code") + "|" + row.get("name") + "|"
						+ row.get("requires_card"))
				.containsExactly("CREDIT_CARD|Cartão de Crédito|true", "DEBIT_CARD|Cartão de Débito|true",
						"PIX|Pix|false");
		assertThat(attemptsOf(cartId)).isEmpty();
	}

	@Test
	void anUnknownSlugIs422ProductNotFoundWithNullCartIdAndNoCartIsCreated() {
		long cartsBefore = cartCount();

		HttpResult result = preCheckoutRequest("premium", "slug-inexistente-4f7a");

		assertThat(result.status()).isEqualTo(422);
		assertThat(result.body()).isEqualTo(error("BLOCK", "PRODUCT_NOT_FOUND", "null", "C-1001", "Plano indisponível",
				"Um dos planos escolhidos não está disponível. Escolha outro plano para continuar.",
				"Escolher outro plano", "RESTART", "null"));
		assertThat(cartCount()).isEqualTo(cartsBefore);
	}

	// ---- Aprovado com cartao (CHK-01, CHK-03, CHK-05) ---------------------------------------

	@Test
	void approvedCardRespondsWithTheChk01BodyAndLeavesTheCartApprovedWithOneApprovedAttempt() {
		UUID cartId = createCart("premium");
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		HttpResult result = checkoutRequest(cartId, "Cartão de Crédito", cardJson(VALID_NUMBER));

		Instant after = Instant.now();
		assertThat(result.status()).isEqualTo(200);
		String paidAtText = (String) asMap(result.body()).get("paidAt");
		assertThat(paidAtText).matches(ISO_INSTANT_SECONDS);
		Instant paidAt = Instant.parse(paidAtText);
		assertThat(paidAt).isBetween(before, after);
		assertThat(result.body()).isEqualTo("{\"cartId\":\"" + cartId + "\",\"status\":\"APROVADO\",\"paidAt\":\""
				+ paidAtText + "\",\"payment\":{\"method\":\"Cartão de Crédito\",\"lastDigits\":\"4242\","
				+ "\"amount\":39.90,\"currency\":\"BRL\"},\"products\":[{\"slug\":\"premium\","
				+ "\"name\":\"Plano Premium\",\"price\":39.90}]}");

		Map<String, Object> cart = cartRow(cartId);
		assertThat(cart.get("status")).isEqualTo("APPROVED");
		assertThat(((Timestamp) cart.get("paid_at")).toInstant().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(paidAt);
		assertThat(attemptsOf(cartId)).singleElement().satisfies(attempt -> {
			assertThat(attempt.get("status")).isEqualTo("APPROVED");
			assertThat(attempt.get("method_code")).isEqualTo("CREDIT_CARD");
			assertThat(attempt.get("last_digits")).isEqualTo("4242");
			assertThat(attempt.get("pix_code")).isNull();
			assertThat(attempt.get("amount")).isEqualTo(new BigDecimal("39.90"));
			assertThat(attempt.get("currency")).isEqualTo("BRL");
		});
	}

	@Test
	void anUppercaseCartIdIsTheSameCartAndTheDebitMethodIsApprovedWithItsOwnCode() {
		UUID cartId = createCart("basico");

		HttpResult result = checkoutRequest(cartId.toString().toUpperCase(), "Cartão de Débito",
				cardJson("5555555555554444"));

		assertThat(result.status()).isEqualTo(200);
		assertThat(result.body()).contains("\"cartId\":\"" + cartId + "\"").contains("\"lastDigits\":\"4444\"");
		assertThat(cartRow(cartId).get("status")).isEqualTo("APPROVED");
		assertThat(attemptsOf(cartId)).singleElement()
				.satisfies(attempt -> assertThat(attempt.get("method_code")).isEqualTo("DEBIT_CARD"));
	}

	// ---- Aprovado com Pix (CHK-02) ----------------------------------------------------------

	@Test
	void approvedPixCarriesThePixCodeInTheResponseAndInTheAttemptAndNoLastDigits() {
		UUID cartId = createCart("premium");

		HttpResult result = checkoutRequest(cartId, "Pix", null);

		assertThat(result.status()).isEqualTo(200);
		Map<String, Object> body = asMap(result.body());
		assertThat(body.get("status")).isEqualTo("APROVADO");
		assertThat(result.body()).contains("\"payment\":{\"method\":\"Pix\",\"pixCode\":\"PIX-SIMULADO-" + cartId
				+ "\",\"amount\":39.90,\"currency\":\"BRL\"}").doesNotContain("lastDigits");
		assertThat(cartRow(cartId).get("status")).isEqualTo("APPROVED");
		assertThat(attemptsOf(cartId)).singleElement().satisfies(attempt -> {
			assertThat(attempt.get("status")).isEqualTo("APPROVED");
			assertThat(attempt.get("method_code")).isEqualTo("PIX");
			assertThat(attempt.get("pix_code")).isEqualTo("PIX-SIMULADO-" + cartId);
			assertThat(attempt.get("last_digits")).isNull();
		});
	}

	// ---- Recusa e nova tentativa (CHK-06, CHK-07) -------------------------------------------

	@Test
	void aDeclinedCardIs422KeepsTheCartOpenAndRecordsADeclinedAttemptThenANewCardIsApproved() {
		UUID cartId = createCart("premium");

		HttpResult declined = checkoutRequest(cartId, "Cartão de Crédito", cardJson(DECLINED_NUMBER));

		assertThat(declined.status()).isEqualTo(422);
		assertThat(declined.body()).isEqualTo(error("BLOCK", "PAYMENT_DECLINED", "\"" + cartId + "\"", "P-1001",
				"Pagamento não aprovado",
				"Não foi possível concluir o pagamento. Tente novamente ou escolha outro meio.", "Tentar novamente",
				"RETRY", "{\"label\":\"Trocar meio de pagamento\",\"url\":\"/checkout/pagamento\"}"));
		Map<String, Object> cart = cartRow(cartId);
		assertThat(cart.get("status")).isEqualTo("OPEN");
		assertThat(cart.get("paid_at")).isNull();
		assertThat(attemptsOf(cartId)).singleElement().satisfies(attempt -> {
			assertThat(attempt.get("status")).isEqualTo("DECLINED");
			assertThat(attempt.get("last_digits")).isEqualTo("0002");
			assertThat(attempt.get("pix_code")).isNull();
		});

		HttpResult approved = checkoutRequest(cartId, "Cartão de Crédito", cardJson(VALID_NUMBER));

		assertThat(approved.status()).isEqualTo(200);
		assertThat(cartRow(cartId).get("status")).isEqualTo("APPROVED");
		assertThat(attemptsOf(cartId)).extracting(attempt -> attempt.get("status") + "|" + attempt.get("last_digits"))
				.containsExactly("DECLINED|0002", "APPROVED|4242");
	}

	@Test
	void repeatingADeclinedCheckoutDeclinesAgainAndRecordsAnotherDeclinedAttempt() {
		UUID cartId = createCart("premium");

		assertThat(checkoutRequest(cartId, "Cartão de Débito", cardJson(DECLINED_NUMBER)).status()).isEqualTo(422);
		assertThat(checkoutRequest(cartId, "Cartão de Débito", cardJson(DECLINED_NUMBER)).status()).isEqualTo(422);

		assertThat(cartRow(cartId).get("status")).isEqualTo("OPEN");
		assertThat(attemptsOf(cartId)).extracting(attempt -> attempt.get("status")).containsExactly("DECLINED",
				"DECLINED");
	}

	// ---- Carrinho ja pago (CHK-09, DUP-01 sequencial) ---------------------------------------

	@Test
	void aSecondCheckoutOnAnApprovedCartIs409AndRecordsNoNewAttempt() {
		UUID cartId = createCart("premium");
		assertThat(checkoutRequest(cartId, "Cartão de Crédito", cardJson(VALID_NUMBER)).status()).isEqualTo(200);

		HttpResult sameBody = checkoutRequest(cartId, "Cartão de Crédito", cardJson(VALID_NUMBER));
		HttpResult otherMethod = checkoutRequest(cartId, "Pix", null);

		String expected = error("BLOCK", "CART_ALREADY_PAID", "\"" + cartId + "\"", "P-1004", "Pagamento já confirmado",
				"Este pedido já foi pago. Não é possível pagar novamente.", "Voltar ao início", "RESTART", "null");
		assertThat(sameBody.status()).isEqualTo(409);
		assertThat(sameBody.body()).isEqualTo(expected);
		assertThat(otherMethod.status()).isEqualTo(409);
		assertThat(otherMethod.body()).isEqualTo(expected);
		assertThat(attemptsOf(cartId)).singleElement()
				.satisfies(attempt -> assertThat(attempt.get("status")).isEqualTo("APPROVED"));
	}

	// ---- Carrinho inexistente (CHK-08) ------------------------------------------------------

	@Test
	void anUnknownCartIs404CartNotFoundWithTheRequestCartIdAndNothingIsRecorded() {
		UUID unknown = UUID.randomUUID();
		long cartsBefore = cartCount();

		HttpResult result = checkoutRequest(unknown, "Cartão de Crédito", cardJson(VALID_NUMBER));

		assertThat(result.status()).isEqualTo(404);
		assertThat(result.body()).isEqualTo(error("BLOCK", "CART_NOT_FOUND", "\"" + unknown + "\"", "C-1002",
				"Carrinho não encontrado", "Não encontramos este carrinho. Recomece o pedido para continuar.",
				"Recomeçar", "RESTART", "null"));
		assertThat(attemptsOf(unknown)).isEmpty();
		assertThat(cartCount()).isEqualTo(cartsBefore);
	}

	// ---- Meio fora do snapshot (CHK-10) -----------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "Boleto", "cartão de crédito" })
	void aMethodOutsideTheSnapshotIs422InterventionAndNothingIsRecorded(String methodName) {
		UUID cartId = createCart("premium");

		HttpResult result = checkoutRequest(cartId, methodName, cardJson(VALID_NUMBER));

		assertThat(result.status()).isEqualTo(422);
		assertThat(result.body()).isEqualTo(error("INTERVENTION", "PAYMENT_METHOD_NOT_ALLOWED", "\"" + cartId + "\"",
				"P-1003", "Meio de pagamento indisponível",
				"Este meio de pagamento não está disponível para o seu carrinho. Escolha outro meio.",
				"Escolher outro meio", "/checkout/pagamento", "null"));
		assertThat(cartRow(cartId).get("status")).isEqualTo("OPEN");
		assertThat(attemptsOf(cartId)).isEmpty();
	}

	// ---- Dados do pagamento (CHK-11, CHK-12) ------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "4111 1111 1111 4242", "411111111111", "41111111111142420000", "4111-1111-1111-4242" })
	void anInvalidCardNumberIs422InvalidPaymentDataAndNothingIsRecorded(String number) {
		UUID cartId = createCart("premium");

		HttpResult result = checkoutRequest(cartId, "Cartão de Crédito", cardJson(number));

		assertThat(result.status()).isEqualTo(422);
		assertThat(result.body()).isEqualTo(error("INTERVENTION", "INVALID_PAYMENT_DATA", "\"" + cartId + "\"",
				"P-1002", "Confira os dados do pagamento",
				"Alguns dados do pagamento estão incorretos. Corrija e tente novamente.", "Corrigir dados", "DISMISS",
				"null"));
		assertThat(cartRow(cartId).get("status")).isEqualTo("OPEN");
		assertThat(attemptsOf(cartId)).isEmpty();
	}

	@Test
	void anExpiredCardIs422InvalidPaymentDataAndNothingIsRecorded() {
		UUID cartId = createCart("premium");

		HttpResult result = checkoutRequest(cartId, "Cartão de Crédito",
				"{\"holderName\":\"Ana Souza\",\"number\":\"" + VALID_NUMBER + "\",\"expiry\":\"01/20\",\"cvv\":\"123\"}");

		assertThat(result.status()).isEqualTo(422);
		assertThat(asMap(result.body()).get("errorCode")).isEqualTo("INVALID_PAYMENT_DATA");
		assertThat(attemptsOf(cartId)).isEmpty();
	}

	@Test
	void aCardMethodWithoutCardIs422InvalidPaymentDataAndNothingIsRecorded() {
		UUID cartId = createCart("premium");

		HttpResult result = checkoutRequest(cartId, "Cartão de Crédito", null);

		assertThat(result.status()).isEqualTo(422);
		assertThat(asMap(result.body())).containsEntry("errorCode", "INVALID_PAYMENT_DATA")
				.containsEntry("cartId", cartId.toString());
		assertThat(cartRow(cartId).get("status")).isEqualTo("OPEN");
		assertThat(attemptsOf(cartId)).isEmpty();
	}

	@Test
	void pixWithACardIs422InvalidPaymentDataAndNothingIsRecorded() {
		UUID cartId = createCart("premium");

		HttpResult result = checkoutRequest(cartId, "Pix", cardJson(VALID_NUMBER));

		assertThat(result.status()).isEqualTo(422);
		assertThat(asMap(result.body())).containsEntry("errorCode", "INVALID_PAYMENT_DATA")
				.containsEntry("cartId", cartId.toString());
		assertThat(cartRow(cartId).get("status")).isEqualTo("OPEN");
		assertThat(attemptsOf(cartId)).isEmpty();
	}

	// ---- Sem chamadas aos servicos no checkout (CHK-04) -------------------------------------

	@Test
	void preCheckoutCallsEachExternalServiceOnceAndCheckoutsCallNoneOfThem() {
		UUID cartId = createCart("premium");
		assertThat(calls.count(PRODUCTS_PATH)).isEqualTo(1);
		assertThat(calls.count(PAYMENT_METHODS_PATH)).isEqualTo(1);
		calls.reset();

		assertThat(checkoutRequest(cartId, "Cartão de Crédito", cardJson(DECLINED_NUMBER)).status()).isEqualTo(422);
		assertThat(checkoutRequest(cartId, "Cartão de Crédito", cardJson(VALID_NUMBER)).status()).isEqualTo(200);
		assertThat(checkoutRequest(cartId, "Cartão de Crédito", cardJson(VALID_NUMBER)).status()).isEqualTo(409);
		assertThat(checkoutRequest(UUID.randomUUID(), "Pix", null).status()).isEqualTo(404);

		assertThat(calls.count(PRODUCTS_PATH)).isZero();
		assertThat(calls.count(PAYMENT_METHODS_PATH)).isZero();
	}

}
