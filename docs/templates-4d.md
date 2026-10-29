> Historický popis původní etapy: aktuální pravidla CODE a penalizací níže nahrazují původní hashované/write-only ukládání.

# Etapa 4d – duplikování a společný kontrakt editoru šablon

> Aktuální rozšíření V20 a závazný kontrakt knihovny souborů: [template-files.md](template-files.md). Doplňuje odkazy, dopředné reference a read-only varování; původní obrazové endpointy zůstávají.

Tento dokument je aktuální společný přehled implementovaného API etap 4a–4d.
Aktuální Markdown a pojmenované obrázky popisuje [templates-markdown.md](templates-markdown.md).
4a–4c byly podle uživatele úspěšně ověřeny nad PostgreSQL. Nové 4d PostgreSQL scénáře včetně opravy AuditExportHttpTest uživatel
potvrdil jako BUILD SUCCESS; nejde o potvrzení hotového webového editoru.

## Přístup a společné kontrakty

Základ je `/api/v1/race-templates`. Všechny endpointy včetně čtení vyžadují
platnou Rallye relaci a aktuální ADMIN podle DB. Cookie mutace vyžadují CSRF,
Bearer požadavky dodržují dosavadní pravidla. Bez relace 401 (anonymní mutaci
může ještě dříve odmítnout CSRF s 403); běžný uživatel 403 `ADMIN_REQUIRED`.
Odpovědi jsou neveřejné/no-store. UUID, JSON čísla a pole mají striktní typy;
body DTO odmítají neznámá pole. Číselné řetězce, desetinná celá čísla, záporná
verze a přetečení vracejí 400 `INVALID_REQUEST`.

`Created` (201 + Location) je vždy `{id, templateId, version}`. U nové šablony
jsou obě UUID shodná; u stanoviště označuje `id` nový dílčí objekt.
Po vytvoření použijte GET pro načtení editovatelného detailu.

`View` je `{id, name, description, skipPenaltyPoints, status, version, createdAt,
updatedAt, stages}`. Časy jsou UTC ISO-8601; `stages` jsou v pořadí 1..N.
`Summary` má stejná základní pole bez `stages`.

`StageView` je `{id, sequenceNo, name, expectedDurationMs, maxPenaltyPoints,
instructionsMarkdown, instructionsRenderedHtml, gps, code, timePenalty, delayed}`. **Stanoviště nemá samostatný
sloupec/API pole `description`**; formátovaný obsah je v `instructionsMarkdown`.
Popis šablony existuje a kopíruje se. 4d nepřidává nové obchodní pole.
ADMIN DTO vrací skutečný CODE podle doplnění V22; hash nevystavuje.

## Všechny endpointy

Cesty v tabulce jsou relativní k základu. Hlavička `Idempotency-Key` je UUID;
`version` je verze celé šablony, nikoliv samostatná verze stanoviště.

| Metoda a cesta | Vstup | Úspěšná odpověď |
| --- | --- | --- |
| `GET /` | query?, status?, page=0, size=50 | 200 `{items: Summary[], page, size, hasMore}` |
| `POST /` | `{name, description?, skipPenaltyPoints}`, Idempotency-Key | 201 Created, verze 0 |
| `GET /{id}` | UUID šablony | 200 View |
| `PUT /{id}` | `{version, name, description?, skipPenaltyPoints}` | 200 View |
| `DELETE /{id}` | query `version` | 204 |
| `PUT /{id}/status` | `{version, status: DRAFT nebo READY}` | 200 View |
| `POST /{id}/validations` | bez těla a Idempotency-Key | 200 Report |
| `GET /{id}/stages` | | 200 StageView[] |
| `POST /{id}/stages` | `{version, name?, expectedDurationMs?, maxPenaltyPoints?}`, Idempotency-Key | 201 Created |
| `GET /{id}/stages/{stageId}` | | 200 StageView |
| `PUT /{id}/stages/{stageId}` | `{version, name?, expectedDurationMs?, maxPenaltyPoints?, instructionsMarkdown?}` | 200 View |
| `DELETE /{id}/stages/{stageId}` | query `version` | 200 View |
| `PUT /{id}/stage-order` | `{version, stageIds: UUID[]}` | 200 View |
| `PUT /{id}/stages/{stageId}/configuration` | ConfigurationInput níže | 200 View |
| `POST /{id}/images` | multipart `file`, query `version`, Idempotency-Key | 201 ImageCreated (viz Markdown kontrakt) |
| `GET /{id}/images` | | 200 ImageView[] |
| `GET /{id}/images/{fileName}` | | 200 binární JPEG/PNG, nosniff |
| `DELETE /{id}/images/{fileName}` | query `version` | 204 |
| `POST /{id}/duplicate` | bez těla, Idempotency-Key | 201 Created nové šablony |
| `POST /{id}/stages/{stageId}/duplicate` | `{version}`, Idempotency-Key | 201 Created nového stanoviště |

