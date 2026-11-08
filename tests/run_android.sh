#!/usr/bin/env bash
# Generate the repository's CI project outside the checkout, using default debug signing and an unsigned release locally.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
build_dir="${IG_GUARD_BUILD_DIR:-/workspace/.android-dev/project}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/.android-dev/sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.android-dev/gradle-home}"
export IG_GUARD_SOURCE="$repo_dir"
export IG_GUARD_BUILD="$build_dir"
mkdir -p "$build_dir"
python3 - <<'PY'
from pathlib import Path
import os,re,shutil
repo=Path(os.environ['IG_GUARD_SOURCE']); out=Path(os.environ['IG_GUARD_BUILD'])
s=(repo/'.github/workflows/build-apk.yml').read_text().split('      - name: Assemble Android project structure\n',1)[1].split('        run: |\n',1)[1].split('\n      - name:',1)[0]
s='\n'.join(x[10:] if x.startswith('          ') else x for x in s.splitlines())+'\n'
s=s.replace('${{ github.run_number }}','1')
(out/'generate.sh').write_text(s)
shutil.copyfile(repo/'MainActivity.kt',out/'MainActivity.kt')
shutil.copyfile(repo/'FamilySecurity.kt',out/'FamilySecurity.kt')
shutil.copyfile(repo/'FamilyEvidence.kt',out/'FamilyEvidence.kt')
shutil.copyfile(repo/'ConversationCapture.kt',out/'ConversationCapture.kt')
shutil.copytree(repo/'ui-res',out/'ui-res',dirs_exist_ok=True)
PY
cd "$build_dir"
bash generate.sh
mkdir -p app/src/test/java/app/family/igguard
cp "$repo_dir"/tests/*Test.kt app/src/test/java/app/family/igguard/
cat >> app/build.gradle <<'GRADLE'
android { testOptions {
    unitTests.includeAndroidResources = true
    unitTests.all {
        maxHeapSize = '1g'
        ['https.proxyHost','https.proxyPort','http.proxyHost','http.proxyPort'].each { key ->
            if (System.getProperty(key)) systemProperty key, System.getProperty(key)
        }
        systemProperty 'robolectric.dependency.repo.url', 'https://repo.maven.apache.org/maven2'
    }
} }
dependencies {
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.json:json:20240303'
    testImplementation 'org.robolectric:robolectric:4.14.1'
}
GRADLE
# The cloud runtime uses an HTTP proxy for Java as well as shell downloads.
proxy_args=()
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    read -r proxy_host proxy_port < <(python3 -c 'import os,urllib.parse; p=urllib.parse.urlparse(os.environ["HTTPS_PROXY"]); print(p.hostname,p.port or 80)')
    proxy_args=("-Dhttps.proxyHost=$proxy_host" "-Dhttps.proxyPort=$proxy_port" "-Dhttp.proxyHost=$proxy_host" "-Dhttp.proxyPort=$proxy_port")
fi
exec "${GRADLE_BIN:-/workspace/.android-dev/gradle-8.7/bin/gradle}" --no-daemon --max-workers=2 -Pandroid.useAndroidX=true "${proxy_args[@]}" "${@:-testDebugUnitTest}"
