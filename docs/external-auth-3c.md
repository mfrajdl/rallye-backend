# Etapa 3c – externí přihlášení a správa identit

Stav k 24. 10. 2026: analýza dokumentačně uzavřena; D01–D08 schváleny uživatelem. Protokolový základ 3c.1 je dokončen a automaticky otestován; stav ověření a konkrétní API jsou v [external-auth-3c1.md](external-auth-3c1.md). Migrace V8 je připravena, úspěšný PostgreSQL běh již potvrdil uživatel. Webové přihlášení a registraci přidává implementačně dokončená a lokálně otestovaná 3c.2 (verify 271 testů, uživatelem potvrzený PostgreSQL BUILD SUCCESS a Postman 16: 6/6). Skutečné přihlášení Google/Apple a pozitivní Postman 17 zůstávají neověřené do schváleného HTTPS prostředí; podrobné ověření a omezení jsou v [external-auth-3c2.md](external-auth-3c2.md). Správa/propojování identit, konfigurace skutečných poskytovatelů a externí smoke zatím neproběhly. Funkční základ: [specifikace 0.7.0](Zavody_specifikace_v0.7.0.md), která zachovává pravidla 0.6.0 a přidává rozhodnutí 3c. Technické návrhy níže nejsou tvrzením o existujícím API.

## Schválená rozhodnutí

| ID | Závazné pravidlo |
| --- | --- |
| 3c-D01 | E-mail při první registraci je ověřený jen při spolehlivém potvrzení validovaným údajem Google/Apple; jinak vlastní ověření. Shoda adres nikdy automaticky nespojuje účty. |
| 3c-D02 | Chybějící povinné jméno nebo použitelný e-mail doplní uživatel v dokončovacím kroku. Doplněný e-mail se ověřuje vlastní cestou. Před dokončením nevzniká plná relace; chybějící údaje při dalších přihlášeních nic nepřepisují. |
| 3c-D03 | Pro propojení a vlastní zrušení účtu nové ověření heslem nebo již propojenou externí identitou. Účelově vázané jednorázové oprávnění platí 5 minut; samotná session nestačí. Tiché SSO není automaticky důkaz čerstvosti. |
| 3c-D04 | Součástí 3c je přidání LOCAL hesla externímu účtu, propojení i odpojení identit a zákaz odstranění posledního použitelného způsobu. Obnova hesla LOCAL identitu nevytváří. |
| 3c-D05 | Nejvýše jedna LOCAL, jedna GOOGLE a jedna APPLE identita na interní účet; externí identita je nadále globálně unikátní. |
| 3c-D06 | Uchovávat pouze nezbytný Apple revokační prostředek, šifrovaně a s omezeným přístupem. Zrušení včetně administrátorského vede k revokaci; při výpadku bezpečně opakovat bez obnovení účtu. Lokální transakce a síťové volání jsou oddělené. |
| 3c-D07 | Skutečný Apple test vyžaduje samostatnou HTTPS testovací doménu. Nyní se nic nezřizuje; simulované testy nejsou skutečné ověření poskytovatele. |
| 3c-D08 | Společný backendový OAuth2/OIDC authorization code tok v systémovém browseru; autorizační stav 10 minut, mobilní předávací kód 60 sekund, jednorázově a s PKCE S256. Jen explicitně povolené návraty; Bearer nikdy v URL. |

Administrátorská cesta rušení nadále ověřuje aktuální oprávnění administrátora, nikoli heslo rušeného uživatele. Návrh D03 pro tuto cestu je čerstvě ověřit jednajícího administrátora stejným mechanismem; tento technický návrh nemění zákaz rušení sebe přes ADMIN endpoint ani invariant posledního ADMIN.

## Existující základ a hranice

