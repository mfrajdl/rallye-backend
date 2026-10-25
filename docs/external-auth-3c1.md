# 3c.1 – protokolový základ externí autentizace

> Aktuální pravidla: [specifikace 0.8.0](Zavody_specifikace_v0.8.0.md), D09 nahrazuje D04/D05 a D10 nahrazuje D03. Účet má jedinou neměnnou LOCAL/GOOGLE/APPLE identitu; link/unlink a dodatečný LOCAL jsou zakázané. LOCAL vlastní zrušení vyžaduje znovu heslo, externí zrušení platnou relaci a samostatné výslovné potvrzení, nikoli čerstvou reautentizaci. Níže uvedené dokončené etapy a výsledky zachycují tehdejší implementaci; DB omezení je nyní připravené v 3c.3a s uživatelem potvrzeným PostgreSQL ověřením BUILD SUCCESS; externí potvrzení je nyní implementované v [3c.3b](account-cancellation-3c3b.md), s uživatelem potvrzeným PG ověřením BUILD SUCCESS. Dopady a nový plán: [external-auth-3c.md](external-auth-3c.md).


Stav: **3c.1 dokončena a automaticky otestována**. Verify prošlo s 250 testy; PostgreSQL ověření úspěšně potvrdil uživatel. Specifikace 0.7.0 a D01–D08 se nemění. Toto není dokončené přihlašování Google/Apple.

Následující text zachovává rozsah a výsledky dokončené 3c.1. Aktuální obchodní zpracování webového callbacku přidává [3c.2](external-auth-3c2.md): 303 redirect, omezená registrace nebo vydání stávající webové session. Původní 204 protokolový callback popsaný níže je historický stav 3c.1.

## Co je implementováno

- Spring Security OAuth2 Client (verze spravovaná Boot parentem), knihovní výměna authorization code a Nimbus ověření RS256 ID tokenu. Pevné issuer/token/JWK/authorization endpointy Google a Apple; žádná discovery podle uživatelského URL.
- Validace podpisu, issueru, audience/azp, expirace, iat, subjectu a nonce. Timeout připojení 5 s a čtení 10 s pro token/JWK komunikaci. Nezpracované chyby poskytovatele se nelogují ani neposílají klientovi.
- GOOGLE/APPLE jsou bez explicitní konfigurace vypnuté. Neexistují výchozí credentials. Nastavení a vlastní protokolové objekty mají redigované toString; knihovní ClientRegistration se nesmí logovat (obsahuje secret).
- POST requests vytváří kontext LOGIN/LINK/REAUTHENTICATE na 10 minut. LINK/REAUTH vyžadují aktuální ACTIVE účet a stále platnou původní relaci, ověřované pod existujícím pořadím zámků. Klient nemůže zadat jiné cílové UUID.
- State, nonce a browserová korelace jsou náhodné 256bitové hodnoty, v DB jen SHA-256 digesty. Google code flow navíc používá PKCE S256; verifier je deterministicky odvozen s odděleným účelem z tajné korelační cookie a není uložen otevřeně v DB. PKCE k Apple se bez ověřené podpory nepředstírá.
- Korelační cookie RALLYE_OIDC_GOOGLE/APPLE je Secure, HttpOnly, SameSite=None, scoped na přesný callback, Max-Age 600. RALLYE_SESSION ani XSRF cookie se nezmírňují. Další zahájení stejného poskytovatele v témže cookie jaru nahradí korelaci a předchozí tok nedokončí.
- GOOGLE callback GET, APPLE callback POST; CSRF výjimka jen pro přesný POST Apple callback. State+nonce+cookie chrání login CSRF. Chybějící nebo duplicitní korelace se odmítne. Všechny ostatní webové stavové endpointy zachovávají CSRF.
- Po validním LOGIN důkazu se stav atomicky spotřebuje. Síťová výměna je mimo DB transakci a zámky; finální spotřeba podmíněným UPDATE dovolí jen jednoho vítěze. Chyba důkazu stav nedokončí, replay po úspěchu je odmítnut. Dva souběžné callbacky mohou oba zkusit token endpoint, ale nedokončí kontext dvakrát.
- LINK/REAUTH kontexty jsou evidovány a korelovány, jejich callback vrací EXTERNAL_FRESH_AUTH_UNAVAILABLE (503). Žádná nonce nebo nové iat se nevydává za důkaz čerstvého ověření. Závislé obchodní operace nejsou implementované.

