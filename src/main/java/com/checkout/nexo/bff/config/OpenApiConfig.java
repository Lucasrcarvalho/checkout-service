package com.checkout.nexo.bff.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.checkout.nexo.bff.error.ErrorCatalog;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import tools.jackson.databind.json.JsonMapper;

/**
 * Documentacao OpenAPI em dois grupos (API-01): {@code bff}, o contrato que o frontend consome, e
 * {@code mock-services}, os servicos externos simulados. Os exemplos de erro ficam so no grupo
 * {@code bff} e saem do {@link ErrorCatalog} (API-02).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	@Bean
	OpenAPI nexoOpenApi() {
		return new OpenAPI().info(new Info().title("Nexo - BFF de checkout").version("v1").description(
				"BFF de checkout de assinatura: pre-checkout (monta o carrinho) e checkout (cobranca simulada). "
						+ "Toda falha responde o mesmo objeto de erro (ErrorResponse)."));
	}

	@Bean
	GroupedOpenApi bffApi(ErrorCatalog errorCatalog, JsonMapper jsonMapper) {
		return GroupedOpenApi.builder().group("bff").displayName("BFF de checkout").pathsToMatch("/operations/**")
				.addOpenApiCustomizer(new ErrorExamplesCustomizer(errorCatalog, jsonMapper)).build();
	}

	@Bean
	GroupedOpenApi mockServicesApi() {
		return GroupedOpenApi.builder().group("mock-services").displayName("Servicos externos simulados")
				.pathsToMatch("/mock-services/**").build();
	}

}
