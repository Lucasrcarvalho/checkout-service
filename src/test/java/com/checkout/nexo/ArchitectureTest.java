package com.checkout.nexo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Regras de dependencia entre pacotes (ARCH-01, EXT-01), por varredura dos {@code import} de
 * {@code src/main/java}, sem biblioteca de arquitetura. Limitacao: nao ve uso por nome totalmente
 * qualificado sem {@code import}.
 *
 * <p>Os testes do proprio detector alimentam-no com arquivos temporarios que violam cada regra,
 * para provar que ele nao e vazio, e com importacoes permitidas, para provar que nao acusa tudo.
 */
class ArchitectureTest {

	private static final String ROOT_PACKAGE = "com.checkout.nexo";

	private static final String ROOT_LAYER = "root";

	private static final String REST_CLIENT = "org.springframework.web.client.RestClient";

	private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

	private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;");

	/** Um {@code import} de um arquivo, com a camada (primeiro pacote abaixo da raiz) do arquivo. */
	record SourceImport(String layer, String file, String imported) {
	}

	// ---- Detector ---------------------------------------------------------------------------

	static List<SourceImport> importsUnder(Path sourceRoot) throws IOException {
		Path base = sourceRoot.resolve(ROOT_PACKAGE.replace('.', '/'));
		List<SourceImport> imports = new ArrayList<>();
		try (Stream<Path> files = Files.walk(base)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				Path relative = base.relativize(file);
				String layer = relative.getNameCount() == 1 ? ROOT_LAYER : relative.getName(0).toString();
				for (String line : Files.readAllLines(file)) {
					Matcher matcher = IMPORT.matcher(line);
					if (matcher.find()) {
						imports.add(new SourceImport(layer, relative.toString(), matcher.group(1)));
					}
				}
			}
		}
		catch (UncheckedIOException ex) {
			throw ex.getCause();
		}
		return imports;
	}

	static List<String> violations(List<SourceImport> imports) {
		List<String> violations = new ArrayList<>();
		for (SourceImport item : imports) {
			String target = layerOfImported(item.imported());
			if ("checkout".equals(item.layer()) && isOneOf(target, "bff", "integration", "mockservices")) {
				violations.add(describe(item, "checkout nao pode depender de bff, integration nem mockservices"));
			}
			else if ("bff".equals(item.layer()) && isOneOf(target, "integration", "mockservices")) {
				violations.add(describe(item, "bff nao pode depender de integration nem de mockservices"));
			}
			else if (!"mockservices".equals(item.layer()) && "mockservices".equals(target)) {
				violations.add(describe(item, "so mockservices pode importar mockservices"));
			}
			if (isOneOf(item.layer(), "bff", "checkout") && importsRestClient(item.imported())) {
				violations.add(describe(item, "bff e checkout nao podem usar RestClient"));
			}
		}
		return violations;
	}

	private static String layerOfImported(String imported) {
		String prefix = ROOT_PACKAGE + ".";
		if (!imported.startsWith(prefix)) {
			return null;
		}
		String rest = imported.substring(prefix.length());
		int dot = rest.indexOf('.');
		return dot < 0 ? null : rest.substring(0, dot);
	}

	private static boolean importsRestClient(String imported) {
		return imported.equals(REST_CLIENT) || imported.startsWith(REST_CLIENT + ".")
				|| imported.equals("org.springframework.web.client.*");
	}

	private static boolean isOneOf(String value, String... options) {
		return value != null && List.of(options).contains(value);
	}

	private static String describe(SourceImport item, String rule) {
		return item.file() + " importa " + item.imported() + ": " + rule;
	}

	// ---- Codigo real ------------------------------------------------------------------------

	@Test
	void theCurrentCodeRespectsTheDependencyRules() throws IOException {
		assertThat(violations(importsUnder(MAIN_SOURCES))).isEmpty();
	}

	@Test
	void theScanSeesImportsOfEveryLayerOfTheRealCode() throws IOException {
		assertThat(importsUnder(MAIN_SOURCES)).extracting(SourceImport::layer).contains("bff", "checkout",
				"integration", "mockservices", ROOT_LAYER);
	}

	// ---- O detector acusa cada violacao (nao e vazio) ---------------------------------------

	@ParameterizedTest
	@CsvSource({
			"checkout, com.checkout.nexo.bff.error.ErrorCatalog",
			"checkout, com.checkout.nexo.integration.products.ProductServiceClient",
			"checkout, com.checkout.nexo.mockservices.ProductServiceMockController"
	})
	void aCheckoutClassImportingBffIntegrationOrMockservicesIsDetected(String layer, String imported,
			@TempDir Path sources) throws IOException {
		assertViolationDetected(sources, layer, "import " + imported + ";", imported);
	}

	@ParameterizedTest
	@CsvSource({
			"bff, com.checkout.nexo.integration.products.ProductServiceClient",
			"bff, com.checkout.nexo.mockservices.ProductServiceMockController"
	})
	void aBffClassImportingIntegrationOrMockservicesIsDetected(String layer, String imported, @TempDir Path sources)
			throws IOException {
		assertViolationDetected(sources, layer, "import " + imported + ";", imported);
	}

	@ParameterizedTest
	@CsvSource({
			"integration, com.checkout.nexo.mockservices.ProductServiceMockController",
			"root, com.checkout.nexo.mockservices.ProductServiceMockController"
	})
	void aClassOutsideMockservicesImportingMockservicesIsDetected(String layer, String imported,
			@TempDir Path sources) throws IOException {
		assertViolationDetected(sources, layer, "import " + imported + ";", imported);
	}

	@ParameterizedTest
	@CsvSource({ "bff", "checkout" })
	void aBffOrCheckoutClassImportingRestClientIsDetected(String layer, @TempDir Path sources) throws IOException {
		assertViolationDetected(sources, layer, "import " + REST_CLIENT + ";", REST_CLIENT);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"bff|import static com.checkout.nexo.integration.config.RestClientConfig.httpClient;|com.checkout.nexo.integration.config.RestClientConfig.httpClient",
			"checkout|import com.checkout.nexo.integration.*;|com.checkout.nexo.integration.*",
			"bff|import org.springframework.web.client.RestClient.Builder;|org.springframework.web.client.RestClient.Builder",
			"checkout|import org.springframework.web.client.*;|org.springframework.web.client.*"
	})
	void staticNestedAndWildcardImportsAreDetectedToo(String layer, String importLine, String expectedImported,
			@TempDir Path sources) throws IOException {
		assertViolationDetected(sources, layer, importLine, expectedImported);
	}

	// ---- O detector nao acusa o que e permitido ---------------------------------------------

	@ParameterizedTest
	@CsvSource({
			"integration, com.checkout.nexo.checkout.port.ProductCatalog",
			"integration, org.springframework.web.client.RestClient",
			"bff, com.checkout.nexo.checkout.application.PreCheckoutService",
			"checkout, com.checkout.nexo.checkout.domain.Cart",
			"checkout, org.springframework.web.client.RestClientException",
			"mockservices, com.checkout.nexo.mockservices.ProductServiceMockController"
	})
	void allowedImportsAreNotFlagged(String layer, String imported, @TempDir Path sources) throws IOException {
		writeSource(sources, layer, "import " + imported + ";");

		assertThat(violations(importsUnder(sources))).isEmpty();
	}

	// ---- Auxiliares -------------------------------------------------------------------------

	private static void assertViolationDetected(Path sources, String layer, String importLine, String imported)
			throws IOException {
		writeSource(sources, layer, importLine);

		assertThat(violations(importsUnder(sources))).hasSize(1).singleElement().asString()
				.contains("Sample.java").contains(imported);
	}

	/** Cria {@code com/checkout/nexo/<camada>/Sample.java} (ou direto na raiz) com a linha de import. */
	private static void writeSource(Path sources, String layer, String importLine) throws IOException {
		Path directory = sources.resolve(ROOT_PACKAGE.replace('.', '/'));
		if (!ROOT_LAYER.equals(layer)) {
			directory = directory.resolve(layer);
		}
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("Sample.java"),
				"package " + ROOT_PACKAGE + ";\n\n" + importLine + "\n\nclass Sample {\n}\n");
	}

}
