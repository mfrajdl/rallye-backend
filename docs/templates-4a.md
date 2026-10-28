# Etapa 4a – základ správy šablon akcí

> Aktuální instrukce a obrázky po V19: [templates-markdown.md](templates-markdown.md). Starší popis instrukcí níže zachycuje původní etapu.

Historický rozsah 4a. Aktuální společný kontrakt pro webový editor je
[templates-4d.md](templates-4d.md); konfiguraci doplňuje 4b, HTML/obrázky 4c,
duplikování 4d. Statistické verzování bylo v 4b zrušeno.

## Rozsah a stav

Implementován je JDBC modul `templates`: CRUD šablon a rozpracovaných stanovišť,
lineární pořadí, jedna technická verze celého celku, ADMIN, audit a existující
24hodinová idempotence pro vytváření. PostgreSQL ověření uživatel potvrdil jako BUILD SUCCESS.
Nová šablona je DRAFT. Každý aktuální ADMIN může spravovat všechny šablony;
nevzniká oprávnění vlastníka ani nová role.

Etapa nepřidává konání, účasti, průběh ani výsledky. Nepřidává duplikování,
GPS/CODE konfiguraci/ověřování, časové výpočty, HTML ani obrázky. Budoucí pole
v databázi nejsou veřejný funkční kontrakt. Stanoviště v této etapě nemá
zapisovací pole `instructionsHtml`; bez sanitizace se HTML vůbec nepřijímá.

Schválená upřesnění etapy mají přednost před obecnějším popisem specifikace 0.8.0:
DRAFT dovoluje neúplná stanoviště; max_penalty_points je nezáporné včetně nuly,
není průběžným stropem. Společná sazba přeskočení i budoucí ostatní bodové sazby
jsou podepsaná celá čísla. Historická specifikace ani pracovní kopie se nemění.

## Základní API (aktualizováno po 4d)

Základ URL: `/api/v1/race-templates`. Všechny endpointy vyžadují platnou relaci
aktuálního ADMIN. Cookie mutace vyžadují stávající CSRF postup; čisté Bearer
požadavky se řídí stávající bezpečnostní konfigurací. Odpovědi mají no-store.

| Metoda a cesta | Vstup | Odpověď |
|---|---|---|
| GET základ | query, status, page, size | 200, stránka souhrnů |
| POST základ | name, description, skipPenaltyPoints; Idempotency-Key | 201, Created + Location |
| GET /{id} | UUID | 200, celý detail včetně seřazených stanovišť |
| PUT /{id} | version, name, description, skipPenaltyPoints | 200, celý detail |
| DELETE /{id} | version v query | 204 |
| PUT /{id}/status | version, status="DRAFT" nebo "READY" | 200, celý detail |
| GET /{id}/stages | | 200, pole stanovišť |
| POST /{id}/stages | version, name?, expectedDurationMs?, maxPenaltyPoints?; Idempotency-Key | 201, Created + Location |
| GET /{id}/stages/{stageId} | | 200, stanoviště |
| PUT /{id}/stages/{stageId} | version, name?, expectedDurationMs?, maxPenaltyPoints?, instructionsHtml? | 200, celý detail |
| DELETE /{id}/stages/{stageId} | version v query | 200, celý detail s novým pořadím |
| PUT /{id}/stage-order | version, stageIds | 200, celý detail |

Přechod do READY od 4b vyžaduje úspěšnou úplnou validaci; viz aktuální
[společný kontrakt](templates-4d.md). Změny stanovišť,
jejich pořadí či společné sazby převádějí případný READY na DRAFT. Samotná změna
názvu/popisu šablony status zachovává. Shodná normalizovaná data nemění version,
updatedAt, status ani audit. Neaktuální version vrací konflikt i u no-op požadavku.

`page` je 0–10000 (výchozí 0), `size` 1–100 (výchozí 50). `query` hledá podřetězec
názvu bez rozlišení velikosti písmen, nejvýše 100 znaků; %, _ a ! jsou doslovné.
`status` je nepovinný DRAFT/READY. Stránka: items, page, size, hasMore. Řazení
createdAt DESC, id DESC; nejde o stabilní snímek napříč souběžně měněnými stránkami.

