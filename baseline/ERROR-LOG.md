# Step 6: Eclipse Error Log acceptance

Reviewed September 22, 2026 against Eclipse Platform 4.41, Mylyn 4.12,
Temurin 21.0.12.1 and macOS aarch64. The current `main` commit was
`6f0cf237a15a8271fa578892521c18183dac9558`.

## Evidence

- All retained installed-IDE logs under
  `/private/tmp/timekeeper-installed.RaDTib` were classified, including clean
  install, update, preference, interrupted-activity, concurrent-instance and p2
  director sessions. These files are temporary local evidence and may be
  removed by the operating system.
- `mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore` passed on the current
  commit with 64 database/report/packaging tests and 11 SWTBot UI tests. The p2
  ZIP was built as `net.resheim.eclipse.timekeeper-2.0.0.202609220643.zip`.
- The resulting `2.0.0.202609220643` feature was installed successfully in an
  isolated copy of Eclipse by one p2 uninstall/install transaction. A fresh
  interactive launch was stopped before the workbench by macOS's local-network
  permission prompt, so it is not counted as new runtime-log evidence.
- The current SWTBot runtime log contains only the previously documented test
  harness entries: the absent default theme, missing Cocoa menu commands and a
  Mylyn shutdown-thread error. None is emitted by a Timekeeper bundle, and the
  corresponding installed-IDE start did not reproduce them as application
  errors.

## Classification

| Entry | Classification | Disposition |
| --- | --- | --- |
| `org.eclipse.mylyn.commons.ui`: `NumberFormatException: For input string: "or" under radix 16` | Installed dependency error, reproduced when Task List creates its tooltip colors | **Open release blocker.** Workweek remains usable, but dependency-error acceptance is not clean. |
| `StringFieldEditor.doStore()`: null `textField` from the Timekeeper database preference page | Historical Timekeeper error | Fixed by PR #202. The SWTBot preference test now visits the page and uses **Apply and Close**; it passed in the current build. |
| Null `lastActiveTime` during interrupted-activity cleanup | Historical Timekeeper error | Fixed by PR #206 and covered by the current idle/recovery tests. |
| `No search provider was registered` from Mylyn Tasks | Historical packaging error | Fixed by PR #197, which installs the complete Mylyn Tasks feature. It is absent from the corrected installed sessions. |
| Duplicate SLF4J providers | Historical packaging error | Fixed by PR #197 by using the host provider rather than shipping a second Equinox provider. |
| Database unavailable while port 9090 was deliberately occupied | Expected negative acceptance scenario | The concurrent-instance test intentionally caused this error and verified unchanged persisted data. |
| p2 version conflict before the tested uninstall/install transaction | Expected negative provisioning scenario | The corrected atomic transaction succeeded; the director recorded severity-zero satisfiable requests. |
| EclipseLink deprecated password-encryption warning | Dependency console warning, not an Eclipse Error Log entry | Triggered by the synthetic H2 `sa`/empty-password configuration. Removing the redundant empty password property did not suppress it, so no ineffective configuration change was retained. |

## Remaining Mylyn blocker

Mylyn's `E4ThemeColor.getRGBFromCssString` accepts literal `rgb(...)` and hex
values. Eclipse 4.41 can instead return a symbolic CSS color beginning with
`#org-...`; Mylyn treats the first two letters, `or`, as hexadecimal, logs the
exception once and falls back to the SWT list background. The same parser is
still present on Mylyn's official `main` at commit
[`476d762`](https://github.com/eclipse-mylyn/org.eclipse.mylyn/blob/476d762847a9d62da6644794a93c593527a17314/mylyn.commons/org.eclipse.mylyn.commons.ui/src/org/eclipse/mylyn/internal/commons/ui/E4ThemeColor.java).

There is no released or current upstream replacement to select in the target
platform. Timekeeper should not fork the Mylyn bundle or override workbench-wide
theme CSS merely to hide the entry. Acceptance therefore remains open until an
upstream fix can be consumed. After that update, rebuild the p2 repository,
install it in a clean Eclipse 4.41, open both Workweek and Task List, exercise a
tooltip, and verify that the fresh workspace Error Log contains no Timekeeper or
dependency errors.

## Decision

The Error Log has been inspected and all Timekeeper-owned findings are either
fixed and regression-tested or belong to deliberate negative scenarios. Step 6
is nevertheless **blocked**, because the installed Mylyn theme-parser error does
not meet the plan's completion criterion of operation without dependency
errors. The remaining release-documentation work can proceed independently,
but the Error Log checkbox and final publication approval must stay open.
