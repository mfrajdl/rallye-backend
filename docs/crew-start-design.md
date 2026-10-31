# Start posádky a závodní zařízení

**Závazný kontrakt `crew-start-api-v1`, aktualizace 31. 10. 2026.**
Nové rozhodnutí o dvojici původních kódů nahrazuje původní Q2 o přegenerování.
Etapa 1/4 (V29) má uživatelem potvrzené PostgreSQL ověření BUILD SUCCESS.
Etapa 2/4 implementuje ADMIN vydání a čtení kódů (§5.1, §5.2, §5.5); uživatel
v zadání etapy3/4 potvrdil i její PostgreSQL ověření. Etapa3/4 implementuje
start-status, code-redemptions a nouzové revocations; její cílené PostgreSQL
ověření uživatel potvrdil jako BUILD SUCCESS. Etapa4/4 přidává participant
current-run/soubory (§10). Jejich cílené PostgreSQL ověření včetně opravy obou
souběhových testů uživatel potvrdil jako BUILD SUCCESS. Herní runtime chybí.

## 1. Zdroje a skutečný stav

Platí [specifikace 0.9.0](Zavody_specifikace_v0.9.0.md) a pozdější schválené
[registrace](participation-registration-api.md), [lifecycle](race-event-lifecycle-api.md)
a tento kontrakt. Pozdější výslovné rozhodnutí o dvou kódech má přednost.

Existují RegistrationController/RegistrationService/RegistrationStore (V26),
LifecycleService/LifecycleStore/LifecycleScheduler (V27), EventStore/event_stage,
CrewStore a převody kapitánství, account user_session Cookie/Bearer, CommandStore
s 24h retencí, AuditService a společný deterministický RaceEvaluator.
Simulátor a jeho tabulky nejsou skutečným průběhem posádky. Persistence je Spring JDBC.
V29 přidává základ skutečného průběhu; nevytváří účast ani neaktivuje zařízení.

## 2. Schválená pravidla

- Registrace je bez kódu. Oficiální START konání neodstartuje posádku.
- První ADMIN vydání vytvoří právě dva různé ORIGINAL kódy v jediné transakci.
  Oba mají nula bodů, stejný okamžik vydání a vydávajícího ADMINa. Kterýkoli smí
  založit první průběh. Druhý zůstává AVAILABLE a později převezme rozehraný závod.
- Další kódy jsou REPLACEMENT, vydává je pouze ADMIN. Slouží výhradně převzetí,
  nikoli prvnímu startu. Jejich počet nemá pevnou kvótu. Nové vydání neruší dříve
  vydané nepoužité kódy; několik AVAILABLE může existovat současně.
- Každý kód je jednorázový. Přegenerování původních kódů ani endpoint regenerations
  neexistují. Kódy nemají vlastní TTL; časovou použitelnost určuje stav konání.
- Q1: oba druhy uplatňuje pouze přihlášený aktuální kapitán příslušné posádky;
  samotný ADMIN cizí kód neuplatní. Oprávnění již aktivního zařízení přežije převod kapitána.
- První start je možný v RUNNING období přísně před end_at (pokud je nastaven).
  Předčasný pokus nespotřebuje kód. Použití při existujícím průběhu je převzetí,
  bez nového startu nebo resetu průběhu. Existující omezení převzetí: rozehraný
  nedokončený current run, RUNNING a před end_at. Dokončený průběh => RUN_ALREADY_COMPLETED.
- Převzetí oběma zbývajícími druhy kódu zachová individual_started_at, current_run_id,
  historii stanovišť a body. Výjimkou je výslovná korekce u dodatečného REPLACEMENT.
  Volitelná celočíselná korekce má výchozí nulu, dovoluje kladné i záporné int64;
  uplatní se právě jednou při úspěšném použití, nikdy při vydání.
- Q3: logout ruší pouze account relaci. Samostatná Race credential nedává práva
  k auth/me, registracím nebo ADMIN API. Nemá sliding TTL account relace.
- Nouzové ADMIN odvolání ruší konkrétní aktivní zařízení, nikoli průběh, start,
  historii či body. Nevydává nové zařízení ani kód. Pokračování vyžaduje nový
  úspěšný redemption dosud AVAILABLE kódu. Vydání samo staré zařízení neodpojí.
- Herní pokus má hranici received_at <= end_at; start < end_at. RESUME samo
  nerestartuje posádky. Q4 započítávání přerušení do časových penalizací zůstává
  otevřené; neblokuje start/zařízení, blokuje úplný runtime časovaných pokusů.

## 3. Datový model a invarianty V29

V1–V28 se nemění. V29 není produkčně nasazená a je upravena přímo dle rozhodnutí.
Předpoklad upgradu: neexistuje individual_started_at bez skutečného průběhu.
Taková V28 data migrace odmítne k prověření; neodhadne historii ani nic nemaže.

`crew_registration`: zachovává V26 složení/version/historického kapitána. Přidává
current_run_id nullable a device_generation bigint >=0 default 0. První start
atomicky zachytí individual_started_at, historical_captain_account_id,
started_members_text a started_children_under_15 a zvýší version. Pozdější převzetí
startovní snapshot nepřepisuje. Aktuální kapitán spravuje přihlášku do startu.
Kontrola složení má nadále pořadí autorizace → version → konec konání → start.

`race_run`: id UUID, registration_id/race_event_id, ordinal >0, status
RUNNING/COMPLETED, activation_generation >0, started_at, nullable completed_at,
participation_ended_at, version >=0. UNIQUE(registration_id,ordinal) a složený
odložený FK z registrace na vlastní run. Aktuálnost určuje ukazatel, nikoli status.

`race_stage_progress`: run_id/event_stage_id, race_event_id, sequence_no, stav
PENDING/ACTIVE/COMPLETED/SKIPPED, activated_at/completed_at/ended_at,
code_satisfied/gps_satisfied. Složené FK ověřují shodné konání. Nejvýše jeden ACTIVE
na run. První start v další etapě inicializuje stav přes RaceEvaluator.start,
nikoli alternativní algoritmus. Historické dokončené stavy jsou neměnné.

`race_start_code`: UUID id, registration_id, kind ORIGINAL/REPLACEMENT,
generation (pořadové číslo kódu v registraci), state AVAILABLE/USED, code VARCHAR(6),
issued_at, issued_by_account_id (auditní UUID), used_at/used_device_id nullable,
points_adjustment bigint default 0, reason nullable max 2000, version >=0.

