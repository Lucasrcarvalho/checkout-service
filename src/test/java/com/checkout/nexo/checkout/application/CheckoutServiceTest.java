package com.checkout.nexo.checkout.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.annotation.Transactional;

import com.checkout.nexo.checkout.domain.AllowedPaymentMethod;
import com.checkout.nexo.checkout.domain.AttemptStatus;
import com.checkout.nexo.checkout.domain.CardData;
import com.checkout.nexo.checkout.domain.Cart;
import com.checkout.nexo.checkout.domain.CartItem;
import com.checkout.nexo.checkout.domain.CartStatus;
import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.checkout.nexo.checkout.domain.CheckoutException;
import com.checkout.nexo.checkout.domain.PaymentAttempt;
import com.checkout.nexo.checkout.persistence.CartRepository;
import com.checkout.nexo.checkout.persistence.PaymentAttemptRepository;
import com.checkout.nexo.checkout.port.ChargeRequest;
import com.checkout.nexo.checkout.port.ChargeResult;
import com.checkout.nexo.checkout.port.PaymentProcessor;

/**
 * Regras do checkout (CHK-03, CHK-04, CHK-06 a CHK-13, ERR-03, OBS-01) com repositorios e
 * processador simulados e o {@link CardValidator} real. O carrinho e uma entidade real, para que
 * se afirme o estado final dele e nao so chamadas.
 */
@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
class CheckoutServiceTest {

	private static final UUID CART_ID = UUID.fromString("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");

	private static final Instant CREATED_AT = Instant.parse("2026-10-14T15:00:00Z");

	private static final Instant NOW = Instant.parse("2026-10-14T16:00:00.987654Z");

	private static final AllowedPaymentMethod CREDIT = new AllowedPaymentMethod("CREDIT_CARD", "Cartão de Crédito", true);

	private static final AllowedPaymentMethod DEBIT = new AllowedPaymentMethod("DEBIT_CARD", "Cartão de Débito", true);

	private static final AllowedPaymentMethod PIX = new AllowedPaymentMethod("PIX", "Pix", false);

	private static final CartItem FAMILIA = new CartItem("familia", "Plano Família", "Assinatura mensal para até 4 perfis",
			new BigDecimal("59.90"), "BRL");

	private static final CartItem BASICO = new CartItem("basico", "Plano Básico",
			"Assinatura mensal com o essencial do catálogo", new BigDecimal("19.90"), "BRL");

	/** Valores distintivos do cartao para a varredura do log. */
	private static final String NUMBER = "4111111111114242";

	private static final String CVV = "9876";

	private static final String HOLDER = "Fulana Distintiva da Silva";

	private static final String EXPIRY = "11/29";

	private static final CardData VALID_CARD = new CardData(HOLDER, NUMBER, EXPIRY, CVV);

	@Mock
	private CartRepository carts;

	@Mock
	private PaymentAttemptRepository attempts;

	@Mock
	private PaymentProcessor paymentProcessor;

	private CheckoutService service;

