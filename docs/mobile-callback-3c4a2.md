# 3c.4a.2 – mobilní callback a vydání handoff grantu

Stav 2026-10-25: implementačně dokončeno a lokálně ověřeno, **verify bez DB BUILD SUCCESS, 317 testů, 0 failures/errors/skipped** (Microsoft JDK 21.0.12.1). PostgreSQL verify po opravě SingleIdentityIT uživatel potvrdil jako BUILD SUCCESS; Postman 23 jako All Passed. Žádné skutečné Google/Apple účty, síťové výměny ani HTTPS nasazení nebyly použity. Základ: specifikace 0.8.0, D09–D13 a mobile-auth-3c4.md; bootstrap 3c.4a.1 v commitu 16e9f50f64cae21df8ac3d2b2dc114027fba97a3.

## Callback a přesměrování

Používají se stávající cesty GET `/api/v1/auth/external/GOOGLE/callback` a POST `/api/v1/auth/external/APPLE/callback` (form_post). Úspěšný tok pokračuje z browser launch; mobilní requestId se nezískává z callback query ani nevydává za autentizační prostředek.

1. Časný privacy filtr nastaví no-store/no-referrer a skryje citlivé parametry před obecným servletovým logováním. Controller čte původní hodnoty pouze explicitním interním helperem. Zachovává požadovanou metodu, jedno state a právě jeden code/error, korelační cookie a dosavadní parsování Apple jména. Ostatní hlavičky/CSRF ani veřejné API matcher se nerozšiřují.
2. ExternalFlowService.verifyCallback používá původní ověření state, deadline, cookie korelace, callback URI, provider exchange, podpisu/issuer/audience/nonce. Síťová výměna probíhá mimo DB zámky. Provider PKCE Google je nezávislé na mobilní challenge; Apple adaptér se v tomto kroku nemění. Interní verifyWebCallback nadále odmítá mobilní flow, ale skutečný controller nyní přes sdílenou aplikační službu volá generic verifyCallback.
3. ExternalLoginService otevře společnou transakci a admin_control lock. MobileCallbackService ověří vazbu flow -> již spuštěný mobile_login_attempt, providera, challenge, rememberMe, návrat, expiraci a stále povolený snapshot return/profile/fallback URI. Mobilní flow bez odpovídajícího pokusu ani webové flow s podvrženou mobilní vazbou nesmí pokračovat.
4. Ve stejné transakci se znovu ověří a spotřebuje state, identita se hledá výhradně podle (provider,issuer,subject), účet se zamkne a musí být ACTIVE. Profil/e-mail z claims neurčuje účet, chybějící jméno jej nepřepisuje. Apple prostředek zachová stávající vault; chyba jeho uložení rollbackne celý výsledek.
5. Pro existující aktivní identitu vznikne pouze MOBILE/LOGIN grant: hash nového 32bajtového náhodného kódu, původní account_id, attempt_id a mobilní challenge. Přesná životnost je 60 sekund od created_at; expirace desetiminutové autorizace se před vydáním znovu ověří. session_id zůstává NULL. Nevzniká nová Rallye session ani LOGIN_SUCCEEDED; ten patří až k budoucímu exchange.
6. Po commitu backend vrátí 303 na přesnou povolenou návratovou adresu s query **code=<handoff>&requestId=<UUID>**. Kromě kódu a nezbytné korelace se zde nepředává nic dalšího. Není zde OAuth provider kód, access/refresh/ID token, Rallye Bearer ani profil. Kód není v těle či nové cookie. Korelační cookie se zneplatní. Existující nezávislá browserová relace není zdrojem identity tohoto toku; dosavadní auth filtr ji může běžným způsobem ověřit, ale callback nevytváří mobilní přihlášení.

Chyby zůstávají ve stávajícím bezpečném API formátu: 400 EXTERNAL_STATE_INVALID / EXTERNAL_PROOF_INVALID při vadném důkazu/korelaci/replay či nesouladu pokusu; 400 INVALID_REQUEST při chybějícím state; 401 INVALID_CREDENTIALS pro známý neaktivní účet; 503 konfigurace/provider/vault a 429 throttle podle dosavadních kontraktů. Neplatný důkaz nepřesměruje na klientskou URL. Oprávněně korelované provider odmítnutí spotřebuje state a vrátí pouze uložený návrat s external=failed a u mobilu requestId, bez raw error_description a bez grantu. Při chybě konfigurace se místo přesměrování bezpečně vrátí chyba.

