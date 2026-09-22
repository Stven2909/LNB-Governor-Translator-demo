package com.pagaduriasintetica.worker.contract;

// Envelope del push HTTP de Pub/Sub (formato estándar de Google Cloud) que llega a /push.
// message.data contiene el evento en Base64; aquí va el envelope completo del POST.
// deliveryAttempt (presente cuando la suscripción tiene DLQ habilitado) es de TRANSPORTE:
// no es parte del contrato de negocio de Alex, pero se registra para auditoría de redelivery.
public record PushEnvelope(
        String subscription,
        PushMessage message,
        Integer deliveryAttempt
) {
}