# Contributing

1. Read `AGENTS.md` and `docs/architecture/OVERVIEW.md`.
2. Create a focused branch; keep changes small and reversible.
3. Add or update tests for behavior changes.
4. Run the checks that cover your change:

   ```bash
   ./gradlew :core:jvmTest :ui:jvmTest
   ./gradlew :androidApp:assembleDebug
   ./gradlew :desktopApp:compileKotlin
   ```

5. Open a pull request describing the behavior change, evidence, and limitations.

Code and documentation are written in English. User-facing strings must be
localizable (pt-BR is the primary locale, English secondary).

Do not add dependencies, modules, or abstractions without a concrete use case.
Do not commit secrets, signing keys, downloaded media, or tool binaries.
