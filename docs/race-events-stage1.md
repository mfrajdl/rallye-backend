# Plánovaná konání – první implementační etapa

Implementovaný backendový rozsah podle specifikace 0.9.0. Žádná registrace,
start, ukončování, průběhy, změny živé trasy, výsledky ani účastnické čtení.
Konání má vždy PLANNED. Termíny jsou pouze uložené nastavení a nic nespouštějí.
GPS/CODE mají závazné AND při zapnutí obou kontrol; samostatná kontrola
vyžaduje pouze sebe. Dosavadní konfigurace se nemění.

## API

Všechny endpointy vyžadují aktuálního aktivního ověřeného ADMIN a platnou relaci.
Cookie požadavky se změnou vyžadují CSRF; Bearer používá stávající autentizaci.
Nezavádí se nová role ani veřejný souborový endpoint.

| Metoda | URL | Výsledek |
|---|---|---|
| POST | `/api/v1/race-templates/{templateId}/events` | 201, Location, `{id, version: 0}` |
| GET | `/api/v1/race-events?query=&page=0&size=50` | `{items, page, size, hasMore}` |
| GET | `/api/v1/race-events/{id}` | Detail základních údajů |
| PUT | `/api/v1/race-events/{id}` | Úplná náhrada editovatelných základních údajů |
| GET | `/api/v1/race-events/{id}/stages` | Seřazený seznam stanovišť a bezpečných konfigurací |
| GET | `/api/v1/race-events/{id}/stages/{stageId}` | Detail stanoviště |
| GET | `/api/v1/race-events/{id}/files` | Seznam metadat a URL samostatné knihovny |
| GET | `/api/v1/race-events/{id}/files/{fileName}` | Autorizované čtení obsahu |

POST vyžaduje UUID hlavičku `Idempotency-Key` a tělo:

```json
{
  "templateVersion": 4,
  "name": "Podzimní projížďka",
  "description": null,
  "scheduledStartAt": "2026-11-01T10:00:00Z",
  "endAt": "2026-11-01T16:00:00Z"
}
```

`templateVersion` je aktuální verze zdroje, nikoliv budoucího konání. Zdroj se
zamkne a znovu ověří READY i úplnost všech referencí. Opakování téhož klíče a
stejných normalizovaných parametrů vrátí původní 201/UUID i při následné změně
zdroje; jiné parametry vracejí 409 IDEMPOTENCY_CONFLICT. Replay stále kontroluje
aktuální oprávnění. Používá společný CommandStore s retencí 24 hodin.

PUT používá stejné editovatelné údaje, ale místo templateVersion obsahuje `version`
konání. Název je povinný (do 100 Unicode znaků), popis volitelný (do 2000).
Oba termíny jsou volitelné a mají UTC význam; lze předat null. Pokud jsou oba
vyplněné, musí start předcházet konci. Ukládají se s přesností milisekund.
V této etapě se nepředpokládá automatické spuštění ani povinnost vyplnit termíny;
před skutečným spuštěním budou potřeba další kontroly. PUT nepřijímá změnu
stavu, zdroje, stanovišť ani pravidel. Skutečný no-op nemění verzi, čas ani audit.
Opakované použití staré verze po úspěšné změně vrátí konflikt.

Detail i položka seznamu obsahují `id`, `sourceTemplateId`,
`sourceTemplateVersion`, `name`, `description`, `skipPenaltyPoints`, `status`,
`scheduledStartAt`, `endAt`, `version`, `createdAt`, `updatedAt`.
Řazení: createdAt sestupně, ID sestupně. Query hledá literální podřetězec názvu
bez rozlišení velikosti písmen (%, _ jsou escapované), max. 100 znaků.
Page je od nuly, size 1–100. Nezavádí se výběr budoucích stavů.

Stanoviště obsahuje `id`, `sourceTemplateStageId`, `sequenceNo`, `name`,
`expectedDurationMs`, `maxPenaltyPoints`, `instructionsMarkdown`,
`instructionsRenderedHtml`, `gps`, `code`, `timePenalty`, `delayed`.
Konfigurace má stejné jednotky a bezpečná pole jako u šablon: CODE obsahuje jen
`enabled`, `codeConfigured`, `wrongAttemptPenaltyPoints`, nikdy hash či tajný kód.
Úplný hash se kopíruje uvnitř SQL bez bind parametru s hashem.

