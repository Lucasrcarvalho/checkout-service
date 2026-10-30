package com.checkout.nexo.checkout.port;

/**
 * Porta de cobranca (EXT-01). O BFF simula a cobranca; nenhum gateway real e chamado.
 */
public interface PaymentProcessor {

	ChargeResult charge(ChargeRequest request);

}
