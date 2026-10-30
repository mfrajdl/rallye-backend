# Životní cyklus konání – race-event-lifecycle-api-v1

**IMPLEMENTOVÁNO A OVĚŘENO.** Kontrakt byl schválen k implementaci.
Uživatel potvrdil úspěšné automatické ověření včetně PostgreSQL a ruční integrační
průchod s frontendem. Codex PostgreSQL ani tento ruční průchod nespouštěl.
Doprovodná definice:
[race-event-lifecycle-openapi.yaml](race-event-lifecycle-openapi.yaml).
Verze kontraktu není Git tag. Aktuální implementační stav a ověření uvádí §14.

## 1. Podklady a výchozí stav před etapou 2

- Závazná specifikace 0.9.0 §3.3, §4, §7, §11–14; nové rozhodnutí zadání
  umožňuje opakované obnovení a konec bez časového limitu. Nahrazuje starší zákaz
  opětovného otevření v §4 a §14; specifikace se tímto dokumentem neupravuje.
- `races/api/EventController.java`: již existují GET/PUT `/race-events/{id}`,
  GET seznamu, vytvoření snapshotu, DELETE a čtení stanovišť/souborů.
  `EventService.View` a `Input` jsou základem níže, nevzniká paralelní detail.
- `races/application/EventService.java`: aktualizace/deletion jsou nyní jen PLANNED;
  `validated()` nyní vyžaduje scheduledStartAt < endAt, pokud jsou oba vyplněné.
- V21 dovoluje jen PLANNED a vynucuje stejnou vazbu termínů. V26 přidává
  registrační interval, override a přihlášky. Aktuální maximum migrací je **V26**.
- `participation/domain/RegistrationRules.java` umí skutečný start a ENDED,
  ale `RegistrationStore.event()` zatím vrací startedAt=null a čte pouze uložený
  stav. Časově odvozuje jen konec. Toto je konkrétní interní návaznost k doplnění.
- `shared/persistence/CommandStore.java` poskytuje společný namespace aktér/klíč,
  24h replay; `CommandAccounts` a privacy/retention cleanup musí znát nové formáty.
- `AuditService`, `AdminStore`, `AccountStore`, `AuthConfiguration`, `ApiErrors`
  zůstávají základem transakcí, ADMIN, relací, CSRF a ProblemDetail.
- Simulátor (`simulation/application/SimulationService.java`, `SimulationView`,
  V25) odděluje stav závodu od postupu posádky, používá virtuální čas a umí
  START/END v historii. Není reálným schedulerem a nemá tuto historii opakovaných
  obnovení konání. Jeho JSON, čas ani stavový automat se na konání nekopírují.

## 2. Rozhodnutí a rozsah návrhu

Schválené přechody: PLANNED→RUNNING, PLANNED→ENDED, RUNNING→ENDED,
ENDED→RUNNING. Nikdy ENDED→PLANNED ani CANCELLED. Počet obnovení není omezený.
**Schváleno:** PLANNED→ENDED podporuje i ruční ukončení před startem; nevytváří fiktivní start.
Dokončení posádky není ukončením konání. Účasti, výpočty, zařízení a publikace se
v této etapě neimplementují.

Technická doporučení určená ke schválení společně s kontraktem:

1. Automatický start má efektivní čas scheduledStartAt, čas zpracování je zvlášť.
2. Pokud je při vyhodnocení stále uloženo PLANNED a již nastal endAt, přednost má
   přímé PLANNED→ENDED. Nedohání se fiktivní historický běh ani dvojice start/konec.
3. Ruční START může předběhnout dosud neuložený automatický start a použije nynější
   serverový čas. Ruční END také nevytváří chybějící automatický start jen pro historii.
   Splatný automatický konec má před oběma ručními operacemi vždy přednost.
4. **Všechny zde popsané GET operace jsou čistě čtecí**, včetně ADMIN detailu,
   seznamu konání, historie, období, registračních GET a nového souhrnu registrací.
   Efektivní stav počítají bez materializace; nemění DB, verze, historii ani audit.
   Materializaci provádí pouze scheduler a relevantní zápisové operace.
5. Zachová se nullable scheduledStartAt: null znamená pouze ruční start, jako
   ve stávajícím API. Nová povinnost plánovaného času se nezavádí.

Tato volba automatického startu zajišťuje reprodukovatelný plán, ale neopravňuje
zpětně přijmout klientské operace. První skutečný zápis účasti musí před přijetím
materializovat start. Pokud už takový zápis proběhl, pozdní ruční START již nemůže
změnit začátek. Při výpadku žádné nepřijaté zprávy nevznikají.

## 3. Čas a efektivní stav

UTC RFC3339, výstup se Z, přesnost milisekund podle existujících Instant a
timestamptz(3). Na vstupu je povolen explicitní offset, normalizovaný do UTC;
vyšší zlomková přesnost se před porovnáním zkrátí na milisekundy jako dosud.
Autorita je serverový čas, nikoli klient: u zápisu odebraný po získání potřebných
zámků, u GET jednou v rámci konzistentního read-only čtení bez lifecycle zámků.
Při porovnání se v jedné rozhodovací části používá jediný `decisionAt`.

Efektivní stav pro čtení:

| Uložený stav a čas | Efektivní stav |
|---|---|
| ENDED | ENDED, nikdy se sám neobnoví |
| PLANNED/RUNNING a endAt != null, nyní >= endAt | ENDED |
| PLANNED a scheduledStartAt != null, nyní >= scheduledStartAt | RUNNING |
| jinak | uložený stav |

`status` v EventView je efektivní; `lifecycle.persistedStatus` je uložený.
`transitionsPending` upozorňuje na neuložený automatický přechod. `startedAt`
uvnitř lifecycle je trvalý první skutečně zaznamenaný start, po obnovení se
nepřepisuje. `effectiveStartedAt` je při čekajícím automatickém startu předběžně
scheduledStartAt, jinak uložený startedAt. Při přímém ukončení před startem je null.
Tato předběžná hodnota není historickým důkazem ani oprávněním k zápisu.
`lastEndedAt` zachovává poslední skutečné ukončení i po obnovení.

### Uložené a efektivní údaje při čekajícím přechodu

GET používá jednu konzistentní DB perspektivu a jeden serverový rozhodovací čas.
Nevyžaduje zápisovou transakci ani FOR UPDATE kvůli lifecycle; budoucí implementace
musí oddělit dnešní zamykací `EventService.authorize()`/`EventStore.find()` od
read-only variant autorizace a načtení. Kontroly aktuální relace/ADMIN se neoslabují.
Čtení historie a období vrací výhradně uložené řádky, nikoli dopočítané události.

| Pole | Čekající první start | Čekající konec z RUNNING | Čekající konec z PLANNED |
|---|---|---|---|
| status | RUNNING | ENDED | ENDED |
| lifecycle.persistedStatus | PLANNED | RUNNING | PLANNED |
| transitionsPending | true | true | true |
| startedAt | null | uložený první start | null |
| effectiveStartedAt | scheduledStartAt | uložený první start | null |
| lastEndedAt | null | předchozí uložený konec nebo null | null |
| activePeriodId | null, období ještě nevzniklo | null, efektivně už neběží | null |
| periodNumber | 0 | počet dosud uložených období | 0 |
| closureRevision | 0 | poslední uložený čítač | 0 |
| currentClosureRevision | null | null, nový konec není uložen | null |

