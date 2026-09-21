# Baseline før Eclipse-oppgradering

Dato: 21. september 2026. Kildegrunnlag: commit
`0105303faa60193f3796cd98c5ff01eac5234ca5` med eksisterende lokale endringer
i `.java-version` og database-/UI-prosjektenes `.classpath`.
Disse lokale endringene er bevart. Ingen produksjonskode eller byggkonfigurasjon
er endret i baseline-arbeidet.

## Byggmiljø og resultat

| Egenskap | Observert verdi |
| --- | --- |
| OS / arkitektur | macOS 27.0 / aarch64 |
| Maven | Homebrew Maven 3.9.16 |
| JDK brukt til bygget | Homebrew OpenJDK 11.0.32.1 |
| Tycho | 2.7.5, uendret |
| Målplattform | Eclipse 2022-03, uendret |
| Standard Java-valg | Feiler: jenv finner ikke `17.0.4.1` fra `.java-version` |
| Byggresultat | Exit 1 under oppløsning av målplattform, før kompilering og tester |

Kommando kjørt fra prosjektroten, med eksplisitt JDK for å omgå det utdaterte jenv-valget:

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home \
  /opt/homebrew/bin/mvn -B -ntp clean verify -Dtycho.localArtifacts=ignore
```

Første forsøk i sandbox stoppet fordi Tycho ikke kunne opprette en lås i Maven-cachen
(`Unable to create lock manager`). Etter godkjent kjøring uten denne begrensningen
kom bygget frem til følgende prosjekt-/repository-feil:

```text
Failed to resolve target definition .../default.target:
Could not find "org.eclipse.mylyn.bugzilla_feature.feature.group/3.25.2.v20200814-0512"
in the repositories of the current location
```

`default.target` ber om denne eksakte versjonen fra
`http://download.eclipse.org/mylyn/releases/latest`. Den ble ikke funnet der ved
dagens kjøring. Bygget meldte også at SLF4J manglet `StaticLoggerBinder` og brukte
logging uten output. Det er Mylyn-oppløsningen som stopper bygget.

Ingen nye tester ble kjørt. Ingen validerbar plugin ble bygget, og oppstart i Eclipse
er ikke testet. Feilen kom før Maven kunne utføre `clean`, så gamle `target`-filer
og rapporter finnes fortsatt og må ikke tas som resultat fra denne kjøringen.

Detaljerte lokale logger fra arbeidet ligger i
`/private/tmp/timekeeper-baseline.gvEheO/`: `build.log` (sandbox),
`build-unrestricted.log` (repository-feil), `fixture.log` og `restore.log`.
Midlertidige filer kan forsvinne; konklusjonene og reproduksjonskommandoene er
derfor bevart i dette dokumentet.

## Testinventar

| Test | Deklarert i koden | Dagens kjøring |
| --- | --- | --- |
| `SharedStorageTest` | To JUnit 4-tester: enkel persistens og varighet per dag | Ikke nådd |
| `TemplateTest` | Én JUnit 5-parametrisert test, to malfiler i `templates/` | Ikke nådd |
| `IntegrationTest.testNavigateWorkweekView` | Aktiv JUnit 4/SWTBot-test | Ikke nådd |
| `IntegrationTest.testOpenPreferences` | Aktiv JUnit 4/SWTBot-test | Ikke nådd |
| `IntegrationTest.testExport` | `@Test` sammen med `@Ignore` | Deaktivert i koden |
| `IntegrationTest.testEditTimeRange` | `@Test` er kommentert ut | Ikke en aktiv test |

Historisk rapport i `net.resheim.eclipse.timekeeper.db/target/surefire-reports/`
er datert 19. september 2022 og viser to beståtte `SharedStorageTest`-tester,
ingen feil og ingen skips. Den angir Java 11.0.15 og Maven 3.8.6.
Dette er historisk evidens, ikke en ny verifikasjon. En kopi ble bevart i loggmappen.