- JDBC moduly identity/api, identity/application a identity/persistence, Spring Security a neprůhledné databázové relace. OAuth2 Client zatím není závislostí.
- user_identity již povoluje LOCAL/GOOGLE/APPLE a UNIQUE(provider, issuer, subject), LOCAL má částečný unikátní index na account_id. Nové UNIQUE(account_id, provider) doplní D05; před migrací ověřit případné duplicity, nic automaticky neslučovat ani nemazat.
- AccountStore načítá LOCAL heslo přes LEFT JOIN; účet bez něj může existovat, ale současné create() vždy zakládá místní registraci. Nová cesta nesmí vkládat fiktivní heslo.
- ACTIVE účet vyžaduje použitelné osobní údaje podle existujících omezení, přihlašovací e-mail je unikátní. Neúplný externí profil proto držet odděleně v krátkodobém registračním kontextu, nikoli vytvářet neplatný účet nebo obcházet omezení.
- AuthService zachová 24 hodin / 30 dní nečinnosti, cookie a Bearer kontrakt, reset všech a změnu ostatních relací. Přidání hesla není reset; politika relací při správě identit je technický bod níže.
- AuthSessionFilter čte aktuální účet a ADMIN z DB. Externí claims nedávají ADMIN; kapitánství i historie zůstávají na interním UUID.
- LocalCancellationIdentityCheck dnes ověřuje jen heslo. Externí ověření a nový kontrakt musí zachovat současné místní použití a bezpečnou autorizaci.
- AuditService má MANDATORY transakci. CommandStore má společný ledger, 24 hodin a uzavřené formáty kontrolované CommandAccounts/CommandCleanupStore. Žádné heslo, kód poskytovatele, ID/access/refresh token ani reautentizační důkaz do ledgeru.
- PreRaceAccountParticipationFacts přijímá přesné V1–V6/V7. Novou konkrétní migraci výslovně přidat i s kontrolou jména a negativními testy; nepovolit neznámé vyšší verze.

Mimo rozsah: změna přihlašovacího e-mailu, slučování dvou existujících účtů, závody, vlastní autorizační server, nové Rallye JWT/refresh tokeny a cloudový deployment. Jednorázové mobilní předání není refresh token.

## Tok, data a bezpečnostní invarianty

Navržené účely: LOGIN, LINK, REAUTHENTICATE. Oddělit běžné přihlášení od důkazu nové identity pro citlivou operaci. Všechny časy v UTC; expirace je striktní, při now >= expires_at odmítnout.

1. Zahájit tok s poskytovatelem z pevného seznamu, klientským režimem, rememberMe a návratem z konfigurace. Pro LINK/REAUTHENTICATE vázat cílové UUID a původní platnou relaci na serveru.
2. Vygenerovat state/nonce a potřebný protokolový materiál. Stav platí 10 minut, nesmí jej prodloužit callback ani opakování.
3. Autorizační kód vyměnit knihovním OAuth2 Client/OIDC adaptérem. Ověřit podpis a povolený algoritmus, issuer, konkrétní audience/azp dle toku, časy, nonce, state a vazbu na správného poskytovatele/klienta. PKCE k poskytovateli jen dle skutečně ověřené podpory, nezaměňovat s povinným PKCE mobilního předání.
4. Síťové volání neprovádět při držení admin_control ani zámku účtu. Krátká konečná transakce znovu ověří stav účtu, relaci a spotřebu kontextu; dodrží existující pořadí zámků control -> account -> další prostředky. Unique constraints a podmíněné UPDATE chrání souběh. Po unikátním konfliktu nepokračovat v abortované transakci.
5. Známá externí identita používá stejné interní UUID. DISABLED/ANONYMIZED účet se nepovolí. Neznámá identita s kolidujícím e-mailem vyžaduje přihlášení k existujícímu účtu a explicitní propojení, bez úniku zbytečných informací o účtu a bez slučování.
6. První registrace musí mít jméno a ověřený použitelný e-mail. Vlastní ověření využije pravidla 24hodinové aktivace, ale krátkodobý OIDC stav tím nezískává 24hodinovou platnost. Dokončovací kontext má uživatelem schválených 15 minut od ověřeného callbacku, bez prodlužování; viz external-auth-3c2.md.
7. Vydání Rallye relace a LOGIN_SUCCEEDED audit tvoří jednu DB transakci. Web dostane stávající HttpOnly cookie, mobil až po výměně kódu stejný Bearer kontrakt. Při rollbacku žádná platná session ani částečné propojení.

