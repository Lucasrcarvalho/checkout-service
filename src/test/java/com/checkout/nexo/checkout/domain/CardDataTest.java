package com.checkout.nexo.checkout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CardDataTest {

	@Test
	void toStringContainsNoDigitOfTheNumberNorOfTheCvv() {
		// O numero usa os dez algarismos: qualquer algarismo no texto seria vazamento.
		String number = "0123456789012345";
		String cvv = "6789";
		String text = new CardData("Ana Souza", number, "12/28", cvv).toString();

		assertThat(text).doesNotContain(number).doesNotContain(cvv);
		for (char digit : (number + cvv).toCharArray()) {
			assertThat(text).doesNotContain(String.valueOf(digit));
		}
	}

	@Test
	void toStringDoesNotContainTheHolderName() {
		String text = new CardData("Ana Souza", "4111111111114242", "12/28", "123").toString();

		assertThat(text).doesNotContain("Ana").doesNotContain("Souza");
	}

	@Test
	void lastDigitsReturnsTheFourLastDigits() {
		assertThat(new CardData("Ana Souza", "4111111111114242", "12/28", "123").lastDigits()).isEqualTo("4242");
	}

	@Test
	void lastDigitsOfAFourCharacterNumberIsTheNumberItself() {
		assertThat(new CardData("Ana Souza", "1357", "12/28", "123").lastDigits()).isEqualTo("1357");
	}

	@ParameterizedTest
	@ValueSource(strings = { "1", "12", "123" })
	void lastDigitsOfANumberWithFewerThanFourCharactersThrowsWithoutTheValue(String shortNumber) {
		CardData card = new CardData("Ana Souza", shortNumber, "12/28", "123");

		assertThatThrownBy(card::lastDigits).isInstanceOf(IllegalArgumentException.class)
				.message().doesNotContain(shortNumber);
	}

}
