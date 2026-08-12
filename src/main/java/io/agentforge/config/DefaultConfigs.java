package io.agentforge.config;

public final class DefaultConfigs {
    private DefaultConfigs() {}

    public static final String PROJECT_YAML = """
            schema_version: 1
            project:
              name: %s
            """;

    public static final String ARCHITECTURE_YAML = """
            schema_version: 1
            architecture:
              layers: [controller, service, repository, mapper]
              rules:
                controller:
                  allow_dependencies: [service]
                service:
                  allow_dependencies: [repository, mapper]
                repository:
                  allow_dependencies: []
                mapper:
                  allow_dependencies: []
            """;

    public static final String POLICIES_YAML = """
            schema_version: 1
            change:
              max_files: 20
              deny: [pom.xml, package.json]
              protected:
                - src/main/java/**/security/**
                - database/**
                - "**/db/migration/**"
            minimal_change:
              free_files: 5
              per_extra_file: 2
              max_file_penalty: 30
              free_modules: 1
              per_extra_module: 8
              max_module_penalty: 24
              config_change_penalty: 10
              dependency_change_penalty: 15
              database_change_penalty: 15
              security_change_penalty: 20
              agents_change_penalty: 10
            risk:
              medium_threshold: 20
              high_threshold: 40
              critical_threshold: 70
              max_modules: 3
              policy_violation: 30
              dependency_change: 15
              security_change: 20
              database_change: 15
              files_over_limit: 20
              modules_over_limit: 10
              controller_change: 10
              agents_change: 10
              config_change: 5
            readiness:
              context:
                agents: 35
                readme: 15
                skills: 10
                agent_rules: 10
                git_rules: 10
                database_rules: 5
                api_rules: 5
                testing_rules: 10
                agents_too_large_penalty: 10
                agents_max_lines: 300
              architecture:
                architecture_doc: 40
                architecture_contract: 35
                recognizable_structure: 10
                no_layer_bypass: 15
              change_safety:
                policies: 40
                gitignore: 15
                git_repository: 15
                git_rules: 10
                database_rules: 10
                api_rules: 10
              testability:
                tests_present: 60
                test_ratio: 25
                testing_rules: 15
                minimum_test_ratio: 0.10
              documentation:
                readme: 35
                architecture_doc: 35
                agents: 20
                docs_directory: 10
            """;

    public static final String AGENTFORGE_README = """
            # AgentForge Configuration

            - `project.yaml` identifies this repository.
            - `architecture.yaml` declares allowed layer dependencies.
            - `policies.yaml` contains explainable readiness, change, minimal-change, and risk rules.
            """;

    public static final String AGENTS_MD = """
            # Repository Rules

            ## Project

            See `.agentforge/project.yaml`.

            ## Architecture

            See `.agentforge/architecture.yaml`.

            ## Change Policy

            See `.agentforge/policies.yaml`.

            ## Development Rules

            1. Inspect before modification.
            2. Prefer minimal changes and do not change unrelated modules.
            3. Preserve API compatibility unless explicitly requested.
            4. Database changes must be declared.
            5. Run validation before completion.
            """;
}
