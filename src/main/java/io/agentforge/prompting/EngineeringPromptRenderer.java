package io.agentforge.prompting;

import io.agentforge.model.EngineeringPrompt;

/** Rendering port used by application orchestration without depending on a CLI implementation package. */
public interface EngineeringPromptRenderer {
    String render(EngineeringPrompt prompt);
}
