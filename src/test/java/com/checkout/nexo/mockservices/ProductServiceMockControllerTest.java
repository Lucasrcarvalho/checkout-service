package com.checkout.nexo.mockservices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.checkout.nexo.bff.error.ErrorCatalog;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sem {@code @ActiveProfiles}: o profile {@code mock-services} vem do {@code application.yaml}
 * (EXT-05, "sem profile adicional").
 */
@WebMvcTest(controllers = ProductServiceMockController.class)
@Import(ErrorCatalog.class)
class ProductServiceMockControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private Environment environment;

	private String productsBody() throws Exception {
		return mockMvc.perform(get("/mock-services/products")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void returnsTheThreePlansInTheSpecOrderWithTheSpecValues() throws Exception {
		mockMvc.perform(get("/mock-services/products"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].slug").value("basico"))
				.andExpect(jsonPath("$[0].name").value("Plano Básico"))
				.andExpect(jsonPath("$[0].description").value("Assinatura mensal com o essencial do catálogo"))
				.andExpect(jsonPath("$[0].price").value(19.90))
				.andExpect(jsonPath("$[0].currency").value("BRL"))
				.andExpect(jsonPath("$[1].slug").value("premium"))
				.andExpect(jsonPath("$[1].name").value("Plano Premium"))
				.andExpect(jsonPath("$[1].description").value("Assinatura mensal com catálogo completo"))
				.andExpect(jsonPath("$[1].price").value(39.90))
				.andExpect(jsonPath("$[1].currency").value("BRL"))
				.andExpect(jsonPath("$[2].slug").value("familia"))
				.andExpect(jsonPath("$[2].name").value("Plano Família"))
				.andExpect(jsonPath("$[2].description").value("Assinatura mensal para até 4 perfis"))
				.andExpect(jsonPath("$[2].price").value(59.90))
				.andExpect(jsonPath("$[2].currency").value("BRL"));
	}

	@Test
	void theRawJsonKeepsTheTwoDecimalPlacesOfThePrices() throws Exception {
		String body = productsBody();

		assertThat(body).contains("\"price\":19.90").contains("\"price\":39.90").contains("\"price\":59.90");
	}

	@Test
	void eachItemHasExactlyTheContractKeys() throws Exception {
		List<Map<String, Object>> items = jsonMapper.readValue(productsBody(),
				new TypeReference<List<Map<String, Object>>>() {
				});

		assertThat(items).hasSize(3).allSatisfy(item -> assertThat(item.keySet())
				.containsExactlyInAnyOrder("slug", "name", "description", "price", "currency"));
	}

	@Test
	void respondsWithAJsonContentType() throws Exception {
		mockMvc.perform(get("/mock-services/products"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
	}

	@Test
	void theApplicationYamlTurnsTheMockServicesProfileOnByDefault() {
		assertThat(environment.getActiveProfiles()).contains("mock-services");
	}

	// ---- Profile (EXT-05) -------------------------------------------------------------------

	@Test
	void theControllerBeanExistsWithTheMockServicesProfile() {
		new ApplicationContextRunner().withUserConfiguration(ProductServiceMockController.class)
				.withInitializer(context -> context.getEnvironment().setActiveProfiles("mock-services"))
				.run(context -> assertThat(context).hasSingleBean(ProductServiceMockController.class));
	}

	@Test
	void theControllerBeanDoesNotExistWithoutTheProfile() {
		new ApplicationContextRunner().withUserConfiguration(ProductServiceMockController.class)
				.run(context -> assertThat(context).doesNotHaveBean(ProductServiceMockController.class));
	}

}
