package com.checkout.nexo;

import org.junit.jupiter.api.Test;

/**
 * Estende {@link AbstractIT}: todos os {@code *IT} {@code @SpringBootTest} compartilham um unico
 * contexto, e portanto um unico par de containers (AD-009).
 */
class NexoApplicationIT extends AbstractIT {

	@Test
	void contextLoads() {
	}

}
