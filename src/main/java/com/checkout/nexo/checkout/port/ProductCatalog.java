package com.checkout.nexo.checkout.port;

import java.util.List;

/**
 * Porta do catalogo de produtos (EXT-01). A regra de checkout so enxerga esta interface; quem a
 * implementa (HTTP, cache, mock) fica em {@code integration}.
 */
public interface ProductCatalog {

	/**
	 * @return todos os produtos do catalogo, na ordem do servico de origem
	 * @throws ExternalServiceUnavailableException se o servico de origem estiver fora ou responder fora
	 * do contrato
	 */
	List<CatalogProduct> findAll();

}
