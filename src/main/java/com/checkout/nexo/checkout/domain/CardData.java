package com.checkout.nexo.checkout.domain;

/**
 * Dados do cartao em transito dentro de uma requisicao. Nunca e persistido (DATA-01) e o
 * {@link #toString()} nao revela nenhum campo (DATA-03, AD-008).
 */
public record CardData(String holderName, String number, String expiry, String cvv) {

	private static final int LAST_DIGITS_LENGTH = 4;

	/** Os 4 ultimos caracteres do numero. */
	public String lastDigits() {
		if (number.length() < LAST_DIGITS_LENGTH) {
			throw new IllegalArgumentException("Numero do cartao com menos de " + LAST_DIGITS_LENGTH + " caracteres");
		}
		return number.substring(number.length() - LAST_DIGITS_LENGTH);
	}

	@Override
	public String toString() {
		return "CardData[****]";
	}

}
