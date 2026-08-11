# Repository Guidelines

## Project Structure & Module Organization

This repository is the 熊博士 platform.

- Java microservices (root `pom.xml`): `gateway-service`, `auth-service`, `bff-service`, `member-service`, `group-service`, `pay-service`, plus shared `ai-group-common/`
- Python Agent: `agent-service/` (FastAPI + LangGraph) — not Spring AI
- Frontend: `frontend/` (React + TypeScript + Vite)
- Contracts: `contracts/` (OpenAPI + event schemas; change contracts before breaking API shapes)
- Ops: `dev-ops/` (Docker Compose, DB bootstrap, middleware)
- Also: `eval/` (Gateway smoke), `scripts/` (contract validation, seed/cleanup)

Browser traffic goes only through Gateway. BFF proxies Agent SSE; the frontend must not call Agent directly.

## Hard Boundaries

- **Identity**: Sa-Token is the Java session authority. Downstream services and Python accept Gateway HMAC identity envelopes / internal tokens — do not bypass them.
- **Quota**: Member is the sole ledger authority (reserve / confirm / release). Agent meters tokens; Group/Pay issue or settle commercial flows, they do not own Agent quota.
- **Agent stack**: research orchestration lives in Python LangGraph + Postgres checkpoint. Do not invent a Java Spring AI Agent implementation unless the code actually adds one.

## Build, Test, and Development Commands

- `mvn clean install -DskipTests` — build Java modules
- `cd gateway-service && mvn test` / `cd group-service && mvn test` / `cd pay-service && mvn test` — scoped Java tests
- `cd agent-service && python -m pytest -q` — Agent tests
- `cd frontend && npm ci && npm run dev` — Vite UI on port `5173`
- `cd frontend && npm run lint` / `npm run build` — TypeScript check (`tsc --noEmit`) and production build
- `docker compose --env-file .env -f dev-ops/compose/docker-compose.full.yml up --build` — full stack (includes XXL-JOB Admin on `:18081`)
- `powershell -ExecutionPolicy Bypass -File scripts/validate-contracts.ps1` — contract check
- `powershell -ExecutionPolicy Bypass -File eval/http-smoke.ps1` — Gateway smoke

Copy `.env.example` to `.env` before Compose; set at least `AI_GROUP_INTERNAL_TOKEN`, `AI_GROUP_IDENTITY_SIGNING_SECRET`, and an LLM key when running Agent. Full Compose defaults `XXL_JOB_ENABLED=true` and `PAY_OUTBOX_LOCAL_SCHEDULER_ENABLED=false` so Pay Outbox is driven by XXL-JOB.

## Coding Style & Naming Conventions

Use Java 21 and Spring Boot conventions. Keep packages under the owning service namespace and follow existing layered modules in `group-service` / `pay-service` (api, domain, infrastructure, trigger, app). Prefer constructor injection, typed configuration properties, and clear DTOs such as `CreateOrderRequest` or `MemberQuotaResponse`.

Frontend uses TypeScript, React, and Vite with `npm` (`package-lock.json`). `npm run lint` is TypeScript checking, not ESLint. Prefer matching existing UI patterns over introducing new tooling.

## Testing Guidelines

Name Java tests with the `*Test` suffix. Keep fast unit tests isolated from MySQL, MQ, external LLMs, and payment sandboxes unless explicitly marked as integration tests. Agent pytest API scenarios may need Postgres checkpoint from Compose; offline unit tests should not call external LLMs. Frontend `npm test` currently type-checks; do not assume Vitest/`pnpm` unless added later.

## Commit & Pull Request Guidelines

Use short, imperative subjects with a scope when useful, for example `member: add quota ledger idempotency`. PRs should include a summary, linked issue when applicable, validation commands run, and screenshots for UI changes.

## Security & Configuration Tips

Keep secrets in `.env`; mirror only safe placeholders in `.env.example`. Do not commit real LLM, Alipay, or signing secrets. Before coding in a scoped area, inspect that module and its existing tests first.
