# 3c.3b – zrušení vlastního účtu podle D10

Stav: implementováno, verify bez DB úspěšné; PostgreSQL ověření uživatel potvrdil jako BUILD SUCCESS; LOCAL manuální ověření uživatel potvrdil; podetapa je dokončená v implementovaném rozsahu. Skutečné GOOGLE/APPLE zrušení nebylo manuálně ověřeno a čeká na HTTPS testovací prostředí. Závazná specifikace 0.8.0, D09/D10. Žádná nová migrace: V1–V10 se nemění, přesný Flyway guard zůstává V10. Nepřidává se mobilní handoff ani Apple revokační worker.

## API kontrakt

Stávající `POST /api/v1/auth/account-cancellations` vyžaduje platnou Rallye relaci, UUID hlavičku `Idempotency-Key` a JSON. Web používá stávající session cookie a CSRF cookie/hlavičku; mobil existující Authorization: Bearer. Samotná omezená registrační cookie není přihlášení. Klient neposílá ani nevybírá typ identity; AccountStore načte právě jednu známou identitu z DB po uzamčení účtu. Neznámá/chybějící/nekonzistentní identita selže bezpečně 503 ACCOUNT_IDENTITY_UNAVAILABLE.

LOCAL – dosavadní požadavek je nadále platný:

```json
{"currentPassword":"<aktuální heslo vyplněné pouze lokálně>"}
```

Heslo se vždy znovu ověřuje. Přidané confirmed=true to nemůže obejít. Chybné heslo nebo chybějící heslo při jinak platném DTO vrací 401 INVALID_CREDENTIALS. Pole currentPassword zachovává maximální délku 256 v existujícím cancellation kontraktu; to nemění pravidla vytváření hesel 8–128 znaků. Bez obou polí nebo při překročení délky se vrací 400 INVALID_REQUEST. Původní klienti posílající heslo tedy nemusí měnit tělo.

GOOGLE / APPLE:

```json
{"confirmed":true}
```

Potvrzení je samostatný explicitní souhlas v požadavku, nikoli nové ověření identity. Relace je povinná. Nevyžaduje se auth_time, max_age, OAuth callback ani pětiminutový důkaz. `confirmed:false` vrací 403 ACCOUNT_CANCELLATION_CONFIRMATION_REQUIRED. Chybějící potvrzení s pouhým heslem vrací stejných 403; prázdný objekt `{}` či obě null pole vrací 400 INVALID_REQUEST při validaci DTO. Na chybějící potvrzení nikdy nenavazuje zrušení. Nevyhovující běžná relace je odmítnuta 401; neúspěšné potvrzení ji samo neodvolává.

Úspěch všech typů: 204 s prázdným tělem, vymazání session cookie a serverové odstranění/zneplatnění všech relací účtu. Následující přístup se starým Bearer nebo cookie musí být odmítnut 401. Žádná hesla, potvrzovací tokeny ani typ identity od klienta se neukládají do ledgeru nebo auditu. Potvrzení není token a nevytváří přenosné oprávnění.

ADMIN endpoint `/api/v1/admin/users/{accountId}/cancellations` zůstává beze změny: UUID Idempotency-Key, bez těla a bez hesla cíle, autorizace jednajícího administrátora. Zachovává zákaz sebe přes ADMIN cestu, posledního aktivního ADMIN, replay i další ochrany. Nová ochrana Apple materiálu se uplatní i zde.

## Transakce a ochrany

Služba zachovává jednu transakci a pořadí admin_control -> účet -> posádky/převody a další závislosti. Server znovu kontroluje ACTIVE účet a aktivní konkrétní relaci. LOCAL používá dosavadní CancellationIdentityCheck/PasswordEncoder; externí účet vyžaduje potvrzení. Starší interní self(actor,password,key) je kompatibilní obálka s confirmed=false, takže ani stará cesta nemůže zrušit externí účet bez souhlasu.