Použít rozšíření Spring Security, vlastní krátkodobé úložiště autorizačních požadavků a success/failure adaptéry. Nezavádět implicitně trvalou HttpSession nebo serializovaný SecurityContext vedle user_session. Technické API ověřit proti verzi spravované skutečným Boot parentem.

### Apple callback a web

Podporovat přesnou callback metodu a formát včetně form_post. Pouze tento protokolový callback může mít cílenou CSRF výjimku; ostatní stavové operace webu zůstávají chráněné CSRF. State/nonce a korelace se zahájeným browserem jsou povinné i pro anonymní LOGIN (ochrana před login CSRF).

RALLYE_SESSION a XSRF cookie mají dnes SameSite=Strict; nelze očekávat jejich přítomnost v cross-site POST. Technický návrh: samostatná krátkodobá Secure/HttpOnly SameSite=None korelační cookie, ne oslabení přihlašovací cookie. Podporu jejího doručení a náhradní postup při blokaci ověřit v browseru; bez korelace odmítnout bezpečně. U LINK znovu ověřit původní relaci v DB i bez její cookie na callbacku. Samotný state bez vazby na klienta nestačí.

Jméno z Apple prvního callbacku je profilový vstup, nikoli důkaz identity. Validovat limity a zacházet s ním jako s nedůvěryhodným textem; e-mail pro rozhodnutí o ověření číst z validovaného důkazu, nikoli volného formulářového JSON. Chybějící další jméno ani odlišný externí e-mail nesmí automaticky přepsat účet.

### Čerstvé ověření a správa identit

Navržený společný důkaz: náhodné tajemství, v DB digest, účet, relace, konkrétní účel a případný cíl, čas ověření, expires_at a consumed_at. Životnost 5 minut od skutečně přijatého nového ověření. Spotřeba musí být atomická s propojením/odpojením, přidáním LOCAL nebo zrušením účtu; rollback důkaz nespotřebuje trvale. Odvolaná relace a změněný účet důkaz zneplatňují.

Pro LINK ověřit obě strany: vlastnictví existujícího účtu a samostatně novou identitu. Nová nepropojená identita nesmí sama potvrdit vlastnictví cílového účtu. Pro odpojení a přidání LOCAL navrženo stejné čerstvé účelové oprávnění. V transakci znovu spočítat použitelné identity; dva souběžné unlink nesmějí odstranit poslední způsob. Heslo 8–128 Unicode znaků, Argon2id; reset pouze již existujícího LOCAL.

Google dokumentuje požadavek na auth_time přes claims; to samo nezaručuje vynucení nové autentizace. prompt=consent ani select_account nepovažovat za důkaz čerstvého přihlášení. Ověřit podporované parametry a validovaný čas autentizace proti zahájení konkrétního toku a povolené malé odchylce hodin. iat nového ID tokenu není auth_time. U Apple není v této analýze prokázána spolehlivá serverově ověřitelná cesta vynucení čerstvosti pro společný webový tok. Do jejího ověření externí citlivý tok selhává bezpečně; nelze tiše změnit D03 ani považovat novou nonce za nové ověření uživatele.

### Mobil

Systémový browser provede společný backendový code flow. Jednorázový předávací kód má 60 sekund, hash v DB, vazbu na dokončený tok, klienta, rememberMe, návrat a PKCE S256 challenge. Mobil předá code + verifier přes HTTPS a atomicky získá novou user_session. Znovu ověřit existenci a ACTIVE stav účtu (zrušení může proběhnout mezi callbackem a výměnou). Záměna challenge, replay nebo expirace nevydá token. Do URL lze vrátit pouze předávací kód, nikdy Bearer; odpovědi no-store a bez citlivého logování. Při ztrátě odpovědi po spotřebování zahájit nový tok, neukládat otevřenou session do replay ledgeru.

## Návrh migrací a API

Migrace se nyní nevytvářejí. Předpoklad: V8 pro základ 3c.1, další nové verze jen podle nutného rozsahu pozdějších částí. V1–V7 zůstanou nedotčené. V8 má zahrnout UNIQUE(account_id, provider), krátkodobé kontexty a podklady pro reautentizaci/mobil i bezpečný úklid při rušení. Před přidáním omezení read-only preflight na duplicity; nalezené duplicity zastaví migraci, nesmějí být automaticky smazané.

