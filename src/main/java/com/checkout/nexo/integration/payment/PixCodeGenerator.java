package com.checkout.nexo.integration.payment;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Gera o codigo Pix simulado (CHK-02): deterministico a partir do carrinho.
 */
@Component
public class PixCodeGenerator {

	private static final String PREFIX = "PIX-SIMULADO-";

	public String generate(UUID cartId) {
		return PREFIX + cartId;
	}

}