Neexistuje parametr řazení šablon. SQL řadí `created_at DESC, id DESC` před
stránkováním; page je 0–10000, size 1–100. `query` (do 100 Unicode code pointů)
hledá podřetězec názvu bez rozlišení velikosti písmen, `%`, `_` a `!` jsou doslovné.
`status` je nepovinné DRAFT/READY. Stránky nejsou dlouhodobým snapshotem při změnách dat.

Název šablony je povinný, nejvýše 100 Unicode code pointů; okrajové mezery se
odstraní, limit se kontroluje před odstraněním. Popis je nepovinný do 2000.
Názvy nejsou unikátní. Název stanoviště může v DRAFT být null, maximum je 100;
prázdný/bílý se normalizuje na null. expectedDurationMs a maxPenaltyPoints mohou
v DRAFT být null, jinak celé >=0. Základní PUT je úplné nahrazení těchto údajů,
ne PATCH. Výjimkou je vynechané instructionsMarkdown: zachová obsah; null jej odstraní.
POST stanoviště zatím nepřijímá instrukce ani konfiguraci: následují příslušné PUT.

## Konfigurace stanoviště

```json
{
  "version": 2,
  "gps": {
    "enabled": true, "latitude": 50.1, "longitude": 14.4, "radiusMeters": 100,
    "outsidePenaltyPoints": -2, "spoofingPenaltyPoints": 10
  },
  "code": {"enabled": true, "newCode": "MOST", "wrongAttemptPenaltyPoints": 3},
  "timePenalty": {"enabled": true, "intervalSeconds": 60, "pointsPerInterval": -1},
  "delayed": {"enabled": true, "minimumDurationMs": 300000, "pointsPerSecond": 2}
}
```

Všechny čtyři skupiny a jejich boolean enabled jsou povinné. Ostatní parametry
mohou být v DRAFT null, ale vyplněné musí být validní i u vypnutých kontrol.
Vynechané běžné parametry se při tomto úplném PUT vynulují na null.
Výjimkou je code.newCode: vynechání/null zachová hodnotu, vypnutí CODE ho také nemaže.
Prázdný nový kód je chyba. Normalizace je strip + uppercase Locale.ROOT, poté
1–128 code pointů a uložení normalizovaného textu. Stejný normalizovaný kód je no-op.
Odpověď code je pouze `{enabled, codeConfigured, wrongAttemptPenaltyPoints}`.

GPS: konečná latitude -90..90, longitude -180..180, radiusMeters >0.
Čas: intervalSeconds celé >0, nejvýše 9223372036854775; uložení je v ms,
API vrací sekundy. expectedDurationMs/minimumDurationMs jsou celé >=0 v ms.
Všechny bodové sazby jsou signed 64bit, kromě maxPenaltyPoints >=0.
Maximum je náhradní penalizace při přeskočení/nedokončení, **nikoliv strop**.
GPS + CODE znamená AND, časové pravidlo a DELAYED jsou nezávislé.
Backend zde pouze ukládá a validuje nastavení, nevyhodnocuje pokusy ani výsledky.

## Markdown, obrázky a soubory

Zdrojové `instructionsMarkdown` se ukládá beze změny (100 KiB UTF-8). Read-only
`instructionsRenderedHtml` vzniká na backendu pomocí CommonMark/GFM a jsoup.
Raw HTML je escapované; externí obrázky a nebezpečné URL nejsou povolené.
Podrobný kontrakt, příklady, názvy, chyby, souborový recovery a V19 jsou v
[templates-markdown.md](templates-markdown.md). Použití se eviduje z AST a transakčně
sdílí verzi, DRAFT/READY a audit se zdrojem. Samotný upload/delete nepoužitého obrázku
nezvýší verzi ani nezmění READY; aktuální verze je přesto povinná.

