package com.pagaduriasintetica.worker.governor;

/**
 * Seam del adaptador Vertex para hacer testeable la clasificacion de VertexGovernor SIN red ni
 * credenciales en CI. La implementacion de produccion (GenaiVertexClient) envuelve el SDK
 * google-genai; los tests inyectan un fake que devuelve VertexOutcome o lanza VertexCallException.
 */
public interface VertexClient {

    VertexOutcome generate(String systemInstruction, String userPrompt);
}