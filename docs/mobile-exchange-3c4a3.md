# 3c.4a.3 – atomická výměna mobilního handoff grantu

Stav: implementačně dokončeno a lokálně ověřeno. Základ: specifikace 0.8.0, D09–D13 a [schválený mobilní návrh](mobile-auth-3c4.md). Uživatel potvrdil nový PostgreSQL běh této podetapy jako BUILD SUCCESS; nejde o přenesený výsledek 3c.4a.2 ani běh Codexu. Skutečný Google/Apple callback, HTTPS, App/Universal Links, profilová stránka a platformní klienti nebyli ověřeni ani nasazeni.

## API

`POST /api/v1/auth/external/mobile/exchanges`, `Content-Type: application/json`, `X-Rallye-Client: mobile`.

```json
{
  "code": "<jednorázový handoff kód z callbacku>",
  "requestId": "<UUID původního mobilního pokusu>",
  "codeVerifier": "<původní mobilní PKCE verifier>"
}
```

Kód musí mít 43 znaků base64url bez paddingu. Verifier má 43–128 ASCII znaků `[A-Za-z0-9._~-]` podle PKCE; výpočet je BASE64URL(SHA256(ASCII(verifier))) bez paddingu. Není to provider PKCE verifier, OAuth code ani launch ticket. RequestId je korelace, nikoliv autentizace. Název vstupního pole je **codeVerifier**, což zpřesňuje pracovní označení verifier ve starším návrhu.

Úspěch `200 OK`:

```json
{"token":"<neprůhledný Rallye token>","tokenType":"Bearer","rememberMe":false}
```

JSON odpovídá již existujícímu LOCAL mobilnímu přihlášení. Používá se `user_session`, žádný JWT, refresh endpoint ani druhý systém relací. RememberMe pochází z původního pokusu; klient jej v exchange nemění. Nečinnost 24 hodin / 30 dní, prodlužování použitím podle AuthService. Bez rememberMe drží mobil token jen v paměti aktuální relace aplikace. Odpověď neobsahuje Set-Cookie ani Location; token není v URL. Použití: Authorization: Bearer na `/api/v1/auth/me` a stávajícím `/logout`.

| Stav | Kód a význam |
| --- | --- |
| 400 | INVALID_REQUEST – chybějící/neplatný formát JSON, UUID, kódu/verifieru, chybějící mobilní hlavička nebo přítomná Rallye session cookie či jakákoli Authorization. |
| 400 | EXTERNAL_HANDOFF_INVALID – jednotně neznámý kód, cizí requestId/verifier, expirace/replay, neexistující/neaktivní účet, neplatná vazba pokus/flow/identita. Bez údaje, která část nesouhlasí. |
| 429 | TOO_MANY_ATTEMPTS, Retry-After: 900 – stávající bounded LoginThrottle, 50 syntakticky platných exchange pokusů za 15 minut na zdroj a proces. Neslibuje distribuovaný limit více instancí. |
| 415 | UNSUPPORTED_MEDIA_TYPE. |
| 500/503 | Stávající bezpečná obecná serverová chyba; žádný token ani DB diagnostika klientovi. |

Endpoint je veřejný jen pro POST. Mobil nepotřebuje CSRF cookie ani anonymní přihlášení; stávající výjimka vyžaduje explicitní nesafelisted mobilní hlavičku. Časný privacy filtr odmítne session cookie a Authorization ještě před autentizačním filtrem, aby nedošlo k obnově browserové cookie nebo použití cizí relace. Ani cookie s platným CSRF nenahrazuje mobilní PKCE. Ostatní endpointy, zejména browserové dokončení registrace, zachovávají CSRF. CORS wildcard se nezavádí.

## Transakce, čas a souběh

1. Validace formátu a nezamykající lookup grantu podle SHA-256 hashe.
2. Společný `admin_control` zámek, aktuální účet FOR UPDATE, teprve potom grant FOR UPDATE a opakovaná validace. Není hrana grant → účet; stejné pořadí používá cancellation.
3. Grant je MOBILE/LOGIN, přísluší requestId a původnímu ACTIVE účtu s jedinou externí identitou. Prověří se spuštěný pokus, provider, challenge a již spotřebovaný LOGIN flow se stejným rememberMe/návratem/deadline. Nehledá se podle e-mailu a grant se nepřevádí na novou registraci.
4. Grant nesmí být spotřebovaný, budoucí ani expirovaný. Čas UTC v milisekundách; při now == expires_at již neplatí. Desetiminutová autorizace se při exchange znovu neprodlužuje ani nevyžaduje jako stále platná: grant vydaný těsně před jejím koncem má svých samostatných 60 sekund.
5. Podmíněný UPDATE consumed_at, INSERT běžné hashované user_session a LOGIN_SUCCEEDED tvoří jednu transakci. Žádné protokolové tajemství se neukládá do CommandStore. Odpověď se vrací až po commitu.

