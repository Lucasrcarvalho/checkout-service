package com.checkout.nexo.bff.operations.dto;

import java.math.BigDecimal;

/**
 * Produto na resposta do pre-checkout (PRE-01); {@code price} em escala 2.
 */
public record ProductView(String slug, String name, String description, BigDecimal price, String currency) {
}
