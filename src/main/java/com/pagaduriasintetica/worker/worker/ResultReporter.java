package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationResult;
import com.pagaduriasintetica.worker.contract.ReportResult;

// Contrato de reporte del Worker hacia la API (Opción B de la minuta con Alex). Hoy la única
// implementación es MockResultReporter; HttpResultReporter llegará cuando la API confirme ruta,
// autenticación y códigos. El Worker queda DESACOPLADO del transporte concreto.
public interface ResultReporter {

    ReportResult report(OperationResult result);
}