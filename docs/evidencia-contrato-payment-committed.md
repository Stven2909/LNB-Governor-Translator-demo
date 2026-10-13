# Evidencia de compatibilidad — contrato PAYMENT_COMMITTED (parte Steven, JD14)

**Fecha:** 13 de octubre de 2026
**Responsable de esta evidencia:** Steven (contrato tipado + deserialización comprobada)
**Coordinación:** Daniel · **Productor:** Alex · **Consumidor:** Carlos
**Rama/commit de referencia:** `feat/e3.2-vertex-governor` (ver `git log`; la evidencia cloud
posterior usa SHA posterior al de esta entrega)

> E2.1 queda como baseline local únicamente; no sustituye la compatibilidad cloud
> productor → consumidores (revisión JD 13/10).

---

## 1. Schema tipado v0.1 (fuente: código, no transcrito a mano)

Clases: `contract/PaymentCommittedEvent.java` (10 campos) y `contract/OperationData.java`
(8 campos). Convención **camelCase en todo el JSON** (coincide con la API REST y con
`lnb-docs/CONTRACT_EVENTO_V0.1.md` de Alex, 17/09).

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "PAYMENT_COMMITTED_V0.1",
  "title": "PaymentCommittedEvent",
  "type": "object",
  "required": ["eventId", "eventType", "aggregateType", "aggregateId", "eventVersion",
               "destinationSystem", "occurredAt", "correlationId", "operationId",
               "operationData"],
  "properties": {
    "eventId":         { "type": "string",  "description": "= outbox_event_id (PK)" },
    "eventType":       { "type": "string",  "const": "PAYMENT_COMMITTED" },
    "aggregateType":   { "type": "string",  "const": "prizes.payment" },
    "aggregateId":     { "type": "string",  "description": "UUID del agregado" },
    "eventVersion":    { "type": "integer", "const": 1 },
    "destinationSystem": { "type": "string", "const": "SYBASE" },
    "occurredAt":      { "type": "string",  "format": "date-time" },
    "correlationId":   { "type": "string",  "description": "correlacion end-to-end (no traceId)" },
    "operationId":     { "type": "string",  "description": "clave de idempotencia (Idempotency-Key)" },
    "operationData": {
      "type": "object",
      "required": ["paymentId", "claimId", "status", "paymentMethod",
                   "grossAmount", "withholdingAmount", "netAmount", "currency"],
      "properties": {
        "paymentId":         { "type": "string" },
        "claimId":           { "type": "string" },
        "status":            { "type": "string", "const": "COMMITTED" },
        "paymentMethod":     { "type": "string" },
        "grossAmount":       { "type": "number", "exclusiveMinimum": 0 },
        "withholdingAmount": { "type": "number", "minimum": 0 },
        "netAmount":         { "type": "number", "exclusiveMinimum": 0 },
        "currency":          { "type": "string", "minLength": 3, "maxLength": 3 }
      }
    }
  }
}
```

Reglas aplicadas por `StructuralValidator` + catálogo (no son schema, son validación):
`eventVersion == 1`, `destinationSystem == SYBASE`, `occurredAt` ISO8601,
invariante `netAmount == grossAmount - withholdingAmount` (BigDecimal.compareTo),
terna `(aggregateType, eventType)` en whitelist del catálogo.

## 2. Catálogo canónico

- **Ruta propuesta como canónica:**
  `src/main/resources/catalog/CATALOG_PAYMENT_COMMITTED_V0.1.json`
- **Versión:** `CATALOG_PAYMENT_COMMITTED_V0.1` (`Catalog.CATALOG_VERSION`)
- **SHA-256 del archivo (calculado en repo el 13/10):**
  `f9557125ebbd8dcd557ebcad67b4bd4a5e5c1a1eb4d31588524a817363485431`
- Contrato del Gobernador derivado: `CONTRACT_PAYMENT_COMMITTED_V0.1`
  (`DeterministicGovernorSupport.CONTRACT_VERSION`).

## 3. Deserialización comprobada (empírico, no declarado)

Suite: `src/test/java/com/pagaduriasintetica/worker/contract/`
`PaymentCommittedContractSerializationTest.java` — **4/4 en verde**
(`mvn test -Dtest=PaymentCommittedContractSerializationTest`).

| Caso | Resultado observado |
|---|---|
| Round-trip DTO → JSON → DTO | Sin pérdida: tipos exactos, montos con `compareTo()==0` (ver nota), `StructuralValidator` sin errores, **hash canónico idéntico** antes/después (`0fd5240a…`) |
| `eventVersion=2` | Rechazado: `eventVersion must be 1` |
| `operationId` ausente | Rechazado: `operationId required` |
| Sonda: payload con `instrumentIds: ["inst-1","inst-2"]` no declarado | **TOLERADO y silenciosamente ignorado** (Jackson 3 no falla); el hash no cambia |

**Notas técnicas:**
- `BigDecimal.equals` compara valor **y** escala (`200.00` ≠ `200.0`); la precisión
  monetaria se verifica con `compareTo`, que es lo que usa la invariante de negocio.
- La sonda es el argumento técnico para no aceptar payloads con `instrumentIds` sin
  shape congelado: hoy se **ignorarían sin error** (peor que rechazar: multi-instrumento
  se procesaría como mono-reclamo). El shape debe nacer del productor.

## 4. Propuesta `instrumentIds` para Alex y Carlos (vía Daniel)

**Opción A — coexistencia retrocompatible (recomendada):** se mantiene `claimId: string`
y se añade `instrumentIds: string[]` opcional. Si ausente/vacío, `claimId` sigue siendo
el ancla; si presente, debe incluir el reclamo. Impacto acotado: validación +
documentación; el hash canónico **no** incorpora el arreglo hasta congelar regla.

**Opción B — sustitución estricta:** se retira `claimId`, se exige `instrumentIds`.
Impacto mayor: cambia `PayloadHasher` (invalida hashes congelados e in-doubt),
`StructuralValidator`, `field_mapping` del catálogo y columnas JDBC/Sybase.

## 5. POR CONFIRMAR (no bloquean esta evidencia; bloquean el cierre JD)

- [ ] Alex: ruta/commit del productor y **payload real posterior al COMMIT** (incluido
      caso multi-instrumento cuando se congele el shape).
- [ ] Carlos: consumidor compatible + fixtures positivo, inválido y múltiples
      `instrumentIds` aprobados.
- [ ] `operationId ↔ outbox_event.idempotency_key` (asumido, ver
      `CONTRACT_EVENTO_V0.1.md` §7.1).
- [ ] Ruta canónica del catálogo y aprobación productor/consumidores.
