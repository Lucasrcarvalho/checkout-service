package com.checkout.nexo.bff.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.checkout.nexo.bff.error.ErrorCatalog;
import com.checkout.nexo.checkout.application.CheckoutService;
import com.checkout.nexo.checkout.application.PreCheckoutResult;
import com.checkout.nexo.checkout.application.PreCheckoutService;
import com.checkout.nexo.checkout.domain.CartItem;
import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.checkout.nexo.checkout.domain.CheckoutException;
import com.checkout.nexo.checkout.port.ExternalServiceUnavailableException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /operations/pre-checkout} (PRE-01, PRE-03, PRE-04) com o servico simulado. Os JSONs
 * esperados sao os literais do spec (PRE-01 e ERR-02), nao derivados do codigo.
 */
@WebMvcTest(controllers = OperationsController.class)
@Import(ErrorCatalog.class)
class PreCheckoutEndpointTest {

	private static final String URL = "/operations/pre-checkout";

	private static final UUID CART_ID = UUID.fromString("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");

	private static final CartItem PREMIUM = new CartItem("premium", "Plano Premium",
			"Assinatura mensal com catálogo completo", new BigDecimal("39.90"), "BRL");

	private static final List<String> METHOD_NAMES = List.of("Cartão de Crédito", "Cartão de Débito", "Pix");

	/** Corpo de sucesso de PRE-01 para o slug {@code premium}. */
	private static final String PRE_01_BODY = "{\"cartId\":\"7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33\",\"products\":["
			+ "{\"slug\":\"premium\",\"name\":\"Plano Premium\",\"description\":\"Assinatura mensal com catálogo completo\","
			+ "\"price\":39.90,\"currency\":\"BRL\"}],"
			+ "\"paymentMethods\":[{\"name\":\"Cartão de Crédito\"},{\"name\":\"Cartão de Débito\"},{\"name\":\"Pix\"}]}";

	/** Corpos de erro de ERR-01/ERR-02 (cartId e secondaryButton presentes como null). */
	private static final String INVALID_REQUEST_JSON = "{\"displayType\":\"BLOCK\",\"errorCode\":\"INVALID_REQUEST\","
			+ "\"cartId\":null,\"frontendError\":{\"code\":\"R-1001\",\"title\":\"Não foi possível processar o pedido\","
			+ "\"description\":\"Os dados enviados estão incompletos ou em formato inválido. Tente novamente.\","
			+ "\"primaryButton\":{\"label\":\"Tentar novamente\",\"url\":\"RETRY\"},\"secondaryButton\":null}}";

	private static final String PRODUCT_NOT_FOUND_JSON = "{\"displayType\":\"BLOCK\",\"errorCode\":\"PRODUCT_NOT_FOUND\","
			+ "\"cartId\":null,\"frontendError\":{\"code\":\"C-1001\",\"title\":\"Plano indisponível\","
			+ "\"description\":\"Um dos planos escolhidos não está disponível. Escolha outro plano para continuar.\","
			+ "\"primaryButton\":{\"label\":\"Escolher outro plano\",\"url\":\"RESTART\"},\"secondaryButton\":null}}";

	private static final String SERVICE_UNAVAILABLE_JSON = "{\"displayType\":\"BLOCK\",\"errorCode\":\"SERVICE_UNAVAILABLE\","
			+ "\"cartId\":null,\"frontendError\":{\"code\":\"S-1001\",\"title\":\"Serviço temporariamente indisponível\","
			+ "\"description\":\"Não conseguimos concluir agora. Tente novamente em instantes.\","
			+ "\"primaryButton\":{\"label\":\"Tentar novamente\",\"url\":\"RETRY\"},\"secondaryButton\":null}}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@MockitoBean
	private PreCheckoutService preCheckoutService;

	/** Dependencia do mesmo controller desde a T26; este teste nao a exercita. */
	@MockitoBean
	private CheckoutService checkoutService;

