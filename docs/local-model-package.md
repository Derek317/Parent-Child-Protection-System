# Build one APK with the model inside

**Read this first.** On 20 November 2026 the owner decided that the released app carries the analysis model: the APK on this repository's GitHub Releases page contains Beacon, and the model file is also published on GitHub Releases as an asset of a separate release (`model-beacon`). The release workflow fetches that asset, checks its size and SHA-256 against `RiskModel.kt`, adds it with `tools/package_with_model.py` and signs with the release key held in the repository's GitHub secrets. The steps below build the same package on your own PC; signed with a different key, it cannot update an install signed with the release key.

Publishing makes the model downloadable by anyone, and a downloaded copy cannot be recalled. The model was trained partly on real conversations (including other people's messages) and on PAN-12; a trained model can sometimes reproduce fragments of its training text; a limited test by the developer found no reproduction of the real training conversations (one setting, one prompt length, PAN-12 not included), which cannot prove the model never does. The training data itself stays private: **never add the real chats, the PAN-12 data or any training file to the repository, a release, an issue or a chat.** The model file is never committed either (it is git-ignored and far over GitHub's file limit). Ordinary builds of `main` contain no model, and nothing in the app downloads it.

**Keep everything out of cloud-synced folders.** The repository clone, the model, the keystore and the output must not be inside a OneDrive, Dropbox, Google Drive or iCloud folder, because the sync program would upload the keystore along with everything else. On Windows, **Documents**, **Desktop** and **Pictures** are often synced by OneDrive without you noticing. Use a plain local folder such as `C:\work\ig-guard` and pass `--out` a path there. The script refuses when the model or the output is under a folder named by `%OneDrive%`, `%OneDriveConsumer%` or `%OneDriveCommercial%`, or whose path contains `OneDrive`, `Dropbox`, `Google Drive` or `iCloudDrive`; `--allow-synced-folder` overrides that, and you should not need it.

The result is one file you install like any app. On the child's phone the app copies the model out of itself on first start (about 1.1 GB free is needed for the copy, on top of the installed app), so nothing has to be copied or picked by hand. Importing the model file with the file picker (parent's password needed) remains only as a recovery path, for example on a build without the model.

**Google Play.** The APK with the model is over 1 GB. It can be installed directly, but it cannot be uploaded to Google Play as a plain APK: Play limits apps published as APKs to 100 MB, and a model this size would need an Android App Bundle with an asset pack (Play allows up to 1.5 GB per asset pack), which is not built. Limits as stated in Google's Play Console Help, "Optimize your app's size and stay within Google Play app size limits" (support.google.com/googleplay/android-developer/answer/9859372), checked 20 November 2026.

## What you need (once)

1. **The model file** `qwen3-1.7b-beacon-Q4_K_M.gguf` (1,107,408,736 bytes). The script checks its size and SHA-256 against the values written in `RiskModel.kt`; any other file is refused and nothing is written.
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

This APK has no model in it. The output of the script is the same kind of file as the release APK.

## Run the script

Open PowerShell in the repository folder and run (change the paths):

```
py -3.12 tools\package_with_model.py `
  --apk C:\Users\you\Downloads\ig-guard-signed-apk\app-release.apk `
  --model D:\models\qwen3-1.7b-beacon-Q4_K_M.gguf `
  --out dist\ig-guard-with-model.apk `
  --keystore D:\keys\ig-guard-release.jks `
  --ks-alias igguard `
  --build-tools C:\Android\sdk\build-tools\34.0.0
```

