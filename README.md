# Rallye Backend

Backend soukromé aplikace pro vícestupňové automobilové Rallye soutěže. Jedna Spring Boot aplikace, modulární monolit. Závazná funkční specifikace je [verze 0.6.0](docs/Zavody_specifikace_v0.6.0.md). Lokální autentizace je popsaná v [etapě 2A](docs/authentication-2a.md).

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

- [Specifikace 0.6.0](docs/Zavody_specifikace_v0.6.0.md)
- [Testovací prostředí](docs/testing.md)
- [Příprava testovací PostgreSQL na OCI](docs/postgresql-test-setup.md)
- [Pravidla pro Codex](AGENTS.md)
- [Návrh architektury, rizika, otázky a implementační etapy](docs/backend-plan.md)

Cloudové nasazení ani Docker konfigurace zatím nejsou připraveny. Nasazení vyžaduje samostatné zadání.

## Lokální registrace bez SMTP

Profil local ukládá aktivační a resetovací zprávy do soukromé schránky mimo repozitář a poslouchá jen na localhostu. Postup spuštění i celý registrační tok bez webového klienta jsou v [authentication-2a.md](docs/authentication-2a.md#lokální-registrace-bez-smtp-a-bez-webového-klienta). Výběr SMTP poskytovatele a ověření doručování jsou odloženy před nasazení; žádná další infrastruktura nyní není potřeba.
