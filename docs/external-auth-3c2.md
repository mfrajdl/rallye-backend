# 3c.2 – webové externí přihlášení, registrace a profil

Stav: **implementačně dokončena a lokálně otestována**. Verify bez DB prošlo s 271 testy; uživatel potvrdil PostgreSQL BUILD SUCCESS a lokální Postman složku 16 se všemi šesti asercemi Passed. Google/Apple jsou standardně vypnuté. Skutečné ověření poskytovatelů ani prohlížeče nebylo provedeno, není zřízena HTTPS doména ani nasazení. Závazná pravidla zůstávají ve specifikaci 0.7.0, D01–D08 se nemění.

## Upřesnění schválená uživatelem při zahájení 3c.2

- Dokončovací kontext platí **15 minut od ověřeného callbacku**, bez prodlužování. OAuth state má stále 10 minut. Vlastní aktivace e-mailu po odeslání údajů má stávajících 24 hodin a nevydává session.
- Součástí 3c.2 je **minimální šifrované Apple úložiště** nezbytného revokačního prostředku, včetně zachování po zrušení účtu. Žádná Apple revokační volání, worker ani retry; ty zůstávají 3c.5.

## Implementované chování

1. Zahájení a korelace navazují na 3c.1, používají pevné providery, state/nonce, Google PKCE a samostatnou Secure/HttpOnly SameSite=None cookie. Webové callbacky ověří serverový code flow; samotné e-mail/jméno od klienta nejsou identifikační důkaz.
2. Identita se hledá pouze podle provider/issuer/subject. Aktivní známý účet dostane novou náhodnou Rallye session v user_session a transakční LOGIN_SUCCEEDED. Cookie, rememberMe, 24h/30d nečinnost a role z DB zůstávají původní. DISABLED/PENDING_EMAIL/ANONYMIZED se nepřihlásí. Jméno/e-mail známého účtu se z externích claims nepřepisuje.
3. **Každá neznámá identita** dostane jen omezený dokončovací kontext a cookie RALLYE_EXTERNAL_REG, i když je profil úplný. Nevytvoří se účet ani session. UI může nabídnuté hodnoty předvyplnit; údaje se odesílají samostatným POST. Tím je odpověď callbacku nezávislá na tom, zda navržený e-mail už existuje.
4. Dokončení profilu vrací vždy **202 bez těla a bez session** pro platný kontext a platné údaje: novou registraci, kolizi i replay již spotřebovaného kontextu. Nedává kód EMAIL_EXISTS ani údaje existujícího účtu. Při kolizi nevytvoří identitu, nezmění heslo či profil a neposílá zprávu existujícímu účtu. Opakovaný POST nemění dříve potvrzené údaje ani znovu neposílá mail.
5. Nový účet vzniká bez LOCAL identity. Spolehlivě ověřený e-mail poskytovatele zůstane ověřený jen při použití stejné normalizované adresy. Doplněný/změněný/neověřený e-mail vyžaduje vlastní jednorázovou EMAIL_VERIFICATION aktivaci na 24 hodin, přes současný SMTP nebo lokální souborový adaptér. Bez aktivace účet zůstává PENDING_EMAIL a nelze se přihlásit.
6. **Po dokončení a případné aktivaci uživatel spustí nové externí přihlášení.** Nyní známá identita dostane relaci. Automatické přihlášení po registraci/aktivaci se nezavádí; žádná session ani tajná replay odpověď není v anonymním registračním kontextu. Odpověď 202 sama nepotvrzuje vznik účtu. Kolize se řeší přihlášením existujícím způsobem a budoucím explicitním link v 3c.3, nikdy shodou adresy.
7. Apple skrytá adresa je běžná přihlašovací adresa účtu. Jméno z prvního Apple user.name formuláře je nedůvěryhodný profilový vstup, kontroluje se limit. E-mail pro rozhodnutí o ověření se bere výhradně z validovaného JWT, ne z user.email. Při pozdějším loginu chybějící jméno nic nesmaže.
8. Odmítnutí poskytovatelem spotřebuje pouze validní korelovaný LOGIN state a vede na pevný returnUri s external=failed, bez odrazu error/error_description. Neplatný state/cookie zůstává bezpečná API chyba; není známa důvěryhodná návratová adresa.

