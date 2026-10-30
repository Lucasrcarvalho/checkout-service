package com.checkout.nexo.checkout.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Snapshot de um produto no momento do pre-checkout (PRE-05).
 */
@Embeddable
public record CartItem(
		@Column(name = "slug", nullable = false, length = 50) String slug,
		@Column(name = "name", nullable = false, length = 120) String name,
		@Column(name = "description", nullable = false) String description,
		@Column(name = "unit_price", nullable = false, precision = 10, scale = 2) BigDecimal unitPrice,
		@Column(name = "currency", nullable = false, length = 3) String currency) {
}
