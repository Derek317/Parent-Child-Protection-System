"""Fetch only the reviewed pinned text-inference snapshot; never import its code."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

HERE = Path(__file__).resolve().parent


def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def verify(snapshot):
    manifest = json.loads((HERE / "evidence/model-provenance.json").read_text())
    for name, expected in manifest["files"].items():
        path = snapshot / name
        if not path.is_file() or sha256(path) != expected["sha256"]:
            raise ValueError(f"Missing or changed pinned model file: {name}")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    args = parser.parse_args()
    snapshot = args.snapshot.resolve()
    if snapshot.is_relative_to(HERE.parents[1]):
        parser.error("Keep external model files outside the Git worktree")
    snapshot.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((HERE / "evidence/model-provenance.json").read_text())
    for name, expected in manifest["files"].items():
        path = snapshot / name
        if path.exists() and sha256(path) == expected["sha256"]:
            continue
        url = f"https://huggingface.co/{manifest['repository']}/resolve/{manifest['revision']}/{name}"
        temp = path.with_name(name + ".part")
        h = hashlib.sha256()
        with urlopen(url, timeout=120) as response, temp.open("wb") as f:
            for block in iter(lambda: response.read(1024 * 1024), b""):
                h.update(block)
                f.write(block)
        if h.hexdigest() != expected["sha256"]:
            temp.unlink()
            raise ValueError(f"Download hash mismatch: {name}")
        temp.replace(path)
        print(name, expected["sha256"], flush=True)
    verify(snapshot)