Database-POM-en bruker Surefire 2.19.1 og deklarerer Jupiter API og params 5.8.2,
men ingen Jupiter engine eller eksplisitt JUnit Platform-provider. Det finnes
ingen historisk `TemplateTest`-rapport i prosjektet. Testoppdagelse og faktisk
kjøring av JUnit 5 må derfor verifiseres når målplattformen igjen kan løses;
en vellykket Maven-exit alene vil ikke være nok.

## Syntetiske data og forventninger

Brukeren valgte syntetiske fixtures dersom ingen eksisterende database var tilgjengelig.
Ingen databasefil ble funnet i prosjektet. Ingen personlig Timekeeper-database er
åpnet eller endret.

### Historikk og hensikt med modellen

Brukeren knytter databaseomleggingen til ønsket om å samordne modellen med
Timewarrior/Taskwarrior. [Issue #165](https://github.com/turesheim/eclipse-timekeeper/issues/165),
opprettet 20. april 2020, bekrefter dette som et prosjektmål. Saken er fortsatt åpen
og har ingen beskrivelse eller kommentarer som definerer et konkret kompatibilitetsformat.

Git-historikken viser at endringen gikk fra `TrackedTask` til `Task`:

- `da5ab90`: innføring av delt databaselagring i 2016/2017, med `TrackedTask`.
- `7cb249f`, 4. november 2020: prosjekt, oppgave-URL og navn lagres i databasen.
  [Issue #163](https://github.com/turesheim/eclipse-timekeeper/issues/163) forklarer
  at rapporter skal kunne lages uten å laste Mylyn-oppgaver eller kontakte deres repository.
- `66590d6`, 8. november 2020: `TrackedTask` blir `Task`, `TRACKEDTASK` blir `TASK`,
  og `TrackedTaskId` blir `GlobalTaskId`. Modellen får også tydeligere valgfri Mylyn-kobling.
- `0105303`, 26. oktober 2022: etikettarbeidet fra
  [issue #166](https://github.com/turesheim/eclipse-timekeeper/issues/166) integreres.

Dette underbygger at forskjellen mellom SQL-skjemaet og dagens modell kommer fra
en bevisst videreutvikling. Den konkrete koblingen mellom rename-committen og #165
er ikke dokumentert i commitmeldingen; brukerens forklaring er registrert som designkontekst.
Oppgraderingen skal bevare dagens modell og muligheten for rapportering uavhengig av
Mylyn. Den historiske fixturen er migreringsgrunnlag, ikke et forslag om å gå tilbake
til `TRACKEDTASK`. Ny Timewarrior/Taskwarrior-integrasjon er ikke lagt til oppgraderingsomfanget.

`legacy-data.sql` bruker prosjektets uendrede `V1__baseline.sql` og
`V2__add_project_taskurl_and_tasksummary.sql`. Den representerer det historiske
SQL-skjemaet i repositoryet; den er ikke dokumentasjon på skjemaet til en bestemt
publisert utgave eller en database opprettet av dagens JPA-modell.

| Kontroll | Forventet |
| --- | --- |
| Prosjekter | 2 |
| Oppgaver | 3, inkludert én uten aktiviteter |
| Oppgave-ID `1` | 2 forskjellige repositories, separate sammensatte nøkler |
| Aktiviteter / oppgave-aktivitetskoblinger | 5 / 5 |
| Manuelt justerte aktiviteter | 1 |
| Varighet for oppgave `a/1` / `b/1` | 9 900 / 9 900 sekunder |
| Total varighet | 19 800 sekunder = 5 t 30 min |
| 18. september 2022 | 1 800 sekunder = 30 min |
| 19. september 2022 | 13 500 sekunder = 3 t 45 min |
| 20. september 2022 | 4 500 sekunder = 1 t 15 min |
| ISO-uke 37 / 38 | 30 min / 5 t |
| Etiketter / tilordninger i separat spesifikasjon | 2 / 3 |
| Fakturerbar / Intern / uten etikett | 2 t 45 min / 45 min / 2 t |

Dataene omfatter flere aktiviteter per oppgave, norsk tekst, manuell justering,
midnatt og overgang fra søndag til mandag. Rapportene skal senere sammenlignes
med disse verdiene, ikke bare kontrolleres for at en fil blir opprettet.

`labels.csv` definerer etiketter og hvilke aktivitets-ID-er de skal knyttes til.
Det gamle SQL-skjemaet har ingen etiketttabeller. Derfor ligger etikettene separat,
og fixture-verktøyet kontrollerer antall, farger og referanser til eksisterende
aktiviteter. Persistens av etiketter gjennom dagens JPA-modell er fortsatt uverifisert.

## Reprodusere og kontrollere databasekopien

Kjør fra prosjektroten med JDK 11 og den medfølgende H2 1.4.194:

```sh
/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home/bin/java \
  -cp net.resheim.eclipse.timekeeper.db/lib/h2-1.4.194.jar \
  baseline/LegacyFixture.java
```

På andre maskiner kan Java-stien erstattes med en installert JDK 11.
Verktøyet bruker JDBC direkte, krever ikke Maven eller en Eclipse-installasjon,
og oppretter en ny midlertidig mappe for hver kjøring. Det skriver ut plasseringen
og oppretter `original.mv.db`, `copy.mv.db` og `export.sql`. Kopien tas etter at
databasen er lukket. Original og kopi kontrolleres for antall, koblinger,
manuell justering, norsk tekst og tidssummer. Det er ikke en JPA-/UI-test.

Resultat 21. september: **bestått**, exit 0. Testdata og verifisert kopi ble laget i
`/var/folders/8m/2g_5_qdj5490htv6jfkzkcsh0000gn/T/timekeeper-legacy-fixture-7656006107624216071/`.
Genererte binærfiler er ikke sjekket inn; kildefilene gjør dem reproducerbare.

For å gjenskape den observerte SQL-restore-feilen, kjør samme kommando med
`--restore` etter `baseline/LegacyFixture.java`. Den oppretter enda en ny mappe
og forsøker å lese full SQL-eksport inn i `restored.mv.db`.

Resultat 21. september: **feilet**, exit 1, etter at original og filkopi var validert:

```text
Index "PRIMARY_KEY_1" already exists
CREATE UNIQUE INDEX PUBLIC.PRIMARY_KEY_1 ON PUBLIC.TRACKEDTASK_ACTIVITY(...)
[42111-194]
```

Feilen er reprodusert med samme H2-versjon på begge sider. Den er ikke rettet
eller skjult ved å endre de historiske migreringene. Full SQL-eksport kan ikke
regnes som en verifisert tilbakeføringsvei for dette skjemaet før trinn 4.

## Oppfølging i neste trinn

- Trinn 2: rett målplattformens Mylyn-oppløsning, og kjør baseline-testene på nytt.
- Trinn 3/5: gjennomfør plugin-oppstart og UI-testene når et bygg er tilgjengelig.
- Trinn 4: avklar forskjellen mellom `TRACKEDTASK` i migreringene og `TASK` med
  etiketter i dagens JPA-modell, og opprett en fixture gjennom den faktiske modellen.
- Trinn 4: verifiser etikettpersistens og en fungerende eksport/import-/tilbakeføringsvei.
- Trinn 5: verifiser Jupiter-testoppdagelse og ta stilling til de deaktiverte UI-testene.

Flyway-kallet er kommentert ut i `TimekeeperPlugin`, men `persistence.xml`
aktiverer fortsatt EclipseLinks `create-tables`. Oppstarten er derfor ikke uten
skjemaoppretting; det som mangler er en verifisert, versjonert migreringsvei.
Standard JDBC-URL i `persistence.xml` har også dobbelt `jdbc:h2:`-prefiks,
mens både pluginoppstarten og `PersistenceHelper` overstyrer URL-en.
