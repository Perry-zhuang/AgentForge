package io.agentforge.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(
        name = "agentforge",
        mixinStandardHelpOptions = true,
        version = "AgentForge 0.2.0-SNAPSHOT",
        description = "Inspect and audit repositories for coding-agent workflows.",
        subcommands = {AuditCommand.class, InitCommand.class, DiffCommand.class, InspectCommand.class}
)
public class AgentForgeCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("Use agentforge --help to list commands.");
    }
}
