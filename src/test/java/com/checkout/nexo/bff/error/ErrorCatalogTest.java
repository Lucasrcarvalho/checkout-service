package com.checkout.nexo.bff.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.checkout.nexo.checkout.domain.CheckoutErrorCode;

class ErrorCatalogTest {

	private static final UUID CART_ID = UUID.fromString("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");

	private static final String DEFAULT_ROUTE = "/checkout/pagamento";

	private final ErrorCatalog catalog = new ErrorCatalog(DEFAULT_ROUTE);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(ErrorCatalog.class);

	@ParameterizedTest
	@EnumSource(CheckoutErrorCode.class)
	void everyCodeHasAllMandatoryFieldsFilled(CheckoutErrorCode code) {
		ErrorCatalog.ErrorDefinition definition = catalog.get(code);

		assertThat(definition.httpStatus()).isNotNull();
		assertThat(definition.displayType()).isNotNull();
		assertThat(definition.frontendError().code()).isNotBlank();
		assertThat(definition.frontendError().title()).isNotBlank();
		assertThat(definition.frontendError().description()).isNotBlank();
		assertThat(definition.frontendError().primaryButton().label()).isNotBlank();
		assertThat(definition.frontendError().primaryButton().url()).isNotBlank();
	}

	/** Uma linha por errorCode da tabela de ERR-02; os textos sao literais do spec. */
	static Stream<Arguments> err02Rows() {
		return Stream.of(
				Arguments.of(CheckoutErrorCode.INVALID_REQUEST, 400, DisplayType.BLOCK, "R-1001",
						"Não foi possível processar o pedido",
						"Os dados enviados estão incompletos ou em formato inválido. Tente novamente.",
						"Tentar novamente", "RETRY"),
				Arguments.of(CheckoutErrorCode.PRODUCT_NOT_FOUND, 422, DisplayType.BLOCK, "C-1001",
						"Plano indisponível",
						"Um dos planos escolhidos não está disponível. Escolha outro plano para continuar.",
						"Escolher outro plano", "RESTART"),
				Arguments.of(CheckoutErrorCode.CART_NOT_FOUND, 404, DisplayType.BLOCK, "C-1002",
						"Carrinho não encontrado",
						"Não encontramos este carrinho. Recomece o pedido para continuar.",
						"Recomeçar", "RESTART"),
				Arguments.of(CheckoutErrorCode.PAYMENT_METHOD_NOT_ALLOWED, 422, DisplayType.INTERVENTION, "P-1003",
						"Meio de pagamento indisponível",
						"Este meio de pagamento não está disponível para o seu carrinho. Escolha outro meio.",
						"Escolher outro meio", "/checkout/pagamento"),
				Arguments.of(CheckoutErrorCode.INVALID_PAYMENT_DATA, 422, DisplayType.INTERVENTION, "P-1002",
						"Confira os dados do pagamento",
						"Alguns dados do pagamento estão incorretos. Corrija e tente novamente.",
						"Corrigir dados", "DISMISS"),
				Arguments.of(CheckoutErrorCode.PAYMENT_DECLINED, 422, DisplayType.BLOCK, "P-1001",
						"Pagamento não aprovado",
						"Não foi possível concluir o pagamento. Tente novamente ou escolha outro meio.",
						"Tentar novamente", "RETRY"),
				Arguments.of(CheckoutErrorCode.CART_ALREADY_PAID, 409, DisplayType.BLOCK, "P-1004",
						"Pagamento já confirmado",
						"Este pedido já foi pago. Não é possível pagar novamente.",
						"Voltar ao início", "RESTART"),
				Arguments.of(CheckoutErrorCode.SERVICE_UNAVAILABLE, 503, DisplayType.BLOCK, "S-1001",
						"Serviço temporariamente indisponível",
						"Não conseguimos concluir agora. Tente novamente em instantes.",
						"Tentar novamente", "RETRY"),
				Arguments.of(CheckoutErrorCode.INTERNAL_ERROR, 500, DisplayType.BLOCK, "S-1002",
						"Algo deu errado",
						"Ocorreu um erro inesperado. Tente novamente.",
						"Tentar novamente", "RETRY"));
	}

