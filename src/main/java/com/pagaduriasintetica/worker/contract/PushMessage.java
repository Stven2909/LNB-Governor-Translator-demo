package com.pagaduriasintetica.worker.contract;

// Mensaje del envelope Pub/Sub: data = evento en Base64; messageId = dedupe de transporte.
public record PushMessage(
        String data,
        String messageId,
        String publishTime
) {
}