	private ResultActions postJson(String body) throws Exception {
		return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String bodyOf(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private static String productsJson(List<String> slugs) {
		return "{\"products\":[" + String.join(",", slugs.stream().map(slug -> "{\"slug\":\"" + slug + "\"}").toList())
				+ "]}";
	}

	// ---- Sucesso (PRE-01) -------------------------------------------------------------------

	@Test
	void createsTheCartWith201AndTheExactPre01Body() throws Exception {
		when(preCheckoutService.preCheckout(List.of("premium")))
				.thenReturn(new PreCheckoutResult(CART_ID, List.of(PREMIUM), METHOD_NAMES));

		ResultActions result = postJson("{\"products\":[{\"slug\":\"premium\"}]}").andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

		assertThat(bodyOf(result)).isEqualTo(PRE_01_BODY);
	}

	@Test
	void theBodyKeysAreExactlyTheContractOnesAndPaymentMethodsOnlyHaveName() throws Exception {
		when(preCheckoutService.preCheckout(List.of("premium")))
				.thenReturn(new PreCheckoutResult(CART_ID, List.of(PREMIUM), METHOD_NAMES));

		Map<String, Object> body = jsonMapper.readValue(
				bodyOf(postJson("{\"products\":[{\"slug\":\"premium\"}]}").andExpect(status().isCreated())),
				new TypeReference<Map<String, Object>>() {
				});

		assertThat(body.keySet()).containsExactly("cartId", "products", "paymentMethods");
		assertThat(asObjects(body.get("products"))).singleElement().satisfies(
				product -> assertThat(product.keySet()).containsExactly("slug", "name", "description", "price",
						"currency"));
		assertThat(asObjects(body.get("paymentMethods"))).hasSize(3)
				.allSatisfy(method -> assertThat(method.keySet()).containsExactly("name"));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> asObjects(Object value) {
		return (List<Map<String, Object>>) value;
	}

	@Test
	void forwardsTheSlugsToTheServiceInTheRequestOrderAndKeepsTheServiceItemOrder() throws Exception {
		CartItem basico = new CartItem("basico", "Plano Básico", "Assinatura mensal com o essencial do catálogo",
				new BigDecimal("19.90"), "BRL");
		CartItem familia = new CartItem("familia", "Plano Família", "Assinatura mensal para até 4 perfis",
				new BigDecimal("59.90"), "BRL");
		when(preCheckoutService.preCheckout(List.of("familia", "basico")))
				.thenReturn(new PreCheckoutResult(CART_ID, List.of(familia, basico), METHOD_NAMES));

		ResultActions result = postJson(productsJson(List.of("familia", "basico"))).andExpect(status().isCreated());

		verify(preCheckoutService).preCheckout(List.of("familia", "basico"));
		Map<String, Object> body = jsonMapper.readValue(bodyOf(result), new TypeReference<Map<String, Object>>() {
		});
		assertThat(asObjects(body.get("products"))).extracting(product -> product.get("slug"))
				.containsExactly("familia", "basico");
		assertThat(bodyOf(result)).contains("\"price\":59.90").contains("\"price\":19.90");
	}

	@Test
	void acceptsTenProductsTheUpperBound() throws Exception {
		List<String> slugs = IntStream.rangeClosed(1, 10).mapToObj(i -> "p" + i).toList();
		when(preCheckoutService.preCheckout(slugs))
				.thenReturn(new PreCheckoutResult(CART_ID, List.of(PREMIUM), METHOD_NAMES));

		postJson(productsJson(slugs)).andExpect(status().isCreated());

		verify(preCheckoutService).preCheckout(slugs);
	}

	@Test
	void acceptsASlugOfFiftyCharactersWithLowercaseLettersDigitsAndHyphens() throws Exception {
		String slug = "a1-".repeat(16) + "bc";
		assertThat(slug).hasSize(50);
		when(preCheckoutService.preCheckout(List.of(slug)))
				.thenReturn(new PreCheckoutResult(CART_ID, List.of(PREMIUM), METHOD_NAMES));

		postJson(productsJson(List.of(slug))).andExpect(status().isCreated());

		verify(preCheckoutService).preCheckout(List.of(slug));
	}

	// ---- Corpo invalido (PRE-04) ------------------------------------------------------------

	static Stream<Arguments> invalidBodies() {
		return Stream.of(
				Arguments.of("products ausente", "{}"),
				Arguments.of("products nulo", "{\"products\":null}"),
				Arguments.of("products vazio", "{\"products\":[]}"),
				Arguments.of("11 itens", productsJson(IntStream.rangeClosed(1, 11).mapToObj(i -> "p" + i).toList())),
				Arguments.of("item nulo", "{\"products\":[null]}"),
				Arguments.of("slug ausente", "{\"products\":[{}]}"),
				Arguments.of("slug nulo", "{\"products\":[{\"slug\":null}]}"),
				Arguments.of("slug com maiuscula", "{\"products\":[{\"slug\":\"Premium\"}]}"),
				Arguments.of("slug com espaco", "{\"products\":[{\"slug\":\"plano premium\"}]}"),
				Arguments.of("slug vazio", "{\"products\":[{\"slug\":\"\"}]}"),
				Arguments.of("slug com 51 caracteres", productsJson(List.of("a".repeat(51)))),
				Arguments.of("JSON malformado", "{\"products\": [ {\"slug\": }"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidBodies")
	void anInvalidBodyIs400InvalidRequestAndNeverReachesTheService(String description, String body)
			throws Exception {
		ResultActions result = postJson(body).andExpect(status().isBadRequest());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
		verifyNoInteractions(preCheckoutService);
	}

	@Test
	void aRequestWithoutBodyIs400InvalidRequestAndNeverReachesTheService() throws Exception {
		ResultActions result = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
		verifyNoInteractions(preCheckoutService);
	}

	@Test
	void aWrongContentTypeIs415InvalidRequestAndNeverReachesTheService() throws Exception {
		ResultActions result = mockMvc
				.perform(post(URL).contentType(MediaType.TEXT_PLAIN).content("{\"products\":[{\"slug\":\"premium\"}]}"))
				.andExpect(status().isUnsupportedMediaType());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
		verifyNoInteractions(preCheckoutService);
	}

	@Test
	void repeatedSlugsRejectedByTheServiceBecome400InvalidRequest() throws Exception {
		when(preCheckoutService.preCheckout(List.of("basico", "basico")))
				.thenThrow(new CheckoutException(CheckoutErrorCode.INVALID_REQUEST));

		ResultActions result = postJson(productsJson(List.of("basico", "basico"))).andExpect(status().isBadRequest());

		assertThat(bodyOf(result)).isEqualTo(INVALID_REQUEST_JSON);
	}

	// ---- Falhas do servico (PRE-03, EXT-04) -------------------------------------------------

	@Test
	void anUnknownProductIs422WithTheExactProductNotFoundBodyAndNullCartId() throws Exception {
		when(preCheckoutService.preCheckout(List.of("inexistente")))
				.thenThrow(new CheckoutException(CheckoutErrorCode.PRODUCT_NOT_FOUND));

		ResultActions result = postJson(productsJson(List.of("inexistente"))).andExpect(status().isUnprocessableContent());

		assertThat(bodyOf(result)).isEqualTo(PRODUCT_NOT_FOUND_JSON);
	}

	@Test
	void anUnavailableExternalServiceIs503ServiceUnavailable() throws Exception {
		when(preCheckoutService.preCheckout(List.of("premium")))
				.thenThrow(new ExternalServiceUnavailableException("produtos", new IOException("io")));

		ResultActions result = postJson(productsJson(List.of("premium"))).andExpect(status().isServiceUnavailable());

		assertThat(bodyOf(result)).isEqualTo(SERVICE_UNAVAILABLE_JSON);
	}

}
