# Source identifiers

OpenYT uses the installed engine's extractor catalog, rather than maintaining a
claim that every website is always available. Open **Sites and sources** to search
that catalog. A listed extractor can require authentication, have regional limits,
or be broken upstream. A logo never indicates a partnership or guaranteed access.

The shared resource collection includes 143 additional Simple Icons vectors,
alongside the original 16 source glyphs. Upstream commit
`cd796c98565fc466923fc1128b962e133fa3c612` is pinned for reproducibility. Assets are
converted to Android XML vectors so the same path geometry renders on both hosts.
The [CC0 license](licenses/simple-icons-CC0.md) accompanies these assets.

Usage rules:

- Keep the accessible source name, including when only an icon is visible.
- Use monochrome foreground colors from the current theme. Do not imply that a
  site's trademark belongs to OpenYT or incorporate it into the OpenYT wordmark.
- Resolve a logo only from an explicit, reviewed brand alias. Similar text is not
  enough to identify a brand (for example Audi and Audius are different sources).
- Sources without a verified logo receive a distinct two-character name tile.
  Every extractor therefore has an identifier without inventing official logos.
- Use at least 24 dp for informational icons and 48 dp for interactive targets.
- Add resource-load coverage and inspect both themes when introducing an alias.

`SourceIconCatalog.kt` contains reviewed aliases. `ProviderIcon` implements the
fallback. The original OpenYT app icon and wordmark follow the separate
[brand guidelines](README.md).
