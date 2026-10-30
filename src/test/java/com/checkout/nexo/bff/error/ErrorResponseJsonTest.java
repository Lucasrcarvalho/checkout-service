package com.checkout.nexo.bff.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ErrorResponseJsonTest {

	private static final UUID CART_ID = UUID.fromString("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");

	private final JsonMapper defaultMapper = JsonMapper.builder().build();

	private final JsonMapper nonNullMapper = JsonMapper.builder()
			.changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
			.build();

	@Test
	void nullCartIdAndNullSecondaryButtonStayPresentAsNullEvenWithGlobalNonNullInclusion() {
		ErrorResponse response = new ErrorResponse(DisplayType.BLOCK, CheckoutErrorCode.PRODUCT_NOT_FOUND, null,
				new FrontendError("C-1001", "Plano indisponivel", "Escolha outro plano.",
						new ErrorButton("Escolher outro plano", "RESTART"), null));

		String json = nonNullMapper.writeValueAsString(response);

		assertThat(json).isEqualTo("{\"displayType\":\"BLOCK\",\"errorCode\":\"PRODUCT_NOT_FOUND\",\"cartId\":null,"
				+ "\"frontendError\":{\"code\":\"C-1001\",\"title\":\"Plano indisponivel\","
				+ "\"description\":\"Escolha outro plano.\","
				+ "\"primaryButton\":{\"label\":\"Escolher outro plano\",\"url\":\"RESTART\"},"
				+ "\"secondaryButton\":null}}");
	}

	@Test
	void keysAreExactlyThoseOfErr01InThatOrder() {
		ErrorResponse response = new ErrorResponse(DisplayType.BLOCK, CheckoutErrorCode.PAYMENT_DECLINED, CART_ID,
				new FrontendError("P-1001", "Pagamento nao aprovado", "Tente novamente.",
						new ErrorButton("Tentar novamente", "RETRY"),
						new ErrorButton("Trocar meio de pagamento", "/checkout/pagamento")));

		JsonNode json = defaultMapper.readTree(defaultMapper.writeValueAsString(response));

		assertThat(json.propertyNames()).containsExactly("displayType", "errorCode", "cartId", "frontendError");
		assertThat(json.get("frontendError").propertyNames())
				.containsExactly("code", "title", "description", "primaryButton", "secondaryButton");
		assertThat(json.get("frontendError").get("primaryButton").propertyNames()).containsExactly("label", "url");
		assertThat(json.get("frontendError").get("secondaryButton").propertyNames()).containsExactly("label", "url");
	}

	@Test
	void errorCodeSerializesAsItsNameAndCartIdAsUuidText() {
		ErrorResponse response = new ErrorResponse(DisplayType.INTERVENTION, CheckoutErrorCode.PAYMENT_DECLINED,
				CART_ID, new FrontendError("P-1001", "t", "d", new ErrorButton("l", "u"), null));

		JsonNode json = defaultMapper.readTree(defaultMapper.writeValueAsString(response));

		assertThat(json.get("errorCode").stringValue()).isEqualTo("PAYMENT_DECLINED");
		assertThat(json.get("cartId").stringValue()).isEqualTo("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");
	}

	@Test
	void displayTypeHasExactlyBlockAndIntervention() {
		assertThat(DisplayType.values()).extracting(Enum::name).containsExactlyInAnyOrder("BLOCK", "INTERVENTION");
	}

	@ParameterizedTest
	@EnumSource(DisplayType.class)
	void displayTypeSerializesAsItsName(DisplayType displayType) {
		assertThat(defaultMapper.writeValueAsString(displayType)).isEqualTo("\"" + displayType.name() + "\"");
	}

}