(On Linux or macOS use `python3.12`, `/` in paths and `\` instead of the backtick.)

- It asks for the keystore password with a hidden prompt, and then for the key password (press Enter if you used one password for both, which `signing.md` supports). **Prefer the hidden prompt.** Setting `$env:IG_GUARD_KEYSTORE_PASSWORD = '...'` in PowerShell leaves the password in your session and may leave it in the command history; the variables `IG_GUARD_KEYSTORE_PASSWORD` and `IG_GUARD_KEY_PASSWORD` exist for automation only. Passwords are never taken from the command line, never printed, and not handed to any program except the signing tool.
- Keep every path free of the characters `&`, `^` and `%` (and avoid spaces if you can); Windows command handling of `apksigner.bat` can misread them. Put the model, keystore and output in simple folders such as `C:\work\ig-guard`.
- `--build-tools` can be left out if `ANDROID_HOME` or `ANDROID_SDK_ROOT` points at your SDK; the script then uses the newest build-tools folder.
- `--out` must end in `.apk`. The script refuses to write inside the git work tree unless the path is ignored by git. `dist\` and `*.apk` are ignored, so `dist\ig-guard-with-model.apk` is fine. Add `--force` to overwrite an earlier output.

The APK plus the model must stay under about 2 GiB (the current build is roughly 1.1 GB plus the APK). Above that the zip format needs zip64 records, which Android may reject, so the script refuses.

It takes a few minutes. It checks the model, copies the APK entry by entry (keeping each entry's compression, dropping the old signature files), adds the model as `assets/risk/qwen3-1.7b-beacon-Q4_K_M.gguf` **uncompressed**, runs `zipalign -p -f 4`, signs with `apksigner` (v2 and v3), and verifies the result. At the end it prints the output path, its size and SHA-256, the signing certificate's SHA-256, and writes `dist\ig-guard-with-model.apk.sha256` next to it.

Why those `zipalign` flags: the GitHub build is made with Android Gradle plugin 8.3.2 for Android 8.0 and newer (`minSdk 26`), and the workflow does not change how native libraries are packed. So `libigguard_risk.so` is stored uncompressed and is not extracted at install time. `-p` keeps it page-aligned so it can be loaded straight from the APK, and `4` aligns everything else. Because the minimum Android version is 8.0, the old v1 signature is not needed and the script does not add one.

## The certificate warning

The script prints three certificate digests when it can: the one that signed the APK you gave it (if it was signed), the one that now signs the output, and the one the GitHub workflow expects (`.github/workflows/build-apk.yml`, "Verify signed APK").

If the new one **differs** from either, it prints a `WARNING`. It means the phone's installed IG Guard was signed by a different key. Android refuses to update an app signed by a different key, so the only way to install is to uninstall the old app first, which **deletes its data on that phone** (pairing, settings and consent receipt; the family would have to be set up again). Stop and use the keystore that signed the GitHub release. Continue only if you really mean to start over on a fresh phone or are happy to pair again.

If the input APK was unsigned, only the comparison with the workflow's pinned digest is possible.

## Install on the phone

- **With adb** (USB debugging on, phone connected): `adb install -r dist\ig-guard-with-model.apk`. The `-r` keeps the existing app's data when the certificate matches.
- **Without adb:** copy the one APK file to the phone (USB cable), open it from the Files app and allow installing from that source when asked. This is blocked, with no "install anyway" option, on phones where Google Play Protect's enhanced fraud protection is active, because the app declares an accessibility service and a notification listener. Use adb there.

Then delete the copy of the packaged APK from the phone's Downloads folder and from any other place you copied it. The installed app still holds the model inside itself.

**Space on the phone.** Counting the copy of the APK you put on the phone (for example in Downloads) and installing it from the Files app, the phone needs about **3.5 GB** free in total: about 1.1 GB for the APK copy, 1.1 GB for the installed app, and 1.1 GB for the model that the first start copies out of the app. **Delete the copied APK from Downloads right after installing** to get 1.1 GB back (with `adb install` there is no extra copy). If there is not enough room, the app says how much it needs (about 1.1 GB) and offers a Retry button; nothing partial is kept. After the copy the phone holds the model twice (inside the installed app and as the app's own copy); that is a trade-off of this method.

The copy takes several minutes. If Android stops the app while it runs (the screen is turned off and the app is closed, or memory is short), the partial copy is thrown away and the copy starts again from the beginning the next time the app is opened. Keep the app open and the phone awake the first time.

**What happens on the child's phone.** When the phone is set up as a child phone (and each time the app starts while the model is missing), a progress card appears under Permissions and the copy runs in the background. The app checks the size and SHA-256 again. A wrong or cut-off file is rejected, deleted and reported; the mode stays off. Installing the model does not turn anything on: nothing is stored or analysed until a parent switches the mode on and the child agrees. The parent's phone is told the model is valid.

Removing the model needs the parent's password. After a removal the app does **not** reinstall the included copy on its own. The child's Permissions page shows **Install the model included in this app**, which asks for the parent's password. A parent's phone never installs the model.

## Checks and limits

- `python3.12 tests/check_package_with_model.py` tests the script without the real model. It also runs a real `zipalign` and `apksigner` with a throw-away key when the Android build-tools are present.
- Phone test, 20 November 2026, on two real phones with a package built from the real model: the APK installed, the first-start copy out of the APK worked, and both collection methods (automatic capture and manual upload) worked end to end. On a 2018 phone reporting 5.8 GB of memory, scoring completed with about 1.7 GB peak app memory (one phone, a few short chats; not a guarantee for other phones or longer chats).
- Not tested: the natural schedules (the 15-minute check-in and the charging-and-idle scoring job were started by hand, not observed running on their own), importing the owner's real Instagram export, more than two devices, how long the copy takes, battery and heat, importing the 1.1 GB file through the file picker, and screen readers on real phones.
- The release workflow change that fetches the model asset and packages it is planned; this document does not show that it has run.