Dva exchange požadavky mají právě jeden úspěch, druhý bezpečnou chybu; replay nevrací původní token. Ztracená úspěšná odpověď vyžaduje nový mobilní tok. Chyba po skutečném INSERT relace či auditu rollbackne spotřebu, relaci i audit; retry může fungovat pouze do původní expirace. Neplatné PKCE grant nespotřebuje, ale podléhá throttlingu.

Cancellation první: grant zmizí a účet neexistuje nebo je anonymizovaný, exchange nevytvoří relaci. Exchange první: následující cancellation odstraní také právě vytvořenou relaci. To neznamená, že dříve vydaný token lze po cancellation stále použít. Apple ciphertext se nemění ani nemaže. Aktivní identita je chráněna V10; provozní porušení invariantu se dál řeší bezpečnou serverovou chybou.

## Databáze a provoz

**Bez nové migrace. V1–V12 beze změny.** V8/V10/V12 již mají hash/expiraci/consumed_at a UNIQUE FK attempt_id; V11 obsahuje spuštěný flow a rememberMe. Používají se existující session/audit tabulky a přesný migration guard.

Privacy filtr nyní chrání i exchange včetně chyb před controllerem: Cache-Control: no-store a Referrer-Policy: no-referrer. Vstupní i výstupní DTO mají redigované toString; do auditu jde pouze běžný LOGIN_SUCCEEDED a interní UUID. ApiErrors zachovává bezpečný typ/SQLSTATE/stack bez zpráv s tajemstvími.

Rozšířený HTTP test odhalil, že DEBUG/TRACE `ExceptionHandlerExceptionResolver` může vypsat rejected value či původní zprávu výjimky navzdory bezpečnému handleru. Startup guard proto navíc odmítá DEBUG/TRACE tohoto loggeru a CommonsRequestLoggingFilter (i explicitních child loggerů). MVC TRACE pro DispatcherServlet a body processor zůstává testované s redigovanými DTO; neznamená povolení payload logování. Zachován path-only Tomcat access log a zákaz connector wire debug. Logger úrovně se nesmějí za běhu přepnout na zakázanou konfiguraci; startup guard není průběžný monitor logování.

Reverse proxy, APM, vlastní request filtry a mobilní klient nesmějí zaznamenávat request/response bodies, Authorization, Cookie, code ani verifier. Při produkčním provozu je nutné HTTPS. Externí infrastruktura není tímto krokem konfigurována ani ověřena.

## Testy

- Unit: PKCE RFC vektor, formát, shoda vazeb, chyby/expirace/replay, pořadí zámků, conditional consume, idle 24h/30d.
- HTTP: anonymní výměna bez CSRF, zákaz cookie/Authorization, formát, jednotná chyba, throttle, privacy hlavičky i před controllerem, redakce vstupu/výstupu a neočekávané chyby. Regrese browser CSRF a startup log guard.
- `MobileExchangeIT`: 15 nových PostgreSQL testů, včetně GOOGLE/APPLE a obou rememberMe režimů, skutečného start/launch/callback s mock providerem, HTTP exchange/me/logout, replay/PKCE/záměny pokusů, hranice 60s, platného grantu po konci autorizace, neaktivních účtů, porušených vazeb, souběžného exchange, DELETE/anonymizace a Apple materiálu, souběhu s cancellation, rollbacku po skutečném INSERT session/auditu a retry.
- IT používají náhodné vlastní schéma, TestDatabaseInitializer a explicitní rallye_test guard. Mockito chyby se vkládají do skutečného cíle spy za transakční proxy, nikoli voláním MANDATORY proxy mimo transakci.

PostgreSQL testy spouští pouze uživatel podle AGENTS.md:

```powershell
.\mvnw.cmd -Pit verify
```

Výsledek nového běhu uživatel potvrdil jako **BUILD SUCCESS**. Simulovaný provider v IT neprokazuje skutečnou Google/Apple interoperabilitu.

## Postman

**24 Mobile exchange 3c.4a.3 - LOCAL negative checks:** spustit celou složku na localhost s vypnutými providery. Osm neinvazivních požadavků; bez účtů, session, skutečného grantu, cookies nebo CSRF. Syntetický neznámý kód očekává EXTERNAL_HANDOFF_INVALID, chybný formát/hlavičky INVALID_REQUEST. Kolekce má vypnutý Cookie Jar a redirecty. Pozor na 50 pokusů / 15 min při opakovaném spouštění.

**25 Mobile exchange 3c.4a.3 - PREPARED HTTPS live callback:** připraveno, nikoli provedeno. Pouze jednotlivé požadavky:

