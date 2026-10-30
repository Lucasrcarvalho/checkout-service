package com.checkout.nexo.bff.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code secondaryButton} e sempre emitido, {@code null} quando nao se aplica (D-08).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FrontendError(String code, String title, String description, ErrorButton primaryButton,
		ErrorButton secondaryButton) {
}
