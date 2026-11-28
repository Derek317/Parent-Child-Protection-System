import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from igrisk import metrics  # noqa: E402
from igrisk.scorers import SEXUAL_PATTERN, five_way, _from_probs  # noqa: E402

_n = [0]


def row(truth, flagged, score=None, family="f", language="en", answer=None, level=None):
    _n[0] += 1
    return {"id": f"c{_n[0]}", "family": family, "language": language, "truth": truth, "level": level,
            "alert_score": (1.0 if flagged else 0.0) if score is None else score,
            "flagged": flagged, "answer": answer, "report_score": None}


def rate_rows(language, risk_k, risk_n, benign_k, benign_n, family="f"):
    return ([row("risk", i < risk_k, language=language, family=family) for i in range(risk_n)]
            + [row("benign", i < benign_k, language=language, family=family) for i in range(benign_n)])


class CountsAndAuroc(unittest.TestCase):
    def test_counts_exclude_potential_and_unresolved_from_rates(self):
        rows = [row("risk", True), row("risk", False), row("benign", True), row("benign", False),
                row("benign", False), row("potential", True), row("unresolved", True), row("unresolved", False)]
        s = metrics.group_stats(rows)
        self.assertEqual((s["risk"]["k"], s["risk"]["n"]), (1, 2))
        self.assertEqual((s["benign"]["k"], s["benign"]["n"]), (1, 3))
        self.assertEqual((s["potential"]["k"], s["potential"]["n"]), (1, 1))
        self.assertEqual((s["unresolved"]["k"], s["unresolved"]["n"]), (1, 2))
        # AUROC ignores potential and unresolved: risk scores 1,0 vs benign 1,0,0 -> (.5+1+1+0+.5+.5)/6
        self.assertAlmostEqual(s["auroc"], (0.5 + 1 + 1 + 0 + 0.5 + 0.5) / 6)

    def test_auroc_hand_computed(self):
        rows = [row("risk", True, 0.9), row("risk", True, 0.6), row("benign", False, 0.6), row("benign", False, 0.2)]
        # pairs: .9>.6 1, .9>.2 1, .6=.6 .5, .6>.2 1 -> 3.5/4
        self.assertAlmostEqual(metrics.auroc(rows), 0.875)

    def test_auroc_all_tied_and_perfect(self):
        self.assertEqual(metrics.auroc([row("risk", True, 0.5), row("benign", True, 0.5)]), 0.5)
        self.assertEqual(metrics.auroc([row("risk", True, 0.9), row("benign", False, 0.1)]), 1.0)

    def test_auroc_empty_class_is_none(self):
        self.assertIsNone(metrics.auroc([row("risk", True)]))
        self.assertIsNone(metrics.auroc([row("benign", True), row("potential", True)]))

    def test_info(self):
        rows = [row("risk", True, answer="2", level=2), row("benign", False, answer="1", level=0),
                row("unresolved", False, answer="U"), row("unresolved", False, answer="1"),
                row("risk", True, answer=None, level=3)]
        i = metrics.info(rows)
        self.assertEqual((i["exact_match"]["k"], i["exact_match"]["n"]), (1, 2))
        self.assertEqual((i["unresolved_answered_U"]["k"], i["unresolved_answered_U"]["n"]), (1, 2))

    def test_exact_match_is_four_way_and_ignores_unresolved(self):
        rows = [row("unresolved", False, answer="U"), row("risk", True, answer="U", level=2)]
        i = metrics.info(rows)
        self.assertEqual((i["exact_match"]["k"], i["exact_match"]["n"]), (0, 1))
        self.assertEqual((i["unresolved_answered_U"]["k"], i["unresolved_answered_U"]["n"]), (1, 1))


class PassMark(unittest.TestCase):
    def build(self, overall_risk, overall_benign, langs):
        """langs: {language: (risk_k, risk_n, benign_k, benign_n)}; overall is not derived, so give stats directly."""
        stats = {}
        for g, (rk, rn, bk, bn) in {"all": overall_risk + overall_benign, **langs}.items():
            stats[g] = {"risk": {"k": rk, "n": rn, "rate": rk / rn}, "benign": {"k": bk, "n": bn, "rate": bk / bn}}
        return metrics.pass_mark(stats)

    def langs(self, rk, bk, n=10):
        return {l: (rk, n, bk, n) for l in metrics.LANGUAGES}

    def test_exact_boundaries_pass(self):
        r = self.build((80, 100), (20, 100), self.langs(7, 3))
        self.assertTrue(r["pass"], r)

    def test_just_outside_overall_fails(self):
        self.assertFalse(self.build((79, 100), (20, 100), self.langs(7, 3))["pass"])
        r = self.build((80, 100), (21, 100), self.langs(7, 3))
        self.assertFalse(r["pass"])
        self.assertTrue(any("all benign" in f for f in r["failed"]))

    def test_just_outside_language_fails(self):
        self.assertFalse(self.build((80, 100), (20, 100), self.langs(6, 3))["pass"])
        self.assertFalse(self.build((80, 100), (20, 100), self.langs(7, 4))["pass"])

    def test_one_language_fails_while_overall_passes(self):
        langs = self.langs(7, 3)
        langs["mixed"] = (6, 10, 3, 10)
        r = self.build((90, 100), (10, 100), langs)
        self.assertFalse(r["pass"])
        self.assertEqual(len(r["failed"]), 1)
        self.assertIn("mixed risk", r["failed"][0])

    def test_empty_class_fails(self):
        rows = [row("risk", True), row("benign", False)]  # no other languages
        r = metrics.pass_mark({g: metrics.group_stats([x for x in rows if g in ("all", x["language"])]) for g in metrics.GROUPS})
        self.assertFalse(r["pass"])

    def test_pass_mark_end_to_end(self):
        rows = []
        for lang in metrics.LANGUAGES:
            rows += rate_rows(lang, 9, 10, 1, 10)
        self.assertTrue(metrics.evaluate(rows, replicates=20)["pass_mark"]["pass"])


