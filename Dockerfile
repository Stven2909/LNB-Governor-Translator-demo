# Worker LNB - Pagaduria Digital (PoC -> DEV)
#
# Version de base FIJA POR DIGEST: el codigo cambia y este archivo no se toca.
# Un upgrade de SO / JDK / JRE es un cambio deliberado de estos digests, no una
# consecuencia de modificar el codigo. Verificados contra Docker Hub (2026-10-06).
ARG JDK_IMAGE=eclipse-temurin:21.0.6_7-jdk-jammy@sha256:42cafd5432c206408a7dbbc2a1d9523cd6277a5e1f618c379877002afd1bc0e2
ARG JRE_IMAGE=eclipse-temurin:21.0.6_7-jre-jammy@sha256:9cad7458b94148830f9eb468bc58f2682b123b0d911bd44bc72e46844ad4b4d5

# ---------------------------------------------------------------------
# Etapa 1: build con el wrapper de Maven (fijado en 3.9.16 por
# .mvn/wrapper/maven-wrapper.properties). No se usa imagen Maven para que
# la version de Maven no pueda derivar de la de la imagen base.
# Las dependencias se resuelven ANTES de copiar src/ para que un cambio de
# codigo no re-pague la descarga de dependencias.
# ---------------------------------------------------------------------
FROM ${JDK_IMAGE} AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn ./.mvn
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests dependency:go-offline

COPY src ./src
# -DskipTests: las pruebas (mvn test / CI) no corren dentro de la imagen.
RUN ./mvnw -B -ntp -DskipTests package

# ---------------------------------------------------------------------
# Etapa 2: runtime con solo el JRE, usuario no-root.
# `*.jar` en lugar del nombre versionado: si cambian <version> o <artifactId>
# en pom.xml, este archivo sigue sirviendo el jar correcto.
# ---------------------------------------------------------------------
FROM ${JRE_IMAGE} AS runtime

RUN groupadd --system --gid 1001 lnb \
 && useradd --system --uid 1001 --gid lnb --create-home lnb

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar

# Cloud Run y las nubes usan el uid 1001 como convencion de no-root.
USER 1001

# Cloud Run inyecta PORT; el default local es 8080 (application.properties).
ENV PORT=8080
EXPOSE 8080

# TODO(jConnect): cuando Alex entregue el driver de Sybase, montar el JAR en
# /app/lib en runtime (-v). No se copia dentro de la imagen ni se versiona el
# JAR: pendiente de licencia, checksum y procedencia (rev. Carlos).

# No se declara HEALTHCHECK: la imagen JRE no trae curl/wget, Cloud Run usa las
# sondas ya configuradas en application.properties, y en la VM el health lo
# tira el host contra /actuator/health/readiness.
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
