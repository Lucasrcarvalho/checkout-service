package com.checkout.nexo.checkout.domain;

/**
 * Codigos de erro de negocio e tecnico expostos pela API (ERR-02).
 */
public enum CheckoutErrorCode {

	INVALID_REQUEST,
	PRODUCT_NOT_FOUND,
	CART_NOT_FOUND,
	PAYMENT_METHOD_NOT_ALLOWED,
	INVALID_PAYMENT_DATA,
	PAYMENT_DECLINED,
	CART_ALREADY_PAID,
	SERVICE_UNAVAILABLE,
	INTERNAL_ERROR

}
