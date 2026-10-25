# Testování bez lokálního Dockeru

> Aktuální pravidla: [specifikace 0.8.0](Zavody_specifikace_v0.8.0.md), D09 nahrazuje D04/D05 a D10 nahrazuje D03. Účet má jedinou neměnnou LOCAL/GOOGLE/APPLE identitu; link/unlink a dodatečný LOCAL jsou zakázané. LOCAL vlastní zrušení vyžaduje znovu heslo, externí zrušení platnou relaci a samostatné výslovné potvrzení, nikoli čerstvou reautentizaci. Níže uvedené dokončené etapy a výsledky zachycují tehdejší implementaci; DB omezení je nyní připravené v 3c.3a s uživatelem potvrzeným PostgreSQL ověřením BUILD SUCCESS; externí potvrzení je implementované a automaticky ověřené v 3c.3b; LOCAL ruční ověření je dokončené, skutečné GOOGLE/APPLE zrušení čeká na HTTPS. Dopady a nový plán: [external-auth-3c.md](external-auth-3c.md).


## Aktuální závěrečný stav po 3b.6 (24. 10. 2026)

Etapy 3b.6a–d a celý blok 3b jsou dokončené. Codex při závěrečné kontrole
znovu spustil verify s JDK 21.0.12.1: BUILD SUCCESS, 234 testů, 0 failures,
0 errors a 0 skipped; JAR sestaven. Dostupné reporty dřívějšího uživatelem
potvrzeného `-Pit verify` uvádějí 133 PostgreSQL IT, 0 failures/errors/skipped,
včetně čtyř Stage3bRegressionIT. Codex nyní nový PostgreSQL běh nespouštěl:
podle AGENTS.md jej provádí uživatel proti rallye_test. Tento rozdíl se nesmí
zaměnit za nynější úspěšné spuštění integrační sady Codexem.

