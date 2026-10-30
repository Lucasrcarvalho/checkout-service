package com.checkout.nexo.integration.products;

import java.math.BigDecimal;

import com.checkout.nexo.checkout.port.CatalogProduct;
import com.checkout.nexo.checkout.port.ExternalServiceUnavailableException;

/**
 * Item do contrato do servico de produtos. E o formato do servico, nao o da regra de checkout:
 * so {@link #toCatalogProduct(ProductServiceResponse)} atravessa para o dominio.
 */
record ProductServiceResponse(String slug, String name, String description, BigDecimal price, String currency) {

	/**
	 * @throws ExternalServiceUnavailableException se o item ou algum campo obrigatorio faltar, ou se o
	 * preco nao couber em escala 2 sem arredondar (contrato quebrado, sem {@code cause})
	 */
	static CatalogProduct toCatalogProduct(ProductServiceResponse item) {
		if (item == null || item.slug == null || item.name == null || item.description == null || item.price == null
				|| item.currency == null) {
			throw outOfContract();
		}
		try {
			return new CatalogProduct(item.slug, item.name, item.description, item.price.setScale(2), item.currency);
		}
		catch (ArithmeticException ex) {
			throw outOfContract();
		}
	}

	private static ExternalServiceUnavailableException outOfContract() {
		return new ExternalServiceUnavailableException("Resposta do servico de produtos fora do contrato", null);
	}

}