## D12 – první registrace

Neznámá identita vytvoří pouze stávající omezený 15minutový external_registration a HttpOnly registrační cookie. U mobilního toku je cílem samostatná povolená profileUri s external=complete-profile; žádný grant ani session. GET registrations/current a POST registrations/completions v témže browser cookie jar zůstávají dosavadní API s povinným CSRF na POST. Doplnění má generic 202 bez informace o kolizi e-mailu a bez přihlášení; nový/doplněný e-mail se ověřuje vlastní 24hodinovou cestou podle D01/D02.

Po dokončení a případné aktivaci je nutný nový mobilní LOGIN s novým state/PKCE. Původní callback je spotřebovaný a nedá se povýšit na grant. Shoda e-mailu nikdy nepropojí existující účet s jinou identitou; zachován invariant V10. Chybějící profilová stránka je závislost skutečného browser end-to-end ověření, ne důvod k vydání grantu nehotovému účtu. Backend UI nepřidává a nevydává za hotovou navigaci z dosud neexistující profilové stránky zpět do aplikace.

## V12, souběh a cancellation

`V12__external_mobile_handoff.sql` rozšiřuje **existující** external_auth_grant o povinné attempt_id FK a UNIQUE(attempt_id). Jedno spuštění mobilního pokusu tak může vydat nejvýše jeden grant, nikoli nový při každém opakování. RequestId je právě ID tohoto pokusu; jeho unikátní flow_id z V11 váže grant na konkrétní flow. RememberMe a návrat jsou dohledatelné přes pokus, není duplicitní tabulka relací. Přesných 60 sekund již vynucuje V8 CHECK; V10 omezení MOBILE/LOGIN a účtů zůstávají.

Před přidáním povinné vazby migrace zamkne grant tabulku a **odmítne jakékoli existující nepřiřazené granty**, včetně expirovaných. Dosavadní produkční kód granty nevydával; přesto se skutečná data nepředpokládají ani automaticky nemažou. Pokud taková data existují, migrace skončí s bezpečnou diagnostikou bez osobních údajů a je potřeba samostatné rozhodnutí, nikoli automatické Flyway repair/clean. Staré migrační soubory V1–V11 se nemění. Přesný participation guard přijímá pouze očekávanou V12; neznámý skript či budoucí V13 se odmítají. Staré integrační fixtures grantů jsou explicitně doplněny o vlastní pokus a budoucí verze posunuty na V13.

Callback/registrace/cancellation používají společný admin_control zámek. Pod ním proběhne kontrola pokusu, jednorázový consume flow, lookup a zámek účtu, případný Apple ciphertext a insert grantu. Podmíněný consume a DB unikátnost zabrání dvojímu zápisu v souběžných callbackech. Provider může dva souběžné network exchange odmítnout i dříve; DB ochrana na tom nezávisí. Selhání grantu rollbackne také state a Apple zápis. Po DB chybě callbacku nelze u skutečného providera spoléhat na opakované použití jeho již spotřebovaného code; klient má zahájit nový tok.

Zrušení po vydání smaže grant přes stávající úklid účtu i při anonymizaci. Vyhraje-li cancellation dříve než callbacková DB transakce, starý účet nedostane grant. Pokud už byla jeho identita řádně odstraněna, pozdější validní proof může vést pouze k omezené **nové** registraci podle D09, nikdy k obnově starých práv. Grant se nepřeváže na nový účet se stejným e-mailem/subjectem. Výměna grantu a její ACTIVE kontrola budou až 3c.4a.3; nyní neexistuje endpoint, který by grant mohl spotřebovat či vytvořit Bearer.

## Privacy a provozní hranice