Seznam souborů je pole `{fileName, contentType, byteSize, width, height, url}`.
MD obsah se vrací jako samostatná sanitizovaná HTML stránka, obrázky jako data,
PDF jako `application/pdf` s attachment. Odpověď má no-store, nosniff,
no-referrer a restriktivní CSP. Relativní odkazy se v uloženém Markdownu zachovají;
renderování je vyhodnotí v knihovně konání. Explicitní interní odkazy včetně
pojmenovaných definic se při kopii přepíšou do `/race-events/{id}/files/...`.
Cyklické MD dokumenty se kopírují bez rekurzivního vkládání.

### Chyby

- 400 INVALID_REQUEST: chybějící/neplatná verze či klíč, neplatné údaje, pořadí termínů,
  neznámá tělová pole, neplatné stránkování. Neplatný název souboru používá
  existující INVALID_IMAGE_NAME; validátory obsahu zachovávají své FILE_* kódy.
- 401 UNAUTHENTICATED; 403 ADMIN_REQUIRED v aplikační kontrole nebo ACCESS_DENIED
  při odmítnutí filtrem/CSRF.
- 404 TEMPLATE_NOT_FOUND, RACE_EVENT_NOT_FOUND, RACE_EVENT_STAGE_NOT_FOUND,
  RACE_EVENT_FILE_NOT_FOUND.
- 409 TEMPLATE_NOT_READY, TEMPLATE_VERSION_CONFLICT, RACE_EVENT_VERSION_CONFLICT,
  IDEMPOTENCY_CONFLICT, TEMPLATE_IN_USE. RACE_EVENT_NOT_PLANNED je obranná
  aplikační kontrola; V21 nyní jiný stav nepovoluje.
- 503 RACE_EVENT_SNAPSHOT_UNAVAILABLE při nesouladu uloženého obsahu/metadat;
  nedostupné fyzické úložiště používá dosavadní PHOTO_STORAGE_UNAVAILABLE.
  Poškozený zdroj se nekopíruje; žádná chyba nesmí zanechat částečné konání.

## Snapshot, V21 a transakce

V21 přidává race_event, event_stage a event_file. Zdrojový template UUID a jeho
verze jsou uložené v konání; FK RESTRICT brání smazání použité šablony i mimo API.
Zdrojové UUID stanovišť jsou povinný historický původ, bez FK na živý zdrojový
řádek: jeho pozdější odstranění nesmí smazat snapshot. Přidávání stanovišť přímo
do konání není v této etapě povoleno; budoucí vlastní stanoviště budou vyžadovat
odpovídající rozšíření schématu, nikoliv falešný původ.

Schéma snapshotů přebírá aktuální kontrolované typy, NOT NULL a CHECK omezení
z modelu V20; nepřebírá zdrojové FK, triggery ani data. Přidává vlastní primární
klíče, FK na konání, jedinečné pořadí a case-insensitive klíč názvu souboru.
Budoucí změny konfigurace musejí výslovně řešit oba modely. Starší migrace se nemění.

Kopie zahrnuje všechna stanoviště a všechny soubory včetně nepoužitých.
Před kopírováním se znovu ověří typ a obsah, velikost/rozměry a u MD shoda
s uloženým zdrojem. Metadata a fyzické soubory mají nová UUID/prostor; nic se
nesdílí živým odkazem se šablonou. Společné aktuálně implementované pravidlo
skipPenaltyPoints se kopíruje. Nastavení členů/přihlášek v modelu šablon zatím
není implementované a touto etapou se nepředstírá jeho snapshot.

Konfigurace souborů: `rallye.races.file-directory`, výchozí
`${user.home}/.rallye/event-files`; prostředím lze použít
`RALLYE_RACES_FILE_DIRECTORY`. Uvnitř je oddělené
`race-events/{UUID}/images/{fileName}` (název images zachovává existující bezpečné
souborové operace, obsahuje i MD/PDF). Root musí být odlišný od šablonového rootu;
shoda se odmítne při konstrukci služby. Adresář není staticky veřejně obsluhovaný.

Pořadí zámků při vytvoření: nejprve samostatná dokončená autorizační kontrola,
poté storage šablon → storage konání → admin_control → účet aktéra → šablona.
Replay se vyhodnotí před novou kontrolou zdroje a jeho verze. Zámek šablony drží
konzistentní konfiguraci i pořadí; souborový zámek blokuje změnu knihovny. Čtení
souboru konání používá storage konání před DB zámky. Aktualizace základních údajů
používá admin_control → účet → konání a žádný souborový zámek nevyžaduje.

Konání, metadata, stanoviště, audit a idempotence jsou v jedné DB transakci.
Při selhání se nové fyzické soubory odstraní až po rollbacku a jen pokud DB
nepotvrdí referenci. Při neověřitelném výsledku commitu se soubor konzervativně
ponechá, nikoliv odstraní naslepo. Pád procesu může zanechat nereferencovaný
soubor: existující zamčený interní cleanup lze využít s opakovanou kontrolou DB
referencí a hranicí 24 hodin. Automatický scheduler ani veřejný cleanup endpoint
konání se nezavádí; provozní úklid není součástí automatické atomicity DB/FS.