Navržené datové oblasti (konkrétní tabulky až v implementačním návrhu V8):

- external_auth_request: účel, provider, klient, state/nonce a korelace, cílový účet/relace, challenge, povolený návrat, created/expires/consumed; nepřechovávat celý externí profil nebo token response.
- registration_completion: minimální nedokončený profil a ověřená externí vazba; žádná plná session. Pravidla expirace viz technické body.
- reauthentication_grant a mobile_handoff: hash jednorázového tajemství, vazby a jednorázová spotřeba.
- apple_revocation_credential a apple_revocation_job: šifrovaný nezbytný token, key ID a stav práce; pracovní návrh níže. Odstranění uživatele nesmí kaskádově odstranit nevyřízenou práci.

| Navržené API | Vstup / výstup |
| --- | --- |
| POST /api/v1/auth/external/{provider}/requests | rememberMe, klientský režim, identifikátor povoleného návratu, u mobilu challenge/S256; autorizační URL, bez tokenu session |
| GET/POST /api/v1/auth/external/{provider}/callback | Parametry poskytovatele dle podporovaného toku; bezpečný redirect nebo dokončovací krok, bez libovolného návratu |
| POST /api/v1/auth/external/registrations/completions | Omezený registrační důkaz a chybějící údaje; ověření adresy nebo dokončení, bez předčasného přihlášení |
| POST /api/v1/auth/external/exchanges | Mobilní code + verifier -> stávající token/tokenType/rememberMe |
| GET /api/v1/auth/identities | Bezpečný seznam vlastních způsobů, bez surového subjectu a tokenů |
| POST /api/v1/auth/reauthentications | Heslo nebo zahájení ověření již propojenou identitou, konkrétní účel -> oprávnění nebo pokračování toku |
| POST /api/v1/auth/identities/link | Provider a důkaz cílového účtu -> samostatný ověřený tok připojované identity |
| DELETE /api/v1/auth/identities/{identityId} | Vlastní identita + účelový důkaz, kontrola posledního způsobu -> 204 |
| POST /api/v1/auth/identities/local | Nové heslo + účelový důkaz -> 201, žádný resetový vedlejší efekt |
| POST /api/v1/auth/account-cancellations | Rozšířit současný kontrakt o externí účelový důkaz; zachovat bezpečné místní ověření |

Jde o návrh, ne existující endpointy. Metody, DTO a konečné error codes se uzavřou před implementací dané části. Navrženo 400 neplatný/expirovaný protokolový důkaz, 401 neplatné přihlášení, 403 zamítnutí účelu/oprávnění, 409 konflikt identity/posledního způsobu, 429 limiter, 503 nedostupné ověření. Callback chyby prezentovat přes pevnou bezpečnou návratovou stránku, ne odrazem údajů poskytovatele.

Pro aplikační vytváření identit/link/unlink použít stávající UUID Idempotency-Key a 24hodinový ledger, kde je relevantní: po aktuální autorizaci replay zkontrolovat před opětovnou spotřebou důkazu, otisk pouze z významových netajných parametrů. Reauth důkaz/heslo ani jeho náhradní hash nepatří do ledgeru. Protokolové callbacky/exchange chrání vlastní jednorázový stav, ne uložená tajná odpověď. Rozšířit uzavřené formáty, related_account_ids i úklid záznamů jiných aktérů; audit/retence nesmějí novými vazbami ztratit bezpečnost legacy dat.

## Apple revokační prostředek a zrušení účtu

Schváleno D06; následující je doporučený technický návrh k dopracování v 3c.1/3c.5:

- Uchovat jen prostředek potřebný pro pozdější revokaci (typicky refresh token) a minimální identifikaci správného client ID. Žádný celý token response ani trvalé ID/access tokeny bez doložené potřeby. Prostředek nepoužívat k prodlužování Rallye session.
- Autentizované šifrování prověřenou knihovnou, např. AES-GCM se správou unikátních nonce; key ID, ciphertext a potřebná kryptografická metadata. To není změna dříve odmítnutého šifrování idempotentního ledgeru.
- Klíče mimo Git/DB/logy, zpřístupněné jen běhové službě přes chráněné prostředí nebo připojený soubor tajemství. Oddělit šifrovací klíč od Apple podpisového privátního klíče a od Google client secret. Ztráta klíče nesmí být maskována přepsáním ciphertextu.
- Rotace: nový key ID pro nové zápisy, zachovat starý klíč do přešifrování živých i čekajících prostředků a ověření obnovy záloh. Zálohy šifrovat a omezit přístup; smazání živé hodnoty neznamená vymazání starých backupů. Před nasazením stanovit i retenci a obnovu záloh.
- Ve stejné DB transakci jako zrušení uložit trvalý revokační úkol a převést do něj nezbytný šifrovaný prostředek, odstranit identity, relace a citlivé kontexty, odpojit posádky a zapsat stávající audit. Úkol zůstává i po fyzickém smazání účtu, bez e-mailu/jména; nová registrace má jiné UUID a nepřebírá starou práci.
- Po commitu pracovník volá Apple mimo DB zámky. Nárokování práce krátkým lease/row lockem s expirací, aby crash umožnil retry jiné instance. Jedinečnost úkolu na konkrétní autorizační prostředek; ne na znovu registrovaný e-mail. Bez nové infrastruktury brokeru.
- Výpadky/síťové timeouty/5xx opakovat s omezeným exponenciálním odstupem a jitterem; konkrétní intervaly jsou technický parametr. Úspěch nebo prokázané již odvolání -> odstranit ciphertext i nepotřebné vazby. Crash po vzdáleném úspěchu před lokálním potvrzením se vyřeší bezpečným opakováním revokace.
- Neznámé chyby/invalid_client/ztráta klíče nejsou důkaz revokace: uchovat chráněnou práci, bezpečně signalizovat provozní problém, po opravě umožnit opakování. Účet zůstává zrušený. Nikdy jej kvůli retry neobnovit a neuchovávat celý účet.
- Retence: prostředek po dobu aktivní vazby a po zrušení pouze do prokazatelného dokončení revokace; následně bezodkladně odstranit. Nezavádět automatický limit, který zahodí nevyřízený token a předstírá úspěch. Dlouhodobě nevyřízenou práci provozně sledovat; případné výjimečné opuštění vyžaduje explicitní rozhodnutí, ne tichý timeout.
- Návrh auditu: IDENTITY_LINKED, IDENTITY_UNLINKED, LOCAL_IDENTITY_ADDED, REAUTHENTICATION_SUCCEEDED a APPLE_REVOCATION_QUEUED/SUCCEEDED/FAILED s bezpečnými UUID/provider/error kategorií. Neúspěšné pokusy o login zůstávají pouze bezpečné provozní logy. Revokační audit není FK na smazaný účet a neukládá token, ciphertext, klíč, subject ani citlivou odpověď Apple. Lokální obchodní audit je transakční; vzdálené dokončení je samostatná následná transakce.

Souběh opětovného přihlášení/propojení stejné Apple identity s dosud čekající revokací musí mít explicitní bezpečný postup před 3c.5 (viz technické body); starý úkol nesmí nečekaně zrušit novou autorizaci. Odpojování Apple identity má navržené použití stejného revokačního mechanismu; rozsah ověřit před 3c.3.

## Implementační části a kritéria dokončení

### 3c.1 – protokolový základ a bezpečnostní infrastruktura

