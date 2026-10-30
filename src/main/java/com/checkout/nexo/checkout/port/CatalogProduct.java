package com.checkout.nexo.checkout.port;

import java.math.BigDecimal;

/**
 * Produto do catalogo como a regra de checkout o enxerga; {@code price} em escala 2.
 */
public record CatalogProduct(String slug, String name, String description, BigDecimal price, String currency) {
}
