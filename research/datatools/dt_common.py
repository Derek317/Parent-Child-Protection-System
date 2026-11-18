"""Shared helpers for the dataset tooling scripts (standard library only)."""
import json
import sys
from pathlib import Path

SEED = 20261119
ENDINGS = ["low", "potential", "moderate", "high", "unresolved"]
LEVELS = {"low": 0, "potential": 1, "moderate": 2, "high": 3, "unresolved": None}
LANG_CODES = {"en": "en", "yue": "zh-Hant-yue", "mixed": "mixed"}  # id suffix -> language
LANGUAGES = ["en", "zh-Hant-yue", "mixed"]
SUFFIX_OF = {v: k for k, v in LANG_CODES.items()}
SPLITS = ["train", "tune", "check", "sealed"]
SPLIT_SIZES = {"train": 100, "tune": 15, "check": 15, "sealed": 50}


def default_data_dir() -> Path:
    return Path(__file__).resolve().parents[1] / "data"


def add_data_dir_arg(parser):
    parser.add_argument("--data-dir", type=Path, default=default_data_dir(),
                        help="data directory (default: research/data)")


def read_jsonl(path):
    rows = []
    with open(path, "r", encoding="utf-8") as fh:
        for n, line in enumerate(fh, 1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}: line {n} is not valid JSON ({exc})")
    return rows


def write_jsonl(path, rows):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")


def write_json(path, obj):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(json.dumps(obj, ensure_ascii=False, indent=2) + "\n")


def read_json(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def load_catalogue(data_dir):
    path = Path(data_dir) / "catalogue" / "settings.jsonl"
    if not path.exists():
        raise FileNotFoundError(f"catalogue not found: {path}")
    return {r["setting_id"]: r for r in read_jsonl(path)}


def load_splits(data_dir):
    path = Path(data_dir) / "splits.json"
    if not path.exists():
        raise FileNotFoundError(f"splits file not found: {path} (run split_catalogue.py first)")
    return read_json(path)


def chat_key(row):
    return tuple((m.get("speaker"), m.get("text")) for m in row["messages"])


def fail(msg):
    print(msg, file=sys.stderr)
    return 1