	@ParameterizedTest
	@MethodSource("err02Rows")
	void matchesTheErr02RowCharacterForCharacter(CheckoutErrorCode errorCode, int httpStatus,
			DisplayType displayType, String code, String title, String description, String primaryLabel,
			String primaryUrl) {
		ErrorResponse response = catalog.toResponse(errorCode, null);

		assertThat(catalog.get(errorCode).httpStatus().value()).isEqualTo(httpStatus);
		assertThat(response.displayType()).isEqualTo(displayType);
		assertThat(response.errorCode()).isEqualTo(errorCode);
		assertThat(response.frontendError().code()).isEqualTo(code);
		assertThat(response.frontendError().title()).isEqualTo(title);
		assertThat(response.frontendError().description()).isEqualTo(description);
		assertThat(response.frontendError().primaryButton()).isEqualTo(new ErrorButton(primaryLabel, primaryUrl));
	}

	@Test
	void paymentDeclinedHasTheSwitchPaymentMethodSecondaryButton() {
		ErrorResponse response = catalog.toResponse(CheckoutErrorCode.PAYMENT_DECLINED, CART_ID);

		assertThat(response.frontendError().secondaryButton())
				.isEqualTo(new ErrorButton("Trocar meio de pagamento", "/checkout/pagamento"));
	}

	@ParameterizedTest
	@EnumSource(value = CheckoutErrorCode.class, mode = EnumSource.Mode.EXCLUDE, names = "PAYMENT_DECLINED")
	void everyOtherCodeHasNoSecondaryButton(CheckoutErrorCode code) {
		assertThat(catalog.toResponse(code, null).frontendError().secondaryButton()).isNull();
	}

	@ParameterizedTest
	@EnumSource(CheckoutErrorCode.class)
	void toResponseKeepsTheInformedCartId(CheckoutErrorCode code) {
		assertThat(catalog.toResponse(code, CART_ID).cartId()).isEqualTo(CART_ID);
	}

	@Test
	void toResponseKeepsANullCartId() {
		assertThat(catalog.toResponse(CheckoutErrorCode.CART_NOT_FOUND, null).cartId()).isNull();
	}

	// ---- Rota do frontend configuravel (F1, ERR-02) -------------------------------------------

	@Test
	void withoutThePropertyBothPaymentButtonsUseTheEnunciadoRoute() {
		contextRunner.run(context -> {
			ErrorCatalog bean = context.getBean(ErrorCatalog.class);

			assertThat(bean.get(CheckoutErrorCode.PAYMENT_METHOD_NOT_ALLOWED).frontendError().primaryButton().url())
					.isEqualTo("/checkout/pagamento");
			assertThat(bean.get(CheckoutErrorCode.PAYMENT_DECLINED).frontendError().secondaryButton().url())
					.isEqualTo("/checkout/pagamento");
		});
	}

	@Test
	void theConfiguredRouteReplacesTheRouteOfTheTwoPaymentButtons() {
		contextRunner.withPropertyValues("app.frontend.payment-route=/pagamento").run(context -> {
			ErrorCatalog bean = context.getBean(ErrorCatalog.class);

			assertThat(bean.get(CheckoutErrorCode.PAYMENT_METHOD_NOT_ALLOWED).frontendError().primaryButton())
					.isEqualTo(new ErrorButton("Escolher outro meio", "/pagamento"));
			assertThat(bean.get(CheckoutErrorCode.PAYMENT_DECLINED).frontendError().secondaryButton())
					.isEqualTo(new ErrorButton("Trocar meio de pagamento", "/pagamento"));
		});
	}

	@ParameterizedTest
	@EnumSource(value = CheckoutErrorCode.class, mode = EnumSource.Mode.EXCLUDE,
			names = { "PAYMENT_METHOD_NOT_ALLOWED", "PAYMENT_DECLINED" })
	void theConfiguredRouteDoesNotChangeTheOtherSevenCodes(CheckoutErrorCode code) {
		contextRunner.withPropertyValues("app.frontend.payment-route=/pagamento").run(context -> {
			ErrorCatalog bean = context.getBean(ErrorCatalog.class);

			assertThat(bean.toResponse(code, CART_ID)).isEqualTo(catalog.toResponse(code, CART_ID));
		});
	}

	@Test
	void theConfiguredRouteKeepsThePaymentDeclinedPrimaryButton() {
		contextRunner.withPropertyValues("app.frontend.payment-route=/pagamento").run(context -> {
			ErrorCatalog bean = context.getBean(ErrorCatalog.class);

			assertThat(bean.get(CheckoutErrorCode.PAYMENT_DECLINED).frontendError().primaryButton())
					.isEqualTo(new ErrorButton("Tentar novamente", "RETRY"));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void aBlankRouteFailsTheCatalogInitializationWithAClearMessage(String blank) {
		contextRunner.withPropertyValues("app.frontend.payment-route=" + blank).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("app.frontend.payment-route");
		});
	}

}
