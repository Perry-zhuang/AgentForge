# AgentForge

AgentForge is a local AI Engineering control plane and repository governance CLI for coding-agent workflows.

AgentForge does not write code. It analyzes the repository, a development task, and the current change before or after a Coding Agent works on it.

V0.2 P4 P2 adds deterministic execution-window boundary verification on top of repository baselines:

- Task Inspection
- Engineering Plan
- Engineering Prompt
- Engineering Boundary Check
- Repository Baseline / Execution Delta
- Repository Audit
- Architecture Contract
- Change Policy
- Minimal Change
- Risk

V0.3 P2 adds an internal, sequential Execution Orchestrator over the Codex CLI adapter. It freezes one plan, prompt,
and task-aware baseline before agent execution, then always computes the execution delta and frozen-boundary Check
after the adapter returns. There is deliberately no `agentforge run` command yet.

## Why AgentForge

Coding agents work best when repository boundaries, architecture rules, and change policies are explicit. AgentForge scans those signals and provides deterministic, explainable readiness and change-risk reports.

## Quick Start

```bash
./mvnw clean package
java -jar target/agentforge.jar audit /path/to/project
java -jar target/agentforge.jar init /path/to/project
java -jar target/agentforge.jar diff /path/to/project
java -Xss16m -jar target/agentforge.jar inspect /path/to/project "development task"
java -Xss16m -jar target/agentforge.jar plan /path/to/project "development task"
java -Xss16m -jar target/agentforge.jar prompt /path/to/project "development task"
java -Xss16m -jar target/agentforge.jar check /path/to/project "development task"
java -jar target/agentforge.jar baseline capture /path/to/project --output /tmp/agentforge-baseline.json
java -jar target/agentforge.jar baseline compare /path/to/project --baseline /tmp/agentforge-baseline.json
java -Xss16m -jar target/agentforge.jar baseline capture /path/to/project --task "development task" --output /tmp/agentforge-before.json
java -Xss16m -jar target/agentforge.jar check /path/to/project "development task" --baseline /tmp/agentforge-before.json
```

## Commands

### `agentforge inspect`

Finds source files related to a natural-language development task without an LLM:

```bash
java -Xss16m -jar target/agentforge.jar inspect /path/to/project "analyze user login permissions"
java -Xss16m -jar target/agentforge.jar inspect --limit 15 /path/to/project "backup alert sync"
```

When `PATH` is omitted, the current directory is inspected. The default limit is 10 and the valid range is 1–15. Results include explainable scores, build modules, source areas, and detected one-hop dependency flow.

Inspection ranks recognized Java, Kotlin, JavaScript, JSX, TypeScript, TSX, and Vue sources. Task analysis separates core keywords, exact phrases, and weak derived n-grams. Ranking is intentionally limited to four explainable dimensions: structural identity, semantic task evidence, one-hop dependency evidence, and lightweight file responsibility. Architecture roles are contextual evidence only. Java also uses class/file/package matches, imports, and direct field or constructor dependencies.

Each result separates three concepts: `Score` summarizes the amount of evidence, ranking identifies which files should be inspected first, and `Confidence` expresses how strongly the structural and task evidence support relevance. Confidence is not a ranking key, so calibration does not reorder results. The CLI shows core-keyword coverage and a short confidence reason, groups positive evidence by task, structure, dependency, and context, and hides internal weak n-grams.

### `agentforge plan`

Builds a deterministic, read-only engineering plan from the existing `inspect` report:

```bash
java -Xss16m -jar target/agentforge.jar plan "fix login 401"
java -Xss16m -jar target/agentforge.jar plan /path/to/project "analyze backup alert synchronization"
```

When `PATH` is omitted, the current directory is used. Planning does not discover additional relevant files. It preserves inspect ordering while grouping known results into PRIMARY likely change targets, SECONDARY files to inspect before changing, and REFERENCE read-only context. These are planning recommendations rather than mandatory edits or policy restrictions.

The report includes the deterministic task type and matching signal, plan confidence, Do Not Touch paths, an estimated change scope, pre-implementation risk, test recommendations, and acceptance criteria. A dirty working tree produces a warning but does not fail. A non-Git directory produces an unavailable baseline and still returns a plan. Missing policy files use built-in defaults; invalid policy or architecture YAML fails explicitly.

`Plan Confidence` describes whether inspect supplied enough implementation evidence. LOW or REFERENCE evidence is never promoted through new business rules, and it is not treated as permission to modify a file. `Pre-Implementation Risk` describes risk visible before implementation and is separate from the current-change risk reported by `agentforge diff`. Dirty-tree risk is calibrated by the existing changed-file count, and overlap with PRIMARY or SECONDARY scope is computed from the existing Git report without rescanning source.

Test guidance is derived only from repository build/test facts, task type, the existing plan scope, architecture roles, and inspect's related flow. It may recommend regression, scheduler, API, persistence, integration, or manual verification without attempting to infer method-level behavior.

### `agentforge prompt`

