import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "datatools"))

import build_sets  # noqa: E402
import dt_common  # noqa: E402
import make_cantonese_review  # noqa: E402
import seal  # noqa: E402
import split_catalogue  # noqa: E402
import validate_family  # noqa: E402

ZH = {"low": "低", "potential": "可能", "moderate": "中等", "high": "高", "unresolved": "未知"}
SUFFIX = {"en": "en", "zh-Hant-yue": "yue", "mixed": "mixed"}


def make_catalogue(n=180, adults=None):
    adults = n // 2 if adults is None else adults
    return [{"setting_id": f"s{i:03d}", "title": "t", "setting": "x",
             "other_role": "adult" if i <= adults else "peer",
             "other_description": "d", "child_age": 13, "region": f"r{i % 4}", "pressure_theme": "p"}
            for i in range(1, n + 1)]


def chat_messages(lang, ending, fam, count=6):
    out = []
    for i in range(count):
        who = "child" if i % 2 == 0 else "other"
        if lang == "en":
            t = f"hey {fam} this is the {ending} thing number {i}"
        elif lang == "zh-Hant-yue":
            t = f"{fam}我哋喺度傾緊{ZH[ending]}嘅嘢，佢冇話唔係咁啦 {i}"
        else:
            t = f"聽日我哋 send 咗個 {ending} link 畀你 {fam} {i}啦"
        out.append({"speaker": who, "text": t})
    return out


def make_family(fam, role="adult", counts=None):
    rows = []
    for lang in dt_common.LANGUAGES:
        for k, e in enumerate(dt_common.ENDINGS):
            rows.append({"id": f"{fam}-{e}-{SUFFIX[lang]}", "family": fam, "language": lang, "ending": e,
                         "level": dt_common.LEVELS[e], "other_role": role,
                         "messages": chat_messages(lang, e, fam, 6 + k + (6 if lang == "zh-Hant-yue" else 0))})
    return rows


def write_rows(path, rows):
    dt_common.write_jsonl(path, rows)


