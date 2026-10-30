package com.checkout.nexo.integration.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.checkout.nexo.checkout.domain.AllowedPaymentMethod;
import com.checkout.nexo.checkout.domain.CardData;
import com.checkout.nexo.checkout.port.ChargeRequest;
import com.checkout.nexo.checkout.port.ChargeResult;

/**
 * Cobranca simulada (CHK-02, CHK-06) e codigo Pix. O unico gatilho de recusa do spec e o numero
 * terminado em {@code 0002}.
 */
class SimulatedPaymentProcessorTest {

	private static final UUID CART_ID = UUID.fromString("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");

	private static final AllowedPaymentMethod CREDIT = new AllowedPaymentMethod("CREDIT_CARD", "Cartão de Crédito", true);

	private static final AllowedPaymentMethod DEBIT = new AllowedPaymentMethod("DEBIT_CARD", "Cartão de Débito", true);

	private static final AllowedPaymentMethod PIX = new AllowedPaymentMethod("PIX", "Pix", false);

	private final SimulatedPaymentProcessor processor = new SimulatedPaymentProcessor(new PixCodeGenerator());

	private static ChargeRequest request(AllowedPaymentMethod method, CardData card) {
		return new ChargeRequest(CART_ID, new BigDecimal("39.90"), "BRL", method, card);
	}

	private static CardData card(String number) {
		return new CardData("Ana Souza", number, "12/28", "123");
	}

	// ---- Recusa (CHK-06) --------------------------------------------------------------------

	@ParameterizedTest
	@CsvSource({ "CREDIT_CARD,Cartão de Crédito", "DEBIT_CARD,Cartão de Débito" })
	void aCardNumberEndingInZeroZeroZeroTwoIsDeclinedForCreditAndDebit(String code, String name) {
		AllowedPaymentMethod method = new AllowedPaymentMethod(code, name, true);

		ChargeResult result = processor.charge(request(method, card("4111111111110002")));

		assertThat(result).isEqualTo(new ChargeResult.Declined());
	}

	@Test
	void aShorterNumberEndingInZeroZeroZeroTwoIsAlsoDeclined() {
		assertThat(processor.charge(request(CREDIT, card("5555555550002")))).isEqualTo(new ChargeResult.Declined());
	}

	// ---- Aprovacao com cartao (CHK-01) ------------------------------------------------------

	@Test
	void theSpecExampleNumberIsApprovedWithItsLastFourDigitsAndNoPixCode() {
		ChargeResult result = processor.charge(request(CREDIT, card("4111111111114242")));

		assertThat(result).isEqualTo(new ChargeResult.Approved("4242", null));
	}

	@ParameterizedTest
	@CsvSource({ "5555555555554444,4444", "4111000211114242,4242", "4111111111110020,0020", "4111111111112000,2000",
			"4111111111111112,1112" })
	void anyOtherNumberIsApprovedOnlyTheFinalFourDigitsDecideTheDecline(String number, String expectedLastDigits) {
		ChargeResult result = processor.charge(request(DEBIT, card(number)));

		assertThat(result).isEqualTo(new ChargeResult.Approved(expectedLastDigits, null));
	}

	// ---- Pix (CHK-02) -----------------------------------------------------------------------

	@Test
	void pixWithoutCardIsApprovedWithTheSimulatedPixCodeAndNoLastDigits() {
		ChargeResult result = processor.charge(request(PIX, null));

		assertThat(result).isEqualTo(
				new ChargeResult.Approved(null, "PIX-SIMULADO-7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33"));
	}

	@Test
	void thePixCodeIsThePrefixPlusTheLowercaseHyphenatedCartId() {
		UUID upperCaseInput = UUID.fromString("7F3C2A10-5B9E-4D1C-8A46-0E2F9B7D1C33");

		assertThat(new PixCodeGenerator().generate(upperCaseInput))
				.isEqualTo("PIX-SIMULADO-7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");
	}

	// ---- Meio que exige cartao sem cartao ---------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "CREDIT_CARD", "DEBIT_CARD" })
	void aMethodThatRequiresACardWithoutCardDataFailsWithAnIllegalArgumentExceptionWithoutValues(String code) {
		AllowedPaymentMethod method = new AllowedPaymentMethod(code, "Cartão " + code, true);

		assertThatThrownBy(() -> processor.charge(request(method, null))).isInstanceOf(IllegalArgumentException.class)
				.message().doesNotContain(CART_ID.toString()).doesNotContain("39.90").doesNotContain(code)
				.doesNotContain("BRL");
	}

}