- Rozsah: konfigurace Google/Apple vypnutá bez explicitního zapnutí, knihovní OIDC validace, V8 a omezení D05, krátkodobé kontexty, rozhraní čerstvého ověření a revokačního úložiště. Žádné automatické povolování externí registrace v nedokončeném stavu.
- DB/backend/API: nové JDBC operace a návrhové DTO; správný SecurityFilterChain/callback matcher, rozšíření přesného seznamu Flyway migrací a bezpečného úklidu. Připravit kontrakty výše, ne tvrdit jejich veřejnou funkčnost před dalšími částmi.
- Unit/HTTP: state/nonce/issuer/audience/podpis/algoritmus/expirace, chybné klientské vazby a návraty, žádné tajné logy, CSRF mimo callback, nedostupné klíče a provider timeout. Simulovaní poskytovatelé bez sítě.
- PostgreSQL: upgrade V7, omezení identity, jednorázová spotřeba, rollback a souběhy; výslovné povolení V8, odmítnutí V9/neznámého skriptu; úklid při rušení.
- Manuálně: Postman validace vstupů a bezpečných chyb; skutečná konfigurace až po schválení prostředí. Protokolový průzkum čerstvosti nesmí být zaměněn za úspěšný test.
- Hotovo: verify bez DB úspěšné, IT potvrzené uživatelem, protokolové invarianty a konkrétní fallback/block podmínky doložené. Závislost: D01–D08, technické prověření knihovních API.

### 3c.2 – web, první registrace a opakovaný login

- Rozsah: Google i Apple, D01/D02, doplnění profilu, vlastní ověření adresy, stávající rememberMe, cookie a audit loginu.
- DB/backend/API: registrační kontext a dokončení/ověření, tvorba účtu bez LOCAL, callback a návraty; nová migrace jen pokud V8 nestačí, bez přepsání V8.
- Bezpečnost: před ověřením žádná plná session; shodný e-mail nespojuje účty; profil není autorizační důkaz; Apple form_post korelace a přesná CSRF výjimka.
- Unit/HTTP: verified/unverified/missing claims, Apple první jméno a chybějící další jméno, kolidující ACTIVE/PENDING e-mail, limity textů, DISABLED/ANONYMIZED, cookie atributy a absence citlivých odpovědí.
- PostgreSQL: souběžný první login jedné identity, kolize e-mailu, dokončení/aktivace právě jednou, rollback účet/identita/session/audit, zrušení během callbacku.
- Manuálně: browser první/další login obou providerů, odmítnutý souhlas a relay; Postman /me/logout a lokální ověření adresy. Simulace Apple neuzavírá skutečný smoke.
- Hotovo: doložené automatické testy a ruční výsledky odděleně, bezpečný nedokončený stav, žádná regrese 2A. Závislost: 3c.1 a životnost dokončovacího kontextu.

### 3c.3 – nové ověření a úplná správa identit

- Rozsah: 5minutové účelové oprávnění, link/unlink, seznam, LOCAL přidání a ochrana posledního způsobu. Navrženo použít oprávnění i pro unlink/LOCAL a čerstvě ověřit aktéra ADMIN rušení.
- DB/backend/API: grant a atomická spotřeba, endpointy identities/reauthentications, případné nové formáty ledgeru s related_account_ids, audit provideru bez tokenů. Zachovat současné místní cancellation použití.
- Unit/HTTP: odlišný účel/účet/relace/cíl, expirace a replay, nové SSO bez důkazu čerstvosti odmítnout, cílová nová identita nedokazuje existující účet; reset nevytváří LOCAL.
- PostgreSQL: souběžné link do dvou účtů a dvou identit téhož provideru, dva unlink posledních způsobů, rollback grantu/auditu/ledgeru; úklid osobních údajů a expirace 24 hodin.
- Manuálně: browser propojení jiné adresy i kolize, Postman seznam/unlink/LOCAL/negativní reauth, login každým zbývajícím způsobem. Ověřit čerstvost skutečným providerem, nikoli jen mockem.
- Hotovo: D03–D05 platí v souběhu, existující účty nemění UUID/role/vlastnictví. Pokud provider neumí doložit čerstvost, závislé citlivé operace nejsou hotové. Závislost: 3c.1–2 a vyřešená čerstvost.

### 3c.4 – mobilní předání

