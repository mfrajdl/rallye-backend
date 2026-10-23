# Testování bez lokálního Dockeru

## 3b.4b – služba a API rušení účtů

**Závěrečný stav: 3b.4b i celá 3b.4 dokončeny.** Uživatel po review potvrdil
`mvnw.cmd verify` – BUILD SUCCESS, 217 testů, `mvnw.cmd -Pit verify` –
BUILD SUCCESS ve svém prostředí a všechny plánované ruční scénáře.
Níže popsaná překážka prostředí Codexu je historický průběh review;
nový PostgreSQL běh uživatel následně úspěšně ověřil. Nejde o PostgreSQL běh Codexu.

Závěrečné review viz [review-3b4b.md](review-3b4b.md): bez nalezené funkční
chyby. Uživatel potvrdil ruční Postman ověření vlastní/ADMIN cesty, session,
nové registrace stejného e-mailu, replay, 404/403/409, zachování osiřelé posádky
a jejího převzetí ADMIN se zvýšením verze. Nové `-Pit verify` v prostředí Codexu
provedlo 217 testů bez DB bez chyby, ale integrační část správně odmítla chybějící
testovací opt-in/konfiguraci (18 chyb inicializace/kontextu, žádný ověřený DB běh).
Předcházelo tomu selhání JVM kvůli paměti Windows; použité dočasné limity jsou
popsány v review. Dřívější uživatelský úspěch 112 IT je historický výsledek,
nikoli výsledek tohoto pokusu Codexu. Potřebné opakování následně provedl
a úspěšně potvrdil uživatel, jak uvádí závěrečný stav výše.
Závěrečné samostatné `mvnw.cmd verify` s dočasně omezenou pamětí JVM následně
prošlo: **BUILD SUCCESS, 217 testů, 0 failures/errors/skipped**, JAR sestaven.

Po doplnění diagnostiky ruční chyby 500 prošlo nové `mvnw.cmd verify` s JDK 21:
**217 testů, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**. HTTP testy
ověřují bezpečné logování obecné i explicitní aplikační 500, selhání filtru
a zachování SQL příčiny chyby kontroly účastí. Log nesmí obsahovat původní
zprávy výjimek, hesla, e-maily ani tokeny. Konkrétní příčina ručního selhání
nebyla doložena; následné uživatelem potvrzené ruční scénáře již prošly.
Podrobnosti v [account-cancellation-3b4b.md](account-cancellation-3b4b.md).
Níže uvedený uživatelský PostgreSQL úspěch předcházel této diagnostické úpravě.

Nové AccountCancellationServiceTest, AccountCancellationHttpTest,
LocalCancellationIdentityCheckTest a PreRaceAccountParticipationFactsTest
ověřují autorizaci, heslo, audit, replay, CSRF/Bearer, rate limiting a odmítnutí
nepodporovaného schématu. AuthServiceTest doplňuje chybějící účet po získání
zámku; CommandAccountsTest kontroluje bezpečný formát ADMIN cancellation.

AccountCancellationServiceIT přidává 21 testů proti izolovanému schématu
`it_cancel_service_<uuid>` a soukromému dočasnému adresáři fotografií. Ověřuje
obě cesty rušení, PENDING_EMAIL/DISABLED, skutečné odstranění relací/tokenů,
opětovnou registraci, osiřelé posádky a zachované soubory, převody na obou
stranách, historii přes testovací adaptér, posledního ADMIN, idempotenci,
rollback po zápisu auditu a při odmítnutí úklidu, neznámé/nedostupné schéma,
zmizení účtu mezi lookup/zámkem a souběhy s převody/registrací/ADMIN kapitánstvím.

