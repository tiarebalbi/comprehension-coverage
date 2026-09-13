---
name: Task
about: A scoped, reviewable-in-one-sitting unit of work
title: ""
labels: []
assignees: []
---

## Scope

What this issue covers, and — as importantly — what it explicitly does not.
Name the blocking issue(s), if any.

## Constraints

Spec sections, constitutional decisions, or prior calibration decisions that
bound the approach. Note any known spec/prototype conflict this touches
(see `CALIBRATION.md`) rather than resolving it silently.

## Done criteria

Concrete, checkable conditions. Prefer "X returns Y for input Z" over
"X works correctly."

## Test requirements

What must be tested and how (unit test, golden-fixture parity, manual run
against a real repo, etc). If this touches scoring or evidence semantics,
state whether golden tests apply or whether there is no reference to match
against yet (say so explicitly — don't invent one).

## Estimated review size

Rough non-generated diff size (e.g. "~150 lines") and whether it fits in one
sitting per `CLAUDE.md`. If not, note how it should be split.
