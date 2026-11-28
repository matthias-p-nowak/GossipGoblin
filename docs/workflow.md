# Collaborative development workflow

## 1. Purpose and autonomy

Develop incrementally through discussion, approval, execution, verification and review. The user controls direction and scope; the agent handles routine implementation decisions.

By default, execute one approved step at a time. The user may explicitly authorize multiple steps or a complete work package. Stop when a significant new decision, scope change, or failure requires discussion.

Small, clearly defined tasks may be executed directly when explicitly requested. Use collaborative planning for multi-step tasks, unclear requirements, significant design or architecture decisions, significant risks or dependencies, or difficult-to-reverse changes. Avoid unnecessary planning overhead.

## 2. Session start

Read `AGENTS.md`, this workflow, `docs/plan.md` and `docs/decisions.md` where present. Read `docs/vision.md` only when product direction is relevant, disputed or needed to interpret the current plan. Briefly summarize the current work, unresolved decisions or blockers, and suggested next action; include project direction only when it was relevant to the context read. Wait for user authorization before continuing; do not automatically resume previously approved work. An explicit current user instruction may authorize a clearly defined direct task.

Load other documentation and source code as needed, rather than reading the entire repository. If a required document is missing, identify the gap and establish it during planning as appropriate.

## 3. Documents and authority

| Document | Purpose | Update when |
|---|---|---|
| `AGENTS.md` | Essential agent rules | Rules change with user approval |
| `docs/workflow.md` | Collaborative process | Workflow changes with user approval |
| `docs/vision.md` | Long-term product direction | Product direction changes with user approval |
| `docs/plan.md` | Short list of current and next work | Priorities or current work change |
| `docs/decisions.md` | Accepted decisions and rationale | A decision is explicitly accepted or revised |
| `docs/requests.md` | Accepted requirements | A requirement is accepted |
| `docs/design.md` | Intended system behaviour | Intended design is agreed |
| `docs/architecture.md` | Actual technical architecture | Actual architecture changes |
| `docs/issues.md` | Open issues and deferred backlog | Issues are discovered or resolved |

Follow any repository-specific authority model. Otherwise, documentation authority for system behaviour is `requests.md` > `design.md` > `architecture.md` > `issues.md`: accepted requirements govern intended design, and intended design governs implementation. Accepted decisions constrain work within their scope; they do not silently override requirements. The plan authorizes work only through explicit approval and cannot override accepted requirements or decisions. Issues are an active queue, not a source of new requirements. If authoritative documents conflict, stop and resolve the conflict with the user.

Apply these document update rules unless the repository defines a more specific model:

- An accepted requirement decision updates `requests.md`, is normalized in `design.md`, and is reflected in `architecture.md` when implementation is affected.
- A design-only clarification updates `design.md`; update `architecture.md` only when implementation constraints change.
- An implementation behaviour change updates `architecture.md`; when behaviour or semantics change, also update `design.md` and, when the accepted requirement changes, `requests.md`.
- Major new or changed functionality is recorded in `design.md` before implementation, then the implemented outcome is recorded in `architecture.md` in the same task.
- Fix the first open issue before later issues unless the user changes the priority. After implementation and verification, remove the resolved issue from `issues.md` rather than retaining a completed-history log.

Keep documentation concise. Reference canonical sections or decision identifiers instead of copying text. Record accepted requirements and decisions during discussion, align design with agreed intentions, and update actual architecture alongside relevant implementation changes. Maintain `issues.md` as active issues and deferred backlog only. Remove duplicate draft text from temporary or draft notes after promoting it into canonical documentation.

## 4. Development cycle

### 4.1 Discussion and progressive planning

Investigate relevant existing code and documentation. Discuss the objective, alternatives, risks and constraints with the user. Ask exactly one significant decision question at a time; proceed to the next after the answer. Offer a recommendation and its rationale.

Keep `docs/plan.md` brief and update it when priorities or current work change. Put durable product direction in `docs/vision.md` and detailed requirements, decisions, design and architecture in their authoritative documents. Do not turn the plan into a specification, activity log or completed-work history.

Record explicitly accepted decisions immediately in `docs/decisions.md` and update relevant requirements and design documents. Minor decisions belong to the agent; significant, risky, uncertain or difficult-to-reverse decisions require discussion unless explicitly delegated. The discussion establishes whether experiments, prototypes or other changes are authorized; do not assume that discussion itself authorizes production implementation.

For the next step, establish the objective, scope and boundaries, acceptance criteria, verification requirements and known risks. Record any specific delegation or restrictions. Do not prescribe routine implementation details unnecessarily.

