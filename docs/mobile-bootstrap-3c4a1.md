# 3c.4a.1 – mobilní pokus a browser launch

Stav: implementačně dokončeno a lokálně ověřeno v rozsahu 3c.4a.1; verify bez DB po bezpečnostní revizi **BUILD SUCCESS, 306 testů, 0 failures/errors/skipped**, Microsoft JDK 21.0.12.1. Uživatel potvrdil PostgreSQL `.\mvnw.cmd -Pit verify` proti rallye_test: **BUILD SUCCESS**. Codex PostgreSQL testy nespouštěl. Žádný skutečný poskytovatel ani HTTPS prostředí nebyl použit. Základ: [schválený návrh D11–D13](mobile-auth-3c4.md), specifikace 0.8.0 a dokončená 3c.3b.

## Implementované API

### Mobilní start

`POST /api/v1/auth/external/GOOGLE/requests` (nebo APPLE), veřejný, `Content-Type: application/json`, `X-Rallye-Client: mobile`, bez Authorization a bez RALLYE_SESSION cookie:

```json
{
  "purpose": "LOGIN",
  "returnId": "android",
  "rememberMe": false,
  "mobileChallenge": "<43znaková base64url S256 challenge vytvořená mobilem>"
}
```

200 obsahuje `stage=MOBILE_BOOTSTRAP`, UUID `requestId`, `browserStartUrl` s jednorázovým ticketem a `expiresAt`. Neobsahuje token/nonce/korelaci/provider URL a nenastavuje žádnou session či korelační cookie. V DB vznikne pouze mobilní pokus. PKCE verifier se neposílá; requestId není přihlašovací prostředek. Challenge musí mít přesně 43 znaků base64url (bez paddingu), jiné účely než LOGIN neexistují.

Webová větev bez mobile hlavičky a challenge zachovává dosavadní odpověď authorizationUrl/expiresAt/stage=WEB_LOGIN a browserovou korelační cookie. Webový POST dál potřebuje CSRF; mobile hlavička nesmí obejít CSRF u cookie požadavku. Mobilní větev odmítá kombinaci se session cookie/Authorization (400 AMBIGUOUS_CREDENTIALS); challenge bez mobile hlavičky odmítá 400 INVALID_REQUEST. Mobilní návraty se nečtou z webové returns mapy.

### Browser launch

`GET /api/v1/auth/external/mobile/launch?ticket=<jednorázový ticket>` je veřejný. Přijímá právě jeden ticket a žádné další query parametry. Úspěch 303 na pevně daný autorizační endpoint poskytovatele s OAuth state/nonce a dosavadním provider PKCE; v prohlížeči nastaví pouze `RALLYE_OIDC_GOOGLE` nebo `RALLYE_OIDC_APPLE`, Secure/HttpOnly/SameSite=None a přesnou callback cestu. Max-Age nepřekročí zbývající dobu autorizačního pokusu. Nezakládá účet ani Rallye session a nevydává grant. Výsledek je no-store a Referrer-Policy: no-referrer.

Mobil otevře browserStartUrl v systémovém prohlížeči. Právě tam nyní vznikne cookie; nepředpokládá se přenos cookies z mobilního HTTP klienta. APPLE URL zachovává response_mode=form_post. GOOGLE provider PKCE se odvozuje z nezávislé browserové korelace, nikoli z mobileChallenge.

Chyby: 400 EXTERNAL_MOBILE_REQUEST_INVALID pro chybný/neznámý/expirovaný/spotřebovaný ticket, 400 INVALID_REQUEST při chybějícím nebo vícenásobném parametru či chybném vstupu; 503 EXTERNAL_PROVIDER_DISABLED / EXTERNAL_CONFIGURATION_INVALID při nedostupné konfiguraci; 429 dle existujícího limiteru. Neplatný launch nenastaví korelační cookie a nepřesměruje na dodanou URL.

