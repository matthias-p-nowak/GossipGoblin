# Project purpose - twofold
- create a new `AGENTS.md` to support agentic coding
- implement the task

# Guiding principle
The user controls vision, priorities, scope, major decisions, and acceptance. The agent controls technical implementation and iteration within an approved task.

# Inputs vs. agent-maintained docs
- `task/` is **user input**; the agent reads it and MUST NOT edit it unless asked (it may propose edits).
  - `task/vision.md` — long-term target
  - `task/next.md` — the current approved step; the agent may suggest a different one but does not change direction without approval
- `docs/` is **created and maintained by the agent** (create files when first needed):
  - `docs/requests.md` — accepted requirement decisions from discussions
  - `docs/design.md` — canonical design decisions, with alternatives and rationale for important ones
  - `docs/architecture.md` — implemented architecture and constraints
  - `docs/issues.md` — active queue only (`Active issues`, `Deferred backlog`; no history)
  - `docs/log.md` — minor decisions taken by the agent during implementation
- `experiments/` — temporary experiment code; `experiments/index.md` is the durable record (see Experiments).
- `tmp/` — temporary workspace (git-ignored); do not persist changes unless instructed.

# Precedence
- Authority order: `task/vision.md` + `task/next.md` > `docs/requests.md` > `docs/design.md` > `docs/architecture.md` > `docs/issues.md`.
- If agent docs contradict `task/`, point it out and ask; do not silently override user input.

# Development cycle
1. Suggest the next feature (based on `task/next.md`, else `task/vision.md` priorities) and identify consequential uncertainties.
2. Discuss; major decisions are taken together → record in `docs/requests.md`, normalize in `docs/design.md`.
3. If a significant uncertainty warrants it, propose a small, focused experiment and ask for approval.
4. After approval, implement and iterate autonomously within the agreed scope; minor decisions → `docs/log.md`.
5. Before presenting a runnable version, build, launch, and perform relevant checks (see Verification).
6. Advise how to test; the user tries it and gives feedback. Small tweaks within scope need no fresh approval.
7. Only the user declares a result accepted. Acceptance does not authorize integration into production.
8. Record the outcome in `docs/architecture.md` (and `experiments/index.md` if applicable).
9. Propose the next few steps and let the user choose; wait for a separate request to integrate or move on.

# Asking questions
- Ask only when a blocking decision exists and cannot be inferred from context.
- One question at a time; proceed to the next only after the prior answer.
- Number the options.

# Experiments
- Prefer standalone, interactive, single-purpose experiments under `experiments/NNN-name/` with a `README.md`.
- Experiment inside the app only when recreating services separately is disproportionately costly; isolate with a branch or feature flag.
- Provide a straightforward way to run and evaluate it, ideally a single command.
- Explore one approach at a time.
- Experimental code is temporary; knowledge is durable. Never treat an accepted experiment as production-ready.
- Delete experiment code only with user approval and after conclusions are preserved; keep its `experiments/index.md` entry with Git references.

# Risks and decisions
- Proactively identify and prioritize technical risks by likelihood and consequence; reduce the largest uncertainties early.
- Defer architectural decisions until evidence makes them necessary.
- Challenge proposed solutions constructively, explaining simpler, safer, or more maintainable alternatives.
- The user's informed decision is final unless new evidence justifies revisiting it.
- Significant architectural changes, scope changes, and vision changes require approval.

# Troubleshooting
- Per distinct technical problem, make at most seven meaningful unsuccessful attempts, each testing a different hypothesis.
- After seven failures, summarize attempts, findings, obstacles, and recommendations, then ask the user.
- Consult earlier when an architectural decision or scope change is required.

# Verification and tests
- Before user evaluation: build, run relevant existing tests, verify startup, perform a basic functional check.
- Add automated tests when practical and when they meaningfully reduce risk; prioritize complex calculations, concurrency, data integrity, and error handling (e.g. timing correlation, recording continuity).
- Simple, low-risk code needs no dedicated tests; briefly explain how it was checked instead.
- Passing checks is not user acceptance.

# Documentation rules
- Keep docs concise; avoid duplicating text across docs; prefer references to canonical sections.
- For major new or changed functionality: update `docs/design.md` before implementation and `docs/architecture.md` afterward (same task).
- Design-only clarification → `docs/design.md`; touch `docs/architecture.md` only when implementation constraints change.
- Implementation behavior change → `docs/architecture.md`; if semantics changed, also `docs/design.md` and possibly `docs/requests.md`.
- Issue lifecycle: fix the first open issue, then remove it after implementation + verification.
- Doc updates happen in the same task as the code/behavior change.
- Before code changes, read the relevant `task/` and `docs/` files and MUST NOT generate code that contradicts them.

# Preferences
- Prefer simple solutions over theoretically elegant ones.
- Keep functions, classes, methods, and variables documented.

# Project implementation
- Product source: `app/src/main/`; device integration tests: `app/src/androidTest/`.
- Build, deterministic unit tests, and static checks: `./gradlew testDebugUnitTest assembleDebug lintDebug` (JDK 17, Android SDK 35; see `README.md`).
- Real-device tests: `bash tools/test-device.sh DEVICE_SERIAL`. These use the microphone; disarm and leave the app before testing so live analysis stops. Never treat an ADB shell exit code alone as proof that instrumentation passed.
- Read `docs/testing.md` for recording lifecycle acceptance checks and update measured results in `docs/architecture.md`.
- Keep generated APKs, signing keys, SDK downloads, logs, and screenshots out of version control. Never edit `task/` to resolve a discrepancy with implementation.

# Git
- The agent may create and switch task branches independently within an approved task.
- Commit meaningful working changes on the task branch with descriptive messages; preserve accepted checkpoints and never lose unmerged work.
- Merging into `main`, pushing to `main`, and deleting unmerged work require explicit user approval.
- Production integration requires a separate user request.

# Workflow: "wrap up work"
Applies only when the user explicitly says "wrap up work":
- Check whether the implementation violates any design specification due to user requests; if so, point it out.
- Stage all modified files.
- Commit with a concise summary of current status as the message.
