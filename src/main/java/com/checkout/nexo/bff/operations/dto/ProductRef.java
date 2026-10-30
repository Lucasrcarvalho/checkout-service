package com.checkout.nexo.bff.operations.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Produto escolhido no pre-checkout (PRE-04: {@code slug} em {@code ^[a-z0-9-]{1,50}$}).
 */
public record ProductRef(@NotNull @Pattern(regexp = "^[a-z0-9-]{1,50}$") String slug) {
}
