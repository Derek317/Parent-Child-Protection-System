# Android feasibility: documented runtime/artifact path, untested on device

As of 2026-11-17, official documentation provides a concrete d1 runtime/artifact path.
This experiment has **not built or run that path on Android**, measured phone latency,
or verified a Kotlin/JNI decision API. The CPU results use the original float32
safetensors snapshot, not a GGUF. No Android application or monitoring integration was added.

## Evidence and limits

| Question | Observed evidence | Limit |
| --- | --- | --- |
| Exact model runtime? | [Official d1-omni-600M page](https://docs.liquid.ai/lfm/models/d1-omni-600m.md) says llama.cpp **0.7.0 or later** and `/v1/systemone`; older versions can fail with `unsupported decision model type`. | This version minimum is a documentation claim; no runtime was built here. |
| Artifact? | [Official GGUF repository](https://huggingface.co/LiquidAI/d1-omni-600M-GGUF/tree/8eb2d2a1c5404bba8a34a88bd8e159c242197d51), pinned to `8eb2d2a1c5404bba8a34a88bd8e159c242197d51`. `d1-omni-600M-Q8_0.gguf`: **407,207,584 bytes**, SHA-256 `cd94463f4cac9c700ec6750df8df005bb6846de410e159d1f5e985e1171693d3`. | Metadata and README inspected; GGUF weights were not downloaded. The card names the base repository but does not tie it to the evaluated safetensors revision. Numerical parity is unverified. |
| Text-only artifact? | GGUF card's text example starts `llama-server` without a projector. Its separate Q8 multimodal projector is 262,791,232 bytes. | Text tests should omit projector/media. Artifact size is not peak RAM. There is no Q4 artifact in the captured repository listing. |
| Runtime source support? | Upstream llama.cpp commit `ebc9fb933e774bb4ac0ba497a67948e16b7e5656`: [`server-decision.cpp:172`](https://github.com/ggml-org/llama.cpp/blob/ebc9fb933e774bb4ac0ba497a67948e16b7e5656/tools/server/server-decision.cpp#L172) handles `COMMON_DECISION_TYPE_LFM2_D1_OMNI` and its mask marker; [`fill_task_d1omni:1328`](https://github.com/ggml-org/llama.cpp/blob/ebc9fb933e774bb4ac0ba497a67948e16b7e5656/tools/server/server-decision.cpp#L1328) builds the budgeted decision input. [Server tests:46](https://github.com/ggml-org/llama.cpp/blob/ebc9fb933e774bb4ac0ba497a67948e16b7e5656/tools/server/tests/unit/test_systemone.py#L46) assert zero-output-token typed responses. | Source evidence, not a test execution. Those tests parameterize `tinylaya` and `tinyopenjev`, not this d1 checkpoint; they establish neither d1 numerical parity nor Android support on hardware. |
| Android runtime? | [Liquid mobile guide](https://docs.liquid.ai/deployment/on-device/llama-cpp/mobile.md) and [pinned upstream Android build guide](https://github.com/ggml-org/llama.cpp/blob/ebc9fb933e774bb4ac0ba497a67948e16b7e5656/docs/android.md) describe NDK arm64 builds and native binaries. | The documented Kotlin `AiChat`/`InferenceEngine` examples generate tokens; they do not expose a verified System One decision binding. An isolated native server probe is the next measurable step. |
| ONNX/ExecuTorch? | [Official ONNX page](https://docs.liquid.ai/deployment/on-device/onnx.md) lists text/VL/MoE/audio families and export entrypoints. | No exact d1-omni-600M decision-head ONNX or ExecuTorch export path is established by the inspected sources. Generic LFM2 support is insufficient. |
| LEAP SDK? | [Official archived SDK page](https://docs.liquid.ai/deployment/on-device/sdk/overview.md) says it is deprecated/frozen and recommends llama.cpp. | Do not assume a frozen SDK supports this checkpoint or typed decisions. |

The GGUF repository LICENSE was fetched at its pinned revision and matches the original
LICENSE SHA-256 `9953509cbd8c1c6ce7b06ddf74fcc204a892692316201dd8f7400a84790d4120`.
LFM Open License v1.0 restrictions still apply to converted weights. All retrieved page
and source hashes are in [android-sources.json](evidence/android-sources.json); raw
source snapshots are external under `/workspace/private/d1-grooming/docs/`. Official web
docs are mutable, so their retrieval hashes identify what was inspected. Repository files
use immutable commit URLs. No demo media URLs were fetched.

## Reproducible next device experiment (not executed here)

Use a separate arm64 Android test device, adb, an Android NDK, CMake and a C++ build toolchain.
Keep every artifact under `/workspace/private/d1-android/` and `/data/local/tmp/d1-experiment/`.
The following builds an isolated command-line test server, without touching IG Guard.
Review the pinned upstream source before executing the build. The current release API
did not supply a verified Android binary asset, so this recipe uses source compilation.

```bash
export D1_DIR=/workspace/private/d1-android
export D1_NDK=/absolute/path/to/android-ndk
mkdir -p "$D1_DIR"
git clone https://github.com/ggml-org/llama.cpp "$D1_DIR/llama.cpp"
git -C "$D1_DIR/llama.cpp" checkout --detach ebc9fb933e774bb4ac0ba497a67948e16b7e5656
cmake -S "$D1_DIR/llama.cpp" -B "$D1_DIR/build" \
  -DCMAKE_TOOLCHAIN_FILE="$D1_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
  -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_SERVER=ON \
  -DLLAMA_BUILD_TESTS=OFF
cmake --build "$D1_DIR/build" --config Release -j2
cmake --install "$D1_DIR/build" --prefix "$D1_DIR/install" --config Release
curl --fail --location \
  https://huggingface.co/LiquidAI/d1-omni-600M-GGUF/resolve/8eb2d2a1c5404bba8a34a88bd8e159c242197d51/d1-omni-600M-Q8_0.gguf \
  --output "$D1_DIR/d1-omni-600M-Q8_0.gguf"
python - <<'PY'
import hashlib, os
from pathlib import Path
p = Path(os.environ['D1_DIR']) / 'd1-omni-600M-Q8_0.gguf'
with p.open('rb') as f:
    digest = hashlib.file_digest(f, 'sha256').hexdigest()
assert digest == 'cd94463f4cac9c700ec6750df8df005bb6846de410e159d1f5e985e1171693d3'
PY
```

Select one test device with `ANDROID_SERIAL` if several are attached. Record its model,
SoC, OS, memory, thermal/battery conditions and the built server/library hashes. Push and
run in a dedicated terminal, with a loopback-only listener:

```bash
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.soc.model
adb shell getprop ro.build.version.release
adb shell cat /proc/meminfo
adb shell mkdir -p /data/local/tmp/d1-experiment
adb push "$D1_DIR/install/bin" /data/local/tmp/d1-experiment/
adb push "$D1_DIR/install/lib" /data/local/tmp/d1-experiment/
adb push "$D1_DIR/d1-omni-600M-Q8_0.gguf" /data/local/tmp/d1-experiment/
adb shell sha256sum /data/local/tmp/d1-experiment/d1-omni-600M-Q8_0.gguf
adb shell 'LD_LIBRARY_PATH=/data/local/tmp/d1-experiment/lib /data/local/tmp/d1-experiment/bin/llama-server -m /data/local/tmp/d1-experiment/d1-omni-600M-Q8_0.gguf --host 127.0.0.1 --port 18080 -c 2048 -b 2048 -ub 2048 --threads 4 --parallel 1'
```

After the server reports ready, use another terminal in this experiment directory:

```bash
adb forward tcp:18080 tcp:18080
python android_probe.py --port 18080 --output /tmp/d1-android-probe
adb shell 'pidof llama-server'
# Use the reported PID to capture /proc/PID/status, /proc/PID/stat and thermal/battery
# observations before and after the probe. Save server logs and artifact build hashes.
adb forward --remove tcp:18080
```

`android_probe.py` performs a warmup and replays only the frozen synthetic fixture to the
local endpoint, saving raw responses and the same per-language metrics. It rejects
nonzero generated-token counts and routes response/errors to review. It does not launch
a device process, install an APK or collect chats. HTTP wall times include adb overhead;
they are not device CPU time or standalone app latency. The probe needs a real device and
server and was not run here; only its transport/schema behavior was tested with a local
stub. Pin and compare GGUF scores against the F32 predictions before discussing quality
parity. Measure cold load, peak RSS, CPU time, sustained thermal behavior and energy on
hardware before making any phone speed or integration claim. A production in-process
decision binding remains separate work.
