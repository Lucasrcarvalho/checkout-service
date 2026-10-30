package com.checkout.nexo.checkout.application;

import java.util.List;
import java.util.UUID;

import com.checkout.nexo.checkout.domain.CartItem;

/**
 * Resultado do pre-checkout: o carrinho criado, os itens na ordem da requisicao e os nomes dos
 * meios de pagamento permitidos.
 */
public record PreCheckoutResult(UUID cartId, List<CartItem> items, List<String> paymentMethodNames) {
}
