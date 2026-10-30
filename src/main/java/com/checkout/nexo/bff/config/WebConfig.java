package com.checkout.nexo.bff.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS so para {@code /operations/**} (CORS-01): as origens de {@link CorsProperties}, os metodos
 * que o frontend usa e o unico cabecalho que ele envia. Qualquer outra origem fica sem
 * {@code Access-Control-Allow-Origin}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class WebConfig implements WebMvcConfigurer {

	private final CorsProperties cors;

	public WebConfig(CorsProperties cors) {
		this.cors = cors;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/operations/**")
				.allowedOrigins(cors.allowedOrigins().toArray(String[]::new))
				.allowedMethods("POST", "OPTIONS")
				.allowedHeaders("Content-Type");
	}

}