`activePeriodId` vždy označuje **efektivně aktivní a již uložené** období.
U čekajícího konce proto není vráceno jako aktivní, přestože GET running-periods
zatím vrátí poslední uložený řádek s endedAt=null. Nejde o rozpor: historie popisuje
uložená fakta a lifecycle aktuální efektivní stav. `lastEndedAt` se nesmí předběžně
přepsat hodnotou endAt; efektivní čas čekajícího konce je znám z endAt. `version`,
updatedAt, UUID období, revize uzávěrky i historie zůstávají při GET nezměněné.

Pro oprávněného ADMIN platí tato přesná tabulka capabilities:

| Uložený / efektivní stav | canStart | canEnd | canResume | canEditScheduledStartAt | canEditEndAt | canEditBasicDetails | resumeBlockedReason |
|---|---|---|---|---|---|---|---|
| PLANNED / PLANNED, bez pending | true | true | false | true | true | true | NOT_ENDED |
| PLANNED / RUNNING, pending start | true | true | false | false | false | false | NOT_ENDED |
| RUNNING / RUNNING, bez pending | false | true | false | false | true | false | NOT_ENDED |
| PLANNED či RUNNING / ENDED, pending konec | false | false | false | false | false | false | PENDING_TRANSITION |
| ENDED / ENDED, bez definitivní publikace | false | false | true | false | false | false | null |
| ENDED / ENDED, s definitivní publikací | false | false | false | false | false | false | RESULTS_FINALIZED |

Definitivní publikace má jako zábrana resume přednost, pokud je doložena; neověřitelná
publikační informace znamená 503 místo optimistického canResume. Pending tabulka
nepřidává žádný limit počtu obnovení. U pending startu je výslovně zachována možnost
ručního START/END; GET tím před nimi neupřednostní scheduler. Běžné editační PUT jsou
při pending vypnuté, protože nejprve materializují a mohou zvýšit verzi. Při pending
konci UI čeká na scheduler/již běžící zápis a opakuje pouze GET; nenabízí fiktivní
revizi ani speciální materializační endpoint. Přímý POST resume může splatný konec
materializovat, ale původní verze pak způsobí standardní konflikt; retry až po GET
a novém výslovném potvrzení. Capabilities nejsou zárukou, že mezitím nevyhraje souběh.

Automatický start: effectiveAt=scheduledStartAt, processedAt=decisionAt.
Automatický konec: effectiveAt=endAt, processedAt=decisionAt. Ruční start/konec
a obnovení: effectiveAt=processedAt=decisionAt. Scheduler vyhodnocuje každých
30 sekund a po restartu dohání splatné přechody stejným resolverem; nikoli přepisem
historických časů na čas restartu.

Příklad: plán 10:00, GET v 10:01 vrátí RUNNING / persistedStatus PLANNED / pending=true
bez jediného zápisu. Uloží-li nyní první scheduler, start je efektivně 10:00 a
processedAt=10:01. Předběhne-li jej úspěšný ruční START, startedAt=10:01.
Rozhoduje první úspěšně uložený přechod, nikoli předchozí GET. Tentýž důsledek
platí při opakování GET; čtení nesmí měnit vítěze souběhu.

## 4. Hranice účastnických operací – závazek budoucího runtime

Registrace a individuální start se uzavřou již při nyní >= endAt. Pokus o splnění
stanoviště je jiná operace: při automatickém konci platí `receivedAt <= endAt`.
Proto nelze runtime implementovat pouhým `status == RUNNING` ani používat pro
odpovědi stejnou hranici jako pro registrace/start. Uložené ENDED samo o sobě
nesmí zneplatnit prokazatelně včas přijatý pokus.

Navržený příjmový bod runtime je krátká serverová transakce po načtení celého
požadavku a ověření autentizace: pod společným zámkem konání přiřadí serverový
receivedAt, pořadí a období a trvale zaznamená přijetí/odmítnutí. Před ní je žádost
jen doručená na HTTP transport, nikoli přijatá soutěžní operace. Čas se po čekání
na následné vyhodnocení znovu neurčuje. Klientský čas, offline odeslání a requestId
nemohou tvořit důkaz včasného přijetí. Tento příjmový bod ani jeho evidence se
v nynější etapě nevytvářejí; musí být součástí kontraktu runtime.

- Automatické období má začátek inkluzivní a konec **inkluzivní**. I pokud byl
  přechod ENDED uložen při přesném endAt, příjem v tomto okamžiku je ještě možný.
  Uzavření příjmu a definitivní výpočet proto smějí nastat až při nyní > endAt
  a po vypořádání všech dříve trvale přijatých pokusů.
- Ruční END uzavře příjem pod stejným zámkem. Příjmy se stejnou milisekundou
  rozliší serverové pořadí: před END platí, po END neplatí. Není to zaokrouhlením
  vynucený polootevřený interval, který by ztratil dříve přijaté odpovědi.
- Obnovení otevře nové období; původní koncový bod ani mezera ENDED se nemažou.
  Každý příjem nese období a serverové pořadí, takže konec a obnova ve stejné
  milisekundě zůstávají rozlišitelné. Odmítnutý vstup z mezery se neuzná po obnově.
- Opožděné zpracování již přijatého pokusu pracuje s původním obdobím. Dříve
  odmítnutý pokus není přijatým idempotentním výsledkem a nesmí se nově vyhodnotit.
- Před publikací výsledků musí být uzavřena příjmová hranice a vypořádána fronta.
  Samotná lifecycle historie ani nastavení status=ENDED toto nezaručuje.

Toto rozlišení časové a pořadové hranice je nutným předpokladem runtime, nikoli
tvrzením, že dnešní backend již přijímá soutěžní odpovědi.

## 5. API, autentizace a chyby

Vše pod `/api/v1`. Operace konání jsou pouze pro aktuální aktivní ověřené ADMIN;
každý ADMIN může spravovat všechny Akce. Jedinou výjimkou je nový GET souhrnu
vlastních registrací (§12a), dostupný všem aktivním ověřeným účtům. Vlastnictví
simulátoru se zde nepoužívá. Cookie zápisy vyžadují
token a cookie z GET `/auth/csrf`, hlavičku X-XSRF-TOKEN. Alternativou je existující
Bearer relace, bez kombinování obou režimů. Žádné nové přihlášení. Odpovědi mají
Cache-Control: no-store. JSON camelCase, int64 0..9223372036854775807 bez zaokrouhlení
na JavaScript Number, nullable hodnoty jsou explicitní JSON null.

