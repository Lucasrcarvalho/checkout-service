package com.checkout.nexo.bff.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springdoc.core.customizers.OpenApiCustomizer;

import com.checkout.nexo.bff.error.ErrorCatalog;
import com.checkout.nexo.bff.error.ErrorResponse;
import com.checkout.nexo.checkout.domain.CheckoutErrorCode;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acrescenta ao grupo {@code bff} o schema {@code ErrorResponse} (com {@code cartId} e
 * {@code secondaryButton} anulaveis, sempre presentes) e um exemplo por {@code errorCode}, e liga os
 * exemplos as respostas de erro de cada operacao (API-02). Os exemplos saem do {@link ErrorCatalog}
 * serializado pelo mesmo {@link JsonMapper} que responde as requisicoes: nenhum texto de erro e
 * repetido aqui. O schema vem dos proprios records de erro, lidos pelo swagger-core, para que os
 * nomes das propriedades nao dependam de transcricao manual.
 *
 * <p>O springdoc 3.2 gera OpenAPI 3.1, em que "anulavel" e o tipo {@code null} na lista de tipos
 * (propriedade simples) ou um {@code oneOf} com {@code null} (referencia a outro schema).
 */
public class ErrorExamplesCustomizer implements OpenApiCustomizer {

	private static final String ERROR_RESPONSE = "ErrorResponse";

	private static final String JSON = "application/json";

	/** Erros que cada operacao pode devolver; o HTTP de cada um vem do catalogo. */
	private static final Map<String, List<CheckoutErrorCode>> ERRORS_BY_PATH = Map.of(
			"/operations/pre-checkout",
			List.of(CheckoutErrorCode.INVALID_REQUEST, CheckoutErrorCode.PRODUCT_NOT_FOUND,
					CheckoutErrorCode.SERVICE_UNAVAILABLE, CheckoutErrorCode.INTERNAL_ERROR),
			"/operations/checkout",
			List.of(CheckoutErrorCode.INVALID_REQUEST, CheckoutErrorCode.CART_NOT_FOUND,
					CheckoutErrorCode.CART_ALREADY_PAID, CheckoutErrorCode.PAYMENT_METHOD_NOT_ALLOWED,
					CheckoutErrorCode.INVALID_PAYMENT_DATA, CheckoutErrorCode.PAYMENT_DECLINED,
					CheckoutErrorCode.SERVICE_UNAVAILABLE, CheckoutErrorCode.INTERNAL_ERROR));

	private final ErrorCatalog catalog;

	private final JsonMapper jsonMapper;

	public ErrorExamplesCustomizer(ErrorCatalog catalog, JsonMapper jsonMapper) {
		this.catalog = catalog;
		this.jsonMapper = jsonMapper;
	}

	@Override
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void customise(OpenAPI openApi) {
		Components components = openApi.getComponents();
		if (components == null) {
			components = new Components();
			openApi.setComponents(components);
		}
		Map<String, Schema> schemas = ModelConverters.getInstance(true).readAll(ErrorResponse.class);
		markNullable(schemas);
		schemas.forEach(components::addSchemas);
		for (CheckoutErrorCode code : CheckoutErrorCode.values()) {
			components.addExamples(code.name(), new Example().value(exampleOf(code)));
		}
		if (openApi.getPaths() != null) {
			ERRORS_BY_PATH.forEach((path, codes) -> {
				if (openApi.getPaths().get(path) != null) {
					openApi.getPaths().get(path).readOperations().forEach(operation -> addErrorResponses(operation, codes));
				}
			});
		}
	}

	private Map<String, Object> exampleOf(CheckoutErrorCode code) {
		return jsonMapper.convertValue(catalog.toResponse(code, null), new TypeReference<LinkedHashMap<String, Object>>() {
		});
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void markNullable(Map<String, Schema> schemas) {
		Schema<?> cartId = (Schema<?>) schemas.get(ERROR_RESPONSE).getProperties().get("cartId");
		cartId.addType("null");
		Map<String, Schema> frontendErrorProperties = schemas.get("FrontendError").getProperties();
		Schema<?> nullType = new Schema<>();
		nullType.addType("null");
		Schema<?> nullableSecondaryButton = new Schema<>();
		nullableSecondaryButton.addOneOfItem((Schema<?>) frontendErrorProperties.get("secondaryButton"));
		nullableSecondaryButton.addOneOfItem(nullType);
		frontendErrorProperties.put("secondaryButton", nullableSecondaryButton);
	}

	private void addErrorResponses(Operation operation, List<CheckoutErrorCode> codes) {
		if (operation.getResponses() == null) {
			operation.setResponses(new ApiResponses());
		}
		Map<Integer, List<CheckoutErrorCode>> codesByStatus = codes.stream().collect(
				Collectors.groupingBy(code -> catalog.get(code).httpStatus().value(), TreeMap::new, Collectors.toList()));
		codesByStatus.forEach((status, group) -> {
			MediaType mediaType = new MediaType().schema(new Schema<>().$ref(ERROR_RESPONSE));
			group.forEach(code -> mediaType.addExamples(code.name(), new Example().$ref(code.name())));
			String description = group.stream().map(code -> catalog.get(code).frontendError().title())
					.collect(Collectors.joining(" / "));
			operation.getResponses().addApiResponse(String.valueOf(status),
					new ApiResponse().description(description).content(new Content().addMediaType(JSON, mediaType)));
		});
	}

}