- ORIGINAL má generation 1 nebo 2 a points_adjustment=0. Pokud existuje alespoň
  jeden ORIGINAL, při COMMIT musí existovat oba, s různými hodnotami a stejným
  issued_at/issued_by. Osiřelý kód ani třetí ORIGINAL nelze potvrdit.
- REPLACEMENT má generation >=3 a vyžaduje již odstartovanou účast. Pod zámkem
  registrace se přidělí další číslo; max+1 je repository primitivum, nikoli workflow.
- UNIQUE(registration_id,generation), UNIQUE(registration_id,code) přes celou historii.
  Původní částečný index jednoho AVAILABLE se ruší. Nerecyklují se použité hodnoty.
- AVAILABLE → USED je jediný přechod, version+1. Kód/účel/původ/korekce jsou neměnné.
  USED vyžaduje used_at a vlastní zařízení. Žádná replaceCode ani REPLACED větev.
- První start dokládá USED ORIGINAL vytvořivší zařízení generace 1, nikoli počet
  ORIGINAL s used_at=start: i druhý kód lze použít ve stejném milisekundovém okamžiku.

`race_device_authorization`: id, registration_id, generation >0, credential_hash
(SHA-256, hex64), ACTIVE/REVOKED, created_at/revoked_at, created_by_code_id,
label max100, revocation_reason max2000, revoked_by_account_id. Hash globálně unikátní
včetně historie; UNIQUE(registration_id,generation), UNIQUE(created_by_code_id),
nejvýše jedno ACTIVE. Odvolaný řádek se neaktivuje znovu; dočasně může nebýt žádný ACTIVE.

`race_start_command`: trvalý doklad, id, registration_id, actor_account_id,
command_key UUID, operation ORIGINAL_ISSUE/REPLACEMENT_ISSUE/CODE_REDEEM/DEVICE_REVOKE,
fingerprint_version, request_fingerprint, code_id, second_code_id, device_id nullable,
executed_at, response_status, result JSON objekt bez secrets.
ORIGINAL_ISSUE vyžaduje obě různá code_id/second_code_id původní dvojice, status201;
REPLACEMENT_ISSUE jeden REPLACEMENT, status201; CODE_REDEEM kód+zařízení, status200;
DEVICE_REVOKE zařízení bez kódu, status200. Ostatní operace mají second_code_id null.
UNIQUE(actor_account_id,command_key) napříč operacemi; nejvýše jeden ORIGINAL_ISSUE
na registraci, nejvýše jeden CODE_REDEEM na kód. Složené FK brání záměně účasti.
ORIGINAL_REGENERATE se odstraňuje. Doklady jsou neměnné a nemají 24h TTL.

`race_points_adjustment`: id, registration_id, source_code_id UNIQUE, points bigint,
applied_at, reason. Nenulová korekce vyžaduje USED REPLACEMENT, shodu částky/času;
nenulově použitý REPLACEMENT vyžaduje právě jednu korekci. Pro nulu není třeba
umělá penalizační událost. Žádný strop penalizace stanoviště se zde neuplatní.

Odložené kontroly validují při COMMIT cyklus registrace/run a kód/zařízení,
reciprocitu použití, doklad redemption, úplnost postupu i první start. Historické
řádky nelze mazat nebo změnit jejich původ. FK RESTRICT zachovají historii při
rušení účtu (anonymizace dle stávajících pravidel). Auditní UUID nezavádějí nové FK na účet.

## 4. Token, bezpečnost a limiter

Klient před redemption bezpečně uloží 32 náhodných bajtů jako kanonický Base64url
bez paddingu (43 znaků) deviceSecret. Backend ověří formát a uloží SHA-256; token
nevrací, klient již zná jeho hodnotu. Pro každé nové zařízení je nutný dosud nikdy
nepoužitý hash, jinak 409 DEVICE_SECRET_REUSED. To není hardwarová attestation.
Android chráněné úložiště/iOS Keychain, bez backupu či crash reportu secretu.

Participant čtení přijímá `Authorization: Race <credential>` pouze na
konkrétních GET cestách §5.7/§10; není to Bearer user_session. Po převzetí/revokaci stará
credential vrací 401 RACE_DEVICE_INVALID. Při ENDED není automaticky smazaná,
ale nemůže potvrzovat nové akce mimo povolené období. Dokončení nedává právo nových odpovědí.

SecureRandom.nextInt(36) pro každý ze šesti znaků A–Z/0–9; přímé VARCHAR(6),
bez HMAC/šifrování/klíčů. Vstup strip+uppercase ROOT, pak ASCII regex.
Kolize ve stejné registraci se při budoucím vydání opakuje nejvýše 20krát, potom
503 RACE_STATE_UNAVAILABLE a rollback celé dvojice. U jiné registrace se text smí shodovat.
ADMIN detail čte code pouze v AVAILABLE stavu; USED nebo jinak neplatný => null.
AVAILABLE je evidenční platnost kódu. Dočasná nemožnost použití při ENDED kód
sama neruší (RESUME); povolený čas určuje serverový start-status.
Seznam kódů vrací metadata bez code. Kapitán získává kód mimo aplikaci.
Neexistuje TTL doručení ani regenerations endpoint.

Bez kódů/deviceSecret v URL, cookies, logu, auditu a traces. Redigované toString,
nezapínat JDBC bind/payload logging ani proxy logování credential. TLS mimo lokální
vývoj, Cache-Control:no-store a Referrer-Policy:no-referrer na nových odpovědích.
Command result smí obsahovat jen bezpečná metadata, nikoli kopii kódu/tokenu.

LoginThrottle.check("race-code-redemption",account UUID,trusted remoteAddr):
10 pokusů/účet/15min, 50/IP/15min, mapa10000. Každý pokus včetně retry před business
transakcí; rollback limit nevrací. Změna registrace/klíče kvótu neobejde. Anonymní
vstup má zdrojovou ochranu. Nikdy libovolný X-Forwarded-For bez trusted proxy.
Limiter je lokální paměťový: restart jej resetuje, instance nesdílejí čítače;
schválené omezení hobby provozu. Žádná nová throttle tabulka nebo framework.

## 5. REST kontrakt

