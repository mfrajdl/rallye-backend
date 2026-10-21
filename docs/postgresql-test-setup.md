# Testovací PostgreSQL ve stávajícím kontejneru

Stav: připraveno ke kontrole, na OCI ani v databázích nebylo nic spuštěno.
Použije se PostgreSQL 17 v `rallye-postgres-1`, nová databáze a role `rallye_test`.
Nemění se ACL `rallye`/`rallye_dev`, HBA, Docker porty ani OCI firewall. Další kontejner není potřeba.

## Přijatá úroveň izolace

Výchozí PUBLIC CONNECT a TEMPORARY v aplikačních databázích jsou přijatelné.
Testovací role se tedy může připojit a vytvářet vlastní dočasné tabulky; nesmí
vlastnit, číst ani měnit aplikační tabulky ani vytvářet objekty v trvalých schématech.
Nedostává žádné explicitní oprávnění v aplikačních DB ani členství v rolích.
Samotný úspěch připojení k aplikační DB nyní není selháním izolace.

Kontroly vyhodnocují efektivní práva včetně PUBLIC, nejen explicitní GRANT.
PUBLIC SELECT na tabulce by již byl problém. Při nálezu nežádoucích práv se postup
zastaví, nic automaticky neopravuje. Budoucí změny aplikačních grantů je nutné
kontrolovat znovu. [Oprávnění PostgreSQL 17](https://www.postgresql.org/docs/17/ddl-priv.html).

## 1. Diagnostika pouze pro čtení

Na existující SSH relaci MobaXterm, až ji správce bude chtít provést:

```bash
PG_CONTAINER='rallye-postgres-1'
PG_ADMIN='<existujici-db-superuser>'
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}'
docker port "$PG_CONTAINER"
docker exec -it "$PG_CONTAINER" psql -X -U "$PG_ADMIN" -d postgres
```

`PG_ADMIN` nahraď skutečným existujícím administrátorem, nemusí být `postgres`.
V psql vlož [00-diagnostics.sql](../ops/postgresql-test/00-diagnostics.sql), ukonči `\q`.
Ověř PG17, správný cluster a stávající loopback host port pro tunel. Nevypisuj
Docker environment ani hesla. Neotevírej veřejný PostgreSQL port. Pokud současné
síťové nastavení není bezpečné nebo HBA neumožňuje heslové přihlášení testovací role,
zastav a oznam překážku; tento postup konfiguraci neopravuje ani nemění.

## 2. Vytvoření DB a role – pouze po schválení

Přes SFTP nahraj adresář `ops/postgresql-test/` do `/home/<ssh-user>/rallye-test-review`.
Soubory musí zůstat pohromadě kvůli relativním psql include. Následující příkazy
jsou určeny k pozdějšímu ručnímu provedení, nyní nebyly spuštěny:

```bash
PG_CONTAINER='rallye-postgres-1'
PG_ADMIN='<existujici-db-superuser>'
REVIEW_DIR='/home/<ssh-user>/rallye-test-review'
docker cp "$REVIEW_DIR/." "$PG_CONTAINER:/tmp/rallye-test-review"
docker exec -it "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$PG_ADMIN" -d postgres -f /tmp/rallye-test-review/10-prepare.sql
docker exec -it "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$PG_ADMIN" -d rallye_test -f /tmp/rallye-test-review/15-test-schema.sql
docker exec -it "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$PG_ADMIN" -d postgres -f /tmp/rallye-test-review/20-verify.sql
docker exec -it "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$PG_ADMIN" -d rallye -f /tmp/rallye-test-review/25-verify-application-access.sql
docker exec -it "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$PG_ADMIN" -d rallye_dev -f /tmp/rallye-test-review/25-verify-application-access.sql
```

Každý příkaz spusť zvlášť a pokračuj pouze po návratovém kódu 0.

| Skript | Chování |
| --- | --- |
| `05-preconditions.sql` | Kontrola správné maintenance DB, PG17, administrátora a přítomnosti obou aplikačních DB. PUBLIC CONNECT/TEMP neblokuje. |
| `10-prepare.sql` | Vytvoří chybějící roli bez admin práv a DB z template0 s vlastníkem rallye_test. Nová role je do zadání hesla NOLOGIN. Stávající bezpečnou roli nemění. |
| `15-test-schema.sql` | Pouze v rallye_test nastaví schema public pro Flyway. |
| `20-verify.sql` | Ověří atributy, vlastnictví, nepřítomnost členství a explicitních aplikačních databázových grantů. CONNECT/TEMP přes PUBLIC pouze vypíše. |
| `25-verify-application-access.sql` | V každé aplikační DB read-only zkontroluje tabulky, sloupce, views, sekvence, vlastnictví a CREATE ve schématech. Kontroluje i přístupné SECURITY DEFINER rutiny jako možnou nepřímou cestu k datům. |

Skript 25 čte pouze katalogová metadata, nikdy aplikační řádky a nikdy neprovádí
zkušební UPDATE/DELETE ani nevolá rutiny. Kontroluje i sloupcové granty a práva
z PUBLIC. Úspěch musí být v obou DB, s počtem zkontrolovaných objektů. Nula objektů
je platná pro prázdnou DB, ale nepotvrzuje ochranu dosud neexistujících tabulek.
Kontroly jsou konzervativní: jakékoli nalezené aplikační právo znamená zastavení,
i když by je momentálně omezovalo chybějící USAGE schématu či RLS.
[Kontroly privilegií PostgreSQL](https://www.postgresql.org/docs/17/functions-info.html).

Opakování nic nemaže, nemění heslo a nezakládá již existující objekty znovu.
Neočekávané vlastnictví, admin atributy nebo členství znamenají chybu, nikoli
pokus o opravu. GRANT/REVOKE se týkají pouze testovací DB a jejího schématu,
nikdy stávajících aplikačních DB. V testovací DB se omezuje PUBLIC, což neovlivní
PUBLIC CONNECT/TEMP v ostatních DB. CREATE DATABASE nelze spustit v transakci,
proto nepoužívej psql `-1`. Při částečném neúspěchu může zůstat NOLOGIN role;
po vyšetření lze přípravu zopakovat bez mazání dat. Běh 10 má session advisory lock,
proto jej spouštěj novým psql procesem, který při chybě ukončí relaci i lock.

## 3. Heslo a dokončení přihlašovací role

Po úspěchu všech kontrol otevři jako dosavadní administrátor:

```bash
docker exec -it rallye-postgres-1 psql -X -v ON_ERROR_STOP=1 -U "$PG_ADMIN" -d postgres
```

V interaktivním psql zadej:

```psql
SET password_encryption = 'scram-sha-256';
\password rallye_test
ALTER ROLE rallye_test LOGIN;
\q
```

SET mění pouze aktuální administrační relaci, nikoli konfiguraci serveru.
Heslo vlož do výzvy `\password`, nikdy do SQL souboru, příkazu shellu ani JDBC URL.
Při opakování není nutné heslo znovu nastavovat, pokud ho nechceš vědomě změnit.
Potom znovu ověř 20: rolcanlogin má být true, všechny admin atributy false,
bez členství. Žádná nová oprávnění v rallye ani rallye_dev se neudělují.

Samostatné náhodné heslo uchovávej v používaném správci hesel (například KeePassXC),
mimo repo. Pro běh ho načti interaktivně do environment proměnné a po práci ji
odstraň. Nesdílená IntelliJ run konfigurace není trezor; heslo neexportuj do XML,
`.run`, `.env` ani sdílených konfigurací. IDE Password Safe pro DB datasource
nenastavuje automaticky Maven environment.

## 4. Tunel MobaXterm a ověření připojení

**Tunneling → New SSH tunnel → Local port forwarding**:

| Pole | Hodnota |
| --- | --- |
| Local/Forwarded port | 5434, bind pouze 127.0.0.1, nesdílet s ostatními počítači |
| Remote server | 127.0.0.1 z pohledu OCI SSH serveru |
| Remote port | Stávající host port z `docker port rallye-postgres-1`, nepředpokládat automaticky 5432 |
| SSH host/login/key | Stejné jako u existujícího vývojového tunelu |
| Název | Rallye PostgreSQL TEST 5434 |

Tunel 5433 ponech beze změn. Oba tunely mohou vést na stejný vzdálený port;
databázi vybírá JDBC název. Konfigurace Dockeru a firewallu se nemění.
Pokud GUI nepovoluje ověřit loopback bind, použij existující Windows SSH klient:

```powershell
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:5434:127.0.0.1:<host-port-postgresql> -p <ssh-port> <ssh-user>@<oci-host>
Get-NetTCPConnection -State Listen -LocalPort 5433,5434 | Select-Object LocalAddress,LocalPort
```

SSH příkaz běží v samostatném terminálu. Kontrola listeneru musí potvrdit pro 5434
jen loopback. V IntelliJ Database vytvoř datasource `127.0.0.1:5434`, database/user
`rallye_test`, heslo ze správce. Nezapínej další SSH tunel v IDE. Proveď Test Connection
(případně jednou ověř, že chybné heslo neuspěje), potom read-only:

```sql
SELECT current_database(), current_user, current_setting('server_version_num');
SELECT has_schema_privilege(current_user, 'public', 'USAGE') AS can_use,
       has_schema_privilege(current_user, 'public', 'CREATE') AS can_create;
```

Očekáváno rallye_test/rallye_test, PostgreSQL 17 a obě schema práva true.
Odmítnutí připojení k rallye/rallye_dev už **není** požadovaná kontrola;
nedostupnost aplikačních dat prokazují katalogové kontroly 25 v obou databázích.
Kvůli ověření se v aplikačních DB neprovádějí žádné změny dat ani ACL.

## 5. Integrační testy z PowerShellu nebo IntelliJ

V kořeni projektu, JDK 21, čistá relace bez Spring datasource/config overrides:

```powershell
$env:JAVA_HOME = 'C:\cesta\k\jdk-21'
.\mvnw.cmd -version
$env:RALLYE_TEST_DB_ENABLED = 'true'
$env:RALLYE_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:5434/rallye_test'
$env:RALLYE_TEST_DB_USERNAME = 'rallye_test'
$testCredential = Get-Credential -UserName rallye_test -Message 'Testovaci heslo'
try {
    $env:RALLYE_TEST_DB_PASSWORD = $testCredential.GetNetworkCredential().Password
    .\mvnw.cmd -Pit verify
    $testExit = $LASTEXITCODE
} finally {
    Remove-Item Env:RALLYE_TEST_DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:RALLYE_TEST_DB_ENABLED -ErrorAction SilentlyContinue
    Remove-Variable testCredential -ErrorAction SilentlyContinue
}
if ($testExit -ne 0) { throw 'Integration tests failed; inspect Failsafe reports' }
```

IntelliJ: Run → Edit Configurations → Maven, kořen projektu, command line
`-Pit verify`, wrapper projektu, runner JRE JDK 21. Předat stejné čtyři environment
proměnné. Lze IDE úplně ukončit a spustit z relace, kde jsou dočasně nastavené;
již běžící instance je nemusí převzít. Po běhu IDE ukončit. Při ručním dočasném
zadání hesla do run konfigurace vypnout Store as project file/Share a poté heslo
odstranit. Přímý JUnit běh musí zachovat initializer a profil it; preferován je Maven.

Zachovány jsou explicitní profil, opt-in, samostatné údaje, URL allowlist,
preflight skutečné DB/uživatele, ochrana proti vývojovým Spring overrides,
Flyway clean-disabled, baseline-on-migrate=false a Hibernate validate. Viz
[testing.md](testing.md). Žádná z těchto ochran nepotřebuje změnu kvůli PUBLIC.

Očekávaný výsledek: BUILD SUCCESS a úspěšný IT v `target/failsafe-reports`.
Log musí ukazovat Flyway proti rallye_test. Etapa 2A přidává skutečnou migraci
`V1__user_accounts.sql`; při prvním běhu musí být aplikována pouze v testovací DB.
Po běhu read-only:

```sql
SELECT current_database(), current_user, to_regclass('public.flyway_schema_history');
```

Historie musí existovat. Ověř `version, description, success` z této tabulky:
verze 1 má popis user accounts a `success=true`. Nesmí být prázdná; při chybě
ověřit log a aktivní Flyway bean. Nepoužívat clean, repair, baseline ani automatické DDL.

## Omezení

Jde o izolaci dat pomocí rolí a oddělené DB ve sdíleném clusteru, nikoli o oddělený
server. Testy sdílejí CPU, disk a spojení s aplikací; spouštět jednu sadu, bez
zátěžových testů. PUBLIC CONNECT/TEMP zůstávají přijatelné. Pokud se najde přístup
k aplikačním objektům, vlastnictví nebo dostupná SECURITY DEFINER rutina, testy
nespouštět; o nálezu rozhodne uživatel, bez automatických změn existujících ACL.
Kontroly zopakovat po změnách schémat/grantů. Žádná instalace Docker Desktopu nebo
WSL není potřeba. Na OCI dosud nebyla provedena diagnostika ani změna.
