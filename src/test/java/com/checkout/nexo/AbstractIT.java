package com.checkout.nexo;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.checkout.nexo.integration.config.CacheConfig;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Base dos {@code *IT} ponta a ponta. O app sobe numa porta fixa (o BFF chama os mocks por HTTP no
 * proprio processo, entao precisa saber a propria porta), escolhida uma unica vez por JVM, e todas
 * as subclasses compartilham o mesmo contexto Spring: sem {@code @DirtiesContext}, sem
 * {@code @MockitoBean} nem propriedade por classe (cada contexto novo subiria PostgreSQL e Redis
 * de novo).
 *
 * <p>Como o contexto e o Redis sao compartilhados, cada teste comeca com o cache do catalogo vazio
 * e sem falha injetada nos mocks: assim a contagem de chamadas a {@code /mock-services/**} nao
 * depende de qual classe rodou antes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({ TestcontainersConfiguration.class, AbstractIT.MockServiceCalls.class })
public abstract class AbstractIT {

	private static final int PORT = TestSocketUtils.findAvailableTcpPort();

	@Autowired
	private CacheManager catalogCacheManager;

	@Autowired
	private MockServiceCallCounter mockServiceCalls;

	@DynamicPropertySource
	static void applicationPort(DynamicPropertyRegistry registry) {
		registry.add("server.port", () -> PORT);
		registry.add("app.services.products.base-url", AbstractIT::baseUrl);
		registry.add("app.services.payment-methods.base-url", AbstractIT::baseUrl);
	}

	/** Endereco da propria aplicacao, o mesmo que os adaptadores usam para chamar os mocks. */
	protected static String baseUrl() {
		return "http://localhost:" + PORT;
	}

	/** Usa {@code invalidate()}: com o Redis real (Spring Data Redis 4.1.2) {@code clear()} nao esvaziou o cache antes de retornar. */
	@BeforeEach
	protected void startFromAColdCatalogCacheWithoutInjectedFaults() {
		catalogCacheManager.getCache(CacheConfig.PRODUCTS_CACHE).invalidate();
		catalogCacheManager.getCache(CacheConfig.PAYMENT_METHODS_CACHE).invalidate();
		mockServiceCalls.clearFaults();
	}

	/**
	 * Conta as chamadas HTTP recebidas em {@code /mock-services/**} (prova de que o caminho e HTTP)
	 * e, quando pedido, faz um caminho responder com um status de erro (falha injetada no servico).
	 * A chamada e contada antes de falhar.
	 */
	public static class MockServiceCallCounter extends OncePerRequestFilter {

		private static final String MOCK_SERVICES_PREFIX = "/mock-services/";

		private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

		private final Map<String, Integer> faults = new ConcurrentHashMap<>();

		public int count(String path) {
			AtomicInteger counter = calls.get(path);
			return counter == null ? 0 : counter.get();
		}

		public void reset() {
			calls.clear();
		}

		/** Passa a responder {@code status}, sem corpo, em toda chamada a {@code path}. */
		public void failWith(String path, int status) {
			faults.put(path, status);
		}

		public void clearFaults() {
			faults.clear();
		}

		@Override
		protected boolean shouldNotFilter(HttpServletRequest request) {
			return !request.getRequestURI().startsWith(MOCK_SERVICES_PREFIX);
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			calls.computeIfAbsent(request.getRequestURI(), path -> new AtomicInteger()).incrementAndGet();
			Integer fault = faults.get(request.getRequestURI());
			if (fault != null) {
				response.setStatus(fault);
				return;
			}
			chain.doFilter(request, response);
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MockServiceCalls {

		@Bean
		MockServiceCallCounter mockServiceCallCounter() {
			return new MockServiceCallCounter();
		}

	}

}
