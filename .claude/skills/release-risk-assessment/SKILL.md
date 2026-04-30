---
name: release-risk-assessment
description: Score every commit in a release range for review-prioritization. Walks <start>..<end> in a git repo, dispatches subagents in batches of N, and emits a per-commit JSON plus an aggregated summary. Use when the user asks to "assess a release", "score commits between X and Y", "find risky commits in the upcoming release", or similar. Requires a git repo and `gh` CLI for PR context (best-effort).
---

# release-risk-assessment

## When to use

The user wants a structured risk score for every commit in a release range so
they can decide which commits warrant detailed manual review. The output is
JSON-per-commit plus an aggregated index, NOT a fix or a test — a separate
skill (`release-blocker-proof`) handles proofs for the high-risk subset.

Typical triggers:
- "make a risk assessment of the next release"
- "score the commits between 1.90 and 2.0"
- "which commits in this range need close review?"

## Required arguments — ASK if not provided

The user must specify:

- `start`: commit hash or tag at the **start** of the range (exclusive — git's
  `start..end` semantics).
- `end`: commit hash or tag at the **end** of the range (inclusive).
- `repo`: optional — path to the git checkout. Defaults to the current working
  directory.

If `start` or `end` is missing, **ask the user**. Do not guess. Multiple tags
may need to be passed if the release spans intermediate tags; use the earliest
tag as `start` and the final tag as `end`.

## Optional arguments

- `gh_repo`: e.g. `apache/jackrabbit-oak`. Enables `gh pr view <num>` for PR
  context. Best-effort — if `gh` fails (auth, network, wrong host), the skill
  continues using only commit data.
- `batch_size` (alias `N`): commits per subagent. Default `3`. Keep ≤ `10`
  so each agent has clean context per commit. Larger batches risk shallow
  analysis.
- `output_dir`: default `.risk_assessment/`. Per-commit JSONs go to
  `<output_dir>/results/`; aggregated outputs at the top of `<output_dir>`.
- `skip_commits`: list of commit hashes (or substrings of commit subjects) to
  skip — typically ones already triaged in a prior run.
- `parallel_waves`: agents per parallel wave. Default `10`. Higher = faster but
  may hit harness limits.

## Output

For each commit in scope:
`<output_dir>/results/<full_hash>.json` matching the schema below.

Aggregated:
- `<output_dir>/risk_assessment.json` — array of all commit JSONs, sorted by
  `risk.score` descending.
- `<output_dir>/INDEX.md` — human-readable index calling out every commit at
  risk ≥ 6, with one-line rationales and links to the per-commit JSON.

## JSON schema (per commit)

```json
{
  "commit_hash": "string (40-char SHA)",
  "commit_message": "string (full subject line)",
  "size": "int (lines added + deleted, from `git show --stat`)",
  "core_logic": "bool (touches Oak's core runtime: commit/query/storage/security paths)",
  "feature_toggle": "bool (introduces Feature/FeatureToggle for safe rollback)",
  "tests": {
    "score": "int 1-10 — how well included tests cover the prod changes",
    "reason": "string — 1-2 sentences"
  },
  "risk": {
    "score": "int 0-10 per the rubric below",
    "reason": "string — 1-5 sentences. Cover the rationale for the score. Include lookahead notes when a later commit in the range affects this one (revert, follow-up fix, etc.) — the score should reflect the end-of-range state. Include a brief PR-context note when a PR was found and adds material context. Skip whichever of these doesn't apply rather than padding."
  },
  "special_logic": {
    "concurrency": {
      "correct": "bool — true if no concurrency change applicable; otherwise asses correctness",
      "reason": "string — short rationale or 'N/A'"
    },
    "edge_cases": {
      "correct": "bool — true if N/A; otherwise assess whether obvious edge cases are handled",
      "reason": "string — short rationale or 'N/A'"
    }
  }
}
```

## Risk scoring rubric

