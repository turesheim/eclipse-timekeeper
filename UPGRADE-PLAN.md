# Oppgraderingsplan for Timekeeper

Opprettet: 21. september 2026.

Mål: Timekeeper skal kunne installeres og brukes i Eclipse IDE 2026-09
(Eclipse Platform 4.41), med eksisterende tidsregistreringer bevart.
Dette var siste stabile Eclipse-utgave da planen ble laget.

Status: Trinn 2 er gjennomført. Rene kilder kompilerer og pakkes mot Eclipse
2026-09 med Java 21. Full testkjøring feiler i persistenslaget; JUnit 5-testene
kjøres fortsatt ikke. Plugin-oppstart, migrering og øvrig testdekning gjenstår.
Se [opprinnelig baseline](baseline/README.md) og
[resultatene fra trinn 2](baseline/BUILD-UPGRADE.md).

## Slik følger vi planen

- Arbeid gjennom trinnene i rekkefølge, og kryss av når en oppgave er verifisert.
- Registrer resultat, valgt versjon og eventuelle avvik under det aktuelle trinnet.
- Bruk separate endringssett for bygg/målplattform, Mylyn/UI, database og utgivelse.
- Test databaseendringer på kopier eller testdata. Behold originaldata og en
  dokumentert tilbakeføringsmulighet.
- Bevar eksisterende lokale endringer i `.java-version` og de to `.classpath`-filene
  i database- og UI-prosjektene. Avklar overlapp før disse innstillingene endres.

## Utgangspunkt

- Aktiv målplattform: Eclipse 2022-03 i `default.target` og `default.tpd`.
- Bygg: Tycho 2.7.5 og Java 11 i Maven, manifestene og CI.
  Den lokale `.java-version` angir Java 17.0.4.1.
- UI-manifestet begrenser sentrale Mylyn-avhengigheter til `[3.0.0,4.0.0)`.
- Flere klasser bruker interne Mylyn-API-er.
- Databaselaget bruker H2 1.4.194, EclipseLink 2.7.3 og `javax.persistence`.
- Flyway-kallet i databaseoppstarten er kommentert ut.
- Testoppsettet blander JUnit 4 og 5 og bruker eldre Surefire og SWTBot.
- Det finnes også en eldre Neon-målplattform i
  `net.resheim.eclipse.timekeeper.target/`.

## 1. Etablere et etterprøvbart utgangspunkt

- [x] Registrer JDK- og Maven-versjoner og kjør dagens bygg.
- [x] Dokumenter byggefeil, testresultater og status for oppstart.
- [x] Kontroller hvilke tester som faktisk oppdages og kjøres, inkludert JUnit 5-testene.
  Verifisert i trinn 2: to JUnit 4-metoder kjøres og feiler; `TemplateTest` kjøres ikke.
- [x] Lag representative syntetiske testdata med oppgaver, aktiviteter,
  separat etikettspesifikasjon og rapportgrunnlag.
- [x] Registrer forventede antall, relasjoner og tidssummer for senere sammenligning.
- [x] Etabler en syntetisk database med repositoryets historiske SQL-skjema
  og en verifisert filkopi til oppgraderingstesting.
- [ ] Verifiser også etiketter og relasjoner i en fixture opprettet gjennom dagens JPA-modell.

Ferdigkriterium: Vi har en dokumentert baseline og testdata som kan avsløre tap
eller endring av registreringer. Eventuelle eksisterende feil er skilt fra nye feil.

Resultater og avvik, 21. september 2026:

- Java 11.0.32.1 og Maven 3.9.16 ble brukt uten å endre lokale Java-innstillinger.
- Bygget feilet før kompilering: target-repositoryet kunne ikke levere
  `org.eclipse.mylyn.bugzilla_feature.feature.group/3.25.2.v20200814-0512`.
- Ingen tester kjørte i det opprinnelige baseline-bygget. To beståtte database-tester finnes i en historisk
  rapport fra 2022; testinventaret og JUnit 5-usikkerheten er dokumentert.
- Brukeren valgte syntetiske fixtures dersom en eksisterende database ikke var tilgjengelig.
  Original og filkopi er validert med 3 oppgaver, 5 aktiviteter og totalt 5 t 30 min.
- Etikettspesifikasjonen har 2 etiketter og 3 tilordninger, men det historiske
  SQL-skjemaet støtter ikke etiketter. Persistens gjennom dagens JPA-modell gjenstår.
