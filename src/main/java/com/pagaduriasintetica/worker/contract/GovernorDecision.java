package com.pagaduriasintetica.worker.contract;

// Decisión del Gobernador (pasos 8-9 de LNB): REJECTED = rechazo de negocio (el evento es
// válido pero no debe procesarse); APPROVED = continuar con la validación estricta del plan.
public enum GovernorDecision {
    APPROVED,
    REJECTED
}