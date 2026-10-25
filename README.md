# Rallye Backend

Backend soukromé aplikace pro vícestupňové automobilové Rallye soutěže. Jedna Spring Boot aplikace, modulární monolit. Závazná funkční specifikace je [verze 0.7.0](docs/Zavody_specifikace_v0.7.0.md). Lokální autentizace je popsaná v [etapě 2A](docs/authentication-2a.md).

Etapy 2A a 3b jsou dokončené. Aktuální rozsah a navazující etapa 3c jsou v [backendovém plánu](docs/backend-plan.md); výsledky společné regrese a provozní omezení v [závěrečné kontrole 3b.6](docs/review-3b6.md). PostgreSQL automatické testy používají pouze rallye_test; uživatelem provedená ruční regrese běžela proti rallye_dev.

Aktuální [specifikace 0.8.0](docs/Zavody_specifikace_v0.8.0.md) zavádí D09/D10, která nahrazují D04/D05 a D03: jediná neměnná identita, LOCAL zrušení s heslem, externí zrušení s platnou relací a výslovným potvrzením bez reautentizace. Nové DB omezení implementuje připravená [3c.3a](docs/single-identity-3c3a.md), jejíž PostgreSQL ověření potvrdil uživatel jako BUILD SUCCESS. Externí potvrzení podle D10 implementuje [3c.3b](docs/account-cancellation-3c3b.md), s uživatelem potvrzeným PostgreSQL ověřením; LOCAL manuální ověření je potvrzené (Postman 19: 35/0, SELF 204 a odvolání přístupu); skutečné GOOGLE/APPLE zrušení čeká na HTTPS testovací prostředí; [technický návrh a plán 3c.1–3c.5](docs/external-auth-3c.md). Protokolový základ [3c.1](docs/external-auth-3c1.md) je dokončený a automaticky otestovaný, úspěšné PostgreSQL ověření potvrdil uživatel; skutečné ověření poskytovatelů dosud nebylo provedeno.

Webovou registraci a přihlášení přidává [3c.2](docs/external-auth-3c2.md); etapa je implementačně dokončená a lokálně otestovaná (verify 271 testů, uživatelem potvrzené PostgreSQL testy a 6/6 asercí Postman 16). Skutečný Google/Apple login a pozitivní Postman složka 17 zůstávají neověřené a vyžadují HTTPS testovací prostředí. Poskytovatelé jsou standardně vypnutí.

## Požadavky

- JDK 21 (včetně `javac`), nastavené `JAVA_HOME` a `PATH`.
- Maven Wrapper v repozitáři; samostatná instalace Mavenu není nutná. Wrapper stahuje Maven 3.10.0 a závislosti, při prvním spuštění potřebuje síť.
- PostgreSQL 17: lokální vývoj používá existující `rallye_dev` na Oracle Cloud přes SSH tunel.
- SSH klient a přístup k serveru; hesla a klíče poskytuje správce mimo repozitář.

Použité závislosti v `pom.xml`: Spring Boot 4.1.1, Data JPA, Flyway včetně PostgreSQL podpory, Validation, Web MVC Spring Security, Mail, Bouncy Castle pro Argon2id a odpovídající testovací závislosti. PostgreSQL JDBC driver je runtime závislost.

## Lokální spuštění na Windows (PowerShell)

V aktuální relaci nastav skutečnou cestu k JDK 21:

```powershell
$env:JAVA_HOME = 'C:\cesta\k\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd -version
```

V samostatném terminálu otevři tunel. Následující hodnoty nahraď údaji od správce; vzdálený port musí odpovídat publikovanému portu PostgreSQL na serveru:

```powershell
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:5433:127.0.0.1:<vzdaleny-port-postgresql> <ssh-uzivatel>@<ssh-host>
```

Tunel ponech běžet. Konfigurace předpokládá JDBC URL `jdbc:postgresql://127.0.0.1:5433/rallye_dev` a databázového uživatele `rallye`.

| Proměnná prostředí | Význam |
| --- | --- |
| `RALLYE_DB_PASSWORD` | Povinné heslo databázového uživatele; bez výchozí hodnoty. |
| `JAVA_HOME` | Cesta k JDK 21 používanému Mavenem. |
| `SPRING_DATASOURCE_URL` | Volitelné přepsání vývojové JDBC URL; integrační testy používají výhradně `RALLYE_TEST_DB_*`. |
| `SPRING_DATASOURCE_USERNAME` | Volitelné přepsání výchozího uživatele `rallye`. |

