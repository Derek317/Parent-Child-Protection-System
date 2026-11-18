"""Check family files against the writing guide. Prints OK or a list of problems per file."""
import argparse
import re
import sys
from itertools import combinations
from pathlib import Path

from dt_common import (ENDINGS, LANG_CODES, LANGUAGES, LEVELS, SUFFIX_OF,
                       add_data_dir_arg, chat_key, fail, load_catalogue, read_jsonl)

ROW_KEYS = {"id", "family", "language", "ending", "level", "other_role", "messages"}
MARKERS = set("係唔嘅咗喺啲嘢佢冇咁㗎喎啦囉咩")
SIMPLIFIED = set("这们说没吗对时为会个来还过问让给谁么样觉应该现发见听话请谢欢妈爸钱买卖车门间学习课师写读书图网电机视频照张")
MANDARIN_WORDS = ["不是", "什麼", "沒有", "他們", "我們", "你們", "這個", "那個", "的話"]
PHONE_RE = re.compile(r"\d{8,}")
HANDLE_RE = re.compile(r"@\w")


def is_cjk(c):
    o = ord(c)
    return (0x3400 <= o <= 0x4DBF or 0x4E00 <= o <= 0x9FFF or 0xF900 <= o <= 0xFAFF
            or 0x20000 <= o <= 0x2A6DF)


def script_stats(text):
    cjk = sum(1 for c in text if is_cjk(c))
    latin = sum(1 for c in text if c.isascii() and c.isalpha())
    total = cjk + latin + sum(1 for c in text if c.isalpha() and not c.isascii() and not is_cjk(c))
    return cjk, latin, total


def check_chat(row, cid, family, cat_row, problems, warnings):
    """Append problems for one chat row. cid is a label for messages."""
    def bad(msg):
        problems.append(f"{cid}: {msg}")

    if not isinstance(row, dict):
        bad("row is not a JSON object")
        return
    keys = set(row)
    if keys != ROW_KEYS:
        missing = sorted(ROW_KEYS - keys)
        extra = sorted(keys - ROW_KEYS)
        bad(f"keys must be exactly {sorted(ROW_KEYS)}; missing {missing}, unexpected {extra}")
    ending = row.get("ending")
    lang = row.get("language")
    if ending not in LEVELS:
        bad(f"ending {ending!r} must be one of {ENDINGS}")
    if lang not in LANGUAGES:
        bad(f"language {lang!r} must be one of {LANGUAGES}")
    if ending in LEVELS and lang in LANGUAGES:
        want = f"{family}-{ending}-{SUFFIX_OF[lang]}"
        if row.get("id") != want:
            bad(f"id is {row.get('id')!r} but must be {want!r}")
    if row.get("family") != family:
        bad(f"family is {row.get('family')!r} but must equal the file name {family!r}")
    if ending in LEVELS:
        lv = row.get("level")
        want_lv = LEVELS[ending]
        if want_lv is None:
            if lv is not None:
                bad(f"level is {lv!r} but ending 'unresolved' needs null")
        elif type(lv) is not int or lv != want_lv:
            bad(f"level is {lv!r} but ending {ending!r} needs {want_lv}")
    if cat_row is not None and row.get("other_role") != cat_row.get("other_role"):
        bad(f"other_role is {row.get('other_role')!r} but the catalogue says {cat_row.get('other_role')!r}")

    msgs = row.get("messages")
    if not isinstance(msgs, list):
        bad("messages must be a list")
        return
    if not 6 <= len(msgs) <= 30:
        bad(f"has {len(msgs)} messages; must be 6 to 30")
    speakers = set()
    texts = []
    for n, m in enumerate(msgs, 1):
        if not isinstance(m, dict) or set(m) != {"speaker", "text"}:
            bad(f"message {n} must be an object with exactly the keys speaker and text")
            continue
        if m["speaker"] not in ("child", "other"):
            bad(f"message {n} speaker {m['speaker']!r} must be 'child' or 'other'")
        else:
            speakers.add(m["speaker"])
        t = m["text"]
        if not isinstance(t, str) or not t.strip():
            bad(f"message {n} text must be a non-empty string")
            continue
        if len(t) > 600:
            bad(f"message {n} is {len(t)} characters; shorten it to 600 or fewer")
        texts.append(t)
        if PHONE_RE.search(t):
            bad(f"message {n} contains a run of 8 or more digits (looks like a phone number); remove it")
        if HANDLE_RE.search(t):
            bad(f"message {n} contains an @handle; remove it")
    if speakers != {"child", "other"} and all(isinstance(m, dict) for m in msgs):
        bad(f"both speakers 'child' and 'other' must appear; found {sorted(speakers)}")

    full = "\n".join(texts)
    cjk, latin, total = script_stats(full)
    cjk_pct = cjk / total if total else 0.0
    latin_pct = latin / total if total else 0.0
    if lang == "en":
        if cjk_pct >= 0.05:
            bad(f"English chat is {cjk_pct:.0%} Chinese characters; must be under 5%")
    elif lang == "zh-Hant-yue":
        if cjk_pct < 0.60:
            bad(f"Cantonese chat is only {cjk_pct:.0%} Chinese characters; needs at least 60%")
        found = {c for c in full if c in MARKERS}
        if len(found) < 3:
            bad(f"only {len(found)} distinct Cantonese marker characters ({''.join(sorted(found)) or 'none'}); "
                f"need at least 3 of {''.join(sorted(MARKERS))}")
        std = {w: full.count(w) for w in MANDARIN_WORDS if full.count(w)}
        if sum(std.values()) > 2:
            warnings.append(f"{cid}: standard-Chinese words used {sum(std.values())} times "
                            f"({', '.join(f'{w}x{c}' for w, c in std.items())}); "
                            f"write Cantonese instead (唔係, 咩, 冇, 佢哋, 呢個, 嘅)")
    elif lang == "mixed":
        if cjk_pct < 0.15:
            bad(f"mixed chat is only {cjk_pct:.0%} Chinese characters; needs at least 15%")
        if latin_pct < 0.10:
            bad(f"mixed chat is only {latin_pct:.0%} Latin letters; needs at least 10%")
    simp = sorted({c for c in full if c in SIMPLIFIED})
    if simp:
        bad(f"contains Simplified characters {''.join(simp)}; use Traditional characters")


