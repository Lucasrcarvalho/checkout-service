package com.checkout.nexo.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import com.checkout.nexo.AbstractIT;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pagamento unico sob concorrencia (DUP-01) e o timeout de lock mapeado para 503 (risco de lock do
 * design). Cada cenario cria o proprio carrinho; o estado final e lido do banco por SQL.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConcurrencyIT extends AbstractIT {

	private static final int ROUNDS = 20;

	private static final int PRE_CHECKOUT_ROUNDS = 10;

	private static final String PREMIUM_PRE_CHECKOUT = "{\"products\":[{\"slug\":\"premium\"}]}";

	private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");

	private static final String VALID_CARD = "{\"holderName\":\"Ana Souza\",\"number\":\"4111111111114242\","
			+ "\"expiry\":\"12/99\",\"cvv\":\"123\"}";

	private record HttpResult(int status, String body) {
	}

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JsonMapper jsonMapper;

	private final RestClient http = RestClient.builder().baseUrl(baseUrl())
			.requestFactory(new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();

	private HttpResult post(String path, String json) {
		return http.post().uri(path).contentType(JSON_UTF8).body(json).exchange(
				(request, response) -> new HttpResult(response.getStatusCode().value(),
						new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
	}

	private UUID createCart() {
		HttpResult result = post("/operations/pre-checkout", "{\"products\":[{\"slug\":\"premium\"}]}");
		assertThat(result.status()).isEqualTo(201);
		return UUID.fromString((String) jsonMapper.readValue(result.body(), new TypeReference<Map<String, Object>>() {
		}).get("cartId"));
	}

	private HttpResult checkout(UUID cartId) {
		return post("/operations/checkout", "{\"cartId\":\"" + cartId
				+ "\",\"paymentMethod\":{\"name\":\"Cartão de Crédito\",\"card\":" + VALID_CARD + "}}");
	}

	private HttpResult preCheckout() {
		return post("/operations/pre-checkout", PREMIUM_PRE_CHECKOUT);
	}

	private UUID cartIdOf(HttpResult preCheckoutResult) {
		return UUID.fromString((String) jsonMapper
				.readValue(preCheckoutResult.body(), new TypeReference<Map<String, Object>>() {
				}).get("cartId"));
	}

	private String cartStatus(UUID cartId) {
		return jdbc.queryForObject("select status from cart where id = ?", String.class, cartId);
	}

	private List<String> attemptStatuses(UUID cartId) {
		return jdbc.queryForList("select status from payment_attempt where cart_id = ? order by id", String.class,
				cartId);
	}

	// ---- DUP-01: duas requisicoes simultaneas ------------------------------------------------

	@Test
	void twentyRoundsOfTwoSimultaneousCheckoutsEndWithOne200OneOne409AndOneApprovedAttemptPerCart() throws Exception {
		List<String> anomalies = new ArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 1; round <= ROUNDS; round++) {
				UUID cartId = createCart();
				CyclicBarrier barrier = new CyclicBarrier(2);
				Callable<HttpResult> simultaneousCheckout = () -> {
					barrier.await(10, TimeUnit.SECONDS);
					return checkout(cartId);
				};
				List<Future<HttpResult>> futures = pool.invokeAll(List.of(simultaneousCheckout, simultaneousCheckout));
				List<Integer> statuses = new ArrayList<>();
				for (Future<HttpResult> future : futures) {
					statuses.add(future.get().status());
				}
				statuses.sort(Integer::compare);

				if (!statuses.equals(List.of(200, 409))) {
					anomalies.add("rodada " + round + ": status " + statuses);
				}
				if (!attemptStatuses(cartId).equals(List.of("APPROVED"))) {
					anomalies.add("rodada " + round + ": tentativas " + attemptStatuses(cartId));
				}
				if (!"APPROVED".equals(cartStatus(cartId))) {
					anomalies.add("rodada " + round + ": carrinho " + cartStatus(cartId));
				}
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(anomalies).isEmpty();
	}

	// ---- PRE-06: dois pre-checkouts simultaneos ---------------------------------------------

	@Test
	void tenRoundsOfTwoSimultaneousPreCheckoutsEndWithTwo201DistinctCartIdsAndTwoIndependentOpenCarts()
			throws Exception {
		List<String> anomalies = new ArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 1; round <= PRE_CHECKOUT_ROUNDS; round++) {
				CyclicBarrier barrier = new CyclicBarrier(2);
				Callable<HttpResult> simultaneousPreCheckout = () -> {
					barrier.await(10, TimeUnit.SECONDS);
					return preCheckout();
				};
				List<Future<HttpResult>> futures = pool
						.invokeAll(List.of(simultaneousPreCheckout, simultaneousPreCheckout));
				HttpResult first = futures.get(0).get();
				HttpResult second = futures.get(1).get();

				if (first.status() != 201 || second.status() != 201) {
					anomalies.add("rodada " + round + ": status " + List.of(first.status(), second.status()));
					continue;
				}
				UUID firstCartId = cartIdOf(first);
				UUID secondCartId = cartIdOf(second);
				if (firstCartId.equals(secondCartId)) {
					anomalies.add("rodada " + round + ": cartId repetido " + firstCartId);
					continue;
				}

				List<String> cartStatuses = jdbc.queryForList("select status from cart where id in (?, ?)",
						String.class, firstCartId, secondCartId);
				if (!cartStatuses.equals(List.of("OPEN", "OPEN"))) {
					anomalies.add("rodada " + round + ": carrinhos " + cartStatuses);
				}
				for (UUID cartId : List.of(firstCartId, secondCartId)) {
					List<String> slugs = jdbc.queryForList("select slug from cart_item where cart_id = ?",
							String.class, cartId);
					if (!slugs.equals(List.of("premium"))) {
						anomalies.add("rodada " + round + ": itens de " + cartId + " " + slugs);
					}
					List<String> methods = jdbc.queryForList(
							"select name from cart_payment_method where cart_id = ? order by code", String.class,
							cartId);
					if (!methods.equals(List.of("Cartão de Crédito", "Cartão de Débito", "Pix"))) {
						anomalies.add("rodada " + round + ": meios de " + cartId + " " + methods);
					}
				}
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(anomalies).isEmpty();
	}

	// ---- Timeout de lock => 503 -------------------------------------------------------------

	@Test
	void theLockTimeoutOfThreeSecondsComesFromTheHikariInitSql() {
		assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("3s");
	}

	@Test
	void aCheckoutBlockedByAnExternalRowLockIs503WithTheRequestCartIdWithinTenSecondsAndTheNextCheckoutIsApproved(
			CapturedOutput output) throws SQLException {
		UUID cartId = createCart();
		HttpResult blocked;
		Duration elapsed;
		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			try (PreparedStatement lock = holder.prepareStatement("select id from cart where id = ? for update")) {
				lock.setObject(1, cartId);
				lock.executeQuery();
			}
			long start = System.nanoTime();
			blocked = checkout(cartId);
			elapsed = Duration.ofNanos(System.nanoTime() - start);
			holder.rollback();
		}

		assertThat(blocked.status()).isEqualTo(503);
		assertThat(blocked.body()).isEqualTo("{\"displayType\":\"BLOCK\",\"errorCode\":\"SERVICE_UNAVAILABLE\","
				+ "\"cartId\":\"" + cartId + "\",\"frontendError\":{\"code\":\"S-1001\","
				+ "\"title\":\"Serviço temporariamente indisponível\","
				+ "\"description\":\"Não conseguimos concluir agora. Tente novamente em instantes.\","
				+ "\"primaryButton\":{\"label\":\"Tentar novamente\",\"url\":\"RETRY\"},\"secondaryButton\":null}}");
		assertThat(elapsed).isLessThan(Duration.ofSeconds(10)).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
		assertThat(output.getAll()).contains("Dependencia indisponivel").doesNotContain("Erro inesperado");
		assertThat(cartStatus(cartId)).isEqualTo("OPEN");
		assertThat(attemptStatuses(cartId)).isEmpty();

		HttpResult afterRelease = checkout(cartId);

		assertThat(afterRelease.status()).isEqualTo(200);
		assertThat(cartStatus(cartId)).isEqualTo("APPROVED");
		assertThat(attemptStatuses(cartId)).containsExactly("APPROVED");
	}

}
