"""Scorers. Each returns a dict with alert_score, flagged, answer, report_score (+ extras)."""
import json
import math
import re
from pathlib import Path

from .client import request
from .data import RESEARCH

INSTRUCTION = RESEARCH / "prompts" / "instruction-v1.txt"
RUBRIC_FILE = RESEARCH / "data" / "handoff" / "d1-rubric.json"
BINARY_FILE = RESEARCH / "data" / "handoff" / "d1-binary-question.json"
ANSWERS = ("0", "1", "2", "3", "U")
MAX_MESSAGES = 30

# --- keywords: port of GuardEngine.SEXUAL_PATTERN (MainActivity.kt) ---
SEXUAL_PATTERN = re.compile(
    r"\b(nudes?|naked|porn|sex|horny|boobs?|tits|dick|cock|pussy|blowjob|handjob|onlyfans|hook ?up|fwb|"
    r"send (me )?(a )?pic|take it off|secret between us|don'?t tell (your )?(mum|mom|dad|parents)|"
    r"ptgf|sugar ?daddy|sugar ?baby)\b|"
    r"(裸照|露點|色情|做愛|扑嘢|約炮|援交|包養|兼職女友|自慰|手淫|私密照|閃卡|J圖|咸相|鹹相|裸聊|電愛|"
    r"唔好同(屋企人|阿媽|阿爸|父母)講|拍片比我睇|除衫)",
    re.IGNORECASE)


def score_keywords(row, ctx):
    hit = any(SEXUAL_PATTERN.search(m["text"]) for m in row["messages"])
    return {"alert_score": 1.0 if hit else 0.0, "flagged": hit, "answer": None, "report_score": None}


# --- d1 decision call ---
def _prob(v):
    if type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1:
        raise ValueError(f"Invalid d1 probability: {v!r}")
    return float(v)


def d1_binary(response):
    p = _prob(response["answers"]["risk"]["noul"])
    return {"alert_score": p, "flagged": p >= 0.5, "answer": None, "report_score": None, "yes_probability": p}


def d1_severity(response, rubric):
    """Follows assess() in the handoff's ordinal/score.py."""
    sev, enough = response["answers"]["severity"], response["answers"]["enough"]
    if sev["type"] != "score" or enough["type"] != "noul" or response["usage"]["output_tokens"] != 0:
        raise ValueError("Expected native d1 scores")
    if set(sev["probabilities"]) != {"0", "1", "2", "3"}:
        raise ValueError("Expected four severity levels")
    p = [_prob(sev["probabilities"][str(i)]) for i in range(4)]
    expectation = sum(i * v for i, v in enumerate(p))
    if abs(sum(p) - 1) > 1e-5 or not math.isclose(expectation, sev["score"], abs_tol=1e-5):
        raise ValueError("Inconsistent d1 severity score")
    context = _prob(enough["noul"])
    raw = min(100.0, 100.0 * expectation / 3)
    withheld = context < rubric["context_min"] or max(p) < rubric["level_confidence_min"]
    score = None if withheld else raw
    return {"alert_score": p[2] + p[3], "flagged": score is not None and score >= 50,
            "answer": "U" if withheld else str(p.index(max(p))), "report_score": score,
            "raw_score": raw, "enough": context, "level_probs": p}


def score_d1(row, ctx):
    mode = ctx["mode"]
    questions = (json.loads(BINARY_FILE.read_text(encoding="utf-8")) if mode == "binary"
                 else ctx["rubric"]["questions"])
    response = request(ctx["port"], "/v1/systemone",
                       {"state": {"chat": row["messages"]}, "questions": questions})
    ctx["model_name"] = response.get("model")
    return d1_binary(response) if mode == "binary" else d1_severity(response, ctx["rubric"])


# --- chat models: five-token answer ---
def user_prompt(messages):
    lines = [f"{m['speaker']}: {' '.join(m['text'].split())}" for m in messages[-MAX_MESSAGES:]]
    return "Chat window:\n" + "\n".join(lines) + "\n\nAnswer (0, 1, 2, 3 or U):"


def render_prompt(ctx, messages):
    system = ctx["instruction"]
    user = user_prompt(messages)
    if ctx["template"] == "chatml":
        return (f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n"
                f"<|im_start|>assistant\n")
    body = {"messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "add_generation_prompt": True, "chat_template_kwargs": ctx["chat_kwargs"]}
    return request(ctx["port"], "/apply-template", body)["prompt"]


def five_way(response):
    """Probability of each answer token from the first generated token's candidates.
    Returns (probs over the five renormalised, answer_mass = un-normalised total)."""
    entry = response["completion_probabilities"][0]
    candidates = entry.get("top_logprobs") or entry.get("top_probs") or entry.get("probs") or []
    mass = dict.fromkeys(ANSWERS, 0.0)
    for c in candidates:
        text = c.get("token", c.get("tok_str", "")).strip()
        if text in mass:
            mass[text] += math.exp(c["logprob"]) if "logprob" in c else c["prob"]
    total = sum(mass.values())
    if total == 0:
        return dict.fromkeys(ANSWERS, 0.0), 0.0
    return {k: v / total for k, v in mass.items()}, total


def _from_probs(p, mass):
    p_alert = p["2"] + p["3"]
    levels = p["0"] + p["1"] + p["2"] + p["3"]
    answer = max(ANSWERS, key=lambda k: p[k]) if mass > 0 else None
    report = None
    if answer not in (None, "U") and levels > 0:
        report = 100 * (p["1"] + 2 * p["2"] + 3 * p["3"]) / (3 * levels)
    return {"alert_score": p_alert, "flagged": p_alert >= 0.5, "answer": answer,
            "report_score": report, "probs": p, "answer_mass": mass}


def tokenize_prompt(ctx, messages):
    """Render the prompt and tokenize it ourselves (no extra BOS). Returns (prompt, tokenize response)."""
    prompt = render_prompt(ctx, messages)
    tok = request(ctx["port"], "/tokenize", {"content": prompt, "add_special": False,
                                             "parse_special": True, "with_pieces": True})
    return prompt, tok


def score_chat(row, ctx):
    _, tok = tokenize_prompt(ctx, row["messages"])
    ids = [t["id"] if isinstance(t, dict) else t for t in tok["tokens"]]
    response = request(ctx["port"], "/completion", {
        "prompt": ids, "n_predict": 1, "temperature": 0, "n_probs": 100, "cache_prompt": False})
    p, mass = five_way(response)
    out = _from_probs(p, mass)
    out["generated"] = response.get("content")
    return out


# --- labels: an outside labeller's hard answers ---
def score_labels(row, ctx):
    answer = ctx["labels"].get(row["id"])
    if answer is None:
        raise ValueError(f"No label for {row['id']}")
    answer = str(answer).strip().upper()
    if answer in ("", "NONE", "NULL"):
        answer = "U"
    if answer not in ANSWERS:
        raise ValueError(f"Label for {row['id']} is not one of {ANSWERS}: {answer!r}")
    p = {k: 1.0 if k == answer else 0.0 for k in ANSWERS}
    return _from_probs(p, 1.0)
