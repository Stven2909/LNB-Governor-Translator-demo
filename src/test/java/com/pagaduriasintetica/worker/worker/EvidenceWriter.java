package com.pagaduriasintetica.worker.worker;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exporta la evidencia del harness E2E a target/demo/ (gitignored, regenerable):
 * - evidencia_casos.json : documento completo machine-readable.
 * - EVIDENCIA_POC_LNB.md : versión legible para el reporte (matriz + detalle por escenario).
 * - envelopes/*.json      : los envelopes reales usados (reutilizables para la demo live y Pub/Sub real).
 * - envelopes/live/*.json : subconjunto que puede POSTearse contra un jar real sin preparar el estado.
 *
 * Es un escritor de evidencia, NO un test: no afirma nada, solo vuelca lo que DemoEvidenceTest
 * registró en cada escenario, para que la corrida se pueda auditar y reproducir.
 */
final class EvidenceWriter {

    private static final Path DIR = Path.of("target", "demo");
    private static final Path ENVELOPES = DIR.resolve("envelopes");
    private static final Path LIVE = ENVELOPES.resolve("live");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private EvidenceWriter() {
    }

    /**
     * Genera todo el paquete de evidencia a partir de la lista de escenarios registrados.
     * Se llama desde @AfterAll (incluso si un escenario falla, la evidencia parcial se exporta).
     */
    static void escribir(List<Map<String, Object>> escenarios) throws Exception {
        Files.createDirectories(LIVE);

        // 1) Documento JSON completo: meta + resumen + detalle por escenario.
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("meta", meta());
        doc.put("resumen", resumen(escenarios));
        doc.put("escenarios", escenarios);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(DIR.resolve("evidencia_casos.json").toFile(), doc);

        // 2) Versión legible en Markdown (matriz + detalle) para pegar en el reporte.
        Files.writeString(DIR.resolve("EVIDENCIA_POC_LNB.md"), markdown(escenarios));

        // 3) Envelopes reutilizables: uno por escenario; los "liveSafe" además quedan en live/.
        for (Map<String, Object> ev : escenarios) {
            Object envelope = ev.get("envelopeJson");
            if (!(envelope instanceof String raw)) {
                continue; // escenarios sin envelope (p.ej. hasher) no generan archivo
            }
            String id = (String) ev.get("id");
            Files.writeString(ENVELOPES.resolve(id + ".json"), raw);
            if (Boolean.TRUE.equals(ev.get("liveSafe"))) {
                String liveName = (String) ev.get("liveName");
                Files.writeString(LIVE.resolve(liveName + ".json"), raw);
            }
        }
    }

    // Metadatos de la corrida: de qué contrato/catálogo/fixture proviene la evidencia y cuándo.
    private static Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("proyecto", "Pagaduría Digital - prueba vertical PoC (LNB)");
        m.put("contrato", "CONTRACT_PAYMENT_COMMITTED_V0.1");
        m.put("catalogo", "CATALOG_PAYMENT_COMMITTED_V0.1");
        m.put("fixture", "FIXTURE_SYNTHETIC_DEV.sql");
        m.put("stack", "Java 21 + Spring Boot 4.1.1 (Framework 7)");
        m.put("fecha", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
        m.put("origen", "corrida E2E generada por DemoEvidenceTest (mvn test)");
        return m;
    }

    // Contadores globales PASS/FAIL del harness, usados en el JSON y en el encabezado del MD.
    private static Map<String, Object> resumen(List<Map<String, Object>> escenarios) {
        long pass = escenarios.stream().filter(e -> "PASS".equals(e.get("resultado"))).count();
        long fail = escenarios.stream().filter(e -> !"PASS".equals(e.get("resultado"))).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", escenarios.size());
        m.put("pass", pass);
        m.put("fail", fail);
        return m;
    }

