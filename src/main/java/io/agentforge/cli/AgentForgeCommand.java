package io.agentforge.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(
        name = "agentforge",
        mixinStandardHelpOptions = true,
        version = "AgentForge 0.1.0-SNAPSHOT",
        description = "Audit repository readiness and local Git changes for coding agents.",
        subcommands = {AuditCommand.class, InitCommand.class, DiffCommand.class}
)
public class AgentForgeCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("Use agentforge --help to list commands.");
    }
}