| Metoda a cesta | Request | Úspěšná odpověď |
|---|---|---|
| GET /race-events/{eventId} | žádné tělo | 200 EventView, čistě čtecí efektivní a uložený stav |
| PUT /race-events/{eventId} | EventUpdate | 200 EventView |
| POST /race-events/{eventId}/start | VersionCommand + Idempotency-Key | 200 LifecycleCommandResult |
| POST /race-events/{eventId}/end | VersionCommand + Idempotency-Key | 200 LifecycleCommandResult |
| POST /race-events/{eventId}/resume | ResumeCommand + Idempotency-Key | 200 LifecycleCommandResult |
| GET /race-events/{eventId}/lifecycle-history | page, size | 200 LifecycleHistoryPage |
| GET /race-events/{eventId}/running-periods | page, size | 200 RunningPeriodPage |
| GET /me/registrations/summary | žádné tělo ani parametry; aktivní ověřený účet | 200 MyRegistrationsSummary; aditivní návrh registrací |

History GET jsou čistá čtení. page=0..10000, výchozí 0; size=1..100, výchozí 50;
`{items,page,size,hasMore}`. Historie řadí sequence DESC,id DESC, období number
DESC,id DESC, vždy před stránkováním. Historie roste, při nových záznamech se může
offsetový seznam posunout; sequence/id slouží k deduplikaci. Historie není feed
s garantovaným neměnným offsetem během zápisů.

### EventUpdate a termíny

Zůstává existující PUT: `{version,name,description,scheduledStartAt,endAt}`.
Povinné version a name; description a oba termíny jsou optional/nullable jako
u dnešního DTO. Vynechání znamená null, nikoli PATCH; frontend posílá celý objekt.
name: neprázdné po strip, nejvýše 100 code points, description nejvýše 2000.
Neznámá pole a neplatné JSON typy → 400 INVALID_REQUEST.

- PLANNED: základní údaje i termíny lze měnit. Nový změněný scheduledStartAt
  musí být >= decisionAt; null vypne automatický start. Rovnost je povolena a
  po uložení nastavení se ve stejné transakci materializuje START v tomto čase.
- RUNNING: lze změnit jen endAt; name/description/scheduledStartAt musí zůstat
  stejné. Nemění se historický start ani původní plán. Basic details jsou zamčené.
- ENDED: PUT je odmítnut; obnovuje se samostatným POST /resume.
- Nový změněný nenulový endAt musí být > decisionAt; null je přípustný v PLANNED
  i RUNNING. Dosažený konec již nelze odsunout ani vymazat PUTem.
- **Nevyžaduje se scheduledStartAt < endAt.** Konec před plánovaným startem má
  výslovně přejít PLANNED→ENDED. Stávající V21 CHECK a EventService.validated()
  byly změněny V27 a aplikačním kódem; V21 zůstala beze změny.
- Shodný obsah se správnou verzí nemění updatedAt, verzi ani audit. Časové
  splatné přechody jsou nezávislé a mohou verzi změnit i během no-op požadavku.
- Skutečný PUT mění verzi o 1; okamžitý automatický start po něm o další 1.
  Při rovnosti plánovaného času tedy může odpověď obsahovat version+2.

Vytvoření přes existující POST `/race-templates/{id}/events` nadále vrací původní
Created/Location. Implementace u nových nenulových termínů používá stejné
kontroly >= nyní pro start a > nyní pro konec, bez vazby start<konec. Pokud je
start rovný nyní, založí i automatický START ve stejné transakci; Created.version
odpoví výsledné verzi. Stávající snapshot, oprávnění a idempotence se zachovají.

### Příkazy start/end/resume

START: pouze dosud neuložený první start PLANNED, není dosažen konec. Při pozdním
ručním START se výjimečně nepředřadí automatický start. Pokud jej scheduler již
uložil, ruční operace nic nepřepisuje. END: PLANNED nebo RUNNING, včetně situace,
kdy všechny posádky již dokončily. ENDED je odmítnuto. RESUME: pouze ENDED,
výsledky nesmějí být definitivně zveřejněny; `endAt` je **povinné nullable pole**.
Chybějící pole není souhlas s neomezeným během. Nenulový čas musí být > decisionAt.
Resume nikdy nedohání původní scheduledStartAt a otevře nové období nyní.
Pokud konání nikdy nezačalo, doplní se při resume také jeho první startedAt.

POST má UUID Idempotency-Key, společný CommandStore, 24 hodin. Fingerprint
obsahuje eventId, operaci, version a u resume explicitní endAt v UTC/null.
Replay vrací původní 200 LifecycleCommandResult, nikoli čerstvý EventView.
Aktuální autentizace/ADMIN a existence cíle se ověří i při replay; před kontrolou
verze/stavu se porovná klíč. Stejný klíč/jiný obsah → 409 IDEMPOTENCY_CONFLICT.
Bez klíče je 400. Nový klíč při opakovaném start/end → 409, nikoli tichý no-op.
Po 24 h frontend nejprve GET a historii, neopakuje automaticky neznámý záměr.
PUT nemá ledger; po timeoutu načíst, porovnat a vědomě znovu potvrdit novou verzi.

### Stabilní chyby

| HTTP | code a podmínka |
|---|---|
| 400 | INVALID_REQUEST: tělo/UUID/klíč/typ/required/null/verze/stránkování |
| 400 | RACE_EVENT_SCHEDULE_INVALID: nový plánovaný start v minulosti |
| 400 | RACE_EVENT_END_TIME_INVALID: nový nenulový konec není v budoucnosti |
| 401 | UNAUTHENTICATED; případně INVALID_CREDENTIALS při opětovné kontrole relace |
| 403 | ACCESS_DENIED: security/CSRF; ADMIN_REQUIRED: aplikační kontrola |
| 404 | RACE_EVENT_NOT_FOUND |
| 409 | RACE_EVENT_VERSION_CONFLICT: očekávaná verze nesouhlasí, i po automatickém přechodu |
| 409 | IDEMPOTENCY_CONFLICT: obsazený klíč s jiným záměrem |
| 409 | RACE_EVENT_ALREADY_STARTED: START již není možný |
| 409 | RACE_EVENT_ALREADY_ENDED: END již není možný |
| 409 | RACE_EVENT_NOT_ENDED: RESUME před ukončením |
| 409 | RACE_EVENT_RESULTS_FINALIZED: obnovení blokuje definitivní publikace |
| 409 | RACE_EVENT_ENDED: obecný PUT po konci |
| 409 | RACE_EVENT_SCHEDULE_LOCKED: změna plánovaného startu v RUNNING |
| 409 | RACE_EVENT_DETAILS_LOCKED: změna jména/popisu v RUNNING |
| 503 | RACE_EVENT_LIFECYCLE_UNAVAILABLE: nelze bezpečně určit/uložit stav |
| 503 | RACE_EVENT_RESULTS_CHECK_UNAVAILABLE: nelze ověřit publikační zábranu |

Obecné 405 METHOD_NOT_ALLOWED, 406 NOT_ACCEPTABLE, 415 UNSUPPORTED_MEDIA_TYPE,
500 INTERNAL_ERROR zůstávají dle ApiErrors. ProblemDetail obsahuje type/title/status/
code, případně instance; bez SQL, tokenů nebo vstupních textů. Verze po autorizaci
má přednost před obchodním konfliktem stavu; u POST tomu předchází replay.

## 6. Úplná DTO

