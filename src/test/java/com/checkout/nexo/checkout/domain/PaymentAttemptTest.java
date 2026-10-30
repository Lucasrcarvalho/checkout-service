package com.checkout.nexo.checkout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentAttemptTest {

	private static final UUID CART_ID = UUID.fromString("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");

	private static final Instant NOW = Instant.parse("2026-10-14T16:00:00Z");

	private final Cart cart = Cart.open(CART_ID,
			List.of(new CartItem("premium", "Plano Premium", "Assinatura mensal com catálogo completo",
					new BigDecimal("39.90"), "BRL")),
			List.of(new AllowedPaymentMethod("CREDIT_CARD", "Cartão de Crédito", true)), NOW);

	// ---- fabricas (CHK-03, CHK-06) ----------------------------------------------------------

	@Test
	void approvedCardRecordsMethodAmountCurrencyLastDigitsAndApprovedStatus() {
		PaymentAttempt attempt = PaymentAttempt.approvedCard(cart, "CREDIT_CARD", "4242", NOW);

		assertThat(attempt.getCart()).isSameAs(cart);
		assertThat(attempt.getMethodCode()).isEqualTo("CREDIT_CARD");
		assertThat(attempt.getAmount()).isEqualTo(new BigDecimal("39.90"));
		assertThat(attempt.getCurrency()).isEqualTo("BRL");
		assertThat(attempt.getLastDigits()).isEqualTo("4242");
		assertThat(attempt.getPixCode()).isNull();
		assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.APPROVED);
		assertThat(attempt.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void approvedPixRecordsThePixCodeWithoutLastDigits() {
		String pixCode = "PIX-SIMULADO-" + CART_ID;

		PaymentAttempt attempt = PaymentAttempt.approvedPix(cart, "PIX", pixCode, NOW);

		assertThat(attempt.getPixCode()).isEqualTo(pixCode);
		assertThat(attempt.getLastDigits()).isNull();
		assertThat(attempt.getMethodCode()).isEqualTo("PIX");
		assertThat(attempt.getAmount()).isEqualTo(new BigDecimal("39.90"));
		assertThat(attempt.getCurrency()).isEqualTo("BRL");
		assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.APPROVED);
	}

	@Test
	void declinedRecordsTheDeclinedStatusWithTheLastDigitsAndNoPixCode() {
		PaymentAttempt attempt = PaymentAttempt.declined(cart, "DEBIT_CARD", "0002", NOW);

		assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.DECLINED);
		assertThat(attempt.getMethodCode()).isEqualTo("DEBIT_CARD");
		assertThat(attempt.getAmount()).isEqualTo(new BigDecimal("39.90"));
		assertThat(attempt.getCurrency()).isEqualTo("BRL");
		assertThat(attempt.getLastDigits()).isEqualTo("0002");
		assertThat(attempt.getPixCode()).isNull();
	}

	// ---- somente 4 digitos finais (DATA-01) -------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "12345", "4111111111114242", "42a2", "123" })
	void approvedCardRejectsLastDigitsThatAreNotExactlyFourDigitsWithoutEchoingTheValue(String lastDigits) {
		assertThatThrownBy(() -> PaymentAttempt.approvedCard(cart, "CREDIT_CARD", lastDigits, NOW))
				.isInstanceOf(IllegalArgumentException.class).message().doesNotContain(lastDigits);
	}

	@ParameterizedTest
	@ValueSource(strings = { "12345", "4111111111114242", "42a2", "123" })
	void declinedRejectsLastDigitsThatAreNotExactlyFourDigitsWithoutEchoingTheValue(String lastDigits) {
		assertThatThrownBy(() -> PaymentAttempt.declined(cart, "CREDIT_CARD", lastDigits, NOW))
				.isInstanceOf(IllegalArgumentException.class).message().doesNotContain(lastDigits);
	}

	@ParameterizedTest
	@NullAndEmptySource
	void cardFactoriesRejectMissingLastDigits(String lastDigits) {
		assertThatThrownBy(() -> PaymentAttempt.approvedCard(cart, "CREDIT_CARD", lastDigits, NOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PaymentAttempt.declined(cart, "CREDIT_CARD", lastDigits, NOW))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void hasNoFieldForTheFullNumberCvvHolderNameOrExpiry() {
		List<String> fieldNames = Arrays.stream(PaymentAttempt.class.getDeclaredFields())
				.filter(field -> !field.isSynthetic() && !Modifier.isStatic(field.getModifiers()))
				.map(Field::getName).toList();

		assertThat(fieldNames).containsExactlyInAnyOrder("id", "cart", "methodCode", "status", "amount", "currency",
				"lastDigits", "pixCode", "createdAt");
	}

}
