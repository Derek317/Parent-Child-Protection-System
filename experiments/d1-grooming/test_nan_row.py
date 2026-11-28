import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock


class FakeModel:
    tokenizer = staticmethod(lambda text, add_special_tokens=False: {"input_ids": [1]})

    def __init__(self, scores):
        self.scores = iter(scores)

    def eval(self):
        return self

    def to(self, device):
        return self

    def parameters(self):
        return []

    def system_one(self, chat, questions):
        return {"answers": {"risk": {"noul": next(self.scores, 0.1)}}, "usage": {"output_tokens": 0}}


def fake_torch():
    torch = types.ModuleType("torch")
    torch.__version__ = "0"
    torch.float32 = None
    torch.set_num_threads = torch.set_num_interop_threads = torch.manual_seed = lambda n: None
    torch.cuda = types.SimpleNamespace(is_available=lambda: False)
    torch.get_num_threads = lambda: 1
    torch.inference_mode = mock.MagicMock
    return torch


def fake_transformers(model):
    transformers = types.ModuleType("transformers")
    transformers.__version__ = "0"
    transformers.AutoModel = types.SimpleNamespace(from_pretrained=lambda *a, **k: model)
    return transformers


ROWS = [{"id": f"r{i}", "family": f"f{i}", "split": "development", "language": "en", "stage": "s",
         "label": "benign", "messages": [{"speaker": "a", "text": "hi"}]} for i in range(3)]
PROTOCOL = {"review_low": 0.4, "review_high": 0.6, "binary_threshold": 0.5, "threads": 1, "model_revision": "v"}
QUESTIONS = {"risk": {"instructions": "q"}}


class NanRowTest(unittest.TestCase):
    def test_one_nan_score_does_not_abort_the_run(self):
        model = FakeModel([0.1, 0.1, float("nan"), 0.1])
        stubs = {"resource": types.SimpleNamespace(RUSAGE_SELF=0, getrusage=lambda who: types.SimpleNamespace(ru_maxrss=0)), "torch": fake_torch(), "transformers": fake_transformers(model)}
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        with mock.patch.dict(sys.modules, stubs):
            sys.modules.pop("run", None)
            import run
            with tempfile.TemporaryDirectory() as d, \
                    mock.patch.object(run, "dataset", lambda: ROWS), \
                    mock.patch.object(run, "verify", lambda snapshot: {"revision": "v"}), \
                    mock.patch.object(run, "sha256", lambda p: "x"), \
                    mock.patch.object(run, "read_json", lambda n: PROTOCOL if n == "protocol.json" else QUESTIONS), \
                    mock.patch.object(sys, "argv", ["run.py", "--backend", "model", "--output", str(Path(d) / "out")]):
                run.main()
                lines = (Path(d) / "out" / "predictions.jsonl").read_text().splitlines()
            sys.modules.pop("run", None)
        rows = [json.loads(s) for s in lines]
        self.assertEqual([r["id"] for r in rows], ["r0", "r1", "r2"])
        self.assertIn("error", rows[1])


if __name__ == "__main__":
    unittest.main()
