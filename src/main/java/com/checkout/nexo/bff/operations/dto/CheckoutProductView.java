package com.checkout.nexo.bff.operations.dto;

import java.math.BigDecimal;

/**
 * Produto na resposta do checkout (CHK-01); {@code price} em escala 2.
 */
public record CheckoutProductView(String slug, String name, BigDecimal price) {
}
