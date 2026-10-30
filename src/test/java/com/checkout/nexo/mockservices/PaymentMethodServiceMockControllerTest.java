package com.checkout.nexo.mockservices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;

import com.checkout.nexo.bff.error.ErrorCatalog;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sem {@code @ActiveProfiles}: o profile {@code mock-services} vem do {@code application.yaml}
 * (EXT-05).
 */
@WebMvcTest(controllers = PaymentMethodServiceMockController.class)
@Import(ErrorCatalog.class)
class PaymentMethodServiceMockControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	private String paymentMethodsBody() throws Exception {
		return mockMvc.perform(get("/mock-services/payment-methods")).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void returnsTheThreeMethodsInTheSpecOrderWithTheSpecValues() throws Exception {
		mockMvc.perform(get("/mock-services/payment-methods"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].code").value("CREDIT_CARD"))
				.andExpect(jsonPath("$[0].name").value("Cartão de Crédito"))
				.andExpect(jsonPath("$[0].requiresCard").value(true))
				.andExpect(jsonPath("$[1].code").value("DEBIT_CARD"))
				.andExpect(jsonPath("$[1].name").value("Cartão de Débito"))
				.andExpect(jsonPath("$[1].requiresCard").value(true))
				.andExpect(jsonPath("$[2].code").value("PIX"))
				.andExpect(jsonPath("$[2].name").value("Pix"))
				.andExpect(jsonPath("$[2].requiresCard").value(false));
	}

	@Test
	void eachItemHasExactlyTheContractKeys() throws Exception {
		List<Map<String, Object>> items = jsonMapper.readValue(paymentMethodsBody(),
				new TypeReference<List<Map<String, Object>>>() {
				});

		assertThat(items).hasSize(3).allSatisfy(
				item -> assertThat(item.keySet()).containsExactlyInAnyOrder("code", "name", "requiresCard"));
	}

	@Test
	void requiresCardIsAJsonBooleanInTheRawText() throws Exception {
		assertThat(paymentMethodsBody()).contains("\"requiresCard\":true").contains("\"requiresCard\":false")
				.doesNotContain("\"requiresCard\":\"");
	}

	// ---- Profile (EXT-05) -------------------------------------------------------------------

	@Test
	void theControllerBeanExistsWithTheMockServicesProfile() {
		new ApplicationContextRunner().withUserConfiguration(PaymentMethodServiceMockController.class)
				.withInitializer(context -> context.getEnvironment().setActiveProfiles("mock-services"))
				.run(context -> assertThat(context).hasSingleBean(PaymentMethodServiceMockController.class));
	}

	@Test
	void theControllerBeanDoesNotExistWithoutTheProfile() {
		new ApplicationContextRunner().withUserConfiguration(PaymentMethodServiceMockController.class)
				.run(context -> assertThat(context).doesNotHaveBean(PaymentMethodServiceMockController.class));
	}

}