Score the commit considering its end-of-range effect, not the commit in
isolation. The bands describe outcomes — the *probability* of the listed
outcome should drive the score, not the presence of any single warning sign.
Concrete evidence of risk (a specific concurrency hazard, a specific
contract change, an explicit critical-path edit) is what pushes a score up
into the upper bands. Hot-path code without ironclad tests is suspect, but
if the change reads as obviously correct it stays in the middle bands.

| Range | Meaning |
| ----- | ------- |
| 9–10  | Highly probable release breaker. A clearly identified failure mode that ships uncontained: data corruption, deadlock, crash on common input, broken contract on a critical path. The reviewer should expect a real problem and look for it specifically. |
| 7–8   | Likely release breaker. Concrete suspicion of a problem on an integral path — a plausible failure mode the assessor can articulate (e.g., "this concurrency rewrite swaps Monitor for Lock and the wait-loop predicate is no longer re-checked under the lock") with weak or absent test evidence to refute it. |
| 5–6   | Other regression, not blocking a full release. A real behavioral change with limited blast radius or a safe fallback; a moderate perf regression on a non-hot path (GC, background indexing, startup, admin tooling) — noticeable to operators but not breaking user flows; SPI shape change with limited known consumers. Magnitude matters: a large enough perf regression on a non-hot path (e.g., GC time doubles, startup grows from seconds to minutes, background indexing falls behind faster than it can catch up) escalates into the upper bands because the cumulative effect *does* become a release breaker even if no single request is slow. Move the score up accordingly. |
| 3–4   | Other issues — frequently latent or pre-existing problems being surfaced, refactors with no observable functional change, non-trivial dependency bumps. Worth a glance, no immediate concern. |
| 0–2   | Trivial: typos, doc updates, dependency patch bumps, test-only commits, automated maven-release-plugin commits, merge commits. |

A useful self-check: if the only thing the assessor can write in
`risk.reason` is "no tests" or "touches concurrency", the score should
probably be lower. Upper-band scores need a concrete failure-mode
description.

The rubric is a guide, not a checklist. Use common sense and judgment.
A change that doesn't fit any cell cleanly should be scored where the
assessor honestly thinks the risk lives, and the rationale should
explain that judgment. The bands exist to keep scores comparable across
commits, not to constrain reasoning.

## Modifiers — qualitative guidance, not formulas

These are factors that nudge the score, not fixed deltas. Apply judgment.

- **Inadequate tests for a non-trivial change**: nudge up if the assessor
  cannot rule out a plausible failure mode because the tests don't cover
  it. Do *not* nudge up purely because the test score is low — many
  commits are correctly low-test (refactors, SPI cleanups).
- **Self-merged or zero human review**: nudge up for non-trivial changes on
  a critical path. Subtle behavioural regressions that a focused reviewer
  would catch are exactly what bots and CI do not catch.
- **Reverted within the range**: nudge down significantly. If the revert
  is clean, the net behavior at the end of the range is unchanged.
  Document the revert in `risk.reason` rather than rolling it into a
  silent score adjustment.
- **Fixed by a later commit in the range**: nudge down. Note the fix-up
  commit in `risk.reason` and describe the residual risk (often `0–2`).
  What counts is the risk at the point the release ships — i.e. the risk
  if the cut at `<end>` went out as-is. A bug introduced and then properly
  fixed within the range is not a release risk, even if the introducing
  commit looked alarming in isolation.
- **Apply the dormant-fix lens**: if a change *attempts* to alter behavior
  but the wiring leaves the runtime behaviour identical to the pre-change
  baseline, it is not a regression — pre- and post-change states are
  equivalent. Score it as a curiosity (`0–2`) rather than as a behavioral
  change.

Do *not* penalize a commit purely for missing PR / JIRA / review trail.
Many projects do not consistently use those tools. If a PR is available
and adds material context, summarise it in `risk.reason`; if it is
absent, just proceed with what the diff shows.

## Process

1. Validate inputs. ASK for any missing required argument.
2. Generate the commit list:
   ```bash
   git -C <repo> log --pretty=format:"%H|%s" <start>..<end>
   ```
