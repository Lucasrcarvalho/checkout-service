package com.checkout.nexo.checkout.application;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.checkout.nexo.checkout.domain.CardData;
import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.checkout.nexo.checkout.domain.CheckoutException;

/**
 * Valida os campos do cartao (CHK-12). Nao aplica Luhn (D-11). A excecao nunca inclui valor
 * do cartao (AD-008).
 */
@Component
public class CardValidator {

	private static final Pattern NUMBER = Pattern.compile("[0-9]{13,19}");

	private static final Pattern EXPIRY = Pattern.compile("(0[1-9]|1[0-2])/([0-9]{2})");

	private static final Pattern CVV = Pattern.compile("[0-9]{3,4}");

	private static final int HOLDER_NAME_MAX_LENGTH = 50;

	/**
	 * @throws CheckoutException {@code INVALID_PAYMENT_DATA}, sem {@code cartId}: quem chama
	 * conhece o carrinho da requisicao.
	 */
	public void validate(CardData card, Clock clock) {
		if (!matches(NUMBER, card.number()) || !isExpiryValid(card.expiry(), clock) || !matches(CVV, card.cvv())
				|| !isHolderNameValid(card.holderName())) {
			throw new CheckoutException(CheckoutErrorCode.INVALID_PAYMENT_DATA);
		}
	}

	private static boolean matches(Pattern pattern, String value) {
		return value != null && pattern.matcher(value).matches();
	}

	/** Valido enquanto o mes de validade for maior ou igual ao mes atual em UTC. */
	private static boolean isExpiryValid(String expiry, Clock clock) {
		if (expiry == null) {
			return false;
		}
		Matcher matcher = EXPIRY.matcher(expiry);
		if (!matcher.matches()) {
			return false;
		}
		YearMonth expiryMonth = YearMonth.of(2000 + Integer.parseInt(matcher.group(2)),
				Integer.parseInt(matcher.group(1)));
		YearMonth currentMonth = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
		return !expiryMonth.isBefore(currentMonth);
	}

	private static boolean isHolderNameValid(String holderName) {
		if (holderName == null) {
			return false;
		}
		int length = holderName.strip().length();
		return length >= 1 && length <= HOLDER_NAME_MAX_LENGTH;
	}

}
