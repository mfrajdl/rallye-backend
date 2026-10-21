# Testování bez lokálního Dockeru

## Uzavření etapy 2A

Etapa 2A – lokální autentizace je dokončena na základě následujícího ověření:

- Automatické testy bez databáze: Codex provedl `mvnw.cmd verify` s JDK 21;
  68 testů, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS a sestavený JAR.
- PostgreSQL integrační testy: uživatel potvrdil úspěšné `mvnw.cmd -Pit verify`
  proti vyhrazené databázi `rallye_test`, včetně `LocalMailboxIT`. Jde o uživatelem
  potvrzený běh, nikoli vlastní PostgreSQL běh Codexu.
- Manuální testy: uživatel potvrdil úspěch všech scénářů v
  `postman/Rallye-Auth-2A.postman_collection.json`: registrace, aktivace účtu,
  přihlášení, aktuální účet `/api/v1/auth/me`, práce se session cookies a CSRF,
  změna hesla a zachování aktuální relace, odhlášení, žádost o reset, reset hesla,
  zneplatnění staré relace a přihlášení novým heslem.
- Prošly také všechny negativní scénáře kolekce: validace vstupů a neplatné tokeny
  (400), chybné přihlášení a nepřihlášený/odhlášený účet (401), nesprávný CSRF
  token (403). Manuální běh provedl uživatel; Codex kolekci proti backendu nespouštěl.

Před produkčním nasazením zbývá nahradit lokální souborové doručování skutečným
poskytovatelem SMTP nebo e-mailovým API a ověřit skutečné doručování aktivačních
a resetovacích zpráv. Dosavadní automatické a manuální testy reálné doručování
neověřují. Tento otevřený provozní bod nebrání uzavření etapy 2A.

Postup pro existující kontejner `rallye-postgres-1` je v [postgresql-test-setup.md](postgresql-test-setup.md). Používá oddělenou DB/roli `rallye_test` a tunel 5434 bez změny HBA, aplikačních ACL, Docker portů nebo OCI firewallu. PUBLIC CONNECT/TEMPORARY jsou přijatelné; ověřuje se zákaz přístupu k aplikačním tabulkám v obou DB.

## Běžné testy bez databáze

Etapa 3b.1 přidává AdminServiceTest, AdminHttpTest, BootstrapScriptTest a auditní
kontroly v AuthServiceTest. Podrobnosti API a ručního ověření jsou v
[administration-3b1.md](administration-3b1.md). Dříve potvrzené výsledky 2A nahoře
nejsou potvrzením celého integračního běhu s V2. Uživatelův běh měl 21/23
úspěšných případů; dva rollback testy selhaly na očekávání nepřeložené výjimky.
Následné dostupné Failsafe XML reporty uvádějí 24/24 úspěšných IT bez chyb.
Jde o nalezené výsledky před závěrečnými opravami, nikoli nový DB běh Codexu.

Lokální `mvnw.cmd verify` s JDK 21 po implementaci 3b.1: **BUILD SUCCESS,
90 testů, 0 failures, 0 errors, 0 skipped**, sestavený JAR při závěrečné kontrole 3b.1. AdministrationIT
se zkompiloval, ale Codex jej podle dohody nespouštěl. Postman kolekce má staticky
zkontrolovaný JSON, 33 požadavků vůči controllerům a syntaxi 55 skriptů;
uživatel potvrdil manuální ověření 3b.1: přihlášení ADMIN 200, čtení auditu 200,
grant/revoke 204, zákaz vlastní role 403 SELF_ROLE_CHANGE_FORBIDDEN, replay 204,
konflikt operace se stejným klíčem 409 IDEMPOTENCY_CONFLICT a audit bootstrapu,
přihlášení a změn rolí.

AuditTransactionTest bez DB ověřuje skutečný Spring překlad IllegalStateException
z repository na InvalidDataAccessApiUsageException a vyvolání transakčního
rollbacku. AdministrationIT ověřuje identitu injektované příčiny, chybu po skutečném
INSERTu auditu a nezměněný stav DB čtený po selhání nezávislou transakcí. Nový
test selhání po zápisu idempotence musí vrátit zpět roli, audit i příkaz. Opravená
sada při závěrečné kontrole obsahuje 26 IT, spouští ji pouze uživatel příkazem
`.\mvnw.cmd -Pit verify`. Dva nové případy ověřují nekonzistentní bootstrap příznak
a chybějící řídicí řádek. Uživatel následně potvrdil úspěšný běh celé rozšířené sady pomocí `.\mvnw.cmd -Pit verify` nad rallye_test: BUILD SUCCESS. Etapa 3b.1 je ověřena; nejde o vlastní PostgreSQL běh Codexu.
Původní reporty v target/failsafe-reports nebyly lokálním verify přepsány.

