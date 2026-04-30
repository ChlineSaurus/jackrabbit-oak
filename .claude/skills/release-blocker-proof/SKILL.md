---
name: release-blocker-proof
description: Turn high-risk commits from a release-risk assessment into branch-isolated test proofs. For every candidate above a configurable threshold M, dispatches one subagent (in a worktree) to either (a) write a test that fails on the end-of-range source AND would have passed before the change, or (b) — only if a test is genuinely impossible — produce an extra-strong written analysis. Use when the user asks to "prove the blockers", "test the high-risk findings", "validate the regressions on a branch", etc. Independent of any specific risk-assessment skill — accepts a results path or a list of commits.
---

# release-blocker-proof

## When to use

The user has a list of high-risk commits (typically the output of
`release-risk-assessment` but could be any source) and wants to *prove* each
candidate is a real release-blocking regression. Outputs are branches +
failing tests, plus a per-issue analysis.

Typical triggers:
- "prove the blockers"
- "test the high-risk findings on a branch"
- "validate the regressions"
- "give me a branch and a test for each item above risk N"

## Required arguments — ASK if not provided

- One of:
  - `assessment_path`: path to a directory of per-commit JSON files OR a
    single aggregated JSON array, OR
  - `candidates`: explicit list of `{commit_hash, issue_id?, summary?}`
    objects.
- `repo_path`: path to the git checkout. ASK if not provided.
- `start_ref`: the start tag/commit of the original release range — needed
  for the "would have passed before" test verification. ASK if not provided.
- `end_ref`: the end tag/commit (current end-of-range or a release tag).
  Defaults to `HEAD`.

## Optional arguments

- `threshold` (alias `M`): minimum `risk.score` to include from
  `assessment_path`. Default `7`. Only relevant when reading an assessment
  directory.
- `gh_repo`: e.g. `apache/jackrabbit-oak`. Best-effort PR lookups.
- `branch_root`: container folder name. Default
  `release-analysis-<YYYY-MM-DD>` to avoid clashes across runs. Per-issue
  branches are created under this name (see naming below).
- `max_parallel`: how many issue-subagents to run concurrently. Default `4`.
  Each takes a worktree, so disk and the harness can throttle.

## Branch naming convention

- Container: `release-analysis-<YYYY-MM-DD>` (e.g.
  `release-analysis-2026-04-29`). The container is **not itself a branch** —
  it is a folder name embedded in each branch name to keep them grouped.
- Per-issue branch:
  `release-analysis-<YYYY-MM-DD>/<issue-id>-<short-slug>`
  - `<issue-id>`: prefer the JIRA / GitHub-issue key from the commit
    subject (e.g. `OAK-12128`); fall back to the short hash.
  - `<short-slug>`: kebab-case 3-5-word summary (e.g.
    `orphan-commit-on-lease-loss`).
- A branch is created **only when the issue is `proven`**. For
  `not-reproducible`, `refuted`, or `unprovable-but-explained` outcomes
  the agent does not create a branch — the analysis goes into the
  top-level summary instead (see Output).
- Each proven-issue branch is created from `<end_ref>` (so the failing
  test observes the end-of-range behavior).

## Output

For each `proven` issue:

- A branch `release-analysis-<date>/<issue-id>-<short-slug>` containing:
  - One or more proof tests in the affected module's test sources.
    Multiple tests are encouraged when the issue has distinct facets
    (e.g., one test for the failure mode itself, one for a follow-on
    consequence, one sanity test that anchors the negative space).
  - `release-analysis-<date>/<issue-id>-<short-slug>/ANALYSIS.md` —
    structure below.

For other outcomes (`not-reproducible`, `refuted`, `unprovable-but-explained`)
no branch is created. The analysis is captured in the top-level summary.

### Top-level summary

