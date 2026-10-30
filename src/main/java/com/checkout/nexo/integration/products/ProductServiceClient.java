package com.checkout.nexo.integration.products;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.checkout.nexo.checkout.port.CatalogProduct;
import com.checkout.nexo.checkout.port.ExternalServiceUnavailableException;
import com.checkout.nexo.checkout.port.ProductCatalog;
import com.checkout.nexo.integration.config.CacheConfig;

/**
 * Adaptador de {@link ProductCatalog} por HTTP (EXT-01, EXT-04).
 */
@Component
public class ProductServiceClient implements ProductCatalog {

	private static final String PRODUCTS_PATH = "/mock-services/products";

	private final RestClient restClient;

	public ProductServiceClient(@Qualifier("productsRestClient") RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	@Cacheable(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "'all'")
	public List<CatalogProduct> findAll() {
		List<ProductServiceResponse> items;
		try {
			items = restClient.get().uri(PRODUCTS_PATH).retrieve()
					.body(new ParameterizedTypeReference<List<ProductServiceResponse>>() {
					});
		}
		catch (RestClientException ex) {
			throw new ExternalServiceUnavailableException("Servico de produtos indisponivel", ex);
		}
		if (items == null) {
			throw new ExternalServiceUnavailableException("Resposta do servico de produtos vazia", null);
		}
		return items.stream().map(ProductServiceResponse::toCatalogProduct).toList();
	}

}
