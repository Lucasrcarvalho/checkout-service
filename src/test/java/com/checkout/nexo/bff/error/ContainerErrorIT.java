package com.checkout.nexo.bff.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.checkout.nexo.AbstractIT;

/**
 * Falhas do container que chegam ao {@code /error} (ERR-01, ERR-04): o corpo e o objeto de erro
 * unico, nunca o corpo padrao do Boot nem a pagina HTML. {@code /WEB-INF/**} e {@code /META-INF/**}
 * sao recusados pelo Tomcat com {@code sendError(404)} antes do Spring MVC, o que gera um despacho
 * de erro de verdade para {@code /error}.
 */
class ContainerErrorIT extends AbstractIT {

	private static final String INVALID_REQUEST_JSON = "{\"displayType\":\"BLOCK\",\"errorCode\":\"INVALID_REQUEST\","
			+ "\"cartId\":null,\"frontendError\":{\"code\":\"R-1001\",\"title\":\"Não foi possível processar o pedido\","
			+ "\"description\":\"Os dados enviados estão incompletos ou em formato inválido. Tente novamente.\","
			+ "\"primaryButton\":{\"label\":\"Tentar novamente\",\"url\":\"RETRY\"},\"secondaryButton\":null}}";

	private record HttpResult(int status, String contentType, String body) {
	}

	private final RestClient http = RestClient.builder().baseUrl(baseUrl())
			.requestFactory(new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();

	private HttpResult get(String path, String accept) {
		return http.get().uri(path).header(HttpHeaders.ACCEPT, accept).exchange(
				(request, response) -> new HttpResult(response.getStatusCode().value(),
						response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
						new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
	}

	@Test
	void aRequestTheContainerRefusesWithSendErrorGetsTheInvalidRequestObjectWithTheOriginalStatus() {
		HttpResult result = get("/WEB-INF/web.xml", "application/json");

		assertThat(result.status()).isEqualTo(404);
		assertThat(result.contentType()).startsWith("application/json");
		assertThat(result.body()).isEqualTo(INVALID_REQUEST_JSON);
	}

	@Test
	void aBrowserAskingForHtmlStillGetsTheErrorObjectNotAnErrorPage() {
		HttpResult result = get("/META-INF/MANIFEST.MF", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8");

		assertThat(result.status()).isEqualTo(404);
		assertThat(result.contentType()).startsWith("application/json");
		assertThat(result.body()).isEqualTo(INVALID_REQUEST_JSON);
	}

	@Test
	void openingTheErrorPathDirectlyIsAnUnknownRouteNotAServerFailure() {
		HttpResult result = get("/error", "application/json");

		assertThat(result.status()).isEqualTo(404);
		assertThat(result.contentType()).startsWith("application/json");
		assertThat(result.body()).isEqualTo(INVALID_REQUEST_JSON);
	}

}
