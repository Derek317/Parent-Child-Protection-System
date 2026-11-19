"""Score one model on one test set and write predictions, metrics and run metadata.

    python research/run_eval.py --kind d1 --set blind90 --name d1-q8 --port 8081
"""
import argparse
import hashlib
import json
import platform
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from igrisk import metrics, scorers  # noqa: E402
from igrisk.client import request  # noqa: E402
from igrisk.data import RESEARCH, excluding_potential, load_set, sha256_file  # noqa: E402

PREDICTION_FIELDS = ("id", "family", "language", "truth", "level")
HARNESS_CHECK = {"risk": (30, 34), "benign": (28, 32)}  # d1 Q8 on blind90 (design doc, stage 1)


def server_info(port):
    props = request(port, "/props", timeout=30)
    models = request(port, "/v1/models", timeout=30)
    ids = [m.get("id") for m in models.get("data", [])]
    name = (ids[0] or "").replace("\\", "/").split("/")[-1] if ids else None
    return {"model_name": name, "model_path": props.get("model_path"),
            "build_info": props.get("build_info"), "total_slots": props.get("total_slots"),
            "chat_template": props.get("chat_template")}


def write_json(path, obj):
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def read_predictions(path, wanted_ids):
    done = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                if row["id"] in wanted_ids:
                    done[row["id"]] = row
    return done


