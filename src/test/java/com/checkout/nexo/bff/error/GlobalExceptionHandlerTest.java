package com.checkout.nexo.bff.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.checkout.nexo.checkout.domain.CheckoutException;
import com.checkout.nexo.checkout.port.ExternalServiceUnavailableException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@Import({ ErrorCatalog.class, GlobalExceptionHandlerTest.ThrowingController.class })
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

	private static final UUID CART_ID = UUID.fromString("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");

	/** HTTP de cada errorCode, literal da tabela de ERR-02. */
	private static final Map<CheckoutErrorCode, Integer> ERR02_HTTP = Map.of(
			CheckoutErrorCode.INVALID_REQUEST, 400,
			CheckoutErrorCode.PRODUCT_NOT_FOUND, 422,
			CheckoutErrorCode.CART_NOT_FOUND, 404,
			CheckoutErrorCode.PAYMENT_METHOD_NOT_ALLOWED, 422,
			CheckoutErrorCode.INVALID_PAYMENT_DATA, 422,
			CheckoutErrorCode.PAYMENT_DECLINED, 422,
			CheckoutErrorCode.CART_ALREADY_PAID, 409,
			CheckoutErrorCode.SERVICE_UNAVAILABLE, 503,
			CheckoutErrorCode.INTERNAL_ERROR, 500);

	/** Corpos exatos de ERR-01/ERR-02 (cartId e secondaryButton presentes como null). */
	private static final String INVALID_REQUEST_JSON = "{\"displayType\":\"BLOCK\",\"errorCode\":\"INVALID_REQUEST\","
			+ "\"cartId\":null,\"frontendError\":{\"code\":\"R-1001\",\"title\":\"Não foi possível processar o pedido\","
			+ "\"description\":\"Os dados enviados estão incompletos ou em formato inválido. Tente novamente.\","
			+ "\"primaryButton\":{\"label\":\"Tentar novamente\",\"url\":\"RETRY\"},\"secondaryButton\":null}}";

	private static final String SERVICE_UNAVAILABLE_JSON = "{\"displayType\":\"BLOCK\",\"errorCode\":\"SERVICE_UNAVAILABLE\","
			+ "\"cartId\":null,\"frontendError\":{\"code\":\"S-1001\",\"title\":\"Serviço temporariamente indisponível\","
			+ "\"description\":\"Não conseguimos concluir agora. Tente novamente em instantes.\","
			+ "\"primaryButton\":{\"label\":\"Tentar novamente\",\"url\":\"RETRY\"},\"secondaryButton\":null}}";

	private static final String INTERNAL_ERROR_JSON = "{\"displayType\":\"BLOCK\",\"errorCode\":\"INTERNAL_ERROR\","
			+ "\"cartId\":null,\"frontendError\":{\"code\":\"S-1002\",\"title\":\"Algo deu errado\","
			+ "\"description\":\"Ocorreu um erro inesperado. Tente novamente.\","
			+ "\"primaryButton\":{\"label\":\"Tentar novamente\",\"url\":\"RETRY\"},\"secondaryButton\":null}}";

	private static final String PAYMENT_DECLINED_JSON = "{\"displayType\":\"BLOCK\",\"errorCode\":\"PAYMENT_DECLINED\","
			+ "\"cartId\":\"7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33\","
			+ "\"frontendError\":{\"code\":\"P-1001\",\"title\":\"Pagamento não aprovado\","
			+ "\"description\":\"Não foi possível concluir o pagamento. Tente novamente ou escolha outro meio.\","
			+ "\"primaryButton\":{\"label\":\"Tentar novamente\",\"url\":\"RETRY\"},"
			+ "\"secondaryButton\":{\"label\":\"Trocar meio de pagamento\",\"url\":\"/checkout/pagamento\"}}}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	private static String bodyOf(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	// ---- CheckoutException (ERR-02, ERR-03) -------------------------------------------------

	@ParameterizedTest
	@EnumSource(CheckoutErrorCode.class)
	void checkoutExceptionBecomesTheCatalogStatusAndBodyWithTheCartId(CheckoutErrorCode code) throws Exception {
		ResultActions result = mockMvc
				.perform(get("/test/checkout/{code}", code.name()).param("cartId", CART_ID.toString()))
				.andExpect(status().is(ERR02_HTTP.get(code)))
				.andExpect(jsonPath("$.errorCode").value(code.name()))
				.andExpect(jsonPath("$.cartId").value(CART_ID.toString()));

		assertThat(bodyOf(result))
				.isEqualTo(jsonMapper.writeValueAsString(new ErrorCatalog("/checkout/pagamento").toResponse(code, CART_ID)));
	}

	@Test
	void checkoutExceptionWithoutCartIdKeepsCartIdAsNullInTheBody() throws Exception {
		ResultActions result = mockMvc.perform(get("/test/checkout/{code}", "PRODUCT_NOT_FOUND"))
				.andExpect(status().isUnprocessableContent());

		assertThat(bodyOf(result)).contains("\"cartId\":null");
	}

	@Test
	void paymentDeclinedBodyMatchesTheSpecTextWithBothButtons() throws Exception {
		ResultActions result = mockMvc
				.perform(get("/test/checkout/{code}", "PAYMENT_DECLINED").param("cartId", CART_ID.toString()))
				.andExpect(status().isUnprocessableContent());

		assertThat(bodyOf(result)).isEqualTo(PAYMENT_DECLINED_JSON);
	}

	// ---- Falhas tecnicas (503 / 500) --------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "external", "lock", "timeout" })
	void externalServiceLockAndQueryTimeoutFailuresBecomeServiceUnavailable(String failure) throws Exception {
		ResultActions result = mockMvc.perform(get("/test/unavailable/{failure}", failure))
				.andExpect(status().isServiceUnavailable());

		assertThat(bodyOf(result)).isEqualTo(SERVICE_UNAVAILABLE_JSON);
	}

	@Test
	void unexpectedExceptionBecomesInternalErrorWithoutStackTraceNorMessage() throws Exception {
		ResultActions result = mockMvc.perform(get("/test/boom")).andExpect(status().isInternalServerError());

		assertThat(bodyOf(result)).isEqualTo(INTERNAL_ERROR_JSON);
	}

	@Test
	void unexpectedExceptionIsLoggedAtErrorLevelWithStackTrace(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/test/boom")).andExpect(status().isInternalServerError());

		assertThat(output.getAll()).contains(" ERROR ")
				.contains("java.lang.IllegalStateException: mensagem-interna-9f2b")
				.contains("GlobalExceptionHandlerTest$ThrowingController.boom(");
	}

	@Test
	void springMvcServerSideFailureKeepsItsStatusWithTheInternalErrorBody() throws Exception {
		ResultActions result = mockMvc.perform(get("/test/framework-failure"))
				.andExpect(status().isInternalServerError());

		assertThat(bodyOf(result)).isEqualTo(INTERNAL_ERROR_JSON);
	}

	// ---- cartId da requisicao (ERR-03) ------------------------------------------------------

	private static String withCartId(String errorJson, UUID cartId) {
		assertThat(errorJson).contains("\"cartId\":null");
		return errorJson.replace("\"cartId\":null", "\"cartId\":\"" + cartId + "\"");
	}

	@Test
	void checkoutExceptionWithoutItsOwnCartIdUsesTheRequestAttribute() throws Exception {
		ResultActions result = mockMvc
				.perform(get("/test/checkout/{code}", "CART_NOT_FOUND")
						.requestAttr(GlobalExceptionHandler.CART_ID_ATTRIBUTE, CART_ID))
				.andExpect(status().isNotFound());

		assertThat(bodyOf(result)).contains("\"cartId\":\"" + CART_ID + "\"");
	}

	@Test
	void theCartIdOfTheCheckoutExceptionWinsOverTheRequestAttribute() throws Exception {
		UUID otherCartId = UUID.fromString("0b1c2d3e-4f50-4a61-b072-8394a5b6c7d8");

		ResultActions result = mockMvc
				.perform(get("/test/checkout/{code}", "CART_ALREADY_PAID").param("cartId", CART_ID.toString())
						.requestAttr(GlobalExceptionHandler.CART_ID_ATTRIBUTE, otherCartId))
				.andExpect(status().isConflict());

		assertThat(bodyOf(result)).contains("\"cartId\":\"" + CART_ID + "\"").doesNotContain(otherCartId.toString());
	}

	@ParameterizedTest
	@ValueSource(strings = { "external", "lock", "timeout" })
	void serviceUnavailableFailuresCarryTheCartIdOfTheRequestAttribute(String failure) throws Exception {
		ResultActions result = mockMvc
				.perform(get("/test/unavailable/{failure}", failure)
						.requestAttr(GlobalExceptionHandler.CART_ID_ATTRIBUTE, CART_ID))
				.andExpect(status().isServiceUnavailable());

		assertThat(bodyOf(result)).isEqualTo(withCartId(SERVICE_UNAVAILABLE_JSON, CART_ID));
	}

	@Test
	void unexpectedExceptionCarriesTheCartIdOfTheRequestAttribute() throws Exception {
		ResultActions result = mockMvc
				.perform(get("/test/boom").requestAttr(GlobalExceptionHandler.CART_ID_ATTRIBUTE, CART_ID))
				.andExpect(status().isInternalServerError());

		assertThat(bodyOf(result)).isEqualTo(withCartId(INTERNAL_ERROR_JSON, CART_ID));
	}

	@Test
	void springMvcRejectionsKeepCartIdNullEvenWithTheRequestAttribute() throws Exception {
		ResultActions result = mockMvc
				.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content("{\"name\": }")
						.requestAttr(GlobalExceptionHandler.CART_ID_ATTRIBUTE, CART_ID))
				.andExpect(status().isBadRequest());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
	}

	// ---- Rejeicoes do Spring MVC (ERR-04) ---------------------------------------------------

	@Test
	void malformedJsonBecomes400InvalidRequest() throws Exception {
		ResultActions result = mockMvc
				.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content("{\"name\": }"))
				.andExpect(status().isBadRequest());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
	}

	@Test
	void unsupportedContentTypeBecomes415InvalidRequest() throws Exception {
		ResultActions result = mockMvc
				.perform(post("/test/echo").contentType(MediaType.TEXT_PLAIN).content("texto"))
				.andExpect(status().isUnsupportedMediaType());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
	}

	@Test
	void notAllowedMethodBecomes405InvalidRequest() throws Exception {
		ResultActions result = mockMvc.perform(get("/test/echo")).andExpect(status().isMethodNotAllowed());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
	}

	@Test
	void unknownRouteBecomes404InvalidRequest() throws Exception {
		ResultActions result = mockMvc.perform(get("/rota/que/nao/existe")).andExpect(status().isNotFound());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
	}

	@Test
	void incompatibleAcceptBecomes406InvalidRequestStillAsJson() throws Exception {
		ResultActions result = mockMvc.perform(get("/test/json-only").accept(MediaType.APPLICATION_XML))
				.andExpect(status().isNotAcceptable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
	}

	@Test
	void beanValidationFailureBecomes400InvalidRequest() throws Exception {
		ResultActions result = mockMvc
				.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"nome-longo-demais\"}"))
				.andExpect(status().isBadRequest());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
	}

	// ---- Higiene de log e corpo (AD-008, DATA-02, DATA-04) ----------------------------------

	@Test
	void malformedBodyWithACardNumberLeavesNeitherLogNorResponseWithTheNumber(CapturedOutput output)
			throws Exception {
		ResultActions result = mockMvc.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON)
				.content("{\"number\": abc4111222233334444}")).andExpect(status().isBadRequest());

		assertThat(output.getAll()).contains("HttpMessageNotReadableException").doesNotContain("4111222233334444");
		assertThat(bodyOf(result)).doesNotContain("4111222233334444");
	}

	@Test
	void validationFailureLeavesNeitherLogNorResponseWithTheRejectedValue(CapturedOutput output) throws Exception {
		ResultActions result = mockMvc.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"valor-distintivo-9f8e7d\"}")).andExpect(status().isBadRequest());

		assertThat(output.getAll()).contains("MethodArgumentNotValidException")
				.doesNotContain("valor-distintivo-9f8e7d");
		assertThat(bodyOf(result)).doesNotContain("valor-distintivo-9f8e7d");
	}

	/** Controller so de teste: provoca cada tipo de falha que o handler deve converter. */
	@RestController
	static class ThrowingController {

		record Payload(@Size(max = 5) String name) {
		}

		@GetMapping("/test/checkout/{code}")
		void checkout(@PathVariable CheckoutErrorCode code, @RequestParam(required = false) UUID cartId) {
			throw new CheckoutException(code, cartId);
		}

		@GetMapping("/test/unavailable/{failure}")
		void unavailable(@PathVariable String failure) {
			switch (failure) {
				case "external" -> throw new ExternalServiceUnavailableException("products", new IOException("io"));
				case "lock" -> throw new PessimisticLockingFailureException("lock");
				default -> throw new QueryTimeoutException("timeout");
			}
		}

		@GetMapping("/test/boom")
		void boom() {
			throw new IllegalStateException("mensagem-interna-9f2b");
		}

		@GetMapping("/test/framework-failure")
		void frameworkFailure() {
			throw new ErrorResponseException(HttpStatus.INTERNAL_SERVER_ERROR);
		}

		@PostMapping(value = "/test/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
		void echo(@Valid @RequestBody Payload payload) {
		}

		@GetMapping(value = "/test/json-only", produces = MediaType.APPLICATION_JSON_VALUE)
		Map<String, String> jsonOnly() {
			return Map.of("ok", "true");
		}

	}

}