Jméno 1–100 Unicode znaků po oříznutí, e-mail podle Jakarta Validation a existující normalizace. Neplatné údaje vracejí 400 bez zápisu. Registrační tajemství je 256bitové, v DB je pouze SHA-256 digest, browser je předává HttpOnly/Secure/SameSite=Strict cookie s cestou omezenou na registraci. Nikdy není v URL či JSON. GET profilu vrací jen údaje vlastního omezeného kontextu, nikoli účet z databáze.

Všechny aplikační změny používají pořadí zámků admin_control -> account/registration -> další prostředky. Síťová výměna zůstává mimo tyto zámky. State se spotřebuje v téže transakci jako výsledný kontext či session/audit; registrace, identita a spotřeba dokončovacího kontextu jsou atomické. Souběh téhož nebo více kontextů jedné identity nevytvoří duplicitní účet. Po chybě DB během callbacku je nutné zahájit nový OAuth tok: autorizační kód může být u poskytovatele již spotřebovaný, i když DB rollbackovala.

Anonymní registrace používá vlastní jednorázový protokolový kontext; nevytváří nový idempotentní ledger a neukládá hesla ani tokeny do CommandStore. Stávající UUID Idempotency-Key operace 3b a jejich 24h retence se nemění.

## API

| Požadavek | Výsledek |
| --- | --- |
| POST /api/v1/auth/external/GOOGLE/requests nebo APPLE/requests | 200 authorizationUrl/expiresAt/stage=WEB_LOGIN; původní LOGIN/LINK/REAUTH základ |
| GET GOOGLE/callback nebo POST APPLE/callback | 303 na konfigurovaný returnUri s external=signed-in nebo complete-profile; žádné tokeny v Location |
| Callback s error místo code | 303 external=failed jen při validním state/korelaci; žádná session |
| GET /api/v1/auth/external/registrations/current | 200 email/displayName/emailVerified/expiresAt z vlastní registrační cookie |
| POST /api/v1/auth/external/registrations/completions | JSON email/displayName; generic 202 bez těla, plná session se nevydává |
| Stávající POST /api/v1/auth/email-verifications | Původní 204 aktivace, bez session |
| Stávající GET /api/v1/auth/me a POST logout | Původní kontrakty, použitelné i po externím loginu |

Dokončovací POST **vždy vyžaduje CSRF**, nelze jej obejít hlavičkou mobile ani Bearer, protože používá browserovou registrační cookie. Přesná Apple POST callback výjimka je nadále jedinou novou protokolovou CSRF výjimkou. Odpovědi jsou no-store. Limity external-start/callback/profile/complete používají dosavadní omezený per-process LoginThrottle, nikoli novou infrastrukturu.

Chyby: 400 EXTERNAL_REGISTRATION_INVALID při neplatném/expirovaném kontextu, INVALID_REQUEST při neplatných údajích, EXTERNAL_PROFILE_INVALID při chybném Apple user JSON, původní EXTERNAL_STATE_INVALID/EXTERNAL_PROOF_INVALID. Neaktivní známý účet má generické INVALID_CREDENTIALS. 503 APPLE_CREDENTIAL_STORAGE_UNAVAILABLE při chybě klíče/tokenu, původní provider/configuration chyby; 429 při vyčerpání limitu. Provider citlivé odpovědi se nepropagují.

LINK/REAUTH callback stále bezpečně odmítá dokončení; mobilní challenge vede při webovém dokončení k EXTERNAL_MOBILE_UNAVAILABLE. Žádné identities link/unlink/LOCAL přidání ani mobilní exchange nebyly přidány.

## V9 a rušení účtů

V9__external_web_registration.sql přidává:

- external_registration: potvrzený externí identifikátor, minimální profil, hash cookie, rememberMe/návrat, přesná 15minutová platnost a spotřeba. Po spotřebě se z řádku odstraní profilový e-mail a jméno. Při úspěšném dalším callbacku se oportunisticky odstraní již expirované kontexty; není nový plánovač ani prodlužování platnosti.
- apple_revocation_credential: šifrovaný nezbytný refresh token, IV, key ID, client ID, neprůhledné UUID účtu nebo dokončovacího kontextu a čas. Neobsahuje e-mail, jméno, otevřený subject, ID token či access token. Není to přihlašovací identita a nelze jím obnovit účet.

Migrace V1–V8 se nemění. Přesná kontrola Flyway historie výslovně přijímá V9 jen s očekávaným názvem; neznámý název či V10 bezpečně blokuje rušení účtů. Future-migration fixture byla posunuta na V10, ne oslabena.

Při rušení účtu se před smazáním identit zneplatní i všechny související dokončovací kontexty podle identity a navržené adresy, včetně expirovaných a kontextů bez přímého account_id. Apple ciphertext se neodstraní kaskádou a zůstává pro povinnou budoucí revokaci. Posádky, audit, role a ochrana posledního ADMIN zachovávají pravidla 3b. Vlastní zrušení externího účtu zatím bezpečně odmítá místní identity check bez hesla; externí reautentizace/cancellation patří do 3c.3/3c.5. ADMIN cesta funguje již nyní.

## Šifrovací klíče a Apple prostředek

Uživatel výslovně schválil toto minimum pro 3c.2. Používá AES-256-GCM, nový náhodný 12bajtový IV, 128bitový tag a AAD jako jednoznačný JSON seznam UUID credential/client ID. Záměna ciphertextu nebo jeho metadat neprojde ověřením integrity. Není to dříve odmítnuté šifrování idempotentního ledgeru.

Konfigurace bez výchozího klíče:

- rallye.auth.apple-vault.active-key-id: identifikátor aktuálního klíče, 1–64 alfanumerických/underscore/hyphen znaků.
- rallye.auth.apple-vault.keys.<id>: Base64 přesně 32 náhodných bajtů, pouze chráněné prostředí nebo soukromý externí konfigurační soubor mimo Git.

Bez platného aktivního klíče Apple callback neprovede ani výměnu kódu. Nová Apple registrace vyžaduje refresh token; pozdější login jej může vynechat jen pokud již existuje chráněný prostředek účtu pro dané client ID. Žádný falešný nebo implicitní klíč se nepoužije. Syntetický klíč v testech nesmí být použit pro provoz.

Rotace: přidat nový key ID a přepnout aktivní, staré klíče ponechat pro dešifrování existujících řádků. Přešifrovací/provozní nástroje nejsou v 3c.2; klíč neodstraňovat, dokud existují příslušné ciphertexty i potřebné zálohy. Zálohy DB i klíčů chránit odděleným přístupem, nevypisovat do logů. Apple podpisový privátní klíč/client-secret JWT a tento symetrický klíč jsou odlišná tajemství.

Retence: po zrušení nebo nedokončené registraci může nezbytný chráněný prostředek zůstat do prokazatelné revokace; 3c.2 jej nesmí zahodit ani předstírat odvolání. Samotná registrace už neobsahuje otevřený token. **Revokace, retry, monitoring a bezpečné finální odstranění včetně pravidel souběhu nové autorizace jsou nezbytné před produkčním zapnutím Apple a patří do 3c.5.** Ukládají se pouze nezbytné prostředky získané danými výměnami; jejich konsolidaci a životní cyklus musí vyřešit navazující revokační část. Apple při této implementaci nebyl kontaktován.

## Manuální postup a Postman

Kolekce rozděluje původních pět požadavků do dvou samostatných složek. Žádný požadavek ani aserce nebyly odstraněny či oslabeny.

### Lokální běh: pouze složka 16

**16 External web login 3c.2 - LOCAL disabled providers** obsahuje tři požadavky a šest asercí. Při vypnutých poskytovatelích spouštějte v Runneru pouze tuto složku, nikoliv celou kolekci nebo složku 17.