Po autorizaci a ověření je zachována dosavadní kontrola idempotence. Vlastní zrušení nezanechává replay záznam ani tajemství; po úspěchu stará relace již nemůže autorizovat opakování. ADMIN replay nadále vrátí původní výsledek po aktuální autorizaci aktéra, před hledáním již smazaného cíle. Množina významových parametrů ani ukládání hesel do CommandStore se nemění.

Zůstává kontrola běžící/historické účasti a posledního ADMIN, odpojení posádek, uzavření čekajících převodů, úklid CommandStore i všech sessions/tokenů/rolí/externích kontextů. Bez historie se účet smaže, s historií anonymizuje. Audit ACCOUNT_DELETED nebo ACCOUNT_ANONYMIZED a všechny vedlejší změny jsou ve stejné transakci. Chyba auditu nebo úklidu rollbackne i zrušení; D10 potvrzení se nikdy neaudituje jako REAUTHENTICATION_SUCCEEDED. V10 dovolí commit odstranění identity pouze s konečným DELETE/ANONYMIZED účtem.

## Apple revokační prostředek

V9 ukládá šifrované prostředky do apple_revocation_credential: UUID záznamu a účtu, client_id, key_id, IV, ciphertext a čas. account_id záměrně nemá FK na user_account, registration_id má ON DELETE SET NULL. Při přiřazení k hotovému účtu je registration_id vyčištěné. Rušení maže identity a registrační kontexty, nikoli Apple ciphertext nebo jeho client/key/AAD vazby. Zachovají se všechny řádky (provider může během přihlášení vrátit více prostředků), a to při DELETE i anonymizaci a v ADMIN cestě.

Před destruktivními změnami u APPLE se navíc kontroluje existence alespoň jednoho uloženého prostředku pro účet s neprázdným client_id a key_id. Chybí-li, celá operace skončí 503 APPLE_REVOCATION_MATERIAL_UNAVAILABLE bez změny účtu. Kontrola probíhá v existující transakci a navazuje na zámek účtu; nejde o nové API nebo novou infrastrukturu. Kryptografické vytvoření a integrita ciphertextu pocházejí z vaultu 3c.2; nezavádí se dešifrování tokenů do odpovědí/logů ani do procesu rušení.

Tím není provedená vzdálená revokace. Worker, enqueue/retry/lease, bezpečné odstranění po revokaci a korelace nové autorizace se starou čekající revokací zůstávají v 3c.5. Klíče musí provoz zachovat mimo Git pro původní key_id; ztrátu klíče nemůže samotné zachování DB vyřešit. Chování při rušení nevytváří nový důvod ztráty již uloženého prostředku. Před zapnutím skutečných Apple účtů je nutné dokončit 3c.5 a skutečné provider ověření; úspěch syntetických testů není potvrzení doručení revokace.

## Automatické ověření

Verify na Microsoft JDK 21.0.12.1: **BUILD SUCCESS, 284 testů, 0 failures/errors/skipped**. Unit testy ověřují serverový provider, nekonzistentní stav, LOCAL heslo i při confirmed=true, externí souhlas a Apple guard. HTTP testy ověřují kompatibilní LOCAL JSON, externí DTO, false, autentizaci, skutečnou CSRF cookie/hlavičku a bezpečné chyby.

PostgreSQL testy byly zkompilované a jejich úspěšný běh následně potvrdil uživatel. Codex je nespouštěl. ExternalWebLoginIT má osm nových D10 scénářů: Google zrušení všech relací/audit, obě externí identity bez potvrzení, odvolaná relace, Apple DELETE se zachováním všech šifrovaných řádků a možností jejich dešifrování, historická anonymizace, chybějící prostředek v self i ADMIN cestě, souběh cancellation a audit failure rollback/retry. Verifier je simulovaný; služby, vault, účet, sessions, audit a V10 jsou skutečné. Pro historickou účast se používá dosavadní testovací adaptér facts, závodní tabulky se nezavádějí. AccountCancellationServiceIT doplňuje skutečné LOCAL odmítnutí chybného i chybějícího hesla navzdory confirmed=true.

