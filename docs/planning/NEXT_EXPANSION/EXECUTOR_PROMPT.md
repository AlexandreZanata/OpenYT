# Executor prompt and handoff protocol

Use this prompt with one task ID. The plan is intentionally written in English
to follow repository rules; communicate progress/results to the user in pt-BR.

## Copy/paste prompt

```text
Work in the OpenYT repository. Implement ONLY task <TASK_ID> from
docs/planning/NEXT_EXPANSION/. Replace <TASK_ID> with the selected task ID before
starting. If it is missing, read PROGRESS.md and propose the first ready task;
do not execute the entire roadmap by assumption.

Read AGENTS.md, docs/architecture/OVERVIEW.md, NEXT_EXPANSION/README.md,
PROGRESS.md, CONTRACTS.md, the selected phase and its relevant ADRs. Inspect the
current implementation before editing. The plan describes future APIs and tests;
do not claim those already exist.

Confirm that prerequisites are VERIFIED. Check the current branch and working
tree; preserve unrelated user edits. Stay on the authorized working branch or
create an isolated codex/ branch when appropriate. Do not reset someone else's
work. Compare current code with the 4.0.1 baseline notes, not just the old roadmap.

Write a short local task checklist. Implement the smallest complete behavior for
this task using existing modules, repositories and platform injection. Follow the
frozen decisions in the plan. Product code must remain Kotlin. Do not add services,
raw shell command strings, UI filesystem calls, arbitrary new architecture,
placeholder methods, fake success, or UI controls without working behavior.

For a dependency, first document the concrete need, pinned version, license,
security/support status, platform support and distribution impact. A dependency
mentioned in this plan is not automatically approved. Stop the dependent task if
the gate fails; continue only independent authorized work.

Add meaningful tests for the task's named contracts, including failure and
backward-compatibility cases. Keep deterministic fixtures, local-tool/native
tests and real-network tests separate. Run the smallest relevant checks first.
After passing checks, run the task/phase's required broader validation. Read the
actual outputs; never report success from a command you did not run.

Update relevant usage/architecture docs only for implemented behavior. Record
the task status, contract/test paths, commands, results, native limitations and
remaining work in PROGRESS.md. A task requiring an unavailable native check stays
IMPLEMENTED or BLOCKED, not VERIFIED. A phase is incomplete until its gate passes.

Leave an atomic reviewable diff and the handoff below. Commit only when authorized
by the execution request, using an English behavior-based message. This planning
package alone does not authorize pushing main, merging, changing the release
version, creating a tag, publishing a release or installing OS background jobs.

If architecture reality contradicts a frozen decision, stop that dependent step
and explain the concrete mismatch and smallest options. Do not silently substitute
a stub, weaker contract, another engine, or a cloud fallback. Ask only for a
decision you cannot resolve from existing authorization and repository evidence.
```

## Work size and stop points

- One task per change by default. A task is a complete vertical slice or a small
  foundational contract, not an entire phase. Do not take unrelated cleanup.
- If a task outgrows one reviewable change, split it into `NXn.m.a`/`NXn.m.b` in
  the phase and progress file before proceeding. Keep the parent gate and criteria.
- Stop on a failing prerequisite, incompatible license, unavailable required API,
  destructive ambiguity, or native gate that cannot be exercised. Preserve work
  and provide exact reproduction/evidence. A capability spike may legitimately
  conclude NO-GO; that is not a completed user feature.
- Do not ask the user to choose routine names, SQL migration numbers, or fixture
  paths. Use this specification and existing conventions.
- Temporary files, benchmarks and logs belong in ignored build/test locations.
  Keep small redistributable fixtures and their provenance in source control.

## Status meanings

- `PENDING`: not started.
- `IN_PROGRESS`: current task; include branch and scope.
- `IMPLEMENTED`: code exists but required verification is incomplete.
- `BLOCKED`: name the unmet prerequisite or external decision; retain evidence.
- `VERIFIED`: task acceptance checks actually passed, with reproducible evidence.
- `NO_GO`: an optional capability gate rejected the feature; document why and
  remove any promise/control that implies it works. This is not VERIFIED delivery.

Checking a task off means VERIFIED, not merely that code was written. The NX7
and NX9 optional gates do not prevent completion of the eight main expansions
when their deferred status is explicitly recorded in product documentation.

## Required handoff after every task

```text
Task: NXn.m — title
Status: IMPLEMENTED / VERIFIED / BLOCKED / NO_GO
Branch and commit (or uncommitted diff):
Behavior changed:
Files and ADRs changed:
Contracts added/updated (IDs and exact test methods):
Commands actually executed and results:
Native environment / device / tool versions:
Failure-path and migration evidence:
Known limits / blocked checks / decisions needed:
Next ready task and why its prerequisites are satisfied:
```

For a failed test, include the relevant assertion/error and the next diagnostic
step. Sanitize logs. Do not attach credentials or unnecessary full local paths.
