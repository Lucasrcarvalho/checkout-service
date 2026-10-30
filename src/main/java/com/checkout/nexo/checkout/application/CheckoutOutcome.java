package com.checkout.nexo.checkout.application;

import java.util.UUID;

/**
 * Resultado de um checkout que chegou a cobrar. A recusa e um resultado, nao uma excecao: lancar
 * dentro da transacao desfaria a tentativa {@code DECLINED} gravada.
 */
public sealed interface CheckoutOutcome {

	record Approved(ApprovedCheckout checkout) implements CheckoutOutcome {
	}

	record Declined(UUID cartId) implements CheckoutOutcome {
	}

}
