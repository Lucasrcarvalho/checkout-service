package com.checkout.nexo.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import com.checkout.nexo.AbstractIT;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Atomicidade do checkout aprovado (CHK-03): a tentativa {@code APPROVED} e o carrinho
 * {@code APPROVED} saem na mesma transacao. A falha e real, no banco: uma constraint de teste faz o
 * {@code UPDATE} que aprova o carrinho falhar no commit, depois de a tentativa ja ter sido inserida
 * (o id e {@code bigserial}, entao o {@code INSERT} vai ao banco na hora). O estado final e lido do
 * banco por SQL.
 *
 * <p>A constraint e do teste e SEMPRE removida (no {@code finally} do teste e no {@code @AfterEach}):
 * o banco e compartilhado com os demais {@code *IT}, que precisam aprovar carrinhos.
 */
class CheckoutAtomicityIT extends AbstractIT {

	private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	private static final String VALID_CARD = "{\"holderName\":\"Ana Souza\",\"number\":\"4111111111114242\","
			+ "\"expiry\":\"12/99\",\"cvv\":\"123\"}";

	private static final String NEVER_APPROVE_CONSTRAINT = "ck_test_never_approve";

	private record HttpResult(int status, String body) {
	}

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper jsonMapper;

	private final RestClient http = RestClient.builder().baseUrl(baseUrl())
			.requestFactory(new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();

	@AfterEach
	void dropTheTestConstraint() {
		dropNeverApproveConstraint();
	}

	private void addNeverApproveConstraint() {
		jdbc.execute("alter table cart add constraint " + NEVER_APPROVE_CONSTRAINT
				+ " check (status <> 'APPROVED') not valid");
	}

	private void dropNeverApproveConstraint() {
		jdbc.execute("alter table cart drop constraint if exists " + NEVER_APPROVE_CONSTRAINT);
	}

	private HttpResult post(String path, String json) {
		return http.post().uri(path).contentType(JSON_UTF8).body(json).exchange(
				(request, response) -> new HttpResult(response.getStatusCode().value(),
						new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
	}

	private Map<String, Object> asMap(String json) {
		return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {
		});
	}

	private UUID createCart() {
		HttpResult result = post("/operations/pre-checkout", "{\"products\":[{\"slug\":\"premium\"}]}");
		assertThat(result.status()).isEqualTo(201);
		return UUID.fromString((String) asMap(result.body()).get("cartId"));
	}

	private HttpResult checkout(UUID cartId) {
		return post("/operations/checkout", "{\"cartId\":\"" + cartId
				+ "\",\"paymentMethod\":{\"name\":\"Cartão de Crédito\",\"card\":" + VALID_CARD + "}}");
	}

	private Map<String, Object> cartRow(UUID cartId) {
		return jdbc.queryForMap("select status, paid_at from cart where id = ?", cartId);
	}

	/** Todas as tentativas do carrinho, de qualquer status: vazio significa nenhuma linha. */
	private List<String> attemptStatuses(UUID cartId) {
		return jdbc.queryForList("select status from payment_attempt where cart_id = ? order by id", String.class,
				cartId);
	}

	@Test
	void aFailureWhenApprovingTheCartRollsBackTheAttemptTooAndTheSameCheckoutIsApprovedOnceTheFailureIsGone() {
		UUID cartId = createCart();

		HttpResult failed;
		try {
			addNeverApproveConstraint();
			failed = checkout(cartId);
		}
		finally {
			dropNeverApproveConstraint();
		}

		assertThat(failed.status()).isEqualTo(500);
		assertThat(failed.body()).isEqualTo("{\"displayType\":\"BLOCK\",\"errorCode\":\"INTERNAL_ERROR\","
				+ "\"cartId\":\"" + cartId + "\",\"frontendError\":{\"code\":\"S-1002\","
				+ "\"title\":\"Algo deu errado\","
				+ "\"description\":\"Ocorreu um erro inesperado. Tente novamente.\","
				+ "\"primaryButton\":{\"label\":\"Tentar novamente\",\"url\":\"RETRY\"},\"secondaryButton\":null}}");
		Map<String, Object> cartAfterFailure = cartRow(cartId);
		assertThat(cartAfterFailure.get("status")).isEqualTo("OPEN");
		assertThat(cartAfterFailure.get("paid_at")).isNull();
		assertThat(attemptStatuses(cartId)).isEmpty();

		HttpResult retried = checkout(cartId);

		assertThat(retried.status()).isEqualTo(200);
		assertThat(asMap(retried.body())).containsEntry("cartId", cartId.toString()).containsEntry("status",
				"APROVADO");
		Map<String, Object> cartAfterRetry = cartRow(cartId);
		assertThat(cartAfterRetry.get("status")).isEqualTo("APPROVED");
		assertThat(cartAfterRetry.get("paid_at")).isNotNull();
		assertThat(attemptStatuses(cartId)).containsExactly("APPROVED");
	}

}