	@BeforeEach
	void createTheService() {
		service = new CheckoutService(carts, attempts, paymentProcessor, new CardValidator(),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static Cart openCart() {
		return Cart.open(CART_ID, List.of(FAMILIA, BASICO), List.of(CREDIT, DEBIT, PIX), CREATED_AT);
	}

	private static Cart approvedCart() {
		Cart cart = openCart();
		cart.approve(Instant.parse("2026-10-14T15:30:00Z"));
		return cart;
	}

	private Cart existing(Cart cart) {
		when(carts.findByIdForUpdate(CART_ID)).thenReturn(Optional.of(cart));
		return cart;
	}

	private PaymentAttempt savedAttempt() {
		ArgumentCaptor<PaymentAttempt> captor = ArgumentCaptor.forClass(PaymentAttempt.class);
		verify(attempts).save(captor.capture());
		return captor.getValue();
	}

	private void assertNothingWasChargedNorRecorded() {
		verify(paymentProcessor, never()).charge(any());
		verify(attempts, never()).save(any());
	}

	private static CheckoutCommand command(String methodName, CardData card) {
		return new CheckoutCommand(CART_ID, methodName, card);
	}

	private static void assertRejectedWith(Throwable thrown, CheckoutErrorCode code) {
		assertThat(thrown).isInstanceOfSatisfying(CheckoutException.class, ex -> {
			assertThat(ex.code()).isEqualTo(code);
			assertThat(ex.cartId()).isEqualTo(CART_ID);
		});
	}

	// ---- Transacao --------------------------------------------------------------------------

	@Test
	void theCheckoutMethodIsAnnotatedAsAWritableTransaction() throws NoSuchMethodException {
		Method method = CheckoutService.class.getMethod("checkout", CheckoutCommand.class);

		Transactional transactional = method.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.readOnly()).isFalse();
	}

	// ---- Aprovado com cartao (CHK-01, CHK-03) -----------------------------------------------

	@Test
	void approvedCardRecordsAnApprovedAttemptWithTheLastDigitsAndNoPixCode() {
		Cart cart = existing(openCart());
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Approved("4242", null));

		service.checkout(command("Cartão de Crédito", VALID_CARD));

		PaymentAttempt attempt = savedAttempt();
		assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.APPROVED);
		assertThat(attempt.getMethodCode()).isEqualTo("CREDIT_CARD");
		assertThat(attempt.getLastDigits()).isEqualTo("4242");
		assertThat(attempt.getPixCode()).isNull();
		assertThat(attempt.getAmount()).isEqualTo(new BigDecimal("79.80"));
		assertThat(attempt.getCurrency()).isEqualTo("BRL");
		assertThat(attempt.getCreatedAt()).isEqualTo(NOW);
		assertThat(attempt.getCart()).isSameAs(cart);
	}

	@Test
	void approvedCardApprovesTheCartWithThePaidAtOfTheClock() {
		Cart cart = existing(openCart());
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Approved("4242", null));

		service.checkout(command("Cartão de Crédito", VALID_CARD));

		assertThat(cart.getStatus()).isEqualTo(CartStatus.APPROVED);
		assertThat(cart.getPaidAt()).isEqualTo(NOW);
	}

	@Test
	void approvedCardReturnsAnImmutableCopyWithTheItemsInTheCartOrder() {
		existing(openCart());
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Approved("4242", null));

		CheckoutOutcome outcome = service.checkout(command("Cartão de Crédito", VALID_CARD));

		assertThat(outcome).isEqualTo(new CheckoutOutcome.Approved(new ApprovedCheckout(CART_ID, NOW,
				"Cartão de Crédito", "4242", null, new BigDecimal("79.80"), "BRL", List.of(FAMILIA, BASICO))));
		ApprovedCheckout approved = ((CheckoutOutcome.Approved) outcome).checkout();
		assertThatThrownBy(() -> approved.items().add(BASICO)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void debitIsApprovedWithItsOwnMethodCode() {
		existing(openCart());
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Approved("4242", null));

		CheckoutOutcome outcome = service.checkout(command("Cartão de Débito", VALID_CARD));

		assertThat(savedAttempt().getMethodCode()).isEqualTo("DEBIT_CARD");
		assertThat(((CheckoutOutcome.Approved) outcome).checkout().methodName()).isEqualTo("Cartão de Débito");
	}

	// ---- Aprovado com Pix (CHK-02, CHK-03) --------------------------------------------------

	@Test
	void approvedPixRecordsTheAttemptWithThePixCodeAndNoLastDigits() {
		Cart cart = existing(openCart());
		String pixCode = "PIX-SIMULADO-" + CART_ID;
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Approved(null, pixCode));

		CheckoutOutcome outcome = service.checkout(command("Pix", null));

		PaymentAttempt attempt = savedAttempt();
		assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.APPROVED);
		assertThat(attempt.getMethodCode()).isEqualTo("PIX");
		assertThat(attempt.getPixCode()).isEqualTo(pixCode);
		assertThat(attempt.getLastDigits()).isNull();
		assertThat(cart.getStatus()).isEqualTo(CartStatus.APPROVED);
		assertThat(outcome).isEqualTo(new CheckoutOutcome.Approved(new ApprovedCheckout(CART_ID, NOW, "Pix", null,
				pixCode, new BigDecimal("79.80"), "BRL", List.of(FAMILIA, BASICO))));
	}

	// ---- Cobranca do snapshot (CHK-04) ------------------------------------------------------

	@Test
	void chargeReceivesTheCartTotalAndCurrencyTheMethodSnapshotAndTheCard() {
		existing(openCart());
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Approved("4242", null));

		service.checkout(command("Cartão de Crédito", VALID_CARD));

		ArgumentCaptor<ChargeRequest> captor = ArgumentCaptor.forClass(ChargeRequest.class);
		verify(paymentProcessor).charge(captor.capture());
		ChargeRequest request = captor.getValue();
		assertThat(request.cartId()).isEqualTo(CART_ID);
		assertThat(request.amount()).isEqualTo(new BigDecimal("79.80"));
		assertThat(request.currency()).isEqualTo("BRL");
		assertThat(request.method()).isEqualTo(CREDIT);
		assertThat(request.card()).isSameAs(VALID_CARD);
	}

	// ---- Recusa (CHK-06) --------------------------------------------------------------------

	@Test
	void declinedRecordsADeclinedAttemptKeepsTheCartOpenAndReturnsDeclined() {
		Cart cart = existing(openCart());
		CardData declinedCard = new CardData(HOLDER, "4111111111110002", EXPIRY, CVV);
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Declined());

		CheckoutOutcome outcome = service.checkout(command("Cartão de Crédito", declinedCard));

		assertThat(outcome).isEqualTo(new CheckoutOutcome.Declined(CART_ID));
		PaymentAttempt attempt = savedAttempt();
		assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.DECLINED);
		assertThat(attempt.getMethodCode()).isEqualTo("CREDIT_CARD");
		assertThat(attempt.getLastDigits()).isEqualTo("0002");
		assertThat(attempt.getPixCode()).isNull();
		assertThat(attempt.getAmount()).isEqualTo(new BigDecimal("79.80"));
		assertThat(cart.getStatus()).isEqualTo(CartStatus.OPEN);
		assertThat(cart.getPaidAt()).isNull();
	}

	// ---- Carrinho inexistente / ja pago (CHK-08, CHK-09) ------------------------------------

	@Test
	void anUnknownCartFailsWithCartNotFoundCarryingTheRequestCartIdAndChargesNothing() {
		when(carts.findByIdForUpdate(CART_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.checkout(command("Cartão de Crédito", VALID_CARD)))
				.satisfies(thrown -> assertRejectedWith(thrown, CheckoutErrorCode.CART_NOT_FOUND));
		assertNothingWasChargedNorRecorded();
	}

	@Test
	void anAlreadyApprovedCartFailsWithCartAlreadyPaidWithoutChargeAndWithoutANewAttempt() {
		existing(approvedCart());

		assertThatThrownBy(() -> service.checkout(command("Cartão de Crédito", VALID_CARD)))
				.satisfies(thrown -> assertRejectedWith(thrown, CheckoutErrorCode.CART_ALREADY_PAID));
		assertNothingWasChargedNorRecorded();
	}

	// ---- Meio nao permitido (CHK-10) --------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "cartão de crédito", "CARTÃO DE CRÉDITO", "Boleto", "PIX", "CREDIT_CARD", "" })
	void aMethodNameDifferentFromTheSnapshotFailsWithPaymentMethodNotAllowedWithoutCharge(String name) {
		existing(openCart());

		assertThatThrownBy(() -> service.checkout(command(name, VALID_CARD)))
				.satisfies(thrown -> assertRejectedWith(thrown, CheckoutErrorCode.PAYMENT_METHOD_NOT_ALLOWED));
		assertNothingWasChargedNorRecorded();
	}

	@Test
	void aDecomposedNfdMethodNameIsAcceptedAndReportedWithTheSnapshotName() {
		existing(openCart());
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Approved("4242", null));
		String nfd = Normalizer.normalize("Cartão de Crédito", Normalizer.Form.NFD);
		assertThat(nfd).isNotEqualTo("Cartão de Crédito");

		CheckoutOutcome outcome = service.checkout(command(nfd, VALID_CARD));

		assertThat(((CheckoutOutcome.Approved) outcome).checkout().methodName()).isEqualTo("Cartão de Crédito");
		assertThat(savedAttempt().getMethodCode()).isEqualTo("CREDIT_CARD");
	}

	// ---- Dados do pagamento (CHK-11, CHK-12) ------------------------------------------------

	@Test
	void creditWithoutCardFailsWithInvalidPaymentDataWithoutChargeNorAttempt() {
		existing(openCart());

		assertThatThrownBy(() -> service.checkout(command("Cartão de Crédito", null)))
				.satisfies(thrown -> assertRejectedWith(thrown, CheckoutErrorCode.INVALID_PAYMENT_DATA));
		assertNothingWasChargedNorRecorded();
	}

	@Test
	void pixWithACardFailsWithInvalidPaymentDataWithoutChargeNorAttempt() {
		existing(openCart());

		assertThatThrownBy(() -> service.checkout(command("Pix", VALID_CARD)))
				.satisfies(thrown -> assertRejectedWith(thrown, CheckoutErrorCode.INVALID_PAYMENT_DATA));
		assertNothingWasChargedNorRecorded();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"Fulana|4111 1111 1111 4242|11/29|9876",
			"Fulana|411111111111|11/29|9876",
			"Fulana|4111111111114242|09/26|9876",
			"Fulana|4111111111114242|11/29|12",
			"Fulana|4111111111114242|11/29|abc",
			"  |4111111111114242|11/29|9876" })
	void anInvalidCardFieldFailsWithInvalidPaymentDataCarryingTheCartIdWithoutChargeNorAttempt(String holder,
			String number, String expiry, String cvv) {
		existing(openCart());

		assertThatThrownBy(() -> service.checkout(command("Cartão de Crédito", new CardData(holder, number, expiry, cvv))))
				.satisfies(thrown -> assertRejectedWith(thrown, CheckoutErrorCode.INVALID_PAYMENT_DATA));
		assertNothingWasChargedNorRecorded();
	}

	@Test
	void theCardIsValidatedAgainstTheServiceClockSoTheCurrentMonthExpiryIsAccepted() {
		existing(openCart());
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Approved("4242", null));

		service.checkout(command("Cartão de Crédito", new CardData(HOLDER, NUMBER, "10/26", CVV)));

		assertThat(savedAttempt().getStatus()).isEqualTo(AttemptStatus.APPROVED);
	}

	// ---- Precedencia (CHK-13) ---------------------------------------------------------------

	@Test
	void anApprovedCartWithAnInvalidMethodReportsCartAlreadyPaid() {
		existing(approvedCart());

		assertThatThrownBy(() -> service.checkout(command("Boleto", VALID_CARD)))
				.satisfies(thrown -> assertRejectedWith(thrown, CheckoutErrorCode.CART_ALREADY_PAID));
	}

	@Test
	void anInvalidMethodWithAnInvalidCardReportsPaymentMethodNotAllowed() {
		existing(openCart());

		assertThatThrownBy(() -> service.checkout(command("Boleto", new CardData("", "12", "99/99", "1"))))
				.satisfies(thrown -> assertRejectedWith(thrown, CheckoutErrorCode.PAYMENT_METHOD_NOT_ALLOWED));
	}

	@Test
	void anUnknownCartPrevailsOverAnInvalidMethodAndAnInvalidCard() {
		when(carts.findByIdForUpdate(CART_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.checkout(command("Boleto", new CardData("", "12", "99/99", "1"))))
				.satisfies(thrown -> assertRejectedWith(thrown, CheckoutErrorCode.CART_NOT_FOUND));
		verifyNoInteractions(paymentProcessor, attempts);
	}

	// ---- Log (OBS-01, DATA-02) --------------------------------------------------------------

	private static List<String> checkoutLines(CapturedOutput output) {
		return output.getAll().lines().filter(line -> line.contains("CheckoutService")).toList();
	}

	@Test
	void anApprovedCheckoutLogsOneInfoLineWithCartIdMethodResultAndNoCardData(CapturedOutput output) {
		existing(openCart());
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Approved("4242", null));

		service.checkout(command("Cartão de Crédito", VALID_CARD));

		assertThat(checkoutLines(output)).singleElement().satisfies(line -> {
			assertThat(line).contains(" INFO ").contains("cartId=" + CART_ID).contains("result=APPROVED")
					.contains("errorCode=NONE").contains("method=CREDIT_CARD");
		});
		assertThat(output.getAll()).doesNotContain(NUMBER).doesNotContain("4111 1111 1111 4242")
				.doesNotContain("411111").doesNotContain(CVV).doesNotContain(HOLDER).doesNotContain(EXPIRY);
	}

	@Test
	void aDeclinedCheckoutLogsOneInfoLineWithTheDeclinedResultAndTheErrorCode(CapturedOutput output) {
		existing(openCart());
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Declined());

		service.checkout(command("Cartão de Crédito", new CardData(HOLDER, "4111111111110002", EXPIRY, CVV)));

		assertThat(checkoutLines(output)).singleElement().satisfies(line -> assertThat(line)
				.contains(" INFO ").contains("cartId=" + CART_ID).contains("result=DECLINED")
				.contains("errorCode=PAYMENT_DECLINED"));
		assertThat(output.getAll()).doesNotContain("4111111111110002").doesNotContain(CVV).doesNotContain(HOLDER);
	}

	@Test
	void aBusinessErrorLogsOneInfoLineWithTheErrorCodeAndNoCardData(CapturedOutput output) {
		existing(approvedCart());

		assertThatThrownBy(() -> service.checkout(command("Cartão de Crédito", VALID_CARD)))
				.isInstanceOf(CheckoutException.class);

		assertThat(checkoutLines(output)).singleElement().satisfies(line -> assertThat(line)
				.contains(" INFO ").contains("cartId=" + CART_ID).contains("result=REJECTED")
				.contains("errorCode=CART_ALREADY_PAID"));
		assertThat(output.getAll()).doesNotContain(NUMBER).doesNotContain(CVV).doesNotContain(HOLDER)
				.doesNotContain(EXPIRY);
	}

	// ---- Log sem texto livre do cliente (OBS-01, DATA-02, F3) --------------------------------

	/** A mensagem completa da linha de log, no formato registrado no spec (Assumptions). */
	private static String logMessage(String result, String errorCode, String method) {
		return "Checkout: cartId=" + CART_ID + " result=" + result + " errorCode=" + errorCode + " method=" + method;
	}

	private static void assertSingleInfoLine(CapturedOutput output, String expectedMessage) {
		assertThat(checkoutLines(output)).singleElement().satisfies(line -> assertThat(line).contains(" INFO ")
				.endsWith(expectedMessage));
	}

	@Test
	void aMethodNameThatIsACardNumberNeverReachesTheLogAndTheMethodIsADash(CapturedOutput output) {
		existing(openCart());

		assertThatThrownBy(() -> service.checkout(command(NUMBER, VALID_CARD))).isInstanceOf(CheckoutException.class);

		assertSingleInfoLine(output, logMessage("REJECTED", "PAYMENT_METHOD_NOT_ALLOWED", "-"));
		assertThat(output.getAll()).doesNotContain(NUMBER);
	}

	@ParameterizedTest
	@CsvSource({ "Cartão de Crédito,CREDIT_CARD", "Cartão de Débito,DEBIT_CARD", "Pix,PIX" })
	void aResolvedMethodIsLoggedByItsSnapshotCode(String requestedName, String expectedCode, CapturedOutput output) {
		existing(openCart());
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Approved("4242", null));
		CardData card = "Pix".equals(requestedName) ? null : VALID_CARD;

		service.checkout(command(requestedName, card));

		assertSingleInfoLine(output, logMessage("APPROVED", "NONE", expectedCode));
	}

	@Test
	void aDeclinedCheckoutLogsTheSnapshotCodeOfTheMethod(CapturedOutput output) {
		existing(openCart());
		when(paymentProcessor.charge(any())).thenReturn(new ChargeResult.Declined());

		service.checkout(command("Cartão de Crédito", new CardData(HOLDER, "4111111111110002", EXPIRY, CVV)));

		assertSingleInfoLine(output, logMessage("DECLINED", "PAYMENT_DECLINED", "CREDIT_CARD"));
	}

	@Test
	void anUnresolvedMethodIsLoggedAsADashAndTheRequestedNameIsNotLogged(CapturedOutput output) {
		existing(openCart());

		assertThatThrownBy(() -> service.checkout(command("Boleto", VALID_CARD))).isInstanceOf(CheckoutException.class);

		assertSingleInfoLine(output, logMessage("REJECTED", "PAYMENT_METHOD_NOT_ALLOWED", "-"));
		assertThat(output.getAll()).doesNotContain("Boleto");
	}

	@Test
	void aMissingCartIsLoggedWithADashAsTheMethodEvenWhenTheNameMatchesAMethod(CapturedOutput output) {
		when(carts.findByIdForUpdate(CART_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.checkout(command("Cartão de Crédito", VALID_CARD)))
				.isInstanceOf(CheckoutException.class);

		assertSingleInfoLine(output, logMessage("REJECTED", "CART_NOT_FOUND", "-"));
	}

	@Test
	void anAlreadyPaidCartIsLoggedWithADashAsTheMethodBecauseTheMethodWasNeverResolved(CapturedOutput output) {
		existing(approvedCart());

		assertThatThrownBy(() -> service.checkout(command("Cartão de Crédito", VALID_CARD)))
				.isInstanceOf(CheckoutException.class);

		assertSingleInfoLine(output, logMessage("REJECTED", "CART_ALREADY_PAID", "-"));
	}

	@Test
	void invalidPaymentDataAfterTheMethodIsResolvedLogsTheSnapshotCode(CapturedOutput output) {
		existing(openCart());

		assertThatThrownBy(() -> service.checkout(command("Cartão de Crédito", new CardData(HOLDER, "12", EXPIRY, CVV))))
				.isInstanceOf(CheckoutException.class);

		assertSingleInfoLine(output, logMessage("REJECTED", "INVALID_PAYMENT_DATA", "CREDIT_CARD"));
	}

	@Test
	void aHostileMethodNameWithControlCharactersAndLineBreaksNeverReachesTheLog(CapturedOutput output) {
		existing(openCart());
		String hostile = "ab\ncd\re\tf\u0007g" + "x".repeat(100) + "\nforged-line cartId=0 result=APPROVED";

		assertThatThrownBy(() -> service.checkout(command(hostile, VALID_CARD))).isInstanceOf(CheckoutException.class);

		assertSingleInfoLine(output, logMessage("REJECTED", "PAYMENT_METHOD_NOT_ALLOWED", "-"));
		assertThat(output.getAll()).doesNotContain("abcdefg").doesNotContain("xxxxxxxx").doesNotContain("forged");
	}

}
