package com.checkout.nexo.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.checkout.nexo.AbstractIT;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Numero completo, CVV, titular e validade do cartao nao ficam no banco, no log nem nas respostas,
 * depois de cada caminho do checkout (DATA-01, DATA-02, DATA-04, AD-008). Os valores sao
 * distintivos (numero, CVV de 4 digitos, titular, validade) para que achar um deles seja prova de
 * vazamento. A varredura do banco cobre todas as colunas texto de todas as tabelas e le o estado
 * final por SQL, nao por repositorios nem por espioes. UUIDs sao removidos do texto antes de
 * procurar, porque um {@code cartId} pode conter por acaso os 4 digitos do CVV.
 */
@ExtendWith(OutputCaptureExtension.class)
class DataHygieneIT extends AbstractIT {

	private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	private static final String NUMBER = "5200828282828210";

	private static final String NUMBER_BY_FOURS = "5200 8282 8282 8210";

	private static final String NUMBER_BY_FOURS_WITH_HYPHENS = "5200-8282-8282-8210";

	/** Os 6 primeiros digitos (BIN): nem sozinhos nem seguidos do restante podem aparecer. */
	private static final String FIRST_SIX_DIGITS = "520082";

	private static final String DECLINED_NUMBER = "5200828282820002";

	private static final String CVV = "7391";

	private static final String HOLDER = "Zelinda Quintanilha Distintiva";

	private static final String EXPIRY = "11/47";

	private static final List<String> SECRETS = List.of(NUMBER, NUMBER_BY_FOURS, NUMBER_BY_FOURS_WITH_HYPHENS,
			FIRST_SIX_DIGITS, DECLINED_NUMBER, CVV, HOLDER, EXPIRY);

