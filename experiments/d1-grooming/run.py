"""Offline, synthetic-only System One feasibility evaluation (no app integration)."""
import argparse
from collections import Counter
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import resource
import statistics
import subprocess
import sys
import time
import traceback

from fetch_model import HERE, sha256, verify


def read_json(name):
    return json.loads((HERE / name).read_text())


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n")


def dataset():
    rows = [json.loads(line) for line in (HERE / "synthetic.jsonl").read_text().splitlines()]
    ids, texts, families = set(), set(), {}
    for row in rows:
        assert row["synthetic"] is True
        assert row["id"] not in ids and row["messages"]
        assert row["language"] in {"en", "zh-Hant-yue", "mixed"}
        assert row["label"] in {"benign", "risk", "review"}
        assert row["split"] in {"development", "heldout_style"}
        assert row["family"] not in families or families[row["family"]] == row["split"]
        families[row["family"]] = row["split"]
        ids.add(row["id"])
        text = json.dumps(row["messages"], ensure_ascii=False)
        assert text not in texts
        texts.add(text)
    return rows


def baseline(row, pattern):
    return any(pattern.search(m["speaker"]) or pattern.search(m["text"]) for m in row["messages"])


def decision(score, protocol):
    if score is None:
        return "review"
    if not math.isfinite(score) or not 0 <= score <= 1:
        raise ValueError("invalid model score")
    if protocol["review_low"] <= score <= protocol["review_high"]:
        return "review"
    return "risk" if score >= protocol["binary_threshold"] else "benign"


def confusion(pairs):
    c = Counter()
    for label, predicted in pairs:
        c[(label, bool(predicted))] += 1
    tp, fn = c["risk", True], c["risk", False]
    fp, tn = c["benign", True], c["benign", False]
    div = lambda a, b: a / b if b else None
    return {"n": tp + fn + fp + tn, "tp": tp, "fp": fp, "tn": tn, "fn": fn,
            "precision": div(tp, tp + fp), "recall": div(tp, tp + fn),
            "false_positive_rate": div(fp, fp + tn), "false_negative_rate": div(fn, fn + tp)}


def summarize(rows, backend):
    result = {}
    for split in sorted({r["split"] for r in rows}):
        for lang in ["all", "en", "zh-Hant-yue", "mixed"]:
            group = [r for r in rows if r["split"] == split and (lang == "all" or r["language"] == lang)]
            binary = [r for r in group if r["label"] != "review"]
            scored = [r for r in binary if r.get("score") is not None]
            decided = [r for r in scored if r["decision"] != "review"]
            gold_review = [r for r in group if r["label"] == "review"]
            valid = [r for r in group if r.get("score") is not None]
            latency = sorted(r["wall_seconds"] for r in valid)
            result[f"{split}/{lang}"] = {
                "total": len(group), "gold_counts": dict(Counter(r["label"] for r in group)),
                "forced_binary": confusion([(r["label"], r["score"] >= 0.5) for r in scored]),
                "selective_binary": confusion([(r["label"], r["decision"] == "risk") for r in decided]),
                "binary_coverage": len(decided) / len(binary) if binary else None,
                "abstentions": sum(r["decision"] == "review" for r in group),
                "abstentions_by_gold": dict(Counter(r["label"] for r in group if r["decision"] == "review")),
                "errors": sum(r.get("error") is not None for r in group),
                "gold_review_total": len(gold_review),
                "gold_review_abstained": sum(r["decision"] == "review" for r in gold_review),
                "risk_not_cleared": sum(r["decision"] != "benign" for r in binary if r["label"] == "risk"),
                "wall_seconds_p50": statistics.median(latency) if latency else None,
                "wall_seconds_p95": latency[math.ceil(0.95 * len(latency)) - 1] if latency else None,
                "false_positive_ids": [r["id"] for r in scored if r["label"] == "benign" and r["score"] >= 0.5],
                "false_negative_ids": [r["id"] for r in scored if r["label"] == "risk" and r["score"] < 0.5],
            }
    return {"backend": backend, "groups": result}


def machine():
    info = {"platform": platform.platform(), "python": sys.version, "logical_cpus": os.cpu_count()}
    for name, path in {"cpuinfo": "/proc/cpuinfo", "meminfo": "/proc/meminfo",
                       "cgroup_memory_max": "/sys/fs/cgroup/memory.max",
                       "cgroup_cpu_max": "/sys/fs/cgroup/cpu.max"}.items():
        info[name] = Path(path).read_text() if Path(path).exists() else None
    return info


def resume_predictions(source, rows, backend, hashes):
    prior = json.loads((source / "run.json").read_text())
    if prior["backend"] != backend or prior["protocol"] != read_json("protocol.json"):
        raise ValueError("Resume backend/protocol differs")
    for name in ["synthetic.jsonl", "questions.json", "protocol.json", "baseline.json"]:
        if prior["inputs_sha256"][name] != hashes[name]:
            raise ValueError(f"Resume input differs: {name}")
    saved = [json.loads(s) for s in (source / "predictions.jsonl").read_text().splitlines()]
    if len(saved) > len(rows):
        raise ValueError("Resume has too many rows")
    for item, row in zip(saved, rows):
        if any(item[k] != row[k] for k in ["id", "family", "split", "language", "stage", "label"]):
            raise ValueError("Resume rows are not an exact fixture prefix")
        if item.get("error") or item["decision"] != decision(item["score"], prior["protocol"]):
            raise ValueError("Resume prediction is invalid")
        if backend == "model" and (prior["model_revision"] != prior["protocol"]["model_revision"]
                or item["raw"]["usage"]["output_tokens"] != 0
                or item["raw"]["answers"]["risk"]["noul"] != item["score"]):
            raise ValueError("Resume model response differs")
    return saved


