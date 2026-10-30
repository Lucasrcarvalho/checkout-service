package com.checkout.nexo.bff.operations;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.checkout.nexo.bff.operations.dto.CardRequest;
import com.checkout.nexo.bff.operations.dto.CheckoutProductView;
import com.checkout.nexo.bff.operations.dto.CheckoutRequest;
import com.checkout.nexo.bff.operations.dto.CheckoutResponse;
import com.checkout.nexo.bff.operations.dto.PaymentMethodView;
import com.checkout.nexo.bff.operations.dto.PaymentView;
import com.checkout.nexo.bff.operations.dto.PreCheckoutRequest;
import com.checkout.nexo.bff.operations.dto.PreCheckoutResponse;
import com.checkout.nexo.bff.operations.dto.ProductRef;
import com.checkout.nexo.bff.operations.dto.ProductView;
import com.checkout.nexo.checkout.application.ApprovedCheckout;
import com.checkout.nexo.checkout.application.CheckoutCommand;
import com.checkout.nexo.checkout.application.PreCheckoutResult;
import com.checkout.nexo.checkout.domain.CardData;

/**
 * Converte DTOs HTTP em entradas dos casos de uso e resultados dos casos de uso em DTOs HTTP.
 */
final class OperationsMapper {

	private static final String APPROVED_STATUS = "APROVADO";

	private OperationsMapper() {
	}

	static List<String> toSlugs(PreCheckoutRequest request) {
		return request.products().stream().map(ProductRef::slug).toList();
	}

	static PreCheckoutResponse toPreCheckoutResponse(PreCheckoutResult result) {
		List<ProductView> products = result.items().stream().map(item -> new ProductView(item.slug(), item.name(),
				item.description(), item.unitPrice(), item.currency())).toList();
		List<PaymentMethodView> methods = result.paymentMethodNames().stream().map(PaymentMethodView::new).toList();
		return new PreCheckoutResponse(result.cartId(), products, methods);
	}

	/** So chamar depois da validacao: o formato canonico ja foi garantido pelo Bean Validation. */
	static UUID toCartId(CheckoutRequest request) {
		return UUID.fromString(request.cartId());
	}

	static CheckoutCommand toCommand(CheckoutRequest request) {
		CardRequest card = request.paymentMethod().card();
		CardData cardData = card == null ? null
				: new CardData(card.holderName(), card.number(), card.expiry(), card.cvv());
		return new CheckoutCommand(toCartId(request), request.paymentMethod().name(), cardData);
	}

	static CheckoutResponse toCheckoutResponse(ApprovedCheckout approved) {
		PaymentView payment = new PaymentView(approved.methodName(), approved.lastDigits(), approved.pixCode(),
				approved.amount(), approved.currency());
		List<CheckoutProductView> products = approved.items().stream()
				.map(item -> new CheckoutProductView(item.slug(), item.name(), item.unitPrice())).toList();
		String paidAt = DateTimeFormatter.ISO_INSTANT.format(approved.paidAt().truncatedTo(ChronoUnit.SECONDS));
		return new CheckoutResponse(approved.cartId(), APPROVED_STATUS, paidAt, payment, products);
	}

}
