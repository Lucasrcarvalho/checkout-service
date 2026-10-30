package com.checkout.nexo.checkout.application;

import java.util.UUID;

import com.checkout.nexo.checkout.domain.CardData;

/**
 * Entrada do checkout. {@code card} e {@code null} nos meios que nao exigem cartao (Pix).
 */
public record CheckoutCommand(UUID cartId, String methodName, CardData card) {
}