JDK 21 a Maven Wrapper stačí:

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean verify
```

Surefire spouští `*Test`/`*Tests`. `HelloControllerTest` ověřuje mapování a odpověď endpointu přes samostatné MockMvc bez Spring Boot kontextu. `TestDatabaseSettingsTest` ověřuje allowlist a odmítnutí chybějících či nebezpečných údajů bez připojení. Žádné heslo, tunel ani DB nejsou potřeba; nepoužívá se H2 jako náhrada PostgreSQL.

Původní `@SpringBootTest contextLoads` je přesunut do `RallyeBackendApplicationIT`. Výchozí `verify` integraci neprovádí; úspěch není potvrzením migrací, připojení ani celé aplikace. Etapa 2A přidává testy identity, Argon2id, limitů pokusů, SMTP adaptéru a MVC/Spring Security bez databáze. Podrobnosti kontraktu jsou v [authentication-2a.md](authentication-2a.md).

## PostgreSQL integrační testy (výslovné spuštění)

Integrační testy v tomto projektu spouští uživatel ručně ve svém PowerShellu s funkčním tunelem a testovacími údaji. Codex testy připravuje, ale PostgreSQL přístup pro sebe nezajišťuje a integrační běhy nespouští. Při potřebě nového ověření předá níže uvedený příkaz a počká na výsledek. Kompilaci a testy bez databáze nadále spouští Codex.

```powershell
.\mvnw.cmd -Pit verify
```

Failsafe je zapojen pouze v Maven profilu `it` a spouští `*IT` ve fázích integration-test a verify. Samotné `-Pit test` integrační testy nespustí. IT jsou zatím ve stejném testovacím source rootu; jmenná konvence a explicitní Failsafe profil oddělují běh bez dalších frameworků. Také běh z IDE musí zachovat initializer a profil `it`; neodstraňovat ochranu kvůli pohodlí. Použít `verify`, ne samotné `integration-test`, aby Maven správně vyhodnotil výsledek.

AdministrationIT také používá TestDatabaseInitializer a výhradně rallye_test.
Flyway aplikuje V1/V2 do unikátního vlastního schématu it_admin_<UUID>; jen v něm
test provádí skutečný SQL bootstrap, souběhy a negativní DB operace. Na konci
ověří název, DB a roli a odstraní pouze toto schéma s vlastními fixture. Neprovádí
Flyway clean, nemaže public ani společný bootstrap/účty. Testovací role potřebuje
CREATE ve vlastní DB. Při pádu procesu může schéma zůstat k ručnímu posouzení
správcem; automaticky se nehledají ani nemažou schémata z dřívějších běhů.

Po schválení správce připraví databázi a roli `rallye_test` ve stávajícím PostgreSQL 17. Role nemá SUPERUSER, CREATEDB, CREATEROLE, REPLICATION, BYPASSRLS ani členství v aplikačních rolích. Má migrační oprávnění pouze uvnitř testovací DB. Žádné explicitní granty do `rallye`/`rallye_dev` se nepřidávají. PUBLIC CONNECT a TEMPORARY se akceptují: připojení k aplikační DB může uspět, čtení či změny jejích tabulek nikoli. Read-only skript `25-verify-application-access.sql` se spustí v obou aplikačních DB; kontroluje tabulkové i sloupcové granty, views, sekvence, CREATE ve schématech a dostupné SECURITY DEFINER rutiny. Při nálezu se zastaví bez opravy ACL. Data a konfigurace stávajících DB zůstávají zachované. Žádný cloudový krok zatím nebyl proveden.

Po přípravě infrastruktury použít nový SSH tunel, například z lokálního `127.0.0.1:5434` na port testovací PostgreSQL na serveru. Tunel na 5433 používaný pro vývoj lze ponechat, ale IT na něj nesmějí dědit vývojovou konfiguraci. Číslo portu samo není bezpečnostní hranice. Obecný příklad (nahraď zástupné hodnoty):

```powershell
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:5434:127.0.0.1:<port-test-postgresql> <ssh-uzivatel>@<ssh-host>
```

V samostatné testovací relaci PowerShellu bez přepsání Spring konfigurace:

```powershell
$env:RALLYE_TEST_DB_ENABLED = 'true'
$env:RALLYE_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:5434/rallye_test'
$env:RALLYE_TEST_DB_USERNAME = 'rallye_test'
$testCredential = Get-Credential -UserName rallye_test -Message 'Samostatné testovací údaje'
$env:RALLYE_TEST_DB_PASSWORD = $testCredential.GetNetworkCredential().Password
Remove-Variable testCredential
.\mvnw.cmd -Pit verify
```

Po práci odstranit testovací heslo a opt-in proměnnou z relace. `.env` se automaticky nenačítá. Testy nečtou vývojové `RALLYE_DB_PASSWORD` a nemají záložní přístupové údaje.

### Ochrana před připojením do nesprávné databáze

`application-it.properties` nemá fallback k vývojové DB. `TestDatabaseInitializer` před obnovením Spring kontextu:

1. Vyžádá přesné `RALLYE_TEST_DB_ENABLED=true` a všechny tři testovací údaje.
2. Odmítne zděděné `SPRING_DATASOURCE_*`, `SPRING_FLYWAY_*`, `SPRING_JPA_*`, `SPRING_SQL_*`, `SPRING_CONFIG_*`, `SPRING_APPLICATION_JSON` a odpovídající JVM system properties. Použít čistou relaci, nepřenášet alternativní Spring konfiguraci do testů.
3. Povolí pouze `jdbc:postgresql://<jediny-host>:<port>/rallye_test`, bez query parametrů, kódovaných názvů, alternativních hostů a embedded údajů. Povolen je DNS/IPv4 host s explicitním portem 1–65535. IPv6 a URL parametry nejsou v první verzi podporovány. Tunel a lokální CI service nevyžadují parametry SSL v URL; veřejné přímé připojení k DB nepoužívat.
4. Povolí pouze roli `rallye_test`, neprázdné samostatné heslo. Před jakoukoli migrací naváže připojení a provede pouze read-only `select current_database(), current_user`; obě hodnoty musí být `rallye_test`. Chyba preflight nevypisuje řetězec JDBC či heslo.
5. Připne datasource, Hikari a Flyway na zkontrolované údaje. Zakáže Flyway clean/baseline-on-migrate, SQL init a automatické Hibernate DDL. Potom teprve může Spring vytvořit datasource a spustit Flyway.

