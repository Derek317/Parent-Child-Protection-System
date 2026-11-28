#!/usr/bin/env bash
# Generate the repository's CI project outside the checkout, using default debug signing and an unsigned release locally.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
build_dir="${IG_GUARD_BUILD_DIR:-/workspace/.android-dev/project}"
build_root="$(mkdir -p -- "$build_dir" && cd -- "$build_dir" && pwd -P)"
repo_root="$(cd -- "$repo_dir" && pwd -P)"
if [[ "$repo_root" == "${build_root%/}" || "$repo_root" == "${build_root%/}"/* ]]; then
    echo "refusing to run: IG_GUARD_BUILD_DIR ($build_dir) is or contains the checkout ($repo_dir)" >&2
    exit 1
fi
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
rm -rf -- "${build_dir:?}"/app/src "${build_dir:?}"/*.kt "${build_dir:?}"/ui-res "${build_dir:?}"/native
python3 - <<'PY'
from pathlib import Path
import os,re,shutil
repo=Path(os.environ['IG_GUARD_SOURCE']); out=Path(os.environ['IG_GUARD_BUILD'])
s=(repo/'.github/workflows/build-apk.yml').read_text().split('      - name: Assemble Android project structure\n',1)[1].split('        run: &assemble_android |\n',1)[1].split('\n      - name:',1)[0]
s='\n'.join(x[10:] if x.startswith('          ') else x for x in s.splitlines())+'\n'
s=s.replace('${{ github.run_number }}','1')
(out/'generate.sh').write_text(s)
for source in repo.glob('*.kt'): shutil.copyfile(source,out/source.name)
(out/'docs').mkdir(exist_ok=True)
shutil.copyfile(repo/'docs/ig-guard-terms-of-service.md',out/'docs/ig-guard-terms-of-service.md')
(out/'scripts').mkdir(exist_ok=True)
shutil.copyfile(repo/'scripts/relay-config.py',out/'scripts/relay-config.py')
(out/'third_party').mkdir(exist_ok=True)
for name in ('zxing-core.sha256','zxing-core-3.5.3.jar'): shutil.copyfile(repo/'third_party'/name,out/'third_party'/name)
(out/'relay').mkdir(exist_ok=True)
shutil.copyfile(repo/'relay/endpoint.json',out/'relay/endpoint.json')
(out/'gradle').mkdir(exist_ok=True)
shutil.copyfile(repo/'gradle/verification-metadata.xml',out/'gradle/verification-metadata.xml')
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
// Robolectric's Android jars (API 26, 28 and 34, which the tests ask for, and 35, which Robolectric loads for the app's
// resources) are resolved by Gradle and Robolectric runs offline. Its own downloader fetched them from Maven Central
// during the tests, which answered GitHub's runners with 429 Too Many Requests; settings.gradle takes this one module
// from Google's Maven Central mirror instead. Each jar must match the SHA-512 pinned here (the values Maven Central publishes).
def robolectricSha512 = [
    '8.0.0_r4-robolectric-r1-i7': 'c55e2053f3ae35d617ce02b0ecd311c1f72fcd671eaff873fb203e106b2dc444b870677b71be546a062045fc0a473926bb1668bd43f9051176826aab3f732aab',
    '9-robolectric-4913185-2-i7': '24687b9ea919eb20f5e27e47274f2aa711e98eb332d4769d34044a3b4952a71393831d0a3f61ba726dc8fe220673a76138c11a0716f304999e5b029146fe88eb',
    '14-robolectric-10818077-i7': '7cdc0b1306e7865f1acd25a363073149654caee5b8e278c5ae37ff4724185724eff39f0b0111800b521e640bb28698d9d2e655134a801569a0f07470e7b5f455',
    '15-robolectric-12650502-i7': '87bdd0341838f5f630e1ff412ab29cb2f06e0f8cb7751f14679d11b4ee6084873001f8f6a8581ae6e139fa7c9a654b632412827430f984ebecc3c2588674f7e7',
]
def robolectricJars = robolectricSha512.keySet().collect { v ->
    configurations.detachedConfiguration(dependencies.create("org.robolectric:android-all-instrumented:$v")).tap { transitive = false }
}
tasks.register('copyRobolectricJars', Copy) {
    from robolectricJars; into layout.buildDirectory.dir('robolectric-jars')
    doLast {
        robolectricSha512.each { v, want ->
            def jar = new File(destinationDir, "android-all-instrumented-${v}.jar")
            def digest = java.security.MessageDigest.getInstance('SHA-512')
            jar.eachByte(1 << 20) { buffer, read -> digest.update(buffer, 0, read) }
            if (digest.digest().encodeHex().toString() != want) throw new GradleException("SHA-512 mismatch for ${jar.name}")
        }
    }
}
android { testOptions {
    unitTests.includeAndroidResources = true
    unitTests.all {
        maxHeapSize = '1g'
        // A failed test prints its message, and every test prints when it starts, so a hang shows which test it is in.
        testLogging { events 'started', 'failed'; exceptionFormat 'full' }
        // The names of the failed tests once more as the last lines of the test output, where a pasted log tail shows them.
        def failedTests = []
        afterTest { d, r -> if (r.resultType == TestResult.ResultType.FAILURE) failedTests << "${d.className} > ${d.name}" }
        afterSuite { d, r -> if (!d.parent && failedTests) println "FAILED TESTS:\n" + failedTests.join('\n') }
        dependsOn 'copyRobolectricJars'
        systemProperty 'robolectric.offline', 'true'
        systemProperty 'robolectric.dependency.dir', layout.buildDirectory.dir('robolectric-jars').get().asFile.absolutePath
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