Uživatel potvrdil `.\mvnw.cmd -Pit verify` proti `rallye_test`: **BUILD SUCCESS**.
Failsafe report dokládá **112 integračních testů, 0 failures, 0 errors, 0 skipped**,
včetně 21 nových scénářů 3b.4b. Jde o uživatelem provedený běh, nikoli běh Codexu.
Ruční scénáře jsou ve složce 13 Postman kolekce, návod a omezení v
[account-cancellation-3b4b.md](account-cancellation-3b4b.md).
Ruční Postman ověření i závěrečný úspěšný PostgreSQL běh v uživatelově prostředí
jsou potvrzeny. Celá 3b.4 je uzavřena.

Codex provedl `mvnw.cmd verify` s JDK 21: **BUILD SUCCESS, 211 testů bez DB,
0 failures, 0 errors, 0 skipped**. PostgreSQL běh následně potvrdil uživatel.
Kolekce prošla JSON parsery Node.js a PowerShellu,
strukturální kontrolou Collection v2.1 a syntaktickou kontrolou 147 skriptů;
obsahuje 87 požadavků, z toho 11 nových. Původní požadavky, proměnné,
autentizační nastavení i skripty byly při kontrole porovnány s HEAD a zachovány.

## 3b.4a – databázový základ rušení účtů

Nový `CommandAccountsTest` ověřuje klasifikaci idempotentních operací, význam
UUID, vyhledávání e-mailu v JSON hodnotách a odmítnutí neznámých formátů.
`AccountCancellationIT` přidává 18 PostgreSQL scénářů pro migraci V5→V6,
omezení ANONYMIZED, uvolnění e-mailu, fyzické smazání, odstranění přihlašovacích
prostředků, ochranu posledního ADMIN, zachování posádek/fotografií a historie,
ukončení převodů na obou stranách, úklid nových i legacy idempotentních záznamů
včetně expirovaných, zachování nesouvisejících a rollback.

PostgreSQL testy Codex nespouští. Uživatel provedl `.\mvnw.cmd -Pit verify`
se stávajícími ochrannými proměnnými a tunelem do `rallye_test` na 5434.
Závěrečný běh včetně V6 uživatel potvrdil: **BUILD SUCCESS, 91 PostgreSQL
integračních testů bez chyb**. Viz [account-cancellation-3b4a.md](account-cancellation-3b4a.md).

Codex provedl `mvnw.cmd verify` s JDK 21: **BUILD SUCCESS, 184 testů bez DB,
0 failures, 0 errors, 0 skipped**. Integrační testy jsou kompilovány, nikoli spuštěny.

Následný uživatelský PostgreSQL běh měl 90/91 úspěšných testů. Jediné selhání
bylo v očekávání obalové výjimky při ochraně posledního ADMIN: report doložil
`PSQLException`, SQLSTATE `P0001`, `Cannot remove the last active administrator`
z `guard_admin_role()`, přeložené Springem na `UncategorizedSQLException`.
Opravený test kontroluje původní SQLSTATE a konkrétní trigger pro odebrání role,
anonymizaci i smazání účtu; po každém odmítnutí ověřuje zachování účtu, role,
jediného aktivního ověřeného ADMIN a `bootstrapped`. Také ověřuje povolenou
anonymizaci druhého ADMIN. Produkční kód ani migrace se kvůli této opravě nemění.
Opakovaný PostgreSQL běh včetně opraveného testu uživatel potvrdil jako úspěšný
(91/91); etapa 3b.4a je ověřena. Nejde o vlastní PostgreSQL běh Codexu.

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

## Ověření etapy 3b.2

Nové PhotoFilesTest/CrewServiceTest/CrewHttpTest ověřují validaci, skutečné
formáty a rozměry obrázků, souborový úklid a jeho zámek, oprávnění, verze,
kapitánství, HTTP a transakční hranice bez DB. Regrese 2A/3b.1 zůstávají zapojené.

CrewsIT používá původní TestDatabaseInitializer a pouze rallye_test. Má vlastní
schéma it_crews_<UUID>, dočasný soukromý adresář a pro regresi V2→V3 ještě
*_upgrade schéma. Na konci ověří databázi, roli a jméno a odstraní pouze vlastní
fixture schémata/soubory. Nepoužívá Flyway clean ani vývojovou DB. Testy pokrývají
reálný rollback po zápisu auditu/idempotence, původní příčinu výjimky, souběhy,
CRUD, soukromé fotografie, kapitánství a zachování původních ADMIN klíčů migrací.

