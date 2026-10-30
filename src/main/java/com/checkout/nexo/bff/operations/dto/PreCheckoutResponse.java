package com.checkout.nexo.bff.operations.dto;

import java.util.List;
import java.util.UUID;

/**
 * Corpo de sucesso do pre-checkout (PRE-01): chaves {@code cartId}, {@code products} e
 * {@code paymentMethods}, nessa ordem.
 */
public record PreCheckoutResponse(UUID cartId, List<ProductView> products, List<PaymentMethodView> paymentMethods) {
}
