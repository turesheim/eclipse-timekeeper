# Working on Dagsverk with Codex

`AGENTS.md` holds the durable project agreements. Project-specific workflows
live under `.agents/skills/dagsverk-*/SKILL.md`. Skills are normally discoverable
automatically, and can be invoked explicitly as `$dagsverk-change`,
`$dagsverk-backend`, `$dagsverk-web`, `$dagsverk-eclipse` and `$dagsverk-review`.
The pre-existing Bootstrap 6 skills remain intact but are disabled by name in
the project's `.codex/config.toml`, since this application pins Bootstrap 5.3.8.
Re-enable the relevant skill only for an intentionally scoped migration.

## Describe the result

For an implementation task, state the goal and observable expectations. Include
constraints that matter. The agent should infer routine details from current
code and documentation, and ask only about material missing decisions.

```text
Use $dagsverk-change to add client filtering to Workweek.
Expected: selecting a client shows only its sessions; clearing the filter
restores all clients. Totals match the visible sessions. Empty results remain
usable on narrow screens, in English and Norwegian.
Scope: preserve API behavior and existing saved records.
Verify the filtering and totals, including changing and clearing the selection.
```

This is an example task, not a declaration that the feature is missing. For a
defect, provide reproduction steps and expected versus actual behavior when
available. Small tasks need no separate planning document.

## Shared quality command

Run from the repository root using the [documented prerequisites](REPOSITORY.md):

```sh
python3 scripts/quality.py --affected --plan
python3 scripts/quality.py --quick
python3 scripts/quality.py --affected
python3 scripts/quality.py --full
```

The default is `--affected`. Selection includes staged, unstaged and untracked
non-ignored files. Use `--base <revision>` to include committed changes since a
known base; in a feature branch, use its merge base with the target branch.
Selection uses the same conservative product routing as `scripts/ci_changes.py`.
Use `--paths <file> ...` to scope local verification to the task when unrelated
pre-existing work is present. Hooks include their session's changed paths in the
suggested command. Receipts still fingerprint the whole checkout and must cover
every command group required by the session's changes.

| Group | Checks |
| --- | --- |
| Quick | Diff whitespace, changed Python/JS/JSON/TOML/XML syntax, skill entry metadata |
| Tooling | Python tests under `scripts/tests` |
| Contract | Python tests under `common/api/tests` |
| Web | Existing Node unit tests and generated-asset checks |
| Server | Independent server Maven reactor, `verify` |
| Eclipse | Root Tycho reactor, `verify` |

`--full` runs all these groups. Runtime/package/browser/installed-client and
deployment acceptance still follow the [component guides](REPOSITORY.md).
For example, a UI behavior change needs relevant Playwright scenarios against
a package built from that source. A CSS-only edit may need visual checks even
when unit tests pass. Scripts never install dependencies, regenerate assets,
deploy or fix source automatically. Build/test commands may write normal output.

Results go to ignored `target/agent-quality/`. A successful receipt records the
HEAD, staged blob identities and hashes/modes of dirty and untracked source files. Any source change
invalidates it; edits during execution cause the run to fail. Receipts describe
executed command groups, not proof that acceptance criteria are met.

## Codex hooks

`.codex/hooks.json` resolves the scripts from the Git root, so starting Codex
in a subdirectory works. Review/trust the project hooks in Codex's hooks UI
(`/hooks` where available), then start a new session. Restart after changing
skill configuration. Repository hooks are subject to the host's managed policy;
adding the files alone does not prove that a running client loaded them.

- `SessionStart` records the initial source state and reports tool versions and
  the number of existing changes. Existing changes alone do not require work.
- `PostToolUse` compares source to that baseline. It runs read-only quick checks
  when source changes, with a five-second debounce and fingerprint deduplication.
  It inspects source rather than parsing shell commands or transcripts.
- `Stop` checks for a successful receipt covering the affected groups and current
  source. Missing/failed verification prompts one automatic continuation per turn.
  It never loops indefinitely or approves/deploys anything. If verification remains
  unavailable, the agent must state the limitation in its final response.

State is per session under ignored `target/agent-hooks/`; deleting it requires
a new session baseline. Hooks never store prompts, transcripts or environment
values. Hook errors are surfaced and require manual quality checks. The hooks
can be disabled through Codex configuration (`[features] hooks = false`). CI
independently runs quick checks and the existing tests and acceptance jobs.

## Evaluate the workflow

`evals/agent/cases.json` contains representative prompts and observable criteria,
including cases where a skill should not run. For a skill/model change, exercise
the relevant cases in disposable checkouts at the same starting revision, with
synthetic data and no release credentials. Use ordinary authorized Codex sessions
or `codex exec --json` to capture the run. Keep traces private and outside source
control; they can include sensitive tool output.

Record revision/model, selected skills, actual test results, acceptance outcomes,
unrelated changes and avoidable retries. Compare outcomes with and without the
change; repeat ambiguous cases. Test skill selection separately from successful
task execution. Human assessment is needed for intent and visual quality.
Add cases for observed failures. The Python hook/quality tests verify deterministic
behavior; they do not substitute for these agent behavior evaluations.
