package com.checkout.nexo.mockservices;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Servico de meios de pagamento simulado (EXT-02), ativo so no profile {@code mock-services}.
 */
@RestController
@Profile("mock-services")
public class PaymentMethodServiceMockController {

	private static final List<MockPaymentMethod> PAYMENT_METHODS = List.of(
			new MockPaymentMethod("CREDIT_CARD", "Cartão de Crédito", true),
			new MockPaymentMethod("DEBIT_CARD", "Cartão de Débito", true),
			new MockPaymentMethod("PIX", "Pix", false));

	@GetMapping("/mock-services/payment-methods")
	public List<MockPaymentMethod> paymentMethods() {
		return PAYMENT_METHODS;
	}

	/** Contrato JSON do servico de meios de pagamento simulado. */
	public record MockPaymentMethod(String code, String name, boolean requiresCard) {
	}

}