def harness_check(rows, handoff_file):
    """blind90 + d1 only: counts must fall in the agreed range; compare with the earlier project's output."""
    risk = sum(r["flagged"] for r in rows if r["truth"] == "risk")
    benign = sum(r["flagged"] for r in rows if r["truth"] == "benign")
    theirs = {}
    for line in handoff_file.read_text(encoding="utf-8").splitlines():
        if line.strip():
            d = json.loads(line)
            theirs[d["id"]] = d["binary_original"]
    diffs = [abs(r["alert_score"] - theirs[r["id"]]) for r in rows if r["id"] in theirs]
    ok = (HARNESS_CHECK["risk"][0] <= risk <= HARNESS_CHECK["risk"][1]
          and HARNESS_CHECK["benign"][0] <= benign <= HARNESS_CHECK["benign"][1])
    return {"risk_flagged": risk, "benign_flagged": benign, "expected": HARNESS_CHECK, "in_range": ok,
            "abs_diff_vs_handoff_max": max(diffs), "abs_diff_vs_handoff_mean": sum(diffs) / len(diffs)}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--kind", required=True, choices=["keywords", "d1", "chat", "labels"])
    ap.add_argument("--set", required=True, dest="set_spec", help="blind90, severity60 or a .jsonl path")
    ap.add_argument("--name", required=True)
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--workers", type=int, default=1)
    ap.add_argument("--template", choices=["server", "chatml"], default="server",
                    help="chat only: 'server' uses the model's own template; 'chatml' renders plain ChatML")
    ap.add_argument("--chat-kwargs", default='{"enable_thinking": false}',
                    help="chat only: chat_template_kwargs as JSON (thinking off by default)")
    ap.add_argument("--labels", type=Path, help="labels kind: JSONL of {id, answer}")
    args = ap.parse_args()

    set_label, rows, input_info = load_set(args.set_spec)
    out_dir = RESEARCH / "results" / set_label / args.name
    out_dir.mkdir(parents=True, exist_ok=True)
    ctx = {"port": args.port, "mode": input_info["mode"], "template": args.template,
           "chat_kwargs": json.loads(args.chat_kwargs)}
    run = {"name": args.name, "kind": args.kind, "set": set_label, "input": input_info,
           "python": platform.python_version(), "platform": platform.platform(), "template": args.template}
    scorer = {"keywords": scorers.score_keywords, "d1": scorers.score_d1,
              "chat": scorers.score_chat, "labels": scorers.score_labels}[args.kind]

    if args.kind == "keywords":
        run["model"] = "keywords (GuardEngine.SEXUAL_PATTERN)"
    if args.kind == "d1":
        ctx["rubric"] = json.loads(scorers.RUBRIC_FILE.read_text(encoding="utf-8"))
        run["rubric_sha256"] = sha256_file(scorers.RUBRIC_FILE)
        run["question_sha256"] = sha256_file(scorers.BINARY_FILE if ctx["mode"] == "binary" else scorers.RUBRIC_FILE)
    if args.kind in ("d1", "chat"):
        run["server"] = server_info(args.port)
        run["model"] = run["server"]["model_name"]
    if args.kind == "chat":
        text = scorers.INSTRUCTION.read_bytes()
        run["instruction_sha256"] = hashlib.sha256(text).hexdigest()
        ctx["instruction"] = text.decode("utf-8")
        if args.template == "server":
            run["chat_kwargs"] = ctx["chat_kwargs"]
        prompt, tok = scorers.tokenize_prompt(ctx, rows[0]["messages"])
        pieces = [t.get("piece") for t in tok["tokens"]] if tok["tokens"] and isinstance(tok["tokens"][0], dict) else None
        run["first_prompt"] = {"id": rows[0]["id"], "text": prompt, "n_tokens": len(tok["tokens"]),
                               "first_pieces": pieces[:3] if pieces else None,
                               "last_pieces": pieces[-6:] if pieces else None}
        print("---- rendered prompt tail (the answer token goes right after this) ----")
        print(repr(prompt[-160:]))
    if args.kind == "labels":
        if not args.labels:
            ap.error("--labels is required for --kind labels")
        ctx["labels"] = {}
        for line in args.labels.read_text(encoding="utf-8").splitlines():
            if line.strip():
                d = json.loads(line)
                ctx["labels"][d["id"]] = str(d["answer"]).strip().upper()
        run["labels_sha256"] = sha256_file(args.labels)

    pred_path = out_dir / "predictions.jsonl"
    done = read_predictions(pred_path, {r["id"] for r in rows})
    todo = [r for r in rows if r["id"] not in done]
    print(f"{len(done)} done, {len(todo)} to run")

    def work(row):
        t0 = time.perf_counter()
        out = scorer(row, ctx)
        out["seconds"] = time.perf_counter() - t0
        return {**{k: row[k] for k in PREDICTION_FIELDS}, **out}

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as pool, open(pred_path, "a", encoding="utf-8") as sink:
        for result in pool.map(work, todo):
            sink.write(json.dumps(result, ensure_ascii=False, allow_nan=False) + "\n")
            sink.flush()
            done[result["id"]] = result
            if len(done) % 10 == 0:
                print(f"{len(done)}/{len(rows)}", flush=True)
    if args.kind == "d1" and ctx.get("model_name"):
        run["model"] = ctx["model_name"]  # the model name d1's own endpoint reports
    run["timing"] = {"wall_seconds_this_invocation": time.perf_counter() - started,
                     "chats_run_this_invocation": len(todo), "workers": args.workers}

    preds = [done[r["id"]] for r in rows]  # dataset order
    result = {"meta": {"name": args.name, "kind": args.kind, "set": set_label, "model": run.get("model")},
              **metrics.evaluate(preds)}
    if args.set_spec == "blind90":
        result["variants"] = {"excluding_potential6": metrics.evaluate(excluding_potential(preds))}
    if args.kind == "chat":
        masses = [p["answer_mass"] for p in preds]
        result["answer_mass"] = {"mean": sum(masses) / len(masses), "min": min(masses),
                                 "below_0.5": sum(m < 0.5 for m in masses), "n": len(masses)}
    if args.kind == "d1" and args.set_spec == "blind90":
        result["harness_check"] = harness_check(preds, RESEARCH / "data" / "handoff" / "d1-q8-handoff-predictions.jsonl")
    write_json(out_dir / "metrics.json", result)
    write_json(out_dir / "run.json", run)
    g = result["groups"]["all"]
    print(f"risk {g['risk']['k']}/{g['risk']['n']}  benign {g['benign']['k']}/{g['benign']['n']}  "
          f"potential {g['potential']['k']}/{g['potential']['n']}  unresolved {g['unresolved']['k']}/{g['unresolved']['n']}  "
          f"AUROC {g['auroc']}")
    print("pass mark:", "PASS" if result["pass_mark"]["pass"] else "FAIL " + "; ".join(result["pass_mark"]["failed"]))
    if "harness_check" in result:
        print("harness check:", json.dumps(result["harness_check"]))
    if "answer_mass" in result:
        print("answer_mass:", json.dumps(result["answer_mass"]))


if __name__ == "__main__":
    main()
