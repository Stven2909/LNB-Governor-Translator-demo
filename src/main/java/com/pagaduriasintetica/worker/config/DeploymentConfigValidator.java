package com.pagaduriasintetica.worker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Verifica la configuracion de despliegue al arrancar y falla de inmediato si es incompleta. Con
 * Spring Boot, un puerto o una sonda mal configurados no impiden el arranque: el servicio levanta
 * y falla en la primera request de Pub/Sub, que es cuando ya no hay margen para corregirlo.
 * Este runner convierte eso en un fallo de despliegue visible en los logs de Cloud Run.
 *
 * No registra secretos: WorkerProperties.toString() solo expone nombres y banderas de presencia.
 */
@Component
public class DeploymentConfigValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeploymentConfigValidator.class);

    private final WorkerProperties properties;

    public DeploymentConfigValidator(WorkerProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        properties.validateForMode();
        log.info("Configuracion de despliegue validada: {}", properties);
    }
}