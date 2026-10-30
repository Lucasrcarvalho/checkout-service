package com.checkout.nexo.bff.operations.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Meio de pagamento escolhido; {@code card} so existe nos meios que exigem cartao.
 */
public record PaymentMethodRequest(@NotBlank String name, CardRequest card) {
}