Audit RACE_EVENT_CREATED a RACE_EVENT_UPDATED je povinný ve stejné transakci;
obsahuje UUID původu/verzi, nikoliv CODE, hash či obsah souborů. Nový formát
RACE_EVENT_CREATE je zapojen do CommandAccounts i souvisejícího privacy/retention
úklidu. Bezpečnostní audit nenahrazuje budoucí trvalou závodní historii.

TemplateStore chrání použitou šablonu konkrétním dotazem a FK, nikoliv plošným
zákazem všech šablon při existenci race_event. Guardy podporují pouze konkrétní
V21__planned_race_events.sql; neznámé budoucí migrace nadále odmítají.
PreRaceAccountParticipationFacts smí stále vracet false/false: konání nejsou
účasti a žádné přihlášky nevznikají. Před zavedením účastí se tento adaptér musí
nahradit skutečnými historickými dotazy, nikoliv jen povolit další číslo migrace.

## Ověření a ruční průchod

Unit/HTTP testy: EventServiceTest, EventMarkdownTest, EventHttpTest a dosavadní
regrese šablon, účtů, CommandStore a migration guardů. EventsIT používá vlastní
schéma a temp adresáře; skutečně ověřuje snapshot/CODE, soubory/odkazy, souběhy,
replay, smazání zdroje, rollback vytvoření i editace, ochranu oprávnění,
privacy cleanup a upgrade V20→V21. Bez skutečných externích poskytovatelů.

PostgreSQL testy spouští uživatel podle testing.md:

```powershell
.\mvnw.cmd -Pit "-Dit.test=EventsIT,TemplatesIT,AccountCancellationServiceIT" verify
# Případně kompletní ruční regrese:
.\mvnw.cmd -Pit verify
```

PostgreSQL ověření uživatel následně potvrdil jako BUILD SUCCESS (28. 10. 2026). Codex PostgreSQL testy nespouštěl.

Ruční API ověření: přihlásit ADMIN (cookie+aktuální CSRF nebo samostatný Bearer),
vybrat READY šablonu a její version, POST s novým UUID klíčem a uvedeným JSON,
GET detail/stages/files a otevřít MD/PDF/obrázek. Stejný POST zopakovat se stejným
klíčem a porovnat UUID. Upravit zdrojovou šablonu a ověřit nezměněné konání.
PUT konání s aktuální version musí zvýšit verzi jednou, no-op nikoliv. DELETE
použité šablony musí vrátit 409 TEMPLATE_IN_USE; běžný uživatel nedostane přístup
ani ke čtení souborů. Nepoužívat produkční DB pro automatické testy.

## Soubory této etapy

Nové: `races/api/EventController.java`, `races/application/EventService.java`,
`races/application/EventFiles.java`, `races/persistence/EventStore.java`,
`V21__planned_race_events.sql`, `identity/EventsIT.java`, `races/EventHttpTest.java`,
`races/EventMarkdownTest.java`, `races/application/EventServiceTest.java` a tento dokument.

Upravené: `AuditService`, `AuditStore`, `AuthConfiguration`,
`PreRaceAccountParticipationFacts`, `CommandAccounts`, `TemplateService`,
`TemplateStore`, `TemplateImageFiles`, `ImageNames`, `InstructionsMarkdown`;
regrese `PreRaceAccountParticipationFactsTest`, `TemplateServiceTest`, `TemplatesIT`;
`Zavody_specifikace_v0.9.0.md`, `backend-plan.md`, `testing.md`.
Změny předchozího Markdown/file-library úkolu v ostatních souborech se zachovávají;
`.gitignore`, rozpracovaná Rallye_specifikace.md a migrace V1–V20 se touto etapou nemění.

Ověření první etapy Codexem: JDK 21.0.12.1, Maven Wrapper verify – **BUILD SUCCESS,
639 testů, 0 failures, 0 errors, 0 skipped**, včetně regresí původních modulů.
Cílený předchozí běh měl 70 testů bez chyb. Uživatel následně potvrdil
PostgreSQL integrační ověření proti rallye_test jako **BUILD SUCCESS**
(28. 10. 2026), včetně 19 připravených EventsIT a V21. Codex tento běh
neprováděl a databázi sám neměnil. Ruční ověření API není tímto potvrzeno.
Git diff --check prošel; chráněné uživatelské soubory a V1–V20 se neměnily.
