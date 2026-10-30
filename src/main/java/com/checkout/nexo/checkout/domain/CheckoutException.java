package com.checkout.nexo.checkout.domain;

import java.util.UUID;

/**
 * Falha de negocio do checkout. A mensagem e apenas o nome do codigo: nunca carrega
 * dado de entrada (AD-008, DATA-04).
 */
public class CheckoutException extends RuntimeException {

	private final CheckoutErrorCode code;

	private final UUID cartId;

	public CheckoutException(CheckoutErrorCode code) {
		this(code, null);
	}

	public CheckoutException(CheckoutErrorCode code, UUID cartId) {
		super(code.name());
		this.code = code;
		this.cartId = cartId;
	}

	public CheckoutErrorCode code() {
		return code;
	}

	/** Carrinho da requisicao quando conhecido; {@code null} caso contrario (ERR-03). */
	public UUID cartId() {
		return cartId;
	}

}