Všechna níže uvedená response pole jsou required, nullable je označeno `?`.
UUID jsou string format uuid; časy string date-time; celá čísla jsou JSON integer.
OpenAPI obsahuje strojově kontrolovatelné úplné properties/required a příklady.

**EventView** zachovává všech 15 dnešních polí: id, sourceTemplateId,
sourceTemplateVersion:int64, name, description?, skipPenaltyPoints:int64 signed,
status:PLANNED|RUNNING|ENDED, scheduledStartAt?, endAt?, version:int64,
createdAt, updatedAt, defaultWrongCodePenaltyPoints:int64 signed,
sourceTemplateName, sourceTemplateNameIsSnapshot:boolean. Přidává pouze `lifecycle`.
Stejné aditivní rozšíření se použije v položkách stávajícího GET seznamu konání;
seznam i detail pouze počítají efektivní stav a nic nematerializují.

**Lifecycle**: serverTime; persistedStatus; transitionsPending:boolean;
startedAt?; effectiveStartedAt?; lastEndedAt?; activePeriodId:UUID?;
periodNumber:int64 (0 před prvním během); closureRevision:int64 (0 před koncem);
currentClosureRevision:int64? (jen aktuální ENDED); resultsFinalizedAt?;
canStart, canEnd, canResume, canEditScheduledStartAt, canEditEndAt,
canEditBasicDetails:boolean; resumeBlockedReason:NOT_ENDED|RESULTS_FINALIZED|
PENDING_TRANSITION|null. Čekající přechody v seznamu i detailu mají přesné hodnoty
a capabilities podle tabulky §3; GET nikdy nepřepne uložený stav. Capabilities
neobcházejí zápisovou kontrolu. Pokud závod skončil jen časově, currentClosureRevision
je do materializace null (nevytváří se fiktivní uložená revize).

**VersionCommand**: version:int64 required. **ResumeCommand** navíc endAt:čas|null
required. **LifecycleCommandResult**: raceEventId:UUID, version:int64,
transition:LifecycleHistoryEntry. Původní receipt je neměnný a není aktuální stav.

**LifecycleHistoryEntry**: id:UUID, raceEventId:UUID, sequence:int64 (monotónní,
mezery přípustné), kind:CONFIGURATION|TRANSITION, type:TIMES_UPDATED|STARTED|ENDED|
RESUMED, fromStatus, toStatus, effectiveAt, processedAt,
trigger:ADMIN|SCHEDULER|API_RECONCILIATION,
cause:SETTINGS_UPDATE|MANUAL|SCHEDULED_START|END_AT,
actorKind:USER|SYSTEM, actorAccountId:UUID?, versionBefore:int64,
versionAfter:int64, scheduledStartAtBefore?, scheduledStartAtAfter?, endAtBefore?,
endAtAfter?, periodId:UUID?, closureRevision:int64?, invalidatedClosureRevision:int64?.
TIMES_UPDATED má kind CONFIGURATION a nezměněný stav, ostatní kind TRANSITION.
Systém má actorAccountId=null, ruční záznam má UUID aktéra. Nastavení je jeden
záznam i při změně obou termínů; samotná změna názvu/popisu není lifecycle přechod.

**RunningPeriod**: id:UUID, raceEventId:UUID, number:int64 od 1, startedAt,
endedAt?, endBoundary:INCLUSIVE|SERVER_ORDER|null, startTransitionId:UUID,
endTransitionId:UUID?, startSequence:int64, endSequence:int64?. Otevřené období
má všechna koncová pole null; automatický konec INCLUSIVE, ruční SERVER_ORDER.
Ukončení PLANNED nezaloží prázdné či fiktivní období. Start a ruční konec ve stejné
milisekundě jsou povoleny a rozlišeny pořadím; doba nikdy není záporná.

**LifecycleHistoryPage/RunningPeriodPage**: items:array příslušných DTO,
page:int32, size:int32, hasMore:boolean. Role, uživatelská jména ani tokeny se
do těchto DTO nepřidávají. Výpočty výsledků/penalizací zde nejsou.

**MyRegistrationsSummary**: pouze `activeCount`, required JSON integer int64
0..9223372036854775807. Žádná další pole. Aditivní návrh registrací viz §12a.

## 7. Příklady použití

GET `/race-events/11111111-1111-4111-8111-111111111111` vrátí úplný EventView
z příkladu `EventDetail` v YAML. Klíčová nová část před startem:

```json
{"serverTime":"2026-11-01T09:00:00Z","persistedStatus":"PLANNED","transitionsPending":false,"startedAt":null,"effectiveStartedAt":null,"lastEndedAt":null,"activePeriodId":null,"periodNumber":0,"closureRevision":0,"currentClosureRevision":null,"resultsFinalizedAt":null,"canStart":true,"canEnd":true,"canResume":false,"canEditScheduledStartAt":true,"canEditEndAt":true,"canEditBasicDetails":true,"resumeBlockedReason":"NOT_ENDED"}
```

PUT stejná cesta, response 200 EventView s novou verzí a uloženými termíny:

```json
{"version":4,"name":"Podzimní Akce","description":null,"scheduledStartAt":"2026-11-01T10:00:00Z","endAt":"2026-11-01T16:00:00Z"}
```

POST `/race-events/{eventId}/start`, Idempotency-Key UUID; při serverovém
09:30 před plánovaným 10:00: request `{"version":5}`; response 200 obsahuje
raceEventId, version=6 a přechod STARTED, MANUAL, PLANNED→RUNNING,
effectiveAt=processedAt=09:30. Úplný JSON `Started` je v YAML.

POST `/race-events/{eventId}/end`, nový klíč, request `{"version":6}`;
response 200 `Ended`: version=7, RUNNING→ENDED, čas serveru 12:00,
closureRevision=1, periodId prvního období.

POST `/race-events/{eventId}/resume`, nový klíč:

```json
{"version":7,"endAt":"2026-11-01T17:00:00Z"}
```

Alternativa bez limitu je `{"version":7,"endAt":null}`. Response 200 `Resumed`
má version=8, ENDED→RUNNING, effectiveAt=processedAt=12:20, nový periodId,
invalidatedClosureRevision=1. Původní konec 12:00 zůstává v historii.

GET `/race-events/{eventId}/lifecycle-history?page=0&size=50` vrací 200 stránku
se záznamy Resumed, Ended, Started v sestupném pořadí. GET
`/race-events/{eventId}/running-periods?page=0&size=50` vrací otevřené druhé období
a první uzavřené v 12:00 s endBoundary=SERVER_ORDER. Úplné příklady requestů,
stránek i receiptů jsou v příslušných responses/requestBodies v YAML.

Konflikt: 409 `{"type":"about:blank","title":"Conflict","status":409,
"code":"RACE_EVENT_VERSION_CONFLICT"}`. Frontend načte detail, zachová rozepsaný
návrh a neodesílá automaticky novou verzi.

## 8. Transakce, scheduler a souběh