Implementované jsou ADMIN endpointy §5.1/§5.2/§5.5, start-status §5.3,
code-redemptions §5.4, ADMIN revocations §5.6 a participant čtení §5.7/§10.
Participant čtení používá skutečný průběh, nikoli simulátor; herní POST nejsou implementované.
UUID string, časy UTC Instant s ms, verze/generace přesné int64, bodové hodnoty
jako desetinný string int64. Neznámá pole odmítnout. Chyby application/problem+json
status/title/code/instance. Cookie zápisy CSRF, Bearer dle současných pravidel;
Race credential nesmí otevírat ADMIN ani account endpointy. Žádná nová role.

Společný prefix `P=/api/v1/race-events/{eventId}/registrations/{registrationId}`.
ADMIN kontroluje aktuální ADMIN roli, kapitán aktuální vlastnictví; cizí účast 404.

### 5.1 První vydání dvojice

`POST P/start-codes`, ADMIN, povinný Idempotency-Key UUID.
Request: `{"registrationVersion":0}`. Pouze před individuálním startem,
PLANNED/RUNNING před end_at. Druhé vydání s novým klíčem =>409 ORIGINAL_CODES_ALREADY_ISSUED.
201, bez Location na jednotlivý kód (výsledek je dvojice):

```json
{"registrationId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","registrationVersion":1,"codes":[{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1","kind":"ORIGINAL","state":"AVAILABLE","generation":1,"code":"7A9K2P","pointsAdjustment":"0","issuedAt":"2026-11-01T08:00:00.000Z"},{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2","kind":"ORIGINAL","state":"AVAILABLE","generation":2,"code":"8B3L5Q","pointsAdjustment":"0","issuedAt":"2026-11-01T08:00:00.000Z"}]}
```
IssuedCodePair: registrationId UUID, registrationVersion int64 historická,
codes přesně dva CodeResult seřazené generation ASC. CodeResult obsahuje přesně
id UUID, kind, state, generation int64, code nullable string, pointsAdjustment string,
issuedAt Instant. Žádné replacedCodeId. Replay vrací původní dvě ID a aktuální
state/code každého zvlášť: po startu např. USED/null a AVAILABLE/8B3L5Q.

### 5.2 Zobrazení kódů

`GET P/start-codes?page=0&size=50`, ADMIN, 200 Page
`{items:[CodeView],page,size,hasMore}`. Page 0–10000, size1–100; issued_at DESC,id DESC.
CodeView: id, registrationId, kind, state, generation, pointsAdjustment, issuedAt,
usedAt nullable Instant, usedDeviceId nullable UUID. Bez code a historické registrationVersion.
`GET P/start-codes/{codeId}` 200 CodeDetail = CodeView + code nullable; pro AVAILABLE
string, pro USED null. Opakované čtení nemění verzi/stav a neprovádí nové vydání.
Příklad detailu: `{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1","registrationId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","kind":"ORIGINAL","state":"USED","generation":1,"pointsAdjustment":"0","issuedAt":"2026-11-01T08:00:00.000Z","usedAt":"2026-11-01T09:00:00.000Z","usedDeviceId":"dddddddd-dddd-4ddd-8ddd-dddddddddddd","code":null}`.

### 5.3 Stav startu

`GET P/start-status`, kapitán nebo ADMIN, 200:

```json
{"registrationId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","registrationVersion":1,"serverTime":"2026-11-01T08:05:00.000Z","eventStatus":"PLANNED","individualStartedAt":null,"canStart":false,"blockedReason":"RACE_NOT_STARTED","currentRunId":null,"canTakeOver":false,"takeOverBlockedReason":"PARTICIPATION_NOT_STARTED","deviceGeneration":0,"activeDevice":null}
```
canStart/canTakeOver vyjadřují kapitánství, stav průběhu a povolený čas, nikoli
platnost konkrétního kódu; GET není oracle a nematerializuje lifecycle.
blockedReason: null nebo NOT_CAPTAIN/RACE_NOT_STARTED/RACE_ENDED/PARTICIPATION_ALREADY_STARTED.
takeOverBlockedReason navíc PARTICIPATION_NOT_STARTED/RUN_ALREADY_COMPLETED.
activeDevice null nebo `{id:UUID,generation:int64,createdAt:Instant,label:string|null}`.
Původní navržená canRedeemReplacement/replacementBlockedReason jsou nahrazena
canTakeOver/takeOverBlockedReason: převzetí již není omezené druhem kódu.

### 5.4 Použití kteréhokoli kódu

`POST P/code-redemptions`, přihlášený aktuální kapitán, Idempotency-Key UUID.
Request `{"registrationVersion":1,"code":"7A9K2P","deviceSecret":"<43 znaků Base64url>","deviceLabel":"Telefon"}`.
Verze nezáporná; label nullable/volitelný prostý text max100. Klient neurčuje účel,
run ani body. Server pod zámky rozhodne podle existence skutečného current_run:
bez něj pouze ORIGINAL založí start; s ním AVAILABLE ORIGINAL nebo REPLACEMENT
provede převzetí. Nikdy druhý start podle samotného kind ORIGINAL.
200 RedemptionReceipt:

```json
{"registrationId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","registrationVersion":2,"codeId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1","kind":"ORIGINAL","effect":"START","individualStartedAt":"2026-11-01T09:00:00.000Z","runId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","activationGeneration":1,"runVersion":0,"deviceId":"dddddddd-dddd-4ddd-8ddd-dddddddddddd","deviceGeneration":1,"pointsAdjustment":"0"}
```
Při převzetí effect=TAKEOVER i pro druhý ORIGINAL; runId/start/activationGeneration
zůstávají stejné. deviceGeneration a registrationVersion rostou. runVersion roste
jen při skutečné nenulové bodové korekci. Receipt neobsahuje credential, cíle ani CODE definice.

### 5.5 Další náhradní kód

`POST P/replacement-codes`, ADMIN, Idempotency-Key UUID, rozehraný nedokončený
current run, RUNNING před end_at. Request
`{"registrationVersion":2,"pointsAdjustment":"-5","reason":"Náhradní telefon"}`.
pointsAdjustment je volitelné (při vynechání přesně "0", explicitní null neplatné),
reason volitelný nullable prostý text max2000. 201 IssuedCode:
`{registrationId,registrationVersion,code:CodeResult}` s kind REPLACEMENT,
generation >=3. Vydání zvyšuje registrationVersion jednou, nemění run/device/body,
neinvaliduje jiné AVAILABLE kódy. Bez existující účasti => PARTICIPATION_NOT_STARTED. Počet vydání bez pevného limitu.