Název šablony je povinný neprázdný, nejvýše 100 Unicode code pointů, okrajové
mezery se odstraní. Popis je nepovinný, nejvýše 2000 code pointů. Název stanoviště
je nepovinný do 100 code pointů; prázdný/bílý se normalizuje na null. Tyto technické
limity odpovídají dosavadním textovým konvencím backendu. Limity platí před strip.
Očekávaná doba a maximum mohou být null; vyplněné musí být >=0. Čísla v JSON musí
být celá, v rozsahu signed 64 bitů; desetinná čísla, číselné řetězce a přetečení
se odmítají. `skipPenaltyPoints` je povinné a přijímá i záporné hodnoty a nulu.
PUT je úplné nahrazení dostupných polí, nikoli PATCH: vynechaná nepovinná pole
stanoviště se vymažou. Neznámá pole se odmítají. HTML patří výhradně do instructionsHtml v PUT
stanoviště; CODE do konfiguračního PUT. Stav se mění stavovým endpointem.

### Příklady

Vytvoření (Idempotency-Key: nové UUID):

```json
{"name":"Jarní 5P","description":"Rozpracovaná trasa","skipPenaltyPoints":-5}
```

Created odpověď obsahuje `id`, `templateId`, `version`; u šablony jsou obě UUID
shodná. U vytvořeného stanoviště je id UUID stanoviště a templateId UUID rodiče.
Neobsahuje kopii celého editovaného obsahu; po vytvoření načtěte detail GET.

První rozpracované stanoviště (nový Idempotency-Key):

```json
{"version":0,"name":"První stanoviště","expectedDurationMs":null,"maxPenaltyPoints":0}
```

Výsledek má version 1. Přidání dalšího stanoviště musí poslat právě tuto novou
verzi. Nová stanoviště se přidávají na konec. Přesun libovolného stanoviště řeší
úplná permutace aktuálních UUID, například:

```json
{"version":2,"stageIds":["00000000-0000-4000-8000-000000000002","00000000-0000-4000-8000-000000000001"]}
```

Detail: id, name, description, skipPenaltyPoints, status, version, createdAt,
updatedAt, stages. Souhrn má stejná metadata bez stages. Stanoviště: id,
sequenceNo (od 1), name, expectedDurationMs, maxPenaltyPoints.
Správný CODE ani jeho hash nejsou v žádném DTO.

### Chyby a opakování

Stávající ProblemDetail formát: 400 INVALID_REQUEST, 401 neplatná/chybějící relace,
403 ACCESS_DENIED v HTTP filtru či ADMIN_REQUIRED v aplikační vrstvě;
404 TEMPLATE_NOT_FOUND / TEMPLATE_STAGE_NOT_FOUND;
409 TEMPLATE_VERSION_CONFLICT / IDEMPOTENCY_CONFLICT.

POST vytváření používá společný CommandStore (actor + key, 24 hodin).
Stejný klíč a stejné normalizované parametry vrací původní Created bez nového
zápisu. Změněné parametry včetně version nebo jiná operace se stejným klíčem
vrací IDEMPOTENCY_CONFLICT ještě před kontrolou zastaralé verze/existence rodiče.
Aktuální oprávnění a relace se ověřují i pro replay. Replay může vrátit původní
potvrzení i po následném odstranění objektu, nesmí jej znovu vytvořit.
PUT/DELETE mají optimistic version, nepotřebují Idempotency-Key. Při nejistotě
po síťové chybě načtěte detail; opakování se starou verzí vrací 409, smazaný objekt
404. Žádná změna se opakováním neprovede podruhé.

## Schéma a transakce

V16__race_templates.sql vytváří race_template a template_stage. Doby a body
používají bigint, časové okamžiky timestamptz(3). Budoucí konfigurační sloupce
jsou v samotné 4a neúplné/null a neveřejné. Technická version rodiče chrání
souběžné úpravy; aktuální rozšíření schématu a konfigurací popisuje 4b.

