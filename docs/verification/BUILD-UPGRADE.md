# Step 2: build and target platform

Initial verification: September 21, 2026, on macOS aarch64 with Temurin 21.0.12.1
and Maven 3.9.16. Source revision: `65fe25d`. This records the initial build
upgrade; subsequent test-enablement results are recorded in [TEST-UPGRADE.md](TEST-UPGRADE.md).

## Changes

| Component | Selected version / configuration |
| --- | --- |
| Eclipse SDK | 2026-09 / 4.41 |
| Mylyn | 4.12 from the same SimRel repository |
| SWTBot | 4.3 from the same SimRel repository |
| Tycho | 5.0.4 |
| Java | JavaSE-21 in manifests, compiler settings, launches and CI |
| Maven | Minimum 3.9.9, checked with Enforcer 3.6.3 |
| JaCoCo | 0.8.15, shared parent-POM property |
| Commons Lang | 3.20.0, updated imports and bundle names |
| Logging | SLF4J 2.0.18 and the Eclipse Equinox SLF4J provider |
| Persistence at this stage | EclipseLink 2.7.3 / javax.persistence retained |

`default.target` became the sole target definition, with all root dependencies
pinned to `https://download.eclipse.org/releases/2026-09/202609091000/`.
Old `.tpd` files and the Neon target were removed; they remain recoverable from Git.
The code does not use Gemini JPA: it instantiates EclipseLink directly.
Old Orbit, JAXB, log4j and extra connector roots were removed; transitive
dependencies resolve through SimRel. Mylyn Tasks and Bugzilla remain included.

The local Java pin changed from unavailable `17.0.4.1` to installed alias `21.0`.
Only the JavaSE-21 lines in the database/UI classpath files were committed;
unrelated pre-existing local changes were preserved outside the commit.

Launch configurations use standard PDE launchers without JMC. CI uses Java 21
and updated GitHub Actions, runs on pull requests, and uploads test results even
on failure. Full test execution remains mandatory in CI; skip flags were only
used for the separate diagnostic compilation/package check below.

UI tests compile into `target/classes` so `clean` removes their output rather
than reusing old `bin/` classes. A clean build exposed log4j imports previously
masked by local 2022 classes. These were converted to SLF4J and the old log4j
configuration was removed, along with unused Guava and obsolete Tycho parameters.

## Initial verification

A clean `git archive` source export was built in a new temporary directory,
without `.metadata`, local classpath edits or old `target`/`bin` files.
The normal Maven cache was used with `-Dtycho.localArtifacts=ignore`; this
was clean-source verification, not an empty-cache build.
An empty temporary Maven settings file excluded unrelated private repositories
configured on the machine. The user's Maven configuration was not modified.

```sh
mvn -s /path/to/empty-settings.xml -B -ntp clean package \
  -DskipTests -Dtycho.localArtifacts=ignore
mvn -s /path/to/empty-settings.xml -B -ntp verify \
  -Dtycho.localArtifacts=ignore
```

Omit `-s` on machines without custom Maven repositories.

| Check | Initial result |
| --- | --- |
| XML and exact target versions against published p2 metadata | Passed |
| Maven/Java requirements | Passed |
| Clean compilation and packaging of all six reactor projects | Passed, tests explicitly skipped |
| Newly compiled UI test classes | Java 21 bytecode, major version 65 |
| p2 repository, ZIP and bundled index.html | Created |
| Full `verify` | Failed in database tests |
| JaCoCo instrumentation on Java 21 | Previous class-file errors resolved |
| Plugin startup and UI test execution | Not verified at this stage |

The clean package run took about eight seconds with a warm cache. Its result was
`net.resheim.eclipse.timekeeper-site/target/repository` in the clean checkout.
That artifact was for further testing, not publication as a completed upgrade.

## Failures identified at this stage

Both `SharedStorageTest` methods executed and failed. EclipseLink 2.7.3 rejected
the model classes with `Unsupported class file major version 65`, reported an
empty metamodel and `Object ... is not a known Entity type`. Cleanup then failed
because the `ACTIVITY` table did not exist. Surefire summarized two failing
methods; the XML contained four error entries including cleanup failures.
A Java 21-compatible persistence implementation was required.

`TemplateTest` did not execute under the existing JUnit 4 provider; only a
`SharedStorageTest` report existed. Jupiter engine/provider configuration and
actual report-test execution needed repair. UI tests compiled from clean source,
but `verify` stopped before that module, so no UI success was claimed.

Target resolution covers Windows x86_64, Linux x86_64/aarch64 and
macOS x86_64/aarch64. Dependency/build verification is not runtime verification
on each platform.

## Sources for version selection

- [Dated Eclipse 2026-09 repository](https://download.eclipse.org/releases/2026-09/202609091000/)
- [Tycho Maven and Java requirements](https://tycho.eclipseprojects.io/doc/latest/tycho-compiler-plugin/plugin-info.html)
- [Maven Enforcer](https://maven.apache.org/enforcer/maven-enforcer-plugin/usage.html)
- [JaCoCo changes](https://www.jacoco.org/jacoco/trunk/doc/changes.html)
