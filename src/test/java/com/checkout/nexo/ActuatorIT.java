package com.checkout.nexo;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import com.github.dockerjava.api.DockerClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Actuator exposto so com {@code health} (RUN-05), agregado com banco e Redis (RUN-01), e o Flyway
 * aplicado na subida (RUN-04). O Redis pausado prova a mitigacao do design: o health agregado cai,
 * a liveness nao (o {@code HEALTHCHECK} do Docker usa a liveness). O Redis pausado e sempre
 * retomado no {@code finally}.
 */
class ActuatorIT extends AbstractIT {

	private record HttpResult(int status, String body) {
	}

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	@Qualifier("redisContainer")
	private GenericContainer<?> redisContainer;

	private final RestClient http = RestClient.builder().baseUrl(baseUrl())
			.requestFactory(new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();

	private HttpResult get(String path) {
		return http.get().uri(path).exchange((request, response) -> new HttpResult(response.getStatusCode().value(),
				new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
	}

	private String statusOf(String path) {
		return jsonMapper.readTree(get(path).body()).get("status").stringValue();
	}

	// ---- RUN-01, RUN-05: health agregado e probes ---------------------------------------------

	@Test
	void theAggregatedHealthIsUpWithTheDatabaseAndRedisComponents() {
		HttpResult health = get("/actuator/health");
		JsonNode body = jsonMapper.readTree(health.body());

		assertThat(health.status()).isEqualTo(200);
		assertThat(body.get("status").stringValue()).isEqualTo("UP");
		assertThat(body.get("components").get("db").get("status").stringValue()).isEqualTo("UP");
		assertThat(body.get("components").get("redis").get("status").stringValue()).isEqualTo("UP");
	}

	@Test
	void theLivenessProbeAnswers200Up() {
		HttpResult liveness = get("/actuator/health/liveness");

		assertThat(liveness.status()).isEqualTo(200);
		assertThat(jsonMapper.readTree(liveness.body()).get("status").stringValue()).isEqualTo("UP");
	}

	@ParameterizedTest
	@ValueSource(strings = { "/actuator/info", "/actuator/env" })
	void onlyHealthIsExposedSoOtherEndpointsAre404(String path) {
		assertThat(get(path).status()).isEqualTo(404);
	}

	// ---- Mitigacao do design: Redis fora derruba o agregado, nao a liveness -------------------

	@Test
	void withRedisPausedTheAggregatedHealthIsDownButTheLivenessStaysUpAndItRecoversAfterTheUnpause()
			throws InterruptedException {
		DockerClient docker = DockerClientFactory.instance().client();
		String redisId = redisContainer.getContainerId();

		docker.pauseContainerCmd(redisId).exec();
		try {
			assertThat(statusOf("/actuator/health")).isEqualTo("DOWN");
			assertThat(get("/actuator/health/liveness").status()).isEqualTo(200);
			assertThat(statusOf("/actuator/health/liveness")).isEqualTo("UP");
		}
		finally {
			docker.unpauseContainerCmd(redisId).exec();
		}

		Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
		String status = statusOf("/actuator/health");
		while (!"UP".equals(status) && Instant.now().isBefore(deadline)) {
			Thread.sleep(250);
			status = statusOf("/actuator/health");
		}
		assertThat(status).isEqualTo("UP");
	}

	// ---- RUN-04: Flyway -----------------------------------------------------------------------

	@Test
	void flywayAppliedTheV1MigrationSuccessfully() {
		Set<String> successfulVersions = new TreeSet<>(jdbc.queryForList(
				"select version from flyway_schema_history where success = true and version is not null", String.class));

		assertThat(successfulVersions).contains("1");
		assertThat(jdbc.queryForObject("select script from flyway_schema_history where version = '1'", String.class))
				.isEqualTo("V1__checkout.sql");
	}

}
