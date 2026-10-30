package com.checkout.nexo.checkout.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CheckoutExceptionTest {

	private static final UUID CART_ID = UUID.fromString("7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33");

	@Test
	void exposesCodeAndCartIdWhenInformed() {
		CheckoutException exception = new CheckoutException(CheckoutErrorCode.CART_NOT_FOUND, CART_ID);

		assertThat(exception.code()).isEqualTo(CheckoutErrorCode.CART_NOT_FOUND);
		assertThat(exception.cartId()).isEqualTo(CART_ID);
	}

	@Test
	void cartIdIsNullWhenNotInformed() {
		CheckoutException exception = new CheckoutException(CheckoutErrorCode.PRODUCT_NOT_FOUND);

		assertThat(exception.code()).isEqualTo(CheckoutErrorCode.PRODUCT_NOT_FOUND);
		assertThat(exception.cartId()).isNull();
	}

	@Test
	void cartIdMayBePassedExplicitlyAsNull() {
		CheckoutException exception = new CheckoutException(CheckoutErrorCode.INVALID_REQUEST, null);

		assertThat(exception.cartId()).isNull();
	}

	@ParameterizedTest
	@EnumSource(CheckoutErrorCode.class)
	void messageIsOnlyTheCodeName(CheckoutErrorCode code) {
		assertThat(new CheckoutException(code, CART_ID).getMessage()).isEqualTo(code.name());
		assertThat(new CheckoutException(code).getMessage()).isEqualTo(code.name());
	}

}