## Duplikování

### Celá šablona

```http
POST /api/v1/race-templates/{sourceId}/duplicate
Idempotency-Key: nové UUID
Authorization: Bearer ...
```

Bez těla a bez source version: kopíruje se aktuální konzistentní snímek zamčeného
zdroje. Není to změna zdrojové šablony; její version, updatedAt, stav i obsah se
nezmění. Kopie má nové UUID, DRAFT, version=0, nové časy a nové UUID všech stanovišť.
Název je původní + ` (kopie)`; původní část se podle Unicode code pointů zkrátí tak,
aby celek měl nejvýše 100 znaků. Popis a společná sazba se zachovají, kolize názvů
jsou povoleny. Není nový parametr přejmenování ani pravidlo číslovaných kopií.

Každý aktuální sloupec konfigurace stanoviště se kopíruje pomocí INSERT SELECT,
včetně skutečného CODE a případného legacy hashe.
Neměnné identifikátory, šablona, pořadí a Markdown mají výslovné cílové hodnoty.
V nové šabloně zůstává původní pořadí. DRAFT může být neúplný i prázdný.

**Všechny** obrázky (použité i nepoužité) se fyzicky kopírují pod stejnými názvy
do soukromého adresáře nové šablony. Bajty se nemění. Relativní Markdown zůstává
přesně stejný; explicitní URL skutečných Image uzlů AST se přepíší na novou šablonu.
Kód, ostatní text ani odkazy se nepřepisují. Vazby používají nová interní UUID,
frontend je nepotřebuje. Před zápisem se kontroluje shoda AST, vazeb a knihovny.
Nekonzistence bezpečně odmítne kopii; změna jedné šablony neovlivní druhou.

Odpověď 201: `{id: newTemplateId, templateId: newTemplateId, version: 0}`,
Location `/api/v1/race-templates/{newTemplateId}`. Audit TEMPLATE_DUPLICATED má
nový target a metadata `{sourceId: oldTemplateId, templateId: newTemplateId}`.
Jde o audit vytvoření s uvedením původu, nikoliv obsahovou kopii v auditu.

### Jedno stanoviště

```http
POST /api/v1/race-templates/{id}/stages/{stageId}/duplicate
Idempotency-Key: nové UUID
Content-Type: application/json

{"version": 7}
```

Nové UUID má stejnou kompletní konfiguraci a přesně stejný uložený Markdown. Stanoviště
se vloží hned za zdroj, následující se přečíslují, pořadí je 1..N. Fyzické soubory
se nekopírují a URL se nemění; nové vazby sdílí původní obrázky téže šablony.
Šablona přejde do DRAFT a version vzroste právě o 1. Popis stanoviště se nevymýšlí:
model má name a instructionsMarkdown, nikoliv samostatné description.

Odpověď 201: `{id: newStageId, templateId: id, version: 8}`,
Location `/api/v1/race-templates/{id}/stages/{newStageId}`. Audit
TEMPLATE_STAGE_DUPLICATED má nový target a metadata `{sourceId: oldStageId, templateId: id}`.

## Verze, idempotence a transakce

Verze celého agregátu je společná textům, Markdownu, konfiguraci, pořadí i stanovištím.
Materiální změna (včetně společné sazby a duplikování stanoviště) invaliduje READY.
Pouhá změna názvu/popisu šablony stav zachová. No-op nezvyšuje verzi, nemění čas
ani audit; stale version je konflikt i pro no-op. Statistické verze nejsou.

READY vyžaduje neprázdný název šablony a alespoň jedno kompletní stanoviště;
**všechna stanoviště** musejí mít název, skutečný obsah instrukcí, expectedDurationMs,
efektivní maxPenaltyPoints (null znamená 0), nejméně jednu zapnutou GPS/CODE kontrolu a úplné parametry všech
aktivních kontrol i časových pravidel. `POST validations` vrací 200 Report
`{templateId, version, ready, errors:[{stageId, field, code, message}]}` i při chybách.
Chybové code je REQUIRED/INVALID, zprávy neobsahují tajemství. Přechod READY při
neúplnosti vrací 409 TEMPLATE_NOT_READY s reportem, bez změny stavu/verze/auditu.
DRAFT validaci úplnosti nevyžaduje. Časová pravidla a DELAYED nepočítají výsledky.

