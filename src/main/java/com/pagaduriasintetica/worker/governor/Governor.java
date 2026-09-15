package com.pagaduriasintetica.worker.governor;

import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.SyntheticEvent;

// Interfaz del Gobernador de LNB: decide (Vertex AI con LLM en producción; MockGovernor hoy)
// sobre el evento decodificado y devuelve el GovernorContract.
public interface Governor {

    GovernorContract decide(SyntheticEvent event);
}