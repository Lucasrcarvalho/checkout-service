package com.checkout.nexo.bff.operations.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /operations/pre-checkout}: de 1 a 10 produtos (PRE-04). Slugs repetidos sao
 * rejeitados pelo {@code PreCheckoutService}.
 */
public record PreCheckoutRequest(@NotNull @Size(min = 1, max = 10) List<@NotNull @Valid ProductRef> products) {
}
