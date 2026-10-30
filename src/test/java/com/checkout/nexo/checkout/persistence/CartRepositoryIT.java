package com.checkout.nexo.checkout.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.checkout.nexo.TestcontainersConfiguration;
import com.checkout.nexo.checkout.domain.AllowedPaymentMethod;
import com.checkout.nexo.checkout.domain.Cart;
import com.checkout.nexo.checkout.domain.CartItem;
import com.checkout.nexo.checkout.domain.CartStatus;

/**
 * Schema e consultas do carrinho contra PostgreSQL real (nunca banco embutido).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.Postgres.class)
class CartRepositoryIT {

	private static final UUID CART_ID = UUID.fromString("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");

	private static final Instant CREATED_AT = Instant.parse("2026-10-14T15:00:00.123456Z");

	private static final Instant PAID_AT = Instant.parse("2026-10-14T16:00:00Z");

	private static final AllowedPaymentMethod CREDIT = new AllowedPaymentMethod("CREDIT_CARD", "Cartão de Crédito", true);

	private static final AllowedPaymentMethod DEBIT = new AllowedPaymentMethod("DEBIT_CARD", "Cartão de Débito", true);

	private static final AllowedPaymentMethod PIX = new AllowedPaymentMethod("PIX", "Pix", false);

	private static final String INSERT_ITEM = "insert into cart_item (cart_id, line_no, slug, name, description, "
			+ "unit_price, currency) values (?, ?, ?, 'Plano', 'Descricao', 19.90, 'BRL')";

	@Autowired
	private CartRepository carts;

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbc;

	private static CartItem item(String slug, String price) {
		return new CartItem(slug, "Plano " + slug, "Assinatura " + slug, new BigDecimal(price), "BRL");
	}

	private static Cart threeItemCart() {
		return Cart.open(CART_ID, List.of(item("familia", "59.90"), item("basico", "19.90"), item("premium", "39.90")),
				List.of(CREDIT, DEBIT, PIX), CREATED_AT);
	}

	private Cart saveAndReload(Cart cart) {
		carts.saveAndFlush(cart);
		entityManager.clear();
		return carts.findById(cart.getId()).orElseThrow();
	}

	// ---- Migracao e validate (RUN-04) -------------------------------------------------------

	@Test
	void flywayAppliesV1AndHibernateRunsInValidateMode() {
		Object ddlAuto = entityManager.getEntityManager().getEntityManagerFactory().getProperties()
				.get("hibernate.hbm2ddl.auto");

		assertThat(ddlAuto).isEqualTo("validate");
		assertThat(jdbc.queryForList("select version from flyway_schema_history where success", String.class))
				.containsExactly("1");
		assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public' "
				+ "and table_name in ('cart', 'cart_item', 'cart_payment_method', 'payment_attempt')", String.class))
				.containsExactlyInAnyOrder("cart", "cart_item", "cart_payment_method", "payment_attempt");
	}

	@Test
	void theTestsRunAgainstARealPostgreSql() {
		assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("PostgreSQL 18.");
	}

	// ---- Ida e volta (PRE-05, CHK-03) -------------------------------------------------------

	@Test
	void savingAndReloadingKeepsTheItemOrderAndTheLineNumbers() {
		Cart reloaded = saveAndReload(threeItemCart());

		assertThat(reloaded.getItems()).extracting(CartItem::slug).containsExactly("familia", "basico", "premium");
		assertThat(jdbc.queryForList("select slug from cart_item where cart_id = ? order by line_no", String.class,
				CART_ID)).containsExactly("familia", "basico", "premium");
		assertThat(jdbc.queryForList("select line_no from cart_item where cart_id = ? order by line_no", Integer.class,
				CART_ID)).containsExactly(0, 1, 2);
	}

	@Test
	void savingAndReloadingKeepsTheAllowedMethodsWithTheirFlags() {
		Cart reloaded = saveAndReload(threeItemCart());

		assertThat(reloaded.getAllowedMethods()).containsExactlyInAnyOrder(CREDIT, DEBIT, PIX);
	}

	@Test
	void savingAndReloadingKeepsStatusTotalCurrencyAndInstants() {
		Cart reloaded = saveAndReload(threeItemCart());

		assertThat(reloaded.getStatus()).isEqualTo(CartStatus.OPEN);
		assertThat(reloaded.getTotalAmount()).isEqualTo(new BigDecimal("119.70"));
		assertThat(reloaded.getCurrency()).isEqualTo("BRL");
		assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
		assertThat(reloaded.getPaidAt()).isNull();
	}

	@Test
	void numericColumnsStoreTwoDecimalPlaces() {
		Cart cart = Cart.open(CART_ID, List.of(item("a", "19.9"), item("b", "60")), List.of(PIX), CREATED_AT);

		Cart reloaded = saveAndReload(cart);

		assertThat(reloaded.getItems()).extracting(CartItem::unitPrice)
				.containsExactly(new BigDecimal("19.90"), new BigDecimal("60.00"));
		assertThat(reloaded.getTotalAmount()).isEqualTo(new BigDecimal("79.90"));
	}

	@Test
	void theVersionStartsAtZeroAndIncrementsOnTheUpdate() {
		Cart cart = threeItemCart();
		carts.saveAndFlush(cart);
		assertThat(cart.getVersion()).isEqualTo(0L);

		cart.approve(PAID_AT);
		carts.saveAndFlush(cart);
		entityManager.clear();

		assertThat(carts.findById(CART_ID).orElseThrow().getVersion()).isEqualTo(1L);
	}

	@Test
	void anApprovedCartIsPersistedWithItsPaidAt() {
		Cart cart = threeItemCart();
		carts.saveAndFlush(cart);
		cart.approve(PAID_AT);

		Cart reloaded = saveAndReload(cart);

		assertThat(reloaded.getStatus()).isEqualTo(CartStatus.APPROVED);
		assertThat(reloaded.getPaidAt()).isEqualTo(PAID_AT);
	}

	// ---- findByIdForUpdate ------------------------------------------------------------------

	@Test
	void findByIdForUpdateReturnsTheStoredCart() {
		carts.saveAndFlush(threeItemCart());
		entityManager.clear();

		Cart found = carts.findByIdForUpdate(CART_ID).orElseThrow();

		assertThat(found.getId()).isEqualTo(CART_ID);
		assertThat(found.getItems()).extracting(CartItem::slug).containsExactly("familia", "basico", "premium");
	}

	@Test
	void findByIdForUpdateReturnsEmptyForAnUnknownId() {
		carts.saveAndFlush(threeItemCart());

		assertThat(carts.findByIdForUpdate(UUID.fromString("00000000-0000-4000-8000-000000000000"))).isEmpty();
	}

	// ---- Constraints do banco ---------------------------------------------------------------

	@ParameterizedTest
	@CsvSource({
			"APPROVED, null",
			"OPEN, now()"
	})
	void ckCartPaidAtRejectsAStatusThatDisagreesWithPaidAt(String status, String paidAt) {
		assertThatThrownBy(() -> insertCart(status, paidAt))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_cart_paid_at");
	}

	@ParameterizedTest
	@CsvSource({
			"APPROVED, now()",
			"OPEN, null"
	})
	void ckCartPaidAtAcceptsAStatusThatAgreesWithPaidAt(String status, String paidAt) {
		insertCart(status, paidAt);

		assertThat(jdbc.queryForObject("select count(*) from cart where id = ?", Integer.class, CART_ID)).isEqualTo(1);
	}

	@Test
	void aStatusOutsideTheAllowedSetIsRejected() {
		assertThatThrownBy(() -> insertCart("PAID", "now()"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("cart_status_check");
	}

	@Test
	void theSameSlugTwiceInTheSameCartIsRejected() {
		insertCart("OPEN", "null");
		jdbc.update(INSERT_ITEM, CART_ID, 0, "premium");

		assertThatThrownBy(() -> jdbc.update(INSERT_ITEM, CART_ID, 1, "premium"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("cart_item_cart_id_slug_key");
	}

	/** {@code paidAt} e uma expressao SQL constante do proprio teste ({@code null} ou {@code now()}). */
	private void insertCart(String status, String paidAt) {
		jdbc.update("insert into cart (id, status, total_amount, currency, created_at, paid_at, version) "
				+ "values (?, ?, 19.90, 'BRL', now(), " + paidAt + ", 0)", CART_ID, status);
	}

}
