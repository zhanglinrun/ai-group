# Repository Guidelines

## Project Structure & Module Organization

This repository is an AI group-membership platform composed of several services. The root `pom.xml` builds the platform services: `ai-group-common/`, `gateway-service/`, `auth-service/`, `member-service/`, and `bff-service/`. Existing business systems live beside them: `group/` for group-buying, `s-pay-mall-ddd-market/` for payment, and `ai-agent/` for the agent runtime. The platform frontend lives at the repository root under `web/`. Java source and tests follow the Maven layout under `src/main` and `src/test`. Frontend code, assets, and Vite configuration live under `web/`. Development operations files are in `docs/dev-ops/`; Trellis planning and specs are under `.trellis/`.

## Build, Test, and Development Commands

- `mvn clean install -DskipTests` builds the root platform modules.
- `cd gateway-service && mvn test` runs Gateway tests.
- `cd group && mvn test` runs group-buying tests.
- `cd s-pay-mall-ddd-market && mvn test` runs payment tests.
- `cd ai-agent && mvn test` runs agent runtime tests.
- `cd web && pnpm install && pnpm dev` starts the Vite UI on port `5173`.
- `cd web && pnpm build` type-checks and builds the production frontend.
- `cd docs/dev-ops && ./start-full-stack.ps1` starts infrastructure and local services on Windows.

## Coding Style & Naming Conventions

Use Java 21 and Spring Boot conventions. Keep packages under the owning service namespace and follow layered module boundaries already present in `group`, payment, and agent modules. Prefer constructor injection, typed configuration properties, and clear DTO names such as `CreateOrderRequest` or `MemberQuotaResponse`. Frontend code uses TypeScript, React, Vite, ESLint, and Prettier; run `pnpm lint` or `pnpm fix` in `web` before submitting UI changes.

## Testing Guidelines

Backend tests use Maven/Surefire and Spring test tooling where needed. Name Java tests with the `*Test` suffix and keep fast unit tests isolated from MySQL, MQ, external LLMs, and payment sandboxes unless explicitly marked as integration tests. UI tests use Vitest via `pnpm test`.

## Commit & Pull Request Guidelines

This checkout does not include local Git history, so no repository-specific commit pattern can be verified. Use short, imperative subjects with a scope when useful, for example `member: add quota ledger idempotency`. Pull requests should include a summary, linked Trellis task or issue, validation commands run, and screenshots for UI changes.

## Security & Configuration Tips

Keep secrets in `.env` and mirror only safe defaults in `.env.example`. Do not bypass Gateway identity headers or internal token checks. Before coding in a scoped area, read the relevant `.trellis/spec/` guidance and active task files under `.trellis/tasks/`.