1. Importujte aktualizovanou kolekci. Nastavte `baseUrl=http://localhost:8080`, kolekční `externalProvidersConfigured=false` a `externalReturnId=web`. V aktivním prostředí nastavte `regressionAuthMode=cookie` (nebo proměnnou odstraňte).
2. Zapněte Cookie Jar u všech tří požadavků, povolte skriptům přístup k doméně `localhost`. Odstraňte ruční hlavičky Authorization, Cookie a X-Rallye-Client. No Auth zůstává nastavené; přihlášení není potřeba.
3. V Cookie Jar pro daný host odstraňte případnou `RALLYE_EXTERNAL_REG`. Ponechte CSRF cookies. Sdílený pre-request script automaticky získá CSRF před POST; samostatná anonymní session není nutná.
4. Spusťte celou složku 16: `3c2 Start GOOGLE web login` a `3c2 Start APPLE web login` očekávají každý 503 a EXTERNAL_PROVIDER_DISABLED; `3c2 Invalid registration cookie returns 400` očekává 400 a EXTERNAL_REGISTRATION_INVALID. Výsledek má být šest úspěšných asercí. Odpověď 400 v posledním požadavku je zamýšlený negativní scénář.
5. Stávající místní registrace/aktivace/login/me/logout zůstávají použitelné. Nevytvářejte registrační kontext ručními SQL příkazy ani neposílejte smyšlený autorizační kód poskytovateli.

### Pozitivní registrace: složka 17, zvláštní příprava

**17 External web login 3c.2 - PREPARED HTTPS registration only** není součástí lokálního běhu při vypnutých poskytovatelích. Vyžaduje skutečně ověřený callback nové identity, platnou 15minutovou `RALLYE_EXTERNAL_REG` cookie pro stejný HTTPS host v používaném cookie jar a režim cookie. Nevyžaduje plné přihlášení; omezený registrační kontext je právě náhradou plné relace před dokončením.

- `3c2 Current registration profile`: nejprve GET, očekává 200. Bez kontextu správně vrací 400 EXTERNAL_REGISTRATION_INVALID.
- `3c2 Complete registration (generic 202)`: následně POST se stejnou cookie, CSRF a lokálně nastavenými `externalRegistrationEmail` a `externalRegistrationName`. Očekává 202 a prázdné tělo. Údaje nestačí bez kontextu. Dokončení může vytvořit testovací účet a vyvolat aktivační e-mail.

Při vypnutém Google/Apple nelze tento kontext získat podporovanou veřejnou API cestou. Oba pozitivní požadavky proto čekají na schválené HTTPS/provider prostředí; jejich očekávání 200/202 zůstává zachováno. Automatické testy používají simulovaný verifier, nikoliv veřejný testovací endpoint pro vytváření kontextů.

### Diagnostika původních tří neúspěšných asercí

Nešlo o tři různé endpointy: GET profilu bez kontextu nesplnil jednu aserci 200. POST dokončení bez kontextu (případně s neplatnými vstupy) vrátil 400, čímž nesplnil dvě aserce: status 202 a prázdné tělo, protože odpověď obsahovala chybový JSON. Pro GET je doložen kód EXTERNAL_REGISTRATION_INVALID. Přesný kód původního POST uživatel nedodal; implementace odmítá chybějící kontext stejným kódem, zatímco chybné údaje mohou vracet INVALID_REQUEST. Rozdělení složek odstraňuje nesplnitelné předpoklady společného lokálního běhu, nikoli bezpečnostní kontroly backendu.

Pozitivní průchod až v později schváleném HTTPS prostředí (nyní se nezřizuje):

