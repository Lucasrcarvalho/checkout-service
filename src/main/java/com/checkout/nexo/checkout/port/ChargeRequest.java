package com.checkout.nexo.checkout.port;

import java.math.BigDecimal;
import java.util.UUID;

import com.checkout.nexo.checkout.domain.AllowedPaymentMethod;
import com.checkout.nexo.checkout.domain.CardData;

/**
 * Pedido de cobranca a um processador de pagamento. {@code card} e {@code null} nos meios que nao
 * exigem cartao (Pix).
 */
public record ChargeRequest(UUID cartId, BigDecimal amount, String currency, AllowedPaymentMethod method,
		CardData card) {
}