### 4.2 Approval

Obtain explicit user approval for the next step or work package. Record approval in the plan and create a local Git checkpoint before implementation. A proposed item, a user edit to the plan, or a previously approved unfinished step in a new session is not by itself authorization to start work.

### 4.3 Execution

Implement only approved work, respecting accepted decisions and scope. Independently choose routine implementation details. Do not silently expand scope, change accepted decisions, or introduce significant dependencies, interface changes or architectural changes outside the agreed boundaries.

Keep the plan current when work begins, priorities change, work becomes blocked or the next action changes. Do not log routine implementation activity.

### 4.4 Verification

Verify the implementation against all acceptance criteria. The agent chooses appropriate verification methods within agreed constraints. Add or update automated tests only where they meaningfully reduce failure or regression risk; do not write tests merely to increase coverage. Do not weaken acceptance criteria or agreed verification requirements. Record verification results and limitations accurately.

After successful implementation and verification, update relevant documentation and the plan, then create a local Git checkpoint before presenting the result for review. Do not claim unperformed verification succeeded.

### 4.5 Review

Report the outcome, verification, deviations and outstanding issues concisely. Present meaningful next actions and wait for the user unless further work is already explicitly authorized within the current work package. Discuss corrections and obtain approval where needed. Commit subsequent corrections after acceptance. Return to discussion and planning for the next step.

## 5. Failure handling

When an approved implementation approach or verification fails, stop immediately and return control to the user. Do not automatically retry, switch approaches, introduce workarounds, or enter a problem-solving loop. Do not conduct an extended diagnostic investigation without approval.

Report what was attempted, what went wrong (including relevant error details), the known or suspected cause, changes already made, and possible alternative approaches. Clearly distinguish confirmed causes from hypotheses. Record relevant findings and the blocked state in `docs/plan.md`. Preserve the current work for inspection; do not automatically revert or discard it. Further attempts require discussion and approval.

## 6. Shared plan

The user may modify `docs/plan.md` at any time, including during agent execution. Both user and agent maintain it.

- Read the latest version before every edit and before starting new work.
- Preserve user additions, deletions, edits and reordered items. Apply targeted edits rather than replacing the document from stale context.
- If concurrent changes conflict with approved work or cannot be reconciled safely, stop and discuss them.
- Never silently overwrite user changes or assume that a plan edit grants execution approval.
- Keep only a short checklist of active and next work, plus a one-line current status when useful.
- Remove completed items after review; Git retains the history.
- Mention a blocker only when it affects what happens next.

## 7. Accepted decisions

`docs/decisions.md` contains only currently valid, explicitly accepted decisions, including their rationale and conditions for reconsideration. Use stable identifiers (e.g. `D-001`). Refer to these identifiers from other documents where useful.

The agent may propose reconsideration but MUST NOT independently change accepted decisions. After user acceptance of a replacement, update the current decision record; Git retains the previous version. Do not store unaccepted proposals as accepted decisions.

Suggested format:

```markdown
## D-001 — Decision title
Decision: ...

Rationale: ...

Reconsider when: ...
```

## 8. Git checkpoints

Create local commits:

1. After plan approval, before implementation begins.
2. After successful implementation and verification, before user review.
3. After subsequent corrections have been accepted.

Inspect Git status and relevant diffs before staging. Include only changes belonging to the approved work, including associated documentation; preserve unrelated user changes. If changes cannot be separated safely, stop and consult the user. Use meaningful commit messages. Do not create empty commits merely to satisfy a checkpoint; identify the existing checkpoint if applicable. Never modify code on `main` or push directly to `main`. Committing does not authorize pushing or opening a pull request.

## 9. Existing shorthand workflows

### `new specs`

When the user requests `new specs`, use supplied file context for `# New specs` if available; otherwise locate the section in the relevant design document. Discuss ambiguous requirements one at a time. Do not promote drafts until acceptance criteria are explicit and the user accepts them. Move accepted requirements to `requests.md`, accepted decisions to `decisions.md`, and intended design to canonical design sections; remove promoted drafts and empty placeholders but preserve unresolved items. Actual architecture is updated when implementation changes. Follow normal planning and approval rules for code changes.

### `good`

Treat `good` as acceptance of the result currently under review. Check for conflicts with accepted specifications and report any found. Update plan completion and relevant documentation, and commit outstanding changes belonging to the accepted work if needed. Never stage unrelated user changes or create a redundant empty commit. Do not infer approval of a subsequent step.
