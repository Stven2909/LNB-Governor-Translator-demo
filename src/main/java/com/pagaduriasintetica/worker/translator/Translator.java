package com.pagaduriasintetica.worker.translator;

import com.pagaduriasintetica.worker.contract.TranslatorInput;
import com.pagaduriasintetica.worker.contract.TranslatorResult;

// Interfaz del Traductor de LNB: convierte TranslatorInput (event + GovernorContract YA
// validado + payloadHash + workerTraceId) en el plan SQL dentro de la whitelist del catálogo.
// Nunca recalcula montos; solo valida la invariante y aplica value_rules autorizados.
public interface Translator {

    TranslatorResult translate(TranslatorInput input);
}