package com.checkout.nexo.integration.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Relogio da aplicacao em UTC, injetavel para que os servicos que dependem do tempo sejam
 * testaveis com {@link Clock#fixed}.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
