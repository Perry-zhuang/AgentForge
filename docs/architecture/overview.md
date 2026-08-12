# AgentForge MVP Architecture

AgentForge is a single-process, local CLI. Picocli is the input adapter; application services orchestrate scanners, configuration, policy, readiness, recommendation, and risk engines. Domain records do not depend on the CLI.

```text
CLI
  -> Application Services
      -> Repository / Context / Architecture Scanners
      -> Task Analyzer / Relevant File Engine
      -> Git Change Scanner
      -> Policy / Readiness / Recommendation / Minimal Change / Risk Engines
  -> Text Renderer
```

The MVP uses the Git CLI and rule-based source inspection. It deliberately excludes network services, databases, plugin frameworks, LLM calls, and full AST parsing.

## V0.1.1 Analysis Semantics

Architecture analysis is intentionally lightweight. It recognizes direct field and constructor dependencies using explicit imports or same-package type resolution. It does not provide full Java semantic analysis, runtime dependency resolution, or a method call graph.

The Diff command scans the working tree, including staged, unstaged, untracked, deleted, and renamed paths. Its architecture section means **Architecture Findings In Changed Files**: findings whose source file is currently changed. V0.1.1 does not compare a full HEAD architecture graph with a full working-tree graph and therefore does not label these findings as newly introduced violations.

Initialization uses safe create-if-missing behavior. Existing `.agentforge` files and `AGENTS.md` are preserved byte-for-byte.

## Actionable Audit Flow

```text
Repository / Context / Architecture Scanners
  -> Repository Report
      -> Readiness Engine
          -> Recommendation Engine
              -> Actionable Audit
```

`RecommendationEngine` applies deterministic rules to the current repository report. For each recommendation it changes only the relevant readiness signal in an immutable hypothetical report, evaluates that report with the same `ReadinessEngine` and active YAML weights, and reports the resulting overall-score delta.

Estimated impacts are independent per recommendation. They describe the effect of satisfying one condition and must not be added together as a guaranteed combined result.

Architecture reports carry `Analysis Mode: LIGHTWEIGHT`. This explicitly limits statements such as layer-bypass detection to findings visible to the current source heuristics.

## V0.2 P1 Task Inspection Flow

```text
TASK -> Task Analyzer
Repository Scanner -> one repository file list
Architecture Scanner -> Java source index + Dependency Graph
Context Scanner -> README / AGENTS context
all inputs -> Relevant File Engine -> Task Inspection Report -> Text Renderer
```

`TaskAnalyzer` emits three deterministic groups: core keywords, exact phrases, and weak derived n-grams. Chinese core tokens use dictionary-free non-overlapping pairs, with a three-character final token for odd-length sequences. `RelevantFileEngine` scores exact phrases and core-token coverage within one logical source line, then applies class/file/package/import evidence and one-hop Java dependencies from frozen strong seeds. Derived n-grams are recall-only and capped; Java architecture roles contribute zero to two points.

Source-area cohort scoring is only applied to the strongest concentration of exact, multi-core, full-coverage, or strong class/file/package seeds and excludes passive data/configuration areas such as DTO, VO, entity, enum, constants, util, and config packages. Dependency evidence cannot propagate from single-core or derived-only matches and never recurses beyond one hop. These are general false-positive controls, not repository-specific rules. Scores are fixed and explainable; no network, LLM, vector store, business dictionary, or random behavior participates.
