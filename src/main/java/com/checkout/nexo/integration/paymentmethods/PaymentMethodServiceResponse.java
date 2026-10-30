package com.checkout.nexo.integration.paymentmethods;

import com.checkout.nexo.checkout.port.CatalogPaymentMethod;
import com.checkout.nexo.checkout.port.ExternalServiceUnavailableException;

/**
 * Item do contrato do servico de meios de pagamento. {@code requiresCard} e {@link Boolean}
 * (nao {@code boolean}) para distinguir campo ausente de {@code false}.
 */
record PaymentMethodServiceResponse(String code, String name, Boolean requiresCard) {

	/**
	 * @throws ExternalServiceUnavailableException se o item ou algum campo obrigatorio faltar
	 * (contrato quebrado, sem {@code cause})
	 */
	static CatalogPaymentMethod toCatalogPaymentMethod(PaymentMethodServiceResponse item) {
		if (item == null || item.code == null || item.name == null || item.requiresCard == null) {
			throw new ExternalServiceUnavailableException("Resposta do servico de meios de pagamento fora do contrato",
					null);
		}
		return new CatalogPaymentMethod(item.code, item.name, item.requiresCard);
	}

}
