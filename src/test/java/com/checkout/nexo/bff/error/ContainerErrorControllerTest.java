package com.checkout.nexo.bff.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.checkout.nexo.checkout.domain.CheckoutErrorCode;

import jakarta.servlet.RequestDispatcher;

/**
 * Mapeamento do despacho de erro do container (ERR-01, ERR-04, ERR-05). O despacho real, com o
 * Tomcat, esta em {@link ContainerErrorIT}.
 */
@ExtendWith(OutputCaptureExtension.class)
class ContainerErrorControllerTest {

	private final ContainerErrorController controller = new ContainerErrorController(
			new ErrorCatalog("/checkout/pagamento"));

	private static MockHttpServletRequest dispatchedWith(Object statusCode) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, statusCode);
		return request;
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 404, 405, 415, 499 })
	void aClientErrorStatusIsKeptAndTheBodyIsTheInvalidRequestObject(int status) {
		ResponseEntity<ErrorResponse> response = controller.error(dispatchedWith(status));

		assertThat(response.getStatusCode().value()).isEqualTo(status);
		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		ErrorResponse body = response.getBody();
		assertThat(body.errorCode()).isEqualTo(CheckoutErrorCode.INVALID_REQUEST);
		assertThat(body.displayType()).isEqualTo(DisplayType.BLOCK);
		assertThat(body.cartId()).isNull();
		assertThat(body.frontendError().code()).isEqualTo("R-1001");
		assertThat(body.frontendError().secondaryButton()).isNull();
	}

	@ParameterizedTest
	@ValueSource(ints = { 500, 502, 503, 599 })
	void aServerErrorStatusIsKeptAndTheBodyIsTheInternalErrorObject(int status) {
		ResponseEntity<ErrorResponse> response = controller.error(dispatchedWith(status));

		assertThat(response.getStatusCode().value()).isEqualTo(status);
		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		ErrorResponse body = response.getBody();
		assertThat(body.errorCode()).isEqualTo(CheckoutErrorCode.INTERNAL_ERROR);
		assertThat(body.cartId()).isNull();
		assertThat(body.frontendError().code()).isEqualTo("S-1002");
		assertThat(body.frontendError().secondaryButton()).isNull();
	}

	@Test
	void openingTheErrorPathDirectlyIsAnUnknownRoute() {
		ResponseEntity<ErrorResponse> response = controller.error(new MockHttpServletRequest());

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().errorCode()).isEqualTo(CheckoutErrorCode.INVALID_REQUEST);
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 200, 302, 399, 600, 999 })
	void aStatusOutsideTheErrorRangeBecomesInternalError(int status) {
		ResponseEntity<ErrorResponse> response = controller.error(dispatchedWith(status));

		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody().errorCode()).isEqualTo(CheckoutErrorCode.INTERNAL_ERROR);
	}

	@Test
	void aStatusThatIsNotANumberBecomesInternalError() {
		ResponseEntity<ErrorResponse> response = controller.error(dispatchedWith("404"));

		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody().errorCode()).isEqualTo(CheckoutErrorCode.INTERNAL_ERROR);
	}

	@Test
	void aClientErrorIsLoggedAtWarnWithOnlyTheStatusAndNothingTheClientSent(CapturedOutput output) {
		MockHttpServletRequest request = dispatchedWith(404);
		request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/cartao/4111111111114242");
		request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "cvv 123 do titular Ana Souza");

		ResponseEntity<ErrorResponse> response = controller.error(request);

		assertThat(output.getAll()).contains(" WARN ").contains("status=404")
				.doesNotContain("4111111111114242").doesNotContain("cvv 123").doesNotContain("Ana Souza");
		assertThat(response.getBody().toString()).doesNotContain("4111111111114242").doesNotContain("cvv 123")
				.doesNotContain("Ana Souza");
	}

	@Test
	void aServerErrorIsLoggedAtErrorWithTheCauseAndStaysOutOfTheBody(CapturedOutput output) {
		MockHttpServletRequest request = dispatchedWith(500);
		request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("falha-interna-do-filtro"));
		request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "mensagem do container");

		ResponseEntity<ErrorResponse> response = controller.error(request);

		assertThat(output.getAll()).contains(" ERROR ").contains("status=500").contains("IllegalStateException")
				.contains("falha-interna-do-filtro");
		assertThat(response.getBody().toString()).doesNotContain("falha-interna-do-filtro")
				.doesNotContain("IllegalStateException").doesNotContain("mensagem do container");
	}

	@Test
	void aServerErrorWithoutACauseIsStillLoggedAtError(CapturedOutput output) {
		controller.error(dispatchedWith(503));

		assertThat(output.getAll()).contains(" ERROR ").contains("status=503");
	}

}
