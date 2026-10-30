package com.checkout.nexo.checkout.domain;

/**
 * Ciclo de vida do carrinho: so vai de {@code OPEN} para {@code APPROVED}; {@code APPROVED} e terminal.
 */
public enum CartStatus {

	OPEN,
	APPROVED

}
