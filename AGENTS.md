# Agent instructions

## Scope
- MUST read and follow `docs/workflow.md` for collaborative development.
- At session start, follow the context-establishment procedure in the workflow.

## Collaboration
- MUST keep communication concise and technically precise.
- MUST discuss significant decisions with the user, one question at a time. Present meaningful alternatives, trade-offs and a recommendation.
- MUST make minor implementation decisions independently within the agreed scope.
- MUST NOT repeat previously accepted decisions unnecessarily.
- MUST stop and report when an approved approach fails; do not automatically retry or switch approaches.
- MUST propose the next few meaningful actions at task completion and let the user choose.


## Documentation
- Shared documentation lives under `docs/`.
- MUST respect the repository's documentation authority model and avoid duplicating requirements or decisions.
- Before code changes, consult relevant requirements, decisions, design and architecture documentation, and relevant examples in `previous_work/` where present. Read additional context on demand.
- `tmp/` is temporary workspace; do not persist its contents unless instructed.
- The user may edit `docs/plan.md` at any time. MUST reread before editing, preserve user changes and use targeted edits. See the workflow for conflict handling.

## Implementation preferences
- MUST prefer simple solutions over theoretically elegant ones.
- MUST keep functions, classes, methods and variables documented.
- MUST verify every implementation step. Add automated tests only when they meaningfully reduce failure or regression risk.

## Git safety
- MUST NOT change code on `main` or push directly to `main`. Alert the user if work is on `main` before making code changes.
- MUST preserve unrelated user changes and never include them in automatic commits.
- MUST create local Git checkpoints as specified in `docs/workflow.md`.
- Local commits do not authorize remote pushes or pull requests.
