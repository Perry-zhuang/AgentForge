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

Task inspection keeps ranking explainable through four signal groups: structural identity, semantic task evidence, direct one-hop dependency evidence, and lightweight file responsibility. Strong dependency seeds require a class/file, package, import, or direct dependency-type task match; propagated evidence cannot create another seed. Passive source responsibilities are capped at LOW unless they carry structural task identity.

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

Inspection deliberately keeps score, ordering, and confidence separate. Score is the sum of positive evidence. Ordering continues to use the P1.2 structural/semantic/responsibility/dependency comparator. `ConfidenceProfile` classifies the already-ranked result using core coverage, structural evidence, executable responsibility, and the passive-file cap; confidence never participates in ordering. The renderer exposes positive evidence in task, structure, dependency, and context groups, while missing core tokens and responsibility are descriptive rather than artificial zero-point evidence.

## V0.2 P2 Engineering Plan Flow

```text
Task -> InspectService -> TaskInspectionReport
TaskInspectionReport + Architecture Contract + Policies + Git Baseline
    -> PlanEngine -> EngineeringPlan -> Text Renderer
```

`PlanService` is application orchestration. It invokes `InspectService` once, loads the active configuration, records the current Git baseline without modifying it, and passes immutable inputs to `PlanEngine`. `PlanEngine` does not scan source files and does not call or reproduce `RelevantFileEngine`. Its scope may contain only files already present in `TaskInspectionReport`.

Scope grouping is deterministic. HIGH and flow-linked MEDIUM inspect results are PRIMARY candidates; if fewer than three candidates exist, already-ranked MEDIUM results may fill the remaining PRIMARY positions. Other MEDIUM and flow-linked LOW results are supporting scope, while remaining inspect results are references. PRIMARY means likely change targets, SECONDARY means inspect before changing, and REFERENCE means read-only context by default. Evidence shortfalls lower `PlanConfidence`; planning never compensates by adding business dictionaries, semantic inference, or newly discovered files.

Architecture contracts and policy paths are constraints. Lightweight architecture findings are labeled as observations and are never presented as configured contracts. `PlanRiskEngine` reports pre-implementation risk from the known scope and baseline, including calibrated dirty-file volume and Git overlap with PRIMARY or SECONDARY scope. A renamed Git entry remains one logical changed file even when its old and new paths both match. The existing `RiskEngine` continues to report risk in current Git changes; these risk meanings and models remain separate.

`PlanTestAdvisor` consumes only the repository report, existing scope responsibilities and architecture roles, task type, testability ratio, and related flow. It deterministically orders repository-native tests, task-type checks, scope-specific scheduler/API/persistence checks, integration checks, manual regression guidance, and the post-implementation diff audit. It performs no repository or source scan.

The command boundaries are:

```text
audit   = Understand repository readiness
inspect = Locate known relevant files
plan    = Organize known facts into a safe implementation plan
diff    = Evaluate current working-tree changes
```

Planning is read-only: it does not initialize `.agentforge`, write caches in the target repository, edit governance files, change Git state, generate prompts, or execute a Coding Agent.

## V0.2 P3 Engineering Prompt Flow

```text
PromptCommand
    -> PromptService
        -> PlanService.plan(...) exactly once
            -> EngineeringPlan
        -> PromptEngine
            -> EngineeringPrompt
    -> PromptRenderer
```

`PromptEngine` is a pure transformation boundary over `EngineeringPlan`. It has no repository scanner, inspect service, task classifier, scope engine, configuration loader, or risk engine dependency. It preserves the plan's task type, confidence, ordered scope, architecture and policy constraints, Git baseline and overlap, pre-implementation risk, verification guidance, and acceptance criteria. Instructions without a direct source in those facts are omitted.

`EngineeringPrompt` is immutable and contains only prompt-facing facts and instructions. Scope entries deliberately omit inspect scores, confidence reasons, and match evidence. `PromptRenderer` owns the fixed Markdown section order and performs no planning or policy decisions.

Prompt generation is read-only with respect to the target repository. The optional user-selected `--output` file is the only permitted write; existing files require `--force`, parent directories are never created, and output inside the target repository produces an explicit warning.

The command boundaries are:

```text
audit   = Understand repository readiness
inspect = Locate known relevant files
plan    = Organize known facts into a safe implementation plan
prompt  = Package the engineering plan as deterministic Coding Agent instructions
diff    = Evaluate current working-tree changes
```

## V0.2 P4 P0 Engineering Boundary Check Flow

```text
CheckCommand
    -> CheckService
        -> PlanService.plan(...) exactly once
            -> EngineeringPlan
        -> DiffAuditService.audit(...) exactly once
            -> DiffAuditReport
        -> CheckEngine
            -> VerificationReport
    -> CheckRenderer
```

`CheckEngine` is a pure boundary-comparison component. It does not scan the repository, inspect source contents, load configuration, classify the task, calculate plan scope, evaluate policy rules, or infer architecture. It compares the plan's ordered PRIMARY, SECONDARY, and REFERENCE scope with the existing Git/Diff facts. Rename old and new paths are evaluated independently, and findings from scope, policy, task type, and architecture remain independent.

