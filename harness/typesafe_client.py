#!/usr/bin/env python3
"""Minimal client for TypeSafe's System One endpoint (Jev), shared by judge.py,
parity-judge.py and ui-drive.py.

    from typesafe_client import ask, noul, choice
    answers = ask(state, {"ok": noul("Is `report.p99_ms` below `goal.p99_ms`?")})
    answers["ok"]["noul"]            # probability of yes

Contract (docs.typesafe.ai/api, read 2026-09-21): POST https://api.typesafe.ai/v1/systemone,
`Authorization: Bearer <key>`, body {state, model, questions}; the reply has one entry in
`answers` per question id: choice -> {choice, confidence, probabilities}, noul -> {noul}.
Jev is text-only: state must be a string / JSON object / array of text, never an image.

Key: $TYPESAFE_API_KEY, else ~/.config/pzopt/typesafe.key (never in the repo).
Uses the official `typesafe-sdk` (pip, user-level on the desktop since 2026-09-21) when it is
importable, else plain urllib with the same request, so the scripts also run on the laptops.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ENDPOINT = "https://api.typesafe.ai/v1/systemone"
MODEL = os.environ.get("TYPESAFE_MODEL", "jev-latest")
KEY_FILE = Path.home() / ".config" / "pzopt" / "typesafe.key"


def api_key():
    k = os.environ.get("TYPESAFE_API_KEY")
    if not k and KEY_FILE.exists():
        k = KEY_FILE.read_text().strip()
    if not k:
        sys.exit(f"no TypeSafe key: set TYPESAFE_API_KEY or write it to {KEY_FILE}")
    return k


def noul(instructions):
    """Yes/no judgment; the answer is the probability of yes."""
    return {"type": "noul", "instructions": instructions}


def choice(instructions, criteria):
    """One option out of `criteria` ({name: description}); answer has probabilities per option."""
    return {"type": "choice", "instructions": instructions, "criteria": criteria}


try:
    import typesafe_sdk as _sdk
except ImportError:  # laptops / Dell / Mac: the urllib path below
    _sdk = None
_client = None


def ask(state, questions, model=None, retries=4, timeout=60, log=None):
    """One request, all questions answered in parallel over the same state.
    Returns the `answers` map (plain dicts, the HTTP shape). Retries 429/529 with backoff."""
    global _client
    if _sdk is not None:
        if _client is None:
            _client = _sdk.TypeSafeClient(api_key=api_key(), model=model or MODEL, timeout=timeout,
                                          retry=_sdk.RetryPolicy(max_retries=retries))
        t0 = time.time()
        qs = {k: (_sdk.Noul(instructions=q["instructions"]) if q["type"] == "noul"
                  else _sdk.Choice(instructions=q["instructions"], criteria=q["criteria"])) for k, q in questions.items()}
        reply = _client.system_one(state, qs, model=model).model_dump(mode="json")
        if log is not None:
            log.append({"ms": round((time.time() - t0) * 1000), "model": reply.get("model"), "questions": list(questions), "via": "sdk"})
        return reply["answers"]
    body = json.dumps({"state": state, "model": model or MODEL, "questions": questions}).encode()
    req = urllib.request.Request(ENDPOINT, data=body, method="POST", headers={
        "Authorization": f"Bearer {api_key()}", "Content-Type": "application/json"})
    delay = 1.0
    for attempt in range(retries + 1):
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                reply = json.loads(r.read())
            if log is not None:
                log.append({"ms": round((time.time() - t0) * 1000), "model": reply.get("model"),
                            "questions": list(questions)})
            return reply["answers"]
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:500]
            if e.code in (429, 529) and attempt < retries:
                time.sleep(delay)
                delay *= 2
                continue
            raise SystemExit(f"TypeSafe HTTP {e.code}: {detail}")


def fmt(answers):
    """One line per answer for logs: id=choice (conf, top probs) or id=noul p(yes)."""
    out = []
    for k, a in answers.items():
        if a["type"] == "noul":
            out.append(f"{k}: yes {a['noul']:.2f}")
        elif a["type"] == "choice":
            probs = ", ".join(f"{o} {p:.2f}" for o, p in sorted(a["probabilities"].items(), key=lambda x: -x[1])[:3])
            out.append(f"{k}: {a['choice']} (conf {a['confidence']:.2f}; {probs})")
        else:
            out.append(f"{k}: {json.dumps(a)}")
    return "\n".join(out)


if __name__ == "__main__":
    # smoke test: python3 harness/typesafe_client.py
    log = []
    ans = ask({"run": {"fps_mean": 389, "p99_ms": 2.6}, "goal": "heavy fog above 350 fps with p99 under 4 ms"},
              {"met": noul("Does `run` satisfy `goal`?"),
               "kind": choice("Which weather does `goal` describe?", {"clear": "no weather effect", "fog": "fog or mist", "storm": "rain, thunder, lightning"})},
              log=log)
    print(fmt(ans))
    print("latency", log[0]["ms"], "ms, model", log[0]["model"])
