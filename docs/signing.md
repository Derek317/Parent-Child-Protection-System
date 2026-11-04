# Private APK signing

The previously committed signing identity is compromised. Never reuse it. Removing it from current files does not revoke the copy in history or prior APK trust.

Generate and back up a new private key **on your own trusted machine**, outside the repository:

```sh
keytool -genkeypair -storetype JKS -keystore ig-guard-release.jks -alias igguard -keyalg RSA -keysize 3072 -validity 10000
```

Use strong passwords when prompted. Store an encrypted backup securely; losing the key can prevent future app updates. Do not commit the keystore or put passwords in commands, issues or chat.

In the GitHub repository's Settings → Secrets and variables → Actions, configure:

| Secret | Value |
| --- | --- |
| `APK_KEYSTORE_BASE64` | Base64 contents of the new keystore |
| `APK_KEYSTORE_PASSWORD` | Keystore password |
| `APK_KEY_ALIAS` | The alias chosen during generation |
| `APK_KEY_PASSWORD` | Key password |

With GitHub CLI authenticated on that machine, the keystore can be supplied without printing it:

```sh
base64 < ig-guard-release.jks | gh secret set APK_KEYSTORE_BASE64 --repo Derek317/Parent-Child-Protection-System
gh secret set APK_KEYSTORE_PASSWORD --repo Derek317/Parent-Child-Protection-System
gh secret set APK_KEY_ALIAS --repo Derek317/Parent-Child-Protection-System
gh secret set APK_KEY_PASSWORD --repo Derek317/Parent-Child-Protection-System
```

The last three commands prompt for values. Run the workflow manually after all four secrets are set. CI uses them only in the packaging step and removes its temporary keystore afterward. Partial configuration fails with a clear error.

Without secrets, tests and release compilation still run and upload **`ig-guard-unsigned-apk`**. This artifact requires private signing before installation. With all four secrets configured, the artifact is **`ig-guard-signed-apk`**. A new key normally requires reinstalling existing installations; arrange records/backups or a signing-lineage migration before replacing an installed production APK.