Žádný callback nevytváří účet, identitu, Rallye session, předávací kód ani reautentizační oprávnění. Úspěšný protokolový callback vrací 204 bez těla; nejde o přihlášení. Proto nevytváří LOGIN_SUCCEEDED ani IDENTITY_LINKED audit. Tyto transakční události vzniknou až společně se skutečnou obchodní operací v 3c.2/3c.3. Limity pokusů používají existující LoginThrottle, oddělené namespace external-start a external-callback.

## Databáze a návaznosti

V8__external_auth_foundation.sql nemění V1–V7. Přidává UNIQUE(account_id,provider); existující duplicity migraci zastaví bez odstranění dat. Globální UNIQUE(provider,issuer,subject) zůstává.

external_auth_flow ukládá účel, vázaný účet/relaci, digesty, pevný callback/návrat, rememberMe, budoucí mobilní challenge a přesně 10 minut platnosti. CHECK odlišuje anonymní LOGIN od vázaných účelů. FK ON DELETE CASCADE uklidí kontexty při odstranění relací/účtu. AccountCancellationStore.deleteSessions navíc explicitně odstraní kontexty a granty účtu, včetně budoucího MOBILE grantu bez vazby na relaci; anonymizační větev tak nic neponechá.

external_auth_grant je zatím pouze databázový základ: MOBILE má hash, PKCE challenge a přesně 60 sekund; REAUTH vazbu na relaci a přesně 5 minut. Žádný aplikační writer/consumer ani veřejný exchange v této etapě neexistuje. Budoucí handoff/grant nevytváří druhý ledger.

PreRaceAccountParticipationFacts výslovně přijímá jen původní podporované posloupnosti nebo přesnou V8 s názvem skriptu; neznámé V8/V9 nadále vracejí 503. Rušení účtů a ochrana posledního ADMIN se nemění. Do CommandStore nepřibyla nová operace ani tajný payload, jeho cleanup/24h retence nejsou změněné.

Apple revokační úložiště a worker, dokončovací registrace, přidání LOCAL a správa identit jsou navazující části. V8 neukládá žádný dlouhodobý Apple token. Aktuální výměna token response pouze validuje a zahodí; před zapnutím skutečného přihlašování musí navazující implementace zajistit D06.

## API a manuální ověření

POST /api/v1/auth/external/GOOGLE/requests (nebo APPLE), JSON:

```json
{"purpose":"LOGIN","returnId":"web","rememberMe":false}
```

Volitelně mobileChallenge obsahuje 43 znaků base64url S256 challenge, nikoli verifier. Pro LINK/REAUTHENTICATE se používá již přihlášená cookie/CSRF nebo Bearer relace. Pro anonymní mobilní zahájení platí stávající X-Rallye-Client: mobile pravidlo; tento browserový protokol přesto vyžaduje návrat korelační cookie. Skutečné propojení mobilu/browseru je úkol 3c.4.

Odpověď 200 obsahuje authorizationUrl, expiresAt a stage=PROTOCOL_ONLY, korelace je jen v cookie. Žádná session ani token v odpovědi. Neotevírat vrácenou URL proti skutečným poskytovatelům v této etapě.

GET /api/v1/auth/external/GOOGLE/callback?state=...&code=...; Apple POST na odpovídající callback s form parametry. Vyžaduje původní korelační cookie, správnou metodu a validní kontext. Apple user profil ani provider error redirect dosud nejsou obchodně zpracovávané.

Chyby: 400 EXTERNAL_STATE_INVALID/EXTERNAL_PROOF_INVALID/EXTERNAL_CALLBACK_INVALID, 503 EXTERNAL_PROVIDER_DISABLED/EXTERNAL_CONFIGURATION_INVALID/EXTERNAL_FRESH_AUTH_UNAVAILABLE, standardní 401/403/429. Neúspěšné síťové ověření má bezpečnou obecnou EXTERNAL_PROOF_INVALID; diagnostické rozlišení výpadku od odmítnutí poskytovatelem zbývá před skutečným smoke. Bezpečnost má přednost před zveřejněním odpovědi poskytovatele.

Bez skutečných credentials lze v lokálním Postmanu ověřit:

1. Backend local podle stávajícího návodu, provider konfiguraci ponechat vypnutou. Toto nezřizuje HTTPS doménu ani nemění bind.
2. GET /api/v1/auth/csrf, zachovat cookie jar a vrácený token pro X-XSRF-TOKEN.
3. Výše uvedený POST s CSRF -> 503 EXTERNAL_PROVIDER_DISABLED; nesmí vytvořit kontext či session.
4. Stejný POST bez CSRF -> 403. Nesprávné tělo s platným CSRF -> 400. Ověřit, že původní místní login/me/logout fungují.
5. GOOGLE callback s neplatným state/code -> 400; Apple POST callback s neplatným kontextem -> 400 i bez běžného CSRF (protože jeho ochrana je protokolová). Nikdy se nevytvoří session.