### 5.6 Nouzové odvolání

`POST P/devices/{deviceId}/revocations`, ADMIN, Idempotency-Key UUID.
Request `{"registrationVersion":4,"deviceGeneration":2,"reason":"Ztracený telefon"}`,
reason nullable/volitelný max2000, generace >=1. 200:
`{revocationId:UUID,registrationId:UUID,registrationVersion:5,deviceId:UUID,revokedDeviceGeneration:2,deviceGeneration:3,state:"REVOKED",revokedAt:Instant,currentRunId:UUID}`.
Lze i v ENDED/COMPLETED. Konkrétní ACTIVE device+generace, nikoli cokoli aktuálně
aktivní. Zvyšuje registrationVersion/deviceGeneration, nevytváří credential nové
generace; následující takeover zvyšuje znovu. Run/start/historie/body/kódy beze změny.
Správná aktuální verze + opakování s jiným klíčem => DEVICE_ALREADY_REVOKED,
stará verze má přednost; cílová neshodná generace => DEVICE_GENERATION_CONFLICT.

### 5.7 Minimální skutečné participant čtení

`GET /api/v1/race-participations/{registrationId}/current-run`, Race credential,
200 `{registrationId,runId,activationGeneration,deviceGeneration,version,status,
individualStartedAt,eventStatus,endAt,currentStage,totalPenaltyPoints}`.
currentStage nullable, jinak `{id,sequenceNo,name,type,activatedAt,instructionsMarkdown,
instructionsRenderedHtml,requiredChecks,satisfiedChecks,files}` (doplnění FileView v §10). Nevrací správný CODE,
cílové GPS ani budoucí instrukce. Body string int64. Po dokončení currentStage null.
`GET /api/v1/race-participations/{registrationId}/files/{fileName}` pouze soubory
aktuálního a již absolvovaných stanovišť včetně tranzitivních lokálních odkazů,
cykly jednou, bezpečné MIME/attachment/CSP dle EventService.file. Nikdy celá ADMIN knihovna.
Budoucí herní POST ponese runId, activationGeneration, deviceGeneration, version,
Idempotency-Key; čas/order přidělí server. Herní runtime je mimo tuto etapu.

### 5.8 Chyby a priority

400 INVALID_REQUEST (formát, neznámá pole, body mimo int64); 401 UNAUTHENTICATED
nebo RACE_DEVICE_INVALID; 403 ADMIN_REQUIRED/ACCESS_DENIED včetně CSRF;
404 RACE_EVENT_NOT_FOUND/REGISTRATION_NOT_FOUND/START_CODE_NOT_FOUND/
RACE_DEVICE_NOT_FOUND/RACE_FILE_NOT_FOUND dle rodičů; cizí účast kapitánovi 404.
409 REGISTRATION_VERSION_CONFLICT/IDEMPOTENCY_CONFLICT/RACE_NOT_STARTED/RACE_ENDED/
START_CODE_INVALID/ORIGINAL_CODES_ALREADY_ISSUED/PARTICIPATION_ALREADY_STARTED/
PARTICIPATION_NOT_STARTED/RUN_ALREADY_COMPLETED/DEVICE_SECRET_REUSED/
RACE_DEVICE_REVOKED/DEVICE_ALREADY_REVOKED/DEVICE_GENERATION_CONFLICT.
Budoucí runtime: RACE_RUN_NOT_CURRENT/RACE_ACTIVATION_STALE/RACE_VERSION_CONFLICT.
429 TOO_MANY_ATTEMPTS, bez nového Retry-After kontraktu; 503 RACE_STATE_UNAVAILABLE.
START_CODE_INVALID jednotně pro chybný, cizí, použitý nebo neplatný kód.
Původní navržené chyby regenerace/jediného dostupného kódu se nepoužívají.
Pořadí: autentizace/přístup → bezpečný replay → version → čas → kód → další
podmínky. Časový zákaz nespotřebuje kód. Chyba neprozrazuje SQL/PII/secret.

## 6. Transakce, idempotence a souběh

Pořadí zámků: control/účty dle stávajícího ADMIN invariantu → posádka → konání →
registrace → current run → kódy (u dvojice generation ASC) → zařízení → doklad/audit.
Před event zámkem se nerozhoduje čas; po něm Clock a reconcile LifecycleService.
GET jen projektuje. Outcome může potvrdit nezávislý splatný lifecycle při business
odmítnutí; technická chyba rollbackuje vše. Žádné provider volání v transakci.

První vydání pod registration lock ověří neexistenci dvojice, vloží dva různé
ORIGINAL řádky, jeden ORIGINAL_ISSUE s oběma ID, jednu změnu registration.version
a audit START_CODES_ISSUED bez hodnot. Vše atomicky, chyba druhého INSERT/auditu
vrací původní stav bez jediného osiřelého kódu. Další vydání nikdy není regenerace.

První redemption nemá current run: uloží startovní snapshot, přes RaceEvaluator
vytvoří run/progress, zařízení generace1, spotřebuje pouze vybraný ORIGINAL,
trvalý receipt effect START a audit CREW_INDIVIDUALLY_STARTED/RACE_DEVICE_AUTHORIZED.
Druhý ORIGINAL zůstává beze změny. Takeover s current run: zamkne jej, ověří
rozehranost, odvolá předchozí ACTIVE (pokud existuje), zvýší generaci a vloží nový
unikátní hash. Použitý kód ->USED, pouze u dodatečného nenulového REPLACEMENT
jedna source_code_id korekce; receipt effect TAKEOVER a audit převzetí.
Přetečení int64, chyba stavu/auditu => rollback včetně odvolání starého zařízení.
Žádné nové první stanoviště/run/start při druhém ORIGINAL.

Nouzový revoke sdílí registration lock s herní akcí i takeover. Dříve potvrzená
akce zůstává; pozdější musí znovu ověřit hash/generaci v transakci. Žádná pozitivní
auth cache bez závěrečné kontroly. Revoke cílí konkrétní deviceId, takže replay
starého odvolání neodpojí nové zařízení. ID/aktivace/generace chrání opožděné požadavky.

