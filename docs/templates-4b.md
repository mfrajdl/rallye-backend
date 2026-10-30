> Historický popis původní etapy: aktuální pravidla CODE a penalizací níže nahrazují původní hashované/write-only ukládání.

# Etapa 4b – konfigurace stanovišť a připravenost šablon

> Aktuální instrukce a obrázky po V19: [templates-markdown.md](templates-markdown.md). Starší popis instrukcí níže zachycuje původní etapu.

## Platná rozhodnutí a rozsah

Tento dokument popisuje konfiguraci nad základem 4a; aktuální společný API přehled
pro editor je v [templates-4d.md](templates-4d.md). Schválené zadání 4b má
přednost před staršími návrhy a v příslušném rozsahu doplňuje specifikaci 0.8.0:

- **GPS + CODE znamená AND**: pokud jsou zapnuté obě kontroly, budou v budoucím
  průběhu nutné obě. Neexistuje přepínač AND/OR. Připravenost vyžaduje úplnost obou.
- Statistické verzování je zrušené. Budoucí odhady použijí historické průjezdy
  příslušného stanoviště bez oddělování statistických verzí.
- Maximum stanoviště je nezáporné, náhradní při přeskočení/nedokončení, není strop.
  Všechny ostatní bodové sazby včetně společného přeskočení jsou podepsané long.
- V 4b byly instrukce původně prostý text. Aktuální 4c/4d API používá výhradně
  sanitizované `instructionsHtml` a soukromé obrázky. Editor není součástí backendu.

4b nevyhodnocuje GPS, podvržení polohy, účastnický kód, splnění stanoviště ani
výsledky či časové body. Ukládá pouze konfiguraci a ověřuje připravenost. Konání,
duplikování a frontend nevznikají. Historická vydání specifikací se nepřepisují.

## API: konfigurace

Stávající CRUD, stránkování, pořadí, Idempotency-Key při vytváření a společná
`template.version` zůstávají zachované. Všechny operace vyžadují aktuální ADMIN
z databáze a platnou relaci. Cookie POST/PUT/DELETE vyžadují CSRF; Bearer se řídí
stávajícími pravidly. Odpovědi mají Cache-Control: no-store.

Nový endpoint:

`PUT /api/v1/race-templates/{id}/stages/{stageId}/configuration`

```json
{
  "version": 2,
  "gps": {
    "enabled": true,
    "latitude": 50.1,
    "longitude": 14.4,
    "radiusMeters": 100,
    "outsidePenaltyPoints": -2,
    "spoofingPenaltyPoints": 10
  },
  "code": {
    "enabled": true,
    "newCode": " MOST ",
    "wrongAttemptPenaltyPoints": 3
  },
  "timePenalty": {
    "enabled": true,
    "intervalSeconds": 60,
    "pointsPerInterval": -1
  },
  "delayed": {
    "enabled": true,
    "minimumDurationMs": 300000,
    "pointsPerSecond": 2
  }
}
```

Vrací 200 s celým detailem šablony a novou verzí. Všechny čtyři skupiny a jejich
boolean `enabled` jsou povinné. Ostatní hodnoty mohou být v DRAFT null/neuvedené;
vynechání běžného parametru jej při tomto úplném PUT vymaže. Neznámá pole,
`completionMode`, klientský hash či číselné řetězce se odmítají.

Výjimkou je `code.newCode`: vynechání/null ponechá dosavadní hodnotu, vypnutí CODE ji nemaže. Prázdný nový kód je chyba. Normalizace je `strip().toUpperCase(Locale.ROOT)`, 1–128 Unicode code pointů. Ukládá se skutečný text; normalizovaná shoda je no-op. ADMIN čtení vrací například:

```json
{"enabled":true,"codeConfigured":true,"value":"KOD","valueUnavailable":false,"wrongAttemptPenaltyPoints":null,"effectiveWrongAttemptPenaltyPoints":0}
```

Hash se nevystavuje. Účastnické API skutečný kód vracet nesmí.
`timePenalty.intervalSeconds` se vrací v sekundách; interní intervalMs není API.

