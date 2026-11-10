# Signing and notarization

## Android

Production signing is configured through repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The generated keystore is at `~/.openyt/android-release.jks`; credentials are in
`~/.openyt/android-signing.properties` (mode 600). Neither file is in the repository.

**Back up the keystore and passwords.** If the key is lost, production-signed APKs
can no longer update installed apps; a new application ID/key would be required.

Verify a release APK:

```bash
apksigner verify --print-certs androidApp-release.apk
```

The certificate should show `CN=OpenYT`, not `CN=Android Debug`.

## Windows and macOS

Trusted signing/notarization cannot be produced without your certificates:

- Windows: an Authenticode code-signing certificate (PFX) and password; sign the
  MSI with `signtool` and timestamp it.
- macOS: an Apple Developer ID Application certificate, a Developer ID team, and
  notarization credentials (Apple ID/app-specific password or API key). The DMG/app
  must be signed with a hardened runtime, then notarized and stapled.

Until these credentials are supplied, MSI/DMG artifacts are **unsigned**; Windows
SmartScreen and macOS Gatekeeper will warn.
