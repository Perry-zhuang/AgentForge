# AgentForge MVP Architecture

AgentForge is a single-process, local CLI. Picocli is the input adapter; application services orchestrate scanners, configuration, policy, readiness, and risk engines. Domain records do not depend on the CLI.

```text
CLI
  -> Application Services
      -> Repository / Context / Architecture Scanners
      -> Git Change Scanner
      -> Policy / Readiness / Minimal Change / Risk Engines
  -> Text Renderer
```

The MVP uses the Git CLI and rule-based source inspection. It deliberately excludes network services, databases, plugin frameworks, LLM calls, and full AST parsing.

## V0.1 Analysis Semantics

Architecture analysis is intentionally lightweight. It recognizes direct field and constructor dependencies using explicit imports or same-package type resolution. It does not provide full Java semantic analysis, runtime dependency resolution, or a method call graph.

The Diff command scans the working tree, including staged, unstaged, untracked, deleted, and renamed paths. Its architecture section means **Architecture Findings In Changed Files**: findings whose source file is currently changed. V0.1 does not compare a full HEAD architecture graph with a full working-tree graph and therefore does not label these findings as newly introduced violations.

Initialization uses safe create-if-missing behavior. Existing `.agentforge` files and `AGENTS.md` are preserved byte-for-byte.