### Platnost hodnot

- Šířka -90 až 90, délka -180 až 180 včetně hranic, konečná čísla.
- Poloměr je konečný a >0. NaN a nekonečno se odmítají.
- `intervalSeconds` je celé číslo >0. Převod na ms používá Math.multiplyExact;
  nejvýše `9223372036854775` sekund. Desetinné či přetékající vstupy jsou 400.
- `minimumDurationMs` a `expectedDurationMs` jsou celé >=0, s ms přesností.
- `maxPenaltyPoints` je celé >=0 včetně nuly; nemá význam průběžného ořezávání.
- Všechny bodové sazby přijímají celý rozsah signed 64 bitů, včetně nuly.
- Časové pravidlo a DELAYED lze zapnout současně; nijak nenahrazují GPS/CODE.
- Vyplněné hodnoty se validují **i u vypnutého pravidla**. Chybějící hodnota je
  přípustná pro DRAFT, neplatná hodnota nikoliv.

## Instrukce po navazující 4c

Původní textové `instructions` z 4b bylo nahrazeno výhradně `instructionsHtml`.
`PUT /api/v1/race-templates/{id}/stages/{stageId}` přijímá například:

```json
{"version":1,"name":"Stanoviště u mostu","expectedDurationMs":600000,"maxPenaltyPoints":100,"instructionsHtml":"<p>Najděte označené místo.</p>"}
```

Vynechané instructionsHtml obsah zachová, null jej odstraní. Základní pole PUT
se nadále nahrazují. HTML je serverově sanitizované, limit je 100 KiB UTF-8 před/po;
GET vrací pouze instructionsHtml. Detaily bezpečného formátování a obrázků v
[templates-4c.md](templates-4c.md), souhrnný kontrakt v [templates-4d.md](templates-4d.md).
POST stanoviště má základní kontrakt bez instrukcí: následuje PUT údajů a konfigurace.

## Připravenost a změna stavu

`POST /api/v1/race-templates/{id}/validations` nemá povinné tělo ani
Idempotency-Key. Je to kontrola bez změny dat šablony, verze či auditu; pro cookie
relaci přesto zachovává obvyklou CSRF ochranu POST.

```json
{
  "templateId": "00000000-0000-4000-8000-000000000001",
  "version": 3,
  "ready": false,
  "errors": [
    {
      "stageId": "00000000-0000-4000-8000-000000000002",
      "field": "code.codeConfigured",
      "code": "REQUIRED",
      "message": "Doplňte povinné pole: code.codeConfigured."
    }
  ]
}
```

Vrací 200 i pro neúplnou šablonu. Chyby mají stageId (null u chyby šablony), pole,
REQUIRED/INVALID a pevnou srozumitelnou zprávu bez citlivých či zadaných hodnot.
Kontrola vyžaduje platný základ šablony, alespoň jedno stanoviště, pořadí 1..N,
platný název a neprázdné instrukce každého stanoviště, očekávanou dobu (nevyplněné maximum znamená 0),
alespoň jednu kontrolu a úplnost všech zapnutých kontrol i časových pravidel.
U GPS jsou povinné i obě sazby; u CODE skutečný kód a účinná sazba (nullable override dědí společnou hodnotu). **Kompletní GPS nenahradí nekompletní zapnuté CODE.**

`PUT /api/v1/race-templates/{id}/status` přijímá:

```json
{"version":3,"status":"READY"}
```

Při úplnosti vrátí 200, stav READY a verzi 4. Při neúplnosti vrátí 409
TEMPLATE_NOT_READY ve stávajícím ProblemDetail formátu s `templateId`, `version`
a `errors`; změna ani audit nevzniknou. Stejný endpoint umožňuje READY -> DRAFT.

Změna parametrů/instrukcí stanoviště, pořadí, přidání či smazání stanoviště a
společné sazby převádí READY zpět na DRAFT. Jméno/popisek samotné šablony stav
nemění. No-op nemění status, version, updatedAt ani audit; to zahrnuje i znovu
zadaný tentýž normalizovaný CODE či opakovaný stav READY s platnou verzí.
Zastaralá version zůstává 409 TEMPLATE_VERSION_CONFLICT i pro jinak shodný vstup.