Callback je **záměrně mimo implementovaný rozsah**: validní mobilní flow při pokusu o dokončení zůstává EXTERNAL_MOBILE_UNAVAILABLE (503) před provider výměnou. Není nový exchange endpoint, 60s grant ani Bearer session. D12 profilový cíl a D13 fallback jsou uložené a validované, jejich použití při mobilním callbacku/návratu následuje v další podetapě. Nejde o hotové přihlášení.

## V11 a transakční chování

`V11__external_mobile_login.sql` přidává pouze mobile_login_attempt: UUID, provider, mobilní challenge, rememberMe, returnId a snapshot povoleného návratu/profilu/fallbacku, SHA-256 hash náhodného 32bajtového ticketu, created_at/expires_at, launch_consumed_at a jedinečný flow_id FK. Ticket vzniká existujícím SecretTokens/SecureRandom, v DB nikdy není otevřeně. Omezení vynucují přesných 10 minut, synchronní NULL stav flow/spotřeby a spotřebu před expirací. Nová tabulka nemění účty, identity ani webové flow. Původní V1–V10 jsou beze změny.

Launch v jediné aplikační transakci nejprve SELECT FOR UPDATE podle hashe, neexpirovaného a nespotřebovaného pokusu. Ověří stále povolenou konfiguraci; změnu cíle od startu bezpečně odmítne. V téže transakci vytvoří flow a podmíněně nastaví spotřebu + flow_id. Expirace se kontroluje znovu při UPDATE. Druhý souběžný launch po odemčení řádku již nenajde nespotřebovaný pokus. Jakékoli selhání rollbackne nový flow i spotřebu; cookie a redirect controller odešle až po úspěšném návratu transakční proxy. Při ztracené odpovědi po commitu je nutný nový pokus.

Flow přebírá původní created_at/expires_at: přechod do browseru desetiminutovou lhůtu neprodlužuje. now >= expiresAt je neplatné. Start ani launch nemají vazbu na přihlášený účet, nemění V10 a neprovádějí identity lookup. Nový FK nezpůsobuje kaskádu do Apple materiálu.

Přesný PreRaceAccountParticipationFacts guard přijímá V11 pouze se schváleným názvem a odmítá neznámý název či V12. Future fixtures AccountCancellationServiceIT i ExternalAuthIT jsou posunuty na V12, aby nekolidiovaly s nyní existující V11. Nejsou povoleny libovolné budoucí migrace.

Odchylka pouze v členění schváleného návrhu: celková 3c.4a předpokládala také nové vazby handoff grantu. Tato V11 je podle užšího zadání jen bootstrap; external_auth_grant se nemění. Potřebné grant vazby musí následující podetapa dodat případnou další migrací; již aplikovanou V11 nesmí přepisovat. Není nový scheduler ani automatický úklid, protože životní cyklus navazujících grantů zatím není implementovaný.

## Konfigurace a logování

Nová serverová konfigurace je standardně prázdná; nic se samo nezapíná. Příklad budoucí externí konfigurace (zástupné domény, ne pokyn k nasazení):

```properties
rallye.auth.external.mobile.launch-uri=https://backend.example.test/api/v1/auth/external/mobile/launch
rallye.auth.external.mobile.returns.android.return-uri=https://app.example.test/mobile/return
rallye.auth.external.mobile.returns.android.profile-uri=https://web.example.test/profile
rallye.auth.external.mobile.returns.android.fallback-uri=https://app.example.test/mobile/fallback
```

Vedle toho musí být platně nakonfigurován a explicitně zapnut příslušný provider podle external-auth-3c1.md. Všechny nové adresy musí být konkrétní HTTPS URI bez userinfo, query a fragmentu; launch má přesnou API cestu. Nejsou odvozeny z Host/X-Forwarded-* ani klientského redirect_uri. Pouhé rozpoznání provider typu jej nezapne. S vypnutými providery start vrátí EXTERNAL_PROVIDER_DISABLED bez vytvoření pokusu.

