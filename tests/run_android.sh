#!/usr/bin/env bash
# Generate the repository's CI project outside the checkout, using default debug signing and an unsigned release locally.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
build_dir="${IG_GUARD_BUILD_DIR:-/workspace/.android-dev/project}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/.android-dev/sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.android-dev/gradle-home}"
export IG_GUARD_SOURCE="$repo_dir"
# Unit tests never load the native library: leave the NDK/CMake/llama.cpp wiring out unless asked
# (IG_GUARD_SKIP_NATIVE=0, e.g. for assembleDebug; set IG_GUARD_LLAMA_DIR to reuse a llama.cpp checkout).
export IG_GUARD_SKIP_NATIVE="${IG_GUARD_SKIP_NATIVE:-1}"
export IG_GUARD_BUILD="$build_dir"
export IG_GUARD_TEST_HOME="${IG_GUARD_TEST_HOME:-/workspace/.android-dev/test-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$IG_GUARD_TEST_HOME/.android}"
mkdir -p "$build_dir" "$IG_GUARD_TEST_HOME" "$ANDROID_USER_HOME"
python3 - <<'PY'
from pathlib import Path
import os,re,shutil
repo=Path(os.environ['IG_GUARD_SOURCE']); out=Path(os.environ['IG_GUARD_BUILD'])
s=(repo/'.github/workflows/build-apk.yml').read_text().split('      - name: Assemble Android project structure\n',1)[1].split('        run: |\n',1)[1].split('\n      - name:',1)[0]
s='\n'.join(x[10:] if x.startswith('          ') else x for x in s.splitlines())+'\n'
s=s.replace('${{ github.run_number }}','1')
(out/'generate.sh').write_text(s)
for source in repo.glob('*.kt'): shutil.copyfile(source,out/source.name)
(out/'docs').mkdir(exist_ok=True)
shutil.copyfile(repo/'docs/ig-guard-terms-of-service.md',out/'docs/ig-guard-terms-of-service.md')
(out/'scripts').mkdir(exist_ok=True)
shutil.copyfile(repo/'scripts/relay-config.py',out/'scripts/relay-config.py')
(out/'relay').mkdir(exist_ok=True)
shutil.copyfile(repo/'relay/endpoint.json',out/'relay/endpoint.json')
shutil.copytree(repo/'ui-res',out/'ui-res',dirs_exist_ok=True)
shutil.copytree(repo/'native/risk',out/'native/risk',dirs_exist_ok=True)
PY
cd "$build_dir"
bash generate.sh
mkdir -p app/src/test/java/app/family/igguard
cp "$repo_dir"/tests/*.kt app/src/test/java/app/family/igguard/
mkdir -p app/src/test/resources
printf 'application=app.family.igguard.VaultTestApplication\n' > app/src/test/resources/robolectric.properties
cat >> app/build.gradle <<'GRADLE'
android { testOptions {
    unitTests.includeAndroidResources = true
    unitTests.all {
        maxHeapSize = '1g'
        ['https.proxyHost','https.proxyPort','http.proxyHost','http.proxyPort'].each { key ->
            if (System.getProperty(key)) systemProperty key, System.getProperty(key)
        }
        systemProperty 'user.home', System.getenv('IG_GUARD_TEST_HOME')
        systemProperty 'robolectric.dependency.repo.url', 'https://repo.maven.apache.org/maven2'
        systemProperty 'igguard.protocol.vector', System.getenv('IG_GUARD_SOURCE') + '/relay/test/protocol-vector.json'
    }
} }
android { buildTypes { debug { buildConfigField 'String', 'RELAY_BASE_URL', '""' } } }
dependencies {
    testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'
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
exec "${GRADLE_BIN:-/workspace/.android-dev/gradle-8.7/bin/gradle}" --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process -Pandroid.useAndroidX=true "${proxy_args[@]}" "${@:-testDebugUnitTest}"