LaunchTicketPrivacyFilter se nově vztahuje také na oba přesné callbacky včetně chyb před controllerem a error dispatch. Pro downstream MVC/request logging rediguje query, parametry a Referer/Cookie, při enumeraci i Authorization. Ověření skutečných cookies a autentizačních hlaviček zůstává funkční. Response wrapper rediguje **pouze diagnostické čtení Location a Set-Cookie**; skutečné hlavičky doručené browseru zůstávají správné. DTO obsahující kód má redigované toString. ApiErrors nadále nepíše citlivé zprávy výjimek, jen bezpečnou diagnostiku. Grant se neukládá do auditu nebo CommandStore a nevzniká předčasný login audit.

Zachován bezpečný path-only Tomcat access-log pattern a startup guard z 3c.4a.1; nepovolovat common/combined, query/Referer/Location nebo wire debug. No-store/no-referrer je na callbacku také při 400/401/403/429/500/503. U kompatibilního browseru no-referrer brání přenosu callback URL při 303. Automatické HTTP testy ověřují hlavičky a úniky, nikoli skutečný Google/Apple browser smoke.

Reverse proxy/APM a HTTPS server obsluhující finální App Link/fallback musí také vynechat query, Referer a Location i záznam response body mobilního startu. Finální stránka musí mít no-store/no-referrer, bez analytiky či automatického exchange. Backend neovládá cizí domény, browser historii ani runtime logy mobilní aplikace/Postmanu. Tato konfigurace nebyla nasazena a externí logy nejsou ověřené; nezapínat diagnostické wire logování kvůli kopírování kódu. Kód je stále citlivý i při krátké životnosti a budoucí PKCE ochraně. Při neinstalované aplikaci se uplatní bezpečný HTTPS fallback na povolené infrastruktuře podle D13; backend zde nepřidává finální UI ani nové návratové endpointy.

## Testy

- Codex verify bez DB: **BUILD SUCCESS, 317 testů, 0 failures/errors/skipped**, JDK 21.0.12.1. Zahrnuje webové regrese, callback DTO, privacy/TRACE včetně skrytí Location/handoff kódu a původní Tomcat log test. PostgreSQL IT pouze zkompilovány.
- Nové MobileCallbackTest: konkrétní flow/pokus, PKCE/provider/rememberMe/expiry nesoulad, allowlist změna, hash-only vydání a opakovaná kontrola expirace při vydání. ExternalLoginServiceTest doplňuje mobilní známou/neznámou identitu bez session/auditu.
- Nové MobileCallbackIT: **15 PostgreSQL scénářů, úspěšný běh potvrzený uživatelem**. Google/Apple grant, přesná expirace grantu, replay, souběh callbacků, neplatná korelace/provider/deadline, změněný cíl/challenge, disabled účet, kolize e-mailu, nová registrace s novým loginem, denial, skutečný grant insert failure rollback (spy za MANDATORY proxy), cancellation mezi proof a transakcí, DELETE/anonymizace grantu, skutečný souběh callback/cancellation, UNIQUE a V12 guard, odmítnutí legacy dat při migraci. Verifier je simulovaný, služby/DB/vault skutečné. Žádné skutečné provider účty ani síť.

Pouze uživatel spustí podle AGENTS.md a docs/testing.md proti rallye_test:

```powershell
.\mvnw.cmd -Pit verify
```

Uživatel potvrdil nový úspěšný PostgreSQL běh po opravě SingleIdentityIT, včetně V12, atomity a souběhů. Jde o uživatelské ověření, nikoli běh Codexu. Lokální unit/HTTP testy se mohou spouštět bez DB samostatně.

## Postman a manuální ověření

Nová složka **23 Mobile callback 3c.4a.2 – LOCAL invalid proofs only** obsahuje sedm neinvazivních negativních scénářů: neplatný Google state, chybějící state, duplicitní state, code+error současně, chybný Apple user JSON, chybějící Apple state, nesprávná Apple metoda. Vše očekává bezpečné 400/kód, no-store/no-referrer a žádný redirect či Rallye session. Cookie Jar i Follow redirects jsou vypnuté, žádná relace není předpokladem. Skripty mají vlastní anonymní protocol přípravu jen pro prefix 3c4a2; složky 21/22 a zbytek kolekce zůstávají zachované. Žádný test nevolá validní provider code ani nevyžaduje enabled providera/vault; malformed Apple profil se odmítá ještě před vstupem do služby. Kolekce nebyla Codexem skutečně spuštěna.

