# Build one APK with the model inside (on your own PC)

**Read this first.** The packaged APK contains the analysis model. The model was trained partly on real conversations. **Never upload the packaged APK to GitHub (not as a release, an artifact, an attachment or an issue), never put it in a cloud drive or a chat, and never share it with anyone.** Keep it on your PC and your own phones. The GitHub build never contains the model, and nothing in the app downloads it.

The result is one file you install like any app. On the child's phone the app copies the model out of itself on first start, so nothing has to be copied or picked by hand. If you prefer, the older way still works: install the normal APK and import the file with the file picker.

## What you need (once)

1. **The model file** `qwen3-1.7b-lora-v7-Q4_K_M.gguf` (1,107,408,736 bytes). The script checks its size and SHA-256 against the values written in `RiskModel.kt`; any other file is refused and nothing is written.
2. **Python 3.12.** Nothing else to install for the script (it uses only the standard library). On Windows use `py -3.12`.
3. **A JDK (17 or newer).** Needed by the Android signing tool. Check with `java -version`.
4. **Android SDK build-tools**, which hold `zipalign` and `apksigner`. If you have Android Studio, open **SDK Manager → SDK Tools → Android SDK Build-Tools** and tick version 34.0.0. Without Android Studio, download "Command line tools only" from developer.android.com, unzip it to `C:\Android\sdk\cmdline-tools\latest`, then run:

   ```
   C:\Android\sdk\cmdline-tools\latest\bin\sdkmanager.bat --sdk_root=C:\Android\sdk "build-tools;34.0.0"
   ```

   Answer `y` to the licence question. The tools end up in `C:\Android\sdk\build-tools\34.0.0`. (On Linux the same command works with `sdkmanager`.)
5. **The signing keystore** (`ig-guard-release.jks`) that signs the GitHub release, kept outside the repository. See [signing.md](signing.md). You need its alias (normally `igguard`) and its passwords.
6. **A copy of this repository** on the PC (for `tools/package_with_model.py` and `RiskModel.kt`). You do not build anything from it.
7. **About 6 GB of free disk space** on the drive of the output folder while the script runs. It needs a few copies of the 1.1 GB model and APK at once and deletes them as it goes.

## Get the APK from GitHub

1. On GitHub open the repository's **Actions** tab and the latest successful **build-apk** run for the branch you want.
2. Under **Artifacts** download **`ig-guard-signed-apk`** (use `ig-guard-unsigned-apk` only if the secrets are not set; the script signs it anyway). It is a zip with one `.apk` inside. Unzip it.

This APK has no model in it, so it is fine to download. Only the output of the script must stay private.

## Run the script

Open PowerShell in the repository folder and run (change the paths):

```
py -3.12 tools\package_with_model.py `
  --apk C:\Users\you\Downloads\ig-guard-signed-apk\app-release.apk `
  --model D:\models\qwen3-1.7b-lora-v7-Q4_K_M.gguf `
  --out dist\ig-guard-with-model.apk `
  --keystore D:\keys\ig-guard-release.jks `
  --ks-alias igguard `
  --build-tools C:\Android\sdk\build-tools\34.0.0
```

(On Linux or macOS use `python3.12`, `/` in paths and `\` instead of the backtick.)

- It asks for the keystore password with a hidden prompt, and then for the key password (press Enter if you used one password for both, which `signing.md` supports). To avoid the prompt set `IG_GUARD_KEYSTORE_PASSWORD` and `IG_GUARD_KEY_PASSWORD` in that terminal first. Passwords are never taken from the command line and never printed.
- `--build-tools` can be left out if `ANDROID_HOME` or `ANDROID_SDK_ROOT` points at your SDK; the script then uses the newest build-tools folder.
- `--out` must end in `.apk`. The script refuses to write inside the git work tree unless the path is ignored by git. `dist\` and `*.apk` are ignored, so `dist\ig-guard-with-model.apk` is fine. Add `--force` to overwrite an earlier output.

It takes a few minutes. It checks the model, copies the APK entry by entry (keeping each entry's compression, dropping the old signature files), adds the model as `assets/risk/qwen3-1.7b-lora-v7-Q4_K_M.gguf` **uncompressed**, runs `zipalign -p -f 4`, signs with `apksigner` (v2 and v3), and verifies the result. At the end it prints the output path, its size and SHA-256, the signing certificate's SHA-256, and writes `dist\ig-guard-with-model.apk.sha256` next to it.

Why those `zipalign` flags: the GitHub build is made with Android Gradle plugin 8.3.2 for Android 8.0 and newer (`minSdk 26`), and the workflow does not change how native libraries are packed. So `libigguard_risk.so` is stored uncompressed and is not extracted at install time. `-p` keeps it page-aligned so it can be loaded straight from the APK, and `4` aligns everything else. Because the minimum Android version is 8.0, the old v1 signature is not needed and the script does not add one.

## The certificate warning

The script prints three certificate digests when it can: the one that signed the APK you gave it (if it was signed), the one that now signs the output, and the one the GitHub workflow expects (`.github/workflows/build-apk.yml`, "Verify signed APK").

If the new one **differs** from either, it prints a `WARNING`. It means the phone's installed IG Guard was signed by a different key. Android refuses to update an app signed by a different key, so the only way to install is to uninstall the old app first, which **deletes its data on that phone** (pairing, settings and consent receipt; the family would have to be set up again). Stop and use the keystore that signed the GitHub release. Continue only if you really mean to start over on a fresh phone or are happy to pair again.

If the input APK was unsigned, only the comparison with the workflow's pinned digest is possible.

## Install on the phone

- **With adb** (USB debugging on, phone connected): `adb install -r dist\ig-guard-with-model.apk`. The `-r` keeps the existing app's data when the certificate matches.
- **Without adb:** copy the one APK file to the phone (USB cable), open it from the Files app and allow installing from that source when asked.

Then delete the copy of the packaged APK from the phone's Downloads folder and from any other place you copied it. The installed app still holds the model inside itself.

**Space on the phone.** The APK is about 1.1 GB, and the first start copies the model out of the APK into the app's private storage, which is another 1.1 GB. Keep about **2.3 GB** free for the first start (and a bit more while Android installs the APK). If there is not enough room, the app says how much is needed and offers a Retry button; nothing partial is kept. After the copy the phone holds the model twice (the installed APK and the app's copy); that is a trade-off of this method.

**What happens on the child's phone.** When the phone is set up as a child phone (and each time the app starts while the model is missing), a progress card appears under Permissions and the copy runs in the background. The app checks the size and SHA-256 again. A wrong or cut-off file is rejected, deleted and reported; the mode stays off. Installing the model does not turn anything on: nothing is stored or analysed until a parent switches the mode on and the child agrees. The parent's phone is told the model is valid.

Removing the model needs the parent's password. After a removal the app does **not** reinstall the included copy on its own. The child's Permissions page shows **Install the model included in this app**, which asks for the parent's password. A parent's phone never installs the model.

## Checks and limits

- `python3.12 tests/check_package_with_model.py` tests the script without the real model. It also runs a real `zipalign` and `apksigner` with a throw-away key when the Android build-tools are present.
- Not tested with the real 1.1 GB model or on a physical phone: the packaging run itself, installing a 1.1 GB APK, the first-start copy out of the APK, and how long it takes. Try it first on a spare phone.
- The packaged APK is a separate, private build. Do not use it for the public release; the release workflow is unchanged and never contains the model.
