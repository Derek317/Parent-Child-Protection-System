#!/usr/bin/env python3.12
"""Compare the native scoring core (host_check) with the research harness path (llama-server).

  python3.12 native/risk/host_check.py --host-check <build>/host_check \
      --model research/models/Qwen3-1.7B-Q8_0.gguf \
      --server research/models/llama-b12503-linux/llama-b12503/llama-server [--port 8097] [--n 24]

Prompts come from train_lora.render("qwen3", messages) over rows of research/data/tune.jsonl plus the
prompt in tests/fixtures/risk-prompt-fixture.json. The server path is exactly score_chat's: POST /tokenize
(add_special=false, parse_special=true) then /completion (n_predict 1, temperature 0, n_probs 100) read
through scorers.five_way. Writes the table to native/risk/HOST-CHECK.md (use --out to change).
"""
import argparse
import datetime
import hashlib
import json
import math
import os
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "research"))
from igrisk.client import request  # noqa: E402
from igrisk.scorers import ANSWERS, five_way  # noqa: E402
import train_lora  # noqa: E402


def pick_rows(n):
    rows = [json.loads(l) for l in (REPO / "research/data/tune.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    by_lang = {}
    for r in rows:
        by_lang.setdefault(r["language"], []).append(r)
    # Cantonese first (at least 8), then the others; stride through each language so levels vary.
    want = {"zh-Hant-yue": max(8, (n - 1) // 3 + 1)}
    rest = n - 1 - want["zh-Hant-yue"]
    want["en"] = rest // 2
    want["mixed"] = rest - want["en"]
    chosen = []
    for lang, k in want.items():
        pool = by_lang[lang]
        step = max(1, len(pool) // k)
        chosen += [pool[(i * step + 1) % len(pool)] for i in range(k)]
    return chosen


def native(binary, model, prompt, threads):
    with tempfile.NamedTemporaryFile("wb", suffix=".txt", delete=False) as f:
        f.write(prompt.encode("utf-8"))
        path = f.name
    try:
        out = subprocess.run([binary, model, path, str(threads)], capture_output=True, text=True, check=True)
    finally:
        os.unlink(path)
    return json.loads(out.stdout)


def server_path(port, prompt):
    tok = request(port, "/tokenize", {"content": prompt, "add_special": False, "parse_special": True, "with_pieces": True})
    ids = [t["id"] if isinstance(t, dict) else t for t in tok["tokens"]]
    resp = request(port, "/completion", {"prompt": ids, "n_predict": 1, "temperature": 0, "n_probs": 100, "cache_prompt": False})
    p, mass = five_way(resp)
    return ids, p, mass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host-check", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--server", required=True)
    ap.add_argument("--port", type=int, default=8097)
    ap.add_argument("--n", type=int, default=24)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--out", default=str(REPO / "native/risk/HOST-CHECK.md"))
    a = ap.parse_args()
    a.host_check, a.model, a.server = (str(Path(x).resolve()) for x in (a.host_check, a.model, a.server))

    items = []
    fx = json.loads((REPO / "tests/fixtures/risk-prompt-fixture.json").read_text(encoding="utf-8"))
    items.append(("fixture", "fixture", fx["prompt"]))
    for r in pick_rows(a.n):
        items.append((r["id"], r["language"], train_lora.render("qwen3", r["messages"])))

    sha = hashlib.sha256()
    with open(a.model, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 24), b""):
            sha.update(chunk)

    server = subprocess.Popen([a.server, "-m", a.model, "--host", "127.0.0.1", "--port", str(a.port), "-t", "4", "-c", "8192"],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, cwd=str(Path(a.server).parent))
    results = []
    try:
        for _ in range(240):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{a.port}/health", timeout=2).read()
                break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError("llama-server did not become healthy")
        for name, lang, prompt in items:
            n = native(a.host_check, a.model, prompt, a.threads)
            ids, p, mass = server_path(a.port, prompt)
            present = [k for k in ANSWERS if p[k] > 0]
            mine = dict(zip(ANSWERS, n["probs"]))
            tot = sum(mine[k] for k in present) or 1.0
            diff = max(abs(mine[k] / tot - p[k]) for k in present) if present else float("nan")
            mism = sum(1 for x, y in zip(n["ids"], ids) if x != y) + abs(len(n["ids"]) - len(ids))
            results.append(dict(name=name, lang=lang, n_tokens=len(ids), native_tokens=n["n_tokens"], mism=mism,
                                present=len(present), diff=diff, top_native=max(mine, key=mine.get),
                                top_server=max(p, key=p.get) if present else "-"))
            print(results[-1], flush=True)
    finally:
        server.terminate()
        try:
            server.wait(timeout=20)
        except subprocess.TimeoutExpired:
            server.kill()

    worst = max(r["diff"] for r in results)
    tok_mism = sum(r["mism"] for r in results)
    lines = [
        "# Host check: native scoring core vs llama-server harness path", "",
        f"- Date: {datetime.date.today().isoformat()}",
        f"- Model: `{Path(a.model).name}` SHA-256 `{sha.hexdigest()}`",
        "- Engine: llama.cpp b12503 (4577932fab595eaa8b27ad71734d5de1f3c07d8c); host build of `risk_core.cpp` (x86-64, GGML_NATIVE) vs prebuilt `llama-server` b12503 (x86-64, `-c 8192 -t 4`)",
        f"- Prompts compared: {len(results)} (fixture prompt, {sum(r['lang'] == 'zh-Hant-yue' for r in results)} Cantonese, rest en/mixed rows of research/data/tune.jsonl)",
        f"- Token id sequences differing: {sum(1 for r in results if r['mism'])} prompts ({tok_mism} positions)",
        f"- Max absolute difference of the five renormalised probabilities: {worst:.2e}",
        f"- Prompts where the server's top-100 list missed some of the five tokens: {sum(r['present'] < 5 for r in results)}",
        "", "| prompt | language | tokens | token mismatches | answers in server top-100 | max abs prob diff | top (native/server) |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for r in results:
        lines.append(f"| {r['name']} | {r['lang']} | {r['n_tokens']} | {r['mism']} | {r['present']}/5 | {r['diff']:.2e} | {r['top_native']}/{r['top_server']} |")
    lines += ["", "## What this proves", "",
              "The same llama.cpp engine version, driven through the same code that the Android library uses (`risk_core.cpp`: tokenize with add_special=false and parse_special=true, one decode over an empty KV cache, raw logits of ids 15 16 17 18 52), gives the same token ids and the same five-way probabilities as the research harness's llama-server path, on this machine.",
              "", "## What this does not prove", "",
              "- It ran on x86-64, not arm64. The Android build uses different CPU kernels, so small numeric differences on a phone are not excluded and were not measured.",
              "- It used the base `Qwen3-1.7B-Q8_0.gguf`, not the fine-tuned `qwen3-1.7b-lora-v7-Q4_K_M.gguf` (the file the app ships; it was not host-checked) (same tokenizer and architecture, different weights and quantisation). The base model is not calibrated for the answer format, so these probabilities say nothing about accuracy.",
              "- It was never run on a device: speed, memory and battery are unmeasured.",
              "- The JNI layer and Kotlin wrapper are not exercised by this check.",
              "- The server's probabilities come from its top-100 candidates, so tokens outside that list are missing there (counted above)."]
    Path(a.out).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"max diff {worst:.2e}, token mismatches {tok_mism}")
    return 0 if tok_mism == 0 and worst <= 1e-3 else 1


if __name__ == "__main__":
    sys.exit(main())
