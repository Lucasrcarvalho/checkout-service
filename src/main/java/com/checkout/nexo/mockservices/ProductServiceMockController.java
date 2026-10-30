package com.checkout.nexo.mockservices;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Servico de produtos simulado (EXT-02): o BFF o consome por HTTP, como faria com um servico
 * externo real. Ativo so no profile {@code mock-services}, ligado por padrao no
 * {@code application.yaml} (EXT-05). Nao conhece nenhuma classe de {@code integration}.
 */
@RestController
@Profile("mock-services")
public class ProductServiceMockController {

	private static final List<MockProduct> PRODUCTS = List.of(
			new MockProduct("basico", "Plano Básico", "Assinatura mensal com o essencial do catálogo",
					new BigDecimal("19.90"), "BRL"),
			new MockProduct("premium", "Plano Premium", "Assinatura mensal com catálogo completo",
					new BigDecimal("39.90"), "BRL"),
			new MockProduct("familia", "Plano Família", "Assinatura mensal para até 4 perfis",
					new BigDecimal("59.90"), "BRL"));

	@GetMapping("/mock-services/products")
	public List<MockProduct> products() {
		return PRODUCTS;
	}

	/** Contrato JSON do servico de produtos simulado. */
	public record MockProduct(String slug, String name, String description, BigDecimal price, String currency) {
	}

}
