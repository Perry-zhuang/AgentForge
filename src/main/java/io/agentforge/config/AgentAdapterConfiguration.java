package io.agentforge.config;

import io.agentforge.agent.CodexAdapter;
import io.agentforge.agent.CodingAgentAdapter;
import io.agentforge.agent.CodingAgentRegistry;
import io.agentforge.agent.process.DeterministicProcessExecutor;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Local explicit adapter wiring; creating these beans does not execute an external Coding Agent. */
@Configuration(proxyBeanMethods = false)
public class AgentAdapterConfiguration {
    @Bean
    DeterministicProcessExecutor deterministicProcessExecutor() {
        return new DeterministicProcessExecutor();
    }

    @Bean
    CodingAgentAdapter codexCodingAgentAdapter(DeterministicProcessExecutor processExecutor) {
        return new CodexAdapter(processExecutor);
    }

    @Bean
    CodingAgentRegistry codingAgentRegistry(List<CodingAgentAdapter> adapters) {
        return new CodingAgentRegistry(adapters);
    }
}
