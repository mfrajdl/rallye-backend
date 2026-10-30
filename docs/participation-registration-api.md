# Registrace posádek do konání – registration-api-v1

**Stav: schválený a implementovaný kontrakt.** Datum: 2026-10-30.
Verze kontraktu není Git tag ani verze aplikace. Strojový protějšek:
[participation-registration-openapi.yaml](participation-registration-openapi.yaml), OpenAPI 3.1.0.
Základ registrací byl dokončen v předchozí etapě. Návaznost lifecycle a vlastní
seznam/souhrn jsou implementované aditivní změny; uživatel potvrdil PostgreSQL
i ruční frontendové integrační ověření. Starší validační záznamy níže popisují
kontraktační fázi, aktuální stav uvádějí závěrečné oddíly.

## 1. Zdroje a změny proti specifikaci

Zdroje: AGENTS.md, specifikace 0.9.0 (§2.2–2.5, 4.7, 10, 11, 14.2), backend-plan.md,
race-events-stage1.md; EventController/EventService/EventStore, CrewController/CrewService/CrewStore,
AuthConfiguration/AuthController/AuthCookies, ApiErrors, CommandStore/CommandAccounts,
AccountCancellationService/PreRaceAccountParticipationFacts a migrace V3, V6, V21–V25.
Analýza připravenosti z chatu nemá samostatný projektový soubor.

Existuje ADMIN správa PLANNED konání, nezávislý READY snapshot, soubory/instrukce,
CRUD posádek, aktuální kapitánství, cookie/Bearer, CSRF, audit a CommandStore (24 h).
V21 zatím dovoluje pouze PLANNED. Registrace ani skutečný start neexistují.
Stávající ADMIN GET /api/v1/race-events/{id} a jeho knihovna se běžným účtům neotevírají.

Následující nová rozhodnutí uživatele mají v tomto návrhu přednost; specifikaci 0.9.0 neměníme:

| Téma | Nové pravidlo a rozpor s 0.9.0 |
|---|---|
| Členové | Bez pevného limitu počtu, membersText nejvýše 2000 znaků; nahrazuje limit/výchozích 5 v §2.4. |
| Předvyplnění | Poslední registrace stejné posádky včetně dětí; žádná živá vazba. Bez historie jméno kapitána a 0 dětí. |
| Uzamčení | Až individuální start, nikoli oficiální start nebo pozdní zařazení (§2.4). |
| Historický kapitán | Kapitán při individuálním startu, nikoli autor přihlášky. |
| Registrace | Bez intervalu do oficiálního startu; [from,to); ruční otevření umožňuje nové přihlášky i po startu do konce. Vyřešena otázka pozdního zařazení. |
| Časové hranice | Individuální start t < end_at; pokusy stanovišť t <= end_at. |
| Budoucí obsah | Jen aktuální a absolvovaná stanoviště/jejich povolené soubory; nikdy správný CODE. |
| Konec | Uživatel dodatečně potvrdil: neodstartovanou přihlášku po konci již neupravovat. |

## 2. Rozsah a odhlášení

V1 pokrývá stav, interval, override, vlastní posádky/předvyplnění, vytvoření přihlášky,
vlastní přehled/detail, změnu složení a ADMIN přehled. Start, zařízení, pokusy a výsledky nejsou součástí.

**Odhlášení se do v1 nezařazuje.** Specifikace nedefinuje jeho oprávnění, časové hranice,
obnovení ani opětovnou registraci. Nezavádět DELETE přihlášky, WITHDRAWN ani automatické
odhlášení při změně/rušení kapitána. Frontend nezobrazuje tlačítko odhlášení.
Pokud má být doplněno, vyžaduje samostatné rozhodnutí; není blokací zde definovaných operací.
V1 má jediný registrační stav REGISTERED, který není stavem průběhu. Použije plné
UNIQUE(race_event_id,crew_id): druhý řádek nevznikne ani s novým idempotence klíčem.
Scénář „po odhlášení“ proto v1 neposkytuje; nelze jej simulovat smazáním řádku.
Budoucí reaktivace versus nový historický cyklus není schválená, nesmí zničit starou historii.

## 3. Společné konvence a bezpečnost

- Základ /api/v1, JSON camelCase. Všechny položky DTO jsou required; volitelné hodnoty jsou explicitní null.
  Neznámé request klíče, chybějící povinné hodnoty, neplatné UUID/enum a scalar coercion jsou 400 INVALID_REQUEST.
- UUID string; časy RFC3339/Instant, ve výstupu UTC Z, přesnost ms. Vstupní offset převést do UTC,
  submilisekundy zkrátit. Bez místních časů bez offsetu.
- version je jako u stávajícího konání JSON integer int64 0..9223372036854775807.
  Frontend nesmí int64 zaokrouhlit přes JavaScript Number; pro velké hodnoty lossless parser.
  Počty jsou int32, celé a nezáporné, memberCount >= 1; odvozuje je server.
- Stávající platná RALLYE_SESSION cookie nebo Authorization: Bearer; v jednom požadavku nekombinovat.
  Aktivní ověřený účet, kde uvedeno aktuální ADMIN. Kapitánství není nová globální role.
- Cookie POST/PUT vyžaduje CSRF: GET /api/v1/auth/csrf vrací headerName/token;
  poslat token v aktuální X-XSRF-TOKEN a příslušné cookies. Po loginu aktuální token;
  nesdílet Cookie Jar/tokeny mezi uživateli. Bearer zachová stávající výjimku.
- POST create vyžaduje UUID Idempotency-Key; PUT ho nevyžaduje a nepoužívá ledger.
- Zápis Content-Type application/json; úspěšná odpověď application/json, Cache-Control: no-store.
  POST také Location. Chyby application/problem+json ve stávajícím formátu ProblemDetail.
- Nové GET konání jsou přístupné přihlášenému se znalostí eventId z odkazu. Žádný globální seznam,
  e-mail allowlist ani nový pozvánkový token. Odkaz není autentizace ani oprávnění ke cizí posádce.