class Bootstrap(unittest.TestCase):
    def rows(self):
        rows = []
        for i, f in enumerate(["a", "b", "c", "d", "e"]):
            rows += [row("risk", i % 2 == 0, score=0.8 - 0.1 * i, family=f),
                     row("benign", i % 3 == 0, score=0.1 + 0.1 * i, family=f)]
        return rows

    def test_deterministic(self):
        a = metrics.bootstrap(self.rows(), replicates=200)
        b = metrics.bootstrap(self.rows(), replicates=200)
        self.assertEqual(a, b)

    def test_seed_changes_result(self):
        a = metrics.bootstrap(self.rows(), replicates=200, seed=1)
        b = metrics.bootstrap(self.rows(), replicates=200, seed=2)
        self.assertNotEqual(a, b)

    def test_single_family_is_degenerate(self):
        rows = [row("risk", True, 0.9, family="only"), row("risk", False, 0.3, family="only"),
                row("benign", False, 0.2, family="only")]
        r = metrics.bootstrap(rows, replicates=50)
        self.assertEqual(r["all"]["risk"], [0.5, 0.5])
        self.assertEqual(r["all"]["benign"], [0.0, 0.0])
        self.assertEqual(r["all"]["auroc"][0], r["all"]["auroc"][1])
        self.assertIsNone(r["zh-Hant-yue"]["risk"])  # never defined

    def test_range_contains_point_estimate_here(self):
        rows = self.rows()
        r = metrics.evaluate(rows, replicates=300)["groups"]["all"]
        lo, hi = r["risk"]["range"]
        self.assertLessEqual(lo, r["risk"]["rate"] + 1e-9)
        self.assertGreaterEqual(hi, r["risk"]["rate"] - 1e-9)


class FiveTokens(unittest.TestCase):
    def response(self, cands, key="top_logprobs"):
        return {"completion_probabilities": [{"id": 1, "token": "2", "logprob": -0.1, key: cands}]}

    def test_sums_variants_and_renormalises(self):
        import math
        cands = [{"token": "2", "logprob": math.log(0.4)}, {"token": " 2", "logprob": math.log(0.2)},
                 {"token": "0", "logprob": math.log(0.1)}, {"token": "U", "logprob": math.log(0.1)},
                 {"token": "The", "logprob": math.log(0.15)}, {"token": "22", "logprob": math.log(0.05)}]
        p, mass = five_way(self.response(cands))
        self.assertAlmostEqual(mass, 0.8)
        self.assertAlmostEqual(p["2"], 0.75)
        self.assertAlmostEqual(p["0"], 0.125)
        self.assertAlmostEqual(p["U"], 0.125)
        self.assertAlmostEqual(sum(p.values()), 1.0)

    def test_probability_format_and_no_candidates(self):
        p, mass = five_way(self.response([{"tok_str": "3", "prob": 0.5}, {"tok_str": "x", "prob": 0.5}], key="probs"))
        self.assertEqual((p["3"], mass), (1.0, 0.5))
        p, mass = five_way(self.response([{"token": "hello", "logprob": -1.0}]))
        self.assertEqual(mass, 0.0)
        self.assertIsNone(_from_probs(p, mass)["answer"])

    def test_scores(self):
        p = {"0": 0.1, "1": 0.2, "2": 0.3, "3": 0.2, "U": 0.2}
        out = _from_probs(p, 1.0)
        self.assertAlmostEqual(out["alert_score"], 0.5)
        self.assertTrue(out["flagged"])
        self.assertAlmostEqual(out["report_score"], 100 * (0.2 + 0.6 + 0.6) / (3 * 0.8))
        self.assertEqual(out["answer"], "2")
        p = {"0": 0.1, "1": 0.1, "2": 0.1, "3": 0.1, "U": 0.6}
        out = _from_probs(p, 1.0)
        self.assertEqual(out["answer"], "U")
        self.assertIsNone(out["report_score"])


class Keywords(unittest.TestCase):
    def test_matches(self):
        for text in ["send me a pic", "Send a pic please", "dont tell your mum", "don't tell mom", "NUDES",
                     "wanna hook up?", "secret between us ok", "你有冇裸照", "唔好同阿媽講", "hookup", "OnlyFans"]:
            self.assertTrue(SEXUAL_PATTERN.search(text), text)

    def test_non_matches(self):
        for text in ["Essex is nice", "we had class about sextant", "see you at school", "I got a pic of the cat",
                     "my dad is nice", "今日去咗學校", "nakedness"]:
            self.assertFalse(SEXUAL_PATTERN.search(text), text)


if __name__ == "__main__":
    unittest.main()
