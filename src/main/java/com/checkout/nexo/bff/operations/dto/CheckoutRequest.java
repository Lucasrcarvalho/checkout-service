package com.checkout.nexo.bff.operations.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Corpo de {@code POST /operations/checkout}. A validacao estrutural (CHK-14) cobre so
 * {@code cartId}, {@code paymentMethod} e {@code paymentMethod.name}; o cartao nao tem Bean Validation.
 *
 * <p>{@code cartId} chega como texto e so vira {@code UUID} depois de validado no formato canonico
 * (36 caracteres, 8-4-4-4-12 hexadecimais, maiusculas ou minusculas): o desserializador de UUID do
 * Jackson aceitaria tambem Base64 de 24 caracteres e espacos ao redor, e {@code UUID.fromString}
 * aceita formas curtas como {@code 1-1-1-1-1}.
 */
public record CheckoutRequest(
		@NotNull @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String cartId,
		@NotNull @Valid PaymentMethodRequest paymentMethod) {
}