Trvalý doklad má UNIQUE(actor,key) přes všechny čtyři operace a registrace.
CommandStore 24h nesmí být jediným dokladem. Replay až po aktuální autorizaci;
ADMIN stále ADMIN, kapitán aktuální kapitán. Jiný rodič/operace/tělo => IDEMPOTENCY_CONFLICT.
Vydání dvojice replay vrací stále tatáž dvě ID v pořadí generation; náhradní
stále jedno původní ID. registrationVersion/issuedAt jsou historické, state/code
aktuální: použitý kód null, nepoužitý stále čitelný. Replay nemění žádnou verzi
ani body, i po vypršení 24h ledgeru. Nejde o byte-for-byte neměnnou odpověď.

Redemption retry musí použít stejné tělo/klíč/deviceSecret, uložený klientem před
odesláním. Backend přímo ověří kód vůči odkazovanému code_id a SHA-256 secretu
vůči původnímu zařízení; fingerprint ostatních polí. Stejný aktivní device/generace
vrací původní receipt i po24h nebo konci; odvolané/převzaté => RACE_DEVICE_REVOKED,
nikdy oživení. Jiný klíč s USED kódem => START_CODE_INVALID.
Fingerprint SHA-256 kanonického JSON: operation, rodiče, očekávané verze/generace,
code IDs, hash zařízení, label/reason a normalizovaná korekce (vynechaná=0).
Bez hodnoty krátkého kódu, bez raw deviceSecret; fingerprint_version eviduje formát.
Result JSON bez secrets, pouze metadata a reference. Neúspěšné pokusy nejsou receipts.

| Souběh | Výsledek |
|---|---|
| Dvě první vydání stejnou verzí | Jedna celá dvojice, druhý konflikt; žádný třetí ORIGINAL |
| Stejný issuance klíč | Stejná dvě ID, jeden účinek/audit i po24h |
| Oba ORIGINAL současně před startem | První založí run, druhý se starou verzí selže bez spotřeby; po reloadu je takeover |
| Stejný kód různým klíčem/zařízením | Jen jedna spotřeba a jedno oprávnění |
| Dva dostupné takeover kódy stejnou verzí | Jeden vítěz, druhý konflikt a zůstává AVAILABLE |
| Další vydání a použití | Pořadí pod registrací; vydání nepřepisuje platnost jiných kódů |
| Start a změna členů/kapitána | Změna před startem nebo odmítnutí po zamčeném startu; historie se nepřepisuje |
| Start a cancellation/END | Jediné pořadí; stávající zákaz zrušení při běžící účasti; bez startu v ENDED/end_at |
| Revoke a takeover/herní akce | Verze/target/generace zajistí jediné pořadí, starý token nemění stav |
| Restart/obnova a opožděná akce | current_run a activation_generation; takeover není restart |

Budoucí runtime musí trvale evidovat serverové přijetí receivedAt/period/order.
Včas přijatý pokus nelze zahodit jen proto, že nyní je ENDED. Q4 není rozhodnutá.
Historie není bezpečnostní audit; retence auditu nesmí smazat průběh/doklady.
Přesné migration guardy EventStore/TemplateStore/PreRaceAccountParticipationFacts
znají V29, neznámé budoucí schéma odmítají. Running predicate používá current run.

## 7. Testovací a akceptační scénáře

- Upgrade V28→V29 zachová neodstartované registrace; neověřitelný starý start odmítne.
- Jediný ORIGINAL při COMMIT odmítnut/rollback, přesně dva různé přijaty;
  třetí, duplicitní hodnota nebo pozměněný původ odmítnuty. Současná dvě vydání.
- První start oběma alternativami; druhý zůstává AVAILABLE a neodstartuje podruhé.
- Použití druhého i ve stejném ms zachová start/run/progress/body, mění zařízení.
- USED nikdy znovu, hash nelze recyklovat ani po revokaci; staré zařízení neplatné.
- Další několik dostupných REPLACEMENT; bez korekce=0, kladná/záporná korekce
  až při použití, jeden source_code_id, replay bez dalšího přičtení; auditní chyba rollback.
- Pair receipt má obě správná ID, nesmí odkazovat na cizí registraci; jeden ORIGINAL_ISSUE.
- Odstraněné ORIGINAL_REGENERATE a REPLACED nepřijímá DB/store; endpoint neexistuje.
- Verze, odložené kontroly při commitu, rollback celého grafu, FK a neměnná historie.
- Ztráta odpovědi issuance/redemption, retry po24h; použité hodnoty nikdy přes API.
- Q1, izolace ADMIN/kapitán, CSRF, LoginThrottle účet/IP, převod kapitána, logout,
  nouzové odvolání a souběhy. Skutečné participant endpointy ověřit až s jejich implementací.

## 8. Implementovaný základ a další bezpečné kroky

Etapa1/4: V29, CrewStartStore vyžadující transakci MANDATORY, CrewStartSecrets,
CrewStartAttempts; jednotkové testy a CrewStartIT. PostgreSQL ověření této varianty
uživatel potvrdil jako BUILD SUCCESS. Store poskytuje primitiva, nikoli start/revoke.
Etapa2/4: StartCodeService, StartCodeController a StartCodePrivacyFilter implementují
ADMIN vydávání a čtení dle §5.1/§5.2/§5.5. Nová migrace není potřeba, V29 se nemění.
Redigované toString a odmítání pojmenovaných secret polí v command result jsou
základní ochrana; aplikační vrstva později vkládá pouze bezpečná metadata.

Etapa3/4 poskytuje skutečný start a takeover, včetně interní RaceDeviceAccess.
Etapa4/4 následně doplňuje bezpečné participant čtení (§10). Samotný start
neposkytuje endpoint pro hraní; runtime zůstává další prací. Časované pokusy čekají na Q4.

Ověření etapy2/4: Maven verify bez databáze BUILD SUCCESS, 851 testů, včetně
kompilace integračních testů. CrewStartIT má 33 scénářů, z nich 11 nových pro
skutečné ADMIN služby, audit, replay, souběhy a rollback. Codex PostgreSQL nespouštěl.