- Otevřít jen přesné nové metody/cesty ve filtrech, nikoli celé /race-events/**.
  Žádné správné CODE, knihovna ADMIN, e-maily nebo jména cizích členů v souhrnu stavu.

## 4. Serverem vyhodnocený stav

registrationFrom/To jsou buď oba null, nebo oba vyplněné s from < to. Jednostranný interval je
400 REGISTRATION_INTERVAL_INVALID. Minulé/budoucí intervaly jsou přípustné; nepřepisují termíny konání.
Interval může přesáhnout start/konec, ale precedence níže má přednost. Změna intervalu sama neruší override.

Server pořídí serverTime a použije první odpovídající řádek:

| Podmínka | effectiveState | reason |
|---|---|---|
| status ENDED nebo endAt != null a serverTime >= endAt | CLOSED | EVENT_ENDED |
| override OPEN | OPEN | MANUALLY_OPEN |
| override CLOSED | CLOSED | MANUALLY_CLOSED |
| skutečný oficiální start: startedAt != null / status RUNNING | CLOSED | OFFICIAL_START_REACHED |
| interval a serverTime < from | CLOSED | BEFORE_INTERVAL |
| interval a serverTime >= to | CLOSED | AFTER_INTERVAL |
| interval a from <= serverTime < to | OPEN | WITHIN_INTERVAL |
| bez intervalu, před oficiálním startem | OPEN | DEFAULT_OPEN |

canRegister = effectiveState == OPEN (konkrétní posádku zohlední až RegistrationCrew.canRegister).
canManageSettings = ADMIN a konání podle první podmínky neskončilo. Po konci nelze měnit interval/override.
Režimy: AUTO počítá interval/start, OPEN ručně otevírá, CLOSED ručně uzavírá. OPEN/CLOSED jsou trvalé do
výslovného dalšího rozhodnutí nebo konce. Návrat AUTO vyžaduje výslovný PUT override=AUTO.
Tlačítko změny intervalu pošle aktuální override; žádné skryté resetování. OPEN nepřebije konec.

scheduledStartAt není skutečný start. První implementace vrací skutečné PLANNED a startedAt=null;
RUNNING/ENDED doplní až reálný lifecycle, nikoli simulátor. endAt může zatím být null; konec pak nastane
jen skutečným ENDED. I PLANNED s minulým endAt má efektivně uzavřené registrace/úpravy.
GET nic nepřepíná a neauditují se změny vyvolané pouhým časem. Efektivní stav se může změnit bez změny version.
Frontend zobrazuje serverový důvod, nepočítá stav podle vlastních hodin; zápis vše znovu atomicky ověří.

## 5. Složení, předvyplnění a oprávnění

membersText je prostý text, ne HTML/Markdown. Na vstupu nejvýše 2000 Unicode code points
(před normalizací, jak určuje maxLength OpenAPI); potom CRLF a samostatné CR -> LF.
Jinak zachovat mezery, prázdné řádky a pořadí. NUL není povolen.
memberCount = počet řádků, pro které Java String.isBlank() je false. Musí být aspoň 1.
childrenUnder15 je 0..memberCount, participants15Plus = memberCount - childrenUnder15.
Žádný další limit členů ani samostatná tabulka osob. Jména renderovat jako text, nikdy aktivní HTML.

Defaults: poslední registrace stejné crewId napříč konáními ORDER BY registered_at DESC,id DESC,
včetně nedokončené/neodstartované i aktuální, pokud již existuje. Nevybírat podle updatedAt.
Převzít text i děti; bez historie současný displayName kapitána a 0. Předvyplnění je jen návrh pro formulář.
POST vždy uloží explicitně potvrzené hodnoty od klienta, nikoli mezitím aktualizované defaults.
Starou přihlášku nemění, nevytváří rezervaci ani podmínku k podání nové.

Vlastní přihlášky se určují současným captain_account_id, nikoli autorem registrace.
Po převodu nový kapitán přístup získá, starý jej okamžitě ztratí i v existující relaci.
Orphan nemá kapitánský přístup, ADMIN může číst; nové přidělení kapitána obnoví správu.
Samotný ADMIN v tomto v1 nesmí registrovat/měnit složení za cizí posádku.

compositionLocked = individualStartedAt != null nebo konání podle §4 skončilo.
canEditMembers = současný kapitán a !compositionLocked.
editBlockedReason má prioritu EVENT_ENDED, INDIVIDUAL_START_REACHED, NOT_CAPTAIN, jinak null.
Uzavření registrací ani oficiální start samy úpravu podané přihlášky neblokují.
Konec bez individuálního startu blokuje úpravu, ale nevytváří falešný historický start/kapitána.
Příznaky se počítají pro konkrétního čtenáře, nejsou trvalým oprávněním klienta.

## 6. Endpointy a příklady

Nové návrhy, nikoli existující endpointy. Všechny cesty níže mají prefix /api/v1.
Seznamy: page 0..10000 (default 0), size 1..100 (default 50), items/page/size/hasMore, bez total.
V SQL nejprve řadit a až potom stránkovat. Přihlášky registeredAt DESC,id DESC;
posádky lower(name) ASC,crewId ASC. Mezi jednotlivými stránkami může dojít ke změně dat, nejde o cursor snapshot.
GET nemá tělo ani Idempotency-Key. Neplatný query parametr nebo enum = 400 INVALID_REQUEST.

| Metoda | Cesta | Request DTO | Response DTO |
|---|---|---|---|
| GET | `/race-events/{eventId}/registration-status` | — | RegistrationStatus |
| PUT | `/race-events/{eventId}/registration-settings` | RegistrationSettingsUpdate | RegistrationStatus |
| GET | `/race-events/{eventId}/registration-crews` | — | RegistrationCrewPage |
| POST | `/race-events/{eventId}/registrations` | RegistrationCreate | RegistrationCreated |
| GET | `/race-events/{eventId}/registrations` | — | RegistrationPage |
| GET | `/race-events/{eventId}/registrations/{registrationId}` | — | Registration |
| PUT | `/race-events/{eventId}/registrations/{registrationId}/members` | RegistrationMembersUpdate | Registration |

### 6.1 Stav registrací a bezpečný souhrn konání

`GET /api/v1/race-events/{eventId}/registration-status`

Aktivní ověřený účet se znalostí eventId z předaného odkazu; bez veřejného seznamu nebo e-mail allowlistu.

Request: bez těla.

Response HTTP 200:

```json
{
  "event": {
    "id": "11111111-1111-4111-8111-111111111111",
    "name": "Podzimní projížďka",
    "description": null,
    "status": "PLANNED",
    "scheduledStartAt": "2026-11-01T10:00:00Z",
    "startedAt": null,
    "endAt": "2026-11-01T16:00:00Z"
  },
  "version": 4,
  "serverTime": "2026-10-30T10:00:00Z",
  "registrationFrom": null,
  "registrationTo": null,
  "override": "AUTO",
  "effectiveState": "OPEN",
  "reason": "DEFAULT_OPEN",
  "canRegister": true,
  "canManageSettings": false
}
```

| HTTP chyby | code |
|---|---|
| 400 | INVALID_REQUEST |
| 401 | UNAUTHENTICATED / INVALID_CREDENTIALS |
| 403 | ACCESS_DENIED |
| 404 | RACE_EVENT_NOT_FOUND |
| 500 | INTERNAL_ERROR |

### 6.2 Nastavení intervalu a ručního override

`PUT /api/v1/race-events/{eventId}/registration-settings`

Pouze aktuální ADMIN. Sdílená version konání; změny intervalu i override atomické.

Request:

```json
{
  "version": 4,
  "registrationFrom": "2026-10-30T08:00:00Z",
  "registrationTo": "2026-11-01T09:00:00Z",
  "override": "OPEN"
}
```
version z GET registration-status (stejná jako ADMIN GET detail konání). Skutečná změna zvýší společnou verzi o 1, no-op se správnou verzí nemění verzi/updatedAt/audit.

Response HTTP 200:

```json
{
  "event": {
    "id": "11111111-1111-4111-8111-111111111111",
    "name": "Podzimní projížďka",
    "description": null,
    "status": "PLANNED",
    "scheduledStartAt": "2026-11-01T10:00:00Z",
    "startedAt": null,
    "endAt": "2026-11-01T16:00:00Z"
  },
  "version": 5,
  "serverTime": "2026-10-30T10:00:00Z",
  "registrationFrom": "2026-10-30T08:00:00Z",
  "registrationTo": "2026-11-01T09:00:00Z",
  "override": "OPEN",
  "effectiveState": "OPEN",
  "reason": "MANUALLY_OPEN",
  "canRegister": true,
  "canManageSettings": true
}
```

| HTTP chyby | code |
|---|---|
| 400 | INVALID_REQUEST / REGISTRATION_INTERVAL_INVALID |
| 401 | UNAUTHENTICATED / INVALID_CREDENTIALS |
| 403 | ACCESS_DENIED / ADMIN_REQUIRED |
| 404 | RACE_EVENT_NOT_FOUND |
| 500 | INTERNAL_ERROR |
| 409 | RACE_EVENT_VERSION_CONFLICT / RACE_EVENT_ENDED |
| 415 | UNSUPPORTED_MEDIA_TYPE |

### 6.3 Vlastní posádky a předvyplnění

`GET /api/v1/race-events/{eventId}/registration-crews`

Pouze vlastní posádky podle současného captain_account_id, i pro ADMIN. Žádné cizí nebo osiřelé posádky.

Request: bez těla.
Query page, size; bez scope/captainId. Již přihlášené vlastní posádky zůstávají s blokací; priorita ALREADY_REGISTERED před REGISTRATION_CLOSED. crewVersion pouze informativní, POST ji nepřijímá.

Response HTTP 200:

```json
{
  "items": [
    {
      "crewId": "22222222-2222-4222-8222-222222222222",
      "crewVersion": 2,
      "name": "Výletníci",
      "captainAccountId": "55555555-5555-4555-8555-555555555555",
      "registrationId": null,
      "canRegister": true,
      "blockedReason": null,
      "defaults": {
        "source": "LAST_REGISTRATION",
        "sourceRegistrationId": "44444444-4444-4444-8444-444444444444",
        "membersText": "Jana Nováková\nPetr Novák\nEva Nováková",
        "childrenUnder15": 1
      }
    }
  ],
  "page": 0,
  "size": 50,
  "hasMore": false
}
```

| HTTP chyby | code |
|---|---|
| 400 | INVALID_REQUEST |
| 401 | UNAUTHENTICATED / INVALID_CREDENTIALS |
| 403 | ACCESS_DENIED |
| 404 | RACE_EVENT_NOT_FOUND |
| 500 | INTERNAL_ERROR |

### 6.4 Přihlášení vlastní posádky

`POST /api/v1/race-events/{eventId}/registrations`

Pouze současný kapitán vlastní posádky. Samotná ADMIN role neopravňuje registrovat cizí posádku.

Request:

```json
{
  "crewId": "22222222-2222-4222-8222-222222222222",
  "membersText": "Jana Nováková\nPetr Novák\nEva Nováková",
  "childrenUnder15": 1
}
```
Povinný Idempotency-Key UUID; 201 pro create i replay. Location /api/v1/race-events/{eventId}/registrations/{id}. Aktuální obsah načíst GET detail.

Response HTTP 201:

```json
{
  "id": "33333333-3333-4333-8333-333333333333",
  "version": 0
}
```

| HTTP chyby | code |
|---|---|
| 400 | INVALID_REQUEST / REGISTRATION_MEMBERS_INVALID / REGISTRATION_MEMBERS_TEXT_TOO_LONG |
| 401 | UNAUTHENTICATED / INVALID_CREDENTIALS |
| 403 | ACCESS_DENIED |
| 404 | RACE_EVENT_NOT_FOUND / CREW_NOT_FOUND |
| 500 | INTERNAL_ERROR |
| 409 | REGISTRATION_CLOSED / CREW_ALREADY_REGISTERED / IDEMPOTENCY_CONFLICT |
| 415 | UNSUPPORTED_MEDIA_TYPE |

### 6.5 Seznam vlastních nebo administrativní přehled přihlášek

`GET /api/v1/race-events/{eventId}/registrations`

scope=own podle současného kapitánství, nikoli autora. scope=all pouze ADMIN; jinak 403. Bez scope i ADMIN vidí jen vlastní.

Request: bez těla.
Query scope=own|all, page, size. ADMIN all zahrnuje orphan i uzamčené přihlášky; stavový filtr zatím není potřeba (jen REGISTERED).

Response HTTP 200:

```json
{
  "items": [
    {
      "id": "33333333-3333-4333-8333-333333333333",
      "raceEventId": "11111111-1111-4111-8111-111111111111",
      "crewId": "22222222-2222-4222-8222-222222222222",
      "crewNameAtRegistration": "Výletníci",
      "status": "REGISTERED",
      "version": 0,
      "registeredAt": "2026-10-30T10:00:00Z",
      "updatedAt": "2026-10-30T10:00:00Z",
      "membersText": "Jana Nováková\nPetr Novák\nEva Nováková",
      "childrenUnder15": 1,
      "memberCount": 3,
      "participants15Plus": 2,
      "individualStartedAt": null,
      "historicalCaptainAccountId": null,
      "compositionLocked": false,
      "canEditMembers": true,
      "editBlockedReason": null
    }
  ],
  "page": 0,
  "size": 50,
  "hasMore": false
}
```

| HTTP chyby | code |
|---|---|
| 400 | INVALID_REQUEST |
| 401 | UNAUTHENTICATED / INVALID_CREDENTIALS |
| 403 | ACCESS_DENIED |
| 404 | RACE_EVENT_NOT_FOUND |
| 500 | INTERNAL_ERROR |

### 6.6 Detail přihlášky

`GET /api/v1/race-events/{eventId}/registrations/{registrationId}`

Současný kapitán nebo ADMIN. Přihláška musí patřit eventId; cizímu běžnému uživateli 404 REGISTRATION_NOT_FOUND.

Request: bez těla.

Response HTTP 200:

```json
{
  "id": "33333333-3333-4333-8333-333333333333",
  "raceEventId": "11111111-1111-4111-8111-111111111111",
  "crewId": "22222222-2222-4222-8222-222222222222",
  "crewNameAtRegistration": "Výletníci",
  "status": "REGISTERED",
  "version": 0,
  "registeredAt": "2026-10-30T10:00:00Z",
  "updatedAt": "2026-10-30T10:00:00Z",
  "membersText": "Jana Nováková\nPetr Novák\nEva Nováková",
  "childrenUnder15": 1,
  "memberCount": 3,
  "participants15Plus": 2,
  "individualStartedAt": null,
  "historicalCaptainAccountId": null,
  "compositionLocked": false,
  "canEditMembers": true,
  "editBlockedReason": null
}
```

| HTTP chyby | code |
|---|---|
| 400 | INVALID_REQUEST |
| 401 | UNAUTHENTICATED / INVALID_CREDENTIALS |
| 403 | ACCESS_DENIED |
| 404 | RACE_EVENT_NOT_FOUND / REGISTRATION_NOT_FOUND |
| 500 | INTERNAL_ERROR |

### 6.7 Úprava složení přihlášené posádky

`PUT /api/v1/race-events/{eventId}/registrations/{registrationId}/members`

Pouze současný kapitán; ADMIN bez kapitánství cizí složení nemění (404 REGISTRATION_NOT_FOUND). Dovoleno před individuálním startem a koncem konání, i při zavřené registraci a po oficiálním startu.

Request:

```json
{
  "version": 0,
  "membersText": "Jana Nováková\nPetr Novák",
  "childrenUnder15": 0
}
```
version z detailu/seznamu přihlášek; změna zvýší jen verzi přihlášky o 1, ne konání/posádky. No-op se správnou verzí nemění verzi/updatedAt/audit. Stará verze je 409 i při opakování po timeoutu.

Response HTTP 200:

```json
{
  "id": "33333333-3333-4333-8333-333333333333",
  "raceEventId": "11111111-1111-4111-8111-111111111111",
  "crewId": "22222222-2222-4222-8222-222222222222",
  "crewNameAtRegistration": "Výletníci",
  "status": "REGISTERED",
  "version": 1,
  "registeredAt": "2026-10-30T10:00:00Z",
  "updatedAt": "2026-10-30T10:05:00Z",
  "membersText": "Jana Nováková\nPetr Novák",
  "childrenUnder15": 0,
  "memberCount": 2,
  "participants15Plus": 2,
  "individualStartedAt": null,
  "historicalCaptainAccountId": null,
  "compositionLocked": false,
  "canEditMembers": true,
  "editBlockedReason": null
}
```

| HTTP chyby | code |
|---|---|
| 400 | INVALID_REQUEST / REGISTRATION_MEMBERS_INVALID / REGISTRATION_MEMBERS_TEXT_TOO_LONG |
| 401 | UNAUTHENTICATED / INVALID_CREDENTIALS |
| 403 | ACCESS_DENIED |
| 404 | RACE_EVENT_NOT_FOUND / REGISTRATION_NOT_FOUND |
| 500 | INTERNAL_ERROR |
| 409 | REGISTRATION_VERSION_CONFLICT / REGISTRATION_COMPOSITION_LOCKED / RACE_EVENT_ENDED |
| 415 | UNSUPPORTED_MEDIA_TYPE |

## 7. Kompletní DTO

Všechna vyjmenovaná pole jsou required, null jen kde je v typu výslovně uvedeno.
DTO nepovolují další properties kromě Problem. Odkazy se uplatní rekurzivně.
Datové typy/rozsahy/normalizace jsou v §3 a §5 i ve strojových schématech.

### MembersText

Prostý text, na vstupu max 2000 Unicode code points před normalizací; následně CRLF/CR -> LF. Jinak zachovat mezery/řádky; aspoň jeden neprázdný řádek podle !String.isBlank(); NUL zakázán.


### EventSummary

Bez správných CODE, stanovišť, knihovny a seznamu přihlášek. RUNNING/ENDED až v pozdější etapě skutečného lifecycle.

| Pole | Typ |
|---|---|
| id | string (uuid) |
| name | string; maxLength 100 |
| description | string / null; maxLength 2000 |
| status | string: PLANNED, RUNNING, ENDED |
| scheduledStartAt | string / null (date-time) |
| startedAt | string / null (date-time) |
| endAt | string / null (date-time) |

### RegistrationStatus

version je společná verze race_event. Efektivní stav určuje server; canRegister znamená otevřenost pro novou přihlášku, nikoli oprávnění ke konkrétní posádce. Priorita: konec -> OPEN -> CLOSED -> skutečný oficiální start -> interval -> výchozí otevření. Změna intervalu override sama neruší.

| Pole | Typ |
|---|---|
| event | EventSummary |
| version | integer (int64) |
| serverTime | string (date-time) |
| registrationFrom | string / null (date-time) |
| registrationTo | string / null (date-time) |
| override | string: AUTO, OPEN, CLOSED |
| effectiveState | string: OPEN, CLOSED |
| reason | string: EVENT_ENDED, MANUALLY_OPEN, MANUALLY_CLOSED, OFFICIAL_START_REACHED, BEFORE_INTERVAL, AFTER_INTERVAL, WITHIN_INTERVAL, DEFAULT_OPEN |
| canRegister | boolean |
| canManageSettings | boolean |

### RegistrationSettingsUpdate

Interval obě null nebo oba časy a from < to. Všechna pole povinná, celý stav se atomicky nahrazuje. Pro zrušení ručního rozhodnutí poslat AUTO; změna intervalu sama override neruší.

| Pole | Typ |
|---|---|
| version | integer (int64) |
| registrationFrom | string / null (date-time) |
| registrationTo | string / null (date-time) |
| override | string: AUTO, OPEN, CLOSED |

### RegistrationCreate

Aktuální kapitán pouze vlastní posádky. childrenUnder15 <= počet neprázdných řádků, bez pevného limitu členů. Žádné historické hodnoty od klienta.

| Pole | Typ |
|---|---|
| crewId | string (uuid) |
| membersText | MembersText |
| childrenUnder15 | integer (int32) |

### RegistrationCreated

Původní id a version=0 pro přesný 24h replay; aktuální detail načíst přes Location.

| Pole | Typ |
|---|---|
| id | string (uuid) |
| version | integer (int64) |

### RegistrationMembersUpdate

Verze přihlášky, nikoli konání/posádky. Úprava pouze aktuálním kapitánem před individuálním startem a koncem konání.

| Pole | Typ |
|---|---|
| version | integer (int64) |
| membersText | MembersText |
| childrenUnder15 | integer (int32) |

### Registration

REGISTERED je stav přihlášky, nikoli průběhu. Historický kapitán je před individuálním startem null. canEditMembers vyžaduje aktuální kapitánství i odemčené složení; ADMIN sám nestačí. memberCount odpovídá neprázdným řádkům; děti <= memberCount; participants15Plus je jejich rozdíl. Příznaky/počty ověřuje server.

| Pole | Typ |
|---|---|
| id | string (uuid) |
| raceEventId | string (uuid) |
| crewId | string (uuid) |
| crewNameAtRegistration | string; maxLength 100 |
| status | string: REGISTERED |
| version | integer (int64) |
| registeredAt | string (date-time) |
| updatedAt | string (date-time) |
| membersText | MembersText |
| childrenUnder15 | integer (int32) |
| memberCount | integer (int32) |
| participants15Plus | integer (int32) |
| individualStartedAt | string / null (date-time) |
| historicalCaptainAccountId | string / null (uuid) |
| compositionLocked | boolean |
| canEditMembers | boolean |
| editBlockedReason | string / null: NOT_CAPTAIN, INDIVIDUAL_START_REACHED, EVENT_ENDED, null |

### RegistrationDefaults

Jen návrh formuláře. Poslední registrace stejné posádky podle registered_at DESC,id DESC, včetně nedokončené. Bez historie displayName kapitána a 0 dětí. source=CAPTAIN_PROFILE má sourceRegistrationId=null; LAST_REGISTRATION vyžaduje UUID zdroje. Návrh načítá aktuální kapitán bez zpřístupnění cizích posádek.

| Pole | Typ |
|---|---|
| source | string: LAST_REGISTRATION, CAPTAIN_PROFILE |
| sourceRegistrationId | string / null (uuid) |
| membersText | MembersText |
| childrenUnder15 | integer (int32) |

### RegistrationCrew

Pouze vlastní posádky, i pro ADMIN. Již registrovaná zůstane s blokací; priorita ALREADY_REGISTERED před REGISTRATION_CLOSED. crewVersion informativní, POST ji nepřijímá.

| Pole | Typ |
|---|---|
| crewId | string (uuid) |
| crewVersion | integer (int64) |
| name | string; maxLength 100 |
| captainAccountId | string (uuid) |
| registrationId | string / null (uuid) |
| canRegister | boolean |
| blockedReason | string / null: ALREADY_REGISTERED, REGISTRATION_CLOSED, null |
| defaults | RegistrationDefaults |

### RegistrationPage



| Pole | Typ |
|---|---|
| items | array<Registration> |
| page | integer (int32) |
| size | integer |
| hasMore | boolean |

### RegistrationCrewPage



| Pole | Typ |
|---|---|
| items | array<RegistrationCrew> |
| page | integer (int32) |
| size | integer |
| hasMore | boolean |

### Problem

Stávající ProblemDetail; type/instance mohou chybět. Rozhodovat podle status/code, nikoli title. Žádná tajemství ani obsah členů v chybě.

| Pole | Typ |
|---|---|
| type | string (uri-reference) |
| instance | string (uri-reference) |
| status | integer |
| title | string |
| code | string |

## 8. Idempotence a chybové situace

POST použije stávající CommandStore, nový známý formát CREW_REGISTRATION_CREATE; retence 24 h.
request_data = eventId, crewId, SHA-256 normalizovaného membersText a childrenUnder15;
result_data = pouze {id,version}. Neukládat do ledgeru otevřené členy nebo defaults.
related_account_ids obsahuje skutečné účty (aktéra), nikdy event/crew/registration UUID zaměněné za účet.
Rozšířit zároveň CommandAccounts a bezpečný cancellation/retention cleanup; neznámý formát nesmí tiše propadnout.
Bez HMAC, šifrovací infrastruktury nebo paralelního ledgeru. Registrační ledger není závodní deník.

Obchodní pořadí POST: aktuální autentizace -> existující konání a vlastní posádka -> aktuální kapitánství
-> replay a porovnání klíče -> otevřenost -> duplicita -> INSERT/audit/ledger. Zámky §10 získat konzistentně.
Stejný klíč+stejné normalizované hodnoty vrátí původní 201 id/version=0 i po úpravě nebo uzavření registrací,
pokud aktér stále smí danou posádku spravovat. Stejný klíč+jiný obsah/operace vrátí IDEMPOTENCY_CONFLICT
před kontrolou otevřenosti/duplicity. Po ztrátě kapitánství replay odmítnout 404 CREW_NOT_FOUND.
Nový klíč při otevřené registraci a existujícím páru -> CREW_ALREADY_REGISTERED; při uzavřené -> REGISTRATION_CLOSED.
Totéž platí po 24 h, kdy ledger již nemusí existovat; DB unikátnost stále brání druhé registraci.

Souběžné stejné klíče: totožný 201 a jeden INSERT/audit. Různé klíče: jeden 201, druhý 409 duplicita.
PUT nemá ledger: po timeoutu nejprve GET, porovnat obsah, nepřepisovat bezmyšlenkovitě novější verzi.
Při změně kapitána rozhoduje pořadí zamčených transakcí; původní kapitán může dokončit dříve zamčenou operaci,
ne po již provedeném převodu. Nového vlastníka ani jeho ID neprozradit chybou.

401 UNAUTHENTICATED (případně INVALID_CREDENTIALS při transakčním přezkoušení); 403 ACCESS_DENIED pro filtr/CSRF,
ADMIN_REQUIRED pro aplikační ADMIN kontrolu. Cizí/neexistující posádka 404 CREW_NOT_FOUND; přihláška
404 REGISTRATION_NOT_FOUND včetně jiné parent eventId. Konání neexistující/nepřístupné 404 RACE_EVENT_NOT_FOUND.
ADMIN endpoint může vrátit 403 před zjišťováním existence cíle. Žádná SQL/PII data v odpovědi.

400 REGISTRATION_MEMBERS_TEXT_TOO_LONG na vstupu před normalizací má přednost před kontrolou obsahu;
REGISTRATION_MEMBERS_INVALID pro nulu členů, NUL nebo děti nad počet členů. Strukturálně neplatné hodnoty
(včetně záporných/necelých dětí) -> INVALID_REQUEST. Interval nesplňující §4 -> REGISTRATION_INTERVAL_INVALID.
PUT po autorizaci kontroluje nejprve odpovídající version (409 *_VERSION_CONFLICT), potom EVENT_ENDED,
potom individuální start (REGISTRATION_COMPOSITION_LOCKED). Odepření/odmítnutí není úspěšná auditní změna.
Obecné 405 METHOD_NOT_ALLOWED, 406 NOT_ACCEPTABLE a 415 UNSUPPORTED_MEDIA_TYPE zůstávají podle ApiErrors;
neočekávané chyby bezpečné 500 INTERNAL_ERROR. ProblemDetail může mít type/instance nebo další pole;
klient závisí jen na status/code, nikoli textu title. Příklad:

```json
{"type":"about:blank","title":"Conflict","status":409,"code":"REGISTRATION_CLOSED"}
```

## 9. Datový model – návrh bez migrace

| Objekt | Navržená pole a omezení |
|---|---|
| race_event | registration_from/to timestamptz(3) nullable oba společně; CHECK from<to; registration_override AUTO/OPEN/CLOSED NOT NULL DEFAULT AUTO; společná stávající version. |
| crew_registration | id UUID PK; race_event_id, crew_id; status REGISTERED; crew_name_at_registration do 100; members_text do 2000; children_under_15; version >=0; registered_at, updated_at timestamptz(3). |
| Budoucí start | nullable individual_started_at a historical_captain_account_id; obojí atomicky při individuálním startu, navíc neměnný snapshot složení (např. started_members_text/started_children_under_15). Před startem null. Jen připravit data, start neimplementovat. |
| FK | Konání a posádka ON DELETE RESTRICT; žádné CASCADE. Historický kapitán po startu FK RESTRICT (účet s historií se anonymizuje). Autor přihlášky není historický kapitán a nemá být novou překážkou fyzického smazání účtu. |
| Jedinečnost | Plné UNIQUE(race_event_id,crew_id); v1 nemá odhlášené/neaktivní řádky. Aktuální kapitán není součást unikátnosti. |
| Indexy | UNIQUE event+crew; (race_event_id,registered_at DESC,id DESC); (crew_id,registered_at DESC,id DESC) pro defaults a použití. Stávající crew_captain pro vlastníka. |
| Validace | Délka a počty, neprázdný název, stav whitelist; DB omezení kde vhodná, zbytek transakční vrstva s testy. DB a Java musejí používat totožnou sémantiku neprázdných řádků, ne rozdílné počítání. |

memberCount/participants15Plus jsou odvozené, ne další editovatelná pravda.
Při podání uložit současný název posádky (neměnit jej pozdějším přejmenováním nebo změnou členů),
pracovní složení a registeredAt; nikoli definitivního historického kapitána.
Při budoucím individuálním startu pod zámky zachytit současného kapitána a složení do neměnného snapshotu.
Pouhá přihláška, oficiální start ani konec bez startu takový snapshot nevytváří.
Verze migrace se vybere při implementaci podle aktuálního stavu (nyní poslední V25); žádné SQL se zde nevytváří.
RUNNING/ENDED a skutečný started_at se přidají s lifecycle, ne přes falešné obcházení V21.

## 10. Transakce, audit a ochrana historie

Změna, audit, version a ledger v jediné DB transakci. Chyba rollbackuje vše včetně rezervace klíče.
Žádné soubory se nekopírují. Zachovat pořadí admin_control -> potřebné účty -> posádka -> konání
-> přihláška -> command/audit. Více účtů/posádek stabilně podle UUID, nezavádět opačné konání->posádka.
Locator z registrationId lze přečíst bez zámku, pak v daném pořadí zamknout a znovu ověřit všechny vazby/oprávnění.
Budoucí start/snapshot musí sdílet stejné zámky; oficiální start/konec sdílí zámek konání s registračním nastavením.

Audit návrh: REGISTRATION_CREATED, REGISTRATION_MEMBERS_UPDATED, RACE_REGISTRATION_SETTINGS_UPDATED.
Jen UUID, aktér a nezbytná bezpečná metadata/verze, ne jména/obsah členů, tokeny nebo cookies.
Trvalé údaje přihlášky nespoléhají na bezpečnostní audit s retencí.

| Existující operace | Změna ochrany při implementaci |
|---|---|
| DELETE /crews/{id}?version=... | Jakákoli zachovaná registrace -> 409 CREW_IN_USE, nesmazat přihlášku. Zachovat vlastníka/ADMIN, verzi a ochranu čekajících převodů. |
| DELETE /race-events/{id}?version=... | Jakákoli zachovaná registrace -> 409 RACE_EVENT_IN_USE. Zachovat ADMIN/PLANNED/verzi. |
| Self/ADMIN cancellation | D10 API beze změny. Pouhá registrace nebrání zrušení, přihláška/členové zůstávají, posádka osiří. Před individuálním startem historie účtu nevzniká. |
| Budoucí cancellation po startu | Historický kapitán trvale zachován, účet s historií anonymizovat; běžící účast může zrušení blokovat podle stávajících pravidel. |
| Převod kapitána | Práva podle aktuální DB, historický název a startovní snapshot se nepřepisují. Nové přiřazení kapitána osiřelé posádce obnoví správu. |

Rozšířit PreRaceAccountParticipationFacts o skutečnou novou tabulku a známou migraci, ne povolit neznámé verze.
Pouhá registrace neznamená historical/running. Až budoucí start založí historickou vazbu na kapitána.
Rozšířit usage checks EventStore/CrewService a cancellation/CommandCleanupStore. Žádná historie nesmí
kaskádově zmizet. Tyto kontroly potřebují souběžné testy proti mazání, registraci i změně kapitána.
Strojový dopad na existující API je i v OpenAPI x-existing-endpoint-impacts; celé starší kontrakty neduplikujeme.

## 11. Frontendové scénáře

Kapitán: odkaz s eventId -> GET registration-status (souhrn, otevřenost a důvod) -> GET registration-crews
-> vybrat posádku a načíst defaults do upravitelného formuláře -> POST s jedním UUID klíčem -> 201/Location -> GET detail.
Existující registrationId vede rovnou na detail, ne nový POST. Žádná posádka -> items=[] a existující CRUD posádek.
Vlastní přihlášky GET registrations?scope=own; editovat podle canEditMembers a PUT /members s version detailu.
Zavřená registrace nebrání čtení a případné editaci podané přihlášky. Cizí přístup i starého kapitána vrací 404.
Při timeoutu POST tentýž klíč i obsah, při 409 znovu načíst stav/detail a zachovat lokální rozepsaný návrh.

Organizátor: stávající ADMIN detail konání -> GET registration-status -> formulář interval/override
-> PUT registration-settings se společnou verzí -> přímo zobrazit vrácený stav/důvod.
Tlačítka Otevřít/Zavřít/Automaticky posílají celý interval a OPEN/CLOSED/AUTO. Interval sám ruční režim neruší.
GET registrations?scope=all zobrazí složení a compositionLocked; canEditMembers může být false, protože ADMIN není kapitán.
Žádné automatické ADMIN přebírání cizí registrace nebo odhlášení. Bez lokálního výpočtu otevřenosti či oprávnění.

## 12. Akceptační testy navazující implementace

- Bez intervalu, from včetně/to výlučně, skutečný versus plánovaný start, sticky OPEN/CLOSED a výslovné AUTO,
  změna intervalu, otevření po startu, konec s nejvyšší prioritou a endAt=null.
- Více posádek jednoho účtu, aktuální kapitánství, orphan, scope own/all, cizí parent/ID, bez úniku členů.
- Defaults poslední i nedokončené registrace, registeredAt tie-breaker ID, děti, fallback jméno/0, nezávislá kopie.
- CRLF/CR, prázdné řádky, Unicode mimo BMP, 2000/2001 znaků, více než 5 členů, děti včetně hranic, NUL a textové HTML.
- Souběžné stejné/jiné klíče, UNIQUE, replay po zavření, konflikt jiného obsahu před otevřeností, odvolané kapitánství.
- Oddělená event/registration/crew version, no-op, audit/ledger rollback, kapitánský převod vs změna členů.
- Úprava po zavření/oficiálním startu ano, po individuálním startu/konci ne; konec netvoří historického kapitána.
- Ochrana mazání, cancellation bez historie zachová registraci a osiřelou posádku; regrese D09/D10, guardů a cleanupu.
- Cookie CSRF, Bearer, odvolaná relace/role, stabilní řazení a stránkování. Budoucí lifecycle pravidla lze v první
  implementaci testovat čistě aplikačně, ne vypínáním DB omezení nebo simulátorem vydávaným za skutečný závod.

Běžné testy bez DB; PostgreSQL pouze TestDatabaseInitializer/rallye_test/profil it, ručně uživatelem:
`.\mvnw.cmd -Pit verify`. V této dokumentační fázi žádný Maven ani DB běh neprobíhá.

## 13. Schválení a otevřené body

Pro sedm operací není další blokující produktová otázka. Pozdější odhlášení zůstává mimo v1;
vyžaduje samostatná pravidla. Budoucí otázky restartů/výsledků nebrání registračnímu API.
Úplná dvojice intervalu, výslovné AUTO, vlastní posádky i pro ADMIN, per-registration verze,
plná UNIQUE v1 a nové chyby jsou konkrétní návrhy k odsouhlasení, nikoli tvrzení o hotovém kódu.
Kontrakt je dostatečně konkrétní pro paralelní frontendové mocky/backend; za závazný schválený
se označí až po akceptaci fáze A. Změny obou dokumentů se potom koordinují společně.

## 14. Validace

Ověřeno 2026-10-30:

- YAML úspěšně načten PyYAML; OpenAPIV31SpecValidator (openapi-spec-validator 0.9.0) potvrdil OpenAPI 3.1.0 bez chyb.
- Všech 13 komponentových schémat ověřeno jako JSON Schema; 62 příkladů DTO, requestů a odpovědí validováno včetně UUID/časů.
- 35 interních odkazů se rozliší; žádné externí reference, unikátní operationId pro všech 7 operací na 6 cestách.
- 9 neplatných ukázek (UUID, čísla, délka, null, neznámá/chybějící pole, override, čas bez offsetu) správně odmítnuto schématy.
- Ověřeny přesné dvojice cookie+CSRF / samostatný Bearer, přítomnost endpointů/DTO v Markdownu a syntaktická platnost jeho JSON příkladů.
- Ručně zkontrolována shoda precedence stavů, verzí, error codes, autorizace, no-op/replay a dopadů na mazání mezi oběma dokumenty.
- git diff --check bez chyb. Maven, PostgreSQL ani HTTP volání proti aplikaci nebyly provedeny.

Validátory jsou pouze v dočasném adresáři mimo projekt; nejde o nové závislosti backendu.
Tato validace neprokazuje hotovou implementaci ani runtime autorizaci, tu musí ověřit budoucí testy.

## Návaznost životního cyklu konání (etapa 2)

Sedm operací registration-api-v1 zůstává zachováno. GET nyní využívají společný
efektivní lifecycle stav a zápisy materializaci přechodů. Aditivní GET
`/api/v1/me/registrations/summary` je kanonicky definován výhradně v
[race-event-lifecycle-api.md](race-event-lifecycle-api.md) a
[race-event-lifecycle-openapi.yaml](race-event-lifecycle-openapi.yaml).
Uživatel při závěrečném uzavření potvrdil PostgreSQL ověření této návaznosti
i ruční integraci s frontendem. Nejde o vlastní PostgreSQL běh Codexu.


## Vlastní přihlášky napříč konáními – implementované rozšíření registration-api-v1

`GET /api/v1/me/registrations?page=0&size=50`

Aktivní ověřený přihlášený účet (USER i ADMIN), Bearer nebo session cookie.
ADMIN nemá výjimku: SQL omezuje výsledek přes `crew.captain_account_id` na právě
přihlášený účet. Změna kapitána proto okamžitě mění přístup. Původní autor ani
historický kapitán nejsou kritériem tohoto seznamu. Neexistuje parametr `scope`.
Neznámé parametry jsou odmítnuty. GET nepotřebuje CSRF ani Idempotency-Key,
neprodlužuje relaci a nemění konání, audit ani verze. Odpověď má `Cache-Control: no-store`.

Stránkování: `page` celé číslo 0–10000, výchozí 0; `size` 1–100, výchozí 50.
Odpověď 200 obsahuje `items`, `page`, `size`, `hasMore`; prázdný seznam je 200.
Jeden řádek odpovídá jedné přihlášce, bez omezení podle registračního intervalu.

```json
{
  "items": [{
    "id": "11111111-1111-4111-8111-111111111111",
    "raceEventId": "22222222-2222-4222-8222-222222222222",
    "raceEventName": "Podzimní projížďka",
    "crewId": "33333333-3333-4333-8333-333333333333",
    "crewName": "Pohodáři",
    "scheduledStartAt": "2026-11-01T09:00:00Z",
    "endAt": "2026-11-01T17:00:00Z",
    "raceEventStatus": "PLANNED",
    "version": 0
  }],
  "page": 0,
  "size": 50,
  "hasMore": false
}
```

ID jsou UUID. Jména jsou aktuální jména konání a posádky; `crewName` není
historické `crewNameAtRegistration`. Časy jsou UTC ISO 8601, oba mohou být null.
`version` je int64 verze přihlášky, nikoli konání; navigace na existující detail
používá `raceEventId` a `id`. Význam ostatních existujících DTO se nemění.

`raceEventStatus` je efektivní PLANNED/RUNNING/ENDED podle EventLifecycle:
uložený ENDED nebo dosažené endAt má přednost; PLANNED s dosaženým scheduledStartAt
je RUNNING. Přechody se pouze promítají, nematerializují. Jediný serverový okamžik
je společný pro vyhodnocení a SQL řazení v read-only repeatable-read transakci.

Řazení před LIMIT/OFFSET:
1. Efektivní PLANNED podle scheduledStartAt vzestupně, bez plánovaného času nakonec.
2. Efektivní RUNNING podle ID přihlášky vzestupně.
3. Efektivní ENDED podle plánovaného endAt sestupně, null nakonec.
Ve shodě rozhoduje UUID přihlášky vzestupně (PostgreSQL UUID pořadí).
Při stejných datech a efektivních stavech jsou stránky stabilní. Změny dat nebo
překročení lifecycle termínu mezi samostatnými HTTP voláními mohou jako u ostatního
offset stránkování položky přesunout; API neposkytuje snapshot napříč požadavky.

Chyby: 400 INVALID_REQUEST (parametry), 401 UNAUTHENTICATED (chybějící/neplatná
relace či nepřípustný účet), případně existující 503 AUTHENTICATION_UNAVAILABLE při
výpadku autentizační DB. Standardní application/problem+json, žádné údaje cizích
přihlášek. Seznam používá jeden omezený JOIN dotaz bez načítání detailu každého
konání či posádky. Nová migrace není potřeba.

Uživatel potvrdil úspěšný cílený PostgreSQL běh tohoto rozšíření v EventLifecycleIT:
`.\mvnw.cmd -Pit "-Dit.test=EventLifecycleIT" verify` – **BUILD SUCCESS**.
Codex podle AGENTS.md PostgreSQL testy nespouštěl.

Ověření rozšíření (30. 10. 2026): Maven Wrapper / JDK 21.0.12.1,
`verify` bez profilu it: **799 testů, BUILD SUCCESS, 0 failures/errors/skipped**.
Pět nových unit/HTTP scénářů pokrývá stránkování, projekci lifecycle, omezení
vlastnictví i u ADMIN, read-only autentizaci, neobnovení remember-me cookie a jeden
seznamový JDBC dotaz. Tři nové PostgreSQL scénáře v EventLifecycleIT jsou
zkompilované, Codexem nespouštěné. YAML syntaxe OpenAPI, všechny lokální reference
a shoda polí OwnView s OpenAPI schématem byly ověřeny. Následný cílený běh
EventLifecycleIT včetně tří nových scénářů potvrdil uživatel jako **BUILD SUCCESS**.
Kompletní PostgreSQL sada tímto není označena za nově ověřenou.
