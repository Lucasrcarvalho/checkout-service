package com.checkout.nexo.checkout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Snapshot de um meio de pagamento permitido para o carrinho (PRE-05).
 */
@Embeddable
public record AllowedPaymentMethod(
		@Column(name = "code", nullable = false, length = 32) String code,
		@Column(name = "name", nullable = false, length = 64) String name,
		@Column(name = "requires_card", nullable = false) boolean requiresCard) {
}
