package com.checkout.nexo.bff.config;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkout.nexo.bff.error.ErrorCatalog;
import com.checkout.nexo.bff.operations.OperationsController;
import com.checkout.nexo.checkout.application.CheckoutService;
import com.checkout.nexo.checkout.application.PreCheckoutResult;
import com.checkout.nexo.checkout.application.PreCheckoutService;
import com.checkout.nexo.checkout.domain.CartItem;

/**
 * CORS de {@code /operations/**} (CORS-01): a origem do frontend local passa, qualquer outra nao, e
 * o que esta fora de {@code /operations/**} nao ganha cabecalho de CORS.
 */
@WebMvcTest(controllers = OperationsController.class)
@Import({ ErrorCatalog.class, CorsTest.OutsideOperationsController.class })
class CorsTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:4200";

	private static final String OTHER_ORIGIN = "http://evil.example";

	private static final String PRE_CHECKOUT_URL = "/operations/pre-checkout";

	private static final String ALLOW_ORIGIN = HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PreCheckoutService preCheckoutService;

	@MockitoBean
	private CheckoutService checkoutService;

	// ---- Preflight (CORS-01) -----------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "/operations/checkout", PRE_CHECKOUT_URL })
	void aPreflightFromTheFrontendOriginIsApprovedWithThatSameOrigin(String path) throws Exception {
		mockMvc.perform(options(path).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
				.andExpect(status().isOk())
				.andExpect(header().string(ALLOW_ORIGIN, ALLOWED_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "POST,OPTIONS"))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "/operations/checkout", PRE_CHECKOUT_URL })
	void aPreflightFromAnotherOriginGetsNoAllowOriginHeader(String path) throws Exception {
		mockMvc.perform(options(path).header(HttpHeaders.ORIGIN, OTHER_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
				.andExpect(header().doesNotExist(ALLOW_ORIGIN));
	}

	// ---- Requisicao real ---------------------------------------------------------------------

	@Test
	void aRealPostFromTheFrontendOriginAnswersWithTheAllowOriginHeader() throws Exception {
		CartItem premium = new CartItem("premium", "Plano Premium", "Assinatura mensal com catálogo completo",
				new BigDecimal("39.90"), "BRL");
		when(preCheckoutService.preCheckout(List.of("premium"))).thenReturn(
				new PreCheckoutResult(UUID.randomUUID(), List.of(premium), List.of("Cartão de Crédito")));

		mockMvc.perform(post(PRE_CHECKOUT_URL).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
				.contentType(MediaType.APPLICATION_JSON).content("{\"products\":[{\"slug\":\"premium\"}]}"))
				.andExpect(status().isCreated())
				.andExpect(header().string(ALLOW_ORIGIN, ALLOWED_ORIGIN));
	}

	@Test
	void aRealPostFromAnotherOriginGetsNoAllowOriginHeader() throws Exception {
		mockMvc.perform(post(PRE_CHECKOUT_URL).header(HttpHeaders.ORIGIN, OTHER_ORIGIN)
				.contentType(MediaType.APPLICATION_JSON).content("{\"products\":[{\"slug\":\"premium\"}]}"))
				.andExpect(header().doesNotExist(ALLOW_ORIGIN));
	}

	// ---- Fora de /operations/** --------------------------------------------------------------

	/** Um {@code POST} fora de {@code /operations/**}, com o mesmo metodo e origem que passariam dentro. */
	@RestController
	static class OutsideOperationsController {

		@PostMapping("/outside")
		String outside() {
			return "ok";
		}

	}

	@Test
	void aPreflightOutsideOperationsGetsNoAllowOriginHeader() throws Exception {
		mockMvc.perform(options("/outside").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(header().doesNotExist(ALLOW_ORIGIN));
	}

	@Test
	void aRealPostOutsideOperationsGetsNoAllowOriginHeader() throws Exception {
		mockMvc.perform(post("/outside").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)).andExpect(status().isOk())
				.andExpect(header().doesNotExist(ALLOW_ORIGIN));
	}

	// ---- app.cors.allowed-origins sobrescrito ------------------------------------------------

	/** Outro contexto: a lista de origens permitidas vem da propriedade, nao do codigo. */
	@Nested
	@TestPropertySource(properties = "app.cors.allowed-origins=https://loja.example,https://admin.example")
	class WithTheAllowedOriginsOverridden {

		@Autowired
		private MockMvc overriddenMockMvc;

		@ParameterizedTest
		@ValueSource(strings = { "https://loja.example", "https://admin.example" })
		void everyConfiguredOriginIsApproved(String origin) throws Exception {
			overriddenMockMvc.perform(options("/operations/checkout").header(HttpHeaders.ORIGIN, origin)
					.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
					.andExpect(header().string(ALLOW_ORIGIN, origin));
		}

		@Test
		void theDefaultFrontendOriginIsNoLongerApproved() throws Exception {
			overriddenMockMvc.perform(options("/operations/checkout").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
					.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
					.andExpect(header().doesNotExist(ALLOW_ORIGIN));
		}

	}

}