Pořadí má kladné pozice a odložitelný UNIQUE(template, sequence). Odložený trigger
ověřuje při commit souvislou řadu 1..N. Přesun přepíše pořadí v jedné transakci,
smazání přečísluje zbylé položky. Trigger dětských zápisů zamyká rodiče a zakazuje
přesun řádku do jiné šablony či změnu jeho UUID. Aplikace získává zámky v pořadí
admin_control -> účet aktéra -> šablona -> stanoviště. Všechny mutace i audit
jsou v jedné transakci; chyba auditu nebo idempotentního zápisu vše vrátí zpět.

Auditní události TEMPLATE_CREATED/UPDATED/DELETED/DRAFTED,
TEMPLATE_STAGE_CREATED/UPDATED/DELETED a TEMPLATE_REORDERED obsahují jen actor,
čas a UUID cíle s target_type TEMPLATE nebo TEMPLATE_STAGE. Nikdy obsah instrukcí,
kódy ani hashe. Při smazání se audit zachová. CommandAccounts zná nové formáty,
target_id není účet; related_account_ids zahrnuje aktéra. Úklid při rušení účtu
umí záznamy zpracovat včetně textových výskytů e-mailu. Migration guard účastí
výslovně přijímá přesnou V16 a nadále odmítá neznámou V17 či změněný název V16.

Konání dosud neexistují. Smazání je povolené pouze v rozpoznaném schématu V1–V16
bez tabulky race_event. Neznámá další migrace nebo přítomnost této tabulky zastaví
mazání odpovědí 503 TEMPLATE_USAGE_CHECK_UNAVAILABLE, bez změn dat. Při zavedení
konání se musí tato dočasná kontrola nahradit skutečnou referenční kontrolou a FK
bez kaskádového smazání konání; současná kontrola nikdy nepředstírá počet účastí.
Šablonu lze nyní smazat kaskádově se stanovišti, protože žádná konání nejsou.

## Ověření a ruční průchod

Rychlé testy: TemplateServiceTest, TemplateHttpTest, CommandAccountsTest a test
migration guardu. PostgreSQL TemplatesIT běží pouze přes TestDatabaseInitializer
v samostatném schématu it_templates_* databáze/role rallye_test. Obsahuje CRUD,
READY invalidaci/no-op, pořadí a DB omezení, shared idempotenci, optimistic souběh,
souběžné vytváření, rollback reálného auditu a command INSERT, privacy cleanup,
V15 -> V16 upgrade a přesné rozpoznávání migrací. PostgreSQL testy Codex nespouští.

```powershell
.\mvnw.cmd -Pit verify
```

Cílený PostgreSQL běh: `.\mvnw.cmd -Pit -Dit.test=TemplatesIT verify`.
Veškeré opt-in a ochrany rallye_test z docs/testing.md zůstávají povinné.

Ruční API průchod proti lokálnímu backendu: přihlásit ADMIN, zachovat cookie a
získat aktuální CSRF token; vytvořit šablonu; přidat dvě neúplná stanoviště vždy
s poslední verzí; načíst detail; obrátit pořadí; zopakovat nezměněný PUT a ověřit
stejnou verzi; zkusit změnu se starou verzí (409); smazat stanoviště a ověřit
přečíslování od 1. Jako běžný uživatel ověřit 403. READY, GPS/CODE, HTML a obrázky
se v této etapě ručně nenastavují. Mazání provádět jen na vlastních testovacích
šablonách. Úplný Postman scénář lze sestavit ze zdokumentovaných požadavků;
stávající autentizační kolekce se touto etapou nemění.

### Výsledek rychlého ověření

Codex: JDK 21.0.12.1, Maven Wrapper `verify` – **BUILD SUCCESS**, 456 testů,
0 failures, 0 errors, 0 skipped; JAR sestaven. Samostatný cílený běh měl 49
úspěšných testů. Připravených 17 scénářů TemplatesIT bylo zkompilováno, nikoli
spuštěno v tomto běhu Codexu. Následné kompletní `.\mvnw.cmd -Pit verify`
proti rallye_test uživatel potvrdil jako **BUILD SUCCESS**, včetně nových
TemplatesIT a V16. Nejde o vlastní PostgreSQL běh Codexu. Ruční API průchod
zatím potvrzen nebyl.
`git diff --check` i whitespace kontrola nových souborů prošly.
