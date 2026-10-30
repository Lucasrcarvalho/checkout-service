package com.checkout.nexo.checkout.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.checkout.nexo.checkout.domain.CardData;
import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.checkout.nexo.checkout.domain.CheckoutException;

class CardValidatorTest {

	/** 14/10/2026 em UTC: o mes corrente e 10/26. */
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T16:00:00Z"), ZoneOffset.UTC);

	private final CardValidator validator = new CardValidator();

	private static CardData withNumber(String number) {
		return new CardData("Ana Souza", number, "12/28", "123");
	}

	private static CardData withExpiry(String expiry) {
		return new CardData("Ana Souza", "4111111111114242", expiry, "123");
	}

	private static CardData withCvv(String cvv) {
		return new CardData("Ana Souza", "4111111111114242", "12/28", cvv);
	}

	private static CardData withHolderName(String holderName) {
		return new CardData(holderName, "4111111111114242", "12/28", "123");
	}

	private void assertAccepted(CardData card) {
		assertThatCode(() -> validator.validate(card, CLOCK)).doesNotThrowAnyException();
	}

	private void assertRejected(CardData card) {
		assertThatThrownBy(() -> validator.validate(card, CLOCK)).isInstanceOfSatisfying(CheckoutException.class,
				exception -> assertThat(exception.code()).isEqualTo(CheckoutErrorCode.INVALID_PAYMENT_DATA));
	}

	// ---- number -----------------------------------------------------------------------------

	static Stream<String> acceptedNumbers() {
		return Stream.of(
				"4111111111111",       // 13 digitos
				"4111111111111111111", // 19 digitos
				"4111111111114242");   // exemplo do enunciado: falha no Luhn e deve ser aceito (D-11)
	}

	@ParameterizedTest
	@MethodSource("acceptedNumbers")
	void acceptsNumbersWith13To19Digits(String number) {
		assertAccepted(withNumber(number));
	}

	static Stream<String> rejectedNumbers() {
		return Stream.of(
				"411111111111",         // 12 digitos
				"41111111111111111111", // 20 digitos
				"4111a11111114242",     // letra
				"4111 1111 1111 4242",  // espacos
				"4111-1111-1111-4242",  // hifens
				"4111111111114242\n",   // algo alem de digitos ao final
				"",
				null);
	}

	@ParameterizedTest
	@MethodSource("rejectedNumbers")
	void rejectsNumbersOutsideTheDigitRangeOrWithNonDigits(String number) {
		assertRejected(withNumber(number));
	}

	// ---- expiry -----------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "10/26", "11/26", "12/28", "01/27" })
	void acceptsTheCurrentMonthAndFutureMonths(String expiry) {
		assertAccepted(withExpiry(expiry));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "1/28", "12/2028", "13/28", "00/28", "", "09/26", "12/25" })
	void rejectsMalformedMonthOutOfRangeAndPastExpiries(String expiry) {
		assertRejected(withExpiry(expiry));
	}

	@Test
	void currentMonthIsEvaluatedInUtcEvenWhenTheClockZoneIsBehind() {
		// 01/11/2026 01:00 UTC ainda e 31/10/2026 22:00 em Sao Paulo: em UTC o mes corrente e 11/26.
		Clock saoPauloClock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneId.of("America/Sao_Paulo"));
		CardData expiredInUtc = withExpiry("10/26");

		assertThatThrownBy(() -> validator.validate(expiredInUtc, saoPauloClock))
				.isInstanceOfSatisfying(CheckoutException.class,
						exception -> assertThat(exception.code()).isEqualTo(CheckoutErrorCode.INVALID_PAYMENT_DATA));
		assertThatCode(() -> validator.validate(withExpiry("11/26"), saoPauloClock)).doesNotThrowAnyException();
	}

	// ---- cvv --------------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "123", "1234" })
	void acceptsThreeAndFourDigitCvv(String cvv) {
		assertAccepted(withCvv(cvv));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "12", "12345", "12a", "abc", "" })
	void rejectsCvvOutsideThreeToFourDigits(String cvv) {
		assertRejected(withCvv(cvv));
	}

	// ---- holderName -------------------------------------------------------------------------

	static Stream<Arguments> acceptedHolderNames() {
		return Stream.of(
				Arguments.of("A"),                          // 1 caractere
				Arguments.of("a".repeat(50)),               // 50 caracteres
				Arguments.of("  " + "a".repeat(50) + "  "), // 50 apos o trim
				Arguments.of("  Ana Souza  "));
	}

	@ParameterizedTest
	@MethodSource("acceptedHolderNames")
	void acceptsHolderNamesWithOneToFiftyCharactersAfterTrim(String holderName) {
		assertAccepted(withHolderName(holderName));
	}

	static Stream<Arguments> rejectedHolderNames() {
		return Stream.of(
				Arguments.of((String) null),
				Arguments.of(""),
				Arguments.of("     "),
				Arguments.of("a".repeat(51)),
				Arguments.of("  " + "a".repeat(51) + "  "));
	}

	@ParameterizedTest
	@MethodSource("rejectedHolderNames")
	void rejectsEmptyBlankNullAndTooLongHolderNames(String holderName) {
		assertRejected(withHolderName(holderName));
	}

	// ---- excecao sem dado de cartao (DATA-04, AD-008) ---------------------------------------

	@Test
	void theExceptionMessageHoldsNoCardValue() {
		CardData card = new CardData("Titular Distintivo", "00123", "99/99", "98765");

		assertThatThrownBy(() -> validator.validate(card, CLOCK)).isInstanceOfSatisfying(CheckoutException.class,
				exception -> {
					assertThat(exception.getMessage()).isEqualTo("INVALID_PAYMENT_DATA");
					assertThat(exception.getMessage()).doesNotContain("00123", "99/99", "98765", "Distintivo");
					assertThat(exception.cartId()).isNull();
				});
	}

}