Běh proti PostgreSQL po V3 a ruční ověření Postman složek 06/07 zatím čekají
na uživatele. Spustit v jeho čisté testovací PowerShell relaci:

```powershell
.\mvnw.cmd -Pit verify
```

Ochrany RALLYE_TEST_DB_*, ověření skutečné DB/role, zákaz clean/baseline/DDL
zůstávají beze změny. Podrobný scénář a API jsou v [crews-3b2.md](crews-3b2.md).

Vlastní závěrečný běh Codexu 22. 10. 2026: **BUILD SUCCESS**, 111 testů bez DB,
0 failures/errors/skipped, JDK 21.0.12.1, sestavený JAR. Nových 13 CrewsIT
je zkompilováno; očekávaná celá PostgreSQL sada má 39 případů a dosud nebyla
po změnách 3b.2 potvrzena. Postman Collection v2.1 ověřena podle oficiálního
schématu: 60 požadavků a syntaxe 94 skriptů; běh kolekce zůstává ruční.

### Oprava přípravy rollback testů 3b.2

Uživatelův PostgreSQL běh měl 39 případů, 0 failures a 3 errors. TXT i XML
Failsafe reporty ukazují IllegalTransactionStateException již při přípravě
Mockito doAnswer(...).when(audit).crew(...), na původních řádcích CrewsIT
166/192/202. Přes AuditService Spring CGLIB proxy se ještě před Mockito spy
spustí TransactionInterceptor a kontrola Propagation.MANDATORY; test v tomto
okamžiku správně nemá obchodní transakci. Potlačené InvalidUseOfMatchersException
jsou následkem přerušeného nastavení spy. Testované operace se vůbec nezavolaly.

Produkční AuditService a jeho MANDATORY se nemění. CrewService vytváří obchodní
transakce pomocí TransactionTemplate a za normálního běhu do nich audit vstupuje.
CrewsIT nyní vkládá selhání do AuditStore.appendCrew, který nemá transakční
precondition při nastavování spy. Při operaci nejprve provede skutečný INSERT
a teprve potom vyhodí jedinečnou testovací výjimku. Kontrola zachovává původní
příčinu výjimky, již ukončenou transakci, DB stav, audit, nepoužitý idempotentní
klíč a původní fotografii/verzi i počet souborů. Kapitánství se navíc po chybě
úspěšně zopakuje se stejným klíčem. Testy nemají vnější testovací transakci.

AuditTransactionTest nově bez DB ověřuje, že konfigurace repository spy nevolá
JDBC, skutečná služba MANDATORY nadále odmítá volání mimo transakci a vložené
selhání po auditním JDBC zápisu vyvolá jediný rollback vnější transakce. Používá
skutečné Spring proxy a HibernateJpaDialect; nenahrazuje PostgreSQL ověření dat.
Cílených 5 transakčních testů prošlo. Nový databázový běh stále provádí uživatel:

```powershell
.\mvnw.cmd -Pit verify
```

Úspěšné opakování opravené sady 39 IT zatím není potvrzené.

Po opravě přípravy testů: vlastní běh Codexu s JDK 21.0.12.1, mvnw.cmd verify,
**BUILD SUCCESS**, 113 testů bez DB, 0 failures/errors/skipped; JAR sestaven
a všech 39 IT zkompilováno. PostgreSQL testy Codex nespouštěl; opakování po
této opravě zůstává na uživateli.

### Uzavření 3b.2 – konečné výsledky

Dostupné závěrečné Surefire reporty obsahují 118 testů bez DB a Failsafe reporty
39 integračních testů; obě sady mají 0 failures/errors/skipped. PostgreSQL běh
provedl uživatel, nikoli Codex. Konečný výsledek nahrazuje výše uvedené průběžné
stavy včetně původních tří chyb přípravy auditního spy.