class ValidateTests(unittest.TestCase):
    def setUp(self):
        self.cat = {"s001": {"setting_id": "s001", "other_role": "adult"}}

    def check(self, mutate=None):
        rows = make_family("s001")
        if mutate:
            mutate(rows)
        return validate_family.validate_rows(rows, "s001", self.cat)

    def assertProblem(self, mutate, text):
        problems, _ = self.check(mutate)
        self.assertTrue(any(text in p for p in problems), f"{text!r} not in {problems}")

    def test_good_family(self):
        problems, warnings = self.check()
        self.assertEqual(problems, [])
        self.assertEqual(warnings, [])

    def test_good_family_file(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "s001.jsonl"
            write_rows(f, make_family("s001"))
            self.assertEqual(validate_family.validate_file(f, self.cat)[0], [])

    def test_row_count(self):
        self.assertProblem(lambda r: r.pop(), "must be exactly 15")

    def test_missing_combination(self):
        def m(r):
            r.pop()
            r.append(copy.deepcopy(r[0]))
        self.assertProblem(m, "appears 2 times")
        self.assertProblem(m, "missing chat")

    def test_bad_id(self):
        self.assertProblem(lambda r: r[0].__setitem__("id", "s001-low-en2"), "must be 's001-low-en'")

    def test_family_not_stem(self):
        def m(r):
            r[0]["family"] = "s002"
        self.assertProblem(m, "must equal the file name")

    def test_family_not_in_catalogue(self):
        rows = make_family("s009")
        problems, _ = validate_family.validate_rows(rows, "s009", self.cat)
        self.assertTrue(any("not in the catalogue" in p for p in problems))

    def test_level_mismatch(self):
        self.assertProblem(lambda r: r[1].__setitem__("level", 0), "needs 1")
        self.assertProblem(lambda r: r[4].__setitem__("level", 0), "needs null")

    def test_other_role(self):
        self.assertProblem(lambda r: r[0].__setitem__("other_role", "peer"), "catalogue says 'adult'")

    def test_message_count(self):
        self.assertProblem(lambda r: r[0].__setitem__("messages", r[0]["messages"][:5]), "5 messages")
        self.assertProblem(lambda r: r[0].__setitem__("messages", r[0]["messages"] * 6), "36 messages")

    def test_speakers(self):
        def m(r):
            r[0]["messages"][0]["speaker"] = "parent"
        self.assertProblem(m, "must be 'child' or 'other'")

        def m2(r):
            for x in r[0]["messages"]:
                x["speaker"] = "child"
        self.assertProblem(m2, "both speakers")

    def test_text_rules(self):
        def empty(r):
            r[0]["messages"][0]["text"] = "  "
        self.assertProblem(empty, "non-empty string")

        def long(r):
            r[0]["messages"][0]["text"] = "a" * 601
        self.assertProblem(long, "601 characters")

    def test_keys(self):
        self.assertProblem(lambda r: r[0].__setitem__("extra", 1), "unexpected ['extra']")

        def m(r):
            r[0]["messages"][0]["ts"] = 1
        self.assertProblem(m, "exactly the keys speaker and text")

    def test_language_en_cjk(self):
        def m(r):
            r[0]["messages"][0]["text"] = "你好嗎你好嗎你好嗎你好嗎"
        self.assertProblem(m, "English chat is")

    def test_language_yue(self):
        i = next(n for n, r in enumerate(make_family("s001")) if r["language"] == "zh-Hant-yue")

        def low_cjk(r):
            for x in r[i]["messages"]:
                x["text"] = "hello there friend how are you today 好"
        self.assertProblem(low_cjk, "needs at least 60%")

        def no_markers(r):
            for x in r[i]["messages"]:
                x["text"] = "今日天氣很好我想去公園玩"
        self.assertProblem(no_markers, "marker characters")

    def test_language_mixed(self):
        i = next(n for n, r in enumerate(make_family("s001")) if r["language"] == "mixed")

        def no_latin(r):
            for x in r[i]["messages"]:
                x["text"] = "聽日我哋去公園玩啦嘅"
        self.assertProblem(no_latin, "Latin letters")

        def no_cjk(r):
            for x in r[i]["messages"]:
                x["text"] = "see you tomorrow at the park ok"
        self.assertProblem(no_cjk, "Chinese characters; needs at least 15%")

    def test_simplified(self):
        def m(r):
            r[0]["messages"][0]["text"] = "hey 这 is odd"
        self.assertProblem(m, "Simplified characters 这")

    def test_mandarin_warning_only(self):
        i = next(n for n, r in enumerate(make_family("s001")) if r["language"] == "zh-Hant-yue")

        def m(r):
            r[i]["messages"][0]["text"] += "不是不是不是"
        problems, warnings = self.check(m)
        self.assertEqual(problems, [])
        self.assertEqual(len(warnings), 1)

    def test_length_spread_per_ending(self):
        def m(r):
            for x in r:
                if x["ending"] == "high":
                    x["messages"] = chat_messages(x["language"], "high", "s001", 8)
        self.assertProblem(m, "high")
        self.assertProblem(m, "11 or fewer")

    def test_phone_and_handle(self):
        def phone(r):
            r[0]["messages"][0]["text"] = "call 12345678 now"
        self.assertProblem(phone, "phone number")

        def handle(r):
            r[0]["messages"][0]["text"] = "add me @cool_kid"
        self.assertProblem(handle, "@handle")

    def test_identical_chats(self):
        def m(r):
            r[1]["messages"] = copy.deepcopy(r[0]["messages"])
        self.assertProblem(m, "identical messages")

    def test_same_except_last(self):
        def m(r):
            r[1]["messages"] = copy.deepcopy(r[0]["messages"])
            r[1]["messages"][-1]["text"] = "different ending"
        self.assertProblem(m, "same chat except the last message")


class SplitTests(unittest.TestCase):
    def setUp(self):
        self.cat = {r["setting_id"]: r for r in make_catalogue()}

    def test_sizes_and_balance(self):
        s = split_catalogue.make_splits(self.cat)
        self.assertEqual([len(s[k]) for k in ("train", "tune", "check", "sealed")], [100, 15, 15, 50])
        allids = sum((s[k] for k in ("train", "tune", "check", "sealed")), [])
        self.assertEqual(sorted(allids), sorted(self.cat))
        adults = {k: sum(self.cat[i]["other_role"] == "adult" for i in s[k]) for k in ("train", "tune", "check", "sealed")}
        self.assertEqual(adults["train"], 50)
        self.assertEqual(adults["sealed"], 25)
        self.assertIn(adults["tune"], (7, 8))
        self.assertEqual(adults["tune"] + adults["check"], 15)

    def test_determinism(self):
        self.assertEqual(split_catalogue.make_splits(self.cat), split_catalogue.make_splits(self.cat))

    def test_cli_writes_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as d:
            dd = Path(d)
            write_rows(dd / "catalogue" / "settings.jsonl", make_catalogue())
            self.assertEqual(split_catalogue.main(["--data-dir", d]), 0)
            self.assertTrue((dd / "briefs" / "sealed" / "batch-10.jsonl").exists())
            self.assertTrue((dd / "briefs" / "train" / "batch-20.jsonl").exists())
            self.assertEqual(len(dt_common.read_jsonl(dd / "briefs" / "tune" / "batch-01.jsonl")), 5)
            self.assertEqual(split_catalogue.main(["--data-dir", d]), 1)
            self.assertEqual(split_catalogue.main(["--data-dir", d, "--force"]), 0)
            sp = json.loads((dd / "splits.json").read_text(encoding="utf-8"))
            self.assertEqual(sp["seed"], 20261119)


class SealTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dd = Path(self.tmp.name)
        cat = make_catalogue(10)
        write_rows(self.dd / "catalogue" / "settings.jsonl", cat)
        self.fams = ["s001", "s002"]
        dt_common.write_json(self.dd / "splits.json", {"seed": 1, "train": [], "tune": [], "check": [], "sealed": self.fams})
        self.rows = {}
        for f in self.fams:
            rows = make_family(f)
            write_rows(self.dd / "raw" / "sealed" / f"{f}.jsonl", rows)
            for r in rows:
                self.rows[r["id"]] = r

    def tearDown(self):
        self.tmp.cleanup()

    def answers(self, overrides):
        key = dt_common.read_json(self.dd / "sealed-work" / "key.json")
        out = []
        for alias, rid in key.items():
            lv = self.rows[rid]["level"]
            out.append({"id": alias, "answer": "U" if lv is None else str(lv)})
        by = {r["id"]: r for r in out}
        inv = {v: k for k, v in key.items()}
        for rid, ans in overrides.items():
            by[inv[rid]]["answer"] = ans
        write_rows(self.dd / "sealed-work" / "answers-1.jsonl", out[:15])
        write_rows(self.dd / "sealed-work" / "answers-2.jsonl", out[15:])

    def test_strip(self):
        self.assertEqual(seal.strip(self.dd), [])
        parts = [dt_common.read_jsonl(self.dd / "sealed-work" / f"part-{i}.jsonl") for i in range(1, 6)]
        self.assertEqual([len(p) for p in parts], [6] * 5)
        for r in parts[0]:
            self.assertEqual(set(r), {"id", "messages"})

    def test_compare(self):
        seal.strip(self.dd)
        self.answers({
            "s001-low-en": "1",           # benign vs potential: set aside
            "s001-moderate-yue": "3",     # 2 vs 3: kept
            "s001-high-mixed": "2",       # 3 vs 2: kept
            "s002-high-en": "0",          # risk vs benign: set aside
            "s002-unresolved-yue": "1",   # unresolved vs potential: set aside
        })
        summary, errs = seal.compare(self.dd)
        self.assertEqual(errs, [])
        self.assertEqual(summary["total"], 30)
        self.assertEqual(summary["set_aside"], 3)
        self.assertEqual(summary["kept"], 27)
        self.assertEqual(summary["kept_2_vs_3_mismatches"], 2)
        self.assertEqual(summary["per_language"]["en"]["set_aside"], 2)
        self.assertEqual(summary["per_ending"]["unresolved"]["set_aside"], 1)
        self.assertEqual(summary["confusion_intended_by_blind_answer"]["low"]["1"], 1)
        self.assertEqual(summary["confusion_intended_by_blind_answer"]["moderate"]["3"], 1)
        self.assertEqual(summary["remaining_per_language"]["en"], {"risk": 3, "benign": 1, "potential": 2, "unresolved": 2})
        self.assertFalse(summary["over_15_percent"])
        kept = dt_common.read_jsonl(self.dd / "sealed-test.jsonl")
        aside = dt_common.read_jsonl(self.dd / "sealed-set-aside.jsonl")
        self.assertEqual(len(kept), 27)
        self.assertEqual([r["id"] for r in kept], sorted(r["id"] for r in kept))
        self.assertEqual({r["id"]: r["blind_answer"] for r in aside},
                         {"s001-low-en": 1, "s002-high-en": 0, "s002-unresolved-yue": 1})
        self.assertTrue((self.dd / "sealed-relabel-summary.json").exists())

    def test_compare_requires_full_coverage(self):
        seal.strip(self.dd)
        self.answers({})
        p = self.dd / "sealed-work" / "answers-2.jsonl"
        lines = p.read_text(encoding="utf-8").splitlines()
        p.write_text("\n".join(lines[:-1]) + "\n", encoding="utf-8")
        summary, errs = seal.compare(self.dd)
        self.assertIsNone(summary)
        self.assertTrue(errs)

    def test_hash_and_verify(self):
        seal.strip(self.dd)
        self.answers({"s001-low-en": "1"})
        seal.compare(self.dd)
        lines = seal.do_hash(self.dd)
        self.assertEqual(len(lines), 2)
        bad, ok = seal.do_verify(self.dd)
        self.assertEqual(bad, [])
        with open(self.dd / "sealed-test.jsonl", "ab") as fh:
            fh.write(b"\n")
        bad, _ = seal.do_verify(self.dd)
        self.assertEqual(len(bad), 1)


class BuildAndReviewTests(unittest.TestCase):
    def test_build_and_review(self):
        with tempfile.TemporaryDirectory() as d:
            dd = Path(d)
            cat = make_catalogue(50)
            write_rows(dd / "catalogue" / "settings.jsonl", cat)
            ids = [r["setting_id"] for r in cat]
            splits = {"seed": 1, "train": ids[:40], "tune": ids[40:45], "check": ids[45:], "sealed": []}
            dt_common.write_json(dd / "splits.json", splits)
            for s in ("train", "tune", "check"):
                for f in splits[s]:
                    role = cat[int(f[1:]) - 1]["other_role"]
                    write_rows(dd / "raw" / s / f"{f}.jsonl", make_family(f, role))
            self.assertEqual(build_sets.main(["--data-dir", d]), 0)
            self.assertEqual(len(dt_common.read_jsonl(dd / "train.jsonl")), 600)
            self.assertEqual(len(dt_common.read_jsonl(dd / "tune.jsonl")), 75)
            self.assertEqual(make_cantonese_review.main(["--data-dir", d]), 0)
            text = (dd / "cantonese-review.md").read_text(encoding="utf-8")
            self.assertEqual(text.count("- [ ] natural   - [ ] off   Note:"), 30)
            body = text.split("## Id to number table")[0]
            for e in dt_common.ENDINGS:
                self.assertNotIn(f"-{e}-", body)
            # missing family file blocks the build
            (dd / "raw" / "tune" / f"{splits['tune'][0]}.jsonl").unlink()
            self.assertEqual(build_sets.main(["--data-dir", d]), 1)


if __name__ == "__main__":
    unittest.main()
