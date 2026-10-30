package com.checkout.nexo.bff.operations.dto;

import java.util.List;
import java.util.UUID;

/**
 * Corpo de sucesso do checkout (CHK-01): chaves {@code cartId}, {@code status}, {@code paidAt},
 * {@code payment} e {@code products}, nessa ordem. {@code paidAt} e texto ISO-8601 UTC truncado em
 * segundos, para nao depender do formato de data padrao do Jackson.
 */
public record CheckoutResponse(UUID cartId, String status, String paidAt, PaymentView payment,
		List<CheckoutProductView> products) {
}