Postman kolekce je staticky ověřená proti oficiálnímu schématu v2.1, má 63
požadavků a 99 validních skriptů. Samostatné scénáře DELETE a administrátorské
změny kapitána obsahují aktuální kontrakt version/Idempotency-Key. Toto statické
ověření se nezaměňuje s kompletním manuálním průchodem všech scénářů.

## Testy interního základu 3b.3a

Nové `CrewTransferLifecycleTest` a `CrewTransferTransactionTest` pokrývají
stavové operace, sedmidenní expiraci, verze a povinnou vnější transakci.
Codex provedl s JDK 21 `.\mvnw.cmd verify`: BUILD SUCCESS, 137 testů,
0 failures, 0 errors, 0 skipped (z toho 19 nových případů). Nové IT se zkompilovaly.
`CrewTransfersIT` obsahuje 9 testů nad izolovaným schématem rallye_test včetně
databázových omezení, souběhu a skutečného rollbacku. Jejich spuštění je podle
AGENTS.md ponecháno uživateli. Uživatel potvrdil `.\mvnw.cmd -Pit verify`
s výsledkem BUILD SUCCESS; dostupný Failsafe report uvádí 48 testů, 0 errors,
0 failures a 0 skipped. Jde o uživatelem potvrzený běh proti rallye_test,
nikoli PostgreSQL běh Codexu.
Rozsah a návaznosti: [crew-transfers-3b3a.md](crew-transfers-3b3a.md).

## Testy založení převodu a e-mailu 3b.3b

Codex provedl `.\mvnw.cmd verify` s JDK 21: BUILD SUCCESS, 159 testů,
0 failures, 0 errors, 0 skipped. Nové unit/HTTP případy pokrývají založení,
resend, replay, konflikty, autorizaci, CSRF, rate limiting, sestavení zpráv
a odesílání až po commitu. PostgreSQL testy se zkompilovaly.

CrewTransfersIT nyní obsahuje 17 případů (9 původních + 8 nových). Nové případy
ověřují skutečný rollback žádosti, rotace a evidence idempotence bez odeslání
zprávy, hashování, opakování, souběh a neplatnost původního tokenu po rotaci.
Uživatel potvrdil `.\mvnw.cmd -Pit verify` proti rallye_test: BUILD SUCCESS.
Dostupný Failsafe report uvádí 56 testů, 0 errors, 0 failures a 0 skipped,
včetně všech 17 případů CrewTransfersIT. Jde o uživatelem potvrzený běh, nikoli běh Codexu.
Postman postup: [crew-transfers-3b3b.md](crew-transfers-3b3b.md).

Regrese pořadí kontrol: unit test potvrzuje přednost IDEMPOTENCY_CONFLICT
před doménovou kontrolou verze; verify prošlo se 159 testy. Nový PostgreSQL
případ ověřuje stejný scénář po skutečné editaci posádky a prošel v uživatelem potvrzeném běhu.
Postman konflikt používá zachycený klíč úspěšného Create, nikdy negeneruje nový.

## Rozhodnutí převodů a ochrany 3b.3c

Codex provedl s JDK 21 mvnw.cmd verify: BUILD SUCCESS, 176 testů bez DB,
0 failures, 0 errors, 0 skipped. Testy zahrnují nové HTTP endpointy, CSRF,
Bearer, oprávnění, auditní transakci, bezpečné otisky, replay a ochrany posádek.

CrewTransfersIT nyní připravuje 32 případů (17 původních a 15 nových), včetně
všech zadaných souběhů, expirace po zámku, aktuálních oprávnění a skutečného
rollbacku auditu, vlastnictví i evidence idempotence. Uživatel potvrdil
úspěšné .\mvnw.cmd -Pit verify proti rallye_test. Failsafe report uvádí 71 testů,
0 errors, 0 failures a 0 skipped. Jde o uživatelem potvrzený běh, nikoli běh Codexu.
Podrobnosti a Postman: [crew-transfers-3b3c.md](crew-transfers-3b3c.md).

