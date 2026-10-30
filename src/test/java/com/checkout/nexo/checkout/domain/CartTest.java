package com.checkout.nexo.checkout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Version;

class CartTest {

	private static final UUID CART_ID = UUID.fromString("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");

	private static final Instant NOW = Instant.parse("2026-10-14T16:00:00.123456Z");

	private static final AllowedPaymentMethod CREDIT = new AllowedPaymentMethod("CREDIT_CARD", "Cartão de Crédito", true);

	private static final AllowedPaymentMethod DEBIT = new AllowedPaymentMethod("DEBIT_CARD", "Cartão de Débito", true);

	private static final AllowedPaymentMethod PIX = new AllowedPaymentMethod("PIX", "Pix", false);

	private static final List<AllowedPaymentMethod> METHODS = List.of(CREDIT, DEBIT, PIX);

	private static CartItem item(String slug, String price, String currency) {
		return new CartItem(slug, "Plano " + slug, "Assinatura " + slug, new BigDecimal(price), currency);
	}

	private static Cart openCart(List<CartItem> items) {
		return Cart.open(CART_ID, items, METHODS, NOW);
	}

	// ---- open (PRE-05) ----------------------------------------------------------------------

	@Test
	void openSumsTheUnitPricesWithScaleTwo() {
		Cart cart = openCart(List.of(item("basico", "19.90", "BRL"), item("familia", "59.90", "BRL")));

		assertThat(cart.getTotalAmount()).isEqualTo(new BigDecimal("79.80"));
	}

	@Test
	void openNormalizesTheTotalToScaleTwoWhenThePricesHaveSmallerScale() {
		Cart cart = openCart(List.of(item("a", "19.9", "BRL"), item("b", "60", "BRL")));

		assertThat(cart.getTotalAmount()).isEqualTo(new BigDecimal("79.90"));
	}

	@Test
	void openKeepsTheItemOrder() {
		Cart cart = openCart(List.of(item("familia", "59.90", "BRL"), item("basico", "19.90", "BRL"),
				item("premium", "39.90", "BRL")));

		assertThat(cart.getItems()).extracting(CartItem::slug).containsExactly("familia", "basico", "premium");
	}

	@Test
	void openCreatesAnOpenCartWithTheGivenIdInstantCurrencyAndMethods() {
		Cart cart = openCart(List.of(item("premium", "39.90", "BRL")));

		assertThat(cart.getId()).isEqualTo(CART_ID);
		assertThat(cart.getStatus()).isEqualTo(CartStatus.OPEN);
		assertThat(cart.getCreatedAt()).isEqualTo(NOW);
		assertThat(cart.getPaidAt()).isNull();
		assertThat(cart.getCurrency()).isEqualTo("BRL");
		assertThat(cart.isApproved()).isFalse();
		assertThat(cart.getAllowedMethods()).containsExactlyInAnyOrder(CREDIT, DEBIT, PIX);
		assertThat(cart.getItems()).containsExactly(item("premium", "39.90", "BRL"));
	}

	@Test
	void openWithDifferentCurrenciesThrowsIllegalStateException() {
		List<CartItem> mixedCurrencies = List.of(item("basico", "19.90", "BRL"), item("premium", "39.90", "USD"));

		assertThatThrownBy(() -> openCart(mixedCurrencies)).isInstanceOf(IllegalStateException.class);
	}

	// ---- approve (CHK-03, DUP-02: integridade de estado) -------------------------------------

	@Test
	void approveMovesToApprovedAndRecordsPaidAt() {
		Cart cart = openCart(List.of(item("premium", "39.90", "BRL")));
		Instant paidAt = Instant.parse("2026-10-14T16:05:00Z");

		cart.approve(paidAt);

		assertThat(cart.getStatus()).isEqualTo(CartStatus.APPROVED);
		assertThat(cart.isApproved()).isTrue();
		assertThat(cart.getPaidAt()).isEqualTo(paidAt);
	}

	@Test
	void approvingTwiceThrowsIllegalStateExceptionAndKeepsTheFirstPaidAt() {
		Cart cart = openCart(List.of(item("premium", "39.90", "BRL")));
		Instant firstPaidAt = Instant.parse("2026-10-14T16:05:00Z");
		cart.approve(firstPaidAt);

		assertThatThrownBy(() -> cart.approve(Instant.parse("2026-10-14T17:00:00Z")))
				.isInstanceOf(IllegalStateException.class);
		assertThat(cart.getPaidAt()).isEqualTo(firstPaidAt);
	}

	// ---- findMethod (CHK-10) ----------------------------------------------------------------

	@Test
	void findMethodMatchesTheExactName() {
		Cart cart = openCart(List.of(item("premium", "39.90", "BRL")));

		assertThat(cart.findMethod("Cartão de Crédito")).contains(CREDIT);
		assertThat(cart.findMethod("Pix")).contains(PIX);
	}

	@Test
	void findMethodIsCaseSensitive() {
		Cart cart = openCart(List.of(item("premium", "39.90", "BRL")));

		assertThat(cart.findMethod("cartão de crédito")).isEmpty();
	}

	@Test
	void findMethodDoesNotMatchANameOutsideTheSnapshot() {
		Cart cart = openCart(List.of(item("premium", "39.90", "BRL")));

		assertThat(cart.findMethod("Boleto")).isEmpty();
	}

	@Test
	void findMethodNormalizesTheGivenNameToNfcBeforeComparing() {
		Cart cart = openCart(List.of(item("premium", "39.90", "BRL")));
		String decomposed = Normalizer.normalize("Cartão de Crédito", Normalizer.Form.NFD);

		assertThat(decomposed).isNotEqualTo("Cartão de Crédito");
		assertThat(cart.findMethod(decomposed)).contains(CREDIT);
	}

	// ---- imutabilidade das listas expostas --------------------------------------------------

	@Test
	void exposedItemsAreImmutable() {
		Cart cart = openCart(List.of(item("premium", "39.90", "BRL")));
		CartItem other = item("basico", "19.90", "BRL");

		assertThatThrownBy(() -> cart.getItems().add(other)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> cart.getItems().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void exposedAllowedMethodsAreImmutable() {
		Cart cart = openCart(List.of(item("premium", "39.90", "BRL")));
		AllowedPaymentMethod boleto = new AllowedPaymentMethod("BOLETO", "Boleto", false);

		assertThatThrownBy(() -> cart.getAllowedMethods().add(boleto)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> cart.getAllowedMethods().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	// ---- anotacoes JPA ----------------------------------------------------------------------

	@Test
	void jpaMappingHasAssignedIdLongVersionAndOrderedElementCollections() throws NoSuchFieldException {
		Field id = Cart.class.getDeclaredField("id");
		Field version = Cart.class.getDeclaredField("version");
		Field items = Cart.class.getDeclaredField("items");
		Field allowedMethods = Cart.class.getDeclaredField("allowedMethods");

		assertThat(Cart.class.isAnnotationPresent(Entity.class)).isTrue();
		assertThat(id.isAnnotationPresent(Id.class)).isTrue();
		assertThat(id.isAnnotationPresent(GeneratedValue.class)).isFalse();
		assertThat(version.isAnnotationPresent(Version.class)).isTrue();
		assertThat(version.getType()).isEqualTo(Long.class);
		assertThat(items.isAnnotationPresent(ElementCollection.class)).isTrue();
		assertThat(items.getAnnotation(OrderColumn.class).name()).isEqualTo("line_no");
		assertThat(allowedMethods.isAnnotationPresent(ElementCollection.class)).isTrue();
	}

}
