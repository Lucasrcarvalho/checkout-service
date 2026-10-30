package com.checkout.nexo.checkout.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.checkout.nexo.checkout.domain.AllowedPaymentMethod;
import com.checkout.nexo.checkout.domain.Cart;
import com.checkout.nexo.checkout.domain.CartItem;
import com.checkout.nexo.checkout.domain.CartStatus;
import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.checkout.nexo.checkout.domain.CheckoutException;
import com.checkout.nexo.checkout.persistence.CartRepository;
import com.checkout.nexo.checkout.port.CatalogPaymentMethod;
import com.checkout.nexo.checkout.port.CatalogProduct;
import com.checkout.nexo.checkout.port.ExternalServiceUnavailableException;
import com.checkout.nexo.checkout.port.PaymentMethodCatalog;
import com.checkout.nexo.checkout.port.ProductCatalog;

/**
 * Regras do pre-checkout (PRE-02 a PRE-06, EXT-04) com portas e repositorio simulados e relogio
 * fixo. Os valores do catalogo sao os do mock de EXT-02.
 */
@ExtendWith(MockitoExtension.class)
class PreCheckoutServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-14T15:00:00.123456Z");

	private static final CatalogProduct BASICO = new CatalogProduct("basico", "Plano Básico",
			"Assinatura mensal com o essencial do catálogo", new BigDecimal("19.90"), "BRL");

	private static final CatalogProduct PREMIUM = new CatalogProduct("premium", "Plano Premium",
			"Assinatura mensal com catálogo completo", new BigDecimal("39.90"), "BRL");

	private static final CatalogProduct FAMILIA = new CatalogProduct("familia", "Plano Família",
			"Assinatura mensal para até 4 perfis", new BigDecimal("59.90"), "BRL");

	private static final List<CatalogPaymentMethod> CATALOG_METHODS = List.of(
			new CatalogPaymentMethod("CREDIT_CARD", "Cartão de Crédito", true),
			new CatalogPaymentMethod("DEBIT_CARD", "Cartão de Débito", true),
			new CatalogPaymentMethod("PIX", "Pix", false));

	@Mock
	private ProductCatalog productCatalog;

	@Mock
	private PaymentMethodCatalog paymentMethodCatalog;

	@Mock
	private CartRepository carts;

	private PreCheckoutService service;

	@BeforeEach
	void createTheService() {
		service = new PreCheckoutService(productCatalog, paymentMethodCatalog, carts,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private void catalogsRespond() {
		when(productCatalog.findAll()).thenReturn(List.of(BASICO, PREMIUM, FAMILIA));
		when(paymentMethodCatalog.findAll()).thenReturn(CATALOG_METHODS);
	}

	private Cart savedCart() {
		ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
		verify(carts).save(captor.capture());
		return captor.getValue();
	}

	// ---- Itens, ordem e total (PRE-02) ------------------------------------------------------

	@Test
	void severalSlugsComeBackInTheRequestOrderNotTheCatalogOrder() {
		catalogsRespond();

		PreCheckoutResult result = service.preCheckout(List.of("familia", "basico"));

		assertThat(result.items()).extracting(CartItem::slug).containsExactly("familia", "basico");
	}

	@Test
	void theTotalIsTheSumOfThePricesWithScaleTwo() {
		catalogsRespond();

		service.preCheckout(List.of("basico", "familia"));

		assertThat(savedCart().getTotalAmount()).isEqualTo(new BigDecimal("79.80"));
	}

	@Test
	void theResultCarriesTheItemSnapshotAndTheMethodNamesInTheCatalogOrder() {
		catalogsRespond();

		PreCheckoutResult result = service.preCheckout(List.of("premium"));

		assertThat(result.items()).containsExactly(
				new CartItem("premium", "Plano Premium", "Assinatura mensal com catálogo completo",
						new BigDecimal("39.90"), "BRL"));
		assertThat(result.paymentMethodNames()).containsExactly("Cartão de Crédito", "Cartão de Débito", "Pix");
		assertThat(result.cartId()).isEqualTo(savedCart().getId());
	}

	// ---- Persistencia do snapshot (PRE-05) --------------------------------------------------

	@Test
	void saveReceivesAnOpenCartWithTheItemSnapshotTotalCurrencyAndClockCreationTime() {
		catalogsRespond();

		service.preCheckout(List.of("basico", "premium"));

		Cart cart = savedCart();
		assertThat(cart.getStatus()).isEqualTo(CartStatus.OPEN);
		assertThat(cart.getPaidAt()).isNull();
		assertThat(cart.getItems()).containsExactly(
				new CartItem("basico", "Plano Básico", "Assinatura mensal com o essencial do catálogo",
						new BigDecimal("19.90"), "BRL"),
				new CartItem("premium", "Plano Premium", "Assinatura mensal com catálogo completo",
						new BigDecimal("39.90"), "BRL"));
		assertThat(cart.getTotalAmount()).isEqualTo(new BigDecimal("59.80"));
		assertThat(cart.getCurrency()).isEqualTo("BRL");
		assertThat(cart.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void saveReceivesTheSnapshotOfAllThePaymentMethodsWithCodeNameAndRequiresCard() {
		catalogsRespond();

		service.preCheckout(List.of("basico"));

		assertThat(savedCart().getAllowedMethods()).containsExactly(
				new AllowedPaymentMethod("CREDIT_CARD", "Cartão de Crédito", true),
				new AllowedPaymentMethod("DEBIT_CARD", "Cartão de Débito", true),
				new AllowedPaymentMethod("PIX", "Pix", false));
	}

	// ---- Carrinhos distintos (PRE-06) -------------------------------------------------------

	@Test
	void twoEqualCallsCreateTwoCartsWithDifferentIds() {
		catalogsRespond();

		PreCheckoutResult first = service.preCheckout(List.of("premium"));
		PreCheckoutResult second = service.preCheckout(List.of("premium"));

		assertThat(first.cartId()).isNotEqualTo(second.cartId());
		ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
		verify(carts, times(2)).save(captor.capture());
		assertThat(captor.getAllValues()).extracting(Cart::getId).containsExactly(first.cartId(), second.cartId());
	}

	// ---- Produto inexistente (PRE-03) -------------------------------------------------------

	@Test
	void anUnknownSlugFailsWithProductNotFoundWithoutCartIdAndWithoutSavingNorNamingTheSlug() {
		catalogsRespond();

		assertThatThrownBy(() -> service.preCheckout(List.of("slug-inexistente-7c1d")))
				.isInstanceOfSatisfying(CheckoutException.class, ex -> {
					assertThat(ex.code()).isEqualTo(CheckoutErrorCode.PRODUCT_NOT_FOUND);
					assertThat(ex.cartId()).isNull();
					assertThat(ex.getMessage()).doesNotContain("slug-inexistente-7c1d");
				});
		verify(carts, never()).save(any());
	}

	@Test
	void anUnknownSlugAmongValidOnesStillFailsWithProductNotFoundAndSavesNothing() {
		catalogsRespond();

		assertThatThrownBy(() -> service.preCheckout(List.of("basico", "inexistente", "premium")))
				.isInstanceOfSatisfying(CheckoutException.class,
						ex -> assertThat(ex.code()).isEqualTo(CheckoutErrorCode.PRODUCT_NOT_FOUND));
		verify(carts, never()).save(any());
	}

	// ---- Slugs repetidos (PRE-04) -----------------------------------------------------------

	@Test
	void repeatedSlugsFailWithInvalidRequestBeforeAnyCatalogIsQueried() {
		assertThatThrownBy(() -> service.preCheckout(List.of("basico", "premium", "basico")))
				.isInstanceOfSatisfying(CheckoutException.class, ex -> {
					assertThat(ex.code()).isEqualTo(CheckoutErrorCode.INVALID_REQUEST);
					assertThat(ex.cartId()).isNull();
				});

		verifyNoInteractions(productCatalog, paymentMethodCatalog, carts);
	}

	// ---- Servico externo fora (EXT-04) ------------------------------------------------------

	@Test
	void theProductCatalogUnavailableExceptionPropagatesAndNothingIsSaved() {
		ExternalServiceUnavailableException failure = new ExternalServiceUnavailableException("produtos",
				new IOException("io"));
		when(productCatalog.findAll()).thenThrow(failure);

		assertThatThrownBy(() -> service.preCheckout(List.of("basico"))).isSameAs(failure);
		verify(carts, never()).save(any());
	}

	@Test
	void thePaymentMethodCatalogUnavailableExceptionPropagatesAndNothingIsSaved() {
		ExternalServiceUnavailableException failure = new ExternalServiceUnavailableException("meios",
				new IOException("io"));
		when(productCatalog.findAll()).thenReturn(List.of(BASICO, PREMIUM, FAMILIA));
		when(paymentMethodCatalog.findAll()).thenThrow(failure);

		assertThatThrownBy(() -> service.preCheckout(List.of("basico"))).isSameAs(failure);
		verify(carts, never()).save(any());
	}

	// ---- Moedas divergentes (assumption "Moeda") --------------------------------------------

	@Test
	void divergentCurrenciesFailWithIllegalStateAndNothingIsSaved() {
		CatalogProduct usd = new CatalogProduct("premium", "Plano Premium", "Assinatura mensal com catálogo completo",
				new BigDecimal("39.90"), "USD");
		when(productCatalog.findAll()).thenReturn(List.of(BASICO, usd, FAMILIA));
		when(paymentMethodCatalog.findAll()).thenReturn(CATALOG_METHODS);

		assertThatThrownBy(() -> service.preCheckout(List.of("basico", "premium")))
				.isInstanceOf(IllegalStateException.class);
		verify(carts, never()).save(any());
	}

	// ---- Sem transacao (as chamadas HTTP nao seguram conexao) -------------------------------

	@Test
	void theServiceIsNotTransactionalAtClassOrMethodLevel() throws NoSuchMethodException {
		Class<PreCheckoutService> type = PreCheckoutService.class;

		assertThat(type.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
		assertThat(type.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
		assertThat(type.getMethod("preCheckout", List.class)
				.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
		assertThat(type.getMethod("preCheckout", List.class)
				.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
	}

}
