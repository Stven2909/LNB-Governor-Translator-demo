-- FIXTURE_SYNTHETIC_DEV.sql
-- Fixture autorizado para PoC vertical en Sybase DEV
-- Catalog: CATALOG_SYNTHETIC_V0 / Contract: CONTRACT_SYNTHETIC_V0
-- NO es tabla real de LNB. Se usa solo para validar plataforma (VPN, JDBC, commit/rollback, idempotencia).
-- Reemplazo futuro: tabla real prize.payment / prize.prize_claim cuando LNB/Alex confirmen CATALOG_REAL.

-- 1. Eliminación idempotente previa (solo para entorno DEV)
IF OBJECT_ID('SYNTHETIC_PAYMENTS', 'U') IS NOT NULL
    DROP TABLE SYNTHETIC_PAYMENTS;

IF OBJECT_ID('WORKER_OPERATION_STATE', 'U') IS NOT NULL
    DROP TABLE WORKER_OPERATION_STATE;

-- 2. Tabla fixture para inserción del negocio (nombre en MAYUSCULAS por convención Sybase)
CREATE TABLE SYNTHETIC_PAYMENTS (
    COD_RECLAMO     VARCHAR(64)     NOT NULL,
    MONTO           DECIMAL(10,2)   NOT NULL,
    FECHA_OPER      DATE            NOT NULL,
    BENEFICIARIO    VARCHAR(100)    NOT NULL,
    OPERATION_ID    VARCHAR(64)     NOT NULL,
    TRACE_ID        VARCHAR(64)     NOT NULL,
    EVENT_ID        VARCHAR(64)     NOT NULL,
    PAYLOAD_HASH    VARCHAR(64)     NOT NULL, -- Permite verificación in-doubt post-commit independientemente del Worker state
    CREATED_AT      DATETIME        NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_SYNTHETIC_PAYMENTS PRIMARY KEY (OPERATION_ID)
);

-- Índices para trazabilidad end-to-end (reporte de evidencia)
CREATE INDEX IX_SYNTHETIC_PAYMENTS_TRACE ON SYNTHETIC_PAYMENTS (TRACE_ID);
CREATE INDEX IX_SYNTHETIC_PAYMENTS_EVENT ON SYNTHETIC_PAYMENTS (EVENT_ID);

-- 3. Tabla de idempotencia / estados del Worker (persistencia atómica en la MISMA base Sybase DEV)
-- La reserva se realiza mediante INSERT directo; una violación de PK indica duplica en vuelo o ya procesado (evita check-then-act).
CREATE TABLE WORKER_OPERATION_STATE (
    OPERATION_ID    VARCHAR(64)     NOT NULL,
    EVENT_ID        VARCHAR(64)     NOT NULL,
    TRACE_ID        VARCHAR(64)     NOT NULL,
    MESSAGE_ID      VARCHAR(128)    NULL,
    PAYLOAD_HASH    VARCHAR(64)     NOT NULL, -- Hash canónico SHA-256 (64 hex chars)
    STATUS          VARCHAR(20)     NOT NULL, -- SUCCEEDED | REJECTED | IDEMPOTENT | RETRYABLE | DLQ_QUARANTINED | PROCESSING
    ATTEMPT_COUNT   INT             NOT NULL DEFAULT 1,
    GOVERNOR_DECISION VARCHAR(20)   NULL,
    TARGET_TABLE    VARCHAR(64)     NULL,
    ERROR_REASON    VARCHAR(500)    NULL,
    CREATED_AT      DATETIME        NOT NULL DEFAULT GETDATE(),
    UPDATED_AT      DATETIME        NOT NULL DEFAULT GETDATE(),
    CONSTRAINT PK_WORKER_OPERATION_STATE PRIMARY KEY (OPERATION_ID)
);

-- 4. Modelo de PreparedStatement JDBC de 8 parámetros (generado por Traductor + Worker)
-- Template: INSERT INTO SYNTHETIC_PAYMENTS (COD_RECLAMO, MONTO, FECHA_OPER, BENEFICIARIO, OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
-- Parámetros: [claimId, amount, operationDate, beneficiary, operationId, traceId, eventId, payloadHash]

-- 5. Teardown para repetir PoC (solo DEV)
-- DELETE FROM SYNTHETIC_PAYMENTS WHERE OPERATION_ID LIKE 'op-%';
-- DELETE FROM WORKER_OPERATION_STATE WHERE OPERATION_ID LIKE 'op-%';