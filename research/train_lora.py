"""LoRA fine-tune a chat model to answer one token (0/1/2/3/U) for one chat window.

  python research/train_lora.py --base <dir or HF id> --family qwen3|gemma4 --out research/models/lora/<name>
         [--epochs 3] [--lr 2e-4] [--rank 16] [--batch 8] [--limit N] [--merge]

Uses only train.jsonl (fit) and tune.jsonl (evaluate). Refuses check/sealed paths.
Prompt text is exactly what the llama.cpp harness sends (see render()), tokenized without
extra special tokens; loss is cross-entropy of the last prompt position against the answer token.
"""
import argparse
import json
import random
import sys
import time
from pathlib import Path

RESEARCH = Path(__file__).resolve().parent
sys.path.insert(0, str(RESEARCH))
from igrisk import metrics  # noqa: E402
from igrisk.scorers import ANSWERS, INSTRUCTION, _from_probs, user_prompt  # noqa: E402
from igrisk.data import truth_from_level  # noqa: E402

TRAIN = RESEARCH / "data" / "train.jsonl"
TUNE = RESEARCH / "data" / "tune.jsonl"
TARGETS = ("q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj")


def check_path(path):
    """Raise for anything that is, or sits under, a sealed or check path."""
    p = Path(path)
    for part in (*p.resolve().parts, *p.parts):
        low = part.lower()
        if "sealed" in low or low.startswith("check.jsonl"):
            raise ValueError(f"Refusing to use protected path: {path}")
    return p


def render(family, messages):
    """Prompt string as the harness sends it, ending where the answer token goes.
    qwen3: stage 1 used the server's own template with enable_thinking=False (ChatML plus an
    empty think block). gemma4: stage 1 used the server's own template (<|turn> format).
    No BOS in either string; the harness tokenizes with add_special=False."""
    system = INSTRUCTION.read_bytes().decode("utf-8")
    user = user_prompt(messages)
    if family == "qwen3":
        return (f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n"
                f"<|im_start|>assistant\n<think>\n\n</think>\n\n")
    if family == "gemma4":
        # The Gemma template trims message content, so the instruction's trailing newline is dropped.
        return (f"<|turn>system\n{system.strip()}<turn|>\n<|turn>user\n{user.strip()}<turn|>\n<|turn>model\n")
    raise ValueError(f"Unknown family {family!r}")


def target_of(level):
    return "U" if level is None else str(level)


def load_rows(path, limit=None):
    check_path(path)
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                row = json.loads(line)
                row["truth"] = truth_from_level(row["level"])
                rows.append(row)
    return rows[:limit] if limit else rows


def check_last_position(attention_mask):
    """Every row must end in a real token, otherwise logits[:, -1] is the wrong position."""
    if not bool(attention_mask[:, -1].all()):
        raise AssertionError("Last position is padding for some row; batch must be left-padded")


def collate(id_lists, pad_id):
    """Left-pad. Returns input_ids, attention_mask, position_ids (real tokens count from 0)."""
    import torch
    n = max(len(x) for x in id_lists)
    ids = torch.full((len(id_lists), n), pad_id, dtype=torch.long)
    mask = torch.zeros((len(id_lists), n), dtype=torch.long)
    for i, x in enumerate(id_lists):
        ids[i, n - len(x):] = torch.tensor(x)
        mask[i, n - len(x):] = 1
    check_last_position(mask)
    pos = (mask.cumsum(1) - 1).clamp(min=0)
    return ids, mask, pos


def encode(tok, text):
    return tok(text, add_special_tokens=False)["input_ids"]


def answer_ids(tok, family, messages):
    """Token id of each answer = the one token appended when the answer follows the prompt."""
    prompt = render(family, messages)
    base = encode(tok, prompt)
    out = []
    for a in ANSWERS:
        full = encode(tok, prompt + a)
        assert full[:len(base)] == base and len(full) == len(base) + 1, f"answer {a!r} is not one appended token"
        out.append(full[-1])
    assert len(set(out)) == len(ANSWERS), "answer token ids are not distinct"
    return out


def five_probs(logits, ans_ids):
    import torch
    return torch.softmax(logits[:, ans_ids].float(), dim=-1)


def forward_last(model, batch, device):
    ids, mask, pos = (t.to(device) for t in batch)
    logits = model(input_ids=ids, attention_mask=mask, position_ids=pos, logits_to_keep=1).logits
    return logits[:, -1, :]


def evaluate(model, tok, family, rows, ans_ids, device, batch_size):
    import torch
    model.eval()
    order = sorted(range(len(rows)), key=lambda i: len(rows[i]["_ids"]))
    probs = [None] * len(rows)
    with torch.no_grad(), torch.autocast("cuda", dtype=torch.bfloat16, enabled=device == "cuda"):
        for s in range(0, len(order), batch_size):
            idx = order[s:s + batch_size]
            batch = collate([rows[i]["_ids"] for i in idx], tok.pad_token_id)
            p = five_probs(forward_last(model, batch, device), ans_ids).cpu().tolist()
            for i, pi in zip(idx, p):
                probs[i] = pi
    preds = []
    for r, pi in zip(rows, probs):
        out = _from_probs(dict(zip(ANSWERS, pi)), 1.0)
        preds.append({"id": r["id"], "family": r["family"], "language": r["language"], "truth": r["truth"],
                      "level": r["level"], **{k: out[k] for k in ("alert_score", "flagged", "answer", "report_score")},
                      "probs": out["probs"]})
    stats = {g: metrics.group_stats(preds if g == "all" else [p for p in preds if p["language"] == g])
             for g in metrics.GROUPS}
    return preds, stats, metrics.pass_mark(stats), metrics.info(preds)


