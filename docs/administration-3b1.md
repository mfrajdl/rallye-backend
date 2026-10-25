# Etapa 3b.1: ADMIN, SQL bootstrap a audit

Implementace navazuje na specifikaci 0.6.0 a dokončenou autentizaci 2A.
Posádky, převody, rušení účtů, CSV export, archivace a mazání auditu nejsou součástí
tohoto kroku. Následná dokončená 3b.5 přidává CSV export a automatický retenční
úklid; aktuální pravidla jsou v [audit-3b5.md](audit-3b5.md). Ruční archivace
ani dvoukrokové mazání se podle pozdějšího schválení neimplementují.

## Migrace a oprávnění

Nová `V2__administration_and_audit.sql` přidává:

- `user_account_role`: pouze role ADMIN, FK na existující účet.
- `admin_control`: jediný řádek pro společný zámek a trvalou informaci o bootstrapu.
- `audit_event`: pouze ID, UTC čas, druh/ID aktéra, typ události a cílové ID účtu.
  Nemá kaskádový FK na účet; budoucí rušení účtu tedy neodstraní auditní historii.
- `admin_role_command`: Idempotency-Key (UUID) podle aktéra, cíle a operace, retence
  24 hodin. Odpověď je vždy 204, nemusí se ukládat její tělo. Při použití stejného
  klíče po expiraci se nahradí jen tento expirovaný záznam. Hromadný úklid není zaveden.
- Triggery chránící posledního aktivního, e-mailem ověřeného administrátora před
  odebráním role, deaktivací, odstraněním ověření nebo smazáním účtu.

V1 se nemění. Flyway V2 se provede standardně při startu aplikace; nepoužívat clean,
baseline ani ruční přepis Flyway historie. Žádná migrace sama nikoho nepovýší.

Filtr při každém autentizovaném požadavku načítá aktuální ADMIN z DB. Relace
neobsahuje snapshot rolí; stávající přihlášení získá/ztratí administraci po změně
role bez nového loginu. Běžný přístup k účtu po odebrání ADMIN zůstává funkční.

Administrativní služba znovu pod společným zámkem ověří aktuální ADMIN a platnost
relace. Zámek sdílí bootstrap i DB triggery; změny, audit a idempotentní výsledek
jsou jedna transakce. Trigger zapisuje do řídicího řádku také kvůli konfliktu
souběžných zapisujících transakcí při snapshotové izolaci. Budoucí rušení účtů
musí respektovat stejné pořadí: řídicí řádek, účty, další záznamy.

