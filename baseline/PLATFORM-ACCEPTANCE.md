# Platform and native idle-detection acceptance

Verified September 21, 2026 with Java 21 and JNA/JNA Platform 5.19.1. GitHub
Actions run [35609755484](https://github.com/turesheim/eclipse-timekeeper/actions/runs/35609755484)
passed on all three hosted desktop operating systems.

## Tested combinations

| Environment | Architecture | Validation | Result |
| --- | --- | --- | --- |
| macOS 27.0 local | aarch64 | Clean full build, 64 database/report/packaging tests, 11 UI tests, installed Eclipse update/restart and native idle query | Passed |
| GitHub `macos-latest` | aarch64 | Clean build plus `MacIdleTimeDetector` native smoke test | Passed |
| GitHub `ubuntu-latest` with Xvfb | x86_64, X11 | Clean full build and UI suite, including `X11IdleTimeDetector` against the XScreenSaver extension | Passed |
| GitHub `windows-latest` | x86_64 | Clean build plus `WindowsIdleTimeDetector` calling `GetLastInputInfo` and `GetTickCount` | Passed |

The smoke test checks that Eclipse selected the detector for the running OS and
that the native API returned a non-negative idle duration rather than
`IdleTimeDetector.NOT_WORKING`. The Windows implementation now uses JNA's
`LASTINPUTINFO`, whose `cbSize` field has the required 32-bit layout, checks the
native return value and treats the tick counters as unsigned values across wrap.
The macOS and X11 implementations likewise reject invalid native results and
convert linkage/runtime failures into the documented disabled state.

Linux remains the primary CI build: it runs the complete UI suite and publishes
the p2 repository. macOS and Windows matrix jobs run the database/report suites,
compile and package every module, and restrict the UI harness to the native idle
smoke test. This avoids making cross-platform idle acceptance depend on the
known SWTBot inline-editor focus problem.

## Support boundaries

- macOS aarch64 is the fully exercised configuration, including clean install,
  update, restart, data display, manual editing and native idle detection.
- Linux x86_64 with X11 is continuously exercised under Xvfb. A real desktop
  window manager is not part of that evidence.
- Windows x86_64 has clean build, persistence and native idle-query coverage,
  but not a complete installed-IDE acceptance run.
- macOS x86_64 and Linux aarch64 remain target-platform build environments, not
  runtime-certified combinations.
- The Linux detector uses X11 and the XScreenSaver extension. It works in an
  X11 session and may work under Wayland only when XWayland supplies `DISPLAY`
  and XScreenSaver. A pure Wayland session has no supported native detector;
  Timekeeper reports the detector as unavailable and disables idle handling for
  that session. No portal, compositor-specific or logind fallback is implemented.

These boundaries describe the evidence for the 2.0 upgrade candidate. They do
not imply that unlisted Eclipse-supported platforms cannot run the plugin.