Uživatel spustí pouze proti rallye_test podle docs/testing.md:

```powershell
.\mvnw.cmd -Pit verify
```

Uživatel potvrdil úspěšný PostgreSQL běh; DB část D10, rollback, souběhy a zachování Apple prostředků jsou integračně ověřené v testovaném prostředí. Předchozí úspěch 3c.3a se nepřenáší na novou logiku.

## Postman – lokální a připravené scénáře

Vždy před operací ověřit `/auth/me` -> 200 a správný jednorázový testovací účet. Nezaměňovat neplatnou relaci za zamítnutí samotného potvrzení. Cookie režim: regressionAuthMode=cookie, zapnutý cookie jar a automatický CSRF; při použití existujícího izolovaného Bearer režimu vypnout cookie jar podle dosavadního návodu. Hodnoty hesel a relací neexportovat. Celou kolekci ani cancellation složky nespouštět dávkově.

1. Lokálně založit/aktivovat a přihlásit disposable LOCAL účet existujícími požadavky 2A.
2. Složka **19 D10 cancellation - LOCAL negative checks** je samostatně opakovatelná cookie sada: nastavte pouze lokálně `d10LocalEmail` a `d10LocalPassword` na existující aktivovaný LOCAL testovací účet. V aktivním prostředí nastavte regressionAuthMode=cookie, v kolekci baseUrl na stejný host jako cookies (např. http://localhost:8080). U všech pěti requestů zapněte Cookie Jar, povolte skriptům cookies localhost a odstraňte ruční Cookie/Authorization/X-Rallye-Client hlavičky. No Auth samo nezískává relaci. Nespoléhejte na relaci z předchozí složky.
   - Spusťte pouze celou složku 19 od začátku: Prepare LOCAL cookie session -> login 200; Verify LOCAL session before negative checks -> /me 200 se správným e-mailem; dva původní negativní POST -> každý 401 INVALID_CREDENTIALS; Verify LOCAL session survives negative checks -> /me 200.
   - Před každým negativním POST se automaticky ověří /me. Pokud není 200 pro určený účet, vznikne neúspěšná aserce předpokladu a POST se neodešle; nejde o úspěšně ověřený negativní scénář. Po každém POST se znovu ověří zachování relace. Původní očekávání 401 INVALID_CREDENTIALS zůstává beze změny; 401 UNAUTHENTICATED se nepřijímá jako jeho náhrada.
   - Chybné heslo se bezpečně vybírá ze dvou testovacích konstant tak, aby se neshodovalo s nastaveným aktuálním heslem; neodvozuje se připojením textu k reálnému heslu. Žádný cancellation požadavek v 19 nemůže obsahovat správné nastavené heslo. Celá složka nic nemaže ani neodhlašuje.
   - Pokud byl účet zrušen ve složce 13 nebo 20, přihlášení selže. Nejdříve zaregistrujte a aktivujte nový testovací účet existujícími 2A požadavky a nastavte nové údaje; login sám účet neobnoví. Po změně/resetu hesla aktualizujte d10LocalPassword. Žádná automatická registrace, aktivace nebo obcházení ověření se nezavádí.
   - Cookie režim byl zvolen pro tuto izolovanou sadu, Bearer režim ostatních složek se nemění. Postman prostředí nesdílený Cookie Jar nezaručují: nespouštějte jiné uživatele/destruktivní složky současně. Opakované spuštění 19 vytváří novou běžnou relaci, původní případně zůstává podle stávajících pravidel více relací. Nové proměnné jsou v exportu prázdné; skutečné hodnoty držte pouze v lokálních nesdílených hodnotách Postman prostředí a neexportujte je.
3. Úspěšné LOCAL zrušení znovu používá existující složku 13 a její request `Self cancellation` s aktuálním cancellationPassword a Idempotency-Key. Očekávat 204, potom `/auth/me` 401. Není vytvořen duplicitní pozitivní LOCAL požadavek.
4. Složka **20 D10 cancellation - PREPARED external account** vyžaduje přihlášený disposable GOOGLE/APPLE účet po ověřeném HTTPS browser/provider toku. Omezený profil z folder 17 nestačí. Prázdné tělo -> 400; explicitní false -> 403 a účet zůstane; skutečné potvrzení true -> 204 a `/auth/me` 401. Spouštět jednotlivě v tomto pořadí, nejde o lokální vypnutý-provider test. Bez externí konfigurace se tyto předpoklady nevytvářejí SQL nebo vymyšleným callbackem.
5. Pro budoucí skutečný Apple test může správce read-only porovnat počet/UUID/client_id/key_id revokačních řádků pro interní UUID účtu před a po zrušení; neexportovat ciphertext, IV ani tokeny. API samo revokační údaje nevystavuje. Reálnou revokaci zatím neočekávat.

Manuální testy této podetapy Codex neprováděl. Google/Apple interoperabilita a HTTPS prostředí zůstávají samostatně neověřené. Žádné nové nasazení ani infrastruktura nebyly vytvořeny.


## Přesný seznam souborů této podetapy

```text
AGENTS.md
README.md
docs/account-cancellation-3b4b.md
docs/authentication-2a.md
docs/backend-plan.md
docs/external-auth-3c.md
docs/external-auth-3c1.md
docs/external-auth-3c2.md
docs/testing.md
postman/Rallye-Auth-2A.postman_collection.json
src/main/java/cz/mh/rallye/rallye_backend/identity/api/AccountCancellationController.java
src/main/java/cz/mh/rallye/rallye_backend/identity/application/AccountCancellationService.java
src/main/java/cz/mh/rallye/rallye_backend/identity/application/CancellationIdentityCheck.java
src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/AccountCancellationStore.java
src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/AccountStore.java
src/test/java/cz/mh/rallye/rallye_backend/identity/AccountCancellationHttpTest.java
src/test/java/cz/mh/rallye/rallye_backend/identity/AccountCancellationServiceIT.java
src/test/java/cz/mh/rallye/rallye_backend/identity/AccountCancellationServiceTest.java
src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalWebLoginIT.java
docs/account-cancellation-3c3b.md
src/test/java/cz/mh/rallye/rallye_backend/identity/AccountIdentityProviderTest.java
```

Kontrola kolekce: JSON parser, schéma Postman v2.1 a syntaxe všech skriptů prošly (111 požadavků, 180 JS skriptů). Původní složky, sdílené skripty a proměnné byly zachovány, přibyly složky 19/20; následná oprava doplnila v 19 přihlášení a kontroly relace. git diff --check prošel. Historické specifikace, V1–V10, .gitignore a docs/Rallye_specifikace.md zůstávají nedotčené.


Uživatelské potvrzení 25. 10. 2026: `.\mvnw.cmd -Pit verify` proti `rallye_test` skončilo **BUILD SUCCESS**. Jde o uživatelem potvrzené integrační ověření, nikoli PostgreSQL běh Codexu. Počet testů tohoto běhu nebyl sdělen. Automatické ověření 3c.3b je dokončené; manuální Postman složky 19/20 ani skutečná Google/Apple integrace tím nejsou potvrzené. Apple revokace zůstává v 3c.5; commit ani push nebyly provedeny.


## Diagnostika Postman 19 po uživatelském běhu 2 Passed / 2 Failed

Oba POST vrátily 401 UNAUTHENTICATED. Dvě statusové aserce tedy prošly a dvě aserce INVALID_CREDENTIALS selhaly. V aktuální implementaci UNAUTHENTICATED zapisuje Spring Security authenticationEntryPoint před chráněným controllerem, když požadavek nemá autentizaci; LocalCancellationIdentityCheck při nesprávném/chybějícím heslu vrací INVALID_CREDENTIALS. Doložená chyba proto není odmítnutí LOCAL hesla, ale nesplněný předpoklad přihlášené relace. Samotná odpověď neprokazuje, zda Cookie Jar cookie neodeslal, relace vypršela, nebo byla dříve zrušena (například cancellation ve 13). Neplatný Bearer v AuthSessionFilter navíc běžně vrací INVALID_CREDENTIALS, proto nelze ze statusu 401 samotného testovat heslo. Chybějící CSRF má vracet 403 ACCESS_DENIED; získání CSRF neprovádí přihlášení.

Původní složka 19 přihlášení neobsahovala a zděděný collection script obstarával CSRF nebo použil existující regressionSessionToken, nikoli novou relaci. Nová sada přidává login a /me kontroly při zachování původních asercí a těl významově stejných negativních operací. Produkční kód ani migrace se při této opravě neměnily. Opakované skutečné Postman ověření nové organizace ještě čeká na uživatele; úspěch PostgreSQL zůstává odděleně potvrzený.


Ověření opravy kolekce: Node JSON.parse a PowerShell ConvertFrom-Json, oficiální schéma v2.1 a syntaxe 180 JS skriptů prošly. Offline simulace skriptů ověřila úspěšnou přípravu, vynulování přípravy při neúspěšném loginu, chybějící relaci, platnou relaci, zachované odmítnutí nesprávného kódu UNAUTHENTICATED a volbu hesla odlišného od nastaveného. Není to skutečný Postman/backend běh. Runtime d10LocalPreparedAccountId nastavuje skript po úspěšném loginu a před každou novou přípravou ho vymaže; uživatel jej ručně nenastavuje. Původní /me cookie jiného účtu ani starý token nejsou fallback. Ostatní složky a collection-level skript se při této opravě neměnily. Java testy nebyly opakovány, protože produkční ani testovací Java soubory nebyly touto opravou měněny.


### Následná diagnostika Postman 19: 23 Passed / 4 Failed (403 ACCESS_DENIED)

Průchozí login a všechny /me kontroly dokládají funkční autentizovanou relaci pomocných požadavků. Oba chráněné POST ale vracely 403 ACCESS_DENIED namísto aplikačních 401 INVALID_CREDENTIALS. ACCESS_DENIED vytváří security accessDeniedHandler; LOCAL password check jej nevytváří. CSRF je proto konkrétní podezřelá hranice před controllerem, nikoliv doložené odmítnutí hesla. Bez skutečných odchozích hlaviček a cookies nelze tvrdit, zda hlavička chyběla, byla stará, nebo neodpovídala cookie poslané v POST. Samotný /me není důkazem rotace CSRF cookie a takovou rotaci analýza nepotvrdila. CSRF zde není uložený v Rallye databázové relaci: CookieCsrfTokenRepository porovnává hlavičku s cookie XSRF-TOKEN. /csrf může vrátit XOR-maskovaný request token, proto se do hlavičky nikdy nekopíruje raw cookie.

Proti funkční složce 13 měla 19 další pomocný /me až po společné CSRF přípravě. Oprava odstraňuje tuto časovou mezeru: collection-level CSRF příprava deleguje pouze dva přesně pojmenované negativní POST na jejich vlastní request-level skript. Ten nejprve ověří /me, potom jako poslední HTTP přípravu získá /csrf, ověří dostupnost RALLYE_SESSION a XSRF-TOKEN v jar a vloží čerstvou hlavičku přímo do odcházejícího požadavku. Po nastavení hlavičky už žádné další přípravné HTTP volání není. Pokud předpoklady chybí, aserce selže a POST se neodešle. Složka 13 i ostatní požadavky stále používají původní společnou přípravu. Původní aserce 401 INVALID_CREDENTIALS nebyly změněny.

Opakování: importovat aktualizovanou kolekci, nastavit d10LocalEmail/d10LocalPassword aktivovaného LOCAL účtu a regressionAuthMode=cookie; pro všechny requesty ponechat Cookie Jar zapnutý a allowlist pro localhost. Používat stejný hostname/port v baseUrl i skutečných URL; nemíchat localhost a 127.0.0.1. Odstranit ručně nastavené Cookie, Authorization, X-Rallye-Client a X-XSRF-TOKEN; cookie ani token nevkládat ručně. Poté spustit pouze celou 19 od přípravného loginu. Negativní POST mají stále vrátit 401 INVALID_CREDENTIALS; nové aserce ověřují poslední CSRF přípravu a dostupnost obou cookies. /me zůstává 200 před i po. Během průchodu nespouštět logout, změnu/reset hesla ani destruktivní složku 13/20 nebo jiného uživatele na stejném jar.

Pokud se 403 opakuje i po všech nových přípravných asercích, pro další diagnostiku ověřit v Postman Console pouze metadata skutečného POST: přítomnost jedné X-XSRF-TOKEN hlavičky, názvy RALLYE_SESSION/XSRF-TOKEN v odeslaném Cookie a případné Set-Cookie smazání/rotaci. Nesdílet jejich hodnoty, heslo ani tokeny. Zkontrolovat duplicitní cookies téhož jména pro různé domain/path; lze odstranit cookies pouze daného lokálního hostu a zopakovat login. Úspěch offline simulace není důkaz skutečného přenosu cookies Postmanem.

Ověřeno: JSON Node/PowerShell, Postman schema v2.1, syntaxe JS a offline simulace včetně posledního získání CSRF, čtení cookies a nastavení hlavičky. Skutečné Postman opakování opravy není zatím potvrzené. Při této opravě se nezměnil produkční ani testovací Java kód, migrace ani security konfigurace. Maven/PG testy nebyly znovu spouštěny, jejich předchozí ověření zůstává oddělené.


### Postman 13 – bezpečné skutečné ověření LOCAL zrušení (3c.3b)

Uživatel potvrdil složku 19: **35 Passed / 0 Failed**. Výsledek složky 13 **3 Passed / 0 Failed s No response není ověření zrušení**. Request-level skripty přeskočily požadavky při chybějících předpokladech; například původní kontrola cancellationPassword četla pouze collection scope a ignorovala environment. No response zde znamená neodeslaný požadavek, nikoliv HTTP 204. Bez uživatelových proměnných nelze určit, který konkrétní guard se u něj aktivoval. Složka 13 neobsahuje setNextRequest; různé žádosti vyžadují různé role a předchozí výsledky, nelze ji bezpečně spustit celou najednou.

Oprava: chybějící předpoklad má nyní výslovně selhávající aserci a požadavek se přesto bezpečně přeskočí. Heslo se hledá přes pm.variables (včetně environmentu). Úspěšné SELF zrušení navíc vyžaduje jednorázový opt-in a shodu aktuálního /me s ručně potvrzeným UUID. Nejprve ověřuje /me, potom získá poslední CSRF token a ověří cookies. Jednorázové povolení se spotřebuje i při selhání přípravy. ADMIN destruktivní žádost obdobně vyžaduje allowAdminAccountCancellation=true; výchozí hodnota obou povolení je false. Samostatná následná 401 kontrola vyžaduje předchozí úspěšné 204, aby nemohla falešně projít pouze kvůli chybějícímu přihlášení.

Bezpečný postup (pouze postradatelný LOCAL účet, nikdy poslední ADMIN):

1. Importovat aktualizovanou kolekci. Použít místní profil backendu, stejný baseUrl/hostname, Cookie Jar zapnutý a localhost v allowlistu. Nastavit regressionAuthMode=cookie. Odstranit ručně vložené Cookie/Authorization/X-Rallye-Client/CSRF hlavičky. Nespouštět jiná přihlášení souběžně.
2. Založit a aktivovat postradatelný LOCAL účet, nebo použít existující. Nastavit lokálně d10LocalEmail a d10LocalPassword a spustit složku 19, která účet neruší a připraví cookie relaci. Samostatné přihlášení cookie režimem je rovněž možné. Nepoužívat již smazaný účet.
3. Načíst /api/v1/auth/me (200) a zkontrolovat cílový účet. Do lokálního environmentu nastavit selfCancellationExpectedAccountId na jeho id, cancellationPassword na jeho aktuální heslo a allowSelfAccountCancellation=true. Hesla ani exportované runtime hodnoty neukládat do Gitu. Tyto proměnné nemají závislost na heslu obecného login requestu.
4. Odeslat **pouze** `3b4b Self cancellation - DELETE TEST ACCOUNT (204)` ze složky 13. Idempotency-Key se vygeneruje jako nové UUID pro toto odeslání. Skript odešle JSON currentPassword, nepoužívá confirmed jako náhradu hesla. Vyžaduje cookie relaci; Bearer režim zde záměrně odmítá, produkční Bearer API se nemění.
5. Ověřit skutečnou odpověď **204 s prázdným tělem** a všechny testy: následné /auth/me **401** a pokus o přihlášení původním e-mailem/heslem **401 INVALID_CREDENTIALS**. Tyto pomocné žádosti jsou v testovacím skriptu a jejich metadata lze vidět v Postman Console. Nejde o přímý důkaz revokace jiné předem zachycené relace; všechny relace ověřují PostgreSQL testy. Při No response nebo selhání přípravných asercí zrušení neproběhlo a není ověřeno. Po opravě předpokladů je nutné znovu vědomě nastavit povolení true.
6. allowSelfAccountCancellation je po pokusu false. Opakované úspěšné zrušení vyžaduje nový aktivovaný testovací účet a nové potvrzení jeho UUID. Teprve po úspěšném 204 lze jednotlivě spustit `original credentials are invalid (401)`. Volitelné read-only DB ověření: podle zachyceného UUID zkontrolovat user_account (bez historie účet chybí; s historií ANONYMIZED), nepřítomnost jeho sessions/identit a captain_account_id=NULL u zachovaných posádek. Žádné mazání SQL není součástí postupu.

Následně uživatel potvrdil úspěšné manuální LOCAL zrušení podle tohoto postupu (viz závěrečné uzavření níže). Produkční kód, migrace ani bezpečnostní mechanismy nebyly při této opravě změněny. Složka 13 zůstává sadou jednotlivě spouštěných scénářů, ne automatickým destruktivním runnerem.


## Závěrečné uzavření 3c.3b

Etapa je implementačně dokončená a lokálně otestovaná. Verify bez DB: BUILD SUCCESS, 284 testů (dřívější běh Codexu). Uživatel potvrdil PostgreSQL `.\mvnw.cmd -Pit verify` – BUILD SUCCESS, Postman 19 – 35 Passed / 0 Failed a samostatný SELF požadavek – skutečné 204 No Content s prázdným tělem, následné zneplatnění relace a odmítnutí přihlášení původními údaji. Ochranné povolení destruktivního testu se vrátilo na false. Dřívější neúspěchy a čekání popsané výše jsou diagnostická historie, nikoli aktuálně otevřené LOCAL ověření.

Skutečné GOOGLE/APPLE zrušení účtu ani Postman složka 20 nebyly manuálně ověřeny; čekají na HTTPS testovací prostředí. Syntetické PostgreSQL testy nejsou skutečným provider ověřením. Apple revokační worker a vzdálená revokace zůstávají v 3c.5; 3c.4 ani 3c.5 nebyly zahájeny. Pro dokumentační uzavření se testy znovu nespouštěly a produkční kód se již neměnil.
