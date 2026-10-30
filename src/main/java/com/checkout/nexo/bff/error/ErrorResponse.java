package com.checkout.nexo.bff.error;

import java.util.UUID;

import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Corpo unico de erro da API (ERR-01). {@code cartId} e sempre emitido, {@code null} quando
 * nao se aplica (D-08), independente do default global de inclusao.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ErrorResponse(DisplayType displayType, CheckoutErrorCode errorCode, UUID cartId,
		FrontendError frontendError) {
}