Zachovat existující pořadí admin_control → účty → případná posádka → konání
→ přihláška/průběh → command/audit. Scheduler nepotřebuje účet a zamyká
admin_control před konáním; výběr kandidátních UUID je jen nezamčené čtení.
Nezačínat SELECT FOR UPDATE konání před globálním zámkem. Jeden kandidát/jedna
krátká transakce; kandidáti stabilně podle UUID, omezená dávka, interval 30 s.
Více instancí může vybrat stejná UUID, pod zámkem se znovu ověří stav/termíny.
Není potřeba další distribuovaný framework ani samostatný globální advisory lock.

Resolver je společná aplikační logika, nikdy nezávislá kopie scheduleru. Jeden
automatický přechod, historie, období, event.version+1 a SYSTEM audit se potvrdí
atomicky. Opakované vyhodnocení nic nepřidává. Selhání auditu rollbackuje vše;
další tick/relevantní API zpracování opakuje. Stavové řádky, ne paměť instance,
jsou autorita pro souběh. Aktualizovat verzi i u systémových změn je záměr.

| Operace | Před vlastním čtením/zápisem |
|---|---|
| ADMIN GET detail, GET seznamu/history/periods, všechny registrační GET, GET /me/registrations/summary | čisté čtení; efektivní stav bez auditu/zápisu; history/periods jen uložené řádky |
| PUT event/registrations/settings/members, POST registration | materializovat časově splatné přechody pod stejnými zámky |
| POST start/end | replay; splatný konec; ruční záměr před dosud neuloženým automatickým startem |
| POST resume | replay; materializovat případný splatný konec; verze; ENDED a publikační zábrana |
| Vytvoření konání | po uložení okamžitě splatný start; konec na vstupu musí být budoucí |
| Instrukce/deletion konání | resolver před kontrolou PLANNED; audit/verze jako dosud |
| Budoucí start/pokus/publikace | resolver + příjmový protokol §4, nestačí kontrola statusu |

Po materializaci se porovná verze. Pokud automatický přechod způsobí konflikt
či stavové odmítnutí, tento systémový přechod se **nesmí rollbacknout vyhozením
business výjimky uvnitř stejné transakce**. Doporučený postup: transakce vrátí
commitovatelný výsledek nebo business chybu, a teprve po úspěšném commitu se chyba
převede na ProblemDetail. Odmítnutý ADMIN záměr nemá USER audit ani ledger;
oprávněný SYSTEM přechod zůstává. Technická chyba/audit/commit failure rollbackují
celou transakci. Neplatná struktura, autentizace, replay konflikt se řeší dříve.

Souběhy: dříve zamčená a ještě včas platná změna endAt může konec odsunout;
po dosažení původního termínu již vyhraje resolver a změna musí použít resume.
Dva ADMIN příkazy se stejnou verzí mají nejvýše jednoho vítěze. Scheduler proti
ručnímu START: kdo uloží první start, určí jeho čas; druhý obdrží konflikt/verzi.
Při ručním END po dosažení endAt se uloží jen automatický konec a ruční požadavek
se odmítne (zastaralá verze má přednost). Při změně plánu ve chvíli jeho splatnosti
se nejprve uloží automatický START; termín již nelze přeložit.

## 9. Registrace beze změny veřejného kontraktu

`registration-api-v1` se nemění, ani jeho YAML/MD. Pure resolver je nutné vložit
do RegistrationStore/Service před vyhodnocení RegistrationRules: EventSummary.status
je efektivní, startedAt uložený první start (při pending ještě null), endAt aktuální.
RUNNING už samo zavírá AUTO. Při ENDED vyhraje konec, při resume zůstane override;
AUTO se neotevře znovu (status RUNNING/startedAt), nové přihlášky otevře jen OPEN.
Neodstartované složení lze po resume znovu upravovat; individuální snapshot zůstává
zamčený. POST replay zůstane před otevřeností/duplicitou, oprávnění se ověří vždy.

Registrační PUT/POST mohou nyní potřebovat materializaci před vlastní změnou,
ale registrační GET zůstává bez zápisu. Systémový přechod může zvýšit společnou
event.version a vyvolat existující RACE_EVENT_VERSION_CONFLICT; DTO ani kódy
registrací se tím nemění. Verze přihlášky se lifecycle přechodem sama nezvyšuje.
Ochrana CREW_IN_USE/RACE_EVENT_IN_USE a D10 zůstává. AccountParticipationFacts
musí při budoucích skutečných startech používat efektivní běh/období, ne zastaralý
endAt/status; samotná registrace nadále není historická ani běžící účast.

## 10. Historie, obnova a budoucí výsledky

Období jsou explicitní a oddělená od administrační historie. endedAt zůstane
uložený na původním období; při resume vzniká nové UUID/number. Registrace,
override, individuální starty, dokončení i aktuální průběhy zůstávají zachované;
resumé samo nerestartuje posádky ani nesmaže body. Doba vypnutí není zpětně
aktivním obdobím. Politika započítání přestávky do budoucích časových penalizací
není tímto návrhem určena; viz otevřená otázka §13.

Každý END zvýší closureRevision a založí historický konec. Resume atomicky
zneplatní currentClosureRevision a označí její odvozené výsledky/koncové
penalizace za neúčinné; nemaže je ani nenuluje jiné body. Další END vytvoří novou
revizi a následný runtime znovu spočítá koncové odvozené údaje. V této etapě se
pouze připraví revize a invalidatedClosureRevision, žádné výsledky se nepočítají.

Publikační zábrana `resultsFinalizedAt` je důvěryhodný údaj konání, default null.
Žádný nynější endpoint jej neumí nastavit ani vymazat. Budoucí definitivní
publikace jej musí měnit pod týmž zámkem/verzí jako resume a v jedné transakci
se zveřejněním výsledků. Neověřitelná vazba znamená 503, nikoli předpoklad null.
Nelze slibovat dokončenou kontrolu cizího dosud neexistujícího výsledkového modulu.

## 11. Implementovaný databázový model V27

Nová migrace je **V27__race_event_lifecycle.sql**. V1–V26 se nemění.
Migrace a databázové invarianty zatím čekají na PostgreSQL ověření.

| Objekt | Navržená změna a invariant |
|---|---|
| race_event | stav whitelist PLANNED/RUNNING/ENDED místo V21 PLANNED; odstranit CHECK scheduledStartAt<endAt |
| Časy | started_at nullable, last_ended_at nullable; stávající scheduled_start_at/end_at zůstávají konfigurace |
| Čítače | lifecycle_order, period_number, closure_revision bigint >=0, default 0; current_closure_revision nullable; results_finalized_at nullable |
| race_event_lifecycle_history | UUID PK, event FK RESTRICT, UNIQUE(event,sequence), všechny položky LifecycleHistoryEntry; immutable; změny termínů se uchovají i po retenci auditu |
| race_event_running_period | UUID PK, event FK RESTRICT, UNIQUE(event,number), start/end history FK; jediný otevřený řádek na event přes partial UNIQUE; end>=start a úplná koncová pole |
| Historie aktéra | actor_kind a historické actor_account_id UUID jako v auditu, bez živé FK bránící zrušení účtu; neukládat jména/e-mail |
| Indexy | history(event,sequence DESC,id DESC), periods(event,number DESC,id DESC); partial indexy PLANNED/scheduled_start_at a PLANNED+RUNNING/end_at pro scheduler |