Pozitivní ověření bez sítě zajišťují testy se simulovaným verifierem a lokálními RSA klíči. Postman nyní nemá pozitivní mock-provider infrastrukturu; test nesmí být vydáván za skutečný Google/Apple login. Browserová kompatibilita SameSite=None/form_post, HTTPS a Apple konfigurace zůstávají neověřené.

## Budoucí konfigurace (nyní ji nezapínat proti poskytovatelům)

Použité property klíče:

- rallye.auth.external.providers.GOOGLE.enabled/client-id/client-secret/callback-uri
- rallye.auth.external.providers.APPLE.enabled/client-id/client-secret/callback-uri
- rallye.auth.external.returns.web (explicitní HTTPS návrat)

Callback musí být HTTPS a odpovídat skutečné API cestě. Apple nesmí být localhost/IP. Client secret a Apple client-secret JWT předávat pouze mimo Git. Generování/rotace Apple client-secret JWT z privátního klíče bude dopracováno před 3c.2; statická hodnota pro tento protokolový adaptér není produkční správa klíčů. Provider konfiguraci nesmí ovládat HTTP klient. Scope Google openid/email/profile, Apple email/name (bez nepodporovaného openid scope).

## Ověření

Codex skutečně spustil Maven Wrapper verify s Microsoft JDK 21.0.12.1 (procesové MAVEN_OPTS Xmx192m, testovací JVM Xmx384m/ActiveProcessorCount=2): **BUILD SUCCESS, 250 testů, 0 failures, 0 errors, 0 skipped**, JAR sestaven. Log je v ignorovaném target/verify-3c1.log. Codex nové IT zkompiloval, ale nespouštěl; následný úspěšný běh potvrdil uživatel. Unit/HTTP ověřují mimo jiné skutečně podepsané lokální RSA JWT a odmítnutí chybného klíče/issueru/audience/nonce/expirace, oddělenou cookie a přesnou CSRF výjimku; všechny existující testy bez DB prošly.

ExternalAuthIT obsahuje **10 scénářů**: souběžná spotřeba i celý protokolový callback, přesná expirace a rollback, V8 allowlist, pozitivní upgrade V7 a odmítnutí duplicit bez ztráty dat, cancellation kontextů a grantů, anonymizace se sessionless MOBILE grantem, absence nového účtu/session/login auditu a DB omezení. Uživatel následně potvrdil úspěšné `.\mvnw.cmd -Pit verify` proti rallye_test (BUILD SUCCESS), včetně těchto scénářů. Jde o uživatelem potvrzený výsledek, nikoli vlastní PostgreSQL běh Codexu; celkový počet IT uživatel neuvedl. Žádná skutečná Google/Apple konfigurace nebo síťový tok nebyly použity.

PostgreSQL testy spouští výhradně uživatel:

```powershell
.\mvnw.cmd -Pit verify
```

Pouze rallye_test přes TestDatabaseInitializer. ExternalAuthIT používá vlastní náhodné schéma, chrání cleanup kontrolou databáze/role/schématu, testuje souběžnou spotřebu, přesnou expiraci, rollback a upgrade s konfliktními duplicitami. Produkční ani vývojová DB se nepoužívá. Uživatel potvrdil úspěšný integrační běh; automatické ověření 3c.1 je dokončené. Skutečné externí a browserové ověření tím není doložené.


## Změněné soubory implementace 3c.1

Níže je přesný seznam implementačních změn; již rozpracovaná dokumentační analýza 3c (specifikace 0.7.0 a odkazy v dřívějších dokumentech) zůstává zachována a není novou implementací.

- pom.xml
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/AuthConfiguration.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/ExternalAuthController.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/ExternalProviders.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/ExternalFlowService.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/ExternalOidcVerifier.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/ExternalFlowStore.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/PreRaceAccountParticipationFacts.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/AccountCancellationStore.java
- src/main/resources/db/migration/V8__external_auth_foundation.sql
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalFlowTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalOidcTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalAuthHttpTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalAuthIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/PreRaceAccountParticipationFactsTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/AccountCancellationServiceIT.java
- docs/external-auth-3c1.md
- docs/external-auth-3c.md
- docs/backend-plan.md
- docs/testing.md
- AGENTS.md
- README.md

Nedotčené: .gitignore, docs/Rallye_specifikace.md, starší vydání specifikace a migrace V1–V7. Bez commitu/pushe, žádná cloudová nebo databázová operace Codexu.
