package com.checkout.nexo.checkout.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.checkout.nexo.TestcontainersConfiguration;
import com.checkout.nexo.checkout.domain.AllowedPaymentMethod;
import com.checkout.nexo.checkout.domain.AttemptStatus;
import com.checkout.nexo.checkout.domain.Cart;
import com.checkout.nexo.checkout.domain.CartItem;
import com.checkout.nexo.checkout.domain.PaymentAttempt;

/**
 * Constraints do banco para DUP-02 (um unico APPROVED por carrinho) e DATA-01 (so 4 digitos finais),
 * contra PostgreSQL real.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.Postgres.class)
class PaymentAttemptRepositoryIT {

	private static final UUID CART_ID = UUID.fromString("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");

	private static final UUID OTHER_CART_ID = UUID.fromString("0b1f4c52-9d3a-4e7b-b6a1-5c8e2d4f7a90");

	private static final Instant NOW = Instant.parse("2026-10-14T16:00:00.123456Z");

	private static final String INSERT_DECLINED = "insert into payment_attempt (cart_id, method_code, status, amount, "
			+ "currency, last_digits, pix_code, created_at) values (?, 'CREDIT_CARD', 'DECLINED', 39.90, 'BRL', ?, null, now())";

	@Autowired
	private PaymentAttemptRepository attempts;

	@Autowired
	private CartRepository carts;

	@Autowired
	private TestEntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbc;

	private Cart savedCart(UUID id) {
		CartItem premium = new CartItem("premium", "Plano Premium", "Assinatura mensal com catálogo completo",
				new BigDecimal("39.90"), "BRL");
		return carts.saveAndFlush(Cart.open(id, List.of(premium),
				List.of(new AllowedPaymentMethod("CREDIT_CARD", "Cartão de Crédito", true),
						new AllowedPaymentMethod("PIX", "Pix", false)),
				NOW));
	}

	private int countAttempts(UUID cartId, String status) {
		return jdbc.queryForObject("select count(*) from payment_attempt where cart_id = ? and status = ?",
				Integer.class, cartId, status);
	}

	// ---- DUP-02: um unico APPROVED por carrinho ---------------------------------------------

	@Test
	void aSecondApprovedAttemptForTheSameCartIsRejected() {
		Cart cart = savedCart(CART_ID);
		attempts.saveAndFlush(PaymentAttempt.approvedCard(cart, "CREDIT_CARD", "4242", NOW));

		assertThatThrownBy(() -> attempts.saveAndFlush(PaymentAttempt.approvedCard(cart, "CREDIT_CARD", "1111", NOW)))
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause().hasMessageContaining("ux_payment_attempt_one_approved");
	}

	@Test
	void manyDeclinedAttemptsForTheSameCartAreAccepted() {
		Cart cart = savedCart(CART_ID);

		attempts.saveAndFlush(PaymentAttempt.declined(cart, "CREDIT_CARD", "0002", NOW));
		attempts.saveAndFlush(PaymentAttempt.declined(cart, "CREDIT_CARD", "0002", NOW));
		attempts.saveAndFlush(PaymentAttempt.declined(cart, "DEBIT_CARD", "0002", NOW));

		assertThat(countAttempts(CART_ID, "DECLINED")).isEqualTo(3);
	}

	@Test
	void aDeclinedAttemptFollowedByAnApprovedOneIsAccepted() {
		Cart cart = savedCart(CART_ID);

		attempts.saveAndFlush(PaymentAttempt.declined(cart, "CREDIT_CARD", "0002", NOW));
		attempts.saveAndFlush(PaymentAttempt.approvedCard(cart, "CREDIT_CARD", "4242", NOW));

		assertThat(jdbc.queryForList("select status from payment_attempt where cart_id = ? order by id", String.class,
				CART_ID)).containsExactly("DECLINED", "APPROVED");
	}

	@Test
	void eachCartCanHaveItsOwnApprovedAttempt() {
		Cart first = savedCart(CART_ID);
		Cart second = savedCart(OTHER_CART_ID);

		attempts.saveAndFlush(PaymentAttempt.approvedCard(first, "CREDIT_CARD", "4242", NOW));
		attempts.saveAndFlush(PaymentAttempt.approvedPix(second, "PIX", "PIX-SIMULADO-" + OTHER_CART_ID, NOW));

		assertThat(countAttempts(CART_ID, "APPROVED")).isEqualTo(1);
		assertThat(countAttempts(OTHER_CART_ID, "APPROVED")).isEqualTo(1);
	}

	// ---- DATA-01: so os 4 ultimos digitos no banco ------------------------------------------

	/** Ate 4 caracteres a coluna aceita o tamanho; quem rejeita e a constraint. */
	@ParameterizedTest
	@ValueSource(strings = { "42a2", "424" })
	void ckAttemptLastDigitsRejectsFourOrFewerCharactersThatAreNotFourDigits(String lastDigits) {
		savedCart(CART_ID);

		assertThatThrownBy(() -> jdbc.update(INSERT_DECLINED, CART_ID, lastDigits))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_attempt_last_digits");
	}

	/**
	 * Com mais de 4 caracteres (5 digitos, numero completo) a rejeicao vem antes, da propria coluna
	 * {@code varchar(4)}, e nao da constraint: o numero completo nao cabe na coluna (DATA-01).
	 */
	@ParameterizedTest
	@ValueSource(strings = { "12345", "4111111111114242" })
	void theColumnRejectsMoreThanFourCharactersSoAFullNumberDoesNotFit(String lastDigits) {
		savedCart(CART_ID);

		assertThatThrownBy(() -> jdbc.update(INSERT_DECLINED, CART_ID, lastDigits))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("value too long for type character varying(4)");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = "4242")
	void ckAttemptLastDigitsAcceptsFourDigitsOrNull(String lastDigits) {
		savedCart(CART_ID);

		jdbc.update(INSERT_DECLINED, CART_ID, lastDigits);

		assertThat(countAttempts(CART_ID, "DECLINED")).isEqualTo(1);
	}

	@Test
	void thePaymentAttemptTableHasNoColumnForCardNumberCvvHolderOrExpiry() {
		assertThat(jdbc.queryForList("select column_name from information_schema.columns "
				+ "where table_schema = 'public' and table_name = 'payment_attempt'", String.class))
				.containsExactlyInAnyOrder("id", "cart_id", "method_code", "status", "amount", "currency",
						"last_digits", "pix_code", "created_at");
	}

	@Test
	void anApprovedCardAttemptIsStoredWithItsLastDigitsAndWithoutPixCode() {
		Cart cart = savedCart(CART_ID);
		PaymentAttempt saved = attempts.saveAndFlush(PaymentAttempt.approvedCard(cart, "CREDIT_CARD", "4242", NOW));
		entityManager.clear();

		Map<String, Object> row = jdbc.queryForMap("select * from payment_attempt where id = ?", saved.getId());
		PaymentAttempt reloaded = attempts.findById(saved.getId()).orElseThrow();

		assertThat(row).containsEntry("method_code", "CREDIT_CARD").containsEntry("status", "APPROVED")
				.containsEntry("currency", "BRL").containsEntry("last_digits", "4242").containsEntry("pix_code", null);
		assertThat(reloaded.getAmount()).isEqualTo(new BigDecimal("39.90"));
		assertThat(reloaded.getStatus()).isEqualTo(AttemptStatus.APPROVED);
		assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void anApprovedPixAttemptIsStoredWithItsPixCodeAndWithoutLastDigits() {
		Cart cart = savedCart(CART_ID);
		String pixCode = "PIX-SIMULADO-" + CART_ID;
		PaymentAttempt saved = attempts.saveAndFlush(PaymentAttempt.approvedPix(cart, "PIX", pixCode, NOW));
		entityManager.clear();

		Map<String, Object> row = jdbc.queryForMap("select * from payment_attempt where id = ?", saved.getId());

		assertThat(row).containsEntry("method_code", "PIX").containsEntry("status", "APPROVED")
				.containsEntry("pix_code", pixCode).containsEntry("last_digits", null);
	}

	// ---- Integridade referencial ------------------------------------------------------------

	@Test
	void anAttemptForACartThatDoesNotExistIsRejected() {
		assertThatThrownBy(() -> jdbc.update(INSERT_DECLINED, UUID.fromString("00000000-0000-4000-8000-000000000000"),
				"4242"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("payment_attempt_cart_id_fkey");
	}

}
