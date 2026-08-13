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

## V0.3 P0 Coding Agent Adapter Contract

```text
AgentForge Control Plane
    -> compute and freeze Engineering Boundary
    -> render Engineering Prompt
    -> capture task-aware baseline
    -> AgentExecutionRequest
        -> CodingAgentAdapter
            -> external Coding Agent process
            -> AgentExecutionResult
    -> compare execution delta
    -> check against the frozen boundary
```

The Adapter is an external-process driver, not a reasoning or governance layer. Its immutable request contains only
agent identity/capabilities, the canonical target repository root, an already rendered prompt, an execution timeout,
and explicit environment overrides. It does not receive an Engineering Plan, inspection report, Engineering Boundary,
repository snapshot, execution delta, policy report, architecture report, or verification report. Prompt generation
and all Engineering Boundary decisions stay in the control plane.

The stdin process implementation delivers the rendered prompt as the exact UTF-8 input string without adding,
removing, or recomputing instructions. The child process runs in the canonical request repository root. Its environment
is inherited from the AgentForge process, and explicit request overrides win for matching names. The Adapter does not
inject credentials, create prompt files, write repository files, or run Git lifecycle commands.

stdout and stderr are drained independently and continuously while stdin is delivered on a third I/O worker, so large
simultaneous output cannot block process completion on full pipe buffers. Normal completion captures both streams in
full. A request timeout or calling-thread interruption terminates and reaps the direct child process and returns the
output captured before termination. P0 intentionally does not implement descendant process-tree management; an agent
that creates independent descendants is a later lifecycle concern.

`AgentExecutionResult` contains process facts only: agent identity, process exit code when available, stdout, stderr,
timeout, interruption, lifecycle status, and transport/start failure detail. Exit code zero means only that the external
process completed successfully. It does not mean scope, policy, architecture, task type, business behavior, or
verification passed. Likewise, a non-zero process exit is not represented as an Engineering Boundary violation.

The registry is an explicit in-process `CodingAgentType -> CodingAgentAdapter` mapping. P0 defines only `CODEX` as an
identity type and does not implement network discovery, SPI loading, or a runtime plugin ecosystem. Future
orchestration must freeze the Engineering Boundary and capture the baseline before `execute`, then compute execution
delta and invoke Check only after `execute` completes.

## V0.3 P1 Codex CLI Adapter

`CodexAdapter` is the first real `CodingAgentAdapter` implementation. With an explicit executable path, or the fallback
executable name `codex`, it deterministically builds this minimal Codex CLI 0.144.5 command:

```text
<codex-executable> exec --sandbox workspace-write -C <canonical-repository-root> -
```

`exec` is Codex's non-interactive mode, `--sandbox workspace-write` explicitly permits repository-local coding changes
while retaining the workspace sandbox, `-C` selects the same canonical repository root used as the child process
working directory, and `-` selects stdin prompt transport. `CodexAdapter` passes the request prompt, timeout, and
environment overrides directly to the existing `DeterministicProcessExecutor`; it has no second process lifecycle
implementation. It does not probe the version on each execution, place prompts in arguments or files, transform prompt
content, or log prompts and environment values.

The adapter deliberately leaves approval behavior to the Codex runtime/configuration and never broadens the sandbox
beyond workspace-write. In particular, it never adds extra writable directories, `danger-full-access`,
`--dangerously-bypass-approvals-and-sandbox`, or `--dangerously-bypass-hook-trust`; the normal Codex approval and
safety boundary remain active. Authentication and network availability belong to the inherited Codex runtime
environment. The Adapter does not login, refresh credentials, inject API keys, or reinterpret runtime failures.

The explicit registry can map `CODEX` to a `CodexAdapter`. P1 adds no user-facing CLI wiring and no baseline capture,
execution delta comparison, Check invocation, rollback, or Git lifecycle behavior in the Adapter layer.

## V0.3 P2 Execution Orchestrator

```text
AgentRunRequest
    -> PlanService.plan(...) exactly once
        -> frozen EngineeringPlan
    -> PromptService.fromPlan(the same plan)
    -> EngineeringPromptRenderer.render(...) exactly once
    -> ExecutionBaselineService.capture(the same plan)
        -> in-memory schema 2 task-aware baseline
    -> CodingAgentRegistry.resolve(type)
    -> CodingAgentAdapter.execute(...) exactly once
    -> ExecutionBaselineService.compare(in-memory baseline)
    -> CheckService.checkExecution(frozen boundary + execution delta)
    -> AgentRunResult
```