Builds a deterministic Engineering Prompt from the existing engineering plan:

```bash
java -Xss16m -jar target/agentforge.jar prompt "fix login 401"
java -Xss16m -jar target/agentforge.jar prompt /path/to/project "analyze backup alert synchronization"
java -Xss16m -jar target/agentforge.jar prompt /path/to/project "fix login 401" --output task-prompt.md
```

When `PATH` is omitted, the current directory is used. Prompt generation calls planning once and does not scan source, rank files, classify the task, calculate scope, or calculate risk independently. It converts only facts already carried by the immutable `EngineeringPlan` into instructions for a Coding Agent.

The prompt keeps PRIMARY, SECONDARY, and REFERENCE semantics explicit, preserves existing working-tree changes, carries configured architecture and policy restrictions, and requires scope deviations to be reported. Analysis tasks are read-only by default. An unavailable architecture contract is clearly separated from lightweight observed flows.

By default the prompt is written to standard output. `--output FILE` writes UTF-8 to an existing parent directory and refuses to overwrite an existing file. `--output FILE --force` permits replacement. A relative output path is resolved from the current working directory. Writing inside the analyzed repository emits a warning; only the explicitly selected output file is written.

### `agentforge check`

Verifies the current Git working-tree changes against the deterministic Engineering Boundary computed for a task:

```bash
java -Xss16m -jar target/agentforge.jar check "fix login 401"
java -Xss16m -jar target/agentforge.jar check /path/to/project "fix backup alert synchronization"
java -Xss16m -jar target/agentforge.jar check /path/to/project "fix backup alert synchronization" \
  --baseline /tmp/agentforge-before.json
```

Without `--baseline`, Check calls planning once, consumes the existing Diff facts, and does not rescan source or infer new architecture facts in `CheckEngine`. PRIMARY changes are allowed, SECONDARY changes require review, and REFERENCE or out-of-scope changes fail. For ANALYSIS tasks, recognized source-code changes fail independently of scope findings. Individual policy violations are preserved from the existing Policy result.

Without `--baseline`, the P4 P0 baseline remains `CURRENT_WORKING_TREE` with its existing output and behavior. With `--baseline`, Check requires a task-aware schema 2 baseline and uses its pre-execution Scope, TaskType, resolved change policy, and Architecture contract together with only the `ExecutionChangeReport` entries introduced after that snapshot. It never replans or reloads execution-modified boundary configuration. Historical dirty files that remain unchanged do not participate in scope, task-type, policy, or architecture compliance. A repository-only schema 1 baseline is rejected for execution check with exit code 2. Execution decisions are labeled `CHECK EXECUTION PASS/WARNING/FAIL`.

Execution policy evaluation reuses the existing max-files, denied-path, and protected-path rules over execution files only. A rename counts once for max-files while both old and new paths are checked. Git-state-only activity participates in scope and path policy, but does not count as ANALYSIS source-content modification. Architecture failures are evidence-safe: without architecture snapshots, a current violation is attributed to execution only when its source file was added during the execution window; existing-file changes are reported with an attribution limitation instead of being mislabeled.

Both modes are read-only. PASS and WARNING return exit code 0, FAIL returns 1, and command, configuration, baseline, schema, repository-identity, or capture errors return 2.

### `agentforge baseline`

Captures complete tracked state plus non-ignored untracked state, then compares a later repository state with that saved baseline:

```bash
java -jar target/agentforge.jar baseline capture /path/to/project --output /tmp/agentforge-baseline.json
java -Xss16m -jar target/agentforge.jar baseline capture /path/to/project --task "fix login 401" \
  --output /tmp/agentforge-before.json
java -jar target/agentforge.jar baseline compare /path/to/project --baseline /tmp/agentforge-baseline.json
```

Repository-only schema 1 snapshots contain a versioned, deterministic JSON representation of the canonical Git root, branch, HEAD, working-tree SHA-256 identities, index identities, and porcelain Git states. Supplying `--task` creates a schema 2 execution baseline that wraps the same repository snapshot plus the minimal verification-relevant pre-execution engineering boundary. File content is never stored. Ignored files are excluded, binary files are hashed safely, and Gitlinks use parent-repository Git/index facts without scanning submodule contents.

Comparison accepts either schema and compares only its repository snapshot. It reports file content changes, Git-state-only changes, add/delete, and Git-confirmed rename facts. A rename already present when the baseline is captured is not attributed to the later execution window. Branch and HEAD changes are reported as facts. Both `EXECUTION DELTA NONE` and `EXECUTION DELTA CHANGED` return exit code 0; invalid invocation, snapshot, schema, repository identity, or file access returns 2.

The baseline file must be outside the target repository. Capture, compare, and execution check do not modify the target repository or the baseline file. P4 P2 consumes the existing `ExecutionChangeReport` without changing snapshot or delta semantics.

### `agentforge audit`

Scans repository technology, structure, AI context, architecture, and readiness, then reports the highest-value actions for improving Coding Agent readiness.

### Actionable Recommendations