`lifecycle_order` řadí události nezávisle na version; při budoucím příjmu odpovědí
může alokovat i receipt pořadí, aniž by každý pokus měnil administrační verzi.
Historie proto negarantuje bezmezerovost sequence. period_number narůstá jen při
otevření období. ENDED vyžaduje last_ended_at a aktuální closure; RUNNING vyžaduje
started_at a jedno otevřené období, PLANNED nemá started_at ani běh. Přechodové
zámky a odložená konzistenční kontrola chrání parent/history/period v jedné
transakci; není správné povolit libovolný přímý UPDATE statusu bez historie.
Vzájemné FK mezi historií a obdobím musejí být odložené nebo bezpečně doplněné
ve stejné transakci; vnější čtenář nesmí vidět poloviční otevření/uzavření období.

Staré PLANNED řádky se uchovají jako PLANNED, čítače 0/nové nullable hodnoty null,
bez odvozování startu ze simulací. Splatné přechody se po nasazení zpracují běžným
resolverem; pokud už skončil endAt, vznikne přímý historický END. Rozšířit přesné
guardy EventStore/TemplateStore/PreRaceAccountParticipationFacts jen o známou V27.
Neznámé budoucí migrace se nepovolují. Nové CommandAccounts formáty obsahují jen
UUID, verzi a termíny; související účet je aktér, event/period nejsou účty.

## 12. Frontend a testovací matice

Detail konání používá nový EventView.lifecycle, stav od serveru a jeho čas.
Polling při otevřeném detailu 30 s plus po každé operaci; lze obnovit ručně,
nevzniká lokální auto-start podle hodin prohlížeče. Samostatně zobrazit plán,
první skutečný start, aktuální období, limit a poslední konec. Dočasně zobrazit
pending systémový přechod v seznamu i detailu; ani jeden jej neukládá. Dokud je
pending start, UI podle canStart/canEnd dovolí ruční příkaz, který může scheduler
předběhnout. Při pending konci zobrazí důvod PENDING_TRANSITION a čeká na materializaci
schedulerem nebo relevantním zápisem; obnovuje GET, nikoli zápis pouze kvůli refreshi.

START/END/RESUME vyžadují výslovný potvrzovací dialog, okamžik se nevybírá.
Resume formulář musí výslovně volit nový budoucí konec nebo běh bez limitu.
PUT používá celou aktuální EventView a novou společnou verzi; zmrazené atributy
neodesílá jako změněné. Při konfliktu zachovat návrh, GET a explicitní nové
potvrzení; žádný automatický rebase. POST po neznámém výsledku opakuje tentýž
klíč/tělo, po receipt vždy GET aktuálního stavu. UI nesmí nabízet individuální start
nebo výsledky jako hotové. Backend i frontend lze implementovat podle DTO níže
po schválení; časové rozhodování a souběhy se nedokazují mocky frontendu.

| Scénář | Požadovaná kontrola budoucích testů |
|---|---|
| AUTO před/přesně/po plánovaném startu | efektivní stav; effectiveAt plán, processedAt skutečné zpracování |
| Opakované GET detail/list/history/periods/registrace/summary | stav počítán jedním serverovým časem; žádný zápis, audit, změna verze ani doplnění historie; read-only přístup |
| GET při pending START/END | přesná pole a capabilities podle §3; activePeriodId/closure nejsou vymyšlené; history/periods jen uložené řádky |
| Ruční START před/po plánu | skutečné nyní, pokud scheduler ještě neuložil start; opačný vítěz → konflikt |
| Plán 10:00 a první uložení 10:01 | scheduler → effectiveAt 10:00; ruční START → 10:01; předchozí GET nesmí rozhodnout souběh |
| Backend vypnut přes start i konec | přímý END z PLANNED, žádné vymyšlené období |
| Změna plánu | minulost odmítnuta, rovnost nyní atomicky nastavení+START; future/null; no-op |
| AUTO END | ENDED při >=endAt, přesný historický konec i při opoždění |
| Ruční END | aktuální čas, před startem i po dokončení všech posádek, opakování odmítnuto; z PLANNED bez startu/období, startedAt=null, verze+1 a audit |
| Termíny | end před plánem, zkrácení/prodloužení/null; nyní/minulost odmítnuty; po konci jen resume |
| Bez endAt | RUNNING bez automatického konce; ruční END funguje |
| Obnovení | budoucí end/null povinně explicitní, zachování registrací a průběhů, opakování bez limitu |
| Publikace/resume | jediný vítěz pod zámkem, finalized blokuje; neověřitelný stav fail closed |
| Historie | nepřepisovat původní konec, druhé období, configurační historie vs běh, paging/pořadí |
| Stejná milisekunda | START+END bez záporné doby; END+RESUME oddělené sequence/periodId |
| Budoucí odpovědi | endAt−1 ms, endAt, endAt+1 ms; příjem vs pozdější zpracování/finalizace |
| Dva ADMIN/scheduler/instance | jeden přechod, jeden audit, správné verze, retry po rollbacku |
| Odmítnutý příkaz po AUTO | systémový přechod zůstane commitnutý; odmítnutý USER záměr bez auditu/ledgeru |
| Registrace | pure GET bez zápisu, pending start zavře AUTO, OPEN přednost, ENDED zavře vše, resume zachová override |
| Složení | před individuálním startem lze měnit po resume, nikoli v mezeře ENDED; historický start neměnit |
| Auth/CSRF/idempotence | 401/403, Bearer, cookie bez/s chybným tokenem, replay/conflict/24 h, ztráta ADMIN |
| Migrace | V26 data zachována, známá V27 akceptována, V28/neznámé odmítnuty |

## 12a. Souhrn vlastních aktivních registrací – aditivní rozšíření

`GET /api/v1/me/registrations/summary` je **aditivní rozšíření registračního API
navržené společně s race-event-lifecycle-api-v1**, nikoli změna sedmi operací
`registration-api-v1`. Souhrn je implementován. Tento dokument a lifecycle OpenAPI
jsou jeho jedinou kanonickou definicí; registrační kontrakt na ně pouze odkazuje.
Existující `/auth/me` a ostatní autentizační cesty zůstávají beze změn.

### Kontrakt a oprávnění

- Aktivní ověřený účet ze stávající cookie nebo Bearer relace. Nevyžaduje ADMIN.
  ADMIN dostává jen vlastní souhrn. Nezavádí se nová role.
- Bez path/query parametrů a bez request DTO. Účet nelze zadat v URL ani těle;
  čerpá se výhradně z ověřené relace. Nepodporované query parametry → 400
  INVALID_REQUEST, nikdy přepnutí na jiného uživatele.
- Čistě čtecí GET, bez CSRF tokenu, Idempotency-Key či verze. Žádná materializace,
  audit, změna relace, historie, verzí nebo jiných trvalých údajů.
