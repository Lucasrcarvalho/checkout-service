# Arquitetura e tecnologias do BFF Nexo

Este documento lista os conceitos de arquitetura, as bibliotecas e as ferramentas
usados no BFF de checkout, cada um com uma frase de contexto e o lugar do código ou
da configuração onde aparece. Tudo aqui foi conferido no código e nos arquivos de
configuração do projeto; onde o dado vem do log de inicialização ou do README, o
texto diz isso.

Para subir e testar a API, veja o [README](../README.md). Para a decisão de cada
tema (cache, concorrência, erros), veja as seções de Decisões do README.

## Índice

1. [Visão geral](#1-visão-geral)
2. [Arquitetura](#2-arquitetura)
3. [Persistência e banco de dados](#3-persistência-e-banco-de-dados)
4. [Frameworks e bibliotecas](#4-frameworks-e-bibliotecas)
5. [Ferramentas de build, execução e infraestrutura](#5-ferramentas-de-build-execução-e-infraestrutura)
6. [Testes](#6-testes)
7. [Documentação da API](#7-documentação-da-api)
8. [O que ficou de fora de propósito](#8-o-que-ficou-de-fora-de-propósito)
9. [Nota sobre os identificadores nos comentários](#9-nota-sobre-os-identificadores-nos-comentários)

## 1. Visão geral

O Nexo é um **BFF** (Backend for Frontend) de checkout de assinatura. Ele expõe duas
operações ao frontend, `POST /operations/pre-checkout` e `POST /operations/checkout`
([OperationsController.java](../src/main/java/com/checkout/nexo/bff/operations/OperationsController.java:34)).
A cobrança é simulada: não há gateway real.

| Item | Valor | Onde |
| --- | --- | --- |
| Linguagem | Java 25 | [pom.xml:30](../pom.xml:30) |
| Framework | Spring Boot 4.1.2 | [pom.xml:8](../pom.xml:8) |
| Banco | PostgreSQL (16.9 no compose, 18.6 nos testes) | [docker-compose.yaml:11](../docker-compose.yaml:11), [TestcontainersConfiguration.java:25](../src/test/java/com/checkout/nexo/TestcontainersConfiguration.java:25) |
| Cache | Redis 8.8.4 | [docker-compose.yaml:29](../docker-compose.yaml:29) |
| Build | Maven (via wrapper) | [maven-wrapper.properties](../.mvn/wrapper/maven-wrapper.properties) |

## 2. Arquitetura

### 2.1 BFF (Backend for Frontend)

Um BFF é um serviço feito sob medida para um frontend: ele junta dados de outros
serviços e devolve só o que as telas precisam. Aqui, o pré-checkout reúne produtos e
meios de pagamento de dois serviços externos e devolve um carrinho pronto para a tela
de pagamento. O contrato de resposta é desenhado para o frontend, inclusive os textos
e os botões de erro (veja 2.6).

### 2.2 Camadas e regra de dependência (portas e adaptadores)

O código fica em quatro pacotes sob `com.checkout.nexo`:

| Pacote | Papel |
| --- | --- |
| `bff` | Entrada HTTP: controllers, DTOs, contrato de erro, CORS e OpenAPI. |
| `checkout` | Regra de negócio: `application` (casos de uso), `domain` (entidades e exceções), `port` (interfaces) e `persistence` (repositórios). |
| `integration` | Adaptadores das portas: clientes HTTP, cache, cobrança simulada e configuração. |
| `mockservices` | Os "serviços externos" simulados, servidos pela própria aplicação. |

A ideia é a de **portas e adaptadores** (arquitetura hexagonal): a regra de negócio só
enxerga interfaces, como `ProductCatalog`
([ProductCatalog.java:9](../src/main/java/com/checkout/nexo/checkout/port/ProductCatalog.java:9))
e `PaymentProcessor`
([PaymentProcessor.java:6](../src/main/java/com/checkout/nexo/checkout/port/PaymentProcessor.java:6)).
Quem as implementa fica em `integration`: `ProductServiceClient`,
`PaymentMethodServiceClient` e `SimulatedPaymentProcessor`. Trocar o serviço simulado
por um real é mudar um adaptador, sem tocar no caso de uso.

A regra de dependência é: `bff` e `integration` dependem de `checkout`, nunca o
contrário, e só `mockservices` importa `mockservices`. O `ArchitectureTest` falha se
alguém violar isso, por varredura dos `import`
([ArchitectureTest.java:67](../src/test/java/com/checkout/nexo/ArchitectureTest.java:67)).

### 2.3 Casos de uso e snapshot do carrinho

Cada operação é um caso de uso numa classe `@Service`:
`PreCheckoutService` e `CheckoutService`, em `checkout/application`.

O pré-checkout grava no carrinho um **snapshot**: preço e nome dos itens e a lista de
meios de pagamento permitidos, como estavam naquele momento
([Cart.java:27](../src/main/java/com/checkout/nexo/checkout/domain/Cart.java:27)). O
checkout cobra desse registro, sem consultar os catálogos
([CheckoutService.java:26](../src/main/java/com/checkout/nexo/checkout/application/CheckoutService.java:26)).
Assim, uma mudança de preço depois do pré-checkout não altera um carrinho já montado.

O pré-checkout **não é transacional de propósito**: as chamadas HTTP aos catálogos não
seguram uma conexão do banco, e só o `save` abre uma transação curta
([PreCheckoutService.java:22](../src/main/java/com/checkout/nexo/checkout/application/PreCheckoutService.java:22)).
O checkout é `@Transactional`
([CheckoutService.java:63](../src/main/java/com/checkout/nexo/checkout/application/CheckoutService.java:63)).

### 2.4 Pagamento único por carrinho (concorrência)

Dois checkouts simultâneos do mesmo carrinho não podem cobrar duas vezes. Há três
barreiras, de fora para dentro:

1. **Lock pessimista**: o checkout lê o carrinho com `SELECT ... FOR UPDATE` e
   serializa as chamadas concorrentes
   ([CartRepository.java:21](../src/main/java/com/checkout/nexo/checkout/persistence/CartRepository.java:21)).
2. **Índice único parcial no banco**: só pode haver uma tentativa `APPROVED` por
   carrinho, mesmo para escritas feitas fora da aplicação
   ([V1__checkout.sql:45](../src/main/resources/db/migration/V1__checkout.sql:45)).
3. **Lock otimista**: o `Cart` tem `@Version`
   ([Cart.java:38](../src/main/java/com/checkout/nexo/checkout/domain/Cart.java:38)).

Quem chega depois recebe `409 CART_ALREADY_PAID`. O `ConcurrencyIT` dispara 20 rodadas
de dois checkouts simultâneos e confere um `200`, um `409` e uma única tentativa
aprovada por carrinho
([ConcurrencyIT.java:113](../src/test/java/com/checkout/nexo/checkout/ConcurrencyIT.java:113)).

### 2.5 Cache-aside com degradação graciosa

O catálogo fica no Redis por 10 minutos com `@Cacheable` (padrão **cache-aside**: lê do
cache e, se faltar, chama a origem e grava). Se o Redis cair, um `CacheErrorHandler`
registra um `WARN` e a chamada segue sem cache, em vez de derrubar o pré-checkout
([CacheConfig.java:68](../src/main/java/com/checkout/nexo/integration/config/CacheConfig.java:68)).
Por que existe, onde é usado e como observar estão em
[Decisões > Cache](../README.md#cache).

### 2.6 Contrato de erro único

Toda falha, de negócio ou técnica, responde o mesmo objeto, com `displayType`,
`errorCode`, `cartId` e `frontendError` (título, descrição e botões). Dois pontos:

- O **`ErrorCatalog`** é a fonte única de status HTTP, `displayType` e textos de cada
  erro ([ErrorCatalog.java:17](../src/main/java/com/checkout/nexo/bff/error/ErrorCatalog.java:17)).
- O **`GlobalExceptionHandler`** (`@RestControllerAdvice`) converte exceções nesse
  objeto, inclusive as rejeições do próprio Spring MVC (400, 404, 405, 406, 415)
  ([GlobalExceptionHandler.java:27](../src/main/java/com/checkout/nexo/bff/error/GlobalExceptionHandler.java:27)).
  Ele estende `ResponseEntityExceptionHandler` para centralizar essas rejeições.

O `displayType` diz ao frontend como mostrar o erro: `BLOCK` impede o fluxo e
`INTERVENTION` pede uma correção do usuário. Falhas técnicas respondem `5xx` (503 para
serviço externo fora do ar, lock ou timeout; 500 para o inesperado), e as de negócio
respondem `4xx`
([GlobalExceptionHandler.java:51](../src/main/java/com/checkout/nexo/bff/error/GlobalExceptionHandler.java:51)).

### 2.7 Resiliência: tempos limite e falha rápida

- **Clientes HTTP** com tempo limite explícito de 1 s para conectar e 2 s para ler
  ([ServicesProperties.java](../src/main/java/com/checkout/nexo/integration/config/ServicesProperties.java),
  [RestClientConfig.java:16](../src/main/java/com/checkout/nexo/integration/config/RestClientConfig.java:16)).
- **Redis** com tempo limite de 500 ms; o padrão do Lettuce é 60 s e prenderia o
  pré-checkout ([application.yaml:25](../src/main/resources/application.yaml:25)).
- **Banco**: `lock_timeout` de 3 s em toda conexão do pool, para que a espera por lock
  de linha estoure em vez de travar
  ([application.yaml:15](../src/main/resources/application.yaml:15)); o estouro vira `503`.

Não há circuit breaker nem retentativa: o README registra isso como decisão
([O que ficou de fora](../README.md#o-que-ficou-de-fora)).

### 2.8 Proteção dos dados do cartão

O número completo, o CVV, o titular e a validade não são gravados no banco, não vão
para log nem para resposta; só os quatro últimos dígitos
([CardData.java:3](../src/main/java/com/checkout/nexo/checkout/domain/CardData.java:3)).
Três mecanismos concretos:

- `CardData.toString()` devolve `CardData[****]`, então o objeto não vaza num log por
  engano ([CardData.java:20](../src/main/java/com/checkout/nexo/checkout/domain/CardData.java:20)).
- O DTO `CardRequest` não tem anotações de Bean Validation, porque a mensagem padrão do
  Spring inclui o valor rejeitado; quem valida é o `CardValidator`
  ([CardRequest.java:3](../src/main/java/com/checkout/nexo/bff/operations/dto/CardRequest.java:3)).
- O `GlobalExceptionHandler` nunca registra `getMessage()` de exceções de parsing ou
  validação, só o tipo.

O `DataHygieneIT` varre colunas, log e respostas atrás dos valores usados no teste.

### 2.9 Observabilidade

- **Logs** com SLF4J. O checkout escreve uma linha INFO por chamada, com `cartId`,
  resultado, código de erro e código do meio, e nunca dado de cartão
  ([CheckoutService.java:139](../src/main/java/com/checkout/nexo/checkout/application/CheckoutService.java:139)).
- **Actuator** expõe só o `health`, com os grupos de liveness e readiness
  ([application.yaml:55](../src/main/resources/application.yaml:55)).
- O `HEALTHCHECK` do container usa a **liveness**, não o health geral: com o Redis
  fora, o geral fica `DOWN`, mas a API continua atendendo
  ([Dockerfile:43](../Dockerfile:43)).

### 2.10 Configuração externalizada e profiles

- **Propriedades tipadas** com `@ConfigurationProperties` em records:
  `ServicesProperties`, `CacheProperties` e `CorsProperties`
  ([CorsProperties.java:13](../src/main/java/com/checkout/nexo/bff/config/CorsProperties.java:13)).
- **Profile `mock-services`**, ligado em `application.yaml`, ativa os controllers dos
  serviços simulados com `@Profile`
  ([application.yaml:5](../src/main/resources/application.yaml:5),
  [ProductServiceMockController.java:16](../src/main/java/com/checkout/nexo/mockservices/ProductServiceMockController.java:16)).
- **`.env` como fonte das credenciais**: o Spring Boot não lê `.env` sozinho, então o
  `application.yaml` o importa com `spring.config.import`
  ([application.yaml:8](../src/main/resources/application.yaml:8)). No Docker, o compose
  sobrepõe a URL do banco com `SPRING_DATASOURCE_URL`
  ([docker-compose.yaml:47](../docker-compose.yaml:47)).

### 2.11 Tempo injetável

O relógio é um bean `Clock` em UTC, injetado nos serviços, para que os testes usem
`Clock.fixed`
([ClockConfig.java](../src/main/java/com/checkout/nexo/integration/config/ClockConfig.java)).
As datas do banco são `timestamptz`.

### 2.12 Resultado tipado: interfaces `sealed` e `switch` por padrão

`CheckoutOutcome` e `ChargeResult` são `sealed interface`, e o código as consome com
`switch` por padrão do Java moderno, que o compilador exige exaustivo
([CheckoutOutcome.java:9](../src/main/java/com/checkout/nexo/checkout/application/CheckoutOutcome.java:9),
[CheckoutService.java:97](../src/main/java/com/checkout/nexo/checkout/application/CheckoutService.java:97)).
A recusa de pagamento volta como resultado e só vira exceção no controller, depois do
commit da tentativa `DECLINED`
([OperationsController.java:91](../src/main/java/com/checkout/nexo/bff/operations/OperationsController.java:91)).

## 3. Persistência e banco de dados

| Conceito | O que é aqui | Onde |
| --- | --- | --- |
| **JPA / Hibernate** | Mapeamento objeto-relacional das entidades `Cart` e `PaymentAttempt`; `CartItem` e `AllowedPaymentMethod` são `@Embeddable`, guardados como `@ElementCollection`. | [Cart.java:30](../src/main/java/com/checkout/nexo/checkout/domain/Cart.java:30), [Cart.java:57](../src/main/java/com/checkout/nexo/checkout/domain/Cart.java:57), [CartItem.java:11](../src/main/java/com/checkout/nexo/checkout/domain/CartItem.java:11) |
| **Spring Data JPA** | Repositórios por interface (`CartRepository`, `PaymentAttemptRepository`), com uma query de lock pessimista. | [CartRepository.java:15](../src/main/java/com/checkout/nexo/checkout/persistence/CartRepository.java:15) |
| **Flyway** | Versiona o schema por migrações SQL; a `V1__checkout.sql` cria as quatro tabelas. | [V1__checkout.sql](../src/main/resources/db/migration/V1__checkout.sql) |
| **`ddl-auto: validate`** | O Hibernate só confere o schema contra as entidades; quem altera o banco é o Flyway. | [application.yaml:20](../src/main/resources/application.yaml:20) |
| **Constraints no banco** | `check` de status, `check` que amarra `paid_at` ao status `APPROVED`, regex dos 4 últimos dígitos e chaves únicas. | [V1__checkout.sql:3](../src/main/resources/db/migration/V1__checkout.sql:3), [V1__checkout.sql:9](../src/main/resources/db/migration/V1__checkout.sql:9), [V1__checkout.sql:42](../src/main/resources/db/migration/V1__checkout.sql:42) |
| **Dinheiro em `BigDecimal`** | Colunas `numeric(10,2)` e total com `setScale(2)`, sem ponto flutuante. | [Cart.java:82](../src/main/java/com/checkout/nexo/checkout/domain/Cart.java:82) |
| **UUID gerado pela aplicação** | O `cartId` nasce no pré-checkout, antes do `save`. | [PreCheckoutService.java:65](../src/main/java/com/checkout/nexo/checkout/application/PreCheckoutService.java:65) |
| **`open-in-view: false`** | Nenhuma sessão do Hibernate fica aberta durante a renderização da resposta. | [application.yaml:17](../src/main/resources/application.yaml:17) |
| **HikariCP** | Pool de conexões; aqui recebe o `SET lock_timeout='3s'` a cada conexão nova. | [application.yaml:15](../src/main/resources/application.yaml:15) |
| **Normalização Unicode (NFC)** | O nome do meio de pagamento recebido é normalizado antes da comparação, para `ã` composto e decomposto casarem. | [Cart.java:93](../src/main/java/com/checkout/nexo/checkout/domain/Cart.java:93) |

## 4. Frameworks e bibliotecas

A coluna "Onde" aponta a declaração no `pom.xml`. As versões vêm do `spring-boot-starter-parent`
4.1.2; as que aparecem entre parênteses foram lidas no log de inicialização
e valem para aquele momento.

| Biblioteca | Para que serve aqui | Onde |
| --- | --- | --- |
| **Spring Boot** 4.1.2 | Base da aplicação: auto-configuração, empacotamento e `starters`. Spring Framework 7.0.10 no log. | [pom.xml:8](../pom.xml:8) |
| **`spring-boot-starter-webmvc`** | API HTTP síncrona com Spring MVC, em Tomcat embutido. | [pom.xml:63](../pom.xml:63) |
| **`spring-boot-starter-validation`** | Bean Validation (`jakarta.validation`) nos DTOs de entrada, como `@NotNull`, `@Size` e `@Valid`. | [pom.xml:59](../pom.xml:59), [PreCheckoutRequest.java:13](../src/main/java/com/checkout/nexo/bff/operations/dto/PreCheckoutRequest.java:13) |
| **`spring-boot-starter-data-jpa`** | Spring Data JPA e Hibernate; HikariCP é o pool padrão (7.0.2 no log). | [pom.xml:43](../pom.xml:43) |
| **`spring-boot-starter-flyway`** e **`flyway-database-postgresql`** | Migrações de schema; o módulo `postgresql` dá suporte ao banco (Flyway 12.4.0 no log). | [pom.xml:51](../pom.xml:51), [pom.xml:67](../pom.xml:67) |
| **Driver `postgresql`** | Driver JDBC do PostgreSQL (42.7.14 no log), só em tempo de execução. | [pom.xml:83](../pom.xml:83) |
| **`spring-boot-starter-data-redis`** | Cliente Redis (Lettuce) e `RedisTemplate`; aqui sustenta o cache. | [pom.xml:47](../pom.xml:47) |
| **`spring-boot-starter-cache`** | Abstração de cache do Spring: `@EnableCaching` e `@Cacheable`. | [pom.xml:39](../pom.xml:39), [CacheConfig.java:35](../src/main/java/com/checkout/nexo/integration/config/CacheConfig.java:35) |
| **`spring-boot-starter-restclient`** | `RestClient`, o cliente HTTP síncrono do Spring, usado nos adaptadores dos serviços externos, sobre o `HttpClient` do JDK em HTTP/1.1. | [pom.xml:55](../pom.xml:55), [RestClientConfig.java:30](../src/main/java/com/checkout/nexo/integration/config/RestClientConfig.java:30) |
| **`spring-boot-starter-actuator`** | Endpoints operacionais; aqui, só `health` e as sondas de liveness e readiness. | [pom.xml:35](../pom.xml:35), [application.yaml:55](../src/main/resources/application.yaml:55) |
| **springdoc-openapi** 3.2.0 | Gera o OpenAPI 3.1 e serve o Swagger UI. | [pom.xml:70](../pom.xml:70) |
| **Jackson 3** (`tools.jackson`) | Serialização JSON, inclusive do cache (`JacksonJsonRedisSerializer`) e dos exemplos do OpenAPI. As anotações continuam em `com.fasterxml.jackson.annotation`, como o `@JsonInclude` do contrato de erro. | [CacheConfig.java:25](../src/main/java/com/checkout/nexo/integration/config/CacheConfig.java:25), [ErrorResponse.java:12](../src/main/java/com/checkout/nexo/bff/error/ErrorResponse.java:12) |
| **SLF4J** | API de log usada nas classes. | [CheckoutService.java:31](../src/main/java/com/checkout/nexo/checkout/application/CheckoutService.java:31) |
| **JSpecify** | Anotação `@Nullable` num override do handler de erros. | [GlobalExceptionHandler.java:5](../src/main/java/com/checkout/nexo/bff/error/GlobalExceptionHandler.java:5) |
| **Jakarta Persistence** e **Jakarta Servlet** | APIs padrão (`@Entity`, `@Version`, `LockModeType`, `HttpServletRequest`) usadas diretamente no código. | [Cart.java:14](../src/main/java/com/checkout/nexo/checkout/domain/Cart.java:14), [CartRepository.java:13](../src/main/java/com/checkout/nexo/checkout/persistence/CartRepository.java:13) |
| **Records do Java** | DTOs, propriedades e valores imutáveis (`CardData`, `CartItem`, `ErrorResponse`, `ServicesProperties`). | [CardData.java:7](../src/main/java/com/checkout/nexo/checkout/domain/CardData.java:7) |

## 5. Ferramentas de build, execução e infraestrutura

| Ferramenta | Papel | Onde |
| --- | --- | --- |
| **Maven Wrapper** | Fixa o Maven 3.10.0 (wrapper 3.3.4), sem exigir instalação. No Windows use `.\mvnw.cmd`. | [maven-wrapper.properties](../.mvn/wrapper/maven-wrapper.properties) |
| **`maven-enforcer-plugin`** | Falha o build logo no início com um JDK menor que 25, com uma mensagem em português. | [pom.xml:132](../pom.xml:132) |
| **`maven-dependency-plugin`** | Expõe o caminho do jar do Mockito para o `-javaagent`, porque o JDK 25 desaprova o auto-anexo. | [pom.xml:150](../pom.xml:150) |
| **`maven-surefire-plugin`** e **`maven-failsafe-plugin`** | Surefire roda os `*Test`; o Failsafe roda os `*IT` (integração). | [pom.xml:165](../pom.xml:165), [pom.xml:172](../pom.xml:172) |
| **JaCoCo** 0.8.16 | Relatório de cobertura em `target/site/jacoco/index.html`, gerado na fase `verify`. | [pom.xml:179](../pom.xml:179) |
| **`spring-boot-maven-plugin`** | Empacota o jar executável e roda `spring-boot:run` e `spring-boot:test-run`. | [pom.xml:199](../pom.xml:199) |
| **`spring-boot-devtools`** | Reinício automático em desenvolvimento (aparece como `restartedMain` no log); opcional e só em runtime. | [pom.xml:77](../pom.xml:77) |
| **Docker, build em duas etapas** | `eclipse-temurin:25-jdk` compila e `eclipse-temurin:25-jre` executa. | [Dockerfile:4](../Dockerfile:4), [Dockerfile:21](../Dockerfile:21) |
| **Jar em camadas** | `java -Djarmode=tools ... extract --layers` separa dependências e código, para o cache de camadas do Docker. | [Dockerfile:18](../Dockerfile:18) |
| **Usuário sem privilégios** | O container roda como `app` (uid 10001), não como root. | [Dockerfile:25](../Dockerfile:25) |
| **Healthcheck sem curl** | A imagem JRE não traz `curl`; o script fala HTTP pelo `/dev/tcp` do bash, na liveness. | [Dockerfile:34](../Dockerfile:34) |
| **Docker Compose** | Sobe `app`, `postgres` e `redis`, com `healthcheck` e `depends_on` com `service_healthy`. | [docker-compose.yaml:40](../docker-compose.yaml:40) |
| **Testcontainers no modo de execução** | A classe `TestNexoApplication` sobe a API com PostgreSQL e Redis descartáveis (`spring-boot:test-run`). | [TestNexoApplication.java:8](../src/test/java/com/checkout/nexo/TestNexoApplication.java:8) |
| **`.gitattributes`** | Força `mvnw` com fim de linha LF e `*.cmd` com CRLF, para o wrapper rodar no Linux e no Windows. | [.gitattributes](../.gitattributes) |

## 6. Testes

Os testes seguem uma divisão por nome: `*Test` roda no Surefire, sem Docker; `*IT` roda
no Failsafe, com PostgreSQL e Redis em containers.

| Ferramenta ou técnica | O que faz | Onde |
| --- | --- | --- |
| **JUnit 5 (Jupiter)** | Motor de testes, com `@ParameterizedTest` e `@CsvSource` nos casos em tabela. | [ArchitectureTest.java:125](../src/test/java/com/checkout/nexo/ArchitectureTest.java:125) |
| **AssertJ** | Asserções fluentes. | [ArchitectureTest.java:114](../src/test/java/com/checkout/nexo/ArchitectureTest.java:114) |
| **Mockito** | Dublês nos testes de serviço, com `@Mock` e `verify`. | [CheckoutServiceTest.java](../src/test/java/com/checkout/nexo/checkout/application/CheckoutServiceTest.java) |
| **MockMvc** (`spring-boot-starter-webmvc-test`) | Testa a camada web sem subir um servidor. | [CheckoutEndpointTest.java](../src/test/java/com/checkout/nexo/bff/operations/CheckoutEndpointTest.java) |
| **Starters de teste** `*-test` | Fatias de teste de JPA, Flyway, RestClient, Validation e WebMVC. | [pom.xml:88](../pom.xml:88) |
| **Testcontainers** | PostgreSQL e Redis reais nos `*IT`, com versão fixa e `@ServiceConnection`, que liga o container à configuração do Spring sem propriedades manuais. | [TestcontainersConfiguration.java:15](../src/test/java/com/checkout/nexo/TestcontainersConfiguration.java:15) |
| **Contexto Spring compartilhado** | Todos os `*IT` herdam de `AbstractIT` e reaproveitam um único contexto e um único par de containers; o cache é invalidado antes de cada teste. | [AbstractIT.java:38](../src/test/java/com/checkout/nexo/AbstractIT.java:38) |
| **Teste de arquitetura sem biblioteca** | `ArchitectureTest` varre os `import` de `src/main/java`; os testes do próprio detector provam que ele acusa cada violação e não acusa o permitido. | [ArchitectureTest.java:20](../src/test/java/com/checkout/nexo/ArchitectureTest.java:20) |
| **Teste de concorrência** | `ExecutorService` e `CyclicBarrier` soltam dois checkouts ao mesmo tempo, 20 vezes. | [ConcurrencyIT.java:113](../src/test/java/com/checkout/nexo/checkout/ConcurrencyIT.java:113) |
| **Contagem de chamadas aos mocks** | Os `*IT` contam as chamadas HTTP que chegam a `/mock-services/**` para provar o cache. | [CacheIT.java:102](../src/test/java/com/checkout/nexo/integration/CacheIT.java:102) |
| **Higiene de dados** | `DataHygieneIT` busca valores do cartão nas colunas, no log e nas respostas. | [DataHygieneIT.java](../src/test/java/com/checkout/nexo/checkout/DataHygieneIT.java) |

Este documento não afirma que os testes passam: não os executei ao escrevê-lo.

## 7. Documentação da API

- **OpenAPI 3.1** gerado por springdoc, em dois grupos: `bff` (as operações do
  frontend) e `mock-services` (os serviços simulados)
  ([OpenApiConfig.java:28](../src/main/java/com/checkout/nexo/bff/config/OpenApiConfig.java:28)).
- **Swagger UI** em `/swagger-ui.html`
  ([application.yaml:48](../src/main/resources/application.yaml:48)).
- Os **exemplos de erro** do OpenAPI saem do `ErrorCatalog`, e não de um texto
  repetido: um `OpenApiCustomizer` os monta a partir do mesmo catálogo que responde as
  requisições reais
  ([ErrorExamplesCustomizer.java:28](../src/main/java/com/checkout/nexo/bff/config/ErrorExamplesCustomizer.java:28)).
- **CORS** só para `/operations/**`, com origem configurável, métodos `POST` e
  `OPTIONS` e o único cabeçalho que o frontend envia
  ([WebConfig.java:23](../src/main/java/com/checkout/nexo/bff/config/WebConfig.java:23)).

Passo a passo no navegador: [teste-swagger-ui.md](teste-swagger-ui.md).

## 8. O que ficou de fora de propósito

Registrado no README ([Decisões](../README.md#decisões) e
[O que ficou de fora](../README.md#o-que-ficou-de-fora)):

- **Mensageria** (RabbitMQ, Kafka): avaliada e não adotada, porque o contrato é
  síncrono e não há carga a absorver. Se surgirem efeitos depois do pagamento, a
  decisão é um outbox transacional.
- **Algoritmo de Luhn**: não é aplicado, para aceitar o cartão de exemplo
  `4111111111114242`.
- **Gateway real, 3DS e antifraude**: a cobrança é simulada.
- **Autenticação, limite de requisições e `Idempotency-Key`**: fora do escopo.
- **Circuit breaker e fallback** para os serviços externos: o tempo limite com `503`
  basta, e o serviço simulado roda no mesmo processo.
- **ArchUnit**: a regra de dependência é testada por varredura de `import`, com a
  limitação de não ver uso por nome totalmente qualificado sem `import`
  ([ArchitectureTest.java:21](../src/test/java/com/checkout/nexo/ArchitectureTest.java:21)).

## 9. Nota sobre os identificadores nos comentários

Os comentários do código citam identificadores como `CHK-04`, `CACHE-03`, `EXT-04`,
`ERR-03`, `DATA-03` e `AD-008`. Eles vêm de uma especificação de requisitos que **não
está neste repositório**, então este documento não os explica nem os usa como fonte.
Quando um comentário explica a regra por extenso, é o comentário que vale.
