package com.checkout.nexo.integration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

class RestClientConfigTest {

	private static final String PRODUCTS_PATH = "/mock-services/products";

	private static final String PAYMENT_METHODS_PATH = "/mock-services/payment-methods";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
			.withUserConfiguration(RestClientConfig.class);

	private ApplicationContextRunner runnerFor(String productsBaseUrl, String paymentMethodsBaseUrl,
			String readTimeout) {
		return runner.withPropertyValues("app.services.products.base-url=" + productsBaseUrl,
				"app.services.payment-methods.base-url=" + paymentMethodsBaseUrl, "app.services.connect-timeout=1s",
				"app.services.read-timeout=" + readTimeout);
	}

	private static String getBody(RestClient restClient, String path) {
		return restClient.get().uri(path).retrieve().body(String.class);
	}

	private static int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}

	// ---- Defaults do application.yaml (EXT-03, EXT-04) ---------------------------------------

	@Test
	void theApplicationYamlDefaultsAreOneSecondToConnectTwoToReadAndTheLocalServerPort() {
		runner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
			ServicesProperties properties = context.getBean(ServicesProperties.class);

			assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
			assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(2));
			assertThat(properties.products().baseUrl()).isEqualTo("http://localhost:8080");
			assertThat(properties.paymentMethods().baseUrl()).isEqualTo("http://localhost:8080");
		});
	}

	@Test
	void theDefaultBaseUrlFollowsTheServerPort() {
		runner.withInitializer(new ConfigDataApplicationContextInitializer()).withPropertyValues("server.port=9090")
				.run(context -> {
					ServicesProperties properties = context.getBean(ServicesProperties.class);

					assertThat(properties.products().baseUrl()).isEqualTo("http://localhost:9090");
					assertThat(properties.paymentMethods().baseUrl()).isEqualTo("http://localhost:9090");
				});
	}

	@Test
	void theHttpClientIsHttp11AndUsesTheConfiguredConnectTimeout() {
		HttpClient httpClient = RestClientConfig.httpClient(Duration.ofMillis(300));

		assertThat(httpClient.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
		assertThat(httpClient.connectTimeout()).contains(Duration.ofMillis(300));
	}

	// ---- base-url configuravel (EXT-03) ------------------------------------------------------

	@Test
	void eachRestClientCallsItsOwnConfiguredBaseUrl() throws Exception {
		try (StubServer productsServer = new StubServer("[\"products\"]", 0);
				StubServer methodsServer = new StubServer("[\"methods\"]", 0)) {
			runnerFor(productsServer.baseUrl(), methodsServer.baseUrl(), "2s").run(context -> {
				RestClient products = context.getBean("productsRestClient", RestClient.class);
				RestClient methods = context.getBean("paymentMethodsRestClient", RestClient.class);

				assertThat(getBody(products, PRODUCTS_PATH)).isEqualTo("[\"products\"]");
				assertThat(getBody(methods, PAYMENT_METHODS_PATH)).isEqualTo("[\"methods\"]");
				assertThat(productsServer.requests).containsExactly("GET " + PRODUCTS_PATH);
				assertThat(methodsServer.requests).containsExactly("GET " + PAYMENT_METHODS_PATH);
			});
		}
	}

	@Test
	void changingTheBaseUrlRedirectsTheCallWithoutChangingCode() throws Exception {
		try (StubServer first = new StubServer("[\"first\"]", 0); StubServer second = new StubServer("[\"second\"]", 0)) {
			runnerFor(first.baseUrl(), first.baseUrl(), "2s").run(context -> assertThat(
					getBody(context.getBean("productsRestClient", RestClient.class), PRODUCTS_PATH))
					.isEqualTo("[\"first\"]"));
			runnerFor(second.baseUrl(), first.baseUrl(), "2s").run(context -> assertThat(
					getBody(context.getBean("productsRestClient", RestClient.class), PRODUCTS_PATH))
					.isEqualTo("[\"second\"]"));
		}
	}

	@Test
	void theRequestGoesOutAsPlainHttp11WithoutAnH2cUpgrade() throws Exception {
		try (StubServer server = new StubServer("[]", 0)) {
			runnerFor(server.baseUrl(), server.baseUrl(), "2s").run(context -> {
				getBody(context.getBean("productsRestClient", RestClient.class), PRODUCTS_PATH);

				assertThat(server.protocols).containsExactly("HTTP/1.1");
				assertThat(server.upgradeHeaders).containsExactly("none");
			});
		}
	}

	// ---- Timeout e porta fechada (EXT-04) ----------------------------------------------------

	@Test
	void aServerSlowerThanTheReadTimeoutFailsFastWithAResourceAccessException() throws Exception {
		try (StubServer slow = new StubServer("[]", 3000)) {
			runnerFor(slow.baseUrl(), slow.baseUrl(), "200ms").run(context -> {
				RestClient products = context.getBean("productsRestClient", RestClient.class);

				long start = System.nanoTime();
				assertThatThrownBy(() -> getBody(products, PRODUCTS_PATH)).isInstanceOf(ResourceAccessException.class);
				Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

				assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
			});
		}
	}

	@Test
	void aClosedPortFailsWithAConnectionException() throws Exception {
		String closed = "http://127.0.0.1:" + closedPort();

		runnerFor(closed, closed, "2s").run(context -> {
			RestClient products = context.getBean("productsRestClient", RestClient.class);

			assertThatThrownBy(() -> getBody(products, PRODUCTS_PATH)).isInstanceOf(ResourceAccessException.class)
					.hasCauseInstanceOf(ConnectException.class);
		});
	}

	/** Servidor HTTP do JDK, com atraso opcional, que anota o que recebeu. */
	private static final class StubServer implements AutoCloseable {

		private final HttpServer server;

		private final ExecutorService executor = Executors.newCachedThreadPool();

		final List<String> requests = new CopyOnWriteArrayList<>();

		final List<String> protocols = new CopyOnWriteArrayList<>();

		final List<String> upgradeHeaders = new CopyOnWriteArrayList<>();

		StubServer(String body, long delayMillis) throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
				protocols.add(exchange.getProtocol());
				upgradeHeaders.add(exchange.getRequestHeaders().containsKey("Upgrade") ? "h2c" : "none");
				try {
					Thread.sleep(delayMillis);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return;
				}
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, bytes.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(bytes);
				}
			});
			server.setExecutor(executor);
			server.start();
		}

		String baseUrl() {
			return "http://127.0.0.1:" + server.getAddress().getPort();
		}

		@Override
		public void close() {
			server.stop(0);
			executor.shutdownNow();
		}

	}

}
