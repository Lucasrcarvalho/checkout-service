package com.checkout.nexo.bff.error;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.checkout.nexo.checkout.domain.CheckoutException;
import com.checkout.nexo.checkout.port.ExternalServiceUnavailableException;

/**
 * Converte toda excecao no objeto de erro unico (ERR-01). Por AD-008, nunca registra nem devolve
 * {@code getMessage()} de excecoes de parsing ou de validacao: so o tipo da excecao.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	/**
	 * Atributo da requisicao com o {@code cartId} (UUID ja validado) que o controller do checkout
	 * registra antes de chamar o servico, para o erro devolver o carrinho mesmo quando a excecao nao o
	 * carrega, como 503 de lock ou timeout e 500 (ERR-03).
	 */
	public static final String CART_ID_ATTRIBUTE = "checkout.cartId";

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final ErrorCatalog errorCatalog;

	public GlobalExceptionHandler(ErrorCatalog errorCatalog) {
		this.errorCatalog = errorCatalog;
	}

	@ExceptionHandler(CheckoutException.class)
	ResponseEntity<ErrorResponse> handleCheckoutException(CheckoutException ex, WebRequest request) {
		log.info("Erro de negocio: errorCode={}", ex.code());
		return respond(ex.code(), ex.cartId() != null ? ex.cartId() : requestCartId(request));
	}

	@ExceptionHandler({ ExternalServiceUnavailableException.class, PessimisticLockingFailureException.class,
			QueryTimeoutException.class })
	ResponseEntity<ErrorResponse> handleServiceUnavailable(Exception ex, WebRequest request) {
		log.warn("Dependencia indisponivel: tipo={}", ex.getClass().getSimpleName());
		return respond(CheckoutErrorCode.SERVICE_UNAVAILABLE, requestCartId(request));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Erro inesperado", ex);
		return respond(CheckoutErrorCode.INTERNAL_ERROR, requestCartId(request));
	}

	private static UUID requestCartId(WebRequest request) {
		return request.getAttribute(CART_ID_ATTRIBUTE, WebRequest.SCOPE_REQUEST) instanceof UUID cartId ? cartId
				: null;
	}

	/**
	 * Ponto unico das rejeicoes do Spring MVC (400, 404, 405, 406, 415 e demais): mantem o status
	 * original e os cabecalhos do framework (como {@code Allow}), e troca o corpo pelo objeto de erro.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		CheckoutErrorCode code;
		if (statusCode.is5xxServerError()) {
			log.error("Falha interna do framework: status={}", statusCode.value(), ex);
			code = CheckoutErrorCode.INTERNAL_ERROR;
		}
		else {
			log.warn("Requisicao rejeitada: status={}, tipo={}", statusCode.value(), ex.getClass().getSimpleName());
			code = CheckoutErrorCode.INVALID_REQUEST;
		}
		Object payload = errorCatalog.toResponse(code, null);
		return ResponseEntity.status(statusCode).headers(headers).contentType(MediaType.APPLICATION_JSON)
				.body(payload);
	}

	private ResponseEntity<ErrorResponse> respond(CheckoutErrorCode code, UUID cartId) {
		return ResponseEntity.status(errorCatalog.get(code).httpStatus()).contentType(MediaType.APPLICATION_JSON)
				.body(errorCatalog.toResponse(code, cartId));
	}

}
