package com.pagaduriasintetica.worker.config;

import com.pagaduriasintetica.worker.governor.DeterministicGovernorSupport;
import com.pagaduriasintetica.worker.governor.GenaiVertexClient;
import com.pagaduriasintetica.worker.governor.VertexClient;
import com.pagaduriasintetica.worker.governor.VertexGovernor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Habilita la configuracion por perfiles. Los beans de las implementaciones reales se registran
 * por @ConditionalOnProperty: en app.governor.mode=VERTEX se activa VertexGovernor (y su adaptador
 * GenaiVertexClient con ADC); en MOCK (default) sigue mandando MockGovernor. Nunca coexisten dos
 * beans Governor: MockGovernor tambien declara @ConditionalOnProperty(MOCK, matchIfMissing=true).
 */
@Configuration
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.governor.mode", havingValue = "VERTEX")
    VertexClient vertexClient(WorkerProperties props) {
        return new GenaiVertexClient(props.getVertex());
    }

    @Bean
    @ConditionalOnProperty(name = "app.governor.mode", havingValue = "VERTEX")
    VertexGovernor vertexGovernor(VertexClient client,
                                  DeterministicGovernorSupport support,
                                  WorkerProperties props,
                                  ObjectMapper mapper) throws IOException {
        return new VertexGovernor(client, support, props.getVertex(), mapper);
    }
}