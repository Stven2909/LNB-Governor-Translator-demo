# Estado Actual del Proyecto — 24/10/2026

## Resumen Ejecutivo

El proyecto "Agentes y Vertex" para Pagaduría Digital ha evolucionado con un **re-scope** del 23/10/2026 de Jorge Daniel. La prioridad ahora es el **Agente de Consulta de Boleto (E3.2)** en lugar del Gobernador financiero.

---

## Cambios Principales

### ✅ E3.2 — Agente de Consulta de Boleto (NUEVA PRIORIDAD)

**Estado:** Implementado (D1/D2 planificados)

**Endpoint:** `POST /api/v1/agent/ticket-query`

**Características:**
- Function calling con Vertex AI (`gemini-2.5-flash`)
- Tool `consultar_boleto` obligatoria (mode=ANY)
- Anti-invención: el modelo NUNCA inventa premio/monto
- El WS de Carlos es el único source de verdad

**Archivos creados:**
- `src/main/java/com/pagaduriasintetica/worker/contract/ticket/` (6 DTOs)
- `src/main/java/com/pagaduriasintetica/worker/agent/` (Controller + Logic)
- `src/main/java/com/pagaduriasintetica/worker/agent/tool/` (3 implementaciones)
- `src/main/resources/prompts/ticket-agent-v0.1.txt`
- `src/main/resources/fixtures/tickets/*.json`
- `scripts/ticket-query.ps1`

---

## 📋 Estado por Frente

| Frente | Estado | Responsable | Próxima Fecha |
|--------|--------|-------------|---------------|
| E3.2 Agente boleto | ✅ D1 implementado | Steven | 27/10 |
| E3.0 Contrato mínimo | 🟡 Carlos define WS | Carlos | - |
| PAYMENT_COMMITTED V1 | ⚪ Backlog | Alex | - |
| Governor financiero | ⚪ Backlog | - | - |
| JDBC Sybase real | ⚪ Backlog | Equipo | - |

---

## 🔧 Configuración Nueva

```properties
# Ticket Agent (E3.2)
APP_TICKET_AGENT_MODE=FIXTURE|HTTP
APP_TICKET_AGENT_BASE_URL=http://localhost:8080
APP_TICKET_AGENT_LOOKUP_PATH=/api/v1/tickets/lookup
APP_TICKET_AGENT_TIMEOUT=10s
APP_TICKET_AGENT_PROVENZA=SYNTHETIC
```

---

## 🧪 Pruebas

| Caso | Estado | Resultado |
|------|--------|-----------|
| found | ✅ | HTTP 200, result=FOUND |
| not-found | ✅ | HTTP 200, result=NOT_FOUND |
| not-awarded | ✅ | HTTP 200, result=NOT_AWARDED |
| invalid | ✅ | HTTP 400, result=INVALID_INPUT |
| timeout | ✅ | HTTP 504, result=TIMEOUT |
| tool no invoked | ✅ | HTTP 502, INVALID_RESPONSE |

---

## ⚠️ Bloqueo Actual

**Modelo Vertex:** `gemini-2.5-flash` venció el 20/10/2026.
- Model Garden confirma la línea vigente: `gemini-3.8-flash` (elegido), `gemini-3.7-flash`, `gemini-3.6-flash`, `gemini-3.5-flash-lite`.
- El ticket-agent usa `APP_TICKET_AGENT_MODEL` (default `gemini-3.8-flash`) y ya no reutiliza `app.vertex.model`.
- Verificado el 25/10/2026: `us-central1` → `404 NOT_FOUND`; `locations/global` → `200` con `modelVersion: gemini-3.8-flash`. Default `APP_TICKET_AGENT_REGION=global`.
- End-to-end verificado: `POST /api/v1/agent/ticket-query` → `FOUND` con `prize.amount=150.00` y `provenance=SYNTHETIC` (Vertex real + WS fixture). Evidencia en `docs/evidencia/2026-10-25/e2e-smoke.md`.
- Pendiente: WS real de Carlos (modo `HTTP`).

**WS de Carlos:** No publicado - avanzamos con fixtures SINTÉTICOS.

---

## 📂 Archivos Clave Actualizados

- `worker-poc/.env.example` - Nuevas variables de configuración
- `worker-poc/README.md` - Estructura actualizada del proyecto
- `worker-poc/docs/evidencia-agente-boleto.md` - Documentación completa E3.2

---

## 🚀 Próximos Pasos

1. **D2 (29/10):** Suite de tests verde (~16 tests nuevos)
2. **D3 (al publicar WS):** Llamada real al WS de Carlos con modelo verificado
3. **Re-autenticación Vertex:** Con cuenta corporativa `steven@avanzatech.xyz`