Idempotency-Key je povinný pro vytvoření šablony, stanoviště, obrázku a obě
kopírovací operace. Stávající ledger (actor + key) je společný pro operace,
retence 24 hodin. Stejná operace/parametry vrátí původní Created se stejným 201;
odlišná operace nebo parametry vrátí 409 IDEMPOTENCY_CONFLICT.
Pro kopii šablony je požadavek `[sourceId]`, pro stanoviště `[templateId, stageId, version]`.
Replay se vyhodnocuje po aktuální autorizaci, před novým vyhledáním zdroje a
kontrolou verze. Přesný replay přežije změnu či odstranění zdroje během retence;
není to nový snímek. CODE, jeho hash, Markdown ani obrazové bajty se do těchto příkazů
nezapisují. PUT/DELETE/readiness nevyžadují nový ledger a dodržují verzování.

Pořadí zámků kopie šablony je společný file lock → admin/account → zdrojová šablona;
krátká kontrola oprávnění před file lockem se nejprve dokončí. Kopie stanoviště
držena pouze DB transakcí ve standardním admin/account/template pořadí. Vložení,
obrázky, stanoviště, vazby, audit a ledger se commitnou jako celek. Selhání vše
rollbackne a nové nereferencované soubory odstraní. Při nejasném commitu se soubor
nejprve ověří proti DB; při nedostupné DB zůstane přes API nedostupný pro pozdější
24hodinový úklid podle 4c. Opuštěný fyzický soubor může po pádu procesu přetrvat do
úklidu, nevzniká částečná dostupná šablona. V19 mění pouze instrukce a identifikaci obrázků; kopírování nepřidává vlastní framework.

## Chyby a omezení

| HTTP | Kód / význam |
| --- | --- |
| 400 | INVALID_REQUEST (typ, rozsah, chybějící parametr, neznámé pole) |
| 400 | INVALID_INSTRUCTIONS_MARKDOWN, INVALID_IMAGE_NAME, TEMPLATE_IMAGE_REFERENCE_INVALID |
| 401 | UNAUTHENTICATED / neplatná relace |
| 403 | ADMIN_REQUIRED, ACCESS_DENIED (např. CSRF) |
| 404 | TEMPLATE_NOT_FOUND, TEMPLATE_STAGE_NOT_FOUND, TEMPLATE_IMAGE_NOT_FOUND |
| 409 | TEMPLATE_VERSION_CONFLICT, IDEMPOTENCY_CONFLICT, TEMPLATE_NOT_READY, TEMPLATE_IMAGE_IN_USE, TEMPLATE_IMAGE_NAME_CONFLICT |
| 413/415 | PHOTO_TOO_LARGE / PHOTO_UNSUPPORTED_FORMAT |
| 400 | INVALID_PHOTO, INVALID_PHOTO_DIMENSIONS |
| 503 | PHOTO_STORAGE_UNAVAILABLE; TEMPLATE_COPY_INCONSISTENT (nesoulad zdrojových dat) |
| 503 | TEMPLATE_USAGE_CHECK_UNAVAILABLE (neznámé schéma/konání při mazání) |

Přesný migration guard po V19 a budoucí kontrola použití šablony se zachovávají.
Konkrétní konání zatím neexistují. Při jejich zavedení je potřeba explicitně
napojit pravidlo zákazu mazání použité šablony, nikoliv obejít fail-closed guard.
Neimplementují se účasti, start, pokusy, výsledky, statistiky ani klientské UI.

## Ruční průchod a testy

1. Přihlaste se jako ADMIN, načtěte detail zkušební šablony a verzi.
2. POST duplicate s novým klíčem → 201, nové id; GET kopie → DRAFT, version=0,
   nové UUID stanovišť/obrázků. Zdroj zůstane přesně stejný.
3. Stáhněte použité i nepoužité obrázky obou šablon. Ve všech nových instrukcích
   musí být nové interní URL; sdílený obrázek se nesmí fyzicky duplikovat vícekrát.
4. Zopakujte původní POST se stejným klíčem → stejná Created bez další kopie.
5. POST stage duplicate s aktuální version → 201; detail ukáže kopii hned za zdrojem,
   pořadí 1..N, DRAFT a version+1. Obrázkové URL zůstaly stejné.
