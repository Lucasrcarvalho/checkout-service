package com.checkout.nexo.checkout.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.checkout.nexo.checkout.domain.CartItem;

/**
 * Copia imutavel do que o controller precisa para responder um checkout aprovado, para que ele
 * nunca toque numa entidade fora da transacao. {@code lastDigits} (cartao) e {@code pixCode} (Pix):
 * so um deles vem preenchido.
 */
public record ApprovedCheckout(UUID cartId, Instant paidAt, String methodName, String lastDigits, String pixCode,
		BigDecimal amount, String currency, List<CartItem> items) {
}
