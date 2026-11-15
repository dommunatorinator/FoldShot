# Security Policy

## Supported Versions

Only the latest FoldShot release series receives security updates and Play Protect hygiene verification:

| Version | Supported |
| ------- | --------- |
| 2.2.x   | Yes       |
| < 2.2   | No        |

## Privacy & Security Architecture

FoldShot is engineered as a **100% offline Android utility**:

- **Zero Network Access**: `android.permission.INTERNET` is never declared in `AndroidManifest.xml`. Screenshots, settings backups, and vault items never leave the device.
- **No Persistent Boot Hooks or Accessibility Services**: FoldShot does not declare `RECEIVE_BOOT_COMPLETED`, `BIND_ACCESSIBILITY_SERVICE`, or `MANAGE_EXTERNAL_STORAGE`.
- **Encrypted Hidden Vault**: Vault media is encrypted in app-private storage (`filesDir/vault`) using `AndroidKeyStore` (`AES/GCM/NoPadding`, 256-bit), guarded by salted `PBKDF2WithHmacSHA256` PIN verification (`MessageDigest.isEqual`), ephemeral unlock tokens, and `WindowManager.LayoutParams.FLAG_SECURE` with auto-lock on background.
- **Path Traversal Prevention**: `ClipboardFileProvider` and `HiddenVaultManager` validate canonical file paths before opening or deleting files.
- **Release Signing**: Official CI APKs are aligned with `zipalign` and signed exclusively with APK Signature Scheme v2/v3 (legacy JAR v1 disabled).

## Verifying Official APKs

You can verify the downloaded `FoldShot.apk` before installing using Android Build Tools:

```bash
apksigner verify -v --print-certs FoldShot.apk
```

Expected signer DN: `CN=FoldShot Utility, OU=Mobile Engineering, O=FoldShot, L=San Jose, ST=CA, C=US`.

## Reporting a Vulnerability

Please **do not open a public GitHub issue** for security vulnerabilities.

Instead, report vulnerabilities confidentially through GitHub **Private Vulnerability Reporting**:

1. Open the repository's **Security** tab -> **Advisories** -> **Report a vulnerability**:
   https://github.com/dommunatorinator/FoldShot/security/advisories/new
2. Include the affected version, steps to reproduce, and potential impact.

Reports are reviewed promptly, and confirmed issues will be patched, verified via `tools/verify_foldshot.py` and CodeQL scanning, and credited in release notes if desired.
