# Windows desktop regression contracts

These contracts cover the Windows Play/Open, Show in folder and missing desktop
shortcut reports fixed in 4.0.1. They are release requirements, not a promise that
every future OS/environment failure is impossible.

## W01 — Reveal selects the exact file

For an existing path with spaces, accents, percent, ampersand, parentheses and a
hash, Show in folder must open Explorer and select that exact item. Success
cannot mean merely starting explorer.exe. The native contract invokes the real
adapter and polls Shell.Application's selected items. Unicode paths remain data.

## W02 — Open reaches the associated application

The installed runtime invokes the real ShellExecuteW path. A unique temporary
HKCU file extension points to a local recording handler, which must receive the
entire unchanged path. Existing media associations are never replaced. The
contract removes only its own unique registry keys afterward.

## W03 — Play works without a machine VLC installation

The installed app's resource directory must contain both native DLLs, plugins
and license/source notices. Discovery must load libVLC from that directory and
report version 3.0.24. Bundled FFmpeg generates an MP4 at a Unicode path. The real
player must open, advance its playback time, show decoded colored video, and
switch to a 420-pixel always-on-top mini-player. A screenshot is retained.
The process starts with a conflicting inherited VLC_PLUGIN_PATH. The harness
checks that the actual CRT environment used by this VLC no longer includes that
external path, preventing plugins from another installation from being mixed in.

## W04 — Both installers create usable branded shortcuts

Install the actual MSI silently. Desktop and Start-menu shortcuts must exist,
target the installed launcher and reference an existing icon resource. Launch
the desktop shortcut and require an application window. Uninstall without
deleting the user-media fixture, install the actual EXE and repeat all installed
contracts. Checking only Gradle flags or installer existence is insufficient.

## W05 — Action failures are visible

Compose UI tests click Open and Show in folder in both Library and Downloads.
A rejected action must display localized recovery instructions and an operable
Close action. Missing files must not be reported as successfully opened; native
exceptions must not escape as raw diagnostics or disappear silently.

## Execution

Deterministic path/action and shared-screen error tests:

```bash
./gradlew :desktopApp:test --tests '*DesktopMediaActionsTest' --tests '*FileActionsUiTest'
```

Native Windows packaging validation, on an isolated Windows runner with installer
privileges and an interactive desktop:

```powershell
./gradlew :desktopApp:packageMsi :desktopApp:packageExe :desktopApp:windowsContractHarnessJar
./scripts/validate-windows.ps1
```

The test-only harness runs from a temporary alternate launcher/config beside the
installed application. It uses the installed JVM, shipping jars and native DLLs;
only its main class and additional test JAR differ. The original launcher/config
are unchanged, and the temporary files are removed. No test command or harness
is shipped in the product. Logs and screenshots live in
`build/windows-validation/` and the `windows-installed-contracts` CI artifact.

The required Windows matrix check runs these contracts for pull requests. The
release Windows job repeats them before publishing; a failed contract fails the
desktop job, preventing publication. Existing Linux playback and Android
instrumentation remain separate regression checks.
