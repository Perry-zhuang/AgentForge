# Repository Rules

## Project

See `.agentforge/project.yaml`.

## Architecture

See `.agentforge/architecture.yaml` and `docs/architecture/overview.md`.

## Change Policy

See `.agentforge/policies.yaml`.

## Development Rules

1. Inspect before modification and keep changes narrowly scoped.
2. Preserve public API compatibility unless a task explicitly changes it.
3. Keep CLI, application orchestration, models, and engines decoupled.
4. Declare dependency, database, security, and configuration changes.
5. Add or update focused tests and run `./mvnw clean package` before completion.
