# Learning and memory

[← Back to the README](../README.md)


## Learning

SoPhi observes its own sessions and gets better at your project over time.
All three surfaces (CLI, web, SDK) wire this in automatically; everything
lives in plain JSONL files under `~/.sophi/learning/`, and learning is
strictly best-effort — it can never fail or slow down a turn.

The loop, end to end:

1. **Capture** — tool calls and session outcomes are recorded via lifecycle
   hooks; tools with a bad recent track record earn a reliability warning in
   the system prompt.
2. **Distill** — one LLM self-evaluation at session end judges the outcome
   (success / partial / failure) and distills deduplicated, reusable
   *lessons* that are recalled into future sessions' system prompts.
3. **Align** — rate replies with `/good` and `/bad [reason]` (or the web
   feedback endpoint); the evaluator also detects implicit signals like
   corrections, each backed by a verbatim quote. Preferences steer future
   behavior through the same lesson pipeline.
4. **Export** — `sophi export` turns the labeled history into fine-tuning
   datasets: chat-format `sft.jsonl` from success-judged sessions, TRL
   `dpo.jsonl` pairs from bad→good retries, plus a provenance
   `manifest.json`. Redaction of secrets/PII is on by default; output is
   deterministic and deduplicated.

Everything the agent has learned is inspectable and revocable:

```bash
sophi lessons list [--all] [--scope <dir>]   # what it believes about this project
sophi lessons archive <id>                   # retract a lesson (it may be learned again)
sophi lessons forget <id>                    # retract it for good: it is never learned again
sophi feedback list [--scope <dir>]          # your ratings + detected signals
sophi feedback delete <id>                   # revoke feedback
sophi export --out ./dataset   # build SFT/DPO training files
```

Training itself is out of scope by design — the datasets are the durable
asset, and they work with TRL, axolotl, unsloth, or llama-factory as-is.

---

## Memory (Jane's Theory)

Where learning remembers *how to behave*, memory remembers *your world*:
who's in it, what happened, and how it connects — across sessions. It's
organized as a memory palace: the leverage is at **encoding time**, not
retrieval time. Opt-in and experimental:

```bash
# Local server with a nomic-embed model loaded: picked up automatically
sophi --memory

# Or name the embeddings model — any OpenAI-compatible embeddings endpoint works
sophi --memory --embedding-model nomic-embed-text --embedding-dimensions 768
```

How it works, per turn:

1. **Encode (async, after the turn)** — one cheap LLM verdict decides what
   deserves remembering, how strongly (emotional weight is the largest
   factor), in which of five rooms (entities, tasks, episodes, knowledge,
   narrative), and connected to what causal story. Most turns store nothing.
2. **Recall (before the turn, no LLM call)** — the query is embedded, routed
   to the most relevant rooms, scored by semantic match × decayed importance
   × your profile, then expanded along causal threads — the agent gets the
   story, not just the fact. Stale or third-party memories are injected with
   a `VERIFY` marker so the agent checks instead of asserting.
3. **Forget (a feature, not a failure)** — each room has its own decay
   half-life (episodes fade in days, life narratives last a year), and a
   consolidation "sleep" cycle merges duplicates and prunes what faded.

Sensitive topics (health, finances, relationships) are tier-guarded: never
volunteered, only surfaced when you raise the topic, and every access is
audited. Storage is an embedded ArcadeDB database (document+graph+vector)
under `~/.sophi/memory/` (user-global — your life isn't per-project), and
like learning it is strictly best-effort: memory can never fail or slow down
a turn.

Everything remembered is inspectable and truly deletable:

```bash
sophi memory list [--room tasks]    # browse what it remembers, by room
sophi memory show <id>              # one memory in full
sophi memory threads                # causal story lines
sophi memory profile                # what it believes about you (confirm/correct/delete)
sophi memory why                    # why did it bring that up?
sophi memory forget <id>            # hard delete — shows impact first, provably gone
sophi memory consolidate            # run the sleep cycle now
sophi memory reset                  # wipe everything
```

"Provably gone" is literal: deletion is a compacting rewrite of every store
file, the causal chain is re-linked around the gap, and the regression suite
greps every file on disk to assert zero traces.