Policy findings preserve each `PolicyEngine` violation from `DiffAuditReport`. Architecture compliance consumes only the changed-file architecture facts already produced by `DiffAuditService`; when no architecture contract is configured, Check reports `NOT CONFIGURED` without promoting lightweight observations to violations. ANALYSIS source checks reuse the same file-type classification used by `RepositoryScanner` and do not inspect file contents.

P4 P0 uses `CURRENT_WORKING_TREE` as its verification baseline. It supports dirty repositories but cannot distinguish pre-existing changes from changes introduced by a particular Coding Agent execution. That behavior and output remain the compatibility contract when `check` is invoked without a baseline.

## V0.2 P4 P1 Repository Baseline Flow

```text
RepositoryStateCapture
    -> complete tracked files + non-ignored untracked files
    -> worktree SHA-256 + index identity + porcelain Git state
    -> RepositorySnapshot

RepositorySnapshot(before)
    + RepositorySnapshot(after)
    -> BaselineComparator
    -> ExecutionChangeReport
```

Capture is the repository fact boundary. It records the canonical Git root, branch, HEAD, every tracked path, and non-ignored untracked paths. Worktree content identity, index identity, and the two-column porcelain state remain independent facts. Regular and binary files are hashed with streaming SHA-256, symlinks use link-target identity, and Gitlinks use only parent-repository Git/index facts without recursive submodule inspection.

`BaselineComparator` is a pure immutable-state comparison. It does not read the repository, run Git, load configuration, or invoke Check. Dirty paths that retain identical content and Git state produce no execution delta; a dirty path whose content changes again produces `MODIFIED`. Git-confirmed renames are attributed only when introduced between snapshots. A rename already represented by the baseline produces no delta unless its new path changes again.

The repository-only schema 1 snapshot remains versioned deterministic JSON without timestamps, UUIDs, host identity, or file contents. A task-aware schema 2 execution baseline wraps that unchanged repository snapshot plus `EngineeringBoundarySnapshot`. Baseline compare accepts either schema and compares only repository snapshots. Snapshot output and input are required to remain outside the target repository. P4 P1 reports branch and HEAD changes as facts and stops at `ExecutionChangeReport`; it does not alter the P4 P0 `CURRENT_WORKING_TREE` Check contract.

## V0.2 P4 P2 Execution Check Flow

```text
CheckCommand --baseline
    -> CheckService
        -> ExecutionBaselineService.compare(...) exactly once
            -> schema 2 EngineeringBoundarySnapshot
            -> ExecutionChangeReport
        -> PolicyEngine(execution files) exactly once
            using baseline change policy
        -> ArchitectureScanner(current facts, baseline contract)
        -> evidence-safe execution attribution
        -> VerificationChangeSet
        -> CheckEngine
            -> VerificationReport(EXECUTION_DELTA)
```

Task-aware capture computes the Engineering Boundary before execution. `EngineeringBoundarySnapshot` stores only normalized task identity, TaskType, PRIMARY/SECONDARY/REFERENCE paths, resolved change-policy facts, policy-source fact, architecture analysis mode, and the resolved Architecture contract. It contains no compliance findings, plan risk, confidence, recommendations, related flow, prompt content, or architecture graph snapshot.

`VerificationChangeSet` only normalizes facts from `DiffAuditReport` or `ExecutionChangeReport`; it computes no scope, task-type, policy, architecture, or risk decisions. Execution mode does not call `PlanService`, reload current policy or Architecture configuration, invoke `DiffAuditService`, or fall back to whole-working-tree facts. A schema 1 repository-only baseline or a task identity mismatch fails explicitly with exit code 2.

Scope consumes every execution entry using the baseline-time paths. Rename oldPath always uses its exact baseline classification. A destination that is an explicit baseline Scope path uses that classification; an otherwise-new destination inherits the old scoped entity only for a same-parent compatible rename, while moves elsewhere remain outside. An outside oldPath remains failing even when the destination is PRIMARY. ANALYSIS source compliance consumes only content-changing entries, so a pure staging change remains scope activity without becoming a source-content modification. Execution policy uses the existing `PolicyEngine` with the baseline-time change policy; each execution entry counts once for max-files, while rename old and new paths both participate in deny and protected matching.

Architecture attribution is deliberately conservative because baselines contain content identities and the baseline contract, but not before-source or architecture graphs. The current graph is evaluated against the baseline-time contract. A current contract violation may fail execution check only when its source file is an execution `ADDED` path, which proves the violating source did not exist in the baseline state. Current violations in modified or renamed existing files are excluded and reported as an attribution limitation. P4 P2.1 does not add architecture graph snapshots or claim those current violations were introduced by execution.

Execution Check is read-only. Baseline/schema/repository/capture failures are invocation errors with exit code 2. Branch and HEAD changes are displayed as execution facts but do not create new P4 P2 violations.

The command boundaries are:

```text
audit   = Understand repository readiness
inspect = Locate known relevant files
plan    = Organize known facts into a safe implementation plan
prompt  = Package the engineering plan as deterministic Coding Agent instructions
diff    = Evaluate current working-tree changes and policy risk
check   = Verify current working-tree or attributed execution changes against a task-specific Engineering Boundary
baseline = Capture repository state and report a deterministic execution-window delta
```
