# AgentForge

AgentForge is a local AI Engineering control plane and repository governance CLI for coding-agent workflows.

AgentForge does not write code. It analyzes the repository and the current change before or after a Coding Agent works on it.

V0.1.1 focuses on five deterministic governance capabilities:

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
```

## Commands

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

V0.1.1 has no LLM calls, Agent execution, or Codex integration. Its findings are deterministic, rule-based, and explainable.

## Roadmap

- Machine-readable report output.
- Richer parser-backed dependency analysis.
- Prompt and coding-agent adapters after the governance core is stable.
