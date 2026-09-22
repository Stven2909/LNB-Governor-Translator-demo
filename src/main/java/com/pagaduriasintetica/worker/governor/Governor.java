package com.pagaduriasintetica.worker.governor;

import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorInput;

// Interfaz del Gobernador de LNB: decide (Vertex AI con LLM en producción; MockGovernor hoy)
// sobre la entrada del evento (GovernorInput: identificadores + operationData) y devuelve el
// GovernorContract (decisión + plan de escritura de la whitelist). Nunca se invoca antes de la
// reserva atómica de operationId (regla del pipeline).
public interface Governor {

    GovernorContract decide(GovernorInput input);
}