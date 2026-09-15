package com.pagaduriasintetica.worker.contract;

// Envelope del push HTTP de Pub/Sub (formato estándar de Google Cloud) que llega a /push.
// message.data contiene el evento en Base64; aquí va el envelope completo del POST.
public record PushEnvelope(
        String subscription,
        PushMessage message
) {
}