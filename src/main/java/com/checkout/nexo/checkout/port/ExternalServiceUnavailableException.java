package com.checkout.nexo.checkout.port;

/**
 * Servico externo (produtos ou meios de pagamento) indisponivel ou com resposta fora do
 * contrato (EXT-04). A mensagem identifica o servico; nunca carrega dado de entrada.
 */
public class ExternalServiceUnavailableException extends RuntimeException {

	public ExternalServiceUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
