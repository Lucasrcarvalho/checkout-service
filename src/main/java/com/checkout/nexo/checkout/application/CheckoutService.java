package com.checkout.nexo.checkout.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkout.nexo.checkout.domain.AllowedPaymentMethod;
import com.checkout.nexo.checkout.domain.Cart;
import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.checkout.nexo.checkout.domain.CheckoutException;
import com.checkout.nexo.checkout.domain.PaymentAttempt;
import com.checkout.nexo.checkout.persistence.CartRepository;
import com.checkout.nexo.checkout.persistence.PaymentAttemptRepository;
import com.checkout.nexo.checkout.port.ChargeRequest;
import com.checkout.nexo.checkout.port.ChargeResult;
import com.checkout.nexo.checkout.port.PaymentProcessor;

/**
 * Caso de uso do checkout (CHK-03, CHK-04, CHK-06 a CHK-13, DUP-01, OBS-01): uma transacao com lock
 * pessimista no carrinho. Nao consulta os catalogos: cobra o total do snapshot (CHK-04).
 */
@Service
public class CheckoutService {

	private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

	/** Valor de {@code method} na linha de log quando o meio nao chegou a ser resolvido no carrinho. */
	private static final String NO_METHOD = "-";

	private final CartRepository carts;

	private final PaymentAttemptRepository attempts;

	private final PaymentProcessor paymentProcessor;

	private final CardValidator cardValidator;

	private final Clock clock;

	public CheckoutService(CartRepository carts, PaymentAttemptRepository attempts, PaymentProcessor paymentProcessor,
			CardValidator cardValidator, Clock clock) {
		this.carts = carts;
		this.attempts = attempts;
		this.paymentProcessor = paymentProcessor;
		this.cardValidator = cardValidator;
		this.clock = clock;
	}

	/**
	 * Verifica na ordem de CHK-13 e cobra. A recusa grava a tentativa {@code DECLINED}, mantem o
	 * carrinho aberto e volta como {@link CheckoutOutcome.Declined}.
	 *
	 * @throws CheckoutException {@code CART_NOT_FOUND}, {@code CART_ALREADY_PAID},
	 * {@code PAYMENT_METHOD_NOT_ALLOWED} ou {@code INVALID_PAYMENT_DATA}, sempre com o
	 * {@code cartId} da requisicao (ERR-03)
	 */
	@Transactional
	public CheckoutOutcome checkout(CheckoutCommand command) {
		UUID cartId = command.cartId();
		String loggedMethod = NO_METHOD;
		try {
			Cart cart = carts.findByIdForUpdate(cartId)
					.orElseThrow(() -> new CheckoutException(CheckoutErrorCode.CART_NOT_FOUND, cartId));
			if (cart.isApproved()) {
				throw new CheckoutException(CheckoutErrorCode.CART_ALREADY_PAID, cartId);
			}
			AllowedPaymentMethod method = cart.findMethod(command.methodName())
					.orElseThrow(() -> new CheckoutException(CheckoutErrorCode.PAYMENT_METHOD_NOT_ALLOWED, cartId));
			loggedMethod = method.code();
			validateCardPresenceAndFields(command, method);

			CheckoutOutcome outcome = charge(cart, method, command);
			if (outcome instanceof CheckoutOutcome.Declined) {
				logResult(cartId, "DECLINED", CheckoutErrorCode.PAYMENT_DECLINED.name(), loggedMethod);
			}
			else {
				logResult(cartId, "APPROVED", "NONE", loggedMethod);
			}
			return outcome;
		}
		catch (CheckoutException ex) {
			logResult(cartId, "REJECTED", ex.code().name(), loggedMethod);
			throw ex;
		}
	}

	private CheckoutOutcome charge(Cart cart, AllowedPaymentMethod method, CheckoutCommand command) {
		ChargeResult result = paymentProcessor.charge(new ChargeRequest(command.cartId(), cart.getTotalAmount(),
				cart.getCurrency(), method, command.card()));
		Instant now = clock.instant();
		return switch (result) {
			case ChargeResult.Approved approved -> approve(cart, method, approved, now);
			case ChargeResult.Declined declined -> {
				attempts.save(PaymentAttempt.declined(cart, method.code(), command.card().lastDigits(), now));
				yield new CheckoutOutcome.Declined(command.cartId());
			}
		};
	}

	private void validateCardPresenceAndFields(CheckoutCommand command, AllowedPaymentMethod method) {
		boolean cardMissing = method.requiresCard() && command.card() == null;
		boolean cardNotExpected = !method.requiresCard() && command.card() != null;
		if (cardMissing || cardNotExpected) {
			throw new CheckoutException(CheckoutErrorCode.INVALID_PAYMENT_DATA, command.cartId());
		}
		if (method.requiresCard()) {
			try {
				cardValidator.validate(command.card(), clock);
			}
			catch (CheckoutException ex) {
				throw new CheckoutException(ex.code(), command.cartId());
			}
		}
	}

	private CheckoutOutcome approve(Cart cart, AllowedPaymentMethod method, ChargeResult.Approved approved,
			Instant now) {
		PaymentAttempt attempt = approved.pixCode() != null
				? PaymentAttempt.approvedPix(cart, method.code(), approved.pixCode(), now)
				: PaymentAttempt.approvedCard(cart, method.code(), approved.lastDigits(), now);
		attempts.save(attempt);
		cart.approve(now);
		return new CheckoutOutcome.Approved(new ApprovedCheckout(cart.getId(), now, method.name(),
				approved.lastDigits(), approved.pixCode(), cart.getTotalAmount(), cart.getCurrency(),
				List.copyOf(cart.getItems())));
	}

	/**
	 * Uma linha INFO por chamada (OBS-01). O meio sai pelo {@code code} do snapshot do carrinho (ou
	 * {@code -} se nao foi resolvido): o nome enviado pelo cliente e texto livre e nunca vai ao log
	 * (AD-008, DATA-02). Nunca ha dado de cartao aqui.
	 */
	private void logResult(UUID cartId, String result, String errorCode, String methodCode) {
		log.info("Checkout: cartId={} result={} errorCode={} method={}", cartId, result, errorCode, methodCode);
	}

}