StartCodeService zamyká admin_control → aktéra → posádku → konání → registraci
→ případný run a poté zapisuje kódy/doklad/audit. Rozhodný čas určí po event lock,
reconcile stávajícího lifecycle respektuje Outcome pro business odmítnutí.
Technické chyby způsobí rollback a 503 RACE_STATE_UNAVAILABLE.
POST vyžaduje version, klíč a aktuální ADMIN, GET ověřuje ADMIN/relaci i rodiče
v read-only repeatable-read transakci. Vydání nemění device_generation/run/body.

Společný CommandStore ukládá operace RACE_ORIGINAL_ISSUE/RACE_REPLACEMENT_ISSUE,
request [eventId,registrationId,registrationVersion,pointsAdjustment,reasonDigestOrNull],
result {commandId}; žádné hodnoty kódů ani otevřený důvod. CommandAccounts tyto
formáty validuje pro cancellation/retenci a jako účet eviduje jen aktéra.
Trvalý V29 doklad zajišťuje stejnou identitu výsledku i po běžném úklidu ledgeru;
nevzniká nový časový mechanismus. Aktuální stav/code se vždy načte ze start_code.
Audit START_CODES_ISSUED/REPLACEMENT_CODE_ISSUED obsahuje UUID, verzi a korekci,
nikoli hodnoty kódů nebo tokeny. Neúspěšné vydání ani replay nový audit nevytváří.
Privacy filter nastavuje no-store/no-referrer také před Security/CSRF a na chybách.

Ruční příkaz a omezení uvádí docs/testing.md. Budoucí endpointy nejsou považovány
za implementované či ověřené. Q4 nadále blokuje pouze úplný časovaný runtime.


## 9. Implementovaná etapa 3/4

`CrewStartService` implementuje první start, takeover, revokaci a čisté čtení
start-status. `CrewStartController` zachovává přesná DTO §5.3/5.4/5.6.
AuthConfiguration povoluje account relaci pouze pro konkrétní start-status a
code-redemptions; ADMIN právo samo nenahrazuje aktuální kapitánství. Revocations
zůstávají ADMIN. Etapa3/4 sama ještě neotevírala participant cesty; jejich
implementaci v etapě4/4 popisuje §10.

První start sestavuje Definition ze snapshotu EventStore.stages a inicializuje
postup výhradně přes společný RaceEvaluator.start. Nevolá vyhodnocování CODE/GPS,
RADAR, časových penalizací nebo kvízů. Použitý kód, run, všechny počáteční progress,
startovní snapshot členů/kapitána, device generace1, registration.version, doklady
a audit jsou jedna transakce. První ACTIVE má serverový čas individuálního startu.

Takeover zachová run, start a progress. Předchozí ACTIVE se odvolá, případná mezera
po nouzovém odvolání je přípustná; nová generace je registrační hranice+1. Jen
nenulová korekce vloží race_points_adjustment a zvýší run.version. Nula má trvalý
receipt bez umělého nulového bodového řádku, jak určuje §3. Součet korekcí se
kontroluje přesně pomocí BigInteger na rozsah int64; overflow rollbackuje vše.
Vazba na ADMIN vydání vede přes source_code_id a REPLACEMENT_ISSUE doklad.

Nouzové odvolání zvyšuje registration.version a device_generation, nikoli run.version.
Po jeho opakování s původním klíčem se vrací historický receipt a nové zařízení se
neodvolá. Při správné aktuální verzi jiný klíč na REVOKED cíl vrací DEVICE_ALREADY_REVOKED.
Bezpečnostní odvolání je možné i po konci/dokončení, bez automatických bodů.

`RaceDeviceAccess.require` je interní služba MANDATORY, nikoli nový veřejný endpoint
nebo alternativní user autentizace. V transakci zamyká event → registraci → run →
zařízení a znovu kontroluje hash, ACTIVE, device_generation, current_run a
activation_generation. Zámky trvají přes případný budoucí účinek volajícího.
Nezávisí na account relaci ani kapitánství. Logout/změna kapitána tedy credential
neodvolá; převzetí/revoke ji okamžitě znemožní. Nesprávný run/aktivace dostává
RACE_RUN_NOT_CURRENT/RACE_ACTIVATION_STALE; neplatná generace/token RACE_DEVICE_INVALID.
Samotná autorizace nerozhoduje čas/stav herní operace – budoucí runtime musí navíc
v téže transakci ověřit stav, verzi a serverový příjem. Žádný Race HTTP filtr se
v etapě3/4 neinstaloval na tehdy neexistující participant API; etapa4/4
provádí tutéž kontrolu přímo uvnitř read transakce. Authorization:Race neotevře
account či administrační API; redemption stále vyžaduje account Bearer/Cookie.

Ochrana hádání používá výhradně existující LoginThrottle. CrewStartThrottleFilter
před Security/MVC účtuje jednou zdrojovou IP pro každý POST code-redemptions,
včetně anonymních a malformed požadavků; nesleduje tělo ani query. Služba před
business transakcí účtuje účet. Nová dílčí checkSource/checkAccount nemění původní
LoginThrottle.check používané jinými autentizačními operacemi. Zdrojová kvóta se
neúčtuje podruhé. Neúspěšné formální požadavky mají přinejmenším zdrojový limit;
rollback nevrací kvótu. Limity a restartová omezení §4 se nemění. Cookie zápisy
stále vyžadují CSRF; anonymní mobilní požadavek používá stávající opt-in
X-Rallye-Client:mobile, bez něj může chybět CSRF dříve než autentizace (403).

CommandAccounts zná RACE_CODE_REDEEM/RACE_DEVICE_REVOKE s request
[eventId,registrationId,fingerprint] a result {commandId}, bez raw kódu/tokenu.
V29 doklad obsahuje plný bezpečný receipt, nikoli credential. Replay redemption
nejdřív ověří aktuálního kapitána, přímou shodu původního kódu, hash zařízení a
fingerprint. Již odvolané zařízení nikdy nereaktivuje. Žádná nová 24h infrastruktura.

Auditní události: CREW_INDIVIDUALLY_STARTED a RACE_DEVICE_AUTHORIZED při prvním
startu, RACE_DEVICE_TAKEN_OVER při převzetí a RACE_DEVICE_REVOKED při odvolání.
Metadata jsou bezpečný receipt (UUID, generace, verze, čas a korekce), bez kódu,
secretu, jeho hashe a členů. Technická chyba/DB omezení/audit vede na rollback a
503 RACE_STATE_UNAVAILABLE; business odmítnutí může potvrdit jen nezávislý splatný
lifecycle dle existujícího Outcome. Privacy hlavičky platí i na chybových cestách.