- 200 application/json, `Cache-Control: no-store`. DTO `MyRegistrationsSummary`
  má jediné required pole activeCount: nezáporný JSON integer int64, ne string.
  PostgreSQL count(*) vrací bigint; frontend zachová přesnost stejným lossless
  parserem jako u ostatních int64. Nula je skutečný úspěšný prázdný výsledek,
  nikoli náhrada za chybu.

```http
GET /api/v1/me/registrations/summary
Accept: application/json
```

Cookie nebo Authorization: Bearer se použije podle stávajícího režimu, ne obojí.
Úspěšné příklady (stejné DTO při libovolném počtu vlastních posádek):

```json
{"activeCount":3}
```

```json
{"activeCount":0}
```

Chyby: 400 INVALID_REQUEST pro nepodporované parametry; 401 UNAUTHENTICATED nebo
INVALID_CREDENTIALS při neplatné relaci/účtu; 403 ACCESS_DENIED pro existující
bezpečnostní odmítnutí (nikoli chybějící ADMIN); 503 RACE_EVENT_LIFECYCLE_UNAVAILABLE
při neověřitelném lifecycle; neočekávaná chyba včetně SQL → 500 INTERNAL_ERROR.
Obecné 405/406 dle ApiErrors. ProblemDetail a no-store jako výše, bez PII. Neexistuje
404 jen proto, že uživatel nemá přihlášky, ani fallback na count=0 při chybě.

### Přesná definice a dotaz

Počítají se **řádky crew_registration**, jejichž posádka má nyní kapitána z
ověřené relace a efektivní stav konání je PLANNED nebo RUNNING. Dvě posádky ve
stejném konání jsou dvě přihlášky. Stávající stav přihlášky REGISTERED se nemění
a nevytváří se nová hodnota ACTIVE. Nepoužívá se autor přihlášky, historický
kapitán, registrační override/otevřenost, individuální start ani dokončení posádky.
Osiřelá posádka se nezapočítá žádnému bývalému kapitánovi.

Souhrn používá tentýž čistý lifecycle resolver a jedno decisionAt pro všechny
řádky. Protože oba neukončené stavy jsou aktivní, lze jeho podmínku pro tento
konkrétní COUNT algebraicky zjednodušit: status je PLANNED/RUNNING a endAt je null
nebo > decisionAt. Plánovaný start členství v množině aktivních stavů nemění.
Nejde o druhý stavový automat; budoucí unit/SQL integrační ověření musí ověřit
ekvivalenci této projekce proti společnému resolveru pro všechny známé stavy a
hranice. Neznámý stav/schéma se nesmí tiše považovat za nulový souhrn.

Implementovaný jediný agregační SQL dotaz (`RegistrationStore.activeCount`):

```sql
WITH decision AS (SELECT CAST(:decisionAt AS timestamptz) AS at)
SELECT count(*)::bigint AS active_count
FROM crew_registration r
JOIN crew c ON c.id = r.crew_id
JOIN race_event e ON e.id = r.race_event_id
CROSS JOIN decision d
WHERE c.captain_account_id = :authenticatedAccountId
  AND r.status = 'REGISTERED'
  AND e.status IN ('PLANNED', 'RUNNING')
  AND (e.end_at IS NULL OR e.end_at > d.at);
```

`:decisionAt` je jednou odebraný serverový Clock.instant, zkrácený na milisekundy,
předaný jako parametr; nikdy klientská hodnota ani nová hodnota pro každý řádek.
Agregace je jeden SELECT nad jedním PostgreSQL MVCC snapshotem v read-only režimu,
bez zamykání/zápisu pro lifecycle. Při souběhu s END/RESUME/převodem získá všechny
dotčené řádky z jedné snapshotové perspektivy: validní starý nebo nový výsledek,
ne směs různých verzí z několika dotazů. Další GET může legitimně vrátit jiný počet.
Nečte se seznam do aplikační paměti, neprovádí se N+1 ani count(DISTINCT event).

Existují: crew_captain(captain_account_id,created_at,id) z V3,
registration_crew_defaults(crew_id,registered_at DESC,id DESC) z V26,
UNIQUE(race_event_id,crew_id) a PK race_event(id). Tyto indexy podporují výběr
vlastních posádek a joiny; nyní není nezbytný nový index. Pokud pozdější EXPLAIN
na reálných objemech doloží přínos, lze zvážit covering index
crew_registration(crew_id,race_event_id). Samotný návrh jej nevyžaduje a žádná
migrace/index nyní nevzniká. Ruční END vyřadí registrace okamžitě podle uloženého
ENDED; po resume s novým budoucím endAt/null se znovu započtou. Pending konec se
vyřadí už při rovnosti decisionAt=endAt, pending start zůstává započtený.

Frontend Přehled volá tento endpoint, neprovádí vlastní sčítání stránek, výpočet
stavů podle hodin ani ADMIN scope=all. Při chybě zobrazí nedostupný údaj, nikoli
zavádějící nulu. Vlastní souhrn se po změně stavu/převodu/registraci znovu načte.

### Matice budoucího ověření souhrnu

| # | Scénář | Očekávaný výsledek |
|---|---|---|
| 1 | Žádná přihláška | 200, activeCount=0 |
| 2 | Jedna vlastní posádka v PLANNED | 1 |
| 3 | Jedna vlastní posádka v RUNNING | 1 |
| 4 | Konání ENDED | 0 |
| 5 | Dvě vlastní posádky ve stejném konání | 2, nikoli 1 |
| 6 | Přihlášky do více konání | Součet jednotlivých aktivních přihlášek |
| 7 | Pending start po scheduledStartAt, DB PLANNED | Započteno, GET nic neuloží |
| 8 | Pending konec v endAt/poté, DB PLANNED či RUNNING | Nezapočteno, GET nic neuloží |
| 9 | Registrace CLOSED, konání aktivní | Započteno |
| 10 | Posádka individuálně odstartovala/dokončila, konání RUNNING | Započteno |
| 11 | Ruční END | Příslušné řádky přestanou být započtené |
| 12 | RESUME s budoucím endAt/null | Původní řádky se opět započtou |
| 13 | Převod kapitána | Starý/nový účet podle současného vlastníka; historie kapitána nerozhoduje |
| 14 | ADMIN a cizí posádky | Jen vlastní registrace, žádný globální souhrn |
| 15 | Nepřihlášený/odvolaná relace | 401 bez souhrnu |
| 16 | Opakovaný GET | Beze změny všech řádků, historie, auditu a verzí; bez CSRF |
| 17 | Souběh END/RESUME/převod | Jediný DB snapshot a společné decisionAt; žádné dvojí načítání |
| 18 | Chyba DB/resolveru nebo nepodporovaný parametr účtu | Chyba dle kontraktu, nikdy náhradní nula ani cizí souhrn |

## 13. Skutečně otevřené navazující otázky

Následující body neblokují samotný lifecycle kontrakt, ale blokují příslušnou
pozdější implementaci výpočtu/průchodu a publikací. Nejsou vyřešené novým návrhem:

1. **Časové penalizace během mezery ENDED:** běží čas aktivovaného stanoviště
   dál, nebo se přestávka odečítá? Doporučení pro rozhodnutí: přestávku odečítat
   z účinné doby; varianta zachování wall-clock je jednodušší, ale penalizuje
   organizační zastavení. Zde se neimplementuje ani jedna varianta.
