---
name: dagsverk-review
description: Review a Dagsverk diff or pull request for correctness, acceptance gaps and regressions. Use for review requests and final review of substantial changes; do not turn a review-only request into implementation.
---

# Dagsverk review

Read root `AGENTS.md`, the actual diff, the requested behavior and relevant tests.
Use the root-relative architecture/component guides for affected boundaries.

- Establish the intended scope and inspect the implementation, callers and
  failure paths. Distinguish pre-existing issues from regressions in the diff.
- Check observable behavior against acceptance criteria. Pay attention to
  account isolation, account-local dates, concurrency, local/remote storage,
  shared-source duplication and backwards-compatible technical identifiers
  when those areas are affected.
- Check that verification applies to the current source and exercises the
  changed behavior. Quick checks and test receipts support review; they do not
  prove the user outcome or replace browser/installed-client acceptance.
- Report actionable findings with severity, file/line, trigger, consequence and
  supporting evidence. Separate confirmed defects from open questions. Prefer
  concrete regressions over speculative redesigns or stylistic preferences.
- If no actionable findings are established, say so and identify material
  verification gaps. Do not claim the code is defect-free.
- A review-only request authorizes inspection and appropriate checks. Follow
  the user's instructions before editing code or posting findings externally.