1. Konfigurovat provider enabled/client-id/client-secret/callback-uri a allowlist returns.web podle 3c.1, u Apple platný client-secret JWT a AES vault. Secrets pouze mimo Git. Produkční automatická generace/rotace Apple client-secret JWT není v tomto kroku; krátkodobou hodnotu dodá bezpečná provozní konfigurace. Účty a klientské registrace poskytovatelů musí být výslovně schválené.
2. Zahájit tok **v prohlížeči**, který dokončí callback se svou korelační cookie. Pouhé otevření autorizační URL získané v Postmanu cookie jar do browseru nepřenese a musí bezpečně selhat.
3. Známý účet: 303 external=signed-in, HttpOnly Rallye cookie; stejný browser zavolá /me -> 200. Nepřepisovat rememberMe ani provider claims v klientovi.
4. Nová identita: 303 external=complete-profile, pouze registrační cookie. GET registrations/current -> 200, doplnit údaje a s CSRF POST completions -> 202. Tento krok v žádném případě nepotvrzuje existenci kolidujícího účtu a nevydává session.
5. Je-li potřeba vlastní ověření e-mailu, použít novou aktivační zprávu a současný email-verifications endpoint -> 204; bez ní se nepřihlásí. Ověřený nezměněný provider e-mail další aktivační zprávu nevyžaduje.
6. Zahájit nový externí tok. Nově registrovaná aktivní identita se přihlásí, /me -> 200, logout -> 204 a následné /me -> 401. Ověřit opakovaný Apple login bez jména a relay adresu.
7. Kolize místního e-mailu: stejné 202 při dokončení, žádná externí identita na místním účtu a původní místní heslo/profil fungují. Nevytvářet automatický link; explicitní link přijde v 3c.3.
8. Negativně ověřit neplatnou korelaci, opakovaný state, 15minutovou expiraci a odmítnutý souhlas. Bez CSRF dokončovací POST vrací 403 i s mobile hlavičkou. Pro potvrzení správných chyb použít odpovídající negativní scénář, ne očekávání 200.

Browser a Postman nesdílejí cookies. Pro ruční API průchod z Postmanu použít výslovně testovací session/cookie jar pro tutéž HTTPS doménu; případný přenos cookie z vlastního browseru dělat pouze lokálně pro testovací účet, nikdy do chatu, exportu kolekce či Gitu. Registrační cookie má HttpOnly a cestu jen pro registrations, korelační cookie jen pro callback. Hotová frontendová obrazovka profilu není součástí backendu; prohlížeč musí integračně zavolat tato API na povoleném originu. Nenastavovat globální CORS/CSRF výjimky kvůli testu.

## Ověření a limity

Codex skutečně spustil Maven Wrapper verify s Microsoft JDK 21.0.12.1: **BUILD SUCCESS, 271 testů, 0 failures, 0 errors, 0 skipped**, JAR sestaven. Prostředky omezeny pouze procesově (Maven Xmx192m, testovací JVM Xmx384m/ActiveProcessorCount=2), nikoli změnou projektu. Log je v ignorovaném target/verify-3c2.log. PostgreSQL testy Codex nespouštěl; jejich následný úspěšný běh potvrdil uživatel.

Postman: Node JSON.parse i PowerShell ConvertFrom-Json, oficiální Collection v2.1 schema, 100 požadavků/165 syntakticky validních JS skriptů a JSON těla po dosazení zástupných proměnných. Porovnání proti HEAD potvrzuje zachování všech původních požadavků, kolekčních skriptů a proměnných; přibylo jen pět requestů, čtyři proměnné a jejich lokální skripty. Nejde o skutečné spuštění requestů proti poskytovateli.

Běžné unit/HTTP testy běží bez DB a sítě. PostgreSQL ExternalWebLoginIT používá skutečné služby a migrace ve vlastním náhodném schématu, pouze verifier je simulován; TestDatabaseInitializer hlídá rallye_test/roli rallye_test. Syntetické profily, tokeny a klíče nejsou skutečné provider credentials.

ExternalWebLoginIT obsahuje **14 scénářů zahrnutých v uživatelem potvrzeném úspěšném PostgreSQL běhu**. Scénáře: první/další login, žádná LOCAL identita, chybějící profil, neověřená či uživatelem změněná adresa a vlastní aktivace, kolize a původní heslo, dva typy souběhu dokončení, přesná expirace, deaktivovaný účet, rollback session/auditu/state, Apple relay/jméno/šifrování a cancellation, rollback dokončení a upgrade V8 -> V9, přesný migration guard.

PostgreSQL testy spouští uživatel podle testing.md:

```powershell
.\mvnw.cmd -Pit verify
```