Audit results combine four deterministic elements:

```text
Score + Evidence + Recommended Action + Estimated Impact
```

Recommendations are derived from the active readiness configuration. Estimated impact means the calculated change to the current overall readiness score if that single condition were satisfied; it is an estimate, not a promise, and separate recommendations are not cumulative guarantees.

`Test File Ratio` is the number of detected test files divided by detected source files. It is a repository signal and must not be interpreted as code coverage.

### `agentforge init`

Safely creates missing `.agentforge` contracts and a short `AGENTS.md`. Existing files are never overwritten, reformatted, or rewritten.

### `agentforge diff`

Audits current Git changes for policy violations, architecture findings in changed files, minimal-change quality, and risk.

## Architecture

The CLI delegates to application services, which coordinate scanners and pure policy/risk engines over immutable domain reports. See [the architecture overview](docs/architecture/overview.md).

The V0.3 adapter boundary is narrower than the control plane. The control plane computes and freezes repository
facts, Engineering Boundary, rendered prompt, baseline, execution delta, and verification decisions. An adapter
receives only agent identity, canonical repository root, the already rendered prompt, timeout, and explicit
environment overrides. It drives one external process and reports stdout, stderr, process exit code, timeout,
interruption, and start-failure facts. Adapter process success is never equivalent to `CHECK EXECUTION PASS`.

The Codex adapter invokes the configured executable as `codex exec -C <canonical-repository-root> -`, supplies the
already rendered prompt unchanged through UTF-8 stdin, and reuses the common process executor. It does not add a
sandbox or approval override, use dangerous bypass flags, manage authentication, log environment secrets, or run Git
lifecycle commands.

The P2 `AgentExecutionService` is an application-layer control-plane coordinator. Its fixed lifecycle is Plan once,
derive and render Prompt from that exact plan, capture an in-memory task-aware baseline from that exact plan, resolve
one adapter, execute once, compare the post-execution repository to the in-memory baseline, and Check against the
frozen boundary. It never asks after-state repository facts to recompute Plan, Prompt, TaskType, Scope, Policy, or
Architecture constraints. Agent process failure, timeout, interruption, or start failure does not suppress delta and
verification; the aggregate result retains process facts and the independent verification result.

## Known Limitations

### Architecture Analysis

V0.1.1 reports Architecture Analysis Mode as `LIGHTWEIGHT`. This means AgentForge uses deterministic source heuristics rather than a complete AST or semantic engine. It detects direct field dependencies and constructor dependencies using explicit imports or same-package resolution.

It does not guarantee detection of generic-wrapped dependencies, reflection, runtime dependency injection, method-level call graphs, dynamic Spring bean resolution, Lombok-generated constructors, complex nested classes, or ambiguous simple class names.

### Git

`agentforge diff` audits the working tree against the current repository state. It includes staged, unstaged, untracked, deleted, and renamed files.

`agentforge check` without `--baseline` evaluates the current working tree against a task-specific Engineering Boundary. With `--baseline`, it evaluates only the deterministic execution delta and never falls back to whole-working-tree changes.

### Architecture Diff

V0.1.1 reports architecture findings present in changed source files. It does not compare complete HEAD and working-tree dependency graphs, so it does not claim that a finding was introduced by the exact current diff.

### AI

V0.3 P2 connects the real Codex CLI adapter to an internal Execution Orchestrator but does not expose it through the
CLI. Inspection, planning, prompt generation, boundary verification, snapshot capture, execution-delta comparison,
and execution check remain deterministic, local, and explainable. Adapter command construction and control-plane
ordering are deterministic; external Coding Agent output is not. Execution Check verifies boundary compliance; it
does not decide whether code is correct or whether a bug is fixed. P2 terminates and reaps the direct child process on
timeout but does not provide cross-platform descendant process-tree cleanup or repository rollback.

### Task Inspection

V0.2 P1 inspection is lexical and structural, not semantic search. It does not translate between languages, infer synonyms, build method call graphs, or understand runtime behavior. Chinese input uses deterministic, dictionary-free core-token splitting plus weak derived n-grams. Core-token coverage is computed within one logical source line, with a bounded window for long lines, so unrelated declarations cannot be combined into artificial full coverage.

`HIGH`, `MEDIUM`, and `LOW` are calibrated confidence labels rather than score bands. A high lexical score without structural task evidence does not imply `HIGH`, and an architecture role alone cannot promote a file. Confidence remains heuristic and should be read together with the reported match coverage and evidence.

Dependency expansion is Java-only, field/constructor based, and limited to one hop. Propagation starts only from structural task matches; source-text exact phrases or multi-core matches alone do not create seeds. DTO, request, response, model, enum, and config files without structural task identity are capped at LOW relevance. Package concentration only reranks candidates that are already relevant. Large Java repositories affected by the current lightweight-regex stack limitation may still require `-Xss16m`.

## Roadmap

- Machine-readable report output.
- Richer parser-backed dependency analysis.
- A user-facing `agentforge run` command over the internal execution orchestrator.