Aplikační kód ticket/state/nonce/verifier ani autorizační URL neloguje. DTO obsahující návratová tajemství mají redigované toString; globální ApiErrors zachovává bezpečnou diagnostiku bez zpráv obsahujících tajemství. Ticket je krátkodobé bootstrap tajemství v URL, nikoli přístupový či obnovovací token. Launch a start posílají no-referrer/no-store. Před skutečným HTTPS ověřením je nutné zajistit také redakci query a Location v proxy/access logách, žádné request-detail/HTTP wire debug logování či analytiku na těchto cestách. Tato podetapa neřídí externí proxy ani logy mobilu/Postmanu; jejich chování není lokálními testy prokázáno. Do chatu ani exportu kolekce neposílat runtime URL/cookies.

## Ověření

- Cílené unit/HTTP testy: BUILD SUCCESS, 41 testů.
- Celé Maven verify bez profilu it: BUILD SUCCESS, **296 testů** bez selhání/chyb/přeskočení; PostgreSQL IT byly zkompilované, nikoli spuštěné.
- Nové MobileLoginServiceTest: start/hash/deadline, validace, konfigurace a její změna, vazba challenge/rememberMe a neúspěšná spotřeba.
- ExternalAuthHttpTest: veřejný mobilní start/launch, security hlavičky/cookies, žádná session, webová regrese, CSRF a zamítnutí duplicitních/neočekávaných parametrů.
- Nové MobileLoginIT: **10 scénářů zahrnutých v uživatelem potvrzeném úspěšném PostgreSQL běhu**, skutečná DB/služby/proxy a simulovaný verifier bez sítě. Hash-only start, zachované lhůty/challenge, replay, přesná expirace, souběh, rollback+retry, Apple form_post a nedokončený callback, V11 guard, DB CHECK a upgrade V10 s existujícím webovým flow.

Podle AGENTS.md PostgreSQL testy spouští uživatel v chráněném prostředí pouze proti rallye_test:

```powershell
.\mvnw.cmd -Pit verify
```

Uživatel následně potvrdil nový běh `.\mvnw.cmd -Pit verify` s výsledkem BUILD SUCCESS. Integrační ověření 3c.4a.1 včetně V11, expirace, souběhu a rollbacku je tím potvrzené v testovaném prostředí. Celkový počet testů tohoto PostgreSQL běhu nebyl sdělen. Nejde o běh Codexu ani o převzetí výsledků 3c.3b. Postman 21 uživatel potvrdil jako All Passed, také po následných bezpečnostních opravách. Skutečné HTTPS/provider ověření a pozitivní Postman 22 zůstávají nepotvrzené.

## Postman – bez destruktivních operací

Importovat aktualizovanou kolekci, nastavit baseUrl; nová protokolová větev společného skriptu se vztahuje jen na prefix `3c4a1 `, odstraňuje autentizační hlavičky a nevyžaduje předchozí Bearer relaci ani browser CSRF. Ostatní složky zachovávají dosavadní přípravu.

**21 Mobile bootstrap – LOCAL disabled providers:** spustit celou pouze s vypnutými Google/Apple. Šest požadavků: dva starty -> 503 EXTERNAL_PROVIDER_DISABLED, neplatný/missing/duplicate ticket -> 400, chybějící challenge -> 400. Cookie Jar je u těchto požadavků vypnutý. Žádné přesměrování se nenásleduje; nevzniká účet/session ani pokus u vypnutého providera. Není třeba HTTPS/provider účet.

**22 PREPARED HTTPS:** až po samostatném povolení a přípravě HTTPS/provider a mobile konfigurace. Nastavit baseUrl na stejný HTTPS origin jako launch-uri, mobileProvider=GOOGLE nebo APPLE, mobileReturnId=android, mobileBootstrapPrepared=true. Spustit v pořadí start -> launch -> replay. Start uloží runtime browserStartUrl a requestId pouze do aktivního environmentu. Launch má zapnutý Cookie Jar a **vypnuté Follow redirects**, kontroluje 303 a zabezpečenou korelační cookie; replay musí vrátit 400 EXTERNAL_MOBILE_REQUEST_INVALID. Před spuštěním odstranit starou RALLYE_SESSION daného hostu, aby nebyla zaměněna za nově vydanou relaci. Veřejná RFC7636 fixture challenge slouží jen k bootstrap testu, není tajná a tato sada ji nevyměňuje za session. Neobsahuje přihlášení u skutečného providera ani exchange. Po ověření odstranit runtime mobileBrowserStartUrl/mobileRequestId z environmentu.