`release-analysis-<date>/SUMMARY.md` is the structured artifact for the
agent: a table of every candidate with status and one-line rationale, plus
a short paragraph per non-proven outcome (the bulk of the discussion lives
on each proven branch's `ANALYSIS.md`). Per-issue statuses are:

- `proven` — test fails on `end_ref`, passes on the parent of the
  offending commit (verified). Branch + ANALYSIS.md exist.
- `not-reproducible` — fixed by a later commit in the range; the
  candidate is no longer a release risk.
- `unprovable-but-explained` — rare; cannot be expressed as a unit test
  but the assessor is highly confident the issue is real. Flagged for
  human review.
- `refuted` — investigation found the original concern was a misread or
  the team explicitly accepted the trade-off in PR review.

### User-facing final message

After all subagents finish, output a brief human-readable summary:

- One short paragraph stating how many candidates were considered, how
  many came back `proven`, and the headline of each proven issue.
- A compact table with one row per candidate (status, issue, branch
  if any).
- Pointer to `release-analysis-<date>/SUMMARY.md` and to each proven
  branch for the detail.

Keep this final message tight. The reader should be able to glance at
it, see the proven count, and decide whether to dig in. The detailed
write-ups live in `SUMMARY.md` and the per-branch `ANALYSIS.md` files.

## Process

1. Validate inputs. ASK for missing required arguments. Pick `branch_root`
   automatically; if it already exists in the repo, append `-2`, `-3`, ...
2. Build the candidate list:
   - From `assessment_path`: filter by `risk.score >= threshold`.
   - From `candidates`: use as-is.
3. Apply the **dormant-fix lens**: a change whose wiring leaves runtime
   behavior identical to the pre-change baseline is not a regression.
   Drop or downgrade these before dispatch.
4. For each remaining candidate, dispatch a subagent **with
   `isolation: worktree`** so concurrent agents don't trample. Subagent
   prompt is below.
5. **Clean up worktrees**: after all subagents finish, each worktree created
   by `isolation: worktree` still holds a lock on its branch, making that
   branch inaccessible from the main repo. For every worktree path returned
   by the harness, run:
   ```bash
   git worktree unlock <worktree-path>
   git worktree remove <worktree-path>
   ```
   If a path was already removed (e.g., subagent cleaned up after itself),
   the command will fail harmlessly — continue. After this step all proven
   branches are accessible normally via `git branch` and `git checkout`.
6. Aggregate: write `release-analysis-<date>/SUMMARY.md` and emit the
   brief human-readable final message described in Output.

## Subagent prompt template

```
You are proving (or refuting) a release-blocker candidate from a risk
assessment of <repo_path>. Work in your isolated worktree; do not assume
the parent repo's state.

Candidate:
- commit_hash: <hash>
- issue_id:    <jira-or-hash>
- start_ref:   <start_ref>
- end_ref:     <end_ref>
- summary:     <one-line from the assessment, optional>
- gh_repo:     <gh_repo> (may fail; best-effort)
- branch:      release-analysis-<YYYY-MM-DD>/<issue-id>-<short-slug>

Steps:

1. Read the commit and its PR (if any).
   - `git -C <worktree> show --stat <hash>`; `git -C <worktree> show <hash>`
   - If subject has "#NNNN": `gh pr view NNNN --repo <gh_repo>
     --json title,body,comments`. Read review comments — they often explain
     why the team accepted the trade-off (this is decisive evidence).
   - If `gh` fails (corporate auth, wrong host), fall back to unauthenticated
     curl. Note that `gh pr view` omits inline review comments; fetch those
     separately regardless:
       `curl -s "https://api.github.com/repos/<gh_repo>/pulls/<NNNN>/reviews"`
       `curl -s "https://api.github.com/repos/<gh_repo>/pulls/<NNNN>/comments"`

2. Look ahead to <end_ref>:
   - `git -C <worktree> log <hash>..<end_ref> --oneline` filtered by JIRA key
     and by changed files. Find any later patch that addresses the concern.
   - If the issue is fully resolved by <end_ref>, STOP and produce a
     "not-reproducible" analysis.

3. Apply the dormant-fix lens: distinguish a real user-visible regression
   from a change whose wiring leaves runtime behavior identical to
   <start_ref>. If the latter, stop and produce a "refuted" analysis.
   Likewise, if PR review comments show that reviewers explicitly
   accepted the trade-off, mark "refuted" with the citation.

4. If the concern is real and live at <end_ref>:
   a. Create the branch from <end_ref>:
      `git checkout <end_ref> -b release-analysis-<date>/<issue-id>-<short-slug>`
   b. Write one or more focused tests in the affected module's test
      sources. Each test MUST encode a SPECIFIC user-visible regression —
      not minor interface changes that won't actually block a release.
      Multiple tests are encouraged when the issue has distinct facets:
      e.g. one test asserting the failure mode itself, another asserting
      a follow-on consequence (such as a downstream cleanup path being
      blocked), and a sanity test anchoring the negative space (the same
      code path under healthy inputs). One scenario per test method
      keeps the proof readable.
   c. Verify "currently failing" for each test: run against <end_ref>.
      Expect failure on the regression assertions. If a test passes, it
      is not proving a regression — iterate or drop it.
   d. Verify "would have passed before" — two-path procedure:
      **Primary path**: stash the tests, check out `<offending_commit>^`,
      unstash and run. Expect the tests to pass. Restore the branch state.
      **Fallback path** (use when the primary fails to compile for reasons
      unrelated to the bug — e.g., surrounding API shapes changed between
      `<start_ref>` and `<offending_commit>^`): check out `<start_ref>`
      instead, apply the tests, run, expect them to pass. The fallback is a
      first-class verification; it confirms the same thing — the behavior
      was correct before the offending change landed — just at a slightly
      earlier anchor. In ANALYSIS.md, record which path was used and why.
   e. If both verifications cannot be performed (e.g., the build is
      broken in a way unrelated to the issue), document precisely which
      step could not be run and why, and what manual evidence supports
      the claim.

5. Write `release-analysis-<date>/<issue-id>-<short-slug>/ANALYSIS.md`
   only when status is `proven`. Sections, in order:
   - **Title**: `<issue-id>: <one-line problem summary>`
   - **Risk**: score and one-sentence rationale (from input).
   - **State at <start_ref>**: 1-3 sentences describing the prior behavior.
   - **State at <end_ref>**: 1-3 sentences describing current behavior.
   - **Problem / impact**: 2-4 sentences. A concrete user-visible scenario.
     Cite file:line. No abstract handwaving.
   - **Test(s)**: brief — file paths, what each test asserts, how to run,
     and which steps verified (a) currently-failing / (b) would-have-passed.
     Keep this section short; the test source is the authoritative
     description.
   - **Fix sketch**: 2-4 sentences describing what would need to change.
     Reference the relevant lines.
   - **Status**: `proven`.

   For `not-reproducible`, `refuted`, or `unprovable-but-explained`:
   no per-issue file. The agent records the rationale (1-3 sentences,
   citing fix-up commit hash / PR-review comment / file:line evidence
   as appropriate) and returns it for inclusion in the top-level
   `SUMMARY.md`. No branch is created.

6. Commit (proven only): stage the test file(s) and ANALYSIS.md (NOT the
   parent repo's risk-assessment scratch). Commit message:
     `<issue-id>: proof test — <one-line>`

7. Return: a single short status line plus, for non-proven outcomes, the
   1-3-sentence rationale to fold into SUMMARY.md. Do NOT paste analysis
   prose or test source in chat.

Test quality requirements:
- The test must encode a release-blocking issue. Cosmetic / SPI-shape /
  log-message-only differences do NOT qualify. If the only failure mode is
  a test-message change, the issue is not a blocker — produce a `refuted`.
- The test must be deterministic (no sleep-based timing assertions).
- The test must compile against the module's existing dependencies. If the
  module has a broken transitive dep at <end_ref>, document the workaround
  used to get the test to compile (e.g., manual `mvn install` of a dep, or
  basing the test on the smallest viable parent module). The fact that the
  module is broken is NOT itself a blocker proof.
- Prefer Mockito to integration setup where the failing condition can be
  observed at a class boundary. Faster verification → faster review.

Fallback (very rare): if a real blocker truly cannot be expressed as a
unit test (e.g., requires a multi-node cluster), write ANALYSIS.md with
extra-strong evidence: file:line citations, exact thread interleavings,
references to JIRA / PR comments that confirm the failure mode, and a
concrete reproduction recipe even if it requires manual steps. Mark
status `unprovable-but-explained`.

Do NOT modify production source. Tests only.
```

## Aggregation

After all subagents finish, build `release-analysis-<date>/SUMMARY.md`.
This is the structured artifact for follow-up review. Sections:

```
# Release-blocker proof summary — <date>

| Status | Issue | Branch | One-line |
| ------ | ----- | ------ | -------- |
| proven | <issue-id> | release-analysis-<date>/<issue-id>-<slug> | ... |
| not-reproducible | <issue-id> | — | fixed by <hash> |
| refuted | <issue-id> | — | dormant-fix lens: pre- and post- equivalent |
| unprovable-but-explained | <issue-id> | — | <one-line> (see notes below) |

## Non-proven candidates
For each non-proven candidate, 1-3 sentences with the evidence
(fix-up commit hash, PR-review citation, file:line referenced
behavior).

## Recommendations
For each proven issue: one short recommendation (revert / patch /
release-note / no action).
```

After writing `SUMMARY.md`, emit the brief user-facing final message
described in Output: a tight paragraph + compact table, with pointers to
`SUMMARY.md` and to each proven branch. Avoid pasting full analysis or
test source in chat.

## Anti-patterns (learned)

- **Don't** ship a test that fails because of a build/compile issue
  unrelated to the bug. Verify the test fails on the *runtime assertion*,
  not on a missing class.
- **Don't** prove issues that are merely SPI-shape changes with no
  in-repo or known external consumers. Apply the dormant-subclass lens.
- **Don't** prove issues that the team explicitly accepted in PR
  discussion. Read review comments; if reviewers signed off on the
  trade-off, the item is by definition not a blocker. Mark `refuted` and
  cite the discussion.
- **Don't** stack assertions in one test method. One scenario per test —
  it's clearer to the reviewer which line proves what.
- **Don't** delete or modify the source files of the offending commit.
  This skill produces TESTS, not fixes.
- **Don't** skip the "would have passed before" verification just because
  it's awkward. Without it, the test could be testing a pre-existing bug
  rather than a regression introduced by the commit. If the verification
  is genuinely impossible, document why, in detail.
- **Don't** create one giant branch for all proofs. One branch per issue
  so reviewers can land them independently.
- **Don't** commit the parent repo's scratch files (`.risk_assessment/`,
  prior runs' artifacts). Stage only the test + ANALYSIS.md.
