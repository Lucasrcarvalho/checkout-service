package com.checkout.nexo.checkout.port;

import java.util.List;

/**
 * Porta do catalogo de meios de pagamento (EXT-01).
 */
public interface PaymentMethodCatalog {

	/**
	 * @return todos os meios de pagamento, na ordem do servico de origem
	 * @throws ExternalServiceUnavailableException se o servico de origem estiver fora ou responder fora
	 * do contrato
	 */
	List<CatalogPaymentMethod> findAll();

}