`AgentExecutionService` is application orchestration, not a new governance engine. It sequences the existing Plan,
Prompt, baseline, Adapter, delta, and Check boundaries. It depends on the adapter registry and a prompt-rendering port,
not on `CodexAdapter`, process infrastructure, scanners, policy engines, planning engines, prompting engines, or Check
engines. The production service writes no target-repository files and invokes no Git mutation command.

The pre-execution `EngineeringPlan` is the only Plan used by the run. Prompt generation consumes that exact plan and
does not call planning again. The in-memory task-aware baseline captures its Scope, TaskType, resolved change policy,
and Architecture contract before `adapter.execute`. After execution, comparison captures current repository state but
does not replan or reload after-state governance rules. Check consumes the frozen boundary and only the attributed
execution delta. Therefore a newly generated, highly task-relevant file remains outside scope if it was outside the
pre-execution boundary.

Process outcome and verification outcome remain independent facts. `AgentRunStatus` gives process failure precedence
for aggregate reporting (`AGENT_FAILED`, `AGENT_TIMED_OUT`, `AGENT_INTERRUPTED`, or `AGENT_START_FAILED`) while
retaining the complete execution delta and `VerificationReport`. For a successful process, Check PASS/WARNING/FAIL
maps to `PASS`, `WARNING`, or `VERIFICATION_FAILED`. A non-zero exit is never converted into a boundary violation, and
an exit code of zero never implies verification success.

An invalid repository or a Plan, Prompt, baseline-capture, or adapter-resolution infrastructure failure stops before
agent invocation and is reported as a phase-specific `AgentRunException`. Once the adapter returns a process result,
delta and verification are attempted even for non-success statuses. A later delta or Check infrastructure exception
retains the completed process result, and a Check exception additionally retains the computed execution delta.

P2 uses no temporary baseline file, so there is no prompt or baseline artifact inside the target repository and no
cleanup lifecycle. It adds explicit local Spring wiring for the deterministic process executor, `CodexAdapter`, and
registry; P2 itself exposed no `agentforge run` command. It does not add rollback, Git add/commit/stash/reset/restore,
multi-agent execution, parallel execution, retries, resume, streaming UI, Claude, Gemini, plugin discovery, or business
correctness decisions.

## V0.3 P3 Run CLI

```text
Picocli RunCommand
    -> parse [PATH] TASK, --agent, --timeout-seconds
    -> AgentRunRequest
    -> AgentExecutionService.execute(...) exactly once
    -> AgentRunResult
    -> RunRenderer
    -> aggregate-status exit code
```

`RunCommand` is deliberately thin. Its only application dependency is `AgentExecutionService`; it does not import or
invoke Plan, Inspect, Prompt, baseline, Check, policy, architecture, Git, process, registry, or concrete Adapter
components. `CodingAgentType.parse` provides the single agent-name parsing boundary. The production Spring composition
root continues to map CODEX to `CodexAdapter` through `CodingAgentRegistry`; neither CLI nor orchestrator constructs a
concrete adapter.

P3 uses `--timeout-seconds` because no general duration CLI contract exists. The value is a positive whole number of
seconds and defaults to 900. Environment overrides remain a programmatic P2 request capability; P3 exposes no
environment or credential flags and passes an empty override map, preserving inherited process environment semantics.

`RunRenderer` consumes only `AgentRunResult` and immutable nested facts. It renders repository/task/agent identity,
frozen boundary counts, process status and exit code, stdout/stderr presence, attributed changes, verification statuses
and findings, and the existing aggregate decision. It reads no repository, Git state, configuration, or external
process. Full prompts and raw stdout/stderr are omitted from the default report. Consequently report structure is
deterministic, while the external agent streams retained in `AgentExecutionResult` remain nondeterministic facts.

CLI exit codes are a projection of existing aggregate semantics: PASS and WARNING return 0; verification failure,
agent failure, timeout, interruption, and start failure return 1; Picocli invocation errors and orchestration
infrastructure failures return 2. The CLI does not reconstruct the aggregate decision. Process SUCCESS plus Check FAIL
is rendered as `AGENT RUN VERIFICATION FAILED`; a failed or timed-out agent still displays its execution delta and
independent verification findings.

P3 adds no real Codex end-to-end acceptance test. Automated root-command tests use the real P2 orchestrator with a
deterministic fake adapter over temporary Git repositories. Model selection, sandbox/approval ownership, login and API
keys, sessions, resume, retry, rollback, auto-commit, parallel execution, additional agents, and business-correctness
decisions remain outside this phase.
