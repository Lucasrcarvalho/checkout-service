package com.checkout.nexo.bff.operations.dto;

/**
 * Cartao como chega na requisicao. De proposito sem nenhuma anotacao de Bean Validation: a mensagem
 * padrao do Spring para uma violacao inclui o valor rejeitado, e o numero do cartao nao pode ir
 * para log nem para resposta (AD-008). Quem valida os campos e o {@code CardValidator}. O
 * {@link #toString()} nao revela nenhum campo (DATA-03).
 */
public record CardRequest(String holderName, String number, String expiry, String cvv) {

	@Override
	public String toString() {
		return "CardRequest[****]";
	}

}