Správu vlastní role API odmítá. Rolí ADMIN lze spravovat pouze jiné aktivní účty
s ověřeným e-mailem. Kontrola posledního administrátora platí navíc i na DB vrstvě.
Privilegovaný správce DB může změnit schéma nebo vypnout triggery; toto není
bezpečnostní hranice proti takovému správci. Role se mimo bootstrap mění přes API.
Podklady k databázovým zárukám: [PostgreSQL triggery](https://www.postgresql.org/docs/17/sql-createtrigger.html)
a [viditelnost změn ve funkcích](https://www.postgresql.org/docs/17/spi-visibility.html).

## REST API

Všechny cesty vyžadují přihlášení a aktuální ADMIN. Cookies nadále vyžadují CSRF,
mobilní Bearer tok zůstává zachován. Odpovědi mají Cache-Control: no-store.

| Metoda a cesta | Vstup | Výsledek |
| --- | --- | --- |
| `PUT /api/v1/admin/users/{accountId}/roles/ADMIN` | UUID účtu, hlavička `Idempotency-Key` jako UUID; bez těla | 204, udělení ADMIN |
| `DELETE /api/v1/admin/users/{accountId}/roles/ADMIN` | Stejné parametry, vlastní klíč; bez těla | 204, odebrání ADMIN |
| `GET /api/v1/admin/audit-events` | Volitelné filtry a stránkování | 200 s `items`, `page`, `size`, `hasMore` |

Opakovaný grant již existující role nebo odebrání neexistující role je 204 bez
dalšího obchodního auditu. Stejný Idempotency-Key se stejným cílem a operací vrátí
původní 204 bez opakování. Jiný cíl či metoda se stejným klíčem stejného aktéra je
409 IDEMPOTENCY_CONFLICT. Aktuální oprávnění se kontrolují i před replay.

Filtry auditu: `actorId`, `targetId` (UUID), `eventType`, `from` (UTC Instant,
včetně), `until` (UTC Instant, bez hranice), `page` 0–10000, `size` 1–100 (výchozí
50). Pořadí je occurredAt sestupně, potom ID sestupně; stránkování přes offset není
neměnný snapshot při souběžném přidávání událostí. DTO neobsahuje autentizační údaje
ani e-maily/jména. Příklad:

```http
GET /api/v1/admin/audit-events?eventType=ADMIN_GRANTED&page=0&size=20
```

Chyby: 400 INVALID_REQUEST (UUID, enum, chybějící klíč), 400 INVALID_AUDIT_FILTER
(rozsah stránky/časů), 401 bez přihlášení, 403 bez role nebo s neplatným CSRF,
403 SELF_ROLE_CHANGE_FORBIDDEN, 409 ADMIN_TARGET_UNAVAILABLE (neexistující,
neaktivní nebo neověřený cíl), 409 LAST_ADMIN_REQUIRED, 409 IDEMPOTENCY_CONFLICT.
Při souběžném odebrání role může druhý již rozběhnutý požadavek skončit 403
ADMIN_REQUIRED po nové kontrole v transakci. Technická chyba necommitne částečnou změnu.

## Bootstrap prvního administrátora

Jde o **ruční administrační operaci**, ne veřejný endpoint ani automatický start.
Provádí se pouze v cílové DB, kterou správce výslovně vybral, po aplikaci V2.
Testovací a vývojový bootstrap jsou oddělené; přihlašovací údaje nezapisovat do Gitu
ani do argumentu příkazové řádky. Žádný následující příkaz Codex sám nespouští.

1. Běžnou registrací vytvoř účet a ověř e-mail (lokálně přes schránku).
2. Z přihlášeného `GET /api/v1/auth/me` převezmi jeho interní UUID.
3. Správce v psql zkontroluje `SELECT current_database(), current_user;` a
   `SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank;`.
   Databáze a role musí odpovídat zamýšlenému prostředí, V2 musí být aplikovaná.
4. Spusť skript s UUID daného účtu. Skript vyžaduje oprávnění k tabulkám, nikoli
   databázový SUPERUSER; samotný přihlašovací účet aplikace tím SUPERUSER nezískává.

Pokud má správce lokální psql klient a existující vývojový tunel 5433:

```powershell
$bootstrapAccountId = Read-Host 'UUID ověřeného účtu prvního administrátora'
# Vývojová DB, uživatel rallye; heslo si psql vyžádá, nevkládej ho do příkazu.
psql -h 127.0.0.1 -p 5433 -U rallye -d rallye_dev -W `
  -v ON_ERROR_STOP=1 -v "account_id=$bootstrapAccountId" `
  -f ops/admin/bootstrap-first-admin.sql
```

Pro vyhrazenou testovací DB použij samostatně port 5434, `-U rallye_test -d rallye_test`.
Pro OCI lze využít existující psql uvnitř kontejneru, bez instalace další infrastruktury.
Správce nejprve přečte skript a vybere skutečnou DB/roli; z kořene repozitáře pak
například v PowerShellu předá jeho obsah do již autorizovaného vzdáleného příkazu:

```powershell
$sshTarget = Read-Host 'Existující SSH uživatel@host'
$bootstrapAccountId = [guid](Read-Host 'UUID ověřeného účtu')
# Příklad výslovně pro rallye_dev; skutečné DB oprávnění ověří správce před spuštěním.
Get-Content -LiteralPath ops/admin/bootstrap-first-admin.sql -Raw | ssh $sshTarget `
  "docker exec -i rallye-postgres-1 psql -U rallye -d rallye_dev -v ON_ERROR_STOP=1 -v account_id=$bootstrapAccountId"
```

Tento příklad předpokládá existující lokální socketové oprávnění uvnitř kontejneru.
Pokud neplatí, použije správce svůj schválený postup přihlášení; nemění se HBA,
Docker porty ani OCI firewall a heslo se nepřidává do shellového příkazu.

Skript `ops/admin/bootstrap-first-admin.sql`:

- Parametr je interní UUID, nikoli e-mail; aktivní stav a ověření znovu ověří.
- Zamkne admin_control a cílový účet. První úspěch uloží ADMIN i audit
  ADMIN_BOOTSTRAPPED s actorKind OPERATOR v jedné transakci.
- Opakování pro stejný již existující ADMIN je bezpečný no-op bez dalšího auditu.
- Jiný účet po dokončeném bootstrapu se odmítne, i kdyby někdo neoprávněně odstranil
  role; trvalý příznak zabraňuje znovuotevření bootstrapu.
- Souběžné spuštění pro dva různé účty umožní inicializovat jen jednoho z nich.
- Chyba zastaví psql a transakce se nepotvrdí; neobcházet ji resetem admin_control.
- Chybějící řádek admin_control i rozpor ADMIN existuje / bootstrapped=false
  se odmítnou. Skript tento stav automaticky neopravuje; správce musí zjistit
  příčinu. Role trigger při normálním bootstrapu nastaví příznak ve stejné transakci.
  Aplikační služba při chybějícím řádku také odmítne pokračovat bez zámku.

Ověření po bootstrapu: se stávající nebo novou webovou relací otevři
`GET /api/v1/admin/audit-events?eventType=ADMIN_BOOTSTRAPPED`; potom uděl ADMIN
jinému ověřenému účtu přes PUT a zkontroluj ADMIN_GRANTED. Veřejný `/auth/me` se
kvůli této etapě nemění a role v něm zatím nejsou uvedeny.

## Audit bezpečnostních operací 2A

Události: LOGIN_SUCCEEDED, LOGOUT_SUCCEEDED, PASSWORD_CHANGED,
PASSWORD_RESET_COMPLETED, ADMIN_GRANTED, ADMIN_REVOKED, ADMIN_BOOTSTRAPPED.
U běžných operací je actorKind USER a interní ID aktéra; u resetu ID účtu
autorizovaného jednorázovým tokenem. Záznam nemá heslo, token, jejich hash, IP,
e-mail ani libovolná klientská metadata. Neúspěšný login zapisuje pouze obecný
provozní log, bez existence/identity účtu a bez databázového auditu.

AuditService vyžaduje již existující transakci (MANDATORY). Selhání auditu vrací zpět
vytvoření relace, odvolání relace, změnu/reset hesla i změnu role. Požadavek na reset
a registrace nejsou události dokončeného resetu nebo úspěšného loginu.

## Testy a předání

Unit/HTTP testy: AdminServiceTest, AdminHttpTest, BootstrapScriptTest a rozšířený
AuthServiceTest, spolu se zachovanými testy 2A. Běh bez DB: `.\mvnw.cmd verify`.

AdministrationIT používá stávající TestDatabaseInitializer, RALLYE_TEST_DB_* a
profil it. Pro nevratný příznak bootstrapu a souběžné transakce vytváří Flyway
v rallye_test vlastní schéma `it_admin_<náhodné UUID>`. Připojení je připnuté pouze
na něj, V1/V2 i skutečný bootstrap se ověří v izolaci. Na konci odstraní výhradně
toto vlastní schéma včetně vlastních fixture; nikdy nemaže public, cizí účty ani
společný admin_control. Název a skutečná DB/role se před odstraněním ověřují.
Testovací role musí mít CREATE uvnitř rallye_test podle existující infrastruktury.
Při pádu procesu může vlastní testovací schéma zůstat; uklidí je správce až po
ověření názvu a toho, že už žádný test neběží. Neprovádí se Flyway clean ani DROP DB.

IT pokrývá souběžný bootstrap, změny oprávnění již přihlášeného účtu, replay a
konflikt klíče/retenci, ochranu posledního ADMIN, souběžné vzájemné odebrání rolí,
auditní filtry a rollback při chybě auditu pro všechny čtyři operace 2A.
Stávající 2A IT zůstávají zapojené. Integrační testy spouští uživatel:

```powershell
.\mvnw.cmd -Pit verify
```

Uživatel provedl původní integrační běh nad rallye_test: 21 z 23 případů prošlo,
dva rollback testy skončily na nesprávném očekávání výjimky (podrobnosti níže).
Následné dostupné Failsafe XML reporty obsahují 24 úspěšných IT bez chyb;
nejde o vlastní DB běh Codexu. Uživatel nyní potvrdil Postman scénáře administrace:
login, čtení auditu, grant/revoke, zákaz vlastní role, replay, konflikt klíče
a audit bootstrapu, přihlášení a rolí. Po závěrečné opravě kontroly admin_control
uživatel potvrdil úspěšný běh rozšířené sady 26 IT: `.\mvnw.cmd -Pit verify` nad rallye_test, BUILD SUCCESS. Etapa 3b.1 je ověřena; nejde o vlastní DB běh Codexu.
Návod na Postman je níže; skutečné SMTP zůstává odloženo před nasazení.

Vlastní běh Codexu `mvnw.cmd verify` s JDK 21 při závěrečné kontrole: **BUILD SUCCESS**, 90 testů,
0 failures, 0 errors, 0 skipped, JAR sestaven. Součástí je kompilace všech IT,
nikoli jejich databázový běh. Postman: JSON, 33 požadavků proti skutečným
controllerům a syntaxe 55 skriptů ověřeny staticky, bez HTTP požadavků.
Přetrvávají neblokující Maven metadata HTTP 401 z Flyway/Red Gate a upozornění
Mockito na dynamické připojení agenta; žádná přihlašovací data se kvůli tomu nemění.

### Oprava očekávání rollback testů

Úplné stack trace v TXT i XML Failsafe reportu ukazují původní
IllegalStateException("Test audit failure") z AuditStore.append, přeloženou
PersistenceExceptionTranslationInterceptor a JPA překladačem na
InvalidDataAccessApiUsageException. Řetězec obsahuje transakční interceptory
AuditService.record i volající AdminService/AuthService. Příčinou není skutečná
PostgreSQL výjimka. Původní assertThrows skončil před kontrolou dat; z tohoto běhu
tedy nelze vydávat rollback dotčených scénářů za prokázaný.

Produkční logika ani migrace se kvůli tomuto selhání nemění. Opravený AdministrationIT
ověřuje nejen přeložený typ, ale i identitu přesně injektované původní příčiny.
Simulovaná chyba nyní vzniká až po skutečném INSERTu auditu. Po dokončení každé
chybující služby test v nové read-only REQUIRES_NEW transakci porovná účet, hash
hesla (bez jeho výpisu), relace, jednorázové tokeny, role, příkazy a audit se stavem
před operací. Test nesmí běžet ve vnější testovací transakci.

Samostatný nový případ vyvolá chybu až po skutečném uložení idempotentního příkazu:
role, audit i příkaz již byly zapsané, a všechny se musí vrátit zpět. Úspěšné
opakování se stejným klíčem a opakované použití resetovacího tokenu po selhání
navíc ověřují, že neuvízla evidence ani spotřebovaný token. Rozšířená sada má
24 integračních případů; jejich následné Failsafe reporty již obsahují úspěšný běh.
Závěrečná kontrola přidala dva další případy pro konzistenci admin_control.

AuditTransactionTest bez DB používá skutečné Spring transakční a repository
interceptory a stejný HibernateJpaDialect. Potvrzuje překlad a původní příčinu,
jediné volání rollbacku vnějšího transakčního manageru, commit při úspěchu a zákaz
zápisu bez obchodní transakce. Záznamový transakční manager tohoto unit testu
nenahrazuje ověření skutečně vrácených PostgreSQL dat v AdministrationIT.

## Přehled souborů 3b.1

- `src/main/resources/db/migration/V2__administration_and_audit.sql`.
- `ops/admin/bootstrap-first-admin.sql`.
- `administration/api/AdminController.java`, `identity/application/AdminService.java`,
  `identity/persistence/AdminStore.java`, `audit/application/AuditService.java`
  a `audit/persistence/AuditStore.java` pod stávajícím kořenovým Java package.
- Rozšířené `AuthService`, `AccountStore`, `AuthSessionFilter`, `AuthConfiguration`
  a `ApiErrors` pro audit, aktuální role a bezpečné mapování validačních chyb.
- `AdminServiceTest`, `AdminHttpTest`, `BootstrapScriptTest`, `AdministrationIT`
  a rozšířený `AuthServiceTest` ve stávajícím testovacím package identity.
- `postman/Rallye-Auth-2A.postman_collection.json`, `AGENTS.md`, `README.md`,
  `docs/backend-plan.md`, `docs/testing.md` a tento návod.

Rozpracovaná Rallye_specifikace.md, předchozí specifikace i migrace V1 zůstávají
nedotčené. Specifikace 0.6.0 se při této implementaci nepřepisuje.

## Postman

Existující `postman/Rallye-Auth-2A.postman_collection.json` má novou složku
administrace. Po registraci/aktivaci dvou účtů a ručním SQL bootstrapu jednoho
nastav lokálně `adminEmail`, `adminPassword`, `adminTargetId` (UUID druhého účtu).
Neexportuj skutečná hesla ani cookies do repozitáře.

Přihlas administrátora novým požadavkem ve složce. Dále čti audit, proveď grant,
opakuj grant se stejným klíčem, zkontroluj filtr auditu a proveď revoke jiným klíčem.
`adminGrantKey` a `adminRevokeKey` se při prvním odeslání automaticky vytvoří a
pak se zachovají pro replay; pro nový nezávislý příkaz je vymaž nebo nahraď novým UUID.
Všechny PUT/DELETE používají stejný automatický CSRF tok jako POST v 2A.

### Diagnostika DELETE: 400, 409 a CSRF cookie

`Idempotency-Key` se v AdminController.revoke převádí na `UUID`. Používej běžný
36znakový zápis `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` s hexadecimálními znaky,
například `0c389aba-137e-4893-a699-dbf31a2fac72`. Nejde o libovolný textový klíč:
`test-revoke-20261021-001` nelze převést a vrátí 400 INVALID_REQUEST. Samostatná
textová validace délky není zavedena; rozhoduje převod Springu na UUID.
MethodArgumentTypeMismatchException vzniká při vazbě argumentů před zavoláním
metody controlleru/služby. ApiErrors.invalid ji zpracuje bez výpisu stack trace.

V Postmanu ponech hlavičku `Idempotency-Key: {{adminRevokeKey}}`. Pro novou operaci
vymaž hodnotu adminRevokeKey: skript vygeneruje nové UUID. Při opakování téže
operace hodnotu ponech. Grant a revoke potřebují různé klíče. Evidence patří účtu
administrátora, nikoli přihlašovací relaci; nový login konflikt neodstraní.
Stejný účet, klíč, cíl a DELETE v rámci 24 hodin vrátí původní 204 bez změny role
a bez dalšího auditu. Jiný cíl nebo PUT místo DELETE vrací 409 IDEMPOTENCY_CONFLICT.
Z pouhé odpovědi 409 nelze určit, která předchozí operace klíč použila.

Spring Security 7.1.1 při této konfiguraci STATELESS přidává SessionManagementFilter.
Po AuthSessionFilter rozpozná načtenou autentizaci a přes CsrfAuthenticationStrategy
vymaže původní token (CookieCsrfTokenRepository.saveToken(null), Max-Age=0).
To nastává také při úspěšném DELETE 204; nejde o důkaz selhání CSRF. Před každým
měnícím webovým požadavkem proto načti GET /api/v1/auth/csrf, zachovej jeho cookie
a odešli token z JSON v X-XSRF-TOKEN. Kolekce tento postup zajišťuje automaticky.
Neplatný/chybějící CSRF vrací 403 ACCESS_DENIED, nikoli popsané 400 INVALID_REQUEST.

AdminHttpTest pokrývá skutečný cookie/CSRF tok bez testovacího nahrazení CSRF:
neplatný textový klíč skončí při bindingu bez zavolání služby; dva DELETE se stejným
UUID a čerstvým CSRF projdou; cookie se maže i při 204. AdminServiceTest ověřuje
replay odebrání i z jiné platné relace stejného účtu bez dalších zápisů a konflikt
při změně cíle. Tyto testy nevyžadují PostgreSQL; produkční logika se kvůli této
diagnostice nemění.

Negativní scénáře jsou oddělené: chybějící klíč 400, vlastní role 403 a konflikt
klíče 409. Ověř také 401 po odhlášení a 403 po přihlášení běžného uživatele.
Ochranu posledního ADMIN včetně přímých DB pokusů ověřuje izolovaný IT; v reálné
vývojové DB záměrně nemaž jedinou administrátorskou roli ani admin_control.

Omezení: všechny administrativní operace včetně čtení auditu nyní sdílejí krátký
řádkový zámek; pro malé soukromé použití je to přiměřené. Přímé SQL mimo schválený
bootstrap není aplikační API a nezajišťuje samo audit každého ručního zásahu.
Audit zatím nemá correlation ID ani payload, protože aktuální události obsahují
jen bezpečná pevná pole. Posádky, rušení účtů a auditní úklid se nepředstírají jako hotové.


## Čtecí API uživatelů pro webovou administraci

`GET /api/v1/admin/users` je dostupné pouze aktuálnímu ADMIN. Parametry:

- `page`: od 0, nejvýše 10000; výchozí 0.
- `size`: 1–100; výchozí 50.
- `query`: nepovinný text do 254 znaků, hledá část jména nebo e-mailu bez rozlišení velikosti písmen. Okrajové mezery se odstraní; `%`, `_` a `!` jsou obyčejné znaky, nikoliv vyhledávací operátory.
- `captainEligible`: výchozí `false`; při `true` vrací jen aktivní účty s ověřeným e-mailem.

Odpověď 200 obsahuje `items`, `page`, `size`, `hasMore`. Řazení je podle
`created_at DESC, id DESC`; souběžné změny mohou mezi stránkami posunout výsledky.
Detail `GET /api/v1/admin/users/{accountId}` vrací jeden stejný objekt:

```json
{
  "id": "00000000-0000-4000-8000-000000000001",
  "displayName": "Testovací kapitán",
  "email": "captain@example.test",
  "roles": ["USER"],
  "status": "ACTIVE",
  "identityProvider": "LOCAL",
  "captainEligible": true
}
```

`identityProvider` je LOCAL/GOOGLE/APPLE, případně null, pokud účet dosud identitu
nemá. USER je existující implicitní oprávnění přihlášeného uživatele; ADMIN se
vrací podle aktuální databáze pro aktivní ověřený účet. Nezavádí se nová role.
Seznam zahrnuje i nezrušené PENDING_EMAIL a DISABLED účty; anonymizované účty
nevrací. Neexistující, fyzicky smazaný nebo anonymizovaný detail vrací
404 ACCOUNT_NOT_FOUND. Neplatné parametry vrací 400 INVALID_REQUEST, chybějící
přihlášení 401 a neadministrátor 403. Odpovědi mají Cache-Control: no-store.
Neobsahují hesla, hashe, subject, tokeny ani revokační materiál.

Pro výběr kapitána použij například
`GET /api/v1/admin/users?query=kapitan&captainEligible=true&page=0&size=20`.
Zvolené `items[].id` předávej existujícímu
`PUT /api/v1/crews/{id}/captain` jako `captainAccountId` spolu s aktuální `version`
posádky a novým UUID `Idempotency-Key` (a pro cookie relaci s CSRF).
Čtecí GET požadavky Idempotency-Key nepotřebují. Změna kapitána znovu ověřuje
aktuální dostupnost cíle; výsledek vyhledávání není oprávněním ani rezervací účtu.
Stávající konflikty CAPTAIN_UNAVAILABLE, CREW_VERSION_CONFLICT a CAPTAIN_UNCHANGED
zůstávají zachované. Nové přiřazovací API ani migrace nevznikají.