Uživatel po opravě mockování potvrdil tento PostgreSQL běh s výsledkem **BUILD SUCCESS**. Integrační ověření 3c.2 je dokončené; Codex PostgreSQL testy nespouštěl. Není tím ověřeno skutečné Google/Apple chování, Apple SameSite/form_post v browseru, relay doručování ani HTTPS/proxy nastavení. Omezení single-instance limiteru a e-mailu po commitu bez durable outbox zůstávají. Neprovádět produkční zapnutí Apple bez 3c.3/3c.5 a vyřešení zrušení externího účtu a revokace.


## Přesný seznam změněných souborů

- AGENTS.md
- README.md
- docs/account-cancellation-3b4b.md
- docs/authentication-2a.md
- docs/backend-plan.md
- docs/external-auth-3c.md
- docs/external-auth-3c1.md
- docs/external-auth-3c2.md
- docs/testing.md
- postman/Rallye-Auth-2A.postman_collection.json
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/AuthConfiguration.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/api/ExternalAuthController.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/AppleTokenVault.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/ExternalFlowService.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/ExternalLoginService.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/ExternalOidcVerifier.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/AccountCancellationStore.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/ExternalAccountStore.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/PreRaceAccountParticipationFacts.java
- src/main/resources/db/migration/V9__external_web_registration.sql
- src/test/java/cz/mh/rallye/rallye_backend/identity/AccountCancellationServiceIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/AppleTokenVaultTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalAuthHttpTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalAuthIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalFlowTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalLoginServiceTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalWebLoginIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/PreRaceAccountParticipationFactsTest.java

Uživatelské .gitignore a docs/Rallye_specifikace.md jsou nedotčené; bez commitu/pushe. Žádná cloudová infrastruktura nebo skutečné účty poskytovatelů nebyly použity.

### Oprava integračního testu dokončení registrace

Uživatelský PostgreSQL běh 3c.2: 157 testů, 0 failures, 2 errors. Stack trace prokázal chybu přípravy Mockito spy: nastavování `consume` přes transakční proxy `ExternalAccountStore` mimo transakci vyvolalo `IllegalTransactionStateException` kvůli `MANDATORY`, ještě před voláním služby. Nedokončený stubbing následně způsobil `UnfinishedStubbingException` při resetu mocků druhého testu; nešlo o samostatné selhání kontroly V9.

Opravený test nastavuje i obnovuje skutečný spy za proxy pomocí `AopTestUtils`. Simulované selhání nastává až po skutečném zápisu `consume` v aktivní transakci. Test ověřuje kořenovou vloženou výjimku, rollback účtu, identity, spotřebování i údajů registračního kontextu, neposlání e-mailu a úspěšný retry. Produkční transakční hranice ani `Propagation.MANDATORY` se nemění. Po opravě uživatel potvrdil opakovaný běh `.\mvnw.cmd -Pit verify` proti `rallye_test` s výsledkem **BUILD SUCCESS**. Jde o uživatelem potvrzené integrační ověření, nikoli běh Codexu; počet testů opakovaného běhu nebyl uveden.

## Závěrečné uzavření 3c.2

Etapa je implementačně dokončená a lokálně otestovaná. Codex provedl verify bez DB: BUILD SUCCESS, 271 testů bez chyb. Uživatel potvrdil PostgreSQL `.\mvnw.cmd -Pit verify` (BUILD SUCCESS) a po reorganizaci kolekce všech šest asercí lokální složky 16 Passed. Jde o uživatelem potvrzené běhy, nikoli PostgreSQL nebo Postman běh Codexu.

Skutečné přihlášení Google/Apple ani pozitivní scénáře složky 17 **nebyly ověřeny**. Vyžadují schválené HTTPS testovací prostředí, konfiguraci poskytovatelů a browser/cookie korelaci; žádná taková infrastruktura nebyla nyní zřízena. Uzavření implementace není potvrzením připravenosti k produkčnímu zapnutí externího přihlašování. Správa identit 3c.3, mobilní předání 3c.4 a Apple revokace 3c.5 zůstávají plánované.