1. Nakonfigurovat HTTPS providera a schválené mobilní cíle; vybrat soukromé Postman environment, nastavit baseUrl (HTTPS), mobileProvider, mobileReturnId a vědomě mobileBootstrapPrepared=true. Existující účet musí mít tuto externí identitu a být ACTIVE.
2. Spustit E2E start: vytvoří nezávislý náhodný verifier a S256 challenge; verifier zůstává pouze v environment. RequestId a browserStartUrl se uloží do stejného prostředí.
3. Otevřít mobileBrowserStartUrl přímo v systémovém browseru. **Nespouštět launch v Postmanu**, jinak se ticket spotřebuje v jiném Cookie Jar. Dokončit skutečný provider callback. Nový účet místo grantu projde browserovým dokončením registrace; pak celý mobilní start zopakovat podle D12.
4. Pouze v soukromém prostředí nastavit mobileHandoffCode a mobileCallbackRequestId z návratového odkazu, ověřit shodu s mobileRequestId. Neopisovat kód/verifier do konzole/chatu ani exportovaného environment. Bez této přípravy skript záměrně nahlásí nesplněný předpoklad a přeskočení není úspěch.
5. Do 60 sekund zavolat E2E exchange: 200/tokenType Bearer, žádná cookie/redirect. Token se uloží pouze do environment mobileBearerToken. Očekávané rememberMe je false z tohoto startu.
6. Replay: 400 EXTERNAL_HANDOFF_INVALID. Bearer me: 200. Logout pouze této vlastní relace: 204. Old Bearer: 401 INVALID_CREDENTIALS. Poslední test odstraní citlivé environment hodnoty.

Celou složku 25 nespouštět automaticky v runneru bez browserového přerušení; nepředstírá pozitivní lokální mock endpoint. Sdílený collection pre-request prefix 3c4a3 odděluje tyto požadavky od cookie/CSRF a regressionSessionToken režimu. Cookie Jar je vypnutý na každém novém požadavku. Žádné rušení účtu ani jiné destruktivní Postman operace se nepřidávají.

## Omezení a další krok

PostgreSQL běh uživatel potvrdil jako BUILD SUCCESS. Uživatel potvrdil Postman 24 jako All Passed. HTTPS/provider smoke, App Links/Universal Links a hotová profilová stránka zůstávají závislosti 3c.4b. Android/iOS ani Apple revokace 3c.5 se neimplementují. Žádný commit, push, deployment či operace proti aplikačním databázím.

Ověření 3c.4a.3 (Codex, 2026-10-25): Maven Wrapper / Microsoft JDK 21.0.12.1, `verify` bez DB – **BUILD SUCCESS, 335 testů, 0 failures/errors/skipped**, JAR sestaven. Zahrnuje 18 nových testů bez DB a zkompilovaných 15 MobileExchangeIT; PostgreSQL se nespouštělo. Postman Collection v2.1 prošla standardním JSON parserem, validací proti oficiálnímu lokálnímu schématu a syntaktickou kontrolou všech 222 JavaScript skriptů. Postman požadavky nebyly spuštěny. `git diff --check` bez chyb; uživatelské .gitignore a docs/Rallye_specifikace.md zachovány.

## Soubory této podetapy

- docs/mobile-exchange-3c4a3.md
- docs/mobile-auth-3c4.md
- docs/backend-plan.md
- docs/testing.md
- postman/Rallye-Auth-2A.postman_collection.json
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/MobileExchangeController.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/AuthConfiguration.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/LaunchTicketPrivacyFilter.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/LaunchLoggingConfiguration.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/MobileExchangeService.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/MobileGrantStore.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/MobileExchangeTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/MobileExchangeHttpTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/MobileExchangeIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/api/LaunchTicketPrivacyTest.java

### Uživatelské PostgreSQL ověření 3c.4a.3

Uživatel po dokončení implementace potvrdil `.\mvnw.cmd -Pit verify` proti `rallye_test`: **BUILD SUCCESS**, včetně nových MobileExchangeIT. Jde o uživatelem provedený běh, nikoli vlastní spuštění Codexem; celkový počet testů nebyl sdělen. Postman složky 24/25 ani skutečné HTTPS/Google/Apple end-to-end ověření tímto potvrzením ověřeny nejsou. Bez commitu a pushe.

### Uzavření 3c.4a.3

Etapa je implementačně dokončena a lokálně ověřena. Codex: verify bez DB BUILD SUCCESS, 335 testů, validní Postman Collection v2.1/222 skriptů a git diff --check. Uživatel: PostgreSQL verify BUILD SUCCESS včetně MobileExchangeIT a Postman složka 24 All Passed. Skutečné Google/Apple přihlášení přes HTTPS a pozitivní Postman složka 25 dosud ověřeny nejsou; nevyvozují se ze simulovaných providerů. 3c.4b ani 3c.5 se nezahajují. Starší záznamy o čekajících testech popisují průběh před tímto potvrzením.
