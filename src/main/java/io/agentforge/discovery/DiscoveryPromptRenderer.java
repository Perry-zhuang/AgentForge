package io.agentforge.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DiscoveryPromptRenderer {
    private final ObjectMapper mapper = new ObjectMapper();

    public String render(DiscoveryRequest request) {
        try {
            return """
                    You are performing AgentForge READ_ONLY discovery before mutation.
                    Do not modify, create, delete, rename, stage, format, build, or otherwise change repository files.
                    You may inspect the entire repository, search broadly, trace call chains, and read tests, configuration, and Git history.
                    Inspect evidence below is only a starting point and does not limit what you may read.
                    Return strict JSON only: no Markdown fences, commentary, or text outside the JSON object.

                    Discovery input:
                    %s

                    Required JSON schema:
                    {"findings":[{"summary":"...","evidence":["relative/file"]}],"relevantFlows":[{"description":"...","files":["relative/file"]}],"proposedPrimaryScope":["relative/file"],"proposedSecondaryScope":[],"referenceFiles":[],"risks":[],"unknowns":[],"confidence":"HIGH"}
                    Every proposed PRIMARY file must be cited by a finding or relevant flow. Use existing exact repository-relative file paths only.
                    Put general limitations and hypothetical external concerns in risks. Put an item in unknowns only when a repository fact remains unresolved after investigation and that fact blocks safe automatic mutation. Do not invent repository-external callers or contracts; treat the checked-out repository and its tests as the available contract.
                    """.formatted(mapper.writeValueAsString(facts(request)));
        } catch (JsonProcessingException exception) {
            throw new DiscoveryException("Unable to render discovery prompt", exception);
        }
    }

    private Map<String, Object> facts(DiscoveryRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", request.task());
        result.put("taskHash", request.taskHash());
        result.put("taskClassification", Map.of(
                "type", request.classification().effectiveType().name(),
                "confidence", request.classification().typeConfidence().name(),
                "mutationMode", request.classification().mutationMode().name(),
                "securityImpact", request.classification().securityImpact().name(),
                "signals", request.classification().signals().stream().map(Enum::name).toList()));
        result.put("repositoryRoot", request.repositoryRoot().toString());
        result.put("branch", request.branch());
        result.put("head", request.head());
        result.put("inspectEvidence", request.inspectEvidence());
        result.put("proposedPrimaryScope", request.proposedPrimaryScope());
        result.put("proposedSecondaryScope", request.proposedSecondaryScope());
        result.put("referenceFiles", request.referenceFiles());
        result.put("existingDirtyPaths", request.existingDirtyPaths());
        result.put("policy", Map.of("maxFiles", request.policy().change().maxFiles(),
                "deny", request.policy().change().deny(),
                "protectedPaths", request.policy().change().protectedPaths(),
                "maxModules", request.policy().risk().maxModules()));
        result.put("architectureAnalysisMode", request.architectureAnalysisMode().name());
        result.put("timeoutSeconds", request.timeout().toSeconds());
        return result;
    }
}