## Transakce, audit a ochrana tajemství

Zámky zůstávají admin_control -> účet aktéra -> šablona -> stanoviště. Validace,
změna stavu a konfigurace jsou v transakci se stejným parent lockem, takže souběh
publikace a editace nemůže schválit cizí neúplnou verzi. Optimistická kontrola
vybere jediného vítěze dvou změn stejné verze. Chyba auditu rollbackne konfiguraci,
hash, stav i čas/verzi.

Nové události: TEMPLATE_STAGE_CONFIGURED a TEMPLATE_READIED. Instrukce používají
TEMPLATE_STAGE_UPDATED, návrat DRAFT stávající TEMPLATE_DRAFTED. Audit obsahuje
pouze identifikátory a typ operace, nikoliv instrukce, kódy či hashe.

Konfigurační PUT **nikdy nevolá CommandStore**; případný přidaný Idempotency-Key
na něm nezakládá replay. Ochranu opakování tvoří version a skutečné no-op porovnání.
CODE není součástí vytvářecích DTO ani uložených příkazů. Zůstává jeden existující
24hodinový ledger pro POST vytváření; nový mechanismus ani úložiště nevzniká.

Request objekty s CODE i interní config mají redigované toString. Test ověřuje
MVC TRACE bez úniku kódu. Stávající zákaz payload/exception DEBUG/TRACE loggerů,
bezpečné access logy a sanitizace neočekávaných výjimek zůstávají. Externí proxy
nesmí logovat request body; kód patří pouze do JSON těla konfigurace, ne do URL.

Ostatní chyby: 400 INVALID_REQUEST pro neplatné hodnoty/typy; 401/403 jako v 4a;
404 TEMPLATE_NOT_FOUND/TEMPLATE_STAGE_NOT_FOUND; 409 TEMPLATE_VERSION_CONFLICT.
Readiness specifické chyby neobsahují nové autentizační informace.

## Migrace V17

`V17__stage_configuration_and_readiness.sql`:

- odstraní zrušený sloupec `statistics_version`;
- přejmenuje dosud neveřejné `instructions_html` na textové `instructions`;
- vynutí interní interval v celých sekundách (ms dělitelné 1000) a neprázdný hash,
  pokud je nastaven;
- případné dosavadní READY vrátí na DRAFT s jedním zvýšením technické verze a
  systémovým auditem s důvodem READINESS_RULES_4B. Tyto řádky nebyly schváleny
  validátorem 4b a nelze je bez kontroly automaticky certifikovat.

Stávající identifikátory, pořadí, data konfigurace a kódové hashe se zachovají.
Neplatná předem ručně uložená data porušující nová omezení migraci zastaví,
neopravují se odhadem. V16 ani jiné aplikované migrace se nepřepisují.
Migration guard rušení účtů i předběžná kontrola mazání šablon přijímají přesnou
V17 a odmítají neznámou V18/špatný název souboru. Mazání nadále zůstává v rozpoznaném
schématu bez konání; žádné tabulky konání se nepřidávají.

## Testy a ruční postup

Unit/HTTP testy pokrývají AND a povinné obě konfigurace, locale/normalizovanou délku
CODE, normalizaci/no-op, ADMIN čtení a nepřítomnost v MVC logu, rozsahy/typy, souběžně zapnuté
časové konfigurace, převod sekund, plain text, report i stavové chyby a CSRF.
PostgreSQL TemplatesIT navazuje na regresi 4a: skutečný Argon2id a jeho zachování,
READY/AND, signed sazby, kód mimo audit/ledger, rollback reálných INSERT auditu,
souběh READY/editace, V16 -> V17 a odstranění sloupce bez ztráty konfigurace.

PostgreSQL testy spouští uživatel podle docs/testing.md, proti rallye_test:

```powershell
.\mvnw.cmd -Pit verify
```

