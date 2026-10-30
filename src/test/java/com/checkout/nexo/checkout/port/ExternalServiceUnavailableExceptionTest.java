package com.checkout.nexo.checkout.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class ExternalServiceUnavailableExceptionTest {

	@Test
	void preservesTheCause() {
		IOException cause = new IOException("connection refused");

		ExternalServiceUnavailableException exception = new ExternalServiceUnavailableException("products", cause);

		assertThat(exception.getCause()).isSameAs(cause);
	}

}