    // Versión legible: encabezado con resumen, tabla-matriz y una sección detallada por escenario.
    private static String markdown(List<Map<String, Object>> escenarios) {
        Map<String, Object> res = resumen(escenarios);
        StringBuilder sb = new StringBuilder();
        sb.append("# Evidencia — Prueba Vertical Pagaduría Digital (LNB)\n\n");
        sb.append("- **Proyecto:** Pagaduría Digital — prueba vertical PoC\n");
        sb.append("- **Contrato:** CONTRACT_PAYMENT_COMMITTED_V0.1 · **Catálogo:** CATALOG_PAYMENT_COMMITTED_V0.1\n");
        sb.append("- **Corrida:** ").append(meta().get("fecha")).append("\n");
        sb.append("- **Resultado:** ").append(res.get("pass")).append("/").append(res.get("total"))
                .append(" escenarios PASS\n\n");

        // Matriz compacta: una fila por escenario para ver el resultado de un vistazo.
        sb.append("## Matriz de escenarios\n\n");
        sb.append("| # | Caso/ref | Esperado | Obtenido | HTTP | Resultado |\n");
        sb.append("|---|----------|----------|----------|------|-----------|\n");
        for (Map<String, Object> ev : escenarios) {
            sb.append("| ").append(ev.get("id")).append(" | ")
                    .append(ev.getOrDefault("casoRef", "-")).append(" | ")
                    .append(ev.getOrDefault("esperadoStatus", "-")).append(" | ")
                    .append(ev.getOrDefault("obtenidoStatus", "-")).append(" | ")
                    .append(ev.getOrDefault("httpTexto", "-")).append(" | ")
                    .append(ev.get("resultado")).append(" |\n");
        }

        // Detalle por escenario: evento decodificado, hash, actores, estado final, DLQ y notas.
        for (Map<String, Object> ev : escenarios) {
            sb.append("\n## ").append(ev.get("id")).append(" — ").append(ev.get("titulo")).append("\n");
            sb.append("- **Caso/ref:** ").append(ev.getOrDefault("casoRef", "-")).append("\n");
            sb.append("- **Esperado:** ").append(ev.getOrDefault("esperadoStatus", "-"))
                    .append(" · **Obtenido:** ").append(ev.getOrDefault("obtenidoStatus", "-"))
                    .append(" · **HTTP:** ").append(ev.getOrDefault("httpTexto", "-")).append("\n");
            if (ev.get("evento") instanceof Map<?, ?> evento) {
                sb.append("- **Evento decodificado:** `").append(writeValueAsStringSilent(MAPPER, evento)).append("`\n");
            }
            sb.append("- **PAYLOAD_HASH:** ").append(ev.getOrDefault("payloadHash", "-")).append("\n");
            sb.append("- **Gobernador:** ").append(ev.getOrDefault("gobernador", "-")).append("\n");
            sb.append("- **Traductor:** ").append(ev.getOrDefault("traductor", "-")).append("\n");
            sb.append("- **Estado final (WORKER_OPERATION_STATE):** ")
                    .append(ev.getOrDefault("estadoFinal", "-")).append("\n");
            sb.append("- **Pago (SYNTHETIC_PAYMENTS):** ")
                    .append(Boolean.TRUE.equals(ev.get("paymentPersisted")) ? "persistido"
                            : "no persistido (no corresponde)").append("\n");
            sb.append("- **Cuarentena (DLQ):** ").append(ev.getOrDefault("cuarentena", List.of())).append("\n");
            if (ev.get("nota") != null) {
                sb.append("- **Nota:** ").append(ev.get("nota")).append("\n");
            }
            if (ev.get("detalle") != null) {
                sb.append("- **Detalle fallo:** ").append(ev.get("detalle")).append("\n");
            }
            sb.append("- **Resultado:** **").append(ev.get("resultado")).append("**\n");
        }
        return sb.toString();
    }

    // Serializa sin lanzar excepción: un fallo aquí no debe romper la exportación de evidencia.
    private static String writeValueAsStringSilent(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}