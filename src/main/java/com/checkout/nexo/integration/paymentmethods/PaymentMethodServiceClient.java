package com.checkout.nexo.integration.paymentmethods;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.checkout.nexo.checkout.port.CatalogPaymentMethod;
import com.checkout.nexo.checkout.port.ExternalServiceUnavailableException;
import com.checkout.nexo.checkout.port.PaymentMethodCatalog;
import com.checkout.nexo.integration.config.CacheConfig;

/**
 * Adaptador de {@link PaymentMethodCatalog} por HTTP (EXT-01, EXT-04).
 */
@Component
public class PaymentMethodServiceClient implements PaymentMethodCatalog {

	private static final String PAYMENT_METHODS_PATH = "/mock-services/payment-methods";

	private final RestClient restClient;

	public PaymentMethodServiceClient(@Qualifier("paymentMethodsRestClient") RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	@Cacheable(cacheNames = CacheConfig.PAYMENT_METHODS_CACHE, key = "'all'")
	public List<CatalogPaymentMethod> findAll() {
		List<PaymentMethodServiceResponse> items;
		try {
			items = restClient.get().uri(PAYMENT_METHODS_PATH).retrieve()
					.body(new ParameterizedTypeReference<List<PaymentMethodServiceResponse>>() {
					});
		}
		catch (RestClientException ex) {
			throw new ExternalServiceUnavailableException("Servico de meios de pagamento indisponivel", ex);
		}
		if (items == null) {
			throw new ExternalServiceUnavailableException("Resposta do servico de meios de pagamento vazia", null);
		}
		return items.stream().map(PaymentMethodServiceResponse::toCatalogPaymentMethod).toList();
	}

}