Heslo nastav prostřednictvím bezpečné konfigurace prostředí nebo IDE. Pro jednorázové zadání bez hesla v historii PowerShellu lze použít:

```powershell
$dbCredential = Get-Credential -UserName rallye -Message 'Přihlašovací údaje k databázi Rallye'
$env:RALLYE_DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
Remove-Variable dbCredential
.\mvnw.cmd spring-boot:run
```

Proměnná je dostupná procesu aplikace; po práci ji lze odstranit pomocí `Remove-Item Env:RALLYE_DB_PASSWORD`. Soubor `.env` Spring Boot ani tento projekt automaticky nenačítá. Neukládej tajné údaje do verzovaných souborů.

Aplikace standardně naslouchá na portu 8080. Kontrola v druhém terminálu:

```powershell
Invoke-RestMethod http://localhost:8080/api/hello
```

Očekávaný text je `Rallye backend funguje`. Jde o testovací endpoint, nikoli ověření všech budoucích funkcí.

## Sestavení a testy

```powershell
.\mvnw.cmd clean verify
```

Výchozí `test` a `verify` spouští pouze testy bez databáze: kontrolu `/api/hello`, aplikační logiku identity, Argon2id, SMTP adaptér, Spring Security/CSRF a ochranná pravidla testovacího připojení. Nepotřebují heslo ani SSH tunel. Nemění to požadavek na PostgreSQL při běžném spuštění aplikace.

PostgreSQL integrační test načtení celé aplikace je oddělen jako `RallyeBackendApplicationIT` a spouští se výhradně `mvnw.cmd -Pit verify`. Vyžaduje výslovný opt-in `RALLYE_TEST_DB_ENABLED=true`, `RALLYE_TEST_DB_URL`, `RALLYE_TEST_DB_USERNAME` a `RALLYE_TEST_DB_PASSWORD`, databázi i roli `rallye_test` a bezpečné připojení. Bez nich selže před spuštěním Flyway. Databáze `rallye_dev` a `rallye` jsou zakázané. Pro běh musí být dostupná existující testovací infrastruktura a její samostatné údaje; tato práce ji nevytváří ani nepřekonfigurovává. Na Windows se kvůli testům neinstaluje Docker ani WSL.

Podrobný postup, bezpečnostní omezení a návrh pozdějšího CI jsou v [docs/testing.md](docs/testing.md). Testy ani konfigurace nesmějí vytvářet nebo měnit cloudovou infrastrukturu bez samostatného schválení.

Pouhé vytvoření JAR bez běhu testů:

```powershell
.\mvnw.cmd -DskipTests package
java -jar target/rallye-backend-0.0.1-SNAPSHOT.jar
```

Přeskočení testů nepotvrzuje funkčnost aplikace ani připojení. Na Linuxu používej `./mvnw`. Hibernate má `ddl-auto=validate`, schéma mění pouze Flyway. Etapa 2A přidává skutečnou migraci V1 pro účty a relace. Běžný start ji aplikuje na nakonfigurovanou databázi; integrační testy ji smějí aplikovat pouze na rallye_test.

## Dokumentace

- [Účty a lokální autentizace – etapa 2A](docs/authentication-2a.md)
- [Administrátorská oprávnění, audit a ruční SQL bootstrap – 3b.1](docs/administration-3b1.md)
- [Posádky, soukromé fotografie a kapitánství – 3b.2](docs/crews-3b2.md)

- [Závazná specifikace 0.8.0](docs/Zavody_specifikace_v0.8.0.md)
- [Historická specifikace 0.7.0](docs/Zavody_specifikace_v0.7.0.md)
- [Testovací prostředí](docs/testing.md)
- [Příprava testovací PostgreSQL na OCI](docs/postgresql-test-setup.md)
- [Pravidla pro Codex](AGENTS.md)
- [Návrh architektury, rizika, otázky a implementační etapy](docs/backend-plan.md)

Cloudové nasazení ani Docker konfigurace zatím nejsou připraveny. Nasazení vyžaduje samostatné zadání.

## Lokální registrace bez SMTP

Profil local ukládá aktivační a resetovací zprávy do soukromé schránky mimo repozitář a poslouchá jen na localhostu. Postup spuštění i celý registrační tok bez webového klienta jsou v [authentication-2a.md](docs/authentication-2a.md#lokální-registrace-bez-smtp-a-bez-webového-klienta). Výběr SMTP poskytovatele a ověření doručování jsou odloženy před nasazení; žádná další infrastruktura nyní není potřeba.