6. Přesný replay je opět 201; změna version se stejným klíčem je IDEMPOTENCY_CONFLICT,
   původní version s novým klíčem je TEMPLATE_VERSION_CONFLICT.
7. Změňte kopii a ověřte nezávislost originálu; běžný uživatel musí dostat 403.

Unit/HTTP testy pokrývají DTO, Location, ADMIN/CSRF, pořadí, replay, Unicode název,
remap pouze img zdrojů a úklid souborů při chybě. PostgreSQL testy doplňují kopii
DRAFT/READY, každé aktuální konfigurační pole, hash funkčnost, všechny obrázky,
neúplný DRAFT, nezávislost, audit/ledger/file rollback, souběhy a bezpečnost API.
PostgreSQL sadu Codex nespouští. Spusťte podle docs/testing.md proti rallye_test:

```powershell
.\mvnw.cmd -Pit verify
```

Pro React editor je backendový kontrakt funkčně doplněný. PostgreSQL část uživatel potvrdil jako BUILD SUCCESS. Není tím potvrzen frontendový ani ruční API průchod.


Závěrečné rychlé ověření: Maven Wrapper verify, JDK 21.0.12.1,
**BUILD SUCCESS, 529 testů bez chyb**, git diff --check bez chyb. Připraveno je
13 nových PostgreSQL scénářů 4d, pouze zkompilovaných. Předchozí známý race v
mock testu CSV exportu se při meziběhu opakoval; následné úplné verify prošlo,
exportní implementace ani jeho test se neměnily. Podrobnosti v testing.md.

Změny 4d: nové TemplateDuplicationController/TemplateDuplicationService,
copyStage/usedImages v TemplateStore, doménově omezený InstructionsHtml.copy,
transakční templateCopy audit a closed-list formáty v CommandAccounts.
Testy TemplateDuplicationServiceTest/TemplateDuplicationHttpTest, TemplatesIT,
InstructionsHtmlTest a CommandAccountsTest. Dokumentace 4a–4d, backend-plan a testing.
Předchozí rozpracované změny v repozitáři zůstávají zachovány a nejsou všechny
součástí této etapy. Bez commitu a pushe; chráněné uživatelské soubory se neměnily.


## Akceptační ověření a uzavření 4a–4d

Uživatel potvrdil kompletní `.\mvnw.cmd -Pit verify` proti rallye_test jako
**BUILD SUCCESS**, včetně 4d, migrací V16–V18 a opravy AuditExportHttpTest.
Předchozí poznámky o čekajícím běhu a nestabilitě jsou historické. Poslední
běžné verify Codexu mělo 548 úspěšných testů po opravě řízeného async executoru.
Backendový modul šablon 4a–4d je dokončený a automaticky ověřený; ruční API/editor
průchod není tímto potvrzen. Závěrečné uložení do Gitu bylo výslovně schváleno;
testy se při commitu znovu nespouštějí. Součástí jsou i související změny seznamu
posádek. Webový frontend ani konkrétní konání tímto nezačínají.

## Aktuální konfigurace po V22

CODE je normalizovaný skutečný text dostupný pouze ADMIN. `code.newCode` zůstává
vstupem; null zachová hodnotu, normalizovaná shoda je no-op. Čtení vrací `value`,
`valueUnavailable`, `enabled`, `codeConfigured`, nullable `wrongAttemptPenaltyPoints`
a `effectiveWrongAttemptPenaltyPoints`. Audit/idempotence neobsahují skutečnou hodnotu.
Staré hashe se nerekonstruují; postižené READY šablony se vrátí do DRAFT.

Create šablony přijímá `defaultWrongCodePenaltyPoints` (výchozí 0); update při
vynechání/null zachová původní hodnotu. Seznam i detail pole vracejí. Změna sazby
vrací READY do DRAFT. Override stanoviště null dědí společnou sazbu, 0 ji přepíše;
sazby jsou podepsané. `maxPenaltyPoints` může být null, efektivně 0 i v odpovědi;
nevyplnění nebrání READY. Duplikace šablon/stanovišť zachová skutečný kód a override,
duplikace celé šablony také společnou sazbu. Snapshot Akce zachová nezávislou kopii.
