package com.checkout.nexo.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.checkout.nexo.AbstractIT;
import com.checkout.nexo.bff.error.ErrorCatalog;
import com.checkout.nexo.checkout.domain.CheckoutErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrato publicado no OpenAPI (API-01 a API-03) contra a aplicacao no ar. O springdoc 3.2 gera
 * OpenAPI 3.1; nessa versao "anulavel" e {@code type: ["string","null"]} numa propriedade simples e
 * {@code oneOf: [<referencia>, {"type":"null"}]} numa referencia, e e isso que os testes afirmam
 * (representacao observada, registrada aqui; o spec so exige "nullable").
 */
class OpenApiIT extends AbstractIT {

	private static final String CHECKOUT = "/operations/checkout";

	private static final String PRE_CHECKOUT = "/operations/pre-checkout";

	private record HttpResult(int status, String location, String body) {
	}

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private ErrorCatalog errorCatalog;

	private final RestClient http = RestClient.builder().baseUrl(baseUrl())
			.requestFactory(new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();

	private HttpResult get(String path) {
		return http.get().uri(path).exchange((request, response) -> new HttpResult(response.getStatusCode().value(),
				response.getHeaders().getFirst("Location"),
				new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
	}

	private JsonNode apiDocs(String group) {
		HttpResult result = get("/v3/api-docs/" + group);
		assertThat(result.status()).isEqualTo(200);
		return jsonMapper.readTree(result.body());
	}

	private static Set<String> namesOf(JsonNode object) {
		return new TreeSet<>(object.propertyNames());
	}

	// ---- API-01: grupos -----------------------------------------------------------------------

	@Test
	void theBffGroupListsExactlyThePreCheckoutAndCheckoutPaths() {
		assertThat(namesOf(apiDocs("bff").get("paths"))).containsExactlyInAnyOrder(PRE_CHECKOUT, CHECKOUT);
	}

	@Test
	void theMockServicesGroupListsOnlyTheMockServicePaths() {
		assertThat(namesOf(apiDocs("mock-services").get("paths")))
				.containsExactlyInAnyOrder("/mock-services/products", "/mock-services/payment-methods");
	}

	// ---- API-02: schema de erro e exemplos ----------------------------------------------------

	@Test
	void theErrorResponseSchemaMarksCartIdAndSecondaryButtonAsNullableInTheOpenApi31Representation() {
		JsonNode docs = apiDocs("bff");
		JsonNode schemas = docs.get("components").get("schemas");

		assertThat(docs.get("openapi").stringValue()).startsWith("3.1");
		assertThat(schemas.get("ErrorResponse").get("properties").get("cartId").get("type"))
				.isEqualTo(jsonMapper.readTree("[\"string\",\"null\"]"));
		assertThat(schemas.get("FrontendError").get("properties").get("secondaryButton"))
				.isEqualTo(jsonMapper.readTree(
						"{\"oneOf\":[{\"$ref\":\"#/components/schemas/ErrorButton\"},{\"type\":\"null\"}]}"));
	}

	@Test
	void thereIsExactlyOneExamplePerErrorCodeAndEachEqualsWhatTheCatalogProduces() {
		JsonNode examples = apiDocs("bff").get("components").get("examples");

		assertThat(namesOf(examples)).containsExactlyInAnyOrderElementsOf(
				List.of(CheckoutErrorCode.values()).stream().map(Enum::name).toList());
		for (CheckoutErrorCode code : CheckoutErrorCode.values()) {
			assertThat(examples.get(code.name()).get("value"))
					.as("exemplo de %s", code)
					.isEqualTo(jsonMapper.valueToTree(errorCatalog.toResponse(code, null)));
		}
		JsonNode declined = examples.get("PAYMENT_DECLINED").get("value");
		assertThat(declined.get("frontendError").get("title").stringValue()).isEqualTo("Pagamento não aprovado");
		assertThat(declined.get("cartId").isNull()).isTrue();
		assertThat(declined.get("frontendError").get("secondaryButton").get("label").stringValue())
				.isEqualTo("Trocar meio de pagamento");
	}

	@Test
	void everyErrorExampleIsReferencedByAnOperationUnderTheHttpStatusOfTheCatalog() {
		JsonNode paths = apiDocs("bff").get("paths");

		for (CheckoutErrorCode code : CheckoutErrorCode.values()) {
			String status = String.valueOf(errorCatalog.get(code).httpStatus().value());
			List<String> references = new ArrayList<>();
			for (String path : List.of(PRE_CHECKOUT, CHECKOUT)) {
				JsonNode content = paths.get(path).get("post").get("responses").path(status).path("content")
						.path("application/json");
				if (content.path("examples").has(code.name())) {
					references.add(content.get("examples").get(code.name()).get("$ref").stringValue());
					assertThat(content.get("schema").get("$ref").stringValue())
							.isEqualTo("#/components/schemas/ErrorResponse");
				}
			}
			assertThat(references).as("referencias de %s", code).isNotEmpty()
					.containsOnly("#/components/examples/" + code.name());
		}
	}

	@Test
	void theSchemaPropertyNamesMatchTheKeysOfARealErrorResponse() {
		JsonNode schemas = apiDocs("bff").get("components").get("schemas");

		String body = http.post().uri(CHECKOUT).contentType(MediaType.APPLICATION_JSON).body("{")
				.exchange((request, response) -> {
					assertThat(response.getStatusCode().value()).isEqualTo(400);
					return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
				});
		JsonNode real = jsonMapper.readTree(body);

		assertThat(namesOf(schemas.get("ErrorResponse").get("properties"))).isEqualTo(namesOf(real));
		assertThat(namesOf(schemas.get("FrontendError").get("properties"))).isEqualTo(namesOf(real.get("frontendError")));
		assertThat(namesOf(schemas.get("ErrorButton").get("properties")))
				.isEqualTo(namesOf(real.get("frontendError").get("primaryButton")));
		assertThat(real.get("cartId").isNull()).isTrue();
		assertThat(real.get("frontendError").get("secondaryButton").isNull()).isTrue();
	}

	// ---- API-04: exemplos de sucesso ----------------------------------------------------------

	private JsonNode postJson(String path, String json, int expectedStatus) {
		String body = http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(json)
				.exchange((request, response) -> {
					assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
					return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
				});
		return jsonMapper.readTree(body);
	}

	private JsonNode realPreCheckout() {
		return postJson(PRE_CHECKOUT, "{\"products\":[{\"slug\":\"premium\"}]}", 201);
	}

	private JsonNode realCheckout(String methodName, String cardJson) {
		String cartId = realPreCheckout().get("cartId").stringValue();
		String card = cardJson == null ? "" : ",\"card\":" + cardJson;
		return postJson(CHECKOUT,
				"{\"cartId\":\"" + cartId + "\",\"paymentMethod\":{\"name\":\"" + methodName + "\"" + card + "}}", 200);
	}

	private JsonNode successExample(String path, String status, String exampleName) {
		JsonNode content = apiDocs("bff").get("paths").get(path).get("post").get("responses").get(status)
				.get("content").get("application/json");
		assertThat(content.get("examples").has(exampleName)).as("exemplo %s em %s %s", exampleName, path, status)
				.isTrue();
		return content.get("examples").get(exampleName).get("value");
	}

	/** Estrutura de chaves (aninhadas) de um JSON; arrays valem pelo primeiro elemento. */
	private static Object shapeOf(JsonNode node) {
		if (node.isObject()) {
			Map<String, Object> shape = new TreeMap<>();
			node.properties().forEach(entry -> shape.put(entry.getKey(), shapeOf(entry.getValue())));
			return shape;
		}
		if (node.isArray()) {
			return node.isEmpty() ? List.of() : List.of(shapeOf(node.get(0)));
		}
		return "value";
	}

	@Test
	void thePreCheckoutSuccessExampleHasTheSameKeysAsARealResponse() {
		JsonNode example = successExample(PRE_CHECKOUT, "201", "premium");

		assertThat(shapeOf(example)).isEqualTo(shapeOf(realPreCheckout()));
		assertThat(apiDocs("bff").get("paths").get(PRE_CHECKOUT).get("post").get("responses").has("200")).isFalse();
	}

	@Test
	void theCardCheckoutSuccessExampleHasTheSameKeysAsARealCardResponseAndNoPixCode() {
		JsonNode example = successExample(CHECKOUT, "200", "card");
		JsonNode real = realCheckout("Cartão de Crédito",
				"{\"holderName\":\"Ana Souza\",\"number\":\"4111111111114242\",\"expiry\":\"12/99\",\"cvv\":\"123\"}");

		assertThat(shapeOf(example)).isEqualTo(shapeOf(real));
		assertThat(example.get("payment").has("lastDigits")).isTrue();
		assertThat(example.get("payment").has("pixCode")).isFalse();
	}

	@Test
	void thePixCheckoutSuccessExampleHasTheSameKeysAsARealPixResponseAndNoLastDigits() {
		JsonNode example = successExample(CHECKOUT, "200", "pix");
		JsonNode real = realCheckout("Pix", null);

		assertThat(shapeOf(example)).isEqualTo(shapeOf(real));
		assertThat(example.get("payment").has("pixCode")).isTrue();
		assertThat(example.get("payment").has("lastDigits")).isFalse();
	}

	// ---- API-03: Swagger UI -------------------------------------------------------------------

	@Test
	void theSwaggerUiPathRedirectsToAPageThatAnswers200() {
		HttpResult entry = get("/swagger-ui.html");

		assertThat(entry.status()).isIn(200, 302);
		if (entry.status() == 302) {
			assertThat(entry.location()).endsWith("/swagger-ui/index.html");
			HttpResult page = get(entry.location().startsWith("http") ? entry.location().substring(baseUrl().length())
					: entry.location());
			assertThat(page.status()).isEqualTo(200);
			assertThat(page.body()).contains("swagger-ui");
		}
	}

}