Ověření Codexu: Maven verify bez DB BUILD SUCCESS, 875 testů; následná kompilace
posledního IT scénáře samostatně. CrewStartIT obsahuje 20 nových scénářů etapy3/4
(celkem53), včetně skutečného DB omezení a auditu při rollbacku, souběhů, logoutu,
kapitánství, časových hranic a zámků odvolání proti autorizovanému účinku.
PostgreSQL Codex nespouštěl; cílené ověření etapy3/4 následně uživatel potvrdil jako BUILD SUCCESS.
V29 a ostatní migrace nebyly pro tuto etapu změněny. Q4 zůstává otevřená pro runtime.


## 10. Implementovaná etapa 4/4 – read-only participant API

Zachované cesty §5.7 (nevzniká duplicitní varianta pod race-events):

- `GET /api/v1/race-participations/{registrationId}/current-run`
- `GET /api/v1/race-participations/{registrationId}/files/{fileName}`

Oba požadavky nesou právě jednu hlavičku `Authorization: Race <deviceSecret>`.
DeviceSecret je původní kanonický Base64url token 32 bajtů, nikoli startovací kód,
UUID, hash nebo Bearer relace. Nic se nepředává v URL ani cookies. Cookie/Bearer
samostatně nestačí, ani pro ADMIN/kapitána. Případná account cookie se na těchto
GET cestách ignoruje (i po logoutu/expiraci); nemůže nahradit Race token.
Výjimka z account autentizace je omezena na tyto dvě GET cesty. Zbytek API,
včetně ADMIN knihovny konání a auth/me, Race token nepřijímá. GET nevyžaduje CSRF,
Idempotency-Key ani očekávanou verzi, nevytváří audit nebo ledger a neposouvá relaci.
Žádný obecný participant SecurityContext ani alternativní autentizační store nevzniká.

### 10.1 JSON DTO (200 application/json)

```json
{
  "registrationId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "runId": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
  "activationGeneration": 1,
  "deviceGeneration": 1,
  "version": 0,
  "status": "RUNNING",
  "individualStartedAt": "2026-11-01T09:00:00Z",
  "eventStatus": "RUNNING",
  "endAt": "2026-11-01T16:00:00Z",
  "currentStage": {
    "id": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    "sequenceNo": 1,
    "name": "První stanoviště",
    "type": "STANDARD",
    "activatedAt": "2026-11-01T09:00:00Z",
    "instructionsMarkdown": "Přečtěte [návod](navod.md).",
    "instructionsRenderedHtml": "<p>Přečtěte <a href=\"/api/v1/race-participations/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/files/navod.md\" target=\"_blank\" rel=\"noopener noreferrer\">návod</a>.</p>",
    "requiredChecks": ["CODE", "GPS"],
    "satisfiedChecks": [],
    "files": [{
      "fileName": "navod.md",
      "contentType": "text/markdown",
      "byteSize": 200,
      "url": "/api/v1/race-participations/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/files/navod.md"
    }]
  },
  "totalPenaltyPoints": "0"
}
```

UUID jsou string; časy UTC ISO-8601 `Instant` s nejvýše milisekundovou přesností
(nulový zlomek může serializer vynechat). Version/generace jsou int64 JSON integers,
sequenceNo/byteSize int32. `totalPenaltyPoints` je přesný desetinný **string int64**,
včetně záporných hodnot. Klient nesmí ztratit přesnost verzí/generací. `endAt`,
`currentStage`, instrukce a activatedAt mohou být null podle uloženého stavu;
start je u platného průběhu povinný. Pole checks/files jsou vždy pole, případně prázdná.

`version` je race_run.version, nikoli registration.version nebo event.version.
Čtení žádnou verzi nezvyšuje. Celkové body nyní tvoří přesný součet již aplikovaných
race_points_adjustment, nikoli dosud neexistující herní penalizace. Při overflow
nevrací zaokrouhlenou hodnotu, ale 503. Žádné pořadí ani odhady dokončení.
Konání je určeno registrací; DTO nepřidává eventId ani administrativní metadata
nad původní kontrakt. `files` je minimální doplnění dříve neurčeného seznamu souborů
aktuálního stanoviště, včetně tranzitivně dosažitelných souborů. Pořadí dle existující
knihovny (name_key, id). ContentType a byteSize popisují uložený soubor, u Markdownu
nikoli velikost odvozené HTML stránky.

### 10.2 Výslovně povolená pole stanoviště

Všechny typy sdílejí pouze StageView z příkladu. Neexistuje samostatný neveřejný
popis v event_stage; veřejný obsah tvoří name a instrukce.

| Model | Participant projekce |
| --- | --- |
| STANDARD s CODE/GPS | requiredChecks obsahuje CODE a/nebo GPS v tomto pořadí; satisfiedChecks jen již potvrzené příznaky těchto kontrol. AND se nemění. |
| TIMED | Není samostatný uložený typ; STANDARD s timePenalty. Neodesílají se interní intervaly ani sazby. |
| DELAYED | Není samostatný uložený typ; časové pravidlo STANDARD. Neodesílá se interní minimum ani sazba. |
| INFORMATION | type INFORMATION; obě pole checks prázdná, instrukce/soubory beze zvláštních hodnoticích dat. |
| RADAR | type RADAR, requiredChecks GPS; bez cílových souřadnic, poloměru a sazby za měření. Měření zatím není endpointem tohoto API. |
| QUIZ | Nemá podporovaný perzistentní model, nelze ho vytvořit současnou konfigurací. Neznámý typ se odmítne 503, nikdy se neposílá hodnoticí klíč. |

Neposkytují se správné CODE varianty, hash, GPS cíle, interní konfigurace,
maximální/časové/radarové sazby, sourceTemplateStageId, budoucí stanoviště,
celý snapshot, poznámky, členové, tajné tokeny ani neveřejné výsledky.
Veřejné instrukce jsou organizátorem zadaný obsah; backend z jejich textu neodstraňuje
informace, které do nich organizátor vědomě vložil.

### 10.3 Soubory a bezpečný renderer