Pozitivní manuální tok až v samostatně připraveném HTTPS prostředí: použít nový start, otevřít **browserStartUrl přímo v systémovém browseru**, tam dokončit provider login. Nepoužít nejprve Postman launch ze složky 22 a pak jen kopírovat provider URL: ticket už bude spotřebovaný a browser nemá Postman korelační cookie. U existujícího ACTIVE testovacího účtu zkontrolovat callback 303 na povolený App Link se code/requestId, žádný session token. Kód nekopírovat do chatu/exportu/logu; v této etapě jej nelze vyměnit a sám expiruje po 60 sekundách. V DB jen read-only ověřit hash, account_id/attempt_id a přesnou životnost, bez veřejného endpointu na čtení grantů. U nové identity očekávat pouze profilovou stránku/omezenou cookie, dokončení přes existující API a nový mobilní LOGIN. Bez hotové profilové stránky není browserový end-to-end dokončený.

Žádný veřejný mock-provider endpoint, ruční SQL tvorba grantů v rallye_dev, registrační UI, exchange, Android/iOS integrace ani nasazení se nepřidává. Souběhy a rollback jsou samostatně automatizované v chráněných IT, ne ručními destruktivními scénáři.


## Změněné a nové soubory této podetapy

- docs/backend-plan.md
- docs/mobile-auth-3c4.md
- docs/mobile-callback-3c4a2.md
- docs/testing.md
- postman/Rallye-Auth-2A.postman_collection.json
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/ExternalAuthController.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/LaunchTicketPrivacyFilter.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/ExternalFlowService.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/ExternalLoginService.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/MobileCallbackService.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/MobileGrantStore.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/MobileLoginStore.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/PreRaceAccountParticipationFacts.java
- src/main/resources/db/migration/V12__external_mobile_handoff.sql
- src/test/java/cz/mh/rallye/rallye_backend/identity/AccountCancellationServiceIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalAuthHttpTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalAuthIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalLoginServiceTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/MobileCallbackIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/MobileCallbackTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/MobileLoginIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/PreRaceAccountParticipationFactsTest.java

Uživatelské .gitignore a docs/Rallye_specifikace.md zůstaly beze změny. Žádný commit ani push.

### Oprava integrační přípravy po V12

Uživatelský PostgreSQL běh: 209 testů, jedna chyba aserce v `SingleIdentityIT` (očekáváno SQLSTATE `23514`, skutečnost `23502`). Starý INSERT nevyplňoval nové povinné `attempt_id`. Test nyní vytváří existující relaci a mobilní pokus, samostatně ověřuje zákaz REAUTH a LINK (`23514`) a kontrolním platným MOBILE/LOGIN insertem potvrzuje platnost přípravy. Nová regrese ověřuje chybějící (`23502`) a neexistující (`23503`) pokus. Produkční kód ani V1–V12 se kvůli této opravě nemění. Opravený PostgreSQL běh `.\mvnw.cmd -Pit verify` proti `rallye_test` uživatel následně potvrdil jako BUILD SUCCESS (2026-10-25). Jde o uživatelský běh, nikoli běh Codexu; počet testů opakovaného běhu nebyl sdělen.

Po opravě přípravy: vlastní běh `verify` bez PostgreSQL – BUILD SUCCESS, 317 testů, 0 failures/errors/skipped; PostgreSQL testy byly zkompilovány, nikoli spuštěny.

Uzavření 3c.4a.2 (2026-10-25): implementačně dokončena a lokálně ověřena. Codex verify bez DB: 317 testů, BUILD SUCCESS. Uživatel potvrdil PostgreSQL verify včetně opravy SingleIdentityIT (BUILD SUCCESS) a Postman složku 23 (All Passed). Skutečné Google/Apple přihlášení přes HTTPS, browser/proxy a profilová stránka zůstávají neověřené. Exchange a mobilní Bearer relace patří až do nezahájené 3c.4a.3.
