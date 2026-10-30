package com.checkout.nexo.integration.payment;

import org.springframework.stereotype.Component;

import com.checkout.nexo.checkout.port.ChargeRequest;
import com.checkout.nexo.checkout.port.ChargeResult;
import com.checkout.nexo.checkout.port.PaymentProcessor;

/**
 * Cobranca simulada (CHK-02, CHK-06): cartao cujo numero termina em {@code 0002} e recusado; os
 * demais cartoes e o Pix sao aprovados.
 */
@Component
public class SimulatedPaymentProcessor implements PaymentProcessor {

	private static final String DECLINED_SUFFIX = "0002";

	private final PixCodeGenerator pixCodeGenerator;

	public SimulatedPaymentProcessor(PixCodeGenerator pixCodeGenerator) {
		this.pixCodeGenerator = pixCodeGenerator;
	}

	/**
	 * @throws IllegalArgumentException se o meio exige cartao e {@code card} esta ausente; a mensagem
	 * nao carrega valor da requisicao
	 */
	@Override
	public ChargeResult charge(ChargeRequest request) {
		if (!request.method().requiresCard()) {
			return new ChargeResult.Approved(null, pixCodeGenerator.generate(request.cartId()));
		}
		if (request.card() == null) {
			throw new IllegalArgumentException("Meio de pagamento que exige cartao sem dados do cartao");
		}
		if (request.card().number().endsWith(DECLINED_SUFFIX)) {
			return new ChargeResult.Declined();
		}
		return new ChargeResult.Approved(request.card().lastDigits(), null);
	}

}