def progress(item):
    try:
        print(item["id"], item["score"], item["decision"], round(item["wall_seconds"], 3), flush=True)
    except BrokenPipeError:
        # A detached progress reader must not terminate persisted inference.
        with open(os.devnull, "w") as sink:
            os.dup2(sink.fileno(), sys.stdout.fileno())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", choices=["baseline", "model"], required=True)
    parser.add_argument("--snapshot", type=Path, default=Path("/workspace/private/d1-grooming/snapshot"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--resume-from", type=Path, help="Reuse a verified prefix into a new output directory")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    protocol = read_json("protocol.json")
    rows = dataset()
    metadata = {"status": "starting", "backend": args.backend, "protocol": protocol, "machine": machine(),
                "inputs_sha256": {n: sha256(HERE / n) for n in ["synthetic.jsonl", "questions.json", "protocol.json", "baseline.json", "run.py"]}}
    write_json(args.output / "run.json", metadata)
    start, cpu_start = time.perf_counter(), time.process_time()
    try:
        predictions = resume_predictions(args.resume_from, rows, args.backend, metadata["inputs_sha256"]) if args.resume_from else []
        metadata["inherited_rows"] = len(predictions)
        if args.resume_from:
            metadata.update(resumed_from=str(args.resume_from.resolve()),
                            prior_run_sha256=sha256(args.resume_from / "run.json"),
                            prior_predictions_sha256=sha256(args.resume_from / "predictions.jsonl"),
                            timing_scope="This process only; inherited rows retain prior timings")
        if args.backend == "model":
            manifest = verify(args.snapshot)
            metadata["model_revision"] = manifest["revision"]
            # Set offline/cache policy before importing any model dependency.
            os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1",
                              HF_HOME="/workspace/private/d1-grooming/hf-cache",
                              HF_MODULES_CACHE="/workspace/private/d1-grooming/hf-modules")
            import torch
            import transformers
            from transformers import AutoModel
            torch.set_num_threads(protocol["threads"])
            torch.set_num_interop_threads(1)
            torch.manual_seed(0)
            metadata.update(torch=torch.__version__, transformers=transformers.__version__,
                            cuda_available=torch.cuda.is_available(), thread_count=torch.get_num_threads())
            load_start, load_cpu = time.perf_counter(), time.process_time()
            model = AutoModel.from_pretrained(str(args.snapshot.resolve()), trust_remote_code=True,
                                             local_files_only=True, dtype=torch.float32).eval().to("cpu")
            metadata.update(load_wall_seconds=time.perf_counter() - load_start,
                            load_cpu_seconds=time.process_time() - load_cpu,
                            parameter_count=sum(p.numel() for p in model.parameters()),
                            parameter_bytes=sum(p.numel() * p.element_size() for p in model.parameters()))
            questions = read_json("questions.json")
            # The reviewed code truncates instructions at a 96-token binary-question budget.
            assert len(model.tokenizer(questions["risk"]["instructions"], add_special_tokens=False)["input_ids"]) <= 95
            warm_start = time.perf_counter()
            with torch.inference_mode():
                warm = model.system_one("I was charged twice; please refund one charge.",
                                        {"refund": {"type": "noul", "instructions": "Is a refund requested?"}})
            metadata.update(warmup_wall_seconds=time.perf_counter() - warm_start, warmup=warm)
        else:
            pattern = re.compile(read_json("baseline.json")["pattern"], re.IGNORECASE)
        with (args.output / "predictions.jsonl").open("w") as f:
            for item in predictions:
                f.write(json.dumps(item, ensure_ascii=False, allow_nan=False) + "\n")
            f.flush()
            for row in rows[len(predictions):]:
                item = {k: row[k] for k in ["id", "family", "split", "language", "stage", "label"]}
                before, cpu_before = time.perf_counter(), time.process_time()
                try:
                    if args.backend == "model":
                        # Never pass the gold label, rationale, family or stage to the model.
                        with torch.inference_mode():
                            raw = model.system_one({"chat": row["messages"]}, questions)
                        score = float(raw["answers"]["risk"]["noul"])
                        item["raw"] = raw
                    else:
                        score = float(baseline(row, pattern))
                    item.update(score=score, decision=decision(score, protocol))
                except Exception as error:
                    item.update(score=None, decision="review", error=f"{type(error).__name__}: {error}")
                item.update(wall_seconds=time.perf_counter() - before, cpu_seconds=time.process_time() - cpu_before)
                predictions.append(item)
                f.write(json.dumps(item, ensure_ascii=False, allow_nan=False) + "\n")
                f.flush()
                progress(item)
        write_json(args.output / "metrics.json", summarize(predictions, args.backend))
        metadata["status"] = "completed" if not any(r.get("error") for r in predictions) else "completed_with_errors"
    except Exception:
        metadata.update(status="blocked", traceback=traceback.format_exc())
        raise
    finally:
        metadata.update(total_wall_seconds=time.perf_counter() - start,
                        total_cpu_seconds=time.process_time() - cpu_start,
                        peak_rss_mib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024)
        write_json(args.output / "run.json", metadata)


if __name__ == "__main__":
    main()
