# AgentForge

AgentForge is a local AI Engineering control plane and repository governance CLI for coding-agent workflows.

AgentForge does not write code. It analyzes the repository, a development task, and the current change before or after a Coding Agent works on it.

V0.2 P2 adds deterministic engineering planning on top of task inspection:

- Task Inspection
- Engineering Plan
- Repository Audit
- Architecture Contract
- Change Policy
- Minimal Change
- Risk

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

## Known Limitations

### Architecture Analysis

V0.1.1 reports Architecture Analysis Mode as `LIGHTWEIGHT`. This means AgentForge uses deterministic source heuristics rather than a complete AST or semantic engine. It detects direct field dependencies and constructor dependencies using explicit imports or same-package resolution.

It does not guarantee detection of generic-wrapped dependencies, reflection, runtime dependency injection, method-level call graphs, dynamic Spring bean resolution, Lombok-generated constructors, complex nested classes, or ambiguous simple class names.

### Git

`agentforge diff` audits the working tree against the current repository state. It includes staged, unstaged, untracked, deleted, and renamed files.

### Architecture Diff

V0.1.1 reports architecture findings present in changed source files. It does not compare complete HEAD and working-tree dependency graphs, so it does not claim that a finding was introduced by the exact current diff.

### AI

V0.2 P2 has no LLM calls, Agent execution, prompt generation, or Coding Agent adapter. Its inspection and planning findings are deterministic, rule-based, and explainable.

### Task Inspection

V0.2 P1 inspection is lexical and structural, not semantic search. It does not translate between languages, infer synonyms, build method call graphs, or understand runtime behavior. Chinese input uses deterministic, dictionary-free core-token splitting plus weak derived n-grams. Core-token coverage is computed within one logical source line, with a bounded window for long lines, so unrelated declarations cannot be combined into artificial full coverage.

`HIGH`, `MEDIUM`, and `LOW` are calibrated confidence labels rather than score bands. A high lexical score without structural task evidence does not imply `HIGH`, and an architecture role alone cannot promote a file. Confidence remains heuristic and should be read together with the reported match coverage and evidence.

Dependency expansion is Java-only, field/constructor based, and limited to one hop. Propagation starts only from structural task matches; source-text exact phrases or multi-core matches alone do not create seeds. DTO, request, response, model, enum, and config files without structural task identity are capped at LOW relevance. Package concentration only reranks candidates that are already relevant. Large Java repositories affected by the current lightweight-regex stack limitation may still require `-Xss16m`.

## Roadmap

- Machine-readable report output.
- Richer parser-backed dependency analysis.
- Prompt and coding-agent adapters after the governance core is stable.
