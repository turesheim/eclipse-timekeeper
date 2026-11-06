---
name: dagsverk-change
description: Implement a feature or fix a defect in Dagsverk with observable acceptance criteria and verification. Use for code changes across this repository; use dagsverk-review for a review-only request.
---

# Dagsverk changes

Read the root `AGENTS.md` and the relevant component guide. Use
`docs/architecture/README.md` for boundaries and
`docs/development/AGENT-WORKFLOW.md` for quality commands and task examples.
Paths in this skill are relative to the repository root.

- Identify the user's desired behavior, affected users/clients and observable
  success conditions. For a defect, reproduce it or explain what evidence is
  available. For larger tasks, record a concise plan in the conversation.
  Do not create planning files or request approval for routine choices.
- Inspect existing implementation, tests and local changes before editing.
  Separate current requirements from dated reports in `docs/verification/`.
- Use `dagsverk-backend`, `dagsverk-web` or `dagsverk-eclipse` where applicable.
  Preserve technical identifiers during the Dagsverk naming transition.
- Implement the smallest coherent change. Cover meaningful changed behavior
  and regressions; reuse existing acceptance fixtures and synthetic accounts.
- Run `python3 scripts/quality.py --affected --plan`, then the selected checks.
  Add scenario checks appropriate to the behavior. A test command appearing
  in a plan or a successful syntax check does not establish acceptance.
- Review the final diff against each acceptance criterion and the agreed scope.
  Report the resulting behavior, executed checks and any remaining uncertainty.
  Preserve the user's authorization boundaries throughout.
