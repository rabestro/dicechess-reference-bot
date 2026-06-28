# AGENTS.md

Branch naming rules, milestones, and agent guidance for the Dice Chess Reference Bot repository.

## Branch Naming Conventions

Branch name pattern: `<type>/<short-description>`, optionally `<type>/<id>-<short-description>` to link an issue.

Allowed prefixes:
- Issue-driven: `task` (work items), `feat` (features), `bug` (fixes) — typically carry an `<id>`.
- Issueless: `refactor`, `chore`, `docs`, `ci`, `test`, `perf` — no issue required.

Examples: `feat/2-setup-repo-services`, `refactor/extract-strategy`, `chore/bump-deps`.

## Agent Rules (AI Assistance)
- Issue-driven work (`task`/`feat`/`bug`) starts from an issue; the branch carries its `<id>` and the PR links it with `Closes #<id>`. Issueless work (`refactor`/`chore`/`docs`/`ci`/`test`/`perf`) needs no issue. Name the branch per the pattern above.
- Always run `sbt scalafmtAll` on any generated code and ensure local validation passes before proposing a PR.
- Human retains the ultimate authority to review, approve, and merge the PR.
- **GitHub CLI Authentication**: On macOS, credentials are saved in the Keychain. When executing `gh` commands in a local interactive session, if keychain prompt issues occur, explicitly set the token to an empty string (e.g., `GH_TOKEN="" gh issue create ...`) to avoid authentication errors. Do **not** clear `GH_TOKEN` in CI/CD runners or remote non-interactive environments, as they rely on this token to authenticate.

## Developer Workflows
- **Code Formatting**: `sbt scalafmtAll` runs scalafmt across the Scala sources.
- **Local Validation**: `sbt scalafmtCheckAll clean test` compiles everything and runs the tests.

## Approved GitHub Labels

Use ONLY these labels when generating `gh` commands:

* **Shared core** (identical across all Dice Chess repositories):
  * **bug** — Something isn't working.
  * **enhancement** — New feature or request.
  * **refactoring** — Code restructuring without behavioral changes.
  * **documentation** — Improvements or additions to documentation.
  * **testing** — Adding unit, property-based, or integration tests.
  * **performance** — Micro-optimizations and performance enhancements.
  * **ci-cd** — GitHub Actions, build scripts, or workflow configurations.
  * **dependencies** — Dependency updates (applied by Dependabot).

* **Domains** (this repository only):
  * **api** — Communication protocol and JSON serialization.
  * **infrastructure** — Docker and deployment configurations.
  * **engine** — Integration with the Dice Chess engine.
