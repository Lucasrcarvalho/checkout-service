package com.checkout.nexo.bff.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import com.checkout.nexo.checkout.domain.CheckoutErrorCode;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Destino do despacho de erro do container (ERR-01): no lugar do {@code BasicErrorController} do
 * Boot, devolve o objeto de erro unico para o que o container recusa antes do Spring MVC
 * ({@code sendError}, como {@code /WEB-INF/**}) e para o acesso direto a {@code /error}. Mantem o
 * status original, como {@link GlobalExceptionHandler} faz com as rejeicoes do MVC. Nao alcanca o
 * que o Tomcat rejeita ao ler a requisicao, como URI malformada: esse nunca chega a um despacho de erro.
 *
 * <p>Por AD-008 nao devolve nem registra a mensagem, o caminho ou os atributos do erro, so o status.
 */
@Controller
@RequestMapping("${server.error.path:${error.path:/error}}")
public class ContainerErrorController implements ErrorController {

	private static final Logger log = LoggerFactory.getLogger(ContainerErrorController.class);

	private final ErrorCatalog errorCatalog;

	public ContainerErrorController(ErrorCatalog errorCatalog) {
		this.errorCatalog = errorCatalog;
	}

	@RequestMapping
	ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
		int status = statusOf(request);
		CheckoutErrorCode code;
		if (status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
			log.error("Falha interna do container: status={}", status,
					request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable cause ? cause : null);
			code = CheckoutErrorCode.INTERNAL_ERROR;
		}
		else {
			log.warn("Requisicao rejeitada pelo container: status={}", status);
			code = CheckoutErrorCode.INVALID_REQUEST;
		}
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
				.body(errorCatalog.toResponse(code, null));
	}

	/**
	 * Sem o atributo do container o acesso e direto, a uma rota que nao existe (404). Um valor que nao
	 * e um status de erro (400 a 599) nao devia ocorrer e vira 500.
	 */
	private static int statusOf(HttpServletRequest request) {
		Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		if (attribute == null) {
			return HttpStatus.NOT_FOUND.value();
		}
		return attribute instanceof Integer status && status >= 400 && status <= 599 ? status
				: HttpStatus.INTERNAL_SERVER_ERROR.value();
	}

}
