package com.pagaduriasintetica.worker.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Habilita la configuracion por perfiles. Los beans de las implementaciones reales se registraran
 * por @ConditionalOnProperty cuando lleguen los bloques de Vertex, Pub/Sub, JDBC y reporte HTTP;
 * aqui solo queda la declaracion de propiedades tipadas.
 */
@Configuration
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfiguration {
}