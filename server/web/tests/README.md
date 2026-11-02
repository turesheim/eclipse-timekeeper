# Browser regression conventions

Run browser suites through `acceptance.py` against a checksum-verified server
ZIP. It creates isolated accounts and bounded batches. Keep production throttles
and write preconditions enabled; never use a retry to conceal a failed mutation.

- Use observable readiness and outcome assertions. A visible editor is not proof
  that text was entered: assert the draft before testing its discard behavior.
- Use `appendEditorText` when the test needs a paragraph-end insertion. It selects
  that location explicitly, sends real input, and verifies the result. Test
  platform keyboard shortcuts separately rather than relying on native End keys.
- Use `nativeConfirmations.run` for actions that require discard prompts. Supply
  every expected accept/cancel decision. Missing, extra and wrong-type prompts
  fail the action, and handlers are removed in `finally` with `dispose`.
- Functional tests should verify the result they own: saved records, visible state,
  retained timestamps, and rejected writes. Do not use hover tooltips or exact
  rendering/timer phases as a proxy for a successful edit. Keep tooltip delay and
  presentation assertions in the dedicated tooltip suite.
- Prefer named `test.step` blocks to numbered console messages. A failure should
  identify the user action and expected outcome without exposing private content.
- Use `tooltipFor` to wait for the tooltip associated with the target control or
  its owning composite card. Finding any visible tooltip can accept stale UI.
- Retain exact-byte, ownership, draft-retention and stale-write assertions when
  refactoring helpers. Do not replace them with screenshots or broad visibility
  checks. Fixed waits are appropriate only when elapsed time itself is tested
  (for example, ensuring a tooltip does not appear immediately).
- Keep test-created data independent. Use private accounts for stateful journal
  scenarios; do not rely on another test's selected date, drafts or credentials.

On failure, inspect the current commit and distribution receipt, the failed
step, server logs and screenshot. Traces are disabled because they can retain
credentials. Report unavailable browsers as unverified rather than passing.