def validate_rows(rows, family, catalogue):
    problems, warnings = [], []
    cat_row = catalogue.get(family)
    if cat_row is None:
        problems.append(f"family {family!r} (the file name) is not in the catalogue")
    if len(rows) != 15:
        problems.append(f"file has {len(rows)} rows; must be exactly 15")
    seen = {}
    for n, row in enumerate(rows, 1):
        cid = row.get("id") if isinstance(row, dict) and isinstance(row.get("id"), str) else f"row {n}"
        check_chat(row, cid, family, cat_row, problems, warnings)
        if isinstance(row, dict):
            seen.setdefault((row.get("ending"), row.get("language")), []).append(cid)
    for e in ENDINGS:
        for lang in LANGUAGES:
            ids = seen.get((e, lang), [])
            if not ids:
                problems.append(f"missing chat: ending {e!r} in language {lang!r} (id {family}-{e}-{SUFFIX_OF[lang]})")
            elif len(ids) > 1:
                problems.append(f"ending {e!r} in language {lang!r} appears {len(ids)} times ({', '.join(ids)}); must be once")
    good = [r for r in rows if isinstance(r, dict) and isinstance(r.get("messages"), list)
            and all(isinstance(m, dict) for m in r["messages"])]
    for a, b in combinations(good, 2):
        ka, kb = chat_key(a), chat_key(b)
        ia, ib = a.get("id"), b.get("id")
        if ka == kb:
            problems.append(f"{ia} and {ib} have identical messages; rewrite one of them")
        elif (a.get("language") == b.get("language") and a.get("ending") != b.get("ending")
              and len(ka) > 1 and ka[:-1] == kb[:-1]):
            problems.append(f"{ia} and {ib} are the same chat except the last message; "
                            f"let the endings diverge from an early point")
    return problems, warnings


def validate_file(path, catalogue):
    path = Path(path)
    try:
        rows = read_jsonl(path)
    except (OSError, ValueError, UnicodeDecodeError) as exc:
        return [f"cannot read file: {exc}"], []
    return validate_rows(rows, path.stem, catalogue)


def collect(paths):
    files = []
    for p in paths:
        p = Path(p)
        if p.is_dir():
            files.extend(sorted(p.rglob("*.jsonl")))
        else:
            files.append(p)
    return files


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("paths", nargs="+", help="family .jsonl files or directories")
    add_data_dir_arg(ap)
    args = ap.parse_args(argv)
    try:
        catalogue = load_catalogue(args.data_dir)
    except (OSError, ValueError) as exc:
        return fail(f"error: {exc}")
    files = collect(args.paths)
    if not files:
        return fail("no .jsonl files found")
    bad = 0
    for f in files:
        problems, warnings = validate_file(f, catalogue)
        if problems:
            bad += 1
            print(f"{f}: {len(problems)} problem(s)")
            for p in problems:
                print(f"  - {p}")
        else:
            print(f"{f}: OK")
        for w in warnings:
            print(f"  warning: {w}")
    print(f"{len(files) - bad} of {len(files)} files OK")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
