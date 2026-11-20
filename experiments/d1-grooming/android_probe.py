"""Replay synthetic chats through an adb-forwarded local System One endpoint.

Requires a separately started, pinned Android llama-server. Never starts or alters an app.
HTTP timings include the host/adb round trip; these are not on-device CPU timings.
"""
import argparse
import json
from pathlib import Path
import time
from urllib.request import Request, urlopen

from run import HERE, dataset, decision, read_json, sha256, summarize, write_json


def request(port, state, questions):
    payload = json.dumps({"state": state, "questions": questions}, ensure_ascii=False).encode()
    req = Request(f"http://127.0.0.1:{port}/v1/systemone", data=payload,
                  headers={"Content-Type": "application/json"})
    with urlopen(req, timeout=120) as response:
        raw = json.load(response)
    if raw["usage"]["output_tokens"] != 0:
        raise ValueError("Expected a zero-generation System One response")
    return raw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("Invalid local port")
    args.output.mkdir(parents=True, exist_ok=False)
    protocol, questions = read_json("protocol.json"), read_json("questions.json")
    metadata = {"status": "starting", "backend": "adb_forwarded_systemone",
                "device_identity_and_artifact_pins": "Must be recorded separately using ANDROID.md commands",
                "timing": "Host HTTP round trip including adb; excludes model load; no device CPU/RSS measurement",
                "inputs_sha256": {n: sha256(HERE / n) for n in ["questions.json", "synthetic.jsonl", "protocol.json"]}}
    write_json(args.output / "run.json", metadata)
    try:
        request(args.port, "Please refund the duplicate charge.",
                {"refund": {"type": "noul", "instructions": "Is a refund requested?"}})
        predictions = []
        with (args.output / "predictions.jsonl").open("w") as f:
            for row in dataset():
                item = {k: row[k] for k in ["id", "family", "split", "language", "stage", "label"]}
                start = time.perf_counter()
                try:
                    raw = request(args.port, {"chat": row["messages"]}, questions)
                    score = float(raw["answers"]["risk"]["noul"])
                    item.update(raw=raw, score=score, decision=decision(score, protocol))
                except Exception as error:
                    item.update(score=None, decision="review", error=f"{type(error).__name__}: {error}")
                item["wall_seconds"] = time.perf_counter() - start
                predictions.append(item)
                f.write(json.dumps(item, ensure_ascii=False, allow_nan=False) + "\n")
                f.flush()
        write_json(args.output / "metrics.json", summarize(predictions, "adb_forwarded_systemone"))
        metadata["status"] = "completed" if not any(r.get("error") for r in predictions) else "completed_with_errors"
    except Exception as error:
        metadata.update(status="blocked", error=f"{type(error).__name__}: {error}")
        raise
    finally:
        write_json(args.output / "run.json", metadata)


if __name__ == "__main__":
    main()