## Závěrečná revize převodů 3b.3d

Codex provedl .\mvnw.cmd verify s JDK 21: BUILD SUCCESS, 176 testů bez DB,
0 failures, 0 errors, 0 skipped. Do CrewTransfersIT přibyly pouze dva chybějící
případy: souběh resend/accept a přesná 24hodinová retence. Původní test
reject/cancel byl posílen o replay a nový klíč po odstranění posádky.
Uživatel dne 2026-10-23 potvrdil .\mvnw.cmd -Pit verify proti rallye_test:
BUILD SUCCESS. Failsafe report uvádí 73 IT celkem, 0 errors, 0 failures,
0 skipped, včetně 34 CrewTransfersIT. Jde o uživatelem potvrzený běh, nikoli
běh Codexu. Nové případy i posílený replay test tím prošly.
Postman má 76 požadavků a 125 syntakticky ověřených skriptů, dva výslovné
GET scénáře přístupu obou kapitánů a replay založení bez generování nového klíče.
Uživatel potvrdil ruční scénáře přístupu nového a původního kapitána ověřené
již v 3b.3c. Nejde o vlastní manuální běh Codexu. Revize 3b.3d a převody 3b.3
jsou uzavřené; 3b.4, 3b.5, 3b.6 a 3c zůstávají plánované.
[Revize 3b.3d](crew-transfers-3b3d.md).

## Ověření etapy 3b.5

Codex po cílené opravě P2 spustil `mvnw.cmd verify`: BUILD SUCCESS,
234 testů bez databáze, 0 failures, 0 errors a 0 skipped (JDK 21).
CSV a HTTP testy ověřují autorizaci, hlavičky, filtry, escapování, CET/CEST,
read-only snapshot a oddělený best-effort audit. Retenční testy ověřují konfiguraci,
pořadí operací, rollback a plán 03:15 Europe/Prague.

Původních 14 scénářů AuditMaintenanceIT pokrývalo export, souběh, přesnou hranici
retence, legacy idempotenci, rollback a V7. Uživatel potvrdil úspěšné
`.\mvnw.cmd -Pit verify` nad rallye_test, včetně těchto nových scénářů.
Jde o uživatelem potvrzený výsledek, nikoli PostgreSQL běh Codexu.
Celkový počet integračních testů nebyl uveden. Uživatel potvrdil také ruční CSV
export, filtrování podle parametrů, vytváření AUDIT_EXPORT a zamítnutí běžného
uživatele.

Oprava P2 přidává CommandRetentionEvidenceTest a rozšiřuje AuditMaintenanceIT na
17 scénářů. Legacy CREW_CAPTAIN bez related_account_ids nelze spolehlivě spojit
s konkrétní auditní událostí; retence proto skončí rollbackem i při existenci
jiné či zdánlivě odpovídající změny stejné posádky. Úplná metadata nadále fungují
bez auditní historie a umožňují následný úklid při rušení účtu. Backfill známého
legacy ADMIN_ROLE a jeho rollback při chybě AUDIT_CLEANUP zůstávají pokryté.
Dosavadní účetní, převodní a idempotentní testy bez DB prošly v celé sadě.
Uživatel následně potvrdil úspěšné `.\mvnw.cmd -Pit verify` proti rallye_test
také po opravě P2, včetně upravené sady 17 scénářů AuditMaintenanceIT.
Jde o uživatelem potvrzený výsledek, nikoli vlastní PostgreSQL běh Codexu.
Celkový počet integračních testů nebyl uveden. Integrační ověření opravy P2
je tím uzavřeno.
Profil it vypíná časový plánovač, testy službu volají explicitně v izolovaném
schématu. Všechny dosavadní ochrany připojení zůstávají zachovány.
Podrobnosti: [audit-3b5.md](audit-3b5.md). 3b.6 a 3c zůstávají plánované.