Úspěšný IT potvrzuje reálnou datasource a PostgreSQL 17. Flyway nyní aplikuje `V1__user_accounts.sql` výhradně v testovací DB. `IdentityIT` ověřuje registraci, tokeny včetně souběžného spotřebování, Argon2 hash, relace a jejich odvolání, expirace i skutečný HTTP Bearer tok. SMTP nahrazuje zachytávací adaptér, čas řídí testovací Clock. Nezavádí se automatické mazání DB ani reset schématu. Testy mají vlastní unikátní účty a cílený úklid pouze vlastních fixture záznamů po skončení; nedotýkají se cizích dat ani Flyway historie. Při použití přetrvávající DB spouštět jednu sadu v daném čase.

Kontroly chrání před běžným chybným nastavením, nejsou sandboxem pro libovolný testovací Java kód. Nové DB testy musí použít stejný initializer; omezená role a ověřená práva k aplikačním objektům jsou další ochrana. HBA se nemění. Záměrné obcházení přes vlastní datasource/JDBC nebo odstraňování kontrol není přípustné. Produkční `application.properties` není měněno a nesmí se kvůli testům oslabovat.

Bez opt-in nebo údajů `-Pit verify` **selže**, nesmí vypsat úspěch se skrytě přeskočenou integrací. Chybějící infrastruktura je zdokumentované omezení; běžný `verify` zůstává dostupný bez ní.

## Provoz a pozdější CI

Použije se existující OCI kontejner `rallye-postgres-1`, bez dalšího cloudového kontejneru. Testy sdílejí prostředky serveru, proto jedna sada v danou chvíli a žádné zátěžové testy. Role má uvnitř `rallye_test` práva pro Flyway; není to produkční runtime role.

Později lze zavést PostgreSQL 17 service v GitHub Actions na Ubuntu runneru, nezávislý na OCI a bez Dockeru na Windows. Zatím se workflow nevytváří ani nenasazuje. Použil by stejný testovací kontrakt `RALLYE_TEST_DB_*`, vlastní nesuperuser roli a `./mvnw -Pit verify`; žádná cloudová produkční tajemství. Oficiální image přes `POSTGRES_USER` standardně vytváří superuser, proto bootstrap účet nepředávat testované aplikaci.

Podklady: [PostgreSQL oprávnění](https://www.postgresql.org/docs/17/ddl-priv.html), [kontroly efektivních práv](https://www.postgresql.org/docs/17/functions-info.html), [Failsafe](https://maven.apache.org/surefire/maven-failsafe-plugin/integration-test-mojo.html).

## Lokální schránka bez SMTP

LocalFileAccountMailTest ověřuje zprávy, výběr adaptéru a požadavek na loopback bez databáze. LocalMailboxIT ověřuje celý tok registrace, jednorázové aktivace, přihlášení a odhlášení přes souborovou schránku a chráněnou databázi rallye_test. Používá dočasný adresář, žádné SMTP a uklízí pouze vlastní testovací účet. Spouští jej uživatel standardním příkazem integračního profilu. Uživatel dne 21. 10. 2026 potvrdil úspěšné `.\mvnw.cmd -Pit verify` proti `rallye_test` (`BUILD SUCCESS`), včetně nového testu `LocalMailboxIT`. Jde o uživatelem potvrzený běh, nikoli vlastní běh Codexu. Skutečné SMTP doručování zůstává odloženo před nasazení.
