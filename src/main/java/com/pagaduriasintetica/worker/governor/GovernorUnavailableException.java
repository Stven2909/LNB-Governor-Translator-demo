package com.pagaduriasintetica.worker.governor;

/**
 * Falla transitoria del Governor (timeout, 429, 5xx, indisponibilidad). Es recoverable: la
 * operacion pasa a RETRYABLE, la reserva queda en estado reclamable y se responde NACK para que
 * Pub/Sub programe la redelivery. Distinguirla de los otros casos es lo que evita que un Vertex
 * lenta se convierta en una perdida de mensaje.
 */
public class GovernorUnavailableException extends RuntimeException {

    public GovernorUnavailableException(String message) {
        super(message);
    }

    public GovernorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}