3. Filter by `skip_commits`.
4. Split into batches of `batch_size`.
5. Dispatch subagents in parallel waves (default 10 per wave). Each subagent
   handles one batch. After each wave, verify each expected JSON file exists;
   if a subagent reported analysis but didn't write a file, write the file
   yourself from the analysis.
6. Aggregate.

## Subagent prompt template

Each subagent receives a self-contained prompt. Use this template (substitute
the placeholders):

```
You are doing a risk assessment of commits in the git repository at <repo>.

Assess these <N> commits:
- <hash1> | <subject1>
- <hash2> | <subject2>
- ...

For each commit:

1. Run `git -C <repo> show --stat <hash>` for line counts and file list.
2. Run `git -C <repo> show <hash>` for the full diff. Truncate output if huge.
3. If the subject contains "#NNNN", run
   `gh pr view NNNN --repo <gh_repo> --json title,body,comments` to read the
   PR description and review comments. If `gh` fails (corporate auth, wrong
   host), fall back to unauthenticated curl:
     `curl -s "https://api.github.com/repos/<gh_repo>/pulls/<NNNN>"`
     `curl -s "https://api.github.com/repos/<gh_repo>/issues/<NNNN>/comments"`
4. Look ahead to the end of the range:
   - If the subject contains a JIRA key, run
     `git -C <repo> log --grep="<JIRA>" <hash>..<end> --oneline`
     to find later commits referencing the same ticket.
   - Run `git -C <repo> log --oneline <hash>..<end> -- <files-changed-by-this-commit>`
     to find later commits touching the same files.
   - Note any clean revert of THIS commit within the range.
   - Determine the end-of-range state: was the concern addressed?
5. Score the commit using the rubric. Apply modifiers. The risk score must
   reflect the end-of-range state.
6. Save JSON to <output_dir>/results/<commit_hash>.json with the schema below.
7. Be concise: 1-2 sentences per `reason` field, 1-3 for risk.reason. Include
   lookahead notes in risk.reason. Include a one-clause PR summary in
   risk.reason if a PR was found.

JSON schema:
{<schema>}

Risk rubric:
{<rubric>}

Modifiers:
{<modifiers>}

Working dir: <repo>. Output: <output_dir>/results/<commit_hash>.json.
Do NOT run tests or modify code. Research + write JSON only.
After all <N> are written, return one short line per commit confirming the
file path, nothing else.
```

## Aggregation

After all subagents finish:

1. Verify count: `<output_dir>/results/*.json` should match the filtered
   commit list. If anything is missing, dispatch a small follow-up wave for
   the gaps.
2. Build `<output_dir>/risk_assessment.json` — a single JSON array of all
   commit JSONs in the order they appear in `git log` (newest first), or
   sorted by risk.score desc — pick one and document.
3. Build `<output_dir>/INDEX.md` with sections:
   - **Top-priority review items** — every commit at risk ≥ 8.
   - **Worth a look** — every commit at risk 6–7.
   - **Notable but not blocking** — every commit at risk 5.
   - For each entry: hash, subject, one-line risk reason, link to JSON.

## Reporting

Final user-facing message: a short markdown table of the top-priority items
(risk ≥ 7) with risk score, hash, subject, and one-clause rationale. Mention
the path to the full report. Do NOT paste the entire JSON.

## Anti-patterns (learned)

- **Don't** trust subagent text reports in lieu of files. Verify files exist;
  many agents return analysis prose without writing the JSON. After each
  wave, list the output dir and write missing files yourself from the
  returned analysis.
- **Don't** score a dormant fix (one whose wiring leaves runtime behavior
  unchanged) as a regression. Pre- and post-change behavior are equivalent.
- **Don't** include reverts whose target is also in the range as net
  contributors to risk — score them low and explain the revert in
  `risk.reason`.
- **Don't** auto-flag any commit as upper-band purely because it touches
  concurrency, caches, or critical paths, or because it has weak tests.
  Upper-band scores require a concrete failure-mode description.
- **Don't** penalize commits for missing PR / JIRA / review trail. Use what
  is available; ignore what isn't.
- **Don't** suggest fixes or write tests in this skill — that's a separate
  workflow. This skill only categorizes.
