package com.checkout.nexo.checkout.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CheckoutErrorCodeTest {

	@Test
	void hasExactlyTheNineCodesOfErr02() {
		assertThat(CheckoutErrorCode.values()).extracting(Enum::name).containsExactlyInAnyOrder(
				"INVALID_REQUEST",
				"PRODUCT_NOT_FOUND",
				"CART_NOT_FOUND",
				"PAYMENT_METHOD_NOT_ALLOWED",
				"INVALID_PAYMENT_DATA",
				"PAYMENT_DECLINED",
				"CART_ALREADY_PAID",
				"SERVICE_UNAVAILABLE",
				"INTERNAL_ERROR");
	}

}