- Rozsah: systémový browser, 60sekundový code, PKCE S256, allowlist návratů a výměna za dosavadní Bearer; rememberMe přeneseno ze serverového kontextu.
- DB/backend/API: hash kódu, challenge, atomický exchange a session/audit, žádné uložené otevřené session v idempotenci.
- Unit/HTTP: špatný verifier/challenge/metoda, otevřený redirect, nesprávný klient, přesná expirace, smíšené cookie/Bearer; no-store a žádný Bearer v URL.
- PostgreSQL: dvě výměny právě jeden vítěz, rollback, zrušení účtu mezi callbackem a exchange, odvolání relace původního LINK/REAUTH toku.
- Manuálně: Postman exchange/negativní replay, skutečná aplikace ověří app/universal links a browser. Postman sám nedokazuje mobilní integraci.
- Hotovo: jednorázové předání bez změny 24h/30d pravidel a bezpečné odmítnutí všech záměn. Závislost: 3c.1–2, pro správu identit i 3c.3.

### 3c.5 – rušení, Apple revokace a regrese

- Rozsah: vlastní externí zrušení, administrátorská cesta, atomický úklid všech nových kontextů a trvalá práce pro Apple; žádné obnovení účtu při síťové chybě. Doplnit dokumentaci a Postman, zachovat 3b historii a posledního ADMIN.
- DB/backend/API: cancellation proof rozšíření a kompatibilita LOCAL, šifrovaný revokační prostředek/práce, pracovník s lease/retry; potřebné nové migrace přes přesný allowlist účastí. Veřejné revokační/diagnostické API není automaticky součástí.
- Unit: chybné klíče, rotace, šifrování s integritou, retry a bezpečné logy, dočasné/trvalé chyby poskytovatele, nulový token v ledgeru/auditu.
- PostgreSQL: rollback celé lokální cancellation včetně enqueue, přežití úkolu po DELETE, ADMIN i anonymizace, restart/lease/souběžní pracovníci, crash po síťovém úspěchu, následný login nesmí obejít čekající revokaci; retence auditu nesmí smazat pracovní zdroj pravdy.
- Manuálně: browser čerstvé ověření a zrušení; Postman 204/401, nová registrace bez práv, ADMIN zákaz sebe a posledního ADMIN; skutečná revokace Apple až v HTTPS prostředí. Neničit skutečné účty, používat výslovně určené testovací účty.
- Hotovo: verify, uživatelem potvrzené rallye_test IT, zaznamenané skutečné smoke Google/Apple/mobil nebo výslovně otevřená integrace; neoznačit celou 3c za ověřenou jen podle mocků. Závislost: 3c.1–4 a uzavřené revokační technické body.

## Předpoklady skutečného testování (nyní bez zřizování)

1. Samostatná HTTPS doména a certifikát, pevná callback URI; schválené oddělené prostředí a testovací účty/data. Apple web redirect nepovoluje localhost/IP. Profil local nesmí být kvůli tomu vystaven do sítě; později navrhnout samostatnou konfiguraci testovacího prostředí.
2. Google projekt/OAuth klient, consent screen a testovací uživatelé, registrovaný redirect; client secret mimo Git. Bez nepotřebných scopes nebo offline Google přístupu.
3. Apple Developer oprávnění, App ID/Services ID a potřebné asociace, registrovaná doména/návrat, Team ID, Key ID a privátní klíč; generování a rotace client-secret JWT. Konkrétní nastavení ověřit v aktuální oficiální dokumentaci při zprovoznění.
4. U Apple relay adres nakonfigurovat povolené odesílání a ověřit doručení, pokud aplikace posílá e-mail; skutečný SMTP poskytovatel zůstává úkol před nasazením. Žádná nevyžádaná infrastruktura nyní.
5. Šifrovací klíče a zálohy, přístup jen služby, odchozí HTTPS na token/JWK/revocation endpointy, správný čas serveru, důvěryhodná konfigurace proxy a veřejného originu.
6. Mobilní identifikátory a asociace app/universal links, explicitní seznam návratů a test na skutečných zařízeních; odlišit webové a mobilní client ID/audience.
7. Postman a browser testují aplikační tok; automatické U/HTTP/PG používají simulovaného poskytovatele. PG pouze uživatelem přes `.\mvnw.cmd -Pit verify` a TestDatabaseInitializer proti rallye_test; nikdy rallye_dev/rallye. Žádné externí toky v běžném verify.

## Zbývající technické body a pořadí

D01–D08 nejsou otevřené obchodní otázky. Před zahájením každé závislé části se musí dopracovat:

1. **Čerstvost Google/Apple – blokuje dokončení 3c.3 a vlastní externí rušení.** Prokázat konkrétní serverově validovatelný mechanismus, ne pouze nový token/nonce či obrazovku souhlasu. Varianty: doložená čerstvá autentizace poskytovatele; již existující LOCAL/jiný spolehlivý způsob; pokud provider nic takového nezaručí, předložit rozhodnutí o jiné silné cestě, ne ji svévolně zavést. Doporučení: izolovaný průzkum v 3c.1, pro nedoloženou cestu fail closed. E-mailový fallback ani povinné přidání hesla zatím nejsou schválené náhrady.
2. **Apple korelace callbacku – technická brána 3c.2.** Ověřit samostatnou cookie a vazbu na původní relaci při form_post i při blokaci cookies. Bezpečné odmítnutí je přijatelné, přihlášení bez klientské vazby není.
3. **Dokončovací kontext – uzavřeno při zahájení 3c.2.** Uživatel schválil 15 minut od ověřeného callbacku bez prodlužování, potom nové externí ověření. Po odeslání profilu se případná vlastní aktivace řídí stávajícími 24 hodinami. Desetiminutový OAuth state se neprodlužuje. Uživatel rovněž schválil minimální šifrované Apple úložiště již v 3c.2; revokační volání/worker/retry zůstávají v 3c.5.
4. **Životní cyklus revokace – před 3c.3/3c.5.** D06 vyřešilo povinnost a šifrování, nikoli konkrétní lease/retry a souběh nové autorizace. Konzervativní návrh: do prokazatelného dokončení staré revokace nepovolit nové připojení stejné Apple autorizace; pro korelaci může být dočasně nutný chráněný subject, nikoli e-mail. Rozsah nezbytného retenovaného údaje a dopad na opětovnou registraci předložit před implementací. Nic nesmí obnovit původní účet ani přenést jeho práva. Pro unlink navržen stejný revokační proces.
5. **Politika relací a citlivé operace – před 3c.3.** D03 konzistentně navrženo pro unlink/LOCAL a aktéra ADMIN cancellation; určit, které ostatní relace odvolat při změně způsobů. Nezaměňovat tuto novou politiku s již schváleným resetem (všechny) a změnou hesla (ostatní). Schválená pravidla zůstávají beze změny.
6. **Klíče a provozní hodnoty – před skutečným prostředím.** Umístění tajemství, key IDs, rotace/zálohy, retry intervaly, monitoring a konkrétní doména/client ID jsou konfigurační a provozní úkoly, ne důvod znovu otevírat D06/D07. Chybné konfigurace mají externí tok/revokaci bezpečně zablokovat.

Část 3c.1 je nyní dokončena a automaticky ověřena podle external-auth-3c1.md. Původní doporučení pro její zahájení bylo splněno: základ, constraints, bezpečné kontexty a simulované testy nejsou závislé na zřízení domény. Nelze předem slíbit plnou externí reautentizaci ani skutečný Apple smoke bez uvedených důkazů a konfigurace. Zahájení implementace není autorizováno touto dokumentací samotnou.

## Oficiální podklady

Ověřeny při dokumentační analýze 24. 10. 2026; skutečnou podporu konkrétního klienta je nutné ověřit před implementací závislé cesty.

- [Spring Security OAuth2 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html): extension points autorizačních požadavků a OIDC validace.
- [Google OIDC reference](https://developers.google.com/identity/openid-connect/reference): prompt a vyžádání auth_time; výběr účtu není důkaz čerstvosti.
- [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect): subject, audience a validace identity.
- [Apple authorization](https://developer.apple.com/documentation/signinwithapplerestapi/request-an-authorization-to-the-sign-in-with-apple-server.): callback a HTTPS redirect.
- [Apple TN3194](https://developer.apple.com/documentation/technotes/tn3194-handling-account-deletions-and-revoking-tokens-for-sign-in-with-apple): rušení účtů a revokace.
- [Apple revocation](https://developer.apple.com/documentation/signinwithapplerestapi/revoke-tokens): revokace tokenu a opakování již zneplatněného tokenu.
