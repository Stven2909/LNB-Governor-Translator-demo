package com.pagaduriasintetica.worker.translator;

import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.SyntheticEvent;
import com.pagaduriasintetica.worker.contract.TranslatorResult;

// Interfaz del Traductor de LNB: convierte evento + contrato del Gobernador en el plan SQL
// (TranslatorResult) que el JDBC ejecuta contra Sybase, siempre dentro de la whitelist.
public interface Translator {

    TranslatorResult translate(SyntheticEvent event, GovernorContract governor, String payloadHash);
}