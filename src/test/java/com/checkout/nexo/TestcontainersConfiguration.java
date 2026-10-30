package com.checkout.nexo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers de teste com versao fixa (TEST-04). A classe externa e a uniao;
 * {@link Postgres} e {@link Redis} podem ser importados separadamente.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({ TestcontainersConfiguration.Postgres.class, TestcontainersConfiguration.Redis.class })
public class TestcontainersConfiguration {

	@TestConfiguration(proxyBeanMethods = false)
	public static class Postgres {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Redis {

		@Bean
		@ServiceConnection(name = "redis")
		GenericContainer<?> redisContainer() {
			return new GenericContainer<>(DockerImageName.parse("redis:8.8.4")).withExposedPorts(6379);
		}

	}

}
