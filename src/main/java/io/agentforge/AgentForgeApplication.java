package io.agentforge;

import io.agentforge.cli.AgentForgeCommand;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;

@SpringBootApplication
public class AgentForgeApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(AgentForgeApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run();
        int exitCode;
        try {
            CommandLine.IFactory factory = context::getBean;
            exitCode = new CommandLine(context.getBean(AgentForgeCommand.class), factory)
                    .setCaseInsensitiveEnumValuesAllowed(true)
                    .execute(args);
        } finally {
            context.close();
        }
        System.exit(exitCode);
    }
}