Samotný skutečný systémový browser/Android App Link a Google/Apple smoke zůstávají 3c.4b. Složku 22 nespouštět při běžném lokálním backendu s vypnutými providery a nevydávat neprovedené requesty za úspěšné.


## Soubory předložené ke kontrole

- docs/mobile-auth-3c4.md
- docs/mobile-bootstrap-3c4a1.md
- docs/backend-plan.md
- docs/testing.md
- postman/Rallye-Auth-2A.postman_collection.json
- src/main/resources/db/migration/V11__external_mobile_login.sql
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/AuthConfiguration.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/ExternalAuthController.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/ExternalFlowService.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/ExternalProviders.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/MobileAuthSettings.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/MobileLoginService.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/MobileLoginStore.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/PreRaceAccountParticipationFacts.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/MobileLoginServiceTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/MobileLoginIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalAuthHttpTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalAuthIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/PreRaceAccountParticipationFactsTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/AccountCancellationServiceIT.java

Součástí jsou dříve schválené rozpracované dokumenty návrhu. Uživatelské .gitignore a docs/Rallye_specifikace.md zůstaly beze změny. Žádný commit ani push.

Kontrola kolekce: Node JSON.parse a PowerShell ConvertFrom-Json, oficiální schéma Postman v2.1 a syntaxe 192 JS skriptů prošly. Porovnání s HEAD potvrdilo zachování všech původních složek, requestů a proměnných; původní collection příprava zůstává beze změny ve výchozí větvi, nový prefix má vlastní anonymní bootstrap přípravu. To není skutečný Postman běh. Git diff --check prošel.


## Následná bezpečnostní revize launch ticketu

[Zjištění a opravy](review-launch-privacy-3c4a1.md) doplňují časný privacy filtr pro chyby před controllerem, redakci downstream request logování, bezpečný Tomcat access-log vzor a startup guard proti nebezpečnému access/wire logování. Access logging se automaticky nezapíná. Změna bezpečnostní konfigurace může nově odmítnout start při nebezpečně přepsaném logování; povolený vzor a provozní hranice jsou v reportu. Po opravě celé verify bez DB: BUILD SUCCESS, 306 testů. Dřívější 296 testů výše označuje původní běh. Uživatel potvrdil také původní Postman 21; nová hlavičková očekávání uživatel následně ověřil opakováním celé složky 21: All Passed. Skutečné browser/proxy/Google/Apple ověření zůstává mimo tento běh. Podpora no-referrer je standardová záruka kompatibilního browseru, nikoli tvrzení o provedeném skutečném provider testu.

Další soubory bezpečnostní opravy:

- src/main/java/cz/mh/rallye/rallye_backend/identity/api/LaunchTicketPrivacyFilter.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/LaunchLoggingConfiguration.java
- src/main/resources/application.properties
- src/test/java/cz/mh/rallye/rallye_backend/identity/api/LaunchTicketPrivacyTest.java
- docs/review-launch-privacy-3c4a1.md

Již uvedené ExternalAuthController, ExternalAuthHttpTest, MobileLoginServiceTest a Postman kolekce obsahují příslušné úpravy/testy. V11 ani jiné migrace se bezpečnostní opravou nemění.


### Potvrzený stav při uzavření

PostgreSQL `.\mvnw.cmd -Pit verify`: uživatelem potvrzený BUILD SUCCESS před závěrečnými bezpečnostními opravami. Po nich Codex verify bez DB: BUILD SUCCESS, 306 testů. Postman 21 po opravách: uživatelem potvrzené All Passed včetně privacy hlaviček. Nový PostgreSQL běh po těchto opravách nebyl proveden; skutečné HTTPS/Google/Apple/browser ověření a složka 22 zůstávají odložené. Rozsah končí startem a launch; žádný grant/exchange/Bearer session ani 3c.4a.2.
