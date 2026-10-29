# Simulátor závodu nad šablonou – technický návrh

**Schválený kontrakt; backend i rozšířená historie (§15) integračně potvrzeny uživatelem**, 29. 10. 2026. Podklad: commit
9afad4b9fdd964ffb71127e2a2ddbc1789d92641, AGENTS.md, specifikace 0.9.0 a aktuální
zdrojový kód. Q1–Q4 jsou rozhodnuté podle následného zadání uživatele (přehled v §12).
Schválené funkční chování je závazné pro návrh; technická doporučení mimo schválený
rozsah zůstávají označená jako doporučení. Pravidlo přeskočení bylo samostatně
ověřeno ve specifikaci (§3.1 níže), nikoli nově schváleno tímto zadáním.

## 1. Výchozí stav před implementací a ověřené zdroje

Tato kapitola zachovává analýzu výchozího commitu před implementací; hotový
simulátor a ověření jsou popsány v §14. Java cesty v této kapitole jsou relativní k
`src/main/java/cz/mh/rallye/rallye_backend/`. Přístup k datům je JDBC. JPA entity
závodních průběhů, simulací nebo penalizací jsem nenašel.

| Oblast | Konkrétní implementace | Co skutečně poskytuje |
|---|---|---|
| Konfigurace | templates/domain/StageConfiguration | Gps, Code, TimePenalty, Delayed; normalizace a Code.matches, nikoli vyhodnocení celého pokusu |
| Připravenost | templates/application/TemplateReadiness, TemplateFileReferences.readiness | Úplnost GPS/CODE, AND, návaznost souborů; žádné runtime skóre |
| Šablony | TemplateController, TemplateService, TemplateStore | race_template, template_stage, CRUD, pořadí, rodičovská version; global defaultWrongCodePenaltyPoints a nullable override |
| Obsah | InstructionsMarkdown, ImageNames, TemplateFileContent, TemplateFileService, TemplateImageFiles | CommonMark/GFM, sanitizace, knihovna JPEG/PNG/MD/PDF, privátní soubory |
| Akce | races/api/EventController, application/EventService, EventFiles, persistence/EventStore | race_event, event_stage, event_file; PLANNED snapshot a editace jeho instrukcí |
| Autentizace | identity/api/AuthConfiguration, AuthSessionFilter; application/AuthService | Cookie/CSRF a Bearer, aktuální ADMIN z DB; /api/v1/admin/** již chrání filtr |
| Audit | audit/application/AuditService, persistence/AuditStore | Transakční audit_event; retenční bezpečnostní audit není trvalým replay logem |
| Idempotence | shared/persistence/CommandStore, CommandAccounts, CommandCleanupStore | admin_role_command, actor/key, JSON request/result, 24 h a vazby na účty |
| Ochrany | PreRaceAccountParticipationFacts, TemplateStore.ensureDeletionSupported, EventStore.ensureDeletionSupported | Explicitní migration guardy nyní znají V22; nové schéma vyžaduje vědomou revizi |
| Zrušení účtu | identity/application/AccountCancellationService a AccountCancellationStore | Konzistentní ADMIN/account zámky, fyzické smazání/anonymizace; nové simulační vazby musí mít úklid |

API šablon: `/api/v1/race-templates`, `/{id}/stages`, `/{id}/files`,
`/{id}/validations`, konfigurace/status/duplikace. API Akcí:
`/api/v1/race-events`, `/{id}/stages`, `/{id}/files`, PUT instrukcí stanoviště.
ADMIN DTO šablon a Akcí obsahují skutečný CODE; nesmějí být herním DTO simulace.

Relevantní migrace: V16 šablony, V17 konfigurace/READY, V18 původní HTML,
V19 Markdown, V20 knihovna, V21 Akce, V22 plaintext CODE/globální sazba/název zdroje.
Existující migrace se nepřepisují.

Testovací vzory v `src/test/java/cz/mh/rallye/rallye_backend/`:
`templates/TemplateReadinessTest`, `TemplateServiceTest`, `InstructionsMarkdownTest`,
`TemplateFilesTest`, `ImageNamesTest`; `races/EventMarkdownTest`, `EventHttpTest`,
`races/application/EventServiceTest`; `identity/TemplatesIT`, `EventsIT`,
`AuditTransactionTest`, `AccountCancellationServiceIT`. PostgreSQL IT používají
TestDatabaseInitializer, rallye_test a izolovaná schémata; běží ručně u uživatele.

**Co chybí:** evaluator pokusů, výpočet GPS vzdálenosti, race penalty_event,
aktivace/průchody, historie pokusů i simulace. Názvy těchto objektů ve specifikaci
nejsou důkazem hotových tabulek. Pouze Code.matches už lze přímo využít.

**Vyřešené návaznosti a zjištěné zdroje pravidel:**

- Původní zadání žádalo kvízy, ale 0.9.0 §4.1 a §14 bod 11 je nechává mimo rozsah.
  Q1 rozhodla jejich odložení také z první etapy simulátoru. Quiz model v kódu není.
- Q2 doplnila jednoznačnou kumulaci dílčích úspěchů GPS/CODE do dokončení nebo
  opuštění aktivace, bez expirace. AND není otevřená otázka.
- Přeskočení: specifikace 0.9.0 §7.1 výslovně stanoví nahrazení dosavadních
  penalizací daného stanoviště; implementace závodních pokusů zatím neexistuje.
  Podrobné ověření v §3.1 rozlišuje požadované pravidlo od hotového runtime.
- Historická věta §7.1 o ručním zadání maxima se čte spolu s novější explicitní
  úpravou §3.1/V22: nevyplněné maximum znamená 0 a nebrání READY.
- Starší realizační odstavce plánu popisují historické hashované CODE; aktuální
  stav je V22. Skutečné heslo/tokeny se naopak nadále bezpečně hashují.

## 2. Doporučená architektura

Malý modul `simulation` (api/application/persistence) a společný čistý
`competition/domain/RaceEvaluator`. Žádný workflow engine, broker nebo plugin systém.

`evaluate(configuration, runState, command, observedAt)` vrací neměnný nový stav,
ověřená fakta a efekty penalizací. Bez JDBC/HTTP/auditu a bez čtení hodin uvnitř.
Čas je explicitní Instant, doby jsou celé ms. GPS geometrie je samostatná čistá
funkce stejného jádra. Persistence jen potvrzuje výsledek v transakci.

Simulační služba poskytne virtuální čas a simulovaná GPS data. Budoucí ostrá
služba poskytne serverový čas, důvěryhodný kontext zařízení a received_at/end_at.
**Obě použijí stejný evaluator**, nikoli dvě verze pravidel. Simulace nevytváří
race_event, crew, registraci ani skutečné výsledky/statistické vzorky.

Změny trasy, restarty skutečných účastí, uzávěrka, pořadí a startovací kódy nejsou
předmětem této simulace neměnné šablony. Šablona nemá termín end_at; simulaci jej
nepřidáváme domněnkou. Ukončení simulace není náhradou testu uzávěrky ostré Akce.

## 3. Vyhodnocovací mechanismus

### Stav a přechody

Průběh RUNNING má právě jedno ACTIVE stanoviště. Ostatní jsou PENDING,
COMPLETED nebo SKIPPED. Start aktivuje první a uloží activatedAt. Poslední
vyžadovaný úspěch dokončí stanoviště ihned, spočítá časové položky právě jednou
a aktivuje další ve stejném přechodu. Po posledním je průběh COMPLETED.

**Schváleno Q2:** úspěch CODE i GPS se uchová do dokončení nebo opuštění
aktuální aktivace. Obě pořadí splní AND; nevzniká časová expirace ani požadavek
na nové GPS po posunu hodin. Již splněná podmínka se znovu nevyhodnocuje, nevznikne
nový úspěch ani bodový efekt; vrací CHECK_ALREADY_SATISFIED. Jde o potvrzení
příkazu, nikoli další CodeAccepted/GpsAccepted fakt. Restart začne s prázdnými
příznaky; restore je obnoví přesně podle vybraného bodu historie. Ani pohyb
simulované polohy po platném GPS sám již splněnou podmínku neruší.

Časová penalizace/DELAYED nejsou samostatným způsobem dokončení. READY dnes
vyžaduje GPS nebo CODE. expectedDurationMs je informace, nikoli závodní limit.

### Výpočty

- CODE: strip + uppercase ROOT, 1–128 Unicode znaků, stejná normalizace jako dnes.
  Chybný validní vstup přidá efektivní sazbu; null override dědí global, 0 přepisuje.
- GPS: doporučuji Haversine s pevnou konstantou Země 6 371 008,8 m a podmínkou
  `distanceMeters <= radiusMeters`. Bez zaokrouhlení a bez skryté tolerance před
  porovnáním; clamp mezivýsledku [0,1], testy pólů a antimeridiánu. Geometrie double,
  bodové výpočty nikdy floating point. Ostrý závod použije přesně tuto funkci.
- accuracy > radius nebo nedostupná poloha: bez bodů, nesplněné GPS, technický
  herní výsledek. Záporná accuracy/nečíselné vstupy jsou validační chyba.
- Výslovně simulovaný mock-location signal: polohu nepoužít, přidat spoof sazbu
  a simulační bezpečnostní fakt. Ručně nastavená GPS sama není spoof pokus.
  Tím se neověřuje skutečná Android/iOS detekce.
- TIME = ceilDiv(durationMs,intervalMs) * pointsPerInterval při dokončení.
- EARLY = ceilDiv(max(0,minimumMs-durationMs),1000) * pointsPerSecond při dokončení.
  DELAYED nezadržuje úspěch a chybné pokusy samy EARLY/TIME za dokončení nevyvolají.
- SKIP: dosavadní účinné body stanoviště nahradí jediná položka
  `(maxPenaltyPoints ?? 0) + skipPenaltyPoints`; historie zůstane s effective=false
  a supersededBy. SKIPPED se nepočítá jako splněné stanoviště.
- Podepsané sazby i součty, žádné minimum nula. ceilDiv pro a>=0,b>0 implementovat
  `a/b + (a%b==0 ? 0 : 1)`. addExact/multiplyExact a kontrola Instant/ms overflow.
  Přetečení odmítne celý příkaz, žádný částečný zápis.

Penalty položka má ID, actionId, stageId, cause, points, effective, supersededBy.
pointsDelta akce = nový účinný součet minus původní účinný součet. Skip může mít
zápornou deltu i při kladné nové náhradní penalizaci. Nejde o počet nových řádků.

### 3.1 Ověření přeskočení: varianta B, zdroj a dopady

**Výsledek ověření: B — nahrazení, nikoli přičítání.** Závazný zdroj je
[Zavody_specifikace_v0.9.0.md, §7.1 „Přeskočená a nedokončená stanoviště“](Zavody_specifikace_v0.9.0.md#71-přeskočená-a-nedokončená-stanoviště),
bod začínající „Za přeskočené stanoviště posádkou“ (při této kontrole řádek 266).
Doslovné rozhodující znění:

> Dosavadní penalizace získané na tomto stanovišti se touto výslednou penalizací nahrazují, nepřičítají se k ní.

Tentýž bod ponechává původní události v historii mimo aktivní součet. Další bod
§7.1 (řádek 269) stejné nahrazení stanoví pro nedokončení při konci závodu;
časová uzávěrka ale není součástí této simulace. §7.2 potvrzuje, že náhradní
penalizace nahrazuje účinné penalizace stanoviště, nikoli historii jejich vzniku.

| Varianta | Stav podle zdroje |
|---|---|
| A: původní body + nová penalizace | Výslovně odporuje citované větě §7.1 |
| B: náhradní penalizace místo dosavadních účinných bodů této stage | Jednoznačně stanovené aktuální specifikací |
| C: jiný režim | Pro přeskočení posádkou není stanoven; globální zrušení organizátorem v §7.3 je odlišná operace |

**Kontrola kódu:** `templates/persistence/TemplateStore` uchovává
skipPenaltyPoints a maxPenaltyPoints; `races/persistence/EventStore.insert` a
copyStage je kopírují do Akce. `templates/application/TemplateReadiness` kontroluje
nepovinné nezáporné maximum. V produkčním kódu není implementovaný SKIP přechod,
RaceEvaluator ani závodní penalty_event. Nelze tedy tvrdit, že varianta B je již
runtime testovaná; zdrojem pravidla je specifikace, nikoli hotová implementace.
Toto zadání pravidlo nově neschvaluje ani nemění — požadované ověření našlo již
existující jednoznačnou definici. Není třeba vytvářet otevřenou otázku o A/B/C.

#### Důsledky pro návrh

- **RaceEvaluator:** skip v jednom přechodu zneúčinní dosavadní položky právě
  aktuálního stanoviště a vytvoří jedinou náhradu
  R = `(maxPenaltyPoints ?? 0) + skipPenaltyPoints`. Záporné maximum je neplatná
  konfigurace, nikoli hodnota, kterou engine potichu opraví na nulu.
  Nedopočítává další TIME/EARLY za přeskočení. Body ostatních stanovišť nemění.
- **Penalizační události:** původní fakta/částky se nemažou ani nepřepisují.
  Skip efekt odkáže na nahrazované položky a novou SKIP_REPLACEMENT. effective a
  supersededBy jsou odvozený stav projekce konkrétní větve; nemění globálně
  původní archivované události, které může použít restore před přeskočením.
- **pointsDelta:** označme P účinné body této stage těsně před skip, O body ostatních
  stage, R náhradu. Before = O + P, after = O + R, delta = R - P. Není to R samotné
  a nikdy O + P + R. Počítat checked signed aritmetikou.
- **Dashboard:** celkové/byStage/byCause počítají jen účinné položky aktuální
  větve; zneúčinněný WRONG_CODE/GPS atd. zůstává v historii, ale ne v součtu.
  byCause obsahuje SKIP_REPLACEMENT v hodnotě R. SKIPPED zvýší skippedStages,
  nikoli completedStages. Poslední akce má skutečnou deltu R - P.
- **Historie/restore:** prefix před skip obnoví tehdejší aktivaci, dílčí úspěchy
  a původní účinné body; prefix po skip obsahuje pouze náhradu v účinném součtu.
  Obnova vytváří novou větev, archiv se nemění. Opakovaný replay/pohyb hodin
  nevloží druhou náhradu a archivované větve se k aktivnímu skóre nepřičtou.
- **API:** stávající stage-skips a StateView se nemění. skipPreview.replacementPoints
  je R, currentEffectivePoints je P a pointsDeltaIfSkipped je R - P. Frontend
  nesmí dosavadní body k replacementPoints znovu přičíst. Celý response State
  z backendu zůstává autoritou pro zobrazení.

Příklad navazující na StateView v §9.2: P = 3, R = 10, O = 0. Preview tedy
správně ukazuje replacementPoints="10", currentEffectivePoints="3",
pointsDeltaIfSkipped="7". Po skip je celkem **10, nikoli 13**. Následující JSON je
pouze ukázka relevantních polí dashboardu/penalty položek, ne nové response DTO;
skutečný endpoint vrátí celý StateView se zvýšenou verzí a dalším stanovištěm:

```json
{
  "totalPenaltyPoints":"10",
  "completedStages":0,
  "skippedStages":1,
  "totalStages":2,
  "currentStageId":"55555555-5555-4555-8555-555555555555",
  "byCause":[{"cause":"SKIP_REPLACEMENT","points":"10"}],
  "byStage":[
    {"stageId":"44444444-4444-4444-8444-444444444444","sequenceNo":1,"status":"SKIPPED","points":"10"},
    {"stageId":"55555555-5555-4555-8555-555555555555","sequenceNo":2,"status":"ACTIVE","points":"0"}
  ],
  "lastAction":{"actionId":"88888888-8888-4888-8888-888888888888","sequence":3,"type":"SKIP","stageId":"44444444-4444-4444-8444-444444444444","outcome":"STAGE_SKIPPED","pointsDelta":"7","virtualAt":"2026-10-29T10:01:00Z","gps":null},
  "recentPenalties":[
    {"id":"77777777-7777-4777-8777-777777777777","actionId":"66666666-6666-4666-8666-666666666666","stageId":"44444444-4444-4444-8444-444444444444","cause":"WRONG_CODE","points":"3","effective":false,"supersededBy":"99999999-9999-4999-8999-999999999999","virtualAt":"2026-10-29T10:01:00Z"},
    {"id":"99999999-9999-4999-8999-999999999999","actionId":"88888888-8888-4888-8888-888888888888","stageId":"44444444-4444-4444-8444-444444444444","cause":"SKIP_REPLACEMENT","points":"10","effective":true,"supersededBy":null,"virtualAt":"2026-10-29T10:01:00Z"}
  ],
  "hasMorePenaltyHistory":false
}
```

Jde o dashboard uvnitř stávajícího StateView, žádné nové pole ani endpoint.
Při O = 5 se celkem změní 8 -> 15, delta je stále 7. Při P = -4, R = 10 je delta
14; při P = 3, R = 0 je delta -3. Tím je ošetřena nulová i záporná sazba bez
neoprávněného minima 0 na celkových bodech.

#### Povinné testy tohoto pravidla

P = 3/R = 10 -> celkem 10/delta7, nikoli 13; zachovat O; kombinovat WRONG_CODE,
GPS_OUTSIDE a GPS_SPOOFING před skip; null maximum0, explicitní0, záporný skip
koeficient i dosavadní záporné body. Ověřit byCause/byStage/totals, supersededBy
a historické položky. Dva souběžné skip/replay stejného klíče nesmějí vytvořit
dvě účinné náhrady. Restore před/po skip a opakovaný replay musí obnovit přesný
prefix bez dvojí aplikace; samotný clock shift nemění ani účinnost bodů.

### Kvízy

**Schváleno Q1: kvízy jsou odložené a nejsou součástí první implementační etapy.**
Žádná provizorní pravidla správnosti/odpovědí ani body zaslané frontendem.
Rezervovaný endpoint v API tabulce není funkční kvíz; pokud bude vůbec routovaný,
vrátí pouze 501 SIMULATION_QUIZ_UNSUPPORTED bez změny stavu. Frontend první etapy
jej nepoužívá ani nezobrazuje ovládání kvízů.

Čisté příkazy, fakta a reducer společného enginu později dovolí přidat QuizAnswer
bez změny hranic HTTP/persistence/evaluatoru. Až budou samostatně rozhodnuta
pravidla, doplní se konkrétní doménové příkazy, konfigurace šablon, readiness,
verze snapshotu a answer DTO. Nyní nepřidávat obecný plugin systém, prázdné quiz
tabulky ani předběžné pole answer. Odložená funkce neblokuje první etapu.

## 4. Datový model a transakce

Doporučení: PostgreSQL + privátní fyzické kopie souborů. Paměť je krátce jednodušší,
ale neudrží stav/idempotenci při restartu a komplikuje více instancí. Žádná Redis.
Navržená nová migrace je při současném schématu V23; číslo znovu ověřit při realizaci.

| Tabulka | Pole / invarianty |
|---|---|
| race_simulation | UUID PK, owner_account_id FK, source_template_id/version/name jako původ; immutable snapshot JSONB včetně CODE, snapshot_format_version, evaluator_version; current_run_id, version>=0, status, virtual_start_at, virtual_now, projection JSONB; reálné created_at,last_activity_at,expires_at |
| simulation_run | UUID PK, simulation_id FK CASCADE, UNIQUE(simulation_id,number), started_virtual_at, parent_run_id, restored_through_sequence, initial_state JSONB; neměnná větev historie |
| simulation_command | UUID actionId PK, simulation_id/run_id FK, sequence, UNIQUE(simulation_id,sequence), actor/key UNIQUE(simulation_id,actor_id,command_key), operation, expected_version, request_fingerprint, verified_facts/effects/response JSONB, observed_virtual_at, reálné recorded_at,replay_expires_at |
| simulation_file | UUID PK, simulation_id FK CASCADE, file_name,name_key UNIQUE(simulation_id,name_key), MIME/byte_size/width/height, markdown_source nullable, bezpečný storage identifikátor |

Kompozitní FK musí zajistit, že current_run a command.run patří téže simulaci.
Odložená FK případně umožní atomické vytvoření rodiče i prvního runu. Source ID
je historický původ, ne živá konfigurace a ne důvod blokovat smazání šablony.
JSONB projekce obsahuje stav stanovišť a body; je deterministicky ověřitelná
z deníku, není druhým nezávislým zdrojem skóre. Snapshot/engine verze chrání
před tichým přepočtem po nekompatibilním upgradu. Nepodporovanou verzi odmítnout,
nebudovat registry hypotetických starých enginů.

Vytvoření využije stávající CommandStore s explicitním SIMULATION_CREATE formátem
(jen ID/verze zdroje, čas a bezpečný výsledek), včetně CommandAccounts/úklidu.
Chybějící virtualStartAt se v create fingerprintu eviduje jako absence/default,
ne jako nově vypočtený now při každém retry; skutečný default se určí až po replay
kontrole. Stavové příkazy se evidují v již potřebném simulation_command, ne v druhém deníku
každého pokusu. UUID Idempotency-Key, 24h replay: shodné parametry původní odpověď,
odlišné 409 IDEMPOTENCY_CONFLICT. Fingerprint SHA-256 kanonického requestu zahrne
typ, zdroj, runId, stageId, očekávanou verzi a normalizované vstupy. Do deníku
ukládat přesný potvrzený CODE v `verified_facts.input.code`, i nesprávný (nové
schválené diagnostické pravidlo). Správný CODE se nekopíruje ze snapshotu do
historie; je zde pouze tehdy, když ho uživatel skutečně zadal. Vedle vstupu
zůstává ověřený výsledek a otisk pro porovnání. Otisk není v odpovědi/auditu. Není potřeba nový kryptografický framework.

Po 24h lze zahodit replay response/fingerprint, ale nikoli historii potřebnou
pro živou simulaci. Key tombstone ponechat do smazání simulace; po expiraci
409 IDEMPOTENCY_KEY_EXPIRED, nikoli nový bodovaný pokus stejným klíčem. Toto je
navržené simulační pravidlo, které výslovně rozšiřuje běžnou 24h evidenci.

Zámky: případný source storage -> simulation storage -> admin_control -> actor
account -> template při create / simulation FOR UPDATE při pokusu. Uvnitř:
autorizace a vlastník -> replay -> version -> run -> stav -> stage -> čas -> obsah
-> evaluator -> command/projekce/version/audit. Jeden commit, nebo úplný rollback.
Stejný klíč s odlišnými parametry musí být konflikt před kontrolou verze.

Každý přijatý nový herní pokus (i chybný CODE) zvyšuje version a sequence. No-op
hodin/polohy verzi nezvýší, má však replay potvrzení bez obchodních efektů. HTTP
validační chyby nic nebodují. Opožděný request musí nést runId a stageId, nikdy
se nesmí aplikovat na následující stanoviště. Replay staré větve vrací původní
odpověď bez mutace; frontend ignoruje nižší version a případně načte GET.
Version simulace se při restartu neresetuje.

## 5. Životní cyklus a úklid

Create vytvoří snapshot a první RUNNING run. Poslední stanoviště -> COMPLETED.
Close -> CLOSED bez nové penalizace (není skutečný end_at). Restart i z CLOSED
vytvoří nový run nad týmž snapshotem, ne novější šablonou. Historie runů zůstává.
Novější šablona vyžaduje novou simulaci. DELETE odstraní vlastní simulaci.

Simulace se uchovávají bez časového omezení. Logout ani opuštění stránky stav nemaže.
Poslední aktivitu mění skutečná stavová změna, nikoli GET či replay. Údržba uklízí
pouze replay odpovědi a nereferencované soubory; nemaže simulace.
Orphan úklid po 24h znovu ověří DB reference a storage lock, stejně jako současné
souborové operace. Neúspěšný create odstraní jen své nereferencované nové bajty.

Zrušení/anonymizace vlastníka musí ve své transakci odstranit simulace a jejich
replay vazby; fyzické soubory po commitu. FK CASCADE nestačí pro anonymizaci.
Simulace není historická účast a nesmí změnit cancellation pravidla. Příkaz i
cancellation sdílejí account zámek. Odebrání ADMIN okamžitě zamezí dalšímu přístupu.
Audit jen SIMULATION_CREATED/RESTARTED/RESTORED/CLOSED/DELETED/EXPIRED s ID, nikdy
obsah/CODE; herní penalizace nejsou skutečné bezpečnostní události posádky.

## 6. Virtuální čas: změna hodin není undo (schváleno Q3)

Schválená architektura používá ručně řízené hodiny: při čekání na HTTP samy neběží. Serverový Clock,
expirace loginu a retence se nikdy nenastavují podle simulace.

### A. Posun hodin dopředu/dozadu

`deltaMs` mění pouze virtualNow; nemaže pokusy, dokončení ani body. Minimum je
virtualStartAt aktuálního runu. sequence je autoritativní pořadí událostí;
neřadit je podle virtuálních timestampů, které při řídicích změnách mohou klesat.
recordedAt zůstává skutečný serverový čas.

Pro herní akce udržovat timeFloor = poslední virtuální čas potvrzené obchodní
události aktuální větve (aktivace/pokus/dokončení). Při virtualNow < timeFloor
CODE/GPS/complete/skip vrací 409 SIMULATION_CLOCK_BEFORE_STATE. Měnit hodiny,
vstupní polohu a provést restore/restart lze. Nedojde k pokusu před aktivací ani
před předchozí potvrzenou akcí. Samotné posuny hodin timeFloor nezvyšují.

Příklad: +120s a -60s před prvním pokusem umožní pokus po 60s. Pokud už nastal
chybný pokus v +120s, návrat na +60s jej neodčiní a další herní akci zablokuje do
návratu na >=120s nebo explicitního restore. Ani completed stanoviště se posunem
hodin neotevře. elapsedSimulationMs = virtualNow - virtualStartAt, může klesnout,
ale ne pod 0. activeStageElapsedMs je null, je-li cursor před aktivací; UI musí
ukázat clockBeforeState/businessActionsAllowed, ne zápornou či maskovanou dobu.

### B. Restore do prefixu historie

`restore {throughSequence}` obnoví stav včetně tehdejší polohy/hodin/aktivace/bodů
z prefixu **aktuální větve**, vytvoří nový runId a zachová parentRunId a bod
větvení. Stará větev se nemaže. Sequence 0 znamená stav ihned po startu větve.
Výběr libovolné jiné archivované větve není nyní požadavek.

Replay: snapshot + uložená ověřená fakta -> stejný reducer -> projekce. Přesný text
CODE se ukládá pro diagnostiku, ale replay jej znovu nevyhodnocuje: fakta
CodeAccepted/Rejected nadále obsahují původní ověřený výsledek. GPS
fakt obsahuje přijaté vstupy a výsledek. Penalty ID odvodit deterministicky z
původního command ID a typu položky. Replay nevkládá podruhé historické efekty,
jen počítá projekci; nová budoucí akce má nové ID. Aktivní větev nepočítá body
odříznutého suffixu ani archivu. Verze evaluatoru a kontrolní digest projekce
odhalí nepodporovaný/poškozený replay.

| Průchod | Výsledek |
|---|---|
| Aktivace t0, +60001ms, úspěch | TIME při intervalu 60000 = dva intervaly |
| Potom -1000ms | Dokončení a TIME zůstanou; neposuzuje se znovu |
| Restore před úspěch | Nová větev s ACTIVE stavem a prefixem bodů |
| Nový úspěch | Jediný nový výsledek aktuální větve |
| Restart | První stanoviště, prázdné body/GPS; stejné virtualStartAt jako původní create |

Q3 tuto architekturu schválila: změna hodin sama nedělá undo, herní akce
před timeFloor jsou blokované, ale samotný posun zpět je povolený v uvedeném
rozsahu. Obnovení do prefixu vytvoří novou větev s tehdejšími GPS/časem,
dokončeními a penalizacemi. Žádná záporná doba plnění ani dvojí aplikace historie.

**Frontendové ovládání:** nabídnout pohodlný posun dopředu/zpět v sekundách,
minutách a hodinách (předvolby i vlastní hodnota). Převod na deltaMs provádět
přesně: 1 s = 1000 ms, 1 min = 60000 ms, 1 h = 3600000 ms, s kontrolou rozsahu.
UI oddělí tlačítko Posunout hodiny od Obnovit stav; při clockBeforeState ukáže
blokaci herních akcí, nikoli blokaci samotného posunu hodin.

## 7. Snapshot a privátní soubory

Využít konfiguraci, readiness a bezpečné souborové operace. Z EventService.create
lze vyčlenit malou snapshot assembly funkci pracující se zamčeným zdrojem;
nepřepisovat celé races a nevytvářet race_event kvůli simulaci.

Create zamkne zdroj a ověří templateVersion i úplnost všech nastavení/referencí.
**Schváleno Q4:** zdrojem může být DRAFT i READY, pokud obsahuje všechny údaje
nezbytné pro simulaci. Vytvoření nemění status ani verzi zdrojové šablony; pracuje
s nezávislým snapshotem. Read-only validace použije stejné kontroly úplnosti
konfigurace a odkazů; nevyžaduje samotný status READY.
Neúplný zdroj vrátí srozumitelný seznam chyb (errors). V první etapě není simulace
jediného vybraného stanoviště ani neúplné trasy. READY-only pravidlo skutečných
Akcí se nemění a na simulátor se nepřenáší.

Snapshot: původní ID/verze/název, nová snapshotStageId, pořadí, CODE, GPS,
raw global/override a účinné sazby, max, očekávané doby, časová pravidla, Markdown.
Kvízy jsou v první etapě podle Q1 vyloučené. Celá knihovna včetně nepoužitých souborů, nezávislé fyzické kopie,
MD zdroje a cyklické odkazy bez rekurzivního vložení. Pozdější změna/smazání
zdroje nesmí obsah simulace změnit.

Adresář např. `${user.home}/.rallye/simulation-files`, namespace race-simulations.
Dnešní TemplateImageFiles přijímá jen race-templates/race-events: rozšířit explicitně,
ne obcházet allowlist. InstructionsMarkdown/ImageNames také znají dva namespace;
přidat řízený simulační resolver, ne libovolné klientské prefixy.

Přepsat pouze explicitní vlastní template odkazy, včetně reference definitions
v přiložených MD, do `/api/v1/admin/race-simulations/{id}/files/{fileName}`.
Relativní odkazy zachovat. Stávající validace MIME, skutečného obsahu, velikosti,
rozměrů, názvů, symlinků a traversal zůstávají. Čtení vždy ověřuje ADMIN/vlastníka,
ne čtení živé šablony ani tokeny v URL. PDF attachment; MD sanitizovaná HTML
stránka; no-store, nosniff, no-referrer, stejné restriktivní CSP. V simulovaném
účastnickém DTO žádný správný CODE. ADMIN ho již může číst ve správě šablony,
ale tím se nesmí obejít oddělení herního a konfiguračního DTO.

## 8. Simulovaná GPS a parser

Jeden autoritativní parser na backendu; frontend poskytuje paste, formátování,
výběr režimu a zobrazení chyb. Nepočítá body a neposílá uvnitř=true. Parser nemění
simulaci; výsledek lze použít v samostatném nastavení polohy. GPS pokus ve své
transakci vezme aktuálně uloženou polohu a uloží její kopii do ověřených faktů.

### Gramatika

- Unicode whitespace a symboly °/′/'/″/dvojité uvozovky; deterministická gramatika,
  regex bez neomezeného backtrackingu. Doporučený limit vstupu 2048 UTF-8 bajtů.
- Bez hemisfér je pořadí latitude,longitude, nikdy odhadované přehození dle rozsahu.
  S hemisférami N/S určují latitude, E/W longitude bez ohledu na jejich pořadí.
- DD: dvě čísla, desetinná tečka. Desetinná čárka jen při jasném oddělení dvojice
  středníkem nebo hemisférami; čtyři čísla oddělená čárkami neodhadovat.
- DMM: celé stupně, desetinné minuty [0,60); volitelný ° i apostrof, různé mezery,
  hemisféra před/za. **Apostrof za minutami není povinný.**
- DMS: celé stupně/minuty [0,60), sekundy [0,60). Rozlišit třetí složku od DMM.
- Latitude [-90,90], longitude [-180,180]; na hraničních stupních minuty/sekundy 0.
  Vše finite. S/W negativní; záporná hodnota se S/W je souhlasná, negativní N/E
  konflikt. Explicitní + se S/W konflikt; neznačená kladná velikost se S/W je platná.
- Nejednoznačný vstup odmítnout s code + srozumitelným obecným message bez echo URL.

### URL bez sítě

Explicitně podporované navržené syntaktické tvary (příklady jsou lokální parser
fixtures, nikoli příslib všech URL dané služby):

| Tvar | Interpretace |
|---|---|
| https://www.google.com/maps?q=50.087451,14.420671 | numerické q jako lat,lon |
| https://www.google.com/maps/@50.087451,14.420671,15z | @lat,lon |
| https://maps.apple.com/?ll=50.087451,14.420671 | ll jako lat,lon |
| https://www.openstreetmap.org/#map=15/50.087451/14.420671 | zoom/lat/lon |
| https://mapy.cz/zakladni?x=14.420671&y=50.087451 | x=longitude,y=latitude |
| https://mapy.com/cs/zakladni?x=14.420671&y=50.087451 | totéž pro explicitní host |

Porovnávat přesný host/path, ne suffix google.com; dekódovat jen jednou.
Nepodporované hosty, textové q, více konkurenčních párů/tras, URL credentials,
zkrácené/redirectové URL odmítnout. Žádný fetch, DNS ani HTTP – parser není SSRF.

### Povinné příklady testů

| Vstup | Očekávání |
|---|---|
| `50.087451, 14.420671` | 50.087451,14.420671 |
| `50.087451 N, 14.420671 E` | stejné DD |
| `N 50° 05.247' E 014° 25.240'` | 50.08745,14.4206666667 |
| `N 50° 05.247 E 014° 25.240` | stejný DMM bez apostrofu |
| `N50 05.247 E014 25.240` | stejný, bez ° i apostrofu |
| `N 50 05.247, E 014 25.240` | stejný |
| `50° 05' 14.8" N, 14° 25' 14.4" E` | 50.0874444444,14.4206666667 |
| `S 50 05.247 W 014 25.240` | obě negativní |
| `-50.087451; -14.420671` | záporné DD |
| `50,087451; 14,420671` | jednoznačná desetinná čárka |
| `N 50 05,247 E 014 25,240` | čárka v DMM |
| `50,087451,14,420671` | AMBIGUOUS_COORDINATES |
| `N -50 E 14`, `N 91 E 14`, `N 90 00.001 E 14`, minuty/sekundy 60 | chyba |
| Všechny tabulkové URL; URL s více cíli/zkracovač | správný pár, nebo výslovné odmítnutí bez sítě |

Poloha TARGET se nastaví serverem přímo z aktuálního snapshotu. OUTSIDE zvolí bod
ve vzdálenosti radius+max(10m,10% radius), ověří jej stejnou geometrií; u pólů
vyzkouší jiný směr. Pokud radius pokrývá celý svět, odpověď je
GPS_OUTSIDE_POINT_UNAVAILABLE, nikoli falešný bod mimo perimetr. accuracy=0,
mockLocation=false u serverových presetů; možnost zvlášť simulovat mock signál
je vstup pro test pravidla, nikoli tvrzení o fyzickém zařízení.

## 9. Přesné navržené API

**Endpointy první etapy níže jsou implementované; PostgreSQL integrace zatím
nebyly Codexem spuštěny.** Q1–Q4 jsou rozhodnuté. Kontrakty
první etapy bez kvízů jsou připravené jako společný podklad pro paralelní
implementaci backendu a frontendu; nevyžadují další produktové rozhodnutí.
Označená quiz cesta je pouze nepodporovaná rezervace mimo tento závazek.
Zjištění varianty B přeskočení ve specifikaci nemění dosavadní DTO ani endpointy. Base:
`/api/v1/admin/race-simulations`.

### 9.1 Společné DTO/pravidla

Aktivní ověřený ADMIN + platná existující relace; jen vlastník. Cizí ID je 404
SIMULATION_NOT_FOUND. Cookie mutace i POST parser vyžadují stávající CSRF, Bearer
beze změny. Strict JSON, neznámá pole a necelé version/ms jsou 400. UUID string,
version nezáporný int64. Všechny computed points/pointsDelta v tomto **novém API**
navrhuji jako desetinné stringy signed int64 pro lossless zobrazení v JS. Počty,
sequence, version a ms jsou JSON integers; velké hodnoty číst lossless parserem.
Instant ISO8601 UTC s ms významem. Žádné code echo, points/owner od klienta.

Každá mutace kromě DELETE má UUID Idempotency-Key a body `{version,runId,...}`;
herní akce také snapshot stageId. Create má templateId/templateVersion místo
budoucí version/runId. Mutace vrátí celý StateView s dashboardem/currentStage,
aby UI nepotřebovalo další request. Výjimky: DELETE 204, parser vlastní DTO,
quiz zatím 501. no-store na stavech/pokusech. Chybný platný CODE je 200 herní
výsledek, ne HTTP chyba. Replay vrací původní State, klient hlídá jeho version.

### 9.2 StateView – úplný příklad

Po chybném CODE po 60s (sequence 1 byl posun hodin):

```json
{
  "id":"11111111-1111-4111-8111-111111111111",
  "runId":"22222222-2222-4222-8222-222222222222",
  "version":2,
  "status":"RUNNING",
  "source":{"templateId":"33333333-3333-4333-8333-333333333333","templateVersion":8,"templateName":"Podzim"},
  "clock":{"virtualStartAt":"2026-10-29T10:00:00Z","virtualNow":"2026-10-29T10:01:00Z","elapsedSimulationMs":60000,"activeStageElapsedMs":60000,"timeFloor":"2026-10-29T10:01:00Z","clockBeforeState":false,"businessActionsAllowed":true},
  "position":null,
  "gpsPreview":null,
  "currentStage":{
    "id":"44444444-4444-4444-8444-444444444444","sequenceNo":1,"name":"U mostu",
    "instructionsMarkdown":"# Najděte kód",
    "instructionsRenderedHtml":"<h1 id=\"najděte-kód\">Najděte kód</h1>",
    "activatedAt":"2026-10-29T10:00:00Z","expectedDurationMs":60000,
    "requiredChecks":["CODE"],"satisfiedChecks":[],
    "timePenalty":{"enabled":false,"intervalSeconds":null,"pointsPerInterval":null},
    "delayed":{"enabled":false,"minimumDurationMs":null,"pointsPerSecond":null},
    "skipPreview":{"replacementPoints":"10","currentEffectivePoints":"3","pointsDeltaIfSkipped":"7"}
  },
  "dashboard":{
    "totalPenaltyPoints":"3","completedStages":0,"skippedStages":0,"totalStages":2,
    "currentStageId":"44444444-4444-4444-8444-444444444444",
    "byCause":[{"cause":"WRONG_CODE","points":"3"}],
    "byStage":[{"stageId":"44444444-4444-4444-8444-444444444444","sequenceNo":1,"status":"ACTIVE","points":"3"},{"stageId":"55555555-5555-4555-8555-555555555555","sequenceNo":2,"status":"PENDING","points":"0"}],
    "lastAction":{"actionId":"66666666-6666-4666-8666-666666666666","sequence":2,"type":"CODE","stageId":"44444444-4444-4444-8444-444444444444","outcome":"CODE_REJECTED","pointsDelta":"3","virtualAt":"2026-10-29T10:01:00Z","gps":null},
    "recentPenalties":[{"id":"77777777-7777-4777-8777-777777777777","actionId":"66666666-6666-4666-8666-666666666666","stageId":"44444444-4444-4444-8444-444444444444","cause":"WRONG_CODE","points":"3","effective":true,"supersededBy":null,"virtualAt":"2026-10-29T10:01:00Z"}],
    "hasMorePenaltyHistory":false
  },
  "expiresAt":null
}
```

currentStage je ParticipantStage, null v COMPLETED/CLOSED; activeStageElapsedMs
tehdy null. Dashboard čerpá clock/status/position ze stejného StateView, ne jiné
kopie hodin. completedStages nezahrnuje SKIPPED. lastAction null před první akcí.
recentPenalties nejvýše posledních 50 položek aktuální větve včetně superseded;
totals a byCause/byStage vždy z celé účinné větve. Historie se stránkuje zvlášť.

ParticipantStage neobsahuje skutečný CODE, plné StageConfiguration ani cílové GPS
souřadnice. serverový TARGET preset je může využít interně. Position je null nebo
`{latitude,longitude,accuracyMeters,mockLocation}`. StateView navíc obsahuje
gpsPreview: null bez polohy/aktivního GPS, jinak
`{distanceMeters,withinPerimeter,accuracyUsable}` vypočtené backendem pro aktuální
polohu a stanoviště. Aktualizuje se po position/změně aktuálního stanoviště, ale
**neznamená provedení GPS kontroly**, nesplní check a nepřidá body. Tak lze zobrazit
vzdálenost i před stisknutím Ověřit polohu bez kopie geometrie ve frontendu.
lastAction.gps je u GPS pokusu
`{latitude,longitude,accuracyMeters,distanceMeters,outcome}`, hodnoty null při
nedostupnosti. Parametry sazeb timePenalty/delayed mají points jako string nebo
null, ostatní enabled/intervalSeconds/minimumDurationMs odpovídají konfiguraci.

### 9.3 Endpointy

Cesty jsou vůči base. `State` = výše definovaný StateView, nikoli administrační DTO
šablony/Akce. Povinná pole jsou všechna uvedená, není-li výslovně napsáno jinak.
Společné ADMIN/vlastník/CSRF/replay/version pravidlo §9.1 platí pro každou cestu.

| Metoda a URL | Request DTO | Response DTO / status |
|---|---|---|
| POST base | `{templateId,templateVersion,virtualStartAt?}` | 201 State, Location base/{id} |
| GET /{id} | bez těla | 200 State |
| GET /{id}/current-stage | bez těla | 200 `{simulationId,runId,version,stage:ParticipantStage|null}` |
| POST /{id}/code-attempts | `{version,runId,stageId,code}` | 200 State, CODE_ACCEPTED/REJECTED |
| POST /{id}/gps-attempts | `{version,runId,stageId}` | 200 State, vstup z uložené position |
| POST /{id}/quiz-answers | `{version,runId,stageId}` | 501 Problem, rezervace, mimo první etapu dle Q1; žádné funkční answer DTO |
| POST /{id}/stage-completions | `{version,runId,stageId}` | 200 State při no-op potvrzení již COMPLETED stage; jinak 409 STAGE_REQUIREMENTS_NOT_MET/STAGE_NOT_CURRENT |
| POST /{id}/stage-skips | `{version,runId,stageId}` | 200 State, STAGE_SKIPPED |
| POST /{id}/clock-shifts | `{version,runId,deltaMs}` | 200 State; signed deltaMs |
| PUT /{id}/position | discriminated DTO dle mode níže | 200 State, POSITION_SET |
| POST /{id}/restarts | `{version,runId}` | 200 State, nový runId, stejný snapshot |
| POST /{id}/restorations | `{version,runId,throughSequence}` | 200 State, nová větev |
| GET /{id}/dashboard | bez těla | 200 `{simulationId,runId,version,status,clock,position,gpsPreview,dashboard}` |
| POST /{id}/closures | `{version,runId}` | 200 State, CLOSED |
| DELETE /{id}?version=V | bez těla, bez Idempotency-Key | 204; opakování po smazání 404 |
| GET /{id}/files | bez těla | 200 FileView[] |
| GET /{id}/files/{fileName} | bez těla | 200 bytes / sanitizovaná HTML stránka |
| GET /{id}/history?runId=UUID&afterSequence=0&size=50 | bez těla | 200 HistoryPage |
| POST /coordinates/parse | `{text}` | 200 ParsedCoordinates / 400 Problem; bez verze a Idempotency-Key |

Create virtualStartAt není-li uveden = jednorázově skutečný okamžik vytvoření;
potom jsou hodiny nezávislé. Vlastník vždy z relace, žádný owner request parametr.
Tělo null ani explicitní null start nejsou aliasem nepřítomného povinného pole;
volitelné virtualStartAt smí být vynecháno, nikoli jiné než validní Instant.

GPS outcomes: GPS_ACCEPTED, GPS_OUTSIDE, GPS_INACCURATE, GPS_UNAVAILABLE,
GPS_MOCK_REJECTED. Opakování již splněné podmínky vrací CHECK_ALREADY_SATISFIED
bez nového vyhodnocení/úspěchu či bodů, podle schválené Q2.
Code format chyba = 400 INVALID_CODE_INPUT, věcně nesprávný validní kód = 200.
Backend dokončuje automaticky při poslední kontrole, i při DELAYED před minimem.

`stage-completions` nesmí vytvořit druhou cestu „dokončit bez GPS/CODE“.
Je to pouze bezpečné potvrzení již dokončené stage stejného runu: 200 beze změny
verze/skóre a bez nového auditu. V jiném případě vyžaduje doménové podmínky;
aktivní stage s nesplněnými checks vrátí 409. UI jej v první verzi nepotřebuje
volat, protože úspěšné pokusy již dokončí a postoupí. Pozdní potvrzení nikdy
neovlivní nové aktuální stanoviště. K jiným minulým/PENDING stage je 409.

Potvrzovací no-op complete nemění dashboard.lastAction (zůstává poslední
skutečně stavová akce), jen vrátí stejný State a uloží replay potvrzení.
CHECK_ALREADY_SATISFIED se eviduje jako potvrzený příkaz s nulovým bodovým
efektem; nesmí znovu volat ověření již splněné podmínky ani vytvořit nový
CodeAccepted/GpsAccepted fakt. Dosavadní splnění zůstává, nejde o další úspěch
ani opětovné dokončení. Síťový replay stejného klíče neprovede ani tento příkaz znovu.

Clock a position lze měnit i po COMPLETED pro diagnostiku, nemění potvrzené body.
Po CLOSED pouze čtení/restart/restore/delete, close se správnou verzí je no-op.
CODE/GPS/skip jen RUNNING. Restore je prefix aktuální větve, musí odkazovat na
platný bod této větve, ne budoucí sequence nebo cizí run. Sequence 0 je její
initial_state; zděděný prefix se obnovuje z parent run + bodu větvení, nikoli
kopírováním bodovaných pokusů do nového příkazového logu.

FileView = `{fileName,contentType,byteSize,width,height,url}`; width/height nullable.
Všechny soubory i history vyžadují aktuálního ADMIN/vlastníka, ne veřejnou URL.
History size 1–100, afterSequence>=0, vzestupná sequence v určené větvi. Nevrací
interní snapshot ani fingerprint. Přesný zadaný CODE vrací v diagnostickém
poli `input.code`; nejde o autentizační tajemství (viz §15).

### 9.4 Konkrétní request/response příklady

Create s UUID Idempotency-Key:

```json
{"templateId":"33333333-3333-4333-8333-333333333333","templateVersion":8,"virtualStartAt":"2026-10-29T10:00:00Z"}
```

CODE request (response je celý State z §9.2):

```json
{"version":1,"runId":"22222222-2222-4222-8222-222222222222","stageId":"44444444-4444-4444-8444-444444444444","code":"nespravny-vstup"}
```

GPS check / skip / complete používají stejné version,runId,stageId bez code.
Pro každý nový pokus nový Idempotency-Key, pro síťové opakování původní klíč.

Clock zpět:

```json
{"version":2,"runId":"22222222-2222-4222-8222-222222222222","deltaMs":-30000}
```

INPUT poloha, všechny souřadnice/accuracy/mockLocation povinné:

```json
{"version":3,"runId":"22222222-2222-4222-8222-222222222222","mode":"INPUT","latitude":50.087451,"longitude":14.420671,"accuracyMeters":5.0,"mockLocation":false}
```

Další přesné varianty position requestu:

```json
{"version":4,"runId":"22222222-2222-4222-8222-222222222222","mode":"TARGET","stageId":"44444444-4444-4444-8444-444444444444"}
```

OUTSIDE má stejná pole jako TARGET, pouze mode=OUTSIDE. stageId musí být aktuální.
UNAVAILABLE má pouze version,runId,mode; nastaví position=null. Směšované/další
atributy jsou 400. Nastavení polohy samo neprovádí GPS kontrolu.

Restore:

```json
{"version":7,"runId":"22222222-2222-4222-8222-222222222222","throughSequence":2}
```

Restart / close:

```json
{"version":8,"runId":"22222222-2222-4222-8222-222222222222"}
```

Parser request a response:

```json
{"text":"N50 05.247 E014 25.240"}
```

```json
{"latitude":50.08745,"longitude":14.420666666666667,"format":"DMM","canonical":"50.087450, 14.420667"}
```

format je DD/DMM/DMS/MAP_URL. canonical je pouze formát pro zobrazení, výpočet
používá nezaokrouhlené numerické hodnoty. Chybová odpověď parseru může doplnit
obecný message, např. „Použijte středník mezi souřadnicemi s desetinnou čárkou.“

HistoryPage:

```json
{"simulationId":"11111111-1111-4111-8111-111111111111","runId":"22222222-2222-4222-8222-222222222222","items":[{"actionId":"66666666-6666-4666-8666-666666666666","sequence":2,"type":"CODE","stageId":"44444444-4444-4444-8444-444444444444","virtualAt":"2026-10-29T10:01:00Z","outcome":"CODE_REJECTED","pointsDelta":"3","penalties":[{"id":"77777777-7777-4777-8777-777777777777","stageId":"44444444-4444-4444-8444-444444444444","cause":"WRONG_CODE","points":"3","effectiveAtThisAction":true}]}],"hasMore":false,"nextAfterSequence":2}
```

Historical effectiveAtThisAction vyjadřuje původní stav prefixu; dashboard penalty
effective/supersededBy aktuální stav. Prázdná stránka vrací stejný nextAfterSequence
jako vstup. cause: WRONG_CODE, GPS_OUTSIDE, GPS_SPOOFING, TIME, EARLY,
SKIP_REPLACEMENT (QUIZ není v první etapě dle Q1). lastAction.type: CODE,GPS,SKIP,COMPLETE,
CLOCK_SHIFT,POSITION,RESTART,RESTORE,CLOSE; řídicí outcomes CLOCK_SHIFTED,
POSITION_SET,RESTARTED,RESTORED,CLOSED. Dokončení/race finish vyjadřuje výsledný
stav rodiče/stage, CODE_ACCEPTED může dokončit celý závod.

### 9.5 Chyby a konflikty

Stávající ProblemDetail: status,title,code,instance; žádný stack trace/echo CODE.
Readiness problém navíc errors:[{stageId,field,code,message}], nikdy správný kód.

| HTTP | Kódy / použití |
|---|---|
| 400 | INVALID_REQUEST, INVALID_CODE_INPUT, INVALID_COORDINATES, AMBIGUOUS_COORDINATES, COORDINATE_SIGN_CONFLICT, MAP_URL_UNSUPPORTED |
| 401 | UNAUTHENTICATED |
| 403 | ACCESS_DENIED (security/CSRF), ADMIN_REQUIRED (service) |
| 404 | TEMPLATE_NOT_FOUND, SIMULATION_NOT_FOUND, SIMULATION_FILE_NOT_FOUND |
| 409 | TEMPLATE_VERSION_CONFLICT, SIMULATION_TEMPLATE_NOT_READY, SIMULATION_VERSION_CONFLICT, SIMULATION_RUN_CONFLICT, STAGE_NOT_CURRENT, STAGE_REQUIREMENTS_NOT_MET, CHECK_NOT_ENABLED, IDEMPOTENCY_CONFLICT, IDEMPOTENCY_KEY_EXPIRED, SIMULATION_CLOCK_BEFORE_STATE, SIMULATION_NOT_RUNNING, SIMULATION_RESTORE_INVALID, GPS_OUTSIDE_POINT_UNAVAILABLE |
| 422 | SIMULATION_TIME_OUT_OF_RANGE, SIMULATION_POINTS_OVERFLOW; celý příkaz beze změny |
| 501 | SIMULATION_QUIZ_UNSUPPORTED |
| 503 | SIMULATION_SNAPSHOT_UNAVAILABLE, SIMULATION_ENGINE_VERSION_UNSUPPORTED, stávající PHOTO_STORAGE_UNAVAILABLE |
| 500 | INTERNAL_ERROR, rollback a bezpečná serverová diagnostika |

Např. 409 odpověď:

```json
{"type":"about:blank","status":409,"title":"Conflict","code":"SIMULATION_VERSION_CONFLICT","instance":"/api/v1/admin/race-simulations/11111111-1111-4111-8111-111111111111/code-attempts"}
```

Frontend při konfliktu načte GET, nenahrazuje automaticky klíč novým, aby omylem
neopakoval bodovaný pokus. Celý State po mutaci dovolí jeden request, nikoli
oddělený GET výsledku a dashboardu s různými verzemi.

## 10. Bezpečnost a návaznosti

- Nové /admin cesty využijí filtr, ale service musí znovu ověřit roli a vlastníka
  pod account zámkem. Odebrání role i cancellation platí ve stávající relaci.
- Interní snapshot s CODE se nesmí serializovat do herního API, logu/auditu či
  idempotentního výsledku. Výsledek pokusu obsahuje jen rozhodnutí a body.
- Žádná vazba na skutečné race_event/crew výsledky, žádné statistické vzorky
  simulovaných dob a žádné skutečné bezpečnostní penalizace posádek.
- Nová migrace musí být explicitně rozpoznána všemi třemi migration guardy;
  unknown future schema dál fail-closed. Simulace nezakládá historickou účast.
- cancellation odstraní i anonymizované vlastnické/replay vazby; úklid respektuje
  rollback a files až po commitu. Samotná FK ochrana není úplnou implementací.
- Limity počtu akcí/velikosti projekce/snapshotu určit technicky podle zátěžového
  testu před nasazením; nepřidávat obecný quota systém. Nedostatek disku nesmí
  potvrdit neúplný snapshot. Žádný automatický zásah do produkčních Akcí.

## 11. Testovací strategie

### Unit bez DB a sítě

Vedle níže uvedených případů povinně pokrýt všechny scénáře ověřeného nahrazení
penalizací v §3.1; nejde o volitelnou variantu bodování.

- CODE normalizace/limity, správný/nesprávný vstup, přesný text v diagnostické historii; null max0 a override
  null vs0, podepsané sazby. Samostatné kontroly i AND v obou pořadích podle schválené Q2; úspěch
  přežije samotný posun hodin i změnu vstupní polohy, dokud neskončí aktivace. Již splněnou
  podmínku znovu nevyhodnotit; restart/reset a restore příznaků z prefixu.
- GPS střed/hrana/vně, póly/antimeridián, accuracy==radius/>radius, unavailable,
  simulovaný mock signal. Tolerance jen v assertion geometrie, ne skrytě v pravidle.
- TIME 0/60000/60001/125000ms; EARLY 900000/899999/898999/720000ms, kladné i záporné
  sazby. Chybné pokusy nepřidávají dokončovací časové položky.
- Skip nahrazuje všechny dosavadní účinné body stage; historie a správná delta,
  poslední skip ukončí run, ale nezvýší completed count. Long/ms overflow rollback.
- Clock +/-, start/floor, opakované změny bez duplicit; shift nevrací completed
  stage, restore/restart ano v nové větvi. Deterministický replay téhož prefixu.
- Všechny formáty §8 parametrizovaně, zejména DMM bez apostrofu/° a decimal comma;
  chybná hemisféra/rozsah/ambiguity/URL bez síťových závislostí.
- Q1: kvízy nejsou součástí první etapy; případná rezervovaná 501 nesmí měnit
  stav. Žádné testy smyšlené správnosti/quiz bodů a žádné quiz UI.
- timeFloor: samotný záporný clock shift je povolený, herní příkazy při hodinách
  před floor vracejí konflikt, návrat na přípustný čas je odblokuje. Restore
  zahrne GPS, virtuální čas, dokončené stage, dílčí příznaky i body z prefixu.

### HTTP/security

Strict DTO/statusy, anonymní/USER/ADMIN/vlastník/cizí ADMIN, platné cookie CSRF a
Bearer, no-store a souborové privacy/CSP. Žádné vyzrazení správného CODE ze
snapshotu; historie smí vracet správný i nesprávný CODE skutečně vložený uživatelem. Complete endpoint nesmí obejít automatické podmínky.
Celý State po mutaci, null currentStage při finish, konzistentní version/dashboard.

### PostgreSQL

- Nová migrace a reviewed guardy, FK run vazby; create source edit/delete souběh.
- Create kompletní DRAFT i READY se stejnou validací bez změny source status/version;
  neúplná konfigurace vrací errors a nezaloží částečnou simulaci ani snapshot souborů.
- Snapshot obsahuje veškerá pole/soubory, přežije změnu/smazání šablony; žádný
  zásah do Akcí/posádek, simulace nezablokuje deletion šablony jako TEMPLATE_IN_USE.
- Souběžné akce stejné verze, duplicitní key, odlišný payload před version check;
  opožděný starý run/stage bez efektu. CODE vs skip vs restart/restore/clock;
  jediné účinné nahrazení stage penalizací a přesný prefix po restore dle §3.1.
- Atomický deník/projekce/version/audit, selhání DB/auditu/souborů, rollback jen
  vlastních nových bajtů. Obnova po restartu ApplicationContext, stejný replay.
- Reálná retence nezávislá na virtualNow, cleanup/cancellation anonymizace i delete
  souběžně s pokusem, zachování chráněných aplikačních dat a všech FK.
- Regrese TemplatesIT, EventsIT, AccountCancellationServiceIT a CommandCleanupStore.

Běžné Maven testy/JDK21 bez DB; PostgreSQL výhradně uživatelem přes
`.\mvnw.cmd -Pit verify`. Tento dokument není implementace ani doklad jejich běhu.

## 12. Uzavřená rozhodnutí a připravenost

| Bod | Stav | Důsledek |
|---|---|---|
| Q1 – Kvízy | Rozhodnuto: odložit | Bez kvízů v první etapě; případná rezervace není hotová funkce a neblokuje jádro |
| Q2 – AND | Rozhodnuto: kumulace | CODE/GPS v libovolném pořadí, bez expirace, do opuštění aktivace; žádné nové vyhodnocení již splněné podmínky |
| Q3 – Virtuální čas | Rozhodnuto: kapitola 6 | Posun bez undo, timeFloor, explicitní restore do nové větve, žádné dvojí penalizace |
| Q4 – Zdroj | Rozhodnuto: DRAFT i READY | Úplná simulovatelná konfigurace, seznam chyb jinak; zdroj se nemění; žádná dílčí trasa |
| Přeskočení | Ověřeno v závazné 0.9.0 §7.1, varianta B | Nahrazení účinných penalizací konkrétního stanoviště, historie zachována; nejde o nové schválení tímto zadáním |

**Nezůstává otevřená produktová otázka blokující první etapu bez kvízů.**
Pokud by uživatel chtěl změnit výslovné pravidlo §7.1 na přičítání, šlo by o novou
změnu specifikace a vyžadovalo by to samostatné rozhodnutí; tento dokument ji
nezavádí. Ověření zdroje neznamená, že je závodní runtime již implementovaný.

Neomezené uchování simulací, konkrétní JSONB persistence, Haversine konstanta a číselné
reprezentace nového API zůstávají označené technické návrhy, nikoli nově
schválená obchodní pravidla. API tvary se tímto dokumentačním uzavřením nemění.
Skutečné změny trasy/end_at/start jsou mimo první etapu a neblokují ji.

Pro souběžnou implementaci oba týmy použijí kapitolu 9 (stejné URL, DTO, stavové
kódy a replay/version/runId). Frontend může začít s mocky a později napojit backend;
nesmí převzít bodování nebo geometrii. Backend implementuje sdílený evaluator
podle uzavřených pravidel a doloží invarianty testy. Technické změny kontraktu
zjištěné při implementaci se nejprve oznámí a sjednotí, neprovedou jednostranně.
Rezervace kvízů není důvod čekat s ostatními obrazovkami ani slibovat jejich funkčnost.

## 13. Doporučený implementační postup

1. **Připravit samostatná implementační zadání podle uzavřených Q1–Q4.**
   API první etapy bez kvízů je připravené pro souběžnou práci; nevymýšlet nové
   otázky ani měnit ověřenou sémantiku přeskočení. Samotný tento dokument není
   pokynem začít implementovat.
2. **Backend: společný evaluator + GPS parser.** Deterministické unit testy bez
   sítě. Frontend souběžně připraví layout a mocky podle schváleného StateView,
   nepřenáší bodování do JavaScriptu.
3. **Backend: persistence/snapshot/create/read/files.** Migrace, guardy a
   cancellation/úklid; frontend napojí Vyzkoušet závod a instrukce.
4. **Backend: pokusy, clock/position, dashboard, replay.** Frontend sekvenčně
   posílá mutace s poslední version a runId, řeší konflikty načtením stavu.
5. **Restart/restore/historie/retence**, testy pádu backendu a souběhů. Dashboard
   na široké obrazovce vpravo, na úzké pod herním panelem; layout řeší frontend.
   Ovládání času v sekundách/minutách/hodinách dopředu i dozadu, samostatné
   ovládání obnovení stavu a viditelné vysvětlení timeFloor blokace.
6. **Společná akceptace:** pravidla, parser včetně geocaching bez apostrofu,
   vlastníci, rollback, manual PG + Postman/UI. Budoucí ostrý runtime použije
   stejné jádro, ale doplní své skutečné časové a závodní ochrany.

## 14. Implementovaný backend a provozní ověření

Implementována první etapa bez kvízů. Veškeré URL a DTO kapitoly 9 zůstávají
zachované, quiz rezervace vrací 501 bez herního efektu. Společný RaceEvaluator
je čisté deterministické jádro; GPS geometrie používá StrictMath pro shodný
výsledek i při replayi mezi Windows/x64 a budoucím ARM64 JVM.

Nové produkční třídy pod src/main/java/cz/mh/rallye/rallye_backend/:

- competition/domain: RaceEvaluator, GpsMath, GpsParser.
- simulation/application: SimulationService, SimulationView, SimulationFiles,
  SimulationMaintenance.
- simulation/persistence: SimulationStore.
- simulation/api: SimulationController.

V23__race_simulations.sql přidává race_simulation, simulation_run,
simulation_command a simulation_file. Kompozitní FK chrání příslušnost runů.
Snapshot má vlastní konfiguraci a soubory, nejde o race_event ani historii účasti.
Projekce se při restore znovu vypočítá z ověřených faktů a kontroluje se hash
předchozího/výsledného stavu. Potvrzené vstupy včetně původního CODE se
nově ukládají vedle ověřených faktů (§15), nikoli místo nich. Po expiraci replaye
zůstanou ověřená fakta a klíčový tombstone do smazání simulace.

V23 přidává schema-qualified trigger odstranění simulací po anonymizaci účtu;
fyzické smazání pokrývá FK CASCADE. Tím se ve stejné cancellation transakci
odstraní snapshoty i runy/deník/souborová metadata. Fyzické soubory z cancellation
se odstraní následným orphan úklidem, nikdy před commitem. K žádné simulaci
zrušeného účtu nelze přistoupit. ADMIN/identitní invariantu se změna nedotýká.

Migration guardy vědomě znají V23, nikoli libovolnou následující verzi.
Stávající CommandStore se využívá pro SIMULATION_CREATE s bezpečným výsledkem
ID/verze; stavový deník navíc uchovává pouze skutečně zadaný CODE v `input.code`. Sdílené Markdown/file helpery
nově znají explicitní simulační namespace, ostatní URL se nemění.

### Konfigurace

| Property | Výchozí hodnota |
|---|---|
| rallye.simulation.file-directory | ${user.home}/.rallye/simulation-files |
| rallye.simulation.cleanup-enabled | true |
| rallye.simulation.cleanup-delay-ms | 600000 |

Úložiště musí být oddělené od kořene šablon i Akcí. Úklid replay odpovědí a orphanů používá skutečný UTC
Clock, ne virtuální čas. Cleanup běží po 10 minutách od startu, potom periodicky,
pod storage a databázovými zámky. V profilu it je plánovač simulací vypnutý;
integrační testy volají cleanup výslovně. Nenahrazuje retenci bezpečnostního auditu.
Při chybě se bezpečně zaloguje diagnostika bez payloadu; další plánovaný běh
může pokračovat. Orphan soubory se mažou až po 24 h a dvojím ověření referencí.
Více aplikačních instancí pro soubory vyžaduje sdílený privátní volume, stejně
jako stávající souborové moduly; nesdílené lokální kopie nejsou distribuovaný storage.

### Ruční ověření

1. ADMIN načte templateVersion a úplnou DRAFT/READY šablonu s knihovnou.
2. POST base dle §9; použít samostatný Idempotency-Key, uložit id/runId/version.
3. Každou další mutaci posílat s aktuální verzí a runId; stejný klíč jen pro retry.
4. Nastavit INPUT/TARGET/OUTSIDE polohu; samostatně provést GPS pokus. CODE/GPS
   zkoušet v obou pořadích, opakování splněné podmínky nezmění body.
5. Clock +60 s (=60000 ms), chybný pokus a posun zpět; očekávat povolený shift,
   ale blokované herní příkazy před timeFloor. Restore do vybrané sequence vytvoří
   nový run a přesný prefix bodů; stará větev zůstává v history.
6. Skip po 3 bodech při náhradě 12 vrátí 12 celkem a pointsDelta 9, nikoli 15.
7. Restart resetuje stav nad původním snapshotem; změna zdrojové šablony jej neovlivní.
8. Jiný ADMIN nemá přístup k souborům ani stavu (404); USER je odmítnut (403).
   Cookie mutace bez CSRF jsou odmítnuty, platný Bearer používá stávající režim.
9. Zkontrolovat dashboard/history, CLOSE a DELETE; žádná skutečná Akce nevzniká.

### Testy a stav akceptace

Codex ověřil JDK 21.0.12.1 a Maven Wrapper. Původní backend měl 688 rychlých
testů a 20 SimulationIT; jeho PostgreSQL ověření včetně V23 uživatel potvrdil
jako BUILD SUCCESS. Uživatel potvrdil také následné cílené ověření optimalizovaných
fixture (21 SimulationIT). Rozšíření historie v §15 prošlo novým verify bez DB:
**692 testů, 0 failures/errors/skipped, BUILD SUCCESS, 59,707 s**. Aktuálních
24 SimulationIT včetně tří nových regresí historie je zkompilovaných. Uživatel
následně potvrdil cílený PostgreSQL běh jako BUILD SUCCESS; nejde o běh Codexu.
Příkaz pro případné opakování:

```powershell
.\mvnw.cmd -Pit verify
```

Případný cílený běh: `.\mvnw.cmd -Pit "-Dit.test=SimulationIT" verify`.
Uživatelské testy se provádějí pouze podle docs/testing.md proti rallye_test.
Předchozí PG úspěch základního simulátoru není ověřením nového rozšíření historie. Ruční UI/API průchod,
skutečné mobilní GPS a frontend nejsou tímto buildem ověřené. Produkční ostrý
závodní runtime zůstává budoucí službou nad stejným evaluatorem.

Bez commitu/pushe, frontend, .gitignore a docs/Rallye_specifikace.md zůstaly nedotčené.

V historii se potvrzovací no-op complete označí STAGE_ALREADY_COMPLETED a uchová
požadované stageId; dashboard.lastAction a version se přitom nemění. Ostatní
řídicí no-op příkazy mají v historii UNCHANGED. Nejde o další úspěch ani penalizaci.

### Přehled souborů této implementace

- `docs/backend-plan.md`
- `docs/race-simulator-design.md`
- `docs/testing.md`
- `src/main/java/cz/mh/rallye/rallye_backend/audit/application/AuditService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/audit/persistence/AuditStore.java`
- `src/main/java/cz/mh/rallye/rallye_backend/competition/domain/GpsMath.java`
- `src/main/java/cz/mh/rallye/rallye_backend/competition/domain/GpsParser.java`
- `src/main/java/cz/mh/rallye/rallye_backend/competition/domain/RaceEvaluator.java`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/PreRaceAccountParticipationFacts.java`
- `src/main/java/cz/mh/rallye/rallye_backend/races/persistence/EventStore.java`
- `src/main/java/cz/mh/rallye/rallye_backend/shared/persistence/CommandAccounts.java`
- `src/main/java/cz/mh/rallye/rallye_backend/simulation/api/SimulationController.java`
- `src/main/java/cz/mh/rallye/rallye_backend/simulation/application/SimulationFiles.java`
- `src/main/java/cz/mh/rallye/rallye_backend/simulation/application/SimulationMaintenance.java`
- `src/main/java/cz/mh/rallye/rallye_backend/simulation/application/SimulationService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/simulation/application/SimulationView.java`
- `src/main/java/cz/mh/rallye/rallye_backend/simulation/persistence/SimulationStore.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/application/ImageNames.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/application/InstructionsMarkdown.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/application/TemplateImageFiles.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/persistence/TemplateStore.java`
- `src/main/resources/db/migration/V23__race_simulations.sql`
- `src/test/java/cz/mh/rallye/rallye_backend/competition/GpsParserTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/competition/RaceEvaluatorTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/EventsIT.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/PreRaceAccountParticipationFactsTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/SimulationIT.java`
- `src/test/java/cz/mh/rallye/rallye_backend/simulation/SimulationHttpTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/simulation/SimulationServiceTest.java`


## 15. Kompletní potvrzené vstupy v historii simulace

Aktuální schválené rozšíření nahrazuje původní zákaz uchování zadaného CODE.
Kód stanoviště vložený při simulaci není autentizační tajemství. Snapshot ani
herní State nezačínají zveřejňovat správnou odpověď; přesný text poskytuje pouze
historie potvrzených pokusů vlastníkovi s aktuální rolí ADMIN.

### REST kontrakt

`GET /api/v1/admin/race-simulations/{id}/history?runId=UUID&afterSequence=0&size=50`
vrací stávající `HistoryPage`. URL, stránkování, autorizace a původní pole položek
`HistoryItem` zůstávají. Každá položka má navíc:

| Pole | Typ / význam |
|---|---|
| `input` | `ConfirmedInput` nebo `null` u staré události; explicitní whitelist, bez HTTP/auth kontextu |
| `input.version` | desetinný řetězec int64 očekávané verze požadavku |
| `input.runId`, `input.stageId` | UUID původně adresované větve/stanoviště; stage může být null |
| `input.code` | přesný řetězec pro CODE včetně mezer, velikosti písmen a chybných hodnot; jinak null |
| `input.deltaMs` | podepsaný desetinný řetězec int64 pro CLOCK_SHIFT; jinak null |
| `input.mode` | INPUT/TARGET/OUTSIDE/UNAVAILABLE u POSITION; jinak null |
| `input.latitude`, `input.longitude`, `input.accuracyMeters` | původní číselné parametry INPUT, přesnost Java double; jinak null; bez zaokrouhlování |
| `input.mockLocation` | původní boolean INPUT; jinak null |
| `input.throughSequence` | desetinný řetězec int64 cílového prefixu RESTORE; jinak null |
| `gps` | původní `GpsResult` kontroly: latitude, longitude, accuracyMeters, distanceMeters (number nebo null), outcome (string); jinak null |
| `position` | skutečně použitá/nastavená Position pro POSITION/GPS: latitude, longitude, accuracyMeters, mockLocation; unavailable = null |
| `clockChange` | `{before: ISO-8601 UTC, after: ISO-8601 UTC}`; u starých událostí null |

Identifikace, pořadí, typ operace, stanoviště, výsledek a body zůstávají ve
stávajících `actionId`, `sequence`, `type`, `stageId`, `outcome`, `pointsDelta`,
`penalties`. Body jsou nadále řetězce int64, nikdy JavaScript float. Nové vstupní
int64 hodnoty jsou také řetězce. Stávající `sequence`/stránkovací parametry a
verze State zachovávají původní kontrakt. Souřadnice se nepřevádějí na zkrácený
formát GPS parseru; historie uchovává skutečně potvrzené numerické hodnoty.

Příklady nových polí (ostatní původní pole položky zůstávají):

```json
{"type":"CODE","outcome":"CODE_REJECTED","pointsDelta":"3","input":{"version":"0","runId":"22222222-2222-4222-8222-222222222222","stageId":"44444444-4444-4444-8444-444444444444","code":"  wrong MiXeD  ","deltaMs":null,"mode":null,"latitude":null,"longitude":null,"accuracyMeters":null,"mockLocation":null,"throughSequence":null},"gps":null,"position":null,"clockChange":{"before":"2026-10-29T10:00:00Z","after":"2026-10-29T10:00:00Z"}}
```

```json
{"type":"POSITION","outcome":"POSITION_SET","pointsDelta":"0","input":{"version":"1","runId":"22222222-2222-4222-8222-222222222222","stageId":null,"code":null,"deltaMs":null,"mode":"INPUT","latitude":50.00123456789,"longitude":14.00123456789,"accuracyMeters":2.123456789,"mockLocation":false,"throughSequence":null},"gps":null,"position":{"latitude":50.00123456789,"longitude":14.00123456789,"accuracyMeters":2.123456789,"mockLocation":false},"clockChange":{"before":"2026-10-29T10:00:00Z","after":"2026-10-29T10:00:00Z"}}
```

```json
{"type":"GPS","outcome":"GPS_OUTSIDE","pointsDelta":"4","gps":{"latitude":50.00123456789,"longitude":14.00123456789,"accuracyMeters":2.123456789,"distanceMeters":163.2,"outcome":"GPS_OUTSIDE"}}
```

GPS příklad ukazuje tvar výsledku; skutečnou vzdálenost vždy vypočítá backend.
POSITION pouze nastaví polohu a neprovádí GPS pokus. Následující GPS je samostatná
událost s vlastním pořadím, použitou polohou, výsledkem a případnou penalizací.
TARGET/OUTSIDE v `input` neobsahují vymyšlené vstupní souřadnice; backendem odvozená
poloha je v `position`. UNAVAILABLE ji vynuluje; GPS_UNAVAILABLE nemá vzdálenost.

```json
{"type":"CLOCK_SHIFT","outcome":"CLOCK_SHIFTED","pointsDelta":"0","input":{"version":"2","runId":"22222222-2222-4222-8222-222222222222","stageId":null,"code":null,"deltaMs":"-500","mode":null,"latitude":null,"longitude":null,"accuracyMeters":null,"mockLocation":null,"throughSequence":null},"gps":null,"position":null,"clockChange":{"before":"2026-10-29T10:00:01Z","after":"2026-10-29T10:00:00.500Z"}}
```

SKIP/COMPLETE uchovávají původní runId/stageId, RESTORE původní runId a
throughSequence, RESTART/CLOSE původní runId. `clockChange` zachycuje oba skutečné
časové konce včetně RESTORE/RESTART; typ změny určuje `type`. Ani řídicí no-op
nepřidává body nebo nemění původní pravidlo verzování.

### Persistence, kompatibilita a bezpečnost

Rozšířeno je pouze JSONB `simulation_command.verified_facts`: `input` a
`previousClock`. V23 ani jiné migrace se nemění; nová migrace není potřeba.
Chybějící nové atributy se čtou jako null. Původní zadaný CODE ze starších událostí
nelze zpětně získat; frontend zobrazí „vstup nezaznamenán“, nic nedopočítává.
Starší GPS fakta mohou poskytovat polohu/výsledek, ale ne původní volbu režimu.

Replay/restore používají nadále ověřená fakta a kontrolní hashe stavů. Nová
vstupní pole jsou diagnostická, nemění evaluator ani jejich hashe. Formát snapshotu
a verze evaluatoru zůstávají 1, pravidla verzování simulace se nemění.

Příkaz, vstupy, efekty a idempotentní výsledek se zapisují společně v existující
transakci pod zámkem simulace. Pořadí je `sequence`; nevalidní/odmítnuté HTTP
požadavky nevytvoří událost. Idempotentní retry nevytvoří další vstup; při
kanonicky shodném CODE (strip/uppercase) zůstane přesný text prvního přijatého
požadavku. Po expiraci replay zůstávají diagnostické vstupy s historií až do
výslovného smazání simulace. Nevzniká trvalá historie po smazání simulace.

Historie nepřebírá request body obecně, hlavičky, cookies, hesla, Bearer tokeny ani
idempotentní klíče. Přesný CODE se nepřidává do bezpečnostního auditu, provozních
logů, chybových odpovědí nebo dashboardu. `Input`, `ConfirmedInput` a `Facts`
mají bezpečné `toString`. Frontend vykresluje `input.code` jako obyčejný text,
nikoli HTML/Markdown. Neukládají se nepotvrzené formuláře nebo otevření dialogu.

### Ověření rozšíření

PostgreSQL regrese pokrývají přesné CODE, idempotenci, reload, odmítnuté
požadavky, pořadí POSITION/GPS a všechny režimy, souřadnice, čas vpřed/vzad,
SKIP/RESTORE/RESTART a starý JSON bez nových atributů. Uživatel potvrdil
`.\mvnw.cmd -Pit "-Dit.test=SimulationIT" verify` jako **BUILD SUCCESS**.
Jde o uživatelem provedené integrační ověření, nikoli běh Codexu.
Lokálně dostupný Failsafe XML je starší report s 21 testy; výše uvedené potvrzení
nového běhu historie vychází z vyjádření uživatele. Rychlé unit/HTTP testy ověřují
i int64 nad 2^53.

Závěrečná kontrola dokončení historie: implementace nebyla znovu vytvářena.
Opakovaný Codex `verify` bez PostgreSQL na JDK 21 dne 29. 10. 2026: **692 testů,
0 failures/errors/skipped, BUILD SUCCESS, 55,434 s**. `git diff --check` a
validace všech 17 JSON příkladů prošly. PostgreSQL sada se znovu nespouštěla.


## 16. Správa trvale uložených simulací

V24 zachovává data, dovoluje NULL v `expires_at`, nastaví tuto hodnotu existujícím
simulacím a přidává index vlastníka a poslední aktivity. TTL 24h/7d se ruší,
konfigurace idle-ttl/max-age se nepoužívá. Kompatibilní `expiresAt` v detailu a
replay odpovědi je vždy null. RUNNING/COMPLETED/CLOSED zůstávají stejné.
Zrušení/anonymizace vlastníka nadále odstraní jeho simulace podle bezpečnostních pravidel.

### Seznam pro frontend

`GET /api/v1/admin/race-simulations?page=0&size=20&status=RUNNING`

Pouze aktuální ADMIN, pouze vlastní data. `page`: 0–10000, `size`: 1–100
(výchozí 20). Nepovinný `status`: RUNNING/COMPLETED/CLOSED. Nesprávné parametry:
400 INVALID_REQUEST. Řazení před stránkováním: last_activity_at DESC, id DESC.
GET nemění aktivitu. Odpověď má Cache-Control: no-store:

```json
{"items":[{"id":"11111111-1111-4111-8111-111111111111",
"source":{"templateId":"22222222-2222-4222-8222-222222222222","templateVersion":4,"templateName":"Podzim"},
"createdAt":"2026-10-29T10:00:00Z","lastActivityAt":"2026-10-29T10:05:00Z",
"runId":"33333333-3333-4333-8333-333333333333","version":2,"status":"RUNNING",
"currentStage":{"id":"44444444-4444-4444-8444-444444444444","name":"Start","sequenceNo":1}}],
"page":0,"size":20,"hasMore":false}
```

Verze jsou JSON int64 dle detailu; bodové hodnoty zůstávají přesné řetězce.
currentStage je null u COMPLETED/CLOSED. source je původní název/verze snapshotu.
Seznam nevrací celý snapshot ani CODE konfiguraci.

### Pokračování a smazání

Použijte stávající GET detailu, historie a dashboardu. Aktuální runId a version
z detailu umožňují pokračovat se zachovaným časem, body a historií. Pro pouhé
otevření nevolejte restart ani restore. Nový resume endpoint není potřebný.

`DELETE /api/v1/admin/race-simulations/{id}?version=2` vrací 204 bez těla;
verzi vezměte z detailu nebo seznamu. Konflikt: 409 SIMULATION_VERSION_CONFLICT.
Idempotency-Key není vyžadován. Opakované, neexistující i cizí ID:
404 SIMULATION_NOT_FOUND. Bez relace 401, neadministrátor 403. Cookie zápisy
vyžadují CSRF, Bearer zachovává dosavadní pravidla.

Smazání a stavové změny sdílejí transakční zámek simulace. Smazání odstraní
běhy, historii, snapshot, reference souborů i SIMULATION_CREATE v CommandStore.
Audit SIMULATION_DELETED zůstává. Kopie souborů se odstraňují po commitu;
případný orphan uklidí údržba po 24 h s ověřením referencí. Zdrojová šablona,
její soubory, další simulace, Akce a posádky se nemažou.

### Ověření

Unit/HTTP testy ověřují seznam, validaci, autorizaci a CSRF. SimulationIT
pokrývá upgrade V23→V24, pořadí/stránkování, nečinnost 365 dní s novou relací,
reload, mazání, rollback auditu a souběh. PostgreSQL běh provádí uživatel:
`.\mvnw.cmd -Pit "-Dit.test=SimulationIT" verify`.


### Závěrečný stav ověření správy simulací

Poslední běžné verify: **695 testů, BUILD SUCCESS**. Uživatel následně potvrdil
cílený PostgreSQL běh **28 SimulationIT, BUILD SUCCESS** po opravě izolace testu
seznamu (vlastní administrátor, deterministický Clock, přesné pořadí a filtry).
Kompletní PostgreSQL sada byla úspěšná před posledním rozšířením správy simulací;
po něm nebyla nově spuštěna. Historické počty testů v předchozích kapitolách
popisují tehdejší běhy, nikoli aktuální počet. Závěrečné uložení do Gitu testy
znovu nespouští a nemění produkční kód ani migrace.
