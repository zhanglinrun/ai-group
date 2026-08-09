# Repository Guidelines

## Project Structure & Module Organization

This repository is the 熊博士 platform. Each Java microservice is a top-level Maven module built from the root `pom.xml`; shared Java code is under `ai-group-common/`. The Python LangGraph Agent is `agent-service/`, and the React frontend is `frontend/`. API/event contracts are under `contracts/`, while Docker Compose, database bootstrap and observability files are under `dev-ops/`.

## Build, Test, and Development Commands

- `mvn clean install -DskipTests` builds the root platform modules.
- `cd gateway-service && mvn test` runs Gateway tests.
- `cd group-service && mvn test` runs group-buying tests.
- `cd pay-service && mvn test` runs payment tests.
- `cd agent-service && python -m pytest -q` runs Agent tests.
- `cd frontend && npm ci && npm run dev` starts the Vite UI on port `5173`.
- `cd frontend && npm run build` type-checks and builds the production frontend.
- `docker compose --env-file .env -f dev-ops/compose/docker-compose.full.yml up --build` starts the full stack.

## Coding Style & Naming Conventions

Use Java 21 and Spring Boot conventions. Keep packages under the owning service namespace and follow layered module boundaries already present in `group-service`, `pay-service`, and `agent-service`. Prefer constructor injection, typed configuration properties, and clear DTO names such as `CreateOrderRequest` or `MemberQuotaResponse`. Frontend code uses TypeScript, React, Vite, ESLint, and Prettier; run `npm run lint` in `frontend` before submitting UI changes.

## Testing Guidelines

Backend tests use Maven/Surefire and Spring test tooling where needed. Name Java tests with the `*Test` suffix and keep fast unit tests isolated from MySQL, MQ, external LLMs, and payment sandboxes unless explicitly marked as integration tests. UI tests use Vitest via `pnpm test`.

## Commit & Pull Request Guidelines

This checkout does not include local Git history, so no repository-specific commit pattern can be verified. Use short, imperative subjects with a scope when useful, for example `member: add quota ledger idempotency`. Pull requests should include a summary, linked issue when applicable, validation commands run, and screenshots for UI changes.

## Security & Configuration Tips

Keep secrets in `.env` and mirror only safe defaults in `.env.example`. Do not bypass Gateway identity headers or internal token checks. Before coding in a scoped area, inspect the relevant module and existing tests.
