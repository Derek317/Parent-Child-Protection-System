import json
import copy
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Thread

from android_probe import request

from run import HERE, baseline, confusion, dataset, decision, read_json, summarize


class HarnessTests(unittest.TestCase):
    def test_progress_survives_closed_pipe(self):
        script = """
import os
import run
r, w = os.pipe()
os.close(r)
os.dup2(w, 1)
os.close(w)
run.progress({'id': 'synthetic', 'score': 0.7, 'decision': 'risk', 'wall_seconds': 1})
print('still running', flush=True)
"""
        result = subprocess.run([sys.executable, "-c", script], cwd=HERE, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr.decode())

    def test_resume_preserves_prefix_and_infers_only_missing_rows(self):
        import run
        source = HERE / "results/baseline"
        rows = dataset()
        old = [json.loads(s) for s in (source / "predictions.jsonl").read_text().splitlines()]
        metadata = json.loads((source / "run.json").read_text())
        with tempfile.TemporaryDirectory() as directory:
            prior, output = Path(directory) / "prior", Path(directory) / "complete"
            prior.mkdir()
            run.write_json(prior / "run.json", metadata)
            (prior / "predictions.jsonl").write_text("".join(json.dumps(r) + "\n" for r in old[:23]))
            inherited = run.resume_predictions(prior, rows, "baseline", metadata["inputs_sha256"])
            self.assertEqual(inherited, old[:23])
            command = [sys.executable, str(HERE / "run.py"), "--backend", "baseline",
                       "--resume-from", str(prior), "--output", str(output)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            completed = [json.loads(s) for s in (output / "predictions.jsonl").read_text().splitlines()]
            self.assertEqual(completed[:23], old[:23])
            self.assertEqual([r["id"] for r in completed], [r["id"] for r in rows])
            self.assertEqual([r["score"] for r in completed], [r["score"] for r in old])
            self.assertEqual(len(result.stdout.splitlines()), 67)
            self.assertEqual(json.loads((output / "run.json").read_text())["inherited_rows"], 23)
            changed = copy.deepcopy(metadata)
            changed["inputs_sha256"]["questions.json"] = "different"
            run.write_json(prior / "run.json", changed)
            with self.assertRaises(ValueError):
                run.resume_predictions(prior, rows, "baseline", metadata["inputs_sha256"])
            run.write_json(prior / "run.json", metadata)
            (prior / "predictions.jsonl").write_text(json.dumps(old[1]) + "\n")
            with self.assertRaises(ValueError):
                run.resume_predictions(prior, rows, "baseline", metadata["inputs_sha256"])
            (prior / "predictions.jsonl").write_text(json.dumps(old[0]) + "\n")
            with self.assertRaises(ValueError):
                run.resume_predictions(prior, rows, "model", metadata["inputs_sha256"])

    def test_hand_computed_metrics(self):
        m = confusion([("risk", True), ("risk", False), ("benign", True), ("benign", False), ("benign", False)])
        self.assertEqual([m[k] for k in ["tp", "fn", "fp", "tn"]], [1, 1, 1, 2])
        self.assertEqual(m["precision"], 0.5)
        self.assertEqual(m["recall"], 0.5)
        self.assertAlmostEqual(m["false_positive_rate"], 1 / 3)
        self.assertEqual(confusion([])["precision"], None)

    def test_review_boundaries_and_invalid_scores(self):
        p = read_json("protocol.json")
        for score in [None, 0.35, 0.5, 0.65]:
            self.assertEqual(decision(score, p), "review")
        self.assertEqual(decision(0.349, p), "benign")
        self.assertEqual(decision(0.651, p), "risk")
        for score in [float("nan"), float("inf"), -0.1, 1.1]:
            with self.assertRaises(ValueError):
                decision(score, p)

    def test_fixture_family_isolation_and_counts(self):
        rows = dataset()
        self.assertEqual(len(rows), 90)
        self.assertEqual(len({r["family"] for r in rows}), 30)
        self.assertEqual(sum(r["split"] == "heldout_style" for r in rows), 72)
        for lang in ["en", "zh-Hant-yue", "mixed"]:
            held = [r for r in rows if r["split"] == "heldout_style" and r["language"] == lang]
            self.assertEqual([sum(r["label"] == l for r in held) for l in ["risk", "benign", "review"]], [10, 10, 4])

    def test_baseline_literal_and_matching(self):
        config = read_json("baseline.json")
        captured = (HERE / "evidence/baseline-source.kt.txt").read_text()
        strings = re.findall(r'"((?:\\.|[^"\\])*)"', captured)
        self.assertEqual("".join(json.loads('"' + s + '"') for s in strings), config["pattern"])
        pattern = re.compile(config["pattern"], re.I)
        for text in ["nudes", "NUDES", "send me a pic of the wheel", "唔好同阿媽講", "私密照"]:
            self.assertTrue(baseline({"messages": [{"speaker": "Peer", "text": text}]}, pattern))
        self.assertFalse(baseline({"messages": [{"speaker": "Peer", "text": "Sussex museum"}]}, pattern))

    def test_abstentions_do_not_disappear(self):
        rows = [dict(id="r", split="heldout_style", language="en", label="risk", score=0.4,
                     decision="review", wall_seconds=1),
                dict(id="b", split="heldout_style", language="en", label="benign", score=None,
                     decision="review", error="failure", wall_seconds=1),
                dict(id="u", split="heldout_style", language="en", label="review", score=0.9,
                     decision="risk", wall_seconds=1)]
        m = summarize(rows, "test")["groups"]["heldout_style/en"]
        self.assertEqual(m["forced_binary"]["fn"], 1)
        self.assertEqual(m["selective_binary"]["n"], 0)
        self.assertEqual(m["binary_coverage"], 0)
        self.assertEqual(m["errors"], 1)
        self.assertEqual(m["gold_review_total"], 1)
        self.assertEqual(m["gold_review_abstained"], 0)
        self.assertEqual(m["risk_not_cleared"], 1)

    def test_android_transport_schema_with_local_stub(self):
        seen = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                seen.append((self.path, json.loads(self.rfile.read(int(self.headers["Content-Length"])))))
                body = json.dumps({"answers": {"risk": {"type": "noul", "noul": 0.75}},
                                   "usage": {"input_tokens": 20, "output_tokens": len(seen) - 1}}).encode()
                self.send_response(200)
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        with HTTPServer(("127.0.0.1", 0), Handler) as server:
            worker = Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                raw = request(server.server_port, {"chat": "synthetic"}, read_json("questions.json"))
                self.assertEqual(raw["answers"]["risk"]["noul"], 0.75)
                self.assertEqual(seen[0][0], "/v1/systemone")
                self.assertEqual(seen[0][1]["state"], {"chat": "synthetic"})
                with self.assertRaises(ValueError):
                    request(server.server_port, "synthetic", read_json("questions.json"))
            finally:
                server.shutdown()
                worker.join()


if __name__ == "__main__":
    unittest.main()
