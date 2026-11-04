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

## Diagnose signing failures

The packaging step verifies the decoded keystore, alias and private-key password before compiling the APK. It reports only validation results; private keys, passwords, file hashes and certificate details are not printed.

- `[SIGNING_BASE64]`: the secret is not valid Base64 contents.
- `[SIGNING_STORE]`: the decoded file cannot be opened with the supplied store password. A different/corrupted file and a wrong password can produce the same JKS error. Test that exact local file/password, then replace the Base64 secret from that file and update the store-password secret together.
- `[SIGNING_ALIAS]`: the store password works, but the alias does not identify an entry.
- `[SIGNING_KEY_PASSWORD]`: the store and alias work, but the private-key password is wrong or the entry is not a usable private key.

Using one password is supported: set **both** `APK_KEYSTORE_PASSWORD` and `APK_KEY_PASSWORD` to that same value. Passwords go to keytool through environment references, never command-line password literals or diagnostic output. The validation CSR contains public information only and is removed with the temporary keystore on success or failure.

To verify the password locally without putting it in shell history, use Bash:

```sh
read -r -s -p 'Keystore password: ' IG_GUARD_LOCAL_PASS
printf '\n'
export IG_GUARD_LOCAL_PASS
keytool -list -keystore ig-guard-release.jks -storepass:env IG_GUARD_LOCAL_PASS -alias igguard
unset IG_GUARD_LOCAL_PASS
```

Enter the actual password at the hidden prompt. A certificate fingerprint printed by keytool identifies the certificate; it does not by itself prove keystore integrity if keytool was run without a password. Never send the password or keystore contents in chat.