Uživatel 24. 10. 2026 potvrdil všech šest ručních scénářů v Postmanu proti
rallye_dev. Přesný rozsah a odlišení nepotvrzených volitelných kroků je
v [postman-3b6.md](postman-3b6.md#10-záznam-výsledků-a-omezení).
Dočasné potíže s Cookie Jar, režimem cookie/bearer a starým tokenem byly
vyřešeny konfigurací klienta; nejsou důkazem chyby backendu.

Závěrečný report a omezení: [review-3b6.md](review-3b6.md).
Níže jsou zachované historické výsledky jednotlivých etap; staré počty testů
a tehdejší blokace nepopisují dnešní souhrnný stav. Commit ani push 3b.6
zatím neproběhl, následuje samostatné schválení. Implementace 3c nezačala.

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
jsou uzavřené; v okamžiku této revize zůstávaly 3b.4, 3b.5, 3b.6 a 3c plánované.
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
Podrobnosti: [audit-3b5.md](audit-3b5.md). V okamžiku uzavření 3b.5 byly 3b.6 a 3c plánované; aktuální stav je v souhrnu nahoře.

## 3b.6a–b – matice pokrytí a doplnění regrese

Codex spustil `mvnw.cmd verify` s JDK 21.0.12.1: BUILD SUCCESS,
234 testů, 0 failures, 0 errors a 0 skipped; JAR sestaven. Nová
Stage3bRegressionIT se zkompilovala, ale její čtyři testy se bez profilu it
nespouštěly. Přechodné selhání během úpravy unit testu bylo způsobeno původním
argumentem Mockito po změně testovacího hesla; opraven pouze test, finální
celá sada prošla. Nešlo o zjištěnou produkční chybu.

Přehled pravidel 2A a 3b.1–3b.5, konkrétních automatických kontrol a omezení je
v [coverage-3b6.md](coverage-3b6.md). Rozšiřuje se pouze chybějící pokrytí:

- Existující AuthServiceTest.missingAndWrongPasswordUseSameErrorAndPasswordWork
  navíc kontroluje obecný provozní log neplatného loginu bez identit, hesel,
  hashů a bez auditu. Počet běžných testů se tím nemění.
- Nová Stage3bRegressionIT přidává čtyři PostgreSQL scénáře návazností:
  skutečný grant/revoke a regrant se stejnou session, CSV a správou posádky;
  přijatý převod → retence → zrušení původního kapitána;
  ADMIN změna kapitána → retence → rollback rušení při chybě auditu a retry;
  souběh retence s rušením původního kapitána.

Testy používají skutečné služby, bootstrap ve vlastním schématu a produkční
adaptér účastí pro schválenou V7. Zestárnou pouze auditní fixture, nikoli tokeny
či sessions. Nemění se konfigurace testovacích ochran, produkční kód, migrace,
endpointy ani Postman kolekce.

Uživatel potvrdil úspěšné integrační ověření 3b.6b nad rallye_test, včetně
čtyř nových scénářů Stage3bRegressionIT, následujícím příkazem:

```powershell
.\mvnw.cmd -Pit verify
```

Jde o nový uživatelem potvrzený úspěšný běh po změnách 3b.6b, nikoli o vlastní
PostgreSQL běh Codexu. Celkový počet IT nebyl uveden. Integrační ověření
3b.6b je dokončené. Tento integrační běh sám nepotvrzoval ruční průchod ani
uzavření 3b; jejich následné výsledky jsou v aktuálním souhrnu nahoře.

## 3b.6c – příprava a výsledky společného Postman průchodu

Připraven [konkrétní postup šesti scénářů](postman-3b6.md): více posádek/vlastníků,
převod ve stávajících relacích, zrušení čekajícího převodu administrátorem,
odebrání ADMIN, zrušení účtu a nová identita stejného e-mailu. Obsahuje parametry,
přesné existující requesty, očekávané statusy/hodnoty a volitelné SELECT kontroly.
Codex ruční testy neprovedl, žádný účet ani databázová data neměnil.

Kolekce má 95 požadavků a 157 syntakticky ověřených JavaScript skriptů.
Nová složka 15 obsahuje pouze tři chybějící varianty: izolovaný mobilní login
s uložením tokenu do vybraného environmentu, audit odmítnutý po odebrání ADMIN
a accept zrušeného převodu. Ostatní requesty se znovu používají.

Režim regressionAuthMode=bearer je výslovná volba v environmentu. Každá relace
má vlastní lokální regressionSessionToken, bez fallbacku do kolekce a bez
přepsání existujícího tokenu novým loginem. U všech znovu použitých requestů
musí uživatel vypnout cookie jar; nové requesty již mají disableCookies=true.
Samotné environments cookies neizolují. Výchozí cookie/CSRF režim zůstává
zachovaný. Tento mobilní průchod nenahrazuje webové CSRF testy 2A.

Opraveno devět skutečných chyb generování klíčů ve skriptech rušení účtu:
nesprávné `{$guid}` nahrazeno Postman dynamickou proměnnou `{{$guid}}`.
Jinak zůstaly všechny původní requesty, těla, proměnné a autentizační nastavení
zachované; kolekční prerequest skript má navíc oddělenou opt-in Bearer větev.

Ověření: Node.js JSON.parse a PowerShell ConvertFrom-Json, oficiální schéma
Postman Collection v2.1, syntaxe všech 157 skriptů a platnost JSON requestů.
Syntetické spuštění bez sítě ověřilo výchozí CSRF větev, oddělení tokenů A/B,
odmítnutí chybějícího tokenu a explicitní Cookie hlavičky, serializaci loginu,
uložení tokenu pouze do environmentu, zákaz jeho přepsání a devět UUID generátorů.
Nejde o spuštění kolekce v Postmanu ani o nový backendový či PostgreSQL běh.
Produkční kód, testy backendu a migrace se při přípravě 3b.6c neměnily.
Uživatel následně potvrdil všech šest ručních scénářů proti rallye_dev.
Příprava i uživatelské ověření 3b.6c jsou dokončené; následná konsolidace
3b.6d je zaznamenaná v [review-3b6.md](review-3b6.md).

## 3b.6d – závěrečná konsolidace

- Verify opakováno s JDK 21: 234 úspěšných testů, žádné chyby ani přeskočení.
- Failsafe souhrn a deset reportů tříd: 133 dokončených IT bez chyb či přeskočení;
  Stage3bRegressionIT má čtyři úspěšné testy. Reporty dokládají dřívější běh
  potvrzený uživatelem, ne nový běh této závěrečné kontroly.
- Node.js JSON.parse a PowerShell ConvertFrom-Json, oficiální Collection v2.1
  schema, 95 requestů a 157 JavaScript skriptů: validní. Syntetické kontroly
  CSRF, Bearer izolace, zákazu přepsání tokenu a devíti UUID generátorů prošly
  bez síťového spuštění kolekce.
- Matice: ověřeno všech 75 konkrétních odkazů na testovací metody.
- Ruční výsledky: všech šest scénářů PROŠEL podle uživatele; scope je
  explicitně zaznamenán, neodvozují se další nehlášené kontroly.
- Produkční Java, konfigurace ani V1–V7 se v 3b.6 neměnily. Žádná nová
  prokázaná chyba backendu, žádné destruktivní databázové operace Codexu.
- Zbylé provozní a budoucí funkční návaznosti jsou v review-3b6.md;
  neblokují uzavření současného rozsahu. Commit a push čekají na schválení.


## 3c.1 – dokončena a otestována: protokolový základ externí autentizace

Codex spustil Maven Wrapper verify s JDK 21.0.12.1: **BUILD SUCCESS, 250 testů, 0 failures/errors/skipped**, sestavený JAR. Protokolové unit/HTTP testy nepoužívají skutečné poskytovatele; JWT se podepisují testovacími RSA klíči. Existing 2A/3b regrese prošla bez DB.

V8 a deset scénářů ExternalAuthIT jsou zkompilované a uživatel následně potvrdil `.\mvnw.cmd -Pit verify` proti rallye_test s výsledkem BUILD SUCCESS. PostgreSQL testy Codex nespouštěl; jde o uživatelem potvrzené ověření, celkový počet IT nebyl uveden. Integrační ověření 3c.1 je dokončené. Nová V8 je výslovně povolena adaptérem účastí, neznámé skripty a V9 se odmítají. Budoucí-migration fixture AccountCancellationServiceIT používá V9 místo nově podporované V8.

Rozsah, konkrétní API a postup Postman bez credentials: [external-auth-3c1.md](external-auth-3c1.md). Callback ještě nevytváří přihlášení ani účty; LINK/REAUTH dokončení je bezpečně zablokované do doložení čerstvosti. Úspěch simulace není skutečné ověření Google/Apple, SameSite/form_post v browseru nebo mobilního klienta. Žádná infrastruktura nebyla zřízena a skutečné externí toky nebyly spuštěny.


## 3c.2 – webová externí registrace a přihlášení (implementačně dokončena a lokálně otestována)

Codex spustil Maven Wrapper verify s Microsoft JDK 21.0.12.1: **BUILD SUCCESS, 271 testů, 0 failures/errors/skipped**, sestavený JAR. Unit/HTTP testy bez sítě a DB pokrývají webové cookies/CSRF, doplnění profilu, generické dokončení při kolizi, předání validované identity, šifrování a chybné klíče. Regrese místní autentizace a 3b prošla.

ExternalWebLoginIT má 14 nových připravených scénářů se skutečnými službami a V9 v izolovaném schématu, mockovaný je jen provider verifier a pro vybrané chyby spy zápisu. Pokrývá souběhy, ověření adresy a původní heslo při kolizi, zákaz Google/Apple auto-link, session/audit rollback, registrační rollback včetně neposlání mailu, Apple relay/šifrovaný prostředek a cancellation, přesnou expiraci, upgrade V8 -> V9 a guard neznámé migrace. Nové IT jsou zkompilované, ale Codex je nespouštěl. Uživatel po opravě mockování potvrdil `.\mvnw.cmd -Pit verify` proti rallye_test: **BUILD SUCCESS**; integrační ověření je dokončené. Starý úspěch 3c.1 se nepřenáší na V9.

Postman kolekce validní jako JSON a Collection v2.1, 100 requestů/165 JS skriptů, původní requesty a nastavení zachované. Nová složka 16 je pro cookie režim; browser/Postman cookie jar nejsou sdílené. Skutečné HTTPS/provider/browser ověření nebylo provedeno. Dokumentace API, konfigurace klíčů, přesný manuální postup a omezení: [external-auth-3c2.md](external-auth-3c2.md).

Uživatel výslovně schválil 15minutový dokončovací kontext a minimální šifrované Apple úložiště již v 3c.2. V1–V8 se nemění; V9 má přesný allowlist a future-migration fixture používá V10. Revokační worker/retry a externí čerstvé ověření/cancellation stále nejsou implementované, nelze tvrdit připravenost Apple k produkčnímu zapnutí.

### Oprava integračního testu dokončení registrace

Uživatelský PostgreSQL běh 3c.2: 157 testů, 0 failures, 2 errors. Stack trace prokázal chybu přípravy Mockito spy: nastavování `consume` přes transakční proxy `ExternalAccountStore` mimo transakci vyvolalo `IllegalTransactionStateException` kvůli `MANDATORY`, ještě před voláním služby. Nedokončený stubbing následně způsobil `UnfinishedStubbingException` při resetu mocků druhého testu; nešlo o samostatné selhání kontroly V9.

Opravený test nastavuje i obnovuje skutečný spy za proxy pomocí `AopTestUtils`. Simulované selhání nastává až po skutečném zápisu `consume` v aktivní transakci. Test ověřuje kořenovou vloženou výjimku, rollback účtu, identity, spotřebování i údajů registračního kontextu, neposlání e-mailu a úspěšný retry. Produkční transakční hranice ani `Propagation.MANDATORY` se nemění. Po opravě uživatel potvrdil opakovaný běh `.\mvnw.cmd -Pit verify` proti `rallye_test` s výsledkem **BUILD SUCCESS**. Jde o uživatelem potvrzené integrační ověření, nikoli běh Codexu; počet testů opakovaného běhu nebyl uveden.

### Postman 3c.2: oddělení lokálních a připravených scénářů

Uživatel po restartu backendu potvrdil šest úspěšných a tři neúspěšné aserce původní složky 16. Pozitivní GET profilu (200) a POST dokončení (202 + prázdné tělo) byly spuštěny bez nutného registračního kontextu; 400 při chybějícím kontextu je správné zamítnutí. Kolekce nyní zachovává všechny požadavky i aserce, ale rozděluje je do složky **16 LOCAL disabled providers** (tři požadavky, šest asercí) a **17 PREPARED HTTPS registration only** (dva pozitivní požadavky). Lokálně spouštět pouze 16, v cookie režimu a bez RALLYE_EXTERNAL_REG. Podrobná příprava a očekávání jsou v [external-auth-3c2.md](external-auth-3c2.md). Uživatel následně potvrdil lokální opakování po reorganizaci: všech šest asercí složky 16 Passed. Úspěšné PostgreSQL ověření zůstává uživatelem potvrzené; skutečný provider/browser průchod je stále neověřený. Produkční kód ani migrace se při reorganizaci neměnily.


Závěrečný stav 3c.2: **implementačně dokončena a lokálně otestována**. Verify bez DB: 271 testů, BUILD SUCCESS (běh Codexu). PostgreSQL integrační verify: BUILD SUCCESS (potvrzeno uživatelem). Postman složka 16: 6/6 asercí Passed (potvrzeno uživatelem). Skutečné Google/Apple přihlášení a pozitivní složka 17 nebyly ověřeny; čekají na schválené HTTPS testovací prostředí. Toto omezení zůstává před provozním zapnutím otevřené a nebrání uzavření implementačního rozsahu 3c.2.


## Dokumentační revize 0.8.0 – D09/D10 (bez implementace)

Dosavadních 271 unit/HTTP testů a uživatelem potvrzené PostgreSQL/Postman 3c.2 běhy jsou historické výsledky kódu do V9, nikoli ověření nových invariantů. Nové testy se nyní nepíší ani nespouštějí. V přepracované 3c.3 doplnit UNIQUE(account_id)/ACTIVE existenci a neměnnost včetně DELETE+INSERT, souběh, rollback, anonymizaci a přesný V10 guard. Testy podporující start LINK/REAUTH změnit na odmítnutí; zachovat bezpečnostní regrese, nikoli jejich původní produktový předpoklad. 5minutová čerstvá oprávnění již nejsou požadavkem. D10 testovat podle DB typu identity, bez externího auth_time/max_age: LOCAL heslo, externí výslovné potvrzení a platná relace, CSRF, audit, replay a zneplatnění relací.

Postman kolekce při této revizi zůstává beze změny. Budoucí úpravy: opravit případné texty doporučující link, zachovat lokální 16 a připravenou 17, doplnit cancellation potvrzení; žádné link/unlink/LOCAL-add/reauth požadavky. Externí manuální cancellation vyžaduje skutečně přihlášený testovací účet v HTTPS prostředí; samotné potvrzení nezřizuje novou identitu ani relaci. Testy bezpečného Apple retry zůstávají v 3c.5. Podrobná matice dopadů a pořadí jsou v external-auth-3c.md.


## 3c.3a – jediná neměnná identita (dokončena a lokálně ověřena)

Codex spustil Maven Wrapper verify na Microsoft JDK 21.0.12.1: **BUILD SUCCESS, 274 testů, 0 failures/errors/skipped**. Všechny PostgreSQL IT včetně nového SingleIdentityIT jsou zkompilované, ale nebyly Codexem spuštěny. V jednom předchozím běhu selhal stávající AuditExportHttpTest.adminStreamsCsvWithDownloadHeadersAndAllFilters na ConcurrentModificationException při souběžném zápisu hlaviček do MockHttpServletResponse (Spring HeaderWriterFilter a asynchronní export). Bez změny tohoto testu či exportní implementace následující celý verify prošel. Tento jednorázový nesouvisející závod je evidované omezení testovacího prostředí, ne zamlčený úspěch prvního běhu.

Nová V10 předem odmítá konfliktní identity i staré nepodporované kontexty bez jejich mazání. SingleIdentityIT obsahuje 15 scénářů, včetně pěti variant neúspěšného preflightu a úspěšného upgradu V9. Další testy pokrývají HTTP 400 pro LINK/UNLINK/REAUTHENTICATE, 404 neexistujících identitních endpointů pro přihlášeného uživatele, přesný guard V10/odmítnutí V11 a původní externí registraci/login/rušení. Podrobnosti a transakční důvody: [single-identity-3c3a.md](single-identity-3c3a.md).

Uživatel spustí podle stávajících ochran `.\mvnw.cmd -Pit verify` pouze proti rallye_test. Po opravě úklidu uživatel potvrdil úspěšný integrační běh včetně V10, preflightu, rollbacku a souběhů. Codex nepoužil rallye_dev ani aplikační DB. Postman kolekce má po přidání negativní složky 18 celkem 103 požadavků a 168 syntakticky validních JS skriptů, validované schéma v2.1. Složku 18 spustit v cookie režimu proti lokálnímu backendu s vypnutými poskytovateli, očekávat šest úspěšných asercí. Kolekce nebyla Codexem spuštěna. Externí potvrzení rušení D10 není součástí 3c.3a.


### Oprava testovacího úklidu po aplikaci V10

Uživatelský PostgreSQL běh skončil 173 testy, 0 failures a 12 errors. Podrobné Failsafe reporty ukazují 11 chyb v IdentityIT.cleanupOnlyOwnFixtures a jednu v LocalMailboxIT finally: DELETE identity v autocommitu dokončil transakci před smazáním účtu. Trigger check_account_identity správně odmítl tento stav (identity_delete_requires_cancellation, SQLSTATE 23514). Nešlo o důvod ke změně produkčního triggeru.

Oba testovací úklidy nyní v jedné TransactionTemplate transakci vyberou a zamknou účet podle přesného UUID e-mailu vytvořeného testem, zapamatují jeho interní UUID a cíleně smažou sessions, action tokeny, identitu a nakonec účet. Při commitu odložený trigger zjistí neexistujícího rodiče a dovolí legitimní odstranění. Neočekávaná FK závislost způsobí rollback celého úklidu; nevypínají se triggery ani se nemažou cizí závislosti. Auditní historie zůstává podle dosavadního modelu bez FK. Starší zbytky předchozího neúspěšného běhu se automaticky plošně nemažou.

Revize všech 13 PostgreSQL IT tříd našla tento autocommit vzor pouze ve zmíněných dvou. Deset ostatních datových tříd uklízí vlastní náhodné schéma s existujícími kontrolami DB/role/jména; RallyeBackendApplicationIT nevytváří vlastní účtová data. Přímé DELETE v SingleIdentityIT jsou buď záměrné negativní testy, nebo součást celé cancellation transakce; perzistentní cancellation regrese už používají jednu transakci.

SingleIdentityIT nově obsahuje 17 scénářů. Dvě nové regrese ověřují cílený úplný úklid s tokenem a session při zachování jiného účtu/identity a rollback odstranění účtu i identity při vložené chybě. Stávající test zákazu samostatného DELETE (SQLSTATE 23514) a testy DELETE+INSERT zůstávají zachovány.

Po opravě Codex spustil verify na JDK 21: **BUILD SUCCESS, 274 testů, 0 failures/errors/skipped**, včetně kompilace všech IT. PostgreSQL testy Codex nespouštěl; opakované ověření `.\mvnw.cmd -Pit verify` proti rallye_test po opravě uživatel potvrdil jako BUILD SUCCESS. Počet testů opakovaného běhu nebyl uveden. Produkční Java kód ani V10 se v této opravě nezměnily a V11 nevznikla.


Závěrečné integrační ověření opravy úklidu: uživatel potvrdil `.\mvnw.cmd -Pit verify` proti `rallye_test` s výsledkem **BUILD SUCCESS**. Jde o uživatelem potvrzený běh, nikoli běh Codexu; celkový počet testů nového běhu nebyl sdělen. Automatické ověření 3c.3a je dokončené. Uživatel následně potvrdil také manuální Postman složku 18: **6/6 Passed**. Skuteční poskytovatelé ani pozitivní složka 17 tím nejsou ověřeni. 3c.3b není zahájena; V10 se po aplikaci neměnila a V11 nevznikla.


## 3c.3b – zrušení účtu D10

Codex spustil verify na Microsoft JDK 21: BUILD SUCCESS, 284 testů, 0 failures/errors/skipped. Nové unit/HTTP kontroly a kompilace PostgreSQL regresí prošly. Osm nových ExternalWebLoginIT scénářů a LOCAL regrese AccountCancellationServiceIT prošly uživatelem potvrzeným `.\mvnw.cmd -Pit verify` proti rallye_test (BUILD SUCCESS). Existující úspěšný běh 3c.3a není novým ověřením D10. Migrace V1–V10 se nemění. Manuálně připraveny Postman 19 (LOCAL negativní kontroly), stávající 13 (LOCAL úspěch) a oddělená 20 (externí testovací účet v HTTPS prostředí). Přesná příprava, výsledky a limity: [account-cancellation-3c3b.md](account-cancellation-3c3b.md). Reálná Apple revokace zůstává neimplementovaná v 3c.5.


Uživatelské potvrzení 25. 10. 2026: `.\mvnw.cmd -Pit verify` proti `rallye_test` skončilo **BUILD SUCCESS**. Jde o uživatelem potvrzené integrační ověření, nikoli PostgreSQL běh Codexu. Počet testů tohoto běhu nebyl sdělen. Automatické ověření 3c.3b je dokončené; manuální Postman složky 19/20 ani skutečná Google/Apple integrace tím nejsou potvrzené. Apple revokace zůstává v 3c.5; commit ani push nebyly provedeny.


### Postman 19 – oprava chybějící přípravy relace

Uživatel potvrdil 2 Passed / 2 Failed: oba POST měly 401 UNAUTHENTICATED, proto prošly statusy a selhaly očekávané kódy INVALID_CREDENTIALS. Složka byla závislá na předem přihlášené relaci. Byla doplněna vlastní cookie login příprava a /me kontroly před/po negativních operacích; stále se vyžaduje aktivovaný LOCAL účet. Lokální proměnné d10LocalEmail/d10LocalPassword, cookie režim a přesné pořadí popisuje [account-cancellation-3c3b.md](account-cancellation-3c3b.md). Původní negativní aserce se neoslabují, bezpečnostní mechanismy a produkční kód zůstávají beze změny. Nové skutečné manuální ověření není zatím potvrzené.


Kontroly opravené 19: JSON Node/PowerShell, schéma Collection v2.1, 111 požadavků a 180 syntakticky validních JS skriptů. Offline simulace ověřila přihlášenou/nepřihlášenou cestu, neúspěšnou přípravu a zachování původního požadovaného kódu chyby; žádný HTTP ani PostgreSQL běh tím nebyl proveden. Původní bezpečnostní aserce zůstávají. Ostatní složky a sdílený skript nebyly měněny, tři nové proměnné jsou v repozitáři prázdné (d10LocalEmail, d10LocalPassword, automatické d10LocalPreparedAccountId).


### Následná diagnostika Postman 19: 23 Passed / 4 Failed (403 ACCESS_DENIED)

Průchozí login a všechny /me kontroly dokládají funkční autentizovanou relaci pomocných požadavků. Oba chráněné POST ale vracely 403 ACCESS_DENIED namísto aplikačních 401 INVALID_CREDENTIALS. ACCESS_DENIED vytváří security accessDeniedHandler; LOCAL password check jej nevytváří. CSRF je proto konkrétní podezřelá hranice před controllerem, nikoliv doložené odmítnutí hesla. Bez skutečných odchozích hlaviček a cookies nelze tvrdit, zda hlavička chyběla, byla stará, nebo neodpovídala cookie poslané v POST. Samotný /me není důkazem rotace CSRF cookie a takovou rotaci analýza nepotvrdila. CSRF zde není uložený v Rallye databázové relaci: CookieCsrfTokenRepository porovnává hlavičku s cookie XSRF-TOKEN. /csrf může vrátit XOR-maskovaný request token, proto se do hlavičky nikdy nekopíruje raw cookie.

Proti funkční složce 13 měla 19 další pomocný /me až po společné CSRF přípravě. Oprava odstraňuje tuto časovou mezeru: collection-level CSRF příprava deleguje pouze dva přesně pojmenované negativní POST na jejich vlastní request-level skript. Ten nejprve ověří /me, potom jako poslední HTTP přípravu získá /csrf, ověří dostupnost RALLYE_SESSION a XSRF-TOKEN v jar a vloží čerstvou hlavičku přímo do odcházejícího požadavku. Po nastavení hlavičky už žádné další přípravné HTTP volání není. Pokud předpoklady chybí, aserce selže a POST se neodešle. Složka 13 i ostatní požadavky stále používají původní společnou přípravu. Původní aserce 401 INVALID_CREDENTIALS nebyly změněny.

Opakování: importovat aktualizovanou kolekci, nastavit d10LocalEmail/d10LocalPassword aktivovaného LOCAL účtu a regressionAuthMode=cookie; pro všechny requesty ponechat Cookie Jar zapnutý a allowlist pro localhost. Používat stejný hostname/port v baseUrl i skutečných URL; nemíchat localhost a 127.0.0.1. Odstranit ručně nastavené Cookie, Authorization, X-Rallye-Client a X-XSRF-TOKEN; cookie ani token nevkládat ručně. Poté spustit pouze celou 19 od přípravného loginu. Negativní POST mají stále vrátit 401 INVALID_CREDENTIALS; nové aserce ověřují poslední CSRF přípravu a dostupnost obou cookies. /me zůstává 200 před i po. Během průchodu nespouštět logout, změnu/reset hesla ani destruktivní složku 13/20 nebo jiného uživatele na stejném jar.

Pokud se 403 opakuje i po všech nových přípravných asercích, pro další diagnostiku ověřit v Postman Console pouze metadata skutečného POST: přítomnost jedné X-XSRF-TOKEN hlavičky, názvy RALLYE_SESSION/XSRF-TOKEN v odeslaném Cookie a případné Set-Cookie smazání/rotaci. Nesdílet jejich hodnoty, heslo ani tokeny. Zkontrolovat duplicitní cookies téhož jména pro různé domain/path; lze odstranit cookies pouze daného lokálního hostu a zopakovat login. Úspěch offline simulace není důkaz skutečného přenosu cookies Postmanem.

Ověřeno: JSON Node/PowerShell, Postman schema v2.1, syntaxe JS a offline simulace včetně posledního získání CSRF, čtení cookies a nastavení hlavičky. Skutečné Postman opakování opravy není zatím potvrzené. Při této opravě se nezměnil produkční ani testovací Java kód, migrace ani security konfigurace. Maven/PG testy nebyly znovu spouštěny, jejich předchozí ověření zůstává oddělené.


### Manuální 3c.3b – aktualizace složek 19 a 13

Uživatel potvrdil složku 19: 35 Passed / 0 Failed. Složka 13 s No response zatím neověřila úspěšné zrušení účtu. Opravené předpoklady, jednorázový opt-in, ověření 204 + následného /me 401 a odmítnutého loginu jsou popsány v [account-cancellation-3c3b.md](account-cancellation-3c3b.md#postman-13--bezpečné-skutečné-ověření-local-zrušení-3c3b). Složku 13 nespouštět celou; povolení mazání je výchozí false. Uživatel následně potvrdil úspěšné LOCAL zrušení; závěrečný výsledek je uveden níže. Java ani PostgreSQL testy nebyly pro tuto čistě Postman/dokumentační změnu opakovány.


### Uzavření 3c.3b – potvrzené výsledky

Etapa 3c.3b je implementačně dokončená a lokálně otestovaná. Dřívější verify Codexu: BUILD SUCCESS, 284 testů. PostgreSQL verify: uživatelem potvrzené BUILD SUCCESS (Codex jej nespouštěl). Postman složka 19: uživatelem potvrzeno 35 Passed / 0 Failed. Samostatný `3b4b Self cancellation - DELETE TEST ACCOUNT (204)`: skutečné 204 No Content, prázdné tělo, následné zneplatnění relace a odmítnutí loginu původními údaji. Jednorázové ochranné povolení se vrátilo na false. Dřívější No response ani historická selhání nebyla započítána jako úspěch; nahradilo je toto potvrzené opakování.

Skutečné GOOGLE/APPLE zrušení a pozitivní externí scénáře složky 20 zůstávají manuálně neověřené a vyžadují HTTPS testovací prostředí. Apple revokace patří do 3c.5. Při uzavření byly upraveny pouze dokumentační výsledky; automatické testy se znovu neopakovaly.


## 3c.4a.1 – mobilní autorizační pokus / browser launch

Cílené unit/HTTP testy: BUILD SUCCESS, 41 testů. Celé verify Codexu na Microsoft JDK 21.0.12.1: BUILD SUCCESS, 296 testů, 0 failures/errors/skipped; PostgreSQL IT pouze zkompilované. Připraveno 10 nových MobileLoginIT scénářů ve vlastním náhodném schématu přes TestDatabaseInitializer. Nový PostgreSQL běh uživatel následně potvrdil: `.\mvnw.cmd -Pit verify` proti rallye_test – **BUILD SUCCESS**. Codex jej nespouštěl; celkový počet testů tohoto běhu nebyl sdělen. Deset nových MobileLoginIT scénářů včetně upgradu V10 → V11, expirace, souběhu a rollbacku je součástí potvrzeného ověření. Nejde o převzetí úspěchu předchozí etapy.

Postman 21: šest lokálních negativních požadavků bez účtu a bez providerů. Postman 22: samostatný připravený HTTPS start/launch/replay s vypnutým následováním redirectů. Žádné destruktivní operace ani Bearer exchange; složku 21 následně uživatel skutečně ověřil, včetně nových privacy asercí (All Passed). Složka 22 zatím spuštěna nebyla. Přesné předpoklady: [mobile-bootstrap-3c4a1.md](mobile-bootstrap-3c4a1.md). V11 zachovává V1–V10 a upravuje přesný guard; navazující handoff zůstává mimo rozsah.


### Bezpečnostní revize ticketu 3c.4a.1

Uživatel potvrdil Postman 21 (všechny testy prošly) a PostgreSQL verify před revizí. Následná oprava privacy hlaviček pro chyby před controllerem a redakce request/access logů má nový Codex verify bez DB: **BUILD SUCCESS, 306 testů, 0 failures/errors/skipped**. Obsahuje skutečný izolovaný embedded Tomcat access log test, MVC TRACE s request details a bezpečnou diagnostiku výjimek. PostgreSQL testy se nyní neopakovaly; migrace/služby zůstaly touto opravou beze změny. Uživatel následně potvrdil opakování celé Postman 21: All Passed včetně nových no-store/no-referrer asercí. HTTPS 22 až ve zvlášť připraveném prostředí. Skutečná absence Referer v browserovém provider požadavku a proxy logy nejsou lokálním HTTP testem prokázány. [Report a provozní podmínky](review-launch-privacy-3c4a1.md).


## 3c.4a.2 – callback a jednorázový handoff grant

Codex verify bez DB: BUILD SUCCESS, **317 testů, 0 failures/errors/skipped**, Microsoft JDK 21.0.12.1. PostgreSQL IT zkompilované, nikoli spuštěné. Nové MobileCallbackIT má **15 scénářů** včetně Google/Apple, replay/souběhu, rollbacku, cancellation/DELETE/anonymizace, kolize e-mailu a V12 preflight/guard. Uživatelský `.\mvnw.cmd -Pit verify` proti rallye_test po opravě přípravy SingleIdentityIT skončil BUILD SUCCESS (potvrzeno 2026-10-25); nejde o běh Codexu.

Postman 23: sedm lokálních negativních callbacků bez providerů a bez destruktivních operací, kontrola chyb/privacy hlaviček. Postman složku 23 uživatel potvrdil jako All Passed; Codex ji nespouštěl. HTTP testy ověřily redakci provider code/state/cookie a handoff kódu v Location při MVC TRACE. Skutečná browserová absence Referer a proxy logy zůstávají neověřené. Pozitivní grant nelze zatím vyměnit za Bearer, protože exchange patří až 3c.4a.3. [Detailní postup a omezení](mobile-callback-3c4a2.md).

### Oprava integrační přípravy po V12

Uživatelský PostgreSQL běh: 209 testů, jedna chyba aserce v `SingleIdentityIT` (očekáváno SQLSTATE `23514`, skutečnost `23502`). Starý INSERT nevyplňoval nové povinné `attempt_id`. Test nyní vytváří existující relaci a mobilní pokus, samostatně ověřuje zákaz REAUTH a LINK (`23514`) a kontrolním platným MOBILE/LOGIN insertem potvrzuje platnost přípravy. Nová regrese ověřuje chybějící (`23502`) a neexistující (`23503`) pokus. Produkční kód ani V1–V12 se kvůli této opravě nemění. Opravený PostgreSQL běh `.\mvnw.cmd -Pit verify` proti `rallye_test` uživatel následně potvrdil jako BUILD SUCCESS (2026-10-25). Jde o uživatelský běh, nikoli běh Codexu; počet testů opakovaného běhu nebyl sdělen.

Po opravě přípravy: vlastní běh `verify` bez PostgreSQL – BUILD SUCCESS, 317 testů, 0 failures/errors/skipped; PostgreSQL testy byly zkompilovány, nikoli spuštěny.

Uzavření 3c.4a.2 (2026-10-25): implementačně dokončena a lokálně ověřena. Codex verify bez DB: 317 testů, BUILD SUCCESS. Uživatel potvrdil PostgreSQL verify včetně opravy SingleIdentityIT (BUILD SUCCESS) a Postman složku 23 (All Passed). Skutečné Google/Apple přihlášení přes HTTPS, browser/proxy a profilová stránka zůstávají neověřené. Exchange a mobilní Bearer relace patří až do nezahájené 3c.4a.3.

## 3c.4a.3 – mobilní exchange (připraveno k ověření)

[Detailní kontrakt a postup](mobile-exchange-3c4a3.md). Připraveno 15 PostgreSQL testů MobileExchangeIT s izolovaným schématem rallye_test: skutečný callback/grant/exchange nad mock providerem, me/logout přes HTTP, replay, PKCE a requestId, přesná expirace, souběhy exchange/cancellation a rollback po skutečném zápisu session/auditu. Nové testy bez DB pokrývají RFC PKCE vektor, validaci, pořadí zámků, HTTP/CSRF, throttle, redakci a odmítnutí nebezpečných loggerů. PostgreSQL `.\mvnw.cmd -Pit verify` spouští pouze uživatel a nový výsledek uživatel potvrdil jako BUILD SUCCESS. Postman 24 lze spustit celý na localhost bez providerů; Postman 25 po přípravě HTTPS jednotlivě s browserovým callbackem. Uživatel potvrdil lokální Postman 24 jako All Passed; skutečné provider přihlášení a pozitivní Postman 25 zatím ověřeny nejsou.

Ověření 3c.4a.3 (Codex, 2026-10-25): Maven Wrapper / Microsoft JDK 21.0.12.1, `verify` bez DB – **BUILD SUCCESS, 335 testů, 0 failures/errors/skipped**, JAR sestaven. Zahrnuje 18 nových testů bez DB a zkompilovaných 15 MobileExchangeIT; PostgreSQL se nespouštělo. Postman Collection v2.1 prošla standardním JSON parserem, validací proti oficiálnímu lokálnímu schématu a syntaktickou kontrolou všech 222 JavaScript skriptů. Postman požadavky nebyly spuštěny. `git diff --check` bez chyb; uživatelské .gitignore a docs/Rallye_specifikace.md zachovány.

### Uživatelské PostgreSQL ověření 3c.4a.3

Uživatel po dokončení implementace potvrdil `.\mvnw.cmd -Pit verify` proti `rallye_test`: **BUILD SUCCESS**, včetně nových MobileExchangeIT. Jde o uživatelem provedený běh, nikoli vlastní spuštění Codexem; celkový počet testů nebyl sdělen. Postman složky 24/25 ani skutečné HTTPS/Google/Apple end-to-end ověření tímto potvrzením ověřeny nejsou. Bez commitu a pushe.

### Uzavření 3c.4a.3

Etapa je implementačně dokončena a lokálně ověřena. Codex: verify bez DB BUILD SUCCESS, 335 testů, validní Postman Collection v2.1/222 skriptů a git diff --check. Uživatel: PostgreSQL verify BUILD SUCCESS včetně MobileExchangeIT a Postman složka 24 All Passed. Skutečné Google/Apple přihlášení přes HTTPS a pozitivní Postman složka 25 dosud ověřeny nejsou; nevyvozují se ze simulovaných providerů. 3c.4b ani 3c.5 se nezahajují. Starší záznamy o čekajících testech popisují průběh před tímto potvrzením.

## 3c.5a.1 – přechodný perzistentní základ Apple revokace

Codex `verify` bez PostgreSQL na Microsoft JDK 21.0.12.1: **BUILD SUCCESS, 349 testů, 0 failures/errors/skipped**, JAR sestaven. Nové unit testy ověřují interní validaci a úplné vazby, redakci, AES/AAD předání, pořadí operací, duplicitní enqueue a CAS/lease vstupy. Přesný guard přidává pouze V13, ne libovolnou budoucí migraci.

AppleRevocationIT má **19 připravených PostgreSQL scénářů**, vlastní náhodné schéma a TestDatabaseInitializer/rallye_test guard. Pokrývá SQL invarianty a vlastníky, ochranu šifrovaného materiálu, odložené kontroly a rollback, idempotentní/souběžné enqueue, souběžné claim, due hranici a takeover lease, stale výsledky, BLOCKED retry, zakázané SQL dokončení, V13 migration guard a kompatibilní syntetický upgrade V12. Tyto testy Codex nespouštěl, pouze zkompiloval. Úspěch předchozí 3c.4a.3 se na V13 nepřenáší.

Uživatel spustí podle stávajících bezpečnostních pravidel proti rallye_test:

```powershell
.\mvnw.cmd -Pit verify
```

Žádný Postman request nebo veřejné API nepřibylo. Původní writer credential/associate/purge zůstává nezměněný; globální NOT NULL case vazba přijde až s napojením procesů ve V14/3c.5a.2. **3c.5a.1 se nesmí samostatně nasadit se skutečně povolenými Apple autorizacemi.** [Rozsah, model a omezení](apple-revocation-3c5.md).

Poznámka k běhům: jeden meziběh se 349 testy měl jediné selhání existujícího AuditExportHttpTest.adminStreamsCsvWithDownloadHeadersAndAllFilters (500; ConcurrentModificationException při souběžném zápisu hlaviček MockHttpServletResponse). Nové testy prošly. Beze změny kódu auditu byl celý verify zopakován: **BUILD SUCCESS, 349 testů, 0 failures/errors/skipped**. Tím se nedokládá oprava tohoto ojedinělého testového souběhu; zůstává odděleným pozorováním.

Uzavření 3c.5a.1: uživatel potvrdil `.\mvnw.cmd -Pit verify` proti `rallye_test` jako **BUILD SUCCESS**, včetně nových AppleRevocationIT. Jde o uživatelské ověření, nikoli běh Codexu. Skutečné Apple/HTTPS ověření nebylo provedeno; zákaz samostatného nasazení před 3c.5a.2/V14 zůstává platný.
