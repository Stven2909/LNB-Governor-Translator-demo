package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Push endpoint de Pub/Sub (Cloud Run de la Pagaduría): recibe el envelope crudo de la
 * suscripción sintética. El Worker decide: 200 = ACK (evidencia persistida, no se reenvía);
 * 500 = NACK (redelivery programado por Pub/Sub para vuelos/retry).
 *
 * <p>Se excluye del perfil {@code carlos}: el rol WS expone el contrato de consulta de boletos,
 * no la ingesta del Worker. Sin esta exclusion, el servicio del WS queda con un
 * {@code POST /push} abierto que no forma parte de su contrato y que cualquiera podria invocar
 * en una ventana de demostracion publica.</p>
 */
@RestController
@Profile("!carlos")
public class PushController {

    private final WorkerService workerService;

    public PushController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @PostMapping("/push")
    public ResponseEntity<Map<String, Object>> push(@RequestBody String rawBody) {
        ProcessingOutcome outcome = workerService.handleRaw(rawBody);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ack", outcome.ack());
        body.put("status", outcome.status().name());
        body.put("operationId", outcome.operationId());
        body.put("reason", outcome.reason());
        HttpStatus status = outcome.ack() ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(body);
    }
}