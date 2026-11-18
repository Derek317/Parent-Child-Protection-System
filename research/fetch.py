"""Download a file listed in research/models.json into research/models/ and verify it.

    python research/fetch.py d1-600m-q8
    python research/fetch.py --list
"""
import hashlib
import json
import sys
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

HERE = Path(__file__).resolve().parent
MODELS = HERE / "models"


def verify(path, entry):
    if not path.exists() or path.stat().st_size != entry["bytes"]:
        return False
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest() == entry["sha256"]


def main(argv):
    entries = json.loads((HERE / "models.json").read_text(encoding="utf-8"))
    if len(argv) != 1 or argv[0] == "--list" or argv[0] not in entries:
        print("Keys:", ", ".join(entries))
        return 0 if argv == ["--list"] else 2
    entry = entries[argv[0]]
    MODELS.mkdir(exist_ok=True)
    target = MODELS / Path(urlparse(entry["url"]).path).name
    if verify(target, entry):
        print(f"Already downloaded and verified: {target}")
        return 0
    part = target.with_name(target.name + ".part")
    print(f"Downloading {entry['url']}")
    done = next_report = 0
    with urllib.request.urlopen(entry["url"], timeout=120) as resp, open(part, "wb") as out:
        while True:
            block = resp.read(1 << 20)
            if not block:
                break
            out.write(block)
            done += len(block)
            if done >= next_report:  # one short line per 100 MB
                print(f"{done / 1e6:,.0f} / {entry['bytes'] / 1e6:,.0f} MB", flush=True)
                next_report = done + 100_000_000
    part.replace(target)
    if not verify(target, entry):
        target.unlink()
        print("FAILED: size or sha256 does not match models.json; file deleted.")
        return 1
    print(f"OK: {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
