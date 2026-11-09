# ADR-0001: Kotlin Multiplatform with shared Compose UI

- Status: accepted
- Date: 2026-11-09

## Context

ClipBaú targets Android, Linux, Windows, and macOS with one product codebase.
Abastevo demonstrates a mature Kotlin/Compose Android architecture, but it is an
Android-only app with a Go backend. ClipBaú has no backend and must share domain
logic and UI across platforms.

## Decision

Use Kotlin Multiplatform with `androidTarget` and a JVM desktop target. Keep domain
and application logic in a pure `core` module. Put shared Compose Multiplatform UI
in a `ui` module. Host applications are thin: they provide platform ports and a
window/Activity entry point.

Use one Gradle build, Kotlin DSL, and a version catalog. Avoid build-logic
convention plugins until duplication justifies them.

## Consequences

- One implementation of business rules and one UI for Android and desktop.
- Compose for desktop is a JVM dependency, not a second UI stack.
- Android-specific APIs stay in `androidApp`; they cannot leak into `core`.
- iOS is out of scope; adding it later requires a new target and ADR.
