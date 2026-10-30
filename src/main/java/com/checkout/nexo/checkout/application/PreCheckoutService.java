package com.checkout.nexo.checkout.application;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.checkout.nexo.checkout.domain.AllowedPaymentMethod;
import com.checkout.nexo.checkout.domain.Cart;
import com.checkout.nexo.checkout.domain.CartItem;
import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.checkout.nexo.checkout.domain.CheckoutException;
import com.checkout.nexo.checkout.persistence.CartRepository;
import com.checkout.nexo.checkout.port.CatalogPaymentMethod;
import com.checkout.nexo.checkout.port.CatalogProduct;
import com.checkout.nexo.checkout.port.PaymentMethodCatalog;
import com.checkout.nexo.checkout.port.ProductCatalog;

/**
 * Caso de uso do pre-checkout (PRE-02 a PRE-06). Nao e transacional de proposito: as chamadas
 * HTTP aos catalogos nao seguram conexao do banco; so o {@code save} abre a transacao curta do
 * repositorio.
 */
@Service
public class PreCheckoutService {

	private final ProductCatalog productCatalog;

	private final PaymentMethodCatalog paymentMethodCatalog;

	private final CartRepository carts;

	private final Clock clock;

	public PreCheckoutService(ProductCatalog productCatalog, PaymentMethodCatalog paymentMethodCatalog,
			CartRepository carts, Clock clock) {
		this.productCatalog = productCatalog;
		this.paymentMethodCatalog = paymentMethodCatalog;
		this.carts = carts;
		this.clock = clock;
	}

	/**
	 * Monta e persiste um carrinho novo a partir dos slugs, na ordem recebida.
	 *
	 * @throws CheckoutException {@code INVALID_REQUEST} para slugs repetidos (antes de qualquer
	 * consulta) ou {@code PRODUCT_NOT_FOUND} para slug fora do catalogo (sem {@code cartId})
	 * @throws com.checkout.nexo.checkout.port.ExternalServiceUnavailableException se um catalogo
	 * estiver fora
	 * @throws IllegalStateException se os produtos tiverem moedas diferentes
	 */
	public PreCheckoutResult preCheckout(List<String> slugs) {
		if (new HashSet<>(slugs).size() != slugs.size()) {
			throw new CheckoutException(CheckoutErrorCode.INVALID_REQUEST);
		}
		List<CatalogProduct> products = productCatalog.findAll();
		List<CatalogPaymentMethod> methods = paymentMethodCatalog.findAll();

		List<CartItem> items = slugs.stream().map(slug -> toItem(slug, products)).toList();
		List<AllowedPaymentMethod> allowedMethods = methods.stream()
				.map(method -> new AllowedPaymentMethod(method.code(), method.name(), method.requiresCard())).toList();

		Cart cart = Cart.open(UUID.randomUUID(), items, allowedMethods, clock.instant());
		carts.save(cart);
		return new PreCheckoutResult(cart.getId(), items,
				allowedMethods.stream().map(AllowedPaymentMethod::name).toList());
	}

	private static CartItem toItem(String slug, List<CatalogProduct> products) {
		CatalogProduct product = products.stream().filter(candidate -> candidate.slug().equals(slug)).findFirst()
				.orElseThrow(() -> new CheckoutException(CheckoutErrorCode.PRODUCT_NOT_FOUND));
		return new CartItem(product.slug(), product.name(), product.description(), product.price(),
				product.currency());
	}

}