Cíleně lze použít `.\mvnw.cmd -Pit -Dit.test=TemplatesIT verify`.
Codex PostgreSQL testy nespouští; nové ověření 4b zatím není potvrzené.

Ruční API průchod: přihlásit ADMIN, získat CSRF pro cookie klienta; vytvořit DRAFT
šablonu a stanoviště; PUT instrukcí s poslední verzí; zapnout GPS a neúplné CODE;
validations musí vypsat chybějící kód, PUT READY vrátit 409 bez změny. Doplnit kód
a sazbu, ověřit GET pouze s codeConfigured, validations ready=true a přejít READY.
Zopakovat stejný normalizovaný kód s aktuální verzí (no-op); změnit radius (DRAFT).
Interval 1.5 s, 0 s, přetékající s a záporné maximum musí vrátit 400. Skutečné
GPS ověření, zadávání účastnických kódů a bodování se v této etapě nezkouší.


### Výsledek automatického ověření

Codex spustil Maven Wrapper verify na Microsoft JDK 21.0.12.1: **BUILD SUCCESS**,
477 testů, 0 failures, 0 errors, 0 skipped; JAR sestaven. Cílený běh konfigurace,
readiness a dotčeného migration guardu: 60 úspěšných testů. TemplatesIT nyní
obsahuje 35 případů (18 nových scénářů 4b); pouze se zkompilovaly, PostgreSQL se
nespouštělo. V17 a integrace čekají na uživatelské ověření. git diff --check a
kontrola whitespace nových souborů prošly. V16, .gitignore a pracovní specifikace
zůstaly obsahově beze změny. Žádný commit ani push.


### Oprava izolace spy v PostgreSQL rollback testu

Uživatelský běh měl 312 testů, 1 failure, 0 errors. Jediné selhání bylo v
`configurationAndPublicationAuditFailuresRollbackHashStateVersionAndAudit`:
spy započítal úspěšnou konfiguraci při přípravě fixture i následnou záměrně
selhávající konfiguraci stejného stanoviště. Nešlo o dva audity jedné operace.
Helper `failedAudit` nyní před ověřovanou operací čistí pouze historii volání
Mockito (`clearInvocations`), nikoliv databázové řádky. Přesně jedno volání se
nadále vyžaduje. Helper ověřuje reálný INSERT uvnitř transakce a návrat původního
počtu auditních řádků po rollbacku; kontroly konfigurace, CODE hashe, stavu,
verze a celkových auditů/command záznamů zůstávají zachované. Produkční kód se
nemění. Oprava čeká na opakování PostgreSQL uživatelem:
`.\mvnw.cmd -Pit -Dit.test=TemplatesIT verify`, případně celé `-Pit verify`.

Po opravě helperu Codex ověřil Maven Wrapper verify bez PostgreSQL: **BUILD SUCCESS**,
477 testů, 0 failures/errors/skipped, JDK 21; opravený TemplatesIT se zkompiloval.
`git diff --check` prošel. Nové PostgreSQL ověření zůstává na uživateli.


## Navazující změna 4c

Výše popsané textové pole `instructions` platilo pro 4b. Implementace 4c je
nahrazuje výhradně HTML polem `instructionsHtml` se serverovou sanitizací a
vazbami na soukromé obrázky; viz [templates-4c.md](templates-4c.md).
Statistické verze zůstávají zrušené. Uživatel při zahájení 4c potvrdil úspěšné
PostgreSQL ověření 4b; nové V18 a 4c scénáře se tím nepovažují za ověřené.

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


Aktuální rozšíření stanovišť (30. 10. 2026): normalizace diakritiky, varianty CODE,
INFORMATION/Pokračovat a RADAR jsou popsané v [stage-extensions-api.md](stage-extensions-api.md)
a [OpenAPI](stage-extensions-openapi.yaml). Tento oddíl doplňuje starší popis;
nové code.newCode se ukládá po strip + uppercase ROOT, se zachováním diakritiky.
Diakritika se odstraňuje pouze při porovnávání. Existující hodnoty se nepřepisují.
STANDARD zachovává GPS+CODE AND.
