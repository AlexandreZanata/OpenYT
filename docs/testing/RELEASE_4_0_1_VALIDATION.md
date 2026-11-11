# OpenYT 4.0.1 candidate validation

Updated: 2026-11-11. **Candidate only: not published.**

Implementation and regression requirements are in
[ADR-0024](../adr/0024-windows-media-and-shortcuts.md) and
[Windows desktop contracts](WINDOWS_DESKTOP_CONTRACTS.md).

## Executed locally

- Full deterministic suites: 216 discovered, 205 executed successfully, 11
  opt-in skips, zero failures/errors. Includes eight new path/action, external
  fallback and Library/Downloads failure-message tests.
- Android debug APK assembled successfully. This is a build result, not a
  physical-device installation claim.
- Linux DEB 4.0.1 built and extracted into isolated application/data directories.
  `validate-linux.sh --scenario all --no-build --offline` passed. Four external
  network scenarios were deliberately skipped. Metadata, cookies, library,
  archive, folder import, storage, bulk operations, playback discovery/queue and
  GUI startup checks passed.
- Real Linux VLC playback passed, including decoded video, mini-player and
  checkpoint/resume. The latest test harness compiled successfully.
- Bugbot reported one P2 inherited VLC_PLUGIN_PATH isolation issue. The bundled
  Windows runtime now clears that MSVCRT variable before discovery; the native
  contract injects a conflicting value and checks the native environment.

## Windows CI findings and pending verification

[CI 43092772390](https://github.com/AlexandreZanata/OpenYT/actions/runs/43092772390)
compiled Windows and built both MSI and EXE. The installer created desktop and
Start-menu shortcuts with the expected launcher targets. The first native
contract stopped at icon-reference validation: an empty IconLocation is a valid
reference to the target executable's embedded icon. The contract was corrected
to resolve that default and compare the image with the OpenYT ICO.

[CI 43094336545](https://github.com/AlexandreZanata/OpenYT/actions/runs/43094336545)
again built both installers but failed the installed-contract step. It remained
stuck in evidence upload; job-log retrieval returned HTTP 502/read timeouts.
Normal and forced cancellation requests were accepted but did not promptly
finalize the run. Its exact second failure could not be diagnosed from available
logs. Do not interpret it as a passed Windows contract.

Subsequent test-tool changes explicitly load Windows imaging support, preserve
shortcut metadata and native diagnostics, and bound evidence uploads to two
minutes. A diagnostic dispatch can skip uploads. Product behavior remains
unapproved until the native contracts actually pass.

## GitHub Actions service blocker

The independent diagnostic
[43096258303](https://github.com/AlexandreZanata/OpenYT/actions/runs/43096258303)
failed at startup without creating jobs. Its public annotation states:
"An unexpected error has occurred and we've been automatically notified."
GitHub request ID: `b4ce1198-de0f-11f1-8167-e76c4c007139`.

Diagnostic [43096894389](https://github.com/AlexandreZanata/OpenYT/actions/runs/43096894389)
and the separate release candidate
[43097538295](https://github.com/AlexandreZanata/OpenYT/actions/runs/43097538295)
also failed at startup without executing jobs. This prevents obtaining the
remaining Windows evidence. No protected-branch checks or release gates have
been bypassed.

## Exit criteria before publication

1. Obtain the failed Windows contract's diagnostics and resolve the actual cause.
2. Pass both installed MSI/EXE contracts: branded shortcut launch, exact Unicode
   external-open handler, Explorer selection and decoded bundled-VLC playback.
3. Pass the normal PR suite, integrate through dev into main, and let the release
   workflow validate all native packages and publish 4.0.1 with available signing.
4. Run `scripts/verify-release.py v4.0.1 --commit <tested-main-sha>` and attach the
   final workflow/evidence links. Until then, the stable release remains 4.0.0.