- Brukeren knytter modellendringene til Timewarrior/Taskwarrior-målet i
  [#165](https://github.com/turesheim/eclipse-timekeeper/issues/165).
  Git-historikken viser `TrackedTask` → `Task` i november 2020. Dagens modell skal
  bevares; den historiske fixturen er kun migreringsgrunnlag. Se baseline-rapporten for historikken.
- Full SQL-restore feiler på en duplisert indeks også med H2 1.4.194 på begge sider.
  Dette er dokumentert for trinn 4; historiske migreringer er ikke endret.
- Se [baseline/README.md](baseline/README.md) for kommandoer, forventede verdier
  og begrensninger. Trinn 2 kan påbegynnes; de åpne kontrollene over må følges opp.

## 2. Oppgradere bygg og samle målplattformen

- [x] Oppdater Tycho til 5.0.4 og bruk Maven 3.9.9 eller nyere.
- [x] Bruk JDK 21 som utgangspunkt for bygget og verifiser kjøretidskravene til målplattformen.
- [x] Fastsett pluginens Java-minimum og samordne Maven, manifestene,
  `.settings`, `.classpath`, `.java-version` og CI.
- [x] Oppdater `default.target` til Eclipse 2026-09 og avvikle `.tpd` som parallell definisjon.
- [x] Lås Mylyn og øvrige avhengigheter for bygging; persistenskompatibilitet gjenstår i trinn 4.
- [x] Bruk HTTPS og versjonsbestemte repositories der det er tilgjengelig.
- [x] Gjennomgå behovet for Gemini JPA, gamle Orbit-biblioteker og connector-avhengigheter.
- [x] Samle prosjektet om én målplattform og avvikle den gamle Neon-definisjonen.
- [x] Oppdater relevante launch-konfigurasjoner.
- [x] Verifiser avhengighetsoppløsning fra en ren utsjekking uten lokale Eclipse-artefakter.

Ferdigkriterium: Avhengighetene løses konsistent mot Eclipse 2026-09, og
utviklingsmiljøet og kommandolinjebygget bruker samme målplattform.
Eventuelle gjenværende kildekodefeil er dokumentert for trinn 3 og 4.

Resultater og avvik, 21. september 2026:

- Eclipse 4.41, Mylyn 4.12 og SWTBot 4.3 løses fra ett datert SimRel-repository.
- Java-minimum er 21. Tycho 5.0.4 og JaCoCo 0.8.15 brukes i bygget.
- Ren kildeeksport kompilerer alle moduler og lager p2-repository med tester
  eksplisitt hoppet over. UI-testklasser er nå nykompilerte, ikke gjenbruk fra `bin/`.
- Full `verify` kjøres med tester og feiler i `SharedStorageTest`: EclipseLink 2.7.3
  avviser Java 21-bytecode (`Unsupported class file major version 65`) og
  gjenkjenner ikke entity-modellen. Dette følges opp i trinn 4.
- JUnit 5-rapporttestene kjøres ikke av dagens provider. Dette følges opp i trinn 5.
- CI kjører fortsatt full `verify`; endringen er ikke klar for utgivelse.
- Se [baseline/BUILD-UPGRADE.md](baseline/BUILD-UPGRADE.md) for detaljert verifikasjon.

## 3. Tilpasse Mylyn-integrasjonen og brukergrensesnittet

- [ ] Bekreft at valgt Mylyn-utgave fungerer med Eclipse 2026-09.
- [ ] Oppdater manifestenes versjonsintervaller etter faktisk kompatibilitet.
- [ ] Gjennomgå interne Mylyn-API-er i blant annet `TimekeeperPlugin`, `Task`,
  `Project`, `WorkWeekView` og innholds- og etikettleverandørene.
- [ ] Erstatt interne API-er med offentlige API-er der det er mulig.
- [ ] Samle nødvendige interne koblinger på færre steder og dokumenter dem.
- [ ] Avklar om Bugzilla må være en obligatorisk avhengighet.
- [ ] Rett nødvendige kompilerings- og kjøretidsfeil i Eclipse/SWT/JFace-integrasjonen.
- [ ] Verifiser aktivering og deaktivering av oppgaver, kategorier og oppdatering av ukevisningen.

Ferdigkriterium: Pluginen starter i Eclipse 2026-09 og registrerer tid korrekt
på testdata. Endelig verifisering avhenger av et fungerende databaselag i trinn 4.

Resultater og avvik: Ikke påbegynt.

## 4. Sikre databaselaget og oppgraderingsveien

- [ ] Test dagens databaseformat og persistensoppsett på den nye kjøretiden.
- [ ] Bevar dagens `Task`-/aktivitetsmodell og rapportering uavhengig av Mylyn
  ved migrering fra det historiske `TRACKEDTASK`-skjemaet, med designkontekst fra #163 og #165.
- [ ] Velg en kompatibel EclipseLink/JPA-kombinasjon og dokumenter versjonene.
- [ ] Vurder om overgang fra `javax.persistence` til `jakarta.persistence` er nødvendig;
  gjennomfør i så fall overgangen som en egen, testbar endring.
- [ ] Avklar og reparer skjemaoppretting og migrering, inkludert det deaktiverte Flyway-kallet.
- [ ] Bestem om H2 skal oppgraderes i denne leveransen, med begrunnelse og eventuelt oppfølgingsarbeid.
- [ ] Ved overgang til H2 2.x: lag eksport med gammel H2-versjon og import til en ny database,
  med sikkerhetskopi, validering og dokumentert tilbakeføring.
- [ ] Verifiser eksisterende JDBC-parametere, delt database, workspace-database og konfigurert server.
- [ ] Test samtidig tilgang fra flere Eclipse-instanser og håndtering av inkompatible klientversjoner.
- [ ] Test ny database, eksisterende database, migreringsfeil og omstart.
- [ ] Sammenlign antall, relasjoner og tidssummer med testgrunnlaget fra trinn 1.

Ferdigkriterium: Ny database og støttet oppgradering av eksisterende data fungerer.
Registreringene er bevart, og en mislykket migrering kan håndteres uten tap av originaldata.

Resultater og avvik: Ikke påbegynt.

## 5. Modernisere tester, biblioteker og CI

- [ ] Oppdater SWTBot, Surefire/testkjøring og JaCoCo til kompatible versjoner.
- [ ] Sørg for at både eksisterende JUnit 4- og JUnit 5-tester faktisk kjøres,
  eller samordne dem på én testplattform.
- [ ] Gjennomgå og oppdater JNA, FreeMarker og logging etter kompatibilitetsbehov.
- [ ] Oppdater GitHub Actions for utsjekking, Java, cache, rapporter og artefakter.
- [ ] Kjør automatiserte tester for tidsregistrering, manuell redigering, etiketter,
  rapporteksport, import og omstart.
- [ ] Kjør UI-testene i CI med nødvendige skjerm-/Xvfb-innstillinger.
- [ ] Kontroller inaktivitetsdeteksjon på Windows, macOS og Linux.
- [ ] Verifiser Apple Silicon og vurder X11/Wayland særskilt; dokumenter begrensninger.
- [ ] Dokumenter hvilke OS-/arkitekturkombinasjoner som er testet og støttes.

Ferdigkriterium: Relevante tester oppdages, kjøres og består. CI produserer
testresultater og et p2-repository, og plattformstøtten er dokumentert.

Resultater og avvik: Ikke påbegynt.

## 6. Verifisere installasjon og klargjøre utgivelsen

- [ ] Oppdater feature- og p2-metadata til den valgte avhengighetsstakken.
- [ ] Bygg p2-repositoriet og installer i en ren Eclipse 2026-09.
- [ ] Kontroller at nødvendige avhengigheter følger med eller kan installeres automatisk.
- [ ] Test oppgradering fra forrige publiserte Timekeeper-utgave.
- [ ] Verifiser omstart, innstillinger og eksisterende data etter oppgraderingen.
- [ ] Kontroller Eclipse Error Log for feil knyttet til Timekeeper og avhengighetene.
- [ ] Oppdater versjonsnumre, README, CHANGES og eventuell migreringsveiledning.
- [ ] Dokumenter støttet Eclipse-/Java-versjon, installasjon og tilbakeføring.
- [ ] Registrer endelig bygg, testresultater og plassering av utgivelsesartefakten.

Ferdigkriterium: Installasjon, oppgradering, omstart og bruk med eksisterende data
fungerer uten avhengighetsfeil. Et verifisert p2-repository og nødvendig dokumentasjon
er klare for publisering.

Resultater og avvik: Ikke påbegynt.

## Viktige beslutninger underveis

| Beslutning | Status |
| --- | --- |
| Eksakt Mylyn-versjon og nødvendige connectors | Mylyn 4.12; Tasks/Bugzilla i target, runtime vurderes i trinn 3 |
| Java-minimum for pluginen og testet kjøretid | Java 21; bygg testet med Temurin 21.0.12.1 |
| EclipseLink/JPA-versjon og eventuelt Jakarta-skifte | Avklares i trinn 4 |
| H2-oppgradering og migreringsprosedyre | Avklares i trinn 4 |
| Støttede OS og arkitekturer | Verifiseres i trinn 5 |
| Ny Timekeeper-versjon | Fastsettes før trinn 6 ferdigstilles |

Mylyn-kompatibilitet og databasemigrering er de største usikkerhetene.
Avklar dem tidlig før omfang og tidsestimat låses.

## Kilder

- [Eclipse-versjoner og dokumentasjon](https://www.eclipse.org/documentation/)
- [Tycho-versjoner og krav til Maven/JDK](https://tycho.eclipseprojects.io/doc/latest/tycho-compiler-plugin/plugin-info.html)
- [H2: migrering til 2.x](https://h2database.com/html/migration-to-v2.html)

Kildene ble gjennomgått da planen ble laget. Versjonsvalgene må bekreftes dersom
arbeidet starter etter at nyere utgaver er publisert.