FileName je URL-encoded normalizovaný název, nikoli interní UUID nebo cesta.
Soubory zůstávají ve stávajícím soukromém EventFiles úložišti, nic se nekopíruje.
Povoleny jsou odkazy z ACTIVE, COMPLETED a skutečně aktivovaných SKIPPED stanovišť.
PENDING stanoviště nezpřístupní nic. Dosažitelnost se počítá ze snapshotu konání,
tranzitivně přes lokální odkazy Markdown dokumentů; cyklus se navštíví jednou.
Soubor sdílený s budoucím stanovištěm je přístupný, jen pokud k němu vede již povolený
odkaz. Nevrací se celá knihovna. Stejný název v jiném konání není náhradní cíl.

Relativní Markdown zůstává zachovaný. Explicitní lokální odkazy na event files/images
se ve vráceném Markdownu i renderovaném HTML převádějí na participant files cestu
(včetně reference-style definic a fragmentů); uložený snapshot se nepřepisuje.
Odvozené HTML používá existující parser a sanitizer. Vnější odkazy zůstávají dle
stávající politiky; nejde o proxy pro vzdálené soubory.

JPEG/PNG se vrací se svým MIME inline; PDF application/pdf jako attachment;
Markdown jako bezpečná text/html;charset=UTF-8 stránka s restriktivní CSP dle
TemplateDocumentHtml. Content-Disposition používá bezpečně zakódovaný původní
název. Všechny odpovědi mají no-store a no-referrer, obsah navíc nosniff a CSP.
Fyzický obsah se čte stávajícími bezpečnými operacemi (bez symlink/path traversal,
se stávající maximální velikostí); velikost a Markdown obsah musí odpovídat DB.
Stávající upload/import validace a limity se nemění.

Mobilní/webový klient musí **každé** stažení včetně obrázků autorizovat Race
hlavičkou, např. authenticated fetch a lokální blob/viewer. Obyčejné otevření URL
nebo img/a v prohlížeči samo nedoplní Authorization; není řešeno vložením tokenu
do URL ani novou cookie či rozšířením ADMIN endpointu. Klient zachová no-referrer
a soukromé zacházení s lokálně materializovanými soubory.

### 10.4 Stavy, zámky a chyby

RaceDeviceAccess.current je MANDATORY a používá stejnou kontrolu jako require.
Pod zámky event → registration → aktuální run → device znovu ověří SHA-256,
ACTIVE, vazbu registrace a aktuální generaci. Žádná user role či relace není
podmínkou. Read service ponechá zámky až do sestavení neměnného DTO/načtení byte[].
Pro soubory předchází DB zámkům existující souborový ochranný zámek, stejně jako
u EventService. Transakce nepoužívá SQL read-only režim, protože PostgreSQL v něm
nepovoluje FOR UPDATE; aplikačně neprovádí žádné INSERT/UPDATE/DELETE.

Při dříve autorizovaném čtení takeover/revoke počká na jeho dokončení. Po již
potvrzeném takeover/revoke starý token neautorizuje žádné další čtení. Odpověď,
která byla již sestavena před odvoláním, samozřejmě nelze odvolat ze sítě/klienta.
Neposílá se kombinace staré generace a nového postupu/bodů. Čtení drží stejné
zámky jako budoucí herní účinek, žádné vlastní zpracování herních pravidel nevzniká.

- PLANNED/neodstartovaná registrace nemá aktivované zařízení: 401 bez vytvoření run.
- RUNNING bez individuálního startu: rovněž 401, bez implicitního startu.
- Aktivní průběh: currentStage je právě ACTIVE stanoviště.
- ENDED: platné zařízení smí číst dosavadní stav a stejné dosažitelné soubory;
  eventStatus ENDED se neplete s run.status RUNNING. Čtení samo nefinalizuje
  průběh, nezapočítává penalizace a neslibuje dokončené výsledky.
- RESUME: eventStatus RUNNING dle existujícího lifecycle, run/start/generace se
  nerestartují. Efektivní stav používá serverový čas, GET nematerializuje přechody.
- COMPLETED: currentStage null, dříve dosažitelné soubory zůstávají dostupné.
  Run s participation_ended_at bez ACTIVE rovněž neposkytuje budoucí stanoviště.
- REVOKED bez následného převzetí: 401, žádný návrat k account autentizaci.

Chyby jsou standardní application/problem+json (code/status/title/instance).
401 RACE_DEVICE_INVALID je jednotné pro chybějící/poškozený/neznámý/cizí/revokovaný
Race token i neexistující registraci; existence cizí účasti se nerozlišuje.
S již platným vlastním zařízením může neplatný aktuální run vrátit 409
RACE_RUN_NOT_CURRENT. 404 RACE_FILE_NOT_FOUND je stejné pro neexistující,
nepovolený, budoucí nebo neplatně pojmenovaný soubor; předtím vždy autorizace.
400 INVALID_REQUEST pokrývá neplatný syntaktický UUID. 503 RACE_STATE_UNAVAILABLE
značí nekonzistentní snapshot/DB nebo overflow; 503 PHOTO_STORAGE_UNAVAILABLE je
stávající chyba fyzického úložiště. Interní SQL, token ani hash se nevrací.
Čtení nevyžaduje klientem zadaný run/gen/version a nevytváří idempotentní zápisy.

Etapa4/4 přidává ParticipantReadService/ParticipantController a úzké propojení
s existující account security a privacy filtrem. Bez migrace, bez změn V29.
Maven verify bez PostgreSQL: BUILD SUCCESS, 894 testů (19 nových unit/HTTP),
včetně kompilace integračních testů. JSON příklad §10 je syntakticky validní
a git diff --check prošel. Nové testy a 12 PostgreSQL scénářů v CrewStartIT pokrývají whitelist,
izolaci, soubory/cykly, zámky, stavy, logout a převod kapitána. Uživatel potvrdil
BUILD SUCCESS cíleného CrewStartIT po opravě nastavení Mockito spy: i stubbing
metody s MANDATORY přes Spring proxy probíhá v TransactionTemplate; vlastní
paralelní operace mají transakce skutečných služeb. Produkční kód ani V29
se při této opravě neměnily. Celá PostgreSQL sada tím není nově ověřena.
Herní pokusy/progrese, plná evidence radarových měření
skutečného průběhu a Q4 zůstávají mimo implementaci.
