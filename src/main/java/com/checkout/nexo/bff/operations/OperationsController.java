package com.checkout.nexo.bff.operations;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.checkout.nexo.bff.error.GlobalExceptionHandler;
import com.checkout.nexo.bff.operations.dto.CheckoutRequest;
import com.checkout.nexo.bff.operations.dto.CheckoutResponse;
import com.checkout.nexo.bff.operations.dto.PreCheckoutRequest;
import com.checkout.nexo.bff.operations.dto.PreCheckoutResponse;
import com.checkout.nexo.checkout.application.CheckoutOutcome;
import com.checkout.nexo.checkout.application.CheckoutService;
import com.checkout.nexo.checkout.application.PreCheckoutService;
import com.checkout.nexo.checkout.domain.CheckoutErrorCode;
import com.checkout.nexo.checkout.domain.CheckoutException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Operacoes do BFF para o frontend de checkout. So traduz HTTP para os casos de uso e de volta; as
 * falhas viram o objeto de erro unico no {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/operations")
public class OperationsController {

	private static final String PRE_CHECKOUT_EXAMPLE = """
			{"cartId":"7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33","products":[{"slug":"premium","name":"Plano Premium",\
			"description":"Assinatura mensal com catálogo completo","price":39.90,"currency":"BRL"}],\
			"paymentMethods":[{"name":"Cartão de Crédito"},{"name":"Cartão de Débito"},{"name":"Pix"}]}""";

	private static final String CARD_CHECKOUT_EXAMPLE = """
			{"cartId":"7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33","status":"APROVADO","paidAt":"2026-10-14T16:00:00Z",\
			"payment":{"method":"Cartão de Crédito","lastDigits":"4242","amount":39.90,"currency":"BRL"},\
			"products":[{"slug":"premium","name":"Plano Premium","price":39.90}]}""";

	private static final String PIX_CHECKOUT_EXAMPLE = """
			{"cartId":"7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33","status":"APROVADO","paidAt":"2026-10-14T16:00:00Z",\
			"payment":{"method":"Pix","pixCode":"PIX-SIMULADO-7f3c2a10-5b9e-4d1c-8a46-0e2f9b7d1c33","amount":39.90,\
			"currency":"BRL"},"products":[{"slug":"premium","name":"Plano Premium","price":39.90}]}""";

	private final PreCheckoutService preCheckoutService;

	private final CheckoutService checkoutService;

	public OperationsController(PreCheckoutService preCheckoutService, CheckoutService checkoutService) {
		this.preCheckoutService = preCheckoutService;
		this.checkoutService = checkoutService;
	}

	@Operation(summary = "Monta o carrinho a partir dos slugs escolhidos",
			description = "Consulta produtos e meios de pagamento e persiste o carrinho (status OPEN).")
	@ApiResponse(responseCode = "201", description = "Carrinho criado",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = PreCheckoutResponse.class),
					examples = @ExampleObject(name = "premium", value = PRE_CHECKOUT_EXAMPLE)))
	@PostMapping(path = "/pre-checkout", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PreCheckoutResponse> preCheckout(@Valid @RequestBody PreCheckoutRequest request) {
		PreCheckoutResponse response = OperationsMapper
				.toPreCheckoutResponse(preCheckoutService.preCheckout(OperationsMapper.toSlugs(request)));
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	/**
	 * Registra o {@code cartId} (ja validado como UUID) na requisicao antes de chamar o servico, para
	 * que o handler de erros o devolva tambem em 503 e 500 (ERR-03). A recusa volta do servico como
	 * resultado, e so aqui vira excecao, depois do commit da tentativa {@code DECLINED}.
	 */
	@Operation(summary = "Conclui o pagamento de um carrinho",
			description = "Cobrança simulada: cartão cujo número termina em 0002 é recusado; Pix dispensa o cartão.")
	@ApiResponse(responseCode = "200", description = "Pagamento aprovado",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = CheckoutResponse.class),
					examples = { @ExampleObject(name = "card", summary = "Cartao", value = CARD_CHECKOUT_EXAMPLE),
							@ExampleObject(name = "pix", summary = "Pix", value = PIX_CHECKOUT_EXAMPLE) }))
	@PostMapping(path = "/checkout", consumes = MediaType.APPLICATION_JSON_VALUE)
	public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request, HttpServletRequest httpRequest) {
		httpRequest.setAttribute(GlobalExceptionHandler.CART_ID_ATTRIBUTE, OperationsMapper.toCartId(request));
		CheckoutOutcome outcome = checkoutService.checkout(OperationsMapper.toCommand(request));
		return switch (outcome) {
			case CheckoutOutcome.Approved approved -> OperationsMapper.toCheckoutResponse(approved.checkout());
			case CheckoutOutcome.Declined declined ->
				throw new CheckoutException(CheckoutErrorCode.PAYMENT_DECLINED, declined.cartId());
		};
	}

}
