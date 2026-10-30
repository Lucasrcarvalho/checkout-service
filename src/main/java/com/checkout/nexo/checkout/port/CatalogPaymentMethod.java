package com.checkout.nexo.checkout.port;

/**
 * Meio de pagamento do catalogo como a regra de checkout o enxerga.
 */
public record CatalogPaymentMethod(String code, String name, boolean requiresCard) {
}
