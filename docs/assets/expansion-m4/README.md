# Milestone 4 UI capture provenance

Captured by `ExpansionM4UiTest` on 2026-11-14 from the actual shared Compose UI.
These are deterministic UI contract fixtures, not native platform screenshots.

- `batch-clips-desktop-en.png`: 960x900, English, partial success and explicit
  retry. The injected processor supports precise/portrait export but deliberately
  lacks preview/covers/caption burning; disabled controls reflect those capabilities.
- `batch-clips-phone-large-pt.png`: 320x800, pt-BR, 200% text. Unsupported controls
  remain disabled and the close action remains visible while content scrolls.

The [validation ledger](../../testing/NEXT_EXPANSION_VALIDATION.md) links native
FFmpeg, SQLite and player evidence separately.