2. **Definitivní publikace po stažení:** §7 dovoluje stáhnout/opravit/znovu
   zveřejnit výsledky, zadání blokuje resume po definitivním zveřejnění. Má být
   tato zábrana trvalá po první publikaci, nebo po výslovném stažení odvolatelná?
   Doporučení: trvalá po první definitivní publikaci, dokud nebude výslovně
   schválen jiný postup. Kontrakt pouze kontroluje resultsFinalizedAt; v této
   etapě se nikde nenastavuje/neruší. Není to schválení nové publikační politiky.

## 14. Implementace a ověření etapy 2

`EventLifecycle` je společná čistá projekce stavu. `LifecycleService.reconcile`
materializuje přechody v transakci volajícího; používá jej scheduler i zápisy.
`LifecycleScheduler` zpracuje nejvýše 100 kandidátů každých 30 sekund, první běh
po 30 sekundách. Profil `it` jej vypíná; testy volají přímo `processDue`.
Každé konání má krátkou samostatnou transakci a po získání zámků se znovu rozhodne.

Zámky: admin_control → účty → případná posádka → konání → přihláška → ledger/audit.
Splatný přechod se zachová při následné očekávané business chybě; jen validace
vrací chybu odloženou za commit. SQL, audit, serializace, chyby 5xx a commit
rollbacknou transakci. Neexistuje obecné zachytávání chyb po provedení mutací.

GET používají read-only transakce a konzistentní snapshot/čas. Lifecycle metadata
se načítají spolu s konáním, souhrn jedním COUNT; nevzniká N+1. Autentizace těchto
GET pouze ověří relaci, nemění last_used_at ani expiraci. Ostatní endpointy si
zachovávají dosavadní obnovování relací.

V27 přidává odložené FK, konzistenční triggery, čítač lifecycle_order, historii a
období. Historii nelze přepsat. Restriktivní FK chrání i PLANNED konání s uloženou
historií změn termínů: DELETE vrací existující `409 RACE_EVENT_IN_USE`.
Konání bez historie/přihlášek lze nadále smazat. Přesné guardy znají V27 a odmítají
V28; existující testy neznámé migrace byly posunuty na V28.

`results_finalized_at` je rezervovaná důvěryhodná zábrana; žádný nynější endpoint
ji nenastavuje ani nemaže. Výsledkový modul, publikace, individuální start,
zařízení, průchod ani frontend nebyly implementovány. Simulační pravidla a staré
migrace se neměnily.

Ověření Codexem (30. 10. 2026): Microsoft JDK 21.0.12.1, Maven Wrapper
`verify` bez DB: **BUILD SUCCESS, 782 testů, 0 failures/errors/skipped**, včetně
kompilace všech integračních testů. OpenAPI 3.1 validní: **114 lokálních referencí,
14 schémat a 86 validací request/response příkladů**. Původní registrační OpenAPI
je rovněž validní. Nová EventLifecycleIT obsahuje **29 připravených scénářů**.
`git diff --check` prošel. Tato kontrola neprovedla Flyway migraci proti DB.
PostgreSQL scénáře `EventLifecycleIT` a navazující regrese čekají na uživatele:

```powershell
.\mvnw.cmd -Pit verify
# případně cílené ověření:
.\mvnw.cmd -Pit "-Dit.test=EventLifecycleIT,RegistrationIT,EventsIT,AccountCancellationServiceIT" verify
```

Do potvrzení výsledku zůstávají V27, skutečné souběhy, FK/triggery a rollbacky
integračně neověřené. Kompilace a mockované testy toto ověření nenahrazují.

## 15. Soubory změněné v této etapě

- `docs/backend-plan.md`
- `docs/participation-registration-api.md`
- `docs/participation-registration-openapi.yaml`
- `docs/race-event-lifecycle-api.md`
- `docs/race-event-lifecycle-openapi.yaml`
- `docs/testing.md`
- `src/main/java/cz/mh/rallye/rallye_backend/audit/application/AuditService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/audit/persistence/AuditStore.java`
- `src/main/java/cz/mh/rallye/rallye_backend/crews/persistence/CrewStore.java`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/api/AuthConfiguration.java`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/api/AuthSessionFilter.java`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/application/AuthService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/PreRaceAccountParticipationFacts.java`
- `src/main/java/cz/mh/rallye/rallye_backend/participation/api/MyRegistrationsController.java`
- `src/main/java/cz/mh/rallye/rallye_backend/participation/application/RegistrationService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/participation/domain/RegistrationRules.java`
- `src/main/java/cz/mh/rallye/rallye_backend/participation/persistence/RegistrationStore.java`
- `src/main/java/cz/mh/rallye/rallye_backend/races/api/LifecycleController.java`
- `src/main/java/cz/mh/rallye/rallye_backend/races/application/EventService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/races/application/LifecycleScheduler.java`
- `src/main/java/cz/mh/rallye/rallye_backend/races/application/LifecycleService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/races/domain/EventLifecycle.java`
- `src/main/java/cz/mh/rallye/rallye_backend/races/persistence/EventStore.java`
- `src/main/java/cz/mh/rallye/rallye_backend/races/persistence/LifecycleStore.java`
- `src/main/java/cz/mh/rallye/rallye_backend/shared/persistence/CommandAccounts.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/persistence/TemplateStore.java`
- `src/main/resources/db/migration/V27__race_event_lifecycle.sql`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/AuthServiceTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/EventLifecycleIT.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/EventsIT.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/PreRaceAccountParticipationFactsTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/SimulationIT.java`
- `src/test/java/cz/mh/rallye/rallye_backend/participation/RegistrationHttpTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/participation/RegistrationServiceTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/races/EventHttpTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/races/EventLifecycleTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/races/LifecycleHttpTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/races/application/EventServiceTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/shared/persistence/CommandAccountsTest.java`

Uživatelské `.gitignore` a `docs/Rallye_specifikace.md` zůstaly beze změny.


## Závěrečné uzavření etapy

Uživatel při zadání závěrečného commitu potvrdil úspěšné automatické testy včetně
PostgreSQL a ruční integrační ověření s frontendem. Samostatně jsou potvrzeny
AboutIT a EventLifecycleIT včetně seznamu vlastních přihlášek. Dřívější čekající
stavy v průběžných záznamech popisují stav před tímto potvrzením, nikoli aktuální
překážku. Nový závěrečný běh Codexu je pouze verify bez profilu it; PostgreSQL
se znovu nespouští. Registrace, zařízení, individuální start a závodní runtime
nad rámec existujících registrací nejsou součástí této etapy.

Závěrečná kontrola před commitem: Maven Wrapper / Microsoft JDK 21.0.12.1,
`verify` bez PostgreSQL: **799 testů, BUILD SUCCESS, 0 failures/errors/skipped**.
Oba OpenAPI dokumenty prošly OpenAPIV31SpecValidator 0.9.0 včetně schémat
a interních referencí. PostgreSQL nebylo Codexem znovu spuštěno.