	private static final Pattern UUID_TEXT = Pattern
			.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private record HttpResult(int status, String body) {
	}

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private final RestClient http = RestClient.builder().baseUrl(baseUrl())
			.requestFactory(new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();

	/** Todos os corpos de resposta recebidos pelo teste em curso. */
	private final List<String> responseBodies = new ArrayList<>();

	// ---- HTTP -------------------------------------------------------------------------------

	private HttpResult post(String path, String json) {
		HttpResult result = http.post().uri(path).contentType(JSON_UTF8).body(json).exchange(
				(request, response) -> new HttpResult(response.getStatusCode().value(),
						new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
		responseBodies.add(result.body());
		return result;
	}

	private UUID createCart() {
		HttpResult result = post("/operations/pre-checkout", "{\"products\":[{\"slug\":\"premium\"}]}");
		assertThat(result.status()).isEqualTo(201);
		return UUID.fromString((String) jsonMapper.readValue(result.body(), new TypeReference<Map<String, Object>>() {
		}).get("cartId"));
	}

	private static String card(String number) {
		return "{\"holderName\":\"" + HOLDER + "\",\"number\":\"" + number + "\",\"expiry\":\"" + EXPIRY
				+ "\",\"cvv\":\"" + CVV + "\"}";
	}

	private HttpResult checkout(UUID cartId, String methodName, String cardJson) {
		String method = "{\"name\":\"" + methodName + "\"" + (cardJson == null ? "" : ",\"card\":" + cardJson) + "}";
		return post("/operations/checkout", "{\"cartId\":\"" + cartId + "\",\"paymentMethod\":" + method + "}");
	}

	// ---- Varredura --------------------------------------------------------------------------

	private static String withoutUuids(String text) {
		return UUID_TEXT.matcher(text).replaceAll("<uuid>");
	}

	/** {@code tabela.coluna} de toda coluna texto de toda tabela do schema public. */
	private List<String> textColumns() {
		return jdbc.queryForList("select table_name || '.' || column_name from information_schema.columns "
				+ "where table_schema = 'public' and data_type in ('character varying', 'character', 'text') "
				+ "order by table_name, ordinal_position", String.class);
	}

	/** Colunas texto (de {@code jdbc}) que guardam, em alguma linha, qualquer um dos segredos. */
	private List<String> columnsHoldingSecrets(JdbcTemplate source, List<String> secrets) {
		List<String> hits = new ArrayList<>();
		for (String column : textColumns()) {
			String[] parts = column.split("\\.");
			List<String> values = source.queryForList(
					"select \"" + parts[1] + "\" from \"" + parts[0] + "\" where \"" + parts[1] + "\" is not null",
					String.class);
			for (String value : values) {
				String searchable = withoutUuids(value);
				if (secrets.stream().anyMatch(searchable::contains)) {
					hits.add(column);
					break;
				}
			}
		}
		return hits;
	}

	private void assertNoCardDataInTheDatabaseTheLogNorTheResponses(CapturedOutput output) {
		assertThat(columnsHoldingSecrets(jdbc, SECRETS)).as("colunas texto do banco com dado de cartao").isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from payment_attempt where last_digits is not null "
				+ "and last_digits !~ '^[0-9]{4}$'", Long.class)).as("last_digits que nao tem 4 digitos").isZero();
		assertThat(withoutUuids(output.getAll())).as("log capturado").doesNotContain(SECRETS);
		assertThat(responseBodies).as("corpos de resposta").isNotEmpty()
				.allSatisfy(body -> assertThat(withoutUuids(body)).doesNotContain(SECRETS));
	}

	private List<Map<String, Object>> attemptsOf(UUID cartId) {
		return jdbc.queryForList(
				"select status, method_code, last_digits, pix_code from payment_attempt where cart_id = ? order by id",
				cartId);
	}

	private String cartStatus(UUID cartId) {
		return jdbc.queryForObject("select status from cart where id = ?", String.class, cartId);
	}

	// ---- Caminhos do checkout ---------------------------------------------------------------

	@Test
	void anApprovedCardLeavesOnlyTheLastFourDigitsInTheDatabaseAndInTheResponse(CapturedOutput output) {
		UUID cartId = createCart();

		HttpResult result = checkout(cartId, "Cartão de Crédito", card(NUMBER));

		assertThat(result.status()).isEqualTo(200);
		assertThat(result.body()).contains("\"payment\":{\"method\":\"Cartão de Crédito\",\"lastDigits\":\"8210\",");
		assertThat(cartStatus(cartId)).isEqualTo("APPROVED");
		assertThat(attemptsOf(cartId)).singleElement().satisfies(attempt -> {
			assertThat(attempt.get("status")).isEqualTo("APPROVED");
			assertThat(attempt.get("method_code")).isEqualTo("CREDIT_CARD");
			assertThat(attempt.get("last_digits")).isEqualTo("8210");
			assertThat(attempt.get("pix_code")).isNull();
		});
		assertNoCardDataInTheDatabaseTheLogNorTheResponses(output);
	}

	@Test
	void anApprovedPixLeavesOnlyThePixCodeAndNoCardDataAnywhere(CapturedOutput output) {
		UUID cartId = createCart();

		HttpResult result = checkout(cartId, "Pix", null);

		assertThat(result.status()).isEqualTo(200);
		assertThat(cartStatus(cartId)).isEqualTo("APPROVED");
		assertThat(attemptsOf(cartId)).singleElement().satisfies(attempt -> {
			assertThat(attempt.get("status")).isEqualTo("APPROVED");
			assertThat(attempt.get("pix_code")).isEqualTo("PIX-SIMULADO-" + cartId);
			assertThat(attempt.get("last_digits")).isNull();
		});
		assertNoCardDataInTheDatabaseTheLogNorTheResponses(output);
	}

	@Test
	void aDeclinedCardLeavesOnlyTheLastFourDigitsOfTheDeclinedAttemptAndKeepsTheCartOpen(CapturedOutput output) {
		UUID cartId = createCart();

		HttpResult result = checkout(cartId, "Cartão de Débito", card(DECLINED_NUMBER));

		assertThat(result.status()).isEqualTo(422);
		assertThat(jdbc.queryForObject("select paid_at is null from cart where id = ?", Boolean.class, cartId)).isTrue();
		assertThat(cartStatus(cartId)).isEqualTo("OPEN");
		assertThat(attemptsOf(cartId)).singleElement().satisfies(attempt -> {
			assertThat(attempt.get("status")).isEqualTo("DECLINED");
			assertThat(attempt.get("last_digits")).isEqualTo("0002");
			assertThat(attempt.get("pix_code")).isNull();
		});
		assertNoCardDataInTheDatabaseTheLogNorTheResponses(output);
	}

	@Test
	void anInvalidCardRecordsNothingAndEchoesNoValueInTheLogNorTheResponse(CapturedOutput output) {
		UUID cartId = createCart();

		HttpResult groupedNumber = checkout(cartId, "Cartão de Crédito", card(NUMBER_BY_FOURS));
		HttpResult hyphenatedNumber = checkout(cartId, "Cartão de Crédito", card(NUMBER_BY_FOURS_WITH_HYPHENS));
		HttpResult pixWithACard = checkout(cartId, "Pix", card(NUMBER));

		assertThat(List.of(groupedNumber.status(), hyphenatedNumber.status(), pixWithACard.status()))
				.containsExactly(422, 422, 422);
		assertThat(groupedNumber.body()).contains("\"errorCode\":\"INVALID_PAYMENT_DATA\"");
		assertThat(cartStatus(cartId)).isEqualTo("OPEN");
		assertThat(attemptsOf(cartId)).isEmpty();
		assertNoCardDataInTheDatabaseTheLogNorTheResponses(output);
	}

	@Test
	void aCardNumberTypedAsThePaymentMethodNameIsNotLeftInTheLogNorTheResponseNorTheDatabase(CapturedOutput output) {
		UUID cartId = createCart();

		HttpResult numberAsName = checkout(cartId, NUMBER, card(NUMBER));
		HttpResult groupedNumberAsName = checkout(cartId, NUMBER_BY_FOURS, null);

		assertThat(List.of(numberAsName.status(), groupedNumberAsName.status())).containsExactly(422, 422);
		assertThat(numberAsName.body()).contains("\"errorCode\":\"PAYMENT_METHOD_NOT_ALLOWED\"");
		assertThat(output.getAll()).contains("errorCode=PAYMENT_METHOD_NOT_ALLOWED method=-");
		assertThat(cartStatus(cartId)).isEqualTo("OPEN");
		assertThat(attemptsOf(cartId)).isEmpty();
		assertNoCardDataInTheDatabaseTheLogNorTheResponses(output);
	}

	@Test
	void aMalformedBodyContainingTheCardNumberIs400AndEchoesNoValueInTheLogNorTheResponse(CapturedOutput output) {
		UUID cartId = createCart();
		String truncated = "{\"cartId\":\"" + cartId + "\",\"paymentMethod\":{\"name\":\"Cartão de Crédito\",\"card\":"
				+ "{\"holderName\":\"" + HOLDER + "\",\"number\":\"" + NUMBER + "\",\"expiry\":\"" + EXPIRY
				+ "\",\"cvv\":\"" + CVV + "\"";
		String garbageAfterTheNumber = "{\"paymentMethod\":{\"card\":{\"number\": " + NUMBER + "x, \"cvv\": " + CVV + "}}}";

		HttpResult truncatedBody = post("/operations/checkout", truncated);
		HttpResult garbageBody = post("/operations/checkout", garbageAfterTheNumber);

		assertThat(List.of(truncatedBody.status(), garbageBody.status())).containsExactly(400, 400);
		assertThat(truncatedBody.body()).contains("\"errorCode\":\"INVALID_REQUEST\",\"cartId\":null");
		assertThat(attemptsOf(cartId)).isEmpty();
		assertThat(output.getAll()).contains("HttpMessageNotReadableException");
		assertNoCardDataInTheDatabaseTheLogNorTheResponses(output);
	}

	@Test
	void anInvalidSlugContainingTheCardNumberIs400AndTheRejectedValueIsNotEchoed(CapturedOutput output) {
		long cartsBefore = jdbc.queryForObject("select count(*) from cart", Long.class);

		HttpResult result = post("/operations/pre-checkout",
				"{\"products\":[{\"slug\":\"SLUG INVALIDO " + NUMBER + " " + CVV + " " + HOLDER + "\"}]}");

		assertThat(result.status()).isEqualTo(400);
		assertThat(result.body()).contains("\"errorCode\":\"INVALID_REQUEST\"");
		assertThat(jdbc.queryForObject("select count(*) from cart", Long.class)).isEqualTo(cartsBefore);
		assertThat(output.getAll()).contains("MethodArgumentNotValidException");
		assertNoCardDataInTheDatabaseTheLogNorTheResponses(output);
	}

	// ---- O detector nao e vazio -------------------------------------------------------------

	@Test
	void theDatabaseScanCoversTheTextColumnsAndFindsAValuePlantedInsideATransactionThatIsRolledBack() {
		assertThat(textColumns()).contains("cart.status", "cart.currency", "cart_item.slug", "cart_item.name",
				"cart_item.description", "cart_payment_method.code", "cart_payment_method.name",
				"payment_attempt.method_code", "payment_attempt.last_digits", "payment_attempt.pix_code",
				"payment_attempt.status");

		List<String> found = new TransactionTemplate(transactionManager).execute(transaction -> {
			UUID plantedCart = UUID.randomUUID();
			jdbc.update("insert into cart (id, status, total_amount, currency, created_at, version) "
					+ "values (?, 'OPEN', 1.00, 'BRL', now(), 0)", plantedCart);
			jdbc.update("insert into cart_item (cart_id, line_no, slug, name, description, unit_price, currency) "
					+ "values (?, 0, 'plantado', ?, 'descricao', 1.00, 'BRL')", plantedCart, "x " + NUMBER_BY_FOURS);
			List<String> hits = columnsHoldingSecrets(jdbc, SECRETS);
			transaction.setRollbackOnly();
			return hits;
		});

		assertThat(found).containsExactly("cart_item.name");
		assertThat(columnsHoldingSecrets(jdbc, SECRETS)).isEmpty();
	}

}