def fmt(x):
    return "n/a" if x is None else f"{x:.3f}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--family", required=True, choices=["qwen3", "gemma4"])
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--limit", type=int)
    ap.add_argument("--merge", action="store_true")
    ap.add_argument("--train", type=Path, default=TRAIN)
    ap.add_argument("--tune", type=Path, default=TUNE)
    ap.add_argument("--seed", type=int, default=1234)
    a = ap.parse_args()
    for p in (a.train, a.tune, a.out):
        check_path(p)

    import torch
    from peft import LoraConfig, get_peft_model
    from transformers import AutoModelForCausalLM, AutoTokenizer, get_linear_schedule_with_warmup

    random.seed(a.seed)
    torch.manual_seed(a.seed)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    tok = AutoTokenizer.from_pretrained(a.base)
    if tok.pad_token_id is None:
        tok.pad_token = tok.eos_token
    train, tune = load_rows(a.train, a.limit), load_rows(a.tune, a.limit)
    ans_ids = answer_ids(tok, a.family, train[0]["messages"])
    for r in train + tune:
        r["_ids"] = encode(tok, render(a.family, r["messages"]))
    target_idx = {ans: i for i, ans in enumerate(ANSWERS)}
    print(f"{len(train)} train, {len(tune)} tune, answer ids {dict(zip(ANSWERS, ans_ids))}, "
          f"first ids {train[0]['_ids'][:3]}", flush=True)

    model = AutoModelForCausalLM.from_pretrained(a.base, dtype=torch.bfloat16).to(device)
    # Gemma 4 may carry vision/audio towers with same-named linears: restrict to the text model.
    targets = (r".*language_model.*\.(" + "|".join(TARGETS) + ")") if a.family == "gemma4" else list(TARGETS)
    model = get_peft_model(model, LoraConfig(r=a.rank, lora_alpha=2 * a.rank, lora_dropout=0.05,
                                             target_modules=targets, task_type="CAUSAL_LM"))
    model.print_trainable_parameters()
    opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=a.lr, weight_decay=0.0)
    steps = a.epochs * ((len(train) + a.batch - 1) // a.batch)
    sched = get_linear_schedule_with_warmup(opt, max(1, int(0.05 * steps)), steps)

    a.out.mkdir(parents=True, exist_ok=True)
    log = open(a.out / "metrics.jsonl", "w", encoding="utf-8")
    t0 = time.time()
    for epoch in range(1, a.epochs + 1):
        model.train()
        order = list(range(len(train)))
        random.shuffle(order)
        total = 0.0
        for s in range(0, len(order), a.batch):
            rows = [train[i] for i in order[s:s + a.batch]]
            batch = collate([r["_ids"] for r in rows], tok.pad_token_id)
            # A row may carry `allowed` (several acceptable answers, for binary-labelled chats); else its one level.
            with torch.autocast("cuda", dtype=torch.bfloat16, enabled=device == "cuda"):
                logits = forward_last(model, batch, device)
            ok = torch.zeros_like(logits, dtype=torch.bool)
            for i, r in enumerate(rows):
                ok[i, [ans_ids[target_idx[x]] for x in (r.get("allowed") or [target_of(r["level"])])]] = True
            logp = torch.log_softmax(logits.float(), dim=-1)
            loss = -torch.logsumexp(logp.masked_fill(~ok, float("-inf")), dim=-1).mean()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
            opt.zero_grad(set_to_none=True)
            total += loss.item() * len(rows)
            if (s // a.batch) % 20 == 0:  # progress line: an out-of-memory spill onto system RAM shows as a jump in s/step
                print(f"epoch {epoch} step {s // a.batch}/{len(order) // a.batch} loss {loss.item():.3f} "
                      f"elapsed {time.time() - t0:.0f}s peak {torch.cuda.max_memory_allocated() / 2**30:.1f} GB", flush=True)
        preds, stats, mark, info = evaluate(model, tok, a.family, tune, ans_ids, device, a.batch)
        model.save_pretrained(a.out / f"adapter-epoch{epoch}")
        s_all = stats["all"]
        rec = {"epoch": epoch, "train_loss": total / len(train), "stats": stats, "pass_mark": mark, "info": info}
        log.write(json.dumps(rec, ensure_ascii=False) + "\n")
        log.flush()
        langs = " ".join(f"{g}:{fmt(stats[g]['auroc'])}" for g in metrics.LANGUAGES)
        print(f"epoch {epoch} loss {rec['train_loss']:.4f} risk {s_all['risk']['k']}/{s_all['risk']['n']} "
              f"benign {s_all['benign']['k']}/{s_all['benign']['n']} auroc {fmt(s_all['auroc'])} ({langs}) "
              f"exact {info['exact_match']['k']}/{info['exact_match']['n']} "
              f"U {info['unresolved_answered_U']['k']}/{info['unresolved_answered_U']['n']} "
              f"pass {mark['pass']}", flush=True)
    log.close()
    with open(a.out / "tune-predictions.jsonl", "w", encoding="utf-8") as f:
        for p in preds:
            f.write(json.dumps(p, ensure_ascii=False) + "\n")
    if a.merge:
        merged = model.merge_and_unload()
        merged.save_pretrained(a.out / "merged", safe_serialization=True)
        tok.save_pretrained(a.out / "merged")


if __name__ == "__main__":
    main()
