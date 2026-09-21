# Trinn 2: bygg og målplattform

Verifisert 21. september 2026 på macOS aarch64 med Temurin 21.0.12.1 og
Maven 3.9.16. Kodegrunnlag for sluttkontrollen: `65fe25d`.

## Endringer

| Del | Valgt versjon / oppsett |
| --- | --- |
| Eclipse SDK | 2026-09 / 4.41 |
| Mylyn | 4.12 fra samme SimRel-repository |
| SWTBot | 4.3 fra samme SimRel-repository |
| Tycho | 5.0.4 |
| Java | JavaSE-21 i manifest, kompilator, launch-konfigurasjoner og CI |
| Maven | Minimum 3.9.9, kontrollert med Enforcer 3.6.3 |
| JaCoCo | 0.8.15, felles versjon i parent-POM |
| Commons Lang | 3.20.0, oppdaterte import- og bundle-navn |
| Logging | SLF4J 2.0.18 og Eclipse Equinox SLF4J-provider |
| Persistens | EclipseLink 2.7.3 / javax.persistence beholdt inntil trinn 4 |

`default.target` er nå eneste målplattformdefinisjon. Alle rotavhengigheter er
versjonslåst mot `https://download.eclipse.org/releases/2026-09/202609091000/`.
De gamle `.tpd`-filene og Neon-målplattformen er fjernet og kan gjenfinnes i Git.
Gemini JPA er ikke brukt av koden, som oppretter EclipseLink-provider direkte.
Gamle Orbit-, JAXB-, log4j- og ekstra connector-røtter er tatt ut; transitive
avhengigheter løses fra SimRel. Mylyn Tasks og Bugzilla er beholdt i målplattformen.

Java-pinnen ble endret fra den lokalt manglende `17.0.4.1` til den eksisterende
`21.0`-aliasen. I database- og UI-prosjektenes classpath-filer er kun
JavaSE-21-linjen inkludert i committen; øvrige eksisterende lokale endringer
er bevart utenfor committen.

Launch-konfigurasjonene bruker standard PDE-launchere uten JMC-avhengighet.
CI bruker Java 21 og oppdaterte GitHub Actions, kjører også på pull requests
og laster opp testresultater selv om bygget feiler. Den fulle testkjøringen
er fortsatt obligatorisk i CI; skip-flagg er kun brukt i den separate
kompilerings-/pakkekontrollen under.

UI-testprosjektet kompilerer nå til `target/classes`, slik at `clean` rydder
output og pakken ikke gjenbruker gamle klasser fra `bin/`. En ren bygging
avdekket log4j-importer som var skjult av lokale 2022-klasser; testene bruker
nå SLF4J og den gamle log4j-konfigurasjonen er fjernet. Ubrukt Guava-avhengighet
og Tycho-parametere som ikke lenger støttes, er også fjernet.

## Verifikasjon

En ren kildeeksport med `git archive` ble bygget i en ny midlertidig mappe,
uten `.metadata`, lokale classpath-endringer eller gamle `target`-/`bin`-filer.
Den vanlige Maven-avhengighetscachen ble brukt, med `-Dtycho.localArtifacts=ignore`.
Dette er verifikasjon fra rene kilder, ikke fra en tom nedlastingscache.
En tom, midlertidig Maven-settings-fil utelot maskinens uvedkommende private
repositories; brukerens Maven-konfigurasjon ble ikke endret.

```sh
mvn -s /path/to/empty-settings.xml -B -ntp clean package \
  -DskipTests -Dtycho.localArtifacts=ignore
mvn -s /path/to/empty-settings.xml -B -ntp verify \
  -Dtycho.localArtifacts=ignore
```

På maskiner uten egne Maven-repositories kan `-s` utelates.

| Kontroll | Resultat |
| --- | --- |
| XML og eksakte target-versjoner mot publisert p2-indeks | Bestått |
| Maven/Java-krav | Bestått |
| Ren kompilering og pakking av alle seks reactor-prosjekter | Bestått, tester hoppet over eksplisitt |
| Nye UI-testklasser | Verifisert Java 21-bytecode, major version 65 |
| p2-repository, ZIP og medfølgende index.html | Opprettet |
| Full `verify` | Feiler i database-testene |
| JaCoCo-instrumentering av Java 21 | Ingen tidligere class-file-feil etter oppdateringen |
| Plugin-oppstart og faktisk UI-testkjøring | Ikke verifisert |

Ren pakkekjøring tok omtrent 8 sekunder med oppvarmet cache. Resultatet ligger i
`net.resheim.eclipse.timekeeper-site/target/repository` i den rene utsjekkingen.
Artefakten er kun til videre testing, ikke til publisering som ferdig oppgradering.

## Gjenstående feil og avgrensning

Begge testmetodene i `SharedStorageTest` kjøres og feiler. EclipseLink 2.7.3
avviser modellklassene med `Unsupported class file major version 65`, rapporterer
tomt metamodel og `Object ... is not a known Entity type`; oppryddingen feiler
deretter fordi `ACTIVITY`-tabellen mangler. Surefire oppsummerer to feilende
testmetoder; XML-rapporten har fire feiloppføringer fordi oppryddingsfeil også
registreres. Dette må løses i trinn 4 med en Java 21-kompatibel persistensstakk.

`TemplateTest` blir ikke kjørt av den eksisterende JUnit 4-provider-en. Det finnes
bare en `SharedStorageTest`-rapport. JUnit 5-engine/provider og faktisk
rapporttestdekning må håndteres i trinn 5. UI-testene kompilerer fra rene kilder,
men `verify` stopper før UI-testmodulen; ingen UI-beståttstatus er hevdet.

P2-løsning er konfigurert for Windows x86_64, Linux x86_64/aarch64 og
macOS x86_64/aarch64. Dette dokumenterer avhengighets- og byggekontroll;
kjøretidstesting på hver plattform gjenstår.

## Kilder for versjonsvalgene

- [Eclipse 2026-09, datert repository](https://download.eclipse.org/releases/2026-09/202609091000/)
- [Tycho-krav til Maven og Java](https://tycho.eclipseprojects.io/doc/latest/tycho-compiler-plugin/plugin-info.html)
- [Maven Enforcer](https://maven.apache.org/enforcer/maven-enforcer-plugin/usage.html)
- [JaCoCo-endringer](https://www.jacoco.org/jacoco/trunk/doc/changes.html)
