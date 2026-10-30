# syntax=docker/dockerfile:1

# ---- build: compila o jar e o separa em camadas ----------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# O mvnw pode chegar com fim de linha CRLF (checkout no Windows sem git); o /bin/sh do Linux nao o aceita.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

# Dependencias antes do codigo: a camada so e refeita quando o pom muda.
RUN ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q -DskipTests package \
    && mv target/nexo-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- runtime: so o JRE, sem root, camadas na ordem em que mudam menos ------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./

# A imagem JRE nao traz curl nem wget: o healthcheck fala HTTP/1.0 pelo /dev/tcp do bash.
# Usa a liveness (nao o health agregado), que continua UP com o Redis fora (RUN-02, design).
COPY --chmod=755 <<'SCRIPT' /usr/local/bin/healthcheck
#!/bin/bash
exec 3<>/dev/tcp/127.0.0.1/8080
printf 'GET /actuator/health/liveness HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
grep -q '"status":"UP"' <&3
SCRIPT

USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 CMD ["/usr/local/bin/healthcheck"]
ENTRYPOINT ["java", "-jar", "application.jar"]
