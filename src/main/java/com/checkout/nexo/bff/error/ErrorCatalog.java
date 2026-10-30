package com.checkout.nexo.bff.error;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.checkout.nexo.checkout.domain.CheckoutErrorCode;

/**
 * Fonte unica de HTTP, {@code displayType} e textos de cada erro (ERR-02, ERR-06).
 */
@Component
public class ErrorCatalog {

	private final Map<CheckoutErrorCode, ErrorDefinition> definitions = new EnumMap<>(CheckoutErrorCode.class);

	/**
	 * @param paymentRoute rota da tela de pagamento do frontend ({@code app.frontend.payment-route},
	 * padrao {@code /checkout/pagamento}), usada nos botoes de PAYMENT_METHOD_NOT_ALLOWED e PAYMENT_DECLINED
	 * @throws IllegalArgumentException se a rota estiver em branco
	 */
	public ErrorCatalog(@Value("${app.frontend.payment-route:/checkout/pagamento}") String paymentRoute) {
		if (paymentRoute == null || paymentRoute.isBlank()) {
			throw new IllegalArgumentException("A propriedade app.frontend.payment-route nao pode ficar em branco");
		}
		define(CheckoutErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, DisplayType.BLOCK, "R-1001",
				"Não foi possível processar o pedido",
				"Os dados enviados estão incompletos ou em formato inválido. Tente novamente.",
				new ErrorButton("Tentar novamente", "RETRY"), null);
		define(CheckoutErrorCode.PRODUCT_NOT_FOUND, HttpStatus.UNPROCESSABLE_CONTENT, DisplayType.BLOCK, "C-1001",
				"Plano indisponível",
				"Um dos planos escolhidos não está disponível. Escolha outro plano para continuar.",
				new ErrorButton("Escolher outro plano", "RESTART"), null);
		define(CheckoutErrorCode.CART_NOT_FOUND, HttpStatus.NOT_FOUND, DisplayType.BLOCK, "C-1002",
				"Carrinho não encontrado",
				"Não encontramos este carrinho. Recomece o pedido para continuar.",
				new ErrorButton("Recomeçar", "RESTART"), null);
		define(CheckoutErrorCode.PAYMENT_METHOD_NOT_ALLOWED, HttpStatus.UNPROCESSABLE_CONTENT,
				DisplayType.INTERVENTION, "P-1003",
				"Meio de pagamento indisponível",
				"Este meio de pagamento não está disponível para o seu carrinho. Escolha outro meio.",
				new ErrorButton("Escolher outro meio", paymentRoute), null);
		define(CheckoutErrorCode.INVALID_PAYMENT_DATA, HttpStatus.UNPROCESSABLE_CONTENT, DisplayType.INTERVENTION,
				"P-1002",
				"Confira os dados do pagamento",
				"Alguns dados do pagamento estão incorretos. Corrija e tente novamente.",
				new ErrorButton("Corrigir dados", "DISMISS"), null);
		define(CheckoutErrorCode.PAYMENT_DECLINED, HttpStatus.UNPROCESSABLE_CONTENT, DisplayType.BLOCK, "P-1001",
				"Pagamento não aprovado",
				"Não foi possível concluir o pagamento. Tente novamente ou escolha outro meio.",
				new ErrorButton("Tentar novamente", "RETRY"),
				new ErrorButton("Trocar meio de pagamento", paymentRoute));
		define(CheckoutErrorCode.CART_ALREADY_PAID, HttpStatus.CONFLICT, DisplayType.BLOCK, "P-1004",
				"Pagamento já confirmado",
				"Este pedido já foi pago. Não é possível pagar novamente.",
				new ErrorButton("Voltar ao início", "RESTART"), null);
		define(CheckoutErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, DisplayType.BLOCK, "S-1001",
				"Serviço temporariamente indisponível",
				"Não conseguimos concluir agora. Tente novamente em instantes.",
				new ErrorButton("Tentar novamente", "RETRY"), null);
		define(CheckoutErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, DisplayType.BLOCK, "S-1002",
				"Algo deu errado",
				"Ocorreu um erro inesperado. Tente novamente.",
				new ErrorButton("Tentar novamente", "RETRY"), null);
	}

	public ErrorDefinition get(CheckoutErrorCode code) {
		return definitions.get(code);
	}

	public ErrorResponse toResponse(CheckoutErrorCode code, UUID cartId) {
		ErrorDefinition definition = get(code);
		return new ErrorResponse(definition.displayType(), code, cartId, definition.frontendError());
	}

	private void define(CheckoutErrorCode errorCode, HttpStatus httpStatus, DisplayType displayType, String code,
			String title, String description, ErrorButton primaryButton, ErrorButton secondaryButton) {
		definitions.put(errorCode, new ErrorDefinition(httpStatus, displayType,
				new FrontendError(code, title, description, primaryButton, secondaryButton)));
	}

	public record ErrorDefinition(HttpStatus httpStatus, DisplayType displayType, FrontendError frontendError) {
	}

}
