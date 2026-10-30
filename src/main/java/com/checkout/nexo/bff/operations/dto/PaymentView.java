package com.checkout.nexo.bff.operations.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Pagamento aprovado (CHK-01, CHK-02). No sucesso as chaves que nao se aplicam sao omitidas:
 * {@code pixCode} no cartao, {@code lastDigits} no Pix. {@code amount} em escala 2.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentView(String method, String lastDigits, String pixCode, BigDecimal amount, String currency) {
}
