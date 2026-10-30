# BFF de Checkout

BFF (Backend for Frontend) de um checkout de assinatura. O frontend tem três
telas (pagamento, sucesso e erro); este serviço expõe as duas operações que
elas usam:

- `POST /operations/pre-checkout` monta o carrinho a partir dos planos
  escolhidos e devolve produtos, preços e meios de pagamento.
- `POST /operations/checkout` conclui o pagamento do carrinho. A cobrança é
  simulada: este projeto não integra nenhum gateway real.

Stack: Java 25, Spring Boot 4.1.2, PostgreSQL 16.9 (JPA e Flyway), Redis 8.8.4
(cache), Maven, Docker.

## Sumário

- [Início rápido](#início-rápido)
- [Requisitos](#requisitos)
- [Subir com Docker](#subir-com-docker)
- [Executar localmente](#executar-localmente)
- [Testes](#testes)
- [Exemplos com curl](#exemplos-com-curl)
- [Swagger UI e OpenAPI](#swagger-ui-e-openapi)
- [Passo a passo no Swagger UI, com capturas de tela](docs/teste-swagger-ui.md)
- [Serviços externos simulados](#serviços-externos-simulados)
- [Tabela de erros](#tabela-de-erros)
- [Decisões](#decisões)
- [O que ficou de fora](#o-que-ficou-de-fora)
- [Limitações e itens não executados](#limitações-e-itens-não-executados)

## Início rápido

Com o Docker ativo, na pasta `nexo/` (os requisitos completos estão em
[Requisitos](#requisitos)):

**PowerShell** (Windows, preferencial):

```powershell
Copy-Item .env.example .env
docker compose up --build -d --wait
curl.exe -s http://localhost:8080/actuator/health
```

**Linux e macOS**:

```bash
cp .env.example .env
docker compose up --build -d --wait
curl -s http://localhost:8080/actuator/health
```

Quando o `status` do health for `UP`, a API está no ar em
`http://localhost:8080`. Para parar e apagar o banco: `docker compose down -v`.
Detalhes, portas e a execução fora do Docker estão em
[Subir com Docker](#subir-com-docker) e [Executar localmente](#executar-localmente).

No PowerShell, chame `curl.exe`, e não `curl` (veja
[Requisitos](#requisitos)). Os exemplos de requisição têm uma variante para
cada terminal (veja [Exemplos com curl](#exemplos-com-curl)).

### Como simular sucesso e recusa

O pagamento é simulado: o resultado depende só do meio de pagamento e do número
do cartão. O fluxo tem dois passos: o pré-checkout cria o carrinho e devolve o
`cartId`; o checkout paga esse carrinho.

| Cenário | Como provocar | Resposta |
| --- | --- | --- |
| Sucesso com cartão | `Cartão de Crédito` ou `Cartão de Débito` com número que **não** termine em `0002`, por exemplo `4111111111114242` | `200`, `status: APROVADO`, `lastDigits` |
| Sucesso com Pix | `Pix`, sem cartão | `200`, `status: APROVADO`, `pixCode` |
| Recusa | `Cartão de Crédito` ou `Cartão de Débito` com número que **termine** em `0002`, por exemplo `4111111111110002` | `422`, `errorCode: PAYMENT_DECLINED` (`P-1001`) |

Na recusa, o carrinho continua aberto: o mesmo `cartId` aceita uma nova
tentativa, por exemplo com um cartão que não termine em `0002`. Pagar de novo um
carrinho já aprovado responde `409 CART_ALREADY_PAID`.

Os comandos `curl` completos, com as respostas, estão em
[Exemplos com curl](#exemplos-com-curl): [pré-checkout](#pré-checkout),
[sucesso com cartão](#checkout-aprovado-com-cartão),
[sucesso com Pix](#checkout-aprovado-com-pix) e
[recusa](#pagamento-recusado-cartão-com-final-0002).

Para testar os mesmos fluxos só pelo navegador, sem `curl`, siga o
[passo a passo no Swagger UI](docs/teste-swagger-ui.md): ele traz os JSON de
request para copiar e capturas de tela de cada resposta.

## Requisitos

- Docker com Compose v2. As verificações deste README usaram Docker Engine
  29.9.2 e Docker Compose v5.6.1.
- JDK 25, para compilar e testar fora do Docker. A variável `JAVA_HOME` deve
  apontar para ele. Com um JDK menor, o build falha logo no início com a
  mensagem do `maven-enforcer-plugin`.
- Um terminal e o `curl`. No Windows, use o **PowerShell**; no Linux e no
  macOS, o terminal padrão (bash ou zsh). Os exemplos trazem uma variante para
  cada um (veja [Exemplos com curl](#exemplos-com-curl)). No PowerShell, chame
  `curl.exe`: `curl` é um alias de `Invoke-WebRequest`, e `curl -s ...` falha com
  "Falta um argumento para o parâmetro 'SessionVariable'". O Git Bash também
  serve, com os comandos de bash.

Aponte o `JAVA_HOME` para o JDK 25 antes de usar o `./mvnw`:

**PowerShell** (Windows, preferencial):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
.\mvnw.cmd -v
```

**Linux e macOS** (use o diretório do seu JDK 25; no Git Bash,
`export JAVA_HOME="C:\Program Files\Java\jdk-25"`):

```bash
export JAVA_HOME=/caminho/do/jdk-25
./mvnw -v
```

O comando deve mostrar `Java version: 25`.

No Windows, use `.\mvnw.cmd` onde o restante deste README mostra `./mvnw`. No
PowerShell, coloque entre aspas os argumentos `-D` que têm ponto, por exemplo
`"-Dspring-boot.run.arguments=--server.port=8081"`: sem aspas, o PowerShell
quebra o argumento no ponto e o Maven recebe duas partes.

## Subir com Docker

O `docker-compose.yaml` sobe três serviços: `app` (a API), `postgres` e
`redis`. A API só inicia depois que os dois estiverem saudáveis. As credenciais
vêm somente do arquivo `.env`, que o git e o Docker ignoram.

1. Entre na pasta `nexo/`.
2. Copie o arquivo de exemplo. Ele traz apenas valores locais de exemplo:

   PowerShell: `Copy-Item .env.example .env`. Linux e macOS:

   ```bash
   cp .env.example .env
   ```

3. Suba os serviços. O primeiro build baixa o Maven e as dependências e leva
   alguns minutos:

   ```bash
   docker compose up --build
   ```

   Para subir em segundo plano e só voltar ao prompt quando os três serviços
   estiverem saudáveis, use `docker compose up --build -d --wait`.

4. Confirme que a API responde. O `status` geral deve ser `UP`:

   PowerShell:

   ```powershell
   curl.exe -s -w "\nHTTP %{http_code}\n" http://localhost:8080/actuator/health
   ```

   Linux, macOS e Git Bash:

   ```bash
   curl -s -w '\nHTTP %{http_code}\n' http://localhost:8080/actuator/health
   ```

   ```text
   {
     "components": {
       "db": { "status": "UP" },
       "diskSpace": { "status": "UP" },
       "livenessState": { "status": "UP" },
       "ping": { "status": "UP" },
       "readinessState": { "status": "UP" },
       "redis": { "status": "UP" },
       "ssl": { "status": "UP" }
     },
     "groups": [
       "liveness",
       "readiness"
     ],
     "status": "UP"
   }
   HTTP 200
   ```

5. Para parar e apagar também o volume do banco, execute:

   ```bash
   docker compose down -v
   ```

Se a porta 8080 do seu computador estiver ocupada, defina `APP_PORT` no `.env`
(por exemplo, `APP_PORT=8081`) e ajuste as URLs dos exemplos. O PostgreSQL
(`5432`) e o Redis (`6379`) publicam portas só em `127.0.0.1`, para a execução
pelo IDE; se alguma estiver ocupada, defina `POSTGRES_PORT` ou `REDIS_PORT` no
`.env` (para o PostgreSQL, veja também [Executar localmente](#executar-localmente)).
Para consultar o banco, use o container. Ao iniciar, o Flyway aplica a migração
`V1__checkout.sql` e o Hibernate valida o schema (`ddl-auto=validate`). Para
conferir a migração:

**PowerShell** (a crase antes do `$` impede que o PowerShell expanda a variável;
quem a expande é o `sh` dentro do container):

```powershell
docker compose exec -T postgres sh -c "psql -U `$POSTGRES_USER -d `$POSTGRES_DB -c 'select version, script, success from flyway_schema_history'"
```

**Linux, macOS e Git Bash**:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, script, success from flyway_schema_history"'
```

```text
 version |      script      | success
---------+------------------+---------
 1       | V1__checkout.sql | t
(1 row)
```

A imagem usa build em duas etapas (`eclipse-temurin:25-jdk` compila e
`eclipse-temurin:25-jre` executa), roda como usuário sem privilégios de root e
separa o jar em camadas. O `HEALTHCHECK` consulta
`/actuator/health/liveness`, e não o health geral: com o Redis fora, o geral
fica `DOWN`, mas a API continua atendendo (veja [Cache](#cache)).

## Executar localmente

Fora do Docker, a API precisa de PostgreSQL e Redis. O caminho mais curto usa
o `TestNexoApplication`, que sobe os dois com Testcontainers (o Docker precisa
estar ativo):

**PowerShell** (os argumentos `-D` com ponto vão entre aspas):

```powershell
.\mvnw.cmd spring-boot:test-run "-Dspring-boot.run.arguments=--server.port=8081"
```

**Linux, macOS e Git Bash**:

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.arguments=--server.port=8081
```

O argumento `--server.port` é opcional (sem ele, a porta é a 8080). Use-o se o
compose já estiver ocupando a 8080; os endereços dos serviços simulados seguem
a porta escolhida. Para parar, encerre o processo (`Ctrl+C`); o Testcontainers
remove os containers do PostgreSQL e do Redis.

Para rodar a classe `NexoApplication` com o PostgreSQL e o Redis do compose
(sem container para a API), suba só os dois e execute o Maven na pasta `nexo/`:

PowerShell:

```powershell
docker compose up -d --wait postgres redis
.\mvnw.cmd spring-boot:run
```

Linux, macOS e Git Bash:

```bash
docker compose up -d --wait postgres redis
./mvnw spring-boot:run
```

A API lê `POSTGRES_URL`, `POSTGRES_USER` e `POSTGRES_PASSWORD` do `.env` da
pasta de trabalho (`spring.config.import` em `application.yaml`); o Spring Boot
não lê `.env` sozinho, então, no IDE, a pasta de trabalho da execução deve ser
`nexo/`. `POSTGRES_URL` é a URL JDBC do banco visto do seu computador, por
exemplo `jdbc:postgresql://localhost:5432/nexo` (é o valor do `.env.example`).
Se mudar `REDIS_PORT`, defina também `SPRING_DATA_REDIS_PORT`. Variáveis de
ambiente do sistema têm precedência sobre o `.env`.

`POSTGRES_URL` vale só fora do Docker. O serviço `app` do compose ignora essa
variável e usa `jdbc:postgresql://postgres:5432/<POSTGRES_DB>`, o nome do
serviço na rede do compose e a porta interna. O host `postgres` não resolve no
IDE: uma `POSTGRES_URL` com ele falha com `UnknownHostException: postgres`.

Para usar o seu próprio PostgreSQL e Redis, aponte `POSTGRES_URL`,
`POSTGRES_USER` e `POSTGRES_PASSWORD` para ele (e defina `SPRING_DATA_REDIS_HOST`
se for preciso). Sem `.env` e sem as variáveis, a URL, o usuário e a senha ficam
vazios e a API não inicia (`Failed to determine a suitable driver class`).

### Quando a porta 5432 do computador já está em uso

Se outro PostgreSQL ocupa a 5432, o compose não consegue publicá-la, e uma
`POSTGRES_URL` em `localhost:5432` faria a API falar com o outro banco. Publique
o container em outra porta do host; a porta interna do container continua 5432.
No `.env`, mude as duas variáveis para a mesma porta (por exemplo, `5433`):

```text
POSTGRES_PORT=5433
POSTGRES_URL=jdbc:postgresql://localhost:5433/nexo
```

`POSTGRES_PORT` é a porta publicada no host pelo compose; `POSTGRES_URL` é a
que a API usa. A API não deriva uma da outra, então as duas precisam coincidir.
Depois, recrie o container: `docker compose up -d --wait postgres redis`.

## Testes

No Windows, use `.\mvnw.cmd` no lugar de `./mvnw` nos comandos abaixo.

- `./mvnw test` executa os testes `*Test` (unitários e de camada web, com
  Mockito). Não precisa de Docker.
- `./mvnw verify` executa também os testes `*IT`, que usam Testcontainers com
  `postgres:18.6` e `redis:8.8.4` (o compose usa o PostgreSQL 16.9, então os
  testes e a execução local rodam em versões diferentes). O Docker precisa
  estar ativo. O relatório de
  cobertura do JaCoCo fica em `target/site/jacoco/index.html`.
- `./mvnw verify -DskipITs` compila, executa os `*Test` e empacota sem Docker.

Os testes de integração compartilham um único contexto Spring e um único par de
containers. A exceção é o teste do cache desligado (`CacheDisabledIT`), que
sobe um contexto próprio, só com PostgreSQL.

## Exemplos com curl

Os comandos abaixo foram executados nesta ordem contra a API no Docker
(`docker compose up --build -d --wait`). O corpo real vem em uma única linha;
aqui ele aparece indentado para leitura, sem alterar nenhum valor. A linha
`HTTP <status>` vem do `-w` do `curl`.

Alguns valores mudam a cada execução: `cartId` e `paidAt`.

### Antes de começar: o terminal

Cada exemplo tem uma variante para **PowerShell** (Windows) e outra para
**Linux, macOS e Git Bash** (bash). Os comandos de PowerShell ficam em uma linha
só, para não depender de continuação de linha ao colar. O que muda de um
terminal para outro:

| | PowerShell | bash |
| --- | --- | --- |
| Programa | `curl.exe` (`curl` é alias de `Invoke-WebRequest`) | `curl` |
| Continuação de linha | `` ` `` (crase) no fim da linha | `\` |
| Corpo JSON | here-string `@"..."@`, pela função `Invoke-Nexo` | heredoc `<<EOF` |
| Variável | `$cartId = '...'` | `CART_ID=...` |

**PowerShell.** Cole este bloco inteiro, uma vez por terminal aberto. Ele define
a função `Invoke-Nexo <caminho> [<corpo JSON>]`, que faz um `GET` quando não há
corpo e um `POST` com `Content-Type: application/json` quando há:

```powershell
$OutputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
function Invoke-Nexo([string]$Path, [string]$Body, [string]$Base = 'http://localhost:8080') {
    if ($Body) {
        $Body | curl.exe -s -w "`nHTTP %{http_code}`n" -X POST "$Base$Path" -H "Content-Type: application/json" --data-binary "@-"
    } else {
        curl.exe -s -w "`nHTTP %{http_code}`n" "$Base$Path"
    }
}
```

As duas primeiras linhas deixam a entrada e a saída em UTF-8: o Windows
PowerShell 5.1 usa ASCII por padrão e corromperia `Cartão de Crédito` (a API
responderia `422 PAYMENT_METHOD_NOT_ALLOWED`). Se a API estiver em outra porta,
acrescente `-Base http://localhost:8081` ao comando.

**bash.** No Git Bash do Windows, o `curl.exe` corrompe caracteres não ASCII
passados em `-d '...'` (a API responde `400 INVALID_REQUEST`). Por isso os
exemplos de checkout, que enviam `Cartão de Crédito`, usam `--data-binary @-`
com um bloco `<<EOF`, que funciona em qualquer shell POSIX. O pré-checkout só
tem caracteres ASCII e usa `-d`.

### Pré-checkout

Monta o carrinho com o plano `premium` e responde `201`. Guarde o `cartId`
para os próximos passos:

**PowerShell** (com a função `Invoke-Nexo` definida acima):

```powershell
Invoke-Nexo /operations/pre-checkout '{"products":[{"slug":"premium"}]}'
```

**Linux, macOS e Git Bash**:

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8080/operations/pre-checkout \
  -H 'Content-Type: application/json' \
  -d '{"products":[{"slug":"premium"}]}'
```

```text
{
  "cartId": "bf9ad4b8-aceb-4165-a69c-d698471bdfa7",
  "products": [
    {
      "slug": "premium",
      "name": "Plano Premium",
      "description": "Assinatura mensal com catálogo completo",
      "price": 39.90,
      "currency": "BRL"
    }
  ],
  "paymentMethods": [
    { "name": "Cartão de Crédito" },
    { "name": "Cartão de Débito" },
    { "name": "Pix" }
  ]
}
HTTP 201
```

Você pode enviar vários planos (`basico`, `premium`, `familia`); os produtos
voltam na mesma ordem e o total do carrinho é a soma dos preços. Slugs
repetidos ou inválidos respondem `400`.

### Checkout aprovado com cartão

Troque o `cartId` pelo da sua resposta (`$cartId` no PowerShell, `CART_ID` no
bash). O cartão `4111111111114242` é o de exemplo. A API não valida o
dígito verificador (Luhn), então ele é aceito.

**PowerShell**:

```powershell
$cartId = 'bf9ad4b8-aceb-4165-a69c-d698471bdfa7'
$corpo = @"
{
  "cartId": "$cartId",
  "paymentMethod": {
    "name": "Cartão de Crédito",
    "card": {
      "holderName": "Ana Souza",
      "number": "4111111111114242",
      "expiry": "12/28",
      "cvv": "123"
    }
  }
}
"@
Invoke-Nexo /operations/checkout $corpo
```

**Linux, macOS e Git Bash**:

```bash
CART_ID=bf9ad4b8-aceb-4165-a69c-d698471bdfa7
```

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8080/operations/checkout \
  -H 'Content-Type: application/json' --data-binary @- <<EOF
{
  "cartId": "$CART_ID",
  "paymentMethod": {
    "name": "Cartão de Crédito",
    "card": {
      "holderName": "Ana Souza",
      "number": "4111111111114242",
      "expiry": "12/28",
      "cvv": "123"
    }
  }
}
EOF
```

```text
{
  "cartId": "bf9ad4b8-aceb-4165-a69c-d698471bdfa7",
  "status": "APROVADO",
  "paidAt": "2026-10-29T04:05:18Z",
  "payment": {
    "method": "Cartão de Crédito",
    "lastDigits": "4242",
    "amount": 39.90,
    "currency": "BRL"
  },
  "products": [
    { "slug": "premium", "name": "Plano Premium", "price": 39.90 }
  ]
}
HTTP 200
```

A resposta traz só os quatro últimos dígitos (`lastDigits`). A API não grava
nem devolve o número completo, o CVV, o titular nem a validade.

### Pagamento duplicado

Repita o mesmo comando no carrinho que já foi pago. A API responde `409` e não
cobra de novo:

**PowerShell** (`$corpo` ainda guarda o JSON do passo anterior):

```powershell
Invoke-Nexo /operations/checkout $corpo
```

**Linux, macOS e Git Bash**:

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8080/operations/checkout \
  -H 'Content-Type: application/json' --data-binary @- <<EOF
{
  "cartId": "$CART_ID",
  "paymentMethod": {
    "name": "Cartão de Crédito",
    "card": {
      "holderName": "Ana Souza",
      "number": "4111111111114242",
      "expiry": "12/28",
      "cvv": "123"
    }
  }
}
EOF
```

```text
{
  "displayType": "BLOCK",
  "errorCode": "CART_ALREADY_PAID",
  "cartId": "bf9ad4b8-aceb-4165-a69c-d698471bdfa7",
  "frontendError": {
    "code": "P-1004",
    "title": "Pagamento já confirmado",
    "description": "Este pedido já foi pago. Não é possível pagar novamente.",
    "primaryButton": { "label": "Voltar ao início", "url": "RESTART" },
    "secondaryButton": null
  }
}
HTTP 409
```

### Checkout aprovado com Pix

O Pix não leva cartão. Crie um carrinho novo (pré-checkout, como acima) e use o
`cartId` dele:

**PowerShell**:

```powershell
Invoke-Nexo /operations/pre-checkout '{"products":[{"slug":"premium"}]}'
```

**Linux, macOS e Git Bash**:

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8080/operations/pre-checkout \
  -H 'Content-Type: application/json' \
  -d '{"products":[{"slug":"premium"}]}'
```

```text
{
  "cartId": "9ea0e741-24e6-4d13-a120-89afaa793ea9",
  "products": [
    {
      "slug": "premium",
      "name": "Plano Premium",
      "description": "Assinatura mensal com catálogo completo",
      "price": 39.90,
      "currency": "BRL"
    }
  ],
  "paymentMethods": [
    { "name": "Cartão de Crédito" },
    { "name": "Cartão de Débito" },
    { "name": "Pix" }
  ]
}
HTTP 201
```

**PowerShell**:

```powershell
$cartId = '9ea0e741-24e6-4d13-a120-89afaa793ea9'
$corpo = @"
{
  "cartId": "$cartId",
  "paymentMethod": { "name": "Pix" }
}
"@
Invoke-Nexo /operations/checkout $corpo
```

**Linux, macOS e Git Bash**:

```bash
CART_ID=9ea0e741-24e6-4d13-a120-89afaa793ea9
```

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8080/operations/checkout \
  -H 'Content-Type: application/json' --data-binary @- <<EOF
{
  "cartId": "$CART_ID",
  "paymentMethod": { "name": "Pix" }
}
EOF
```

```text
{
  "cartId": "9ea0e741-24e6-4d13-a120-89afaa793ea9",
  "status": "APROVADO",
  "paidAt": "2026-10-29T04:05:19Z",
  "payment": {
    "method": "Pix",
    "pixCode": "PIX-SIMULADO-9ea0e741-24e6-4d13-a120-89afaa793ea9",
    "amount": 39.90,
    "currency": "BRL"
  },
  "products": [
    { "slug": "premium", "name": "Plano Premium", "price": 39.90 }
  ]
}
HTTP 200
```

No Pix, `payment` traz `pixCode` no lugar de `lastDigits`.

### Pagamento recusado (cartão com final 0002)

Qualquer cartão de crédito ou débito cujo número termine em `0002` é recusado.
Crie um terceiro carrinho:

**PowerShell**:

```powershell
Invoke-Nexo /operations/pre-checkout '{"products":[{"slug":"premium"}]}'
```

**Linux, macOS e Git Bash**:

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8080/operations/pre-checkout \
  -H 'Content-Type: application/json' \
  -d '{"products":[{"slug":"premium"}]}'
```

```text
{
  "cartId": "8f702521-e08a-4c83-84dc-4f832bcb769d",
  "products": [
    {
      "slug": "premium",
      "name": "Plano Premium",
      "description": "Assinatura mensal com catálogo completo",
      "price": 39.90,
      "currency": "BRL"
    }
  ],
  "paymentMethods": [
    { "name": "Cartão de Crédito" },
    { "name": "Cartão de Débito" },
    { "name": "Pix" }
  ]
}
HTTP 201
```

**PowerShell**:

```powershell
$cartId = '8f702521-e08a-4c83-84dc-4f832bcb769d'
$corpo = @"
{
  "cartId": "$cartId",
  "paymentMethod": {
    "name": "Cartão de Crédito",
    "card": {
      "holderName": "Ana Souza",
      "number": "4111111111110002",
      "expiry": "12/28",
      "cvv": "123"
    }
  }
}
"@
Invoke-Nexo /operations/checkout $corpo
```

**Linux, macOS e Git Bash**:

```bash
CART_ID=8f702521-e08a-4c83-84dc-4f832bcb769d
```

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8080/operations/checkout \
  -H 'Content-Type: application/json' --data-binary @- <<EOF
{
  "cartId": "$CART_ID",
  "paymentMethod": {
    "name": "Cartão de Crédito",
    "card": {
      "holderName": "Ana Souza",
      "number": "4111111111110002",
      "expiry": "12/28",
      "cvv": "123"
    }
  }
}
EOF
```

```text
{
  "displayType": "BLOCK",
  "errorCode": "PAYMENT_DECLINED",
  "cartId": "8f702521-e08a-4c83-84dc-4f832bcb769d",
  "frontendError": {
    "code": "P-1001",
    "title": "Pagamento não aprovado",
    "description": "Não foi possível concluir o pagamento. Tente novamente ou escolha outro meio.",
    "primaryButton": { "label": "Tentar novamente", "url": "RETRY" },
    "secondaryButton": { "label": "Trocar meio de pagamento", "url": "/checkout/pagamento" }
  }
}
HTTP 422
```

A recusa não bloqueia o carrinho: ele continua aberto e aceita outra tentativa.
O mesmo `CART_ID` com um cartão que não termina em `0002`:

**PowerShell**:

```powershell
$corpo = @"
{
  "cartId": "$cartId",
  "paymentMethod": {
    "name": "Cartão de Crédito",
    "card": {
      "holderName": "Ana Souza",
      "number": "4111111111114242",
      "expiry": "12/28",
      "cvv": "123"
    }
  }
}
"@
Invoke-Nexo /operations/checkout $corpo
```

**Linux, macOS e Git Bash**:

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8080/operations/checkout \
  -H 'Content-Type: application/json' --data-binary @- <<EOF
{
  "cartId": "$CART_ID",
  "paymentMethod": {
    "name": "Cartão de Crédito",
    "card": {
      "holderName": "Ana Souza",
      "number": "4111111111114242",
      "expiry": "12/28",
      "cvv": "123"
    }
  }
}
EOF
```

```text
{
  "cartId": "8f702521-e08a-4c83-84dc-4f832bcb769d",
  "status": "APROVADO",
  "paidAt": "2026-10-29T04:05:19Z",
  "payment": {
    "method": "Cartão de Crédito",
    "lastDigits": "4242",
    "amount": 39.90,
    "currency": "BRL"
  },
  "products": [
    { "slug": "premium", "name": "Plano Premium", "price": 39.90 }
  ]
}
HTTP 200
```

### Carrinho inexistente

Um `cartId` válido que não existe responde `404`:

**PowerShell**:

```powershell
Invoke-Nexo /operations/checkout '{"cartId":"00000000-0000-4000-8000-000000000000","paymentMethod":{"name":"Pix"}}'
```

**Linux, macOS e Git Bash**:

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8080/operations/checkout \
  -H 'Content-Type: application/json' --data-binary @- <<'EOF'
{
  "cartId": "00000000-0000-4000-8000-000000000000",
  "paymentMethod": { "name": "Pix" }
}
EOF
```

```text
{
  "displayType": "BLOCK",
  "errorCode": "CART_NOT_FOUND",
  "cartId": "00000000-0000-4000-8000-000000000000",
  "frontendError": {
    "code": "C-1002",
    "title": "Carrinho não encontrado",
    "description": "Não encontramos este carrinho. Recomece o pedido para continuar.",
    "primaryButton": { "label": "Recomeçar", "url": "RESTART" },
    "secondaryButton": null
  }
}
HTTP 404
```

### Produto inexistente

Um slug que não está no catálogo responde `422` e não cria carrinho:

**PowerShell**:

```powershell
Invoke-Nexo /operations/pre-checkout '{"products":[{"slug":"inexistente"}]}'
```

**Linux, macOS e Git Bash**:

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST http://localhost:8080/operations/pre-checkout \
  -H 'Content-Type: application/json' \
  -d '{"products":[{"slug":"inexistente"}]}'
```

```text
{
  "displayType": "BLOCK",
  "errorCode": "PRODUCT_NOT_FOUND",
  "cartId": null,
  "frontendError": {
    "code": "C-1001",
    "title": "Plano indisponível",
    "description": "Um dos planos escolhidos não está disponível. Escolha outro plano para continuar.",
    "primaryButton": { "label": "Escolher outro plano", "url": "RESTART" },
    "secondaryButton": null
  }
}
HTTP 422
```

## Swagger UI e OpenAPI

Abra [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
no navegador. O caminho redireciona para a página da UI:

PowerShell:

```powershell
curl.exe -s -o NUL -w "HTTP %{http_code} -> %{redirect_url}\n" http://localhost:8080/swagger-ui.html
```

Linux, macOS e Git Bash:

```bash
curl -s -o /dev/null -w 'HTTP %{http_code} -> %{redirect_url}\n' http://localhost:8080/swagger-ui.html
```

```text
HTTP 302 -> http://localhost:8080/swagger-ui/index.html
```

A UI tem dois grupos, que também estão disponíveis como JSON:

- `bff`, em `/v3/api-docs/bff`: as duas operações de `/operations/**`, com
  exemplos de sucesso (pré-checkout, checkout com cartão e checkout com Pix) e
  um exemplo por `errorCode`. Os exemplos de erro são gerados a partir do mesmo
  catálogo que monta as respostas reais.
- `mock-services`, em `/v3/api-docs/mock-services`: os serviços externos
  simulados.

O OpenAPI é gerado na versão 3.1, em que "anulável" é o tipo `null`. No schema
de erro, `cartId` aparece como `"type": ["string", "null"]` e
`secondaryButton` como um `oneOf` entre `ErrorButton` e `{"type": "null"}`.

## Serviços externos simulados

O BFF não conhece a origem de produtos e meios de pagamento. A regra de
checkout usa só as interfaces `ProductCatalog` e `PaymentMethodCatalog`; quem as
implementa são clientes HTTP (`RestClient`) no pacote `integration`. Para
rodar sem dependências externas, a própria aplicação serve os dois "serviços"
em `/mock-services/**` e os consome por HTTP, como faria com um serviço real.
No PowerShell, os dois `GET` abaixo são:

```powershell
curl.exe -s -w "\nHTTP %{http_code}\n" http://localhost:8080/mock-services/products
curl.exe -s -w "\nHTTP %{http_code}\n" http://localhost:8080/mock-services/payment-methods
```

Em Linux, macOS e Git Bash:

```bash
curl -s -w '\nHTTP %{http_code}\n' http://localhost:8080/mock-services/products
```

```text
[
  {
    "slug": "basico",
    "name": "Plano Básico",
    "description": "Assinatura mensal com o essencial do catálogo",
    "price": 19.90,
    "currency": "BRL"
  },
  {
    "slug": "premium",
    "name": "Plano Premium",
    "description": "Assinatura mensal com catálogo completo",
    "price": 39.90,
    "currency": "BRL"
  },
  {
    "slug": "familia",
    "name": "Plano Família",
    "description": "Assinatura mensal para até 4 perfis",
    "price": 59.90,
    "currency": "BRL"
  }
]
HTTP 200
```

```bash
curl -s -w '\nHTTP %{http_code}\n' http://localhost:8080/mock-services/payment-methods
```

```text
[
  { "code": "CREDIT_CARD", "name": "Cartão de Crédito", "requiresCard": true },
  { "code": "DEBIT_CARD", "name": "Cartão de Débito", "requiresCard": true },
  { "code": "PIX", "name": "Pix", "requiresCard": false }
]
HTTP 200
```

Os endpoints existem porque o profile `mock-services` vem ligado em
`application.yaml` (`spring.profiles.active`). Se você definir
`SPRING_PROFILES_ACTIVE`, esse valor substitui o do arquivo e desliga os
mocks, a menos que inclua `mock-services` na lista.

### Trocar a base-url

Os endereços dos serviços vêm de `app.services.products.base-url` e
`app.services.payment-methods.base-url` (padrão: `http://localhost:` seguido da
porta da aplicação). Para apontar para outro serviço, defina a propriedade ou
a variável de ambiente equivalente, sem mudar código:

```bash
APP_SERVICES_PRODUCTS_BASE_URL=https://catalogo.exemplo.com.br
APP_SERVICES_PAYMENT_METHODS_BASE_URL=https://pagamentos.exemplo.com.br
```

Os tempos limite são 1 s para conectar e 2 s para ler a resposta
(`app.services.connect-timeout` e `app.services.read-timeout`). Serviço fora do
ar, resposta `5xx`, tempo esgotado ou corpo fora do contrato resultam em
`503 SERVICE_UNAVAILABLE` no pré-checkout, sem criar carrinho.

Esse comportamento foi verificado com um segundo container da API que apontava
cada uma das duas variáveis, uma de cada vez, para uma porta fechada, com o
cache vazio: nos dois casos o pré-checkout respondeu `503 SERVICE_UNAVAILABLE`.
Se o catálogo já estiver no cache (veja [Cache](#cache)), a mudança só vale
depois que a entrada expira.

## Tabela de erros

Toda falha, de negócio ou técnica, responde o mesmo objeto, com as chaves
`displayType`, `errorCode`, `cartId` e `frontendError`. `cartId` e
`frontendError.secondaryButton` estão sempre presentes e valem `null` quando
não se aplicam. No checkout, `cartId` repete o da requisição quando ele é um
UUID válido; nos demais casos vale `null`.

| `errorCode` | HTTP | `displayType` | `code` | Título | Botão principal |
| --- | --- | --- | --- | --- | --- |
| `INVALID_REQUEST` | 400 | `BLOCK` | R-1001 | Não foi possível processar o pedido | Tentar novamente (`RETRY`) |
| `PRODUCT_NOT_FOUND` | 422 | `BLOCK` | C-1001 | Plano indisponível | Escolher outro plano (`RESTART`) |
| `CART_NOT_FOUND` | 404 | `BLOCK` | C-1002 | Carrinho não encontrado | Recomeçar (`RESTART`) |
| `PAYMENT_METHOD_NOT_ALLOWED` | 422 | `INTERVENTION` | P-1003 | Meio de pagamento indisponível | Escolher outro meio (`/checkout/pagamento`) |
| `INVALID_PAYMENT_DATA` | 422 | `INTERVENTION` | P-1002 | Confira os dados do pagamento | Corrigir dados (`DISMISS`) |
| `PAYMENT_DECLINED` | 422 | `BLOCK` | P-1001 | Pagamento não aprovado | Tentar novamente (`RETRY`) |
| `CART_ALREADY_PAID` | 409 | `BLOCK` | P-1004 | Pagamento já confirmado | Voltar ao início (`RESTART`) |
| `SERVICE_UNAVAILABLE` | 503 | `BLOCK` | S-1001 | Serviço temporariamente indisponível | Tentar novamente (`RETRY`) |
| `INTERNAL_ERROR` | 500 | `BLOCK` | S-1002 | Algo deu errado | Tentar novamente (`RETRY`) |

Só `PAYMENT_DECLINED` tem botão secundário: Trocar meio de pagamento
(`/checkout/pagamento`). A rota `/checkout/pagamento` é configurável em
`app.frontend.payment-route`.

O Spring MVC também responde nesse formato: JSON malformado (`400`), tipo de
conteúdo não suportado (`415`), método não permitido (`405`), rota inexistente
(`404`) e `Accept` incompatível (`406`) viram `INVALID_REQUEST` com o status
original. Uma exceção inesperada vira `500 INTERNAL_ERROR`, sem stack trace nem
mensagem no corpo; o detalhe fica só no log.

O que o próprio container recusa e despacha para `/error` (por exemplo
`GET /WEB-INF/x`) também responde nesse formato, com o status original: 4xx vira
`INVALID_REQUEST` e 5xx vira `INTERNAL_ERROR`. `GET /error` aberto direto, sem
erro despachado, responde `404 INVALID_REQUEST`.

Quando mais de uma condição de erro vale ao mesmo tempo, a API reporta a
primeira desta ordem: corpo inválido, carrinho inexistente, carrinho já pago,
meio não permitido, dados do pagamento inválidos e, por último, cobrança.

## Decisões

### Sem validação de Luhn

O cartão de exemplo do enunciado (`4111111111114242`) falha no algoritmo de
Luhn. Para aceitá-lo, a API valida só o formato: número com 13 a 19 dígitos,
validade `MM/AA` que não esteja vencida (fuso UTC), CVV com 3 ou 4 dígitos e
titular com 1 a 50 caracteres.

### Mensageria avaliada e não adotada

Foram avaliados RabbitMQ e Kafka e nenhum foi adotado. O contrato é síncrono, os
dados do cartão passariam pelo broker e não há carga a absorver. Se surgirem
efeitos depois do pagamento (por exemplo, e-mail), a decisão é usar um outbox
transacional, fora do caminho da autorização.

### Cache

O catálogo de produtos e de meios de pagamento fica no Redis, inteiro, com as
chaves `catalog-products::all` e `catalog-payment-methods::all` e TTL de 10
minutos (`app.cache.ttl`). Dois pré-checkouts dentro do TTL chamam cada serviço
uma única vez. O filtro por slug é feito em memória.

**Por que existe.** Cada pré-checkout precisa do catálogo de produtos e dos meios
de pagamento, e os dois vêm de serviços externos por HTTP, com tempo limite de 1 s
para conectar e 2 s para ler (veja [Trocar a base-url](#trocar-a-base-url)). Sem
cache, cada carrinho novo faria duas chamadas externas, e a latência e a
disponibilidade do pré-checkout passariam a depender delas. Com o catálogo no
Redis, só a primeira chamada de cada janela de 10 minutos vai ao serviço. A
premissa é que o catálogo muda pouco nesse intervalo: se mudar, a API só enxerga a
alteração quando a entrada expira, e um preço antigo no cache entra nos carrinhos
criados até lá. Depois de criado, o carrinho guarda o preço e os meios de
pagamento do momento do pré-checkout
([CartItem.java:16](src/main/java/com/checkout/nexo/checkout/domain/CartItem.java:16),
[Cart.java:64](src/main/java/com/checkout/nexo/checkout/domain/Cart.java:64)), e o
checkout cobra desse registro, sem consultar catálogo nem Redis
([CheckoutService.java:26](src/main/java/com/checkout/nexo/checkout/application/CheckoutService.java:26)).

**Onde é usado.** Em dois métodos anotados com `@Cacheable`:

| Cache | Chave no Redis | Método |
| --- | --- | --- |
| `catalog-products` | `catalog-products::all` | `ProductServiceClient.findAll()` ([ProductServiceClient.java:32](src/main/java/com/checkout/nexo/integration/products/ProductServiceClient.java:32)) |
| `catalog-payment-methods` | `catalog-payment-methods::all` | `PaymentMethodServiceClient.findAll()` ([PaymentMethodServiceClient.java:32](src/main/java/com/checkout/nexo/integration/paymentmethods/PaymentMethodServiceClient.java:32)) |

**Cenário.** Só o `POST /operations/pre-checkout` passa pelo cache: o
`PreCheckoutService` é o único que consulta os catálogos
([PreCheckoutService.java:58](src/main/java/com/checkout/nexo/checkout/application/PreCheckoutService.java:58)).
Ficam fora dele o `POST /operations/checkout` (lê só o carrinho no PostgreSQL), o
próprio banco e os endpoints `/mock-services/**`. O TTL (`app.cache.ttl`), o
serializer JSON, a escrita imediata e o tratamento de falha do Redis estão em
[CacheConfig.java](src/main/java/com/checkout/nexo/integration/config/CacheConfig.java).

- Falha de serviço externo não é cacheada: a próxima chamada, depois que o
  serviço volta, consulta o serviço de novo.
- Com o Redis fora do ar, a API registra um `WARN`, chama o serviço externo e
  responde o pré-checkout normalmente (`201`). Os tempos limite do Redis são de
  500 ms (`spring.data.redis.timeout` e `connect-timeout`). Nesse caso o health
  geral fica `DOWN`, mas a liveness continua `UP`.
- Com `spring.cache.type=none`, o cache é desligado, cada pré-checkout chama os
  serviços e a API inicia sem Redis.

#### Como observar o cache

A API não registra "cache hit"; a prova está nas chaves e no TTL do Redis. Os
comandos de `docker compose` e `redis-cli` abaixo são iguais no PowerShell e no
bash. Para os pré-checkouts, use o comando de [Pré-checkout](#pré-checkout) da
sua variante. Saídas observadas em uma execução com a API no Docker:

1. Remova as duas entradas. O `ttl` devolve `-2` quando a chave não existe:

   ```powershell
   docker compose exec -T redis redis-cli unlink catalog-products::all catalog-payment-methods::all
   docker compose exec -T redis redis-cli ttl catalog-products::all
   ```

   ```text
   2
   -2
   ```

2. Faça um pré-checkout. Foi um *miss*: a API chamou os dois serviços e gravou as
   entradas, com TTL de 600 s (10 minutos):

   ```powershell
   docker compose exec -T redis redis-cli ttl catalog-products::all
   ```

   ```text
   600
   ```

3. Repita o pré-checkout alguns segundos depois e consulte o TTL de novo. Ele
   continuou a contar (aqui, `595`, 4 s depois), em vez de voltar a 600: a entrada
   não foi regravada, ou seja, o catálogo veio do cache. `--scan` lista as duas
   chaves:

   ```powershell
   docker compose exec -T redis redis-cli ttl catalog-products::all
   docker compose exec -T redis redis-cli --scan
   ```

   ```text
   595
   catalog-payment-methods::all
   catalog-products::all
   ```

4. Para ver a falha do Redis, pare-o e faça um pré-checkout. Ele continua
   respondendo `201`, mas mais devagar, porque cada operação de cache espera até
   500 ms (nesta execução, 2,1 s). O health geral responde `503` e a liveness
   `200`. O `WARN` mostra a operação e o tipo da exceção, nunca a chave nem o
   valor:

   ```powershell
   docker compose stop redis
   docker compose logs app --since 2m | Select-String indisponivel
   docker compose start redis
   ```

   ```text
   WARN ... CacheConfig : Cache 'catalog-products' indisponivel na operacao get (QueryTimeoutException); seguindo sem cache
   WARN ... CacheConfig : Cache 'catalog-products' indisponivel na operacao put (QueryTimeoutException); seguindo sem cache
   ```

   No bash, troque o filtro por `grep indisponivel`.

Os testes `CacheIT`
([CacheIT.java:102](src/test/java/com/checkout/nexo/integration/CacheIT.java:102))
e `CacheDisabledIT`
([CacheDisabledIT.java:84](src/test/java/com/checkout/nexo/integration/CacheDisabledIT.java:84))
automatizam esse comportamento, contando as chamadas HTTP que chegam a
`/mock-services/**`; eles não foram executados nesta sessão.

### Falhas técnicas respondem 5xx

O enunciado pede `4xx` para toda falha que o frontend precisa exibir. Este
projeto desvia disso nas falhas técnicas: serviço externo fora do ar responde
`503` e erro inesperado responde `500`, no mesmo objeto de erro. Um `4xx` para
uma falha do servidor indicaria uma causa errada ao cliente e esconderia o
problema em métricas e alertas. Mudar para `4xx` é trocar uma coluna do
catálogo de erros (`ErrorCatalog`). As falhas de negócio continuam `4xx`.

### Recusa responde 422

`PAYMENT_DECLINED` usa `422`. A recusa não bloqueia o carrinho: ele segue
aberto e aceita novas tentativas, e cada tentativa fica registrada.

### Pagamento único por carrinho

O checkout trava a linha do carrinho (`select ... for update`) dentro de uma
transação, e um índice único parcial no banco
(`payment_attempt(cart_id) where status = 'APPROVED'`) impede uma segunda
tentativa aprovada mesmo fora da aplicação. Se o carrinho já foi pago, a API
responde `409`. O teste de concorrência dispara dois checkouts simultâneos em 20
carrinhos e confere que exatamente um responde `200`.

### Dados de cartão

O número completo, o CVV, o titular e a validade não são gravados no banco, não
aparecem em log nem em nenhuma resposta; só os quatro últimos dígitos
(`lastDigits`). As mensagens de erro e o log não repetem valores rejeitados.
Os testes de integração varrem todas as colunas de texto do banco, o log
capturado e os corpos de resposta atrás dos valores usados.

### Camadas

O código fica em quatro pacotes sob `com.checkout.nexo`:

- `bff`: controllers, DTOs, contrato de erro, CORS e OpenAPI.
- `checkout`: regra de negócio, entidades, repositórios e as portas
  (`ProductCatalog`, `PaymentMethodCatalog`, `PaymentProcessor`).
- `integration`: clientes HTTP, cache, processador de pagamento simulado e
  configuração.
- `mockservices`: os serviços externos simulados.

`bff` e `integration` dependem de `checkout`, nunca o contrário, e só
`mockservices` conhece `mockservices`. O `ArchitectureTest` falha se uma dessas
regras for violada.

### CORS

Apenas `/operations/**` aceita chamadas do navegador, e só da origem
`http://localhost:4200` (o frontend Angular local). Para mudar, defina
`app.cors.allowed-origins` com uma lista separada por vírgulas.

## O que ficou de fora

- Gateway real, tokenização, 3DS e antifraude: a cobrança é simulada.
- Mensageria, eventos e outbox (veja [Decisões](#decisões)).
- Autenticação do cliente e limite de requisições.
- Cabeçalho `Idempotency-Key` no checkout: o `409` e o lock cobrem o pagamento
  duplicado pedido no enunciado.
- Expiração de carrinho e de cobrança Pix, e confirmação Pix assíncrona. Um
  carrinho aberto não expira.
- Circuit breaker e fallback para os serviços externos: o serviço simulado roda
  no mesmo processo, e o tempo limite com `503` basta.
- Outras recusas além do cartão com final `0002` (saldo, instabilidade, Pix
  recusado).
- Quantidade por produto, desconto, taxa e imposto: cada slug entra uma vez e o
  total é a soma dos preços.
- Consulta de carrinho (`GET`) e endpoints administrativos.
- Integração do frontend `checkout-nexo` com este BFF.
- Meta de cobertura mínima de testes e ArchUnit: a regra de dependência entre
  pacotes é testada por varredura de imports.

## Limitações e itens não executados

- Uma URI malformada, como `GET /%zz`, é rejeitada pelo Tomcat ao ler a
  requisição, antes de qualquer despacho de erro, e responde a página HTML `400`
  do Tomcat em vez do objeto de erro. Não passa pelo `/error`, então este
  projeto não a alcança.
- Os exemplos de sucesso do OpenAPI (`/v3/api-docs/bff`) trazem os preços como
  `39.9`; a API responde `39.90`. O gerador do OpenAPI lê o exemplo como número
  decimal comum e perde o zero final. Só o JSON foi conferido; a UI não foi
  aberta no navegador.
- `docker compose up --build` em primeiro plano (sem `-d`): não executado. A
  verificação usou `docker compose up --build -d --wait`, que sobe os mesmos
  serviços.
- Execução local com `docker compose up -d --wait postgres redis` e
  `./mvnw spring-boot:run`: executada com PostgreSQL 16.9, com `POSTGRES_PORT=5433`
  e `POSTGRES_URL` em `localhost:5433` e credenciais lidas só do `.env`, com
  `--spring.main.web-application-type=none` (o Flyway confirmou o schema e a
  aplicação subiu). Na porta padrão 5432, a execução anterior ao `POSTGRES_URL`
  também subiu, mas não foi repetida com ele. Sem essa opção, o health e os
  endpoints não foram chamados nessa configuração, e a execução pelo IntelliJ
  não foi executada.
- A stack completa com PostgreSQL 16.9 (serviço `app` em container): executada
  com `APP_PORT=8090 docker compose up --build -d --wait`; os três serviços
  ficaram `healthy`, o health respondeu `200` e os fluxos de
  [docs/teste-swagger-ui.md](docs/teste-swagger-ui.md) foram executados no
  Swagger UI. Na porta padrão 8080, não executado.
- Comandos de PowerShell: os blocos `powershell` de
  [Exemplos com curl](#exemplos-com-curl), extraídos deste arquivo, foram
  executados no Windows PowerShell 5.1 e no PowerShell 7, contra a API no
  Docker, e a sequência de status foi a documentada (`201`, `200`, `409`, `201`,
  `200`, `201`, `422`, `200`, `404`, `422`). Também foram executados nas duas
  versões o `psql` no container, o redirect do Swagger, os `GET` dos serviços
  simulados e `.\mvnw.cmd -v` com `JAVA_HOME` no JDK 25. Não foram executados:
  `Copy-Item`, o `-D` com aspas no `spring-boot:run` (só o repasse do argumento
  foi conferido, com outro programa) e a colagem interativa no console (os
  testes rodaram por arquivo ou por entrada padrão). Os exemplos de bash foram
  executados em Windows 11 com Git Bash.
- Em Linux e macOS, o `-d` com caracteres não ASCII deve funcionar, mas isso
  não foi executado.
