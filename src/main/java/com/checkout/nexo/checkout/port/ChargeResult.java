package com.checkout.nexo.checkout.port;

/**
 * Resultado de uma cobranca: aprovada (com os 4 ultimos digitos do cartao ou o codigo Pix) ou
 * recusada.
 */
public sealed interface ChargeResult {

	/** Exatamente um dos campos vem preenchido: {@code lastDigits} (cartao) ou {@code pixCode} (Pix). */
	record Approved(String lastDigits, String pixCode) implements ChargeResult {
	}

	record Declined() implements ChargeResult {
	}

}
