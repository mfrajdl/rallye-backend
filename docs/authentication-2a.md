# Etapa 2A: účty a lokální autentizace

> Aktuální pravidla: [specifikace 0.8.0](Zavody_specifikace_v0.8.0.md), D09 nahrazuje D04/D05 a D10 nahrazuje D03. Účet má jedinou neměnnou LOCAL/GOOGLE/APPLE identitu; link/unlink a dodatečný LOCAL jsou zakázané. LOCAL vlastní zrušení vyžaduje znovu heslo, externí zrušení platnou relaci a samostatné výslovné potvrzení, nikoli čerstvou reautentizaci. Níže uvedené dokončené etapy a výsledky zachycují tehdejší implementaci; DB omezení je nyní připravené v 3c.3a s uživatelem potvrzeným PostgreSQL ověřením BUILD SUCCESS; externí potvrzení je nyní implementované v [3c.3b](account-cancellation-3c3b.md), s uživatelem potvrzeným PG ověřením BUILD SUCCESS. Dopady a nový plán: [external-auth-3c.md](external-auth-3c.md).


Aktuální rozšíření: [schválená analýza 3c](external-auth-3c.md), aktuální rozhodnutí D09/D10 ve [specifikaci 0.8.0](Zavody_specifikace_v0.8.0.md). Následující text dokumentuje již implementovanou etapu; webové externí přihlášení přidává [3c.2](external-auth-3c2.md), ale externí potvrzení podle D10 přidává 3c.3b, Apple revokace zatím implementovaná není. D10 zachovává ověření LOCAL hesla a pro externí vlastní rušení zavádí relaci s explicitním potvrzením; ADMIN cesta nadále ověřuje jednajícího administrátora. D06 vyžaduje trvalou bezpečnou revokační práci i po odstranění účtu, bez obnovy jeho práv.

Implementace pod `cz.mh.rallye.rallye_backend.identity` vychází ze specifikace 0.5.0
a schválených upřesnění etapy 2A. Neobsahuje Google/Apple OAuth, posádky, závody,
změnu e-mailu ani zrušení účtu. Schéma pro další identity je připravené, ale samo
neumožňuje externí přihlášení nebo spojování účtů.

## Schéma a transakce

`V1__user_accounts.sql` vytváří pouze `user_account`, `user_identity`,
`user_action_token` a `user_session`, s UUID klíči, cizími klíči, unikátními indexy
a UTC časovými okamžiky s milisekundovou přesností. Persistence je implementována
pomocí JDBC v `identity.persistence.AccountStore`; JPA entity nejsou pro tyto
jednoduché dotazy potřeba a žádné perzistentní objekty se nevystavují jako REST DTO.
Stávající Data JPA konfigurace a `ddl-auto=validate` zůstávají zachované.

E-mail se při registraci/přihlašování normalizuje oříznutím okolních mezer a převodem
na malá písmena. Unikátnost v DB zabraňuje souběžnému vytvoření duplicit. Opakovaná
registrace nezmění heslo ani údaje existujícího účtu a vrátí stejné 202.
Nový účet je `PENDING_EMAIL`; až jednorázové ověření jej aktivuje. LOCAL identita
má neměnný subject odvozený z interního ID, nikoli z měnitelného e-mailu.

Hesla mají 8–128 Unicode znaků (code points), bez kategorií a bez ořezávání.
Hashuje je Spring Security Argon2id se solí; Bouncy Castle je potřebná Java závislost.
Náhodné tokeny mají 256 bitů entropie; v DB je pouze jejich SHA-256 digest, nikdy
otevřený token. Hash rychlého náhodného tokenu není náhradou Argon2id pro hesla.

Změny zamykají nejprve účet. Podmíněné spotřebování jednorázového tokenu,
aktivace/změna hesla a odvolání relací probíhají v jedné transakci. Token platí jen
před `expires_at` (přesně při expiraci už ne). Přihlášení i reset používají stejný
účetní zámek, aby nové přihlášení neobešlo odvolání relací. Změna hesla navíc
znovu ověří, že volající relace nebyla souběžně odvolaná. Reset zneplatní i ostatní
dosud nepoužité resetovací tokeny. Časy dodává injektovaný `Clock`.

## REST kontrakt

Všechny následující cesty jsou pod `/api/v1/auth`. Stavové operace jsou POST,
GET nikdy nespotřebovává aktivační/resetovací token. Chyby mají
`application/problem+json` a stabilní `code`, bez hesel, tokenů, SQL a stack trace.
Autentizační odpovědi mají `Cache-Control: no-store`.

| Cesta | JSON vstup | Výsledek |
| --- | --- | --- |
| `POST /register` | `email`, `password`, `displayName` | 202 bez těla; e-mail jen novému účtu. |
| `POST /email-verification-requests` | `email` | 202 bez ohledu na existenci/stav; opakované zaslání pouze čekajícímu účtu. |
| `POST /email-verifications` | `token` | 204; nesprávný, expirovaný nebo použitý token 400 `INVALID_OR_EXPIRED_TOKEN`. |
| `POST /login` | `email`, `password`, volitelné `rememberMe` (výchozí false) | Web: cookie a veřejné údaje vlastního účtu. Mobil: token viz níže. Neplatné údaje i neověřený účet 401 `INVALID_CREDENTIALS`. |
| `POST /logout` | bez těla, přihlášení | 204, serverové odvolání dané relace a smazání webové cookie. |
| `GET /me` | přihlášení | ID, e-mail a zobrazované jméno vlastního aktivního účtu. |
| `POST /password-reset-requests` | `email` | 202 bez těla pro existující i neexistující účet; e-mail aktivnímu LOCAL účtu. |
| `POST /password-resets` | `token`, `newPassword` | 204; resetovací odkaz platí 30 minut, jediné použití; odvolá všechny relace. |
| `POST /password-changes` | `currentPassword`, `newPassword`, přihlášení | 204; vyžaduje současné heslo, zachová právě volající relaci, odvolá ostatní. |
| `GET /csrf` | bez přihlášení | JSON `headerName`, `token` a CSRF cookie pro webový tok. |

`/api/hello` je nadále veřejné. Ostatní cesty mimo uvedené veřejné operace vyžadují
přihlášení. Změna e-mailu ani veřejné přidělení administrátorských práv nejsou
implementované. DTO s hesly nebo tokeny mají redigovaný `toString()`.

## Webové a mobilní relace

### Ruční testování v Postmanu

Importuj [Rallye-Auth-2A.postman_collection.json](../postman/Rallye-Auth-2A.postman_collection.json)
přes **Import → Files** do Postman Desktop. Backend musí běžet v profilu `local`
s vývojovým DB připojením. Kolekce sama aplikaci ani databázi nespouští.

V proměnných kolekce nastav `baseUrl` (výchozí `http://localhost:8080`, bez koncového
lomítka a bez `/api/v1/auth`), nový testovací `email` a lokální hodnoty `password`,
`newPassword`, `resetNewPassword` (8–128 Unicode znaků). Použij tři různá testovací
hesla. `displayName` má připravenou ukázkovou hodnotu. Hesla i tokeny jsou v souboru
prázdné. Nevytvářej stejnojmenné environment/global proměnné; skripty čtou proměnné
kolekce. Vyplněné hodnoty nesynchronizuj, nesdílej a neexportuj zpět do repozitáře.
Používej soukromý lokální workspace a po testu hodnoty vymaž.

Nech zapnutý **cookie jar**. V **Cookies → Domains Allowlist** přidej `localhost`
(bez protokolu a portu), případně přesný hostname z vlastního `baseUrl`; skript
potřebuje přístup ke kontrole CSRF cookie. Postman automaticky uchovává a posílá
cookies. Nemíchej `localhost` a `127.0.0.1` a nepřidávej Authorization ani mobilní
hlavičku. Nastavení cookie jar a allowlistu popisuje také
[oficiální dokumentace Postmanu](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-cookies/).

Požadavky odesílej jednotlivě přes **Send** v očíslovaném pořadí:

1. Složka **01**: registrace (202), volitelně opětovné zaslání ověření (202).
   Z nejnovějšího souboru `EMAIL_VERIFICATION` pro daný e-mail v
   `$HOME\.rallye\local-mail` zkopíruj pouze hodnotu za `#token=` do proměnné
   `activationToken`, bez mezery nebo konce řádku. Pak aktivace (204).
2. Složka **02**: přihlášení (200), `/api/v1/auth/me` (200), změna hesla (204),
   kontrola zachované relace (200), odhlášení (204) a nové přihlášení (200).
   Po úspěšné změně skript přepíše `password` hodnotou `newPassword`.
3. Složka **03**: žádost o reset (202). Z nejnovějšího souboru `PASSWORD_RESET`
   stejného účtu vlož hodnotu za `#token=` do `resetToken` a odešli reset (204).
   Kontrola staré relace musí vrátit 401. Následuje přihlášení novým heslem,
   `/me`, odhlášení a závěrečná kontrola 401. Po úspěšném resetu skript nastaví
   `password` na `resetNewPassword`.

Kolekce načítá před každým běžným POST nový CSRF token přes GET `/csrf`, zachová
odpovídající cookie a doplní hlavičku podle `headerName` z JSON odpovědi. Při chybě
načítání nebo nedostupné cookie hlavní požadavek přeskočí a zobrazí chybu skriptu.
První samostatný GET je pouze pro prohlédnutí odpovědi. Přihlašovací cookie
`RALLYE_SESSION` spravuje cookie jar; kolekce neukládá relační token do proměnných.
JSON těla mají připravené příklady a skript je skládá pomocí `JSON.stringify`,
takže i uvozovky v heslech zůstanou správně escapované.

Složku **04** spouštěj zvlášť podle potřeby. Obsahuje validaci vstupu (400),
neplatné aktivační/resetovací tokeny (400), chybné přihlášení (401) a úmyslně
nesprávný CSRF token (403). Pouze tento poslední scénář vynechá automatické načtení
CSRF, aby ověřil odmítnutí serverem; bezpečnostní konfigurace se nemění.
Odpověď prohlédni v **Body → Pretty/JSON**, očekávaný stav a `code` v **Test Results**.
Neopakuj scénáře v rychlé smyčce: platí běžné limity pokusů a případná odpověď 429.

Celou kolekci nepouštěj nepřerušeně Runnerem: aktivace a reset vyžadují ruční vložení
čerstvého tokenu. Pro další kompletní průchod použij nový e-mail a vymaž cookies
předchozího testu pro daný hostname. Opakovaná registrace existujícího účtu vrací
202, ale nemění heslo a neposílá novou zprávu; opětovné zaslání je samostatný endpoint.
Zprávy s použitými tokeny po testu odstraň. Skutečné SMTP zůstává odloženo před nasazení.

Codex kolekci ověřil staticky (JSON, syntaxe skriptů, endpointy a DTO proti kódu).
Uživatel následně potvrdil úspěšné manuální provedení všech scénářů v Postmanu,
včetně negativních. Etapa 2A je uzavřena; souhrn výsledků je v
[testing.md](testing.md#uzavření-etapy-2a). Skutečné doručování poskytovatelem SMTP
nebo e-mailovým API zbývá ověřit před produkčním nasazením.

Server eviduje každou relaci samostatně; nepoužívá JWT, Redis ani paměťové servletové
HttpSession pro přihlášení. Spring Security filter obnovuje identitu z hashovaného
relačního tokenu v DB. Stav účtu a odvolání se ověřují při každém požadavku.

- Běžná relace: nečinnost maximálně 24 hodin, při používání se tento interval
  obnovuje. Webová cookie je session cookie bez Max-Age, mobil token uchová jen
  během relace klienta.
- `rememberMe=true`: nečinnost maximálně 30 dní; každý autentizovaný požadavek
  obnoví serverovou expiraci a u webu také Max-Age cookie na 30 dní.
- Odhlášení odvolá relaci okamžitě na serveru. Změna hesla odvolá ostatní relace,
  obnova všechny. Již probíhající odpověď se tím zpětně neodvolá; navazující
  požadavky odvolanou relaci nepoužijí.
- Server nedokáže sám rozpoznat zavření prohlížeče/aplikace. Klient musí dodržet
  pravidla uchování tokenu, případně poslat logout; browser restore session může
  obnovit session cookies. Serverový limit 24 hodin platí nezávisle na klientovi.

Web používá `RALLYE_SESSION`, `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`.
Token se webovému JavaScriptu v login odpovědi nevrací. Web má běžet na stejném
originu jako API (případně přes vývojový proxy). Nejprve GET `/csrf`, uchovat
vrácenou cookie a posílat vrácený token v hlavičce uvedené v `headerName`.
Tímto postupem chránit i login, registraci a resetovací POST, ne jen přihlášené operace.

Mobil při loginu pošle `X-Rallye-Client: mobile`; odpověď obsahuje `token`,
`tokenType: Bearer`, `rememberMe`, bez session cookie. Další požadavky posílá s
`Authorization: Bearer <token>`. Token ukládat v privátním úložišti klienta,
při běžné relaci jej po jejím skončení zahodit. Nekombinovat session cookie a
Authorization: server takový požadavek odmítne jako `AMBIGUOUS_CREDENTIALS`.

CSRF výjimka platí pouze bez autentizační cookie při explicitní Bearer hlavičce
nebo mobilní hlavičce. Cookie autentizaci nevyjme ani mobilní hlavička. CORS se
neotevírá: web cizího originu nesmí získat výjimku přes vlastní hlavičky. Mobilní
veřejné POST používají `X-Rallye-Client: mobile`, protože nemají browserový CSRF tok.

## Omezení pokusů

Všechny pokusy (i úspěšné) se počítají v 15minutových oknech: nejvýše 10 na
normalizovaný účet a typ operace, 50 na zdrojovou IP a typ operace. Login tak nelze
obcházet střídáním zdrojů proti jednomu účtu nebo střídáním účtů z jednoho zdroje.
Omezeny jsou i registrace, ověření, žádosti o e-mail, reset a změna hesla; operace
bez e-mailu mají zdrojový limit. Odpověď je 429 s Retry-After. Paměť je omezená
na 10 000 položek; při zaplnění se další pokusy dočasně odmítají.

Jde o ochranu jedné instance, která se při restartu vynuluje. Při provozu více
instancí je nutná sdílená ochrana nebo limit na proxy. Používá se skutečná
`remoteAddr`, nedůvěřuje se libovolným X-Forwarded-For. Před provozem za proxy
navrhnout důvěryhodné předávání IP, jinak více uživatelů sdílí zdrojový limit.
Login pro neznámý účet provede náhradní Argon2 ověření a vrací stejnou chybu jako
nesprávné heslo. Reset nečeká na síťové SMTP spojení; odpověď neobsahuje existenci účtu.

## SMTP a aktivační odkazy

Výběr poskytovatele a ověření skutečného doručování jsou odloženy před nasazení.
Konfigurovatelný SMTP adaptér zůstává výchozím režimem mimo profil `local`.
Pro vývoj není potřeba externí SMTP ani další infrastruktura.

### Lokální registrace bez SMTP a bez webového klienta

S běžnou vývojovou DB konfigurací a SSH tunelem 5433 spusť:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Profil `local` nastaví `rallye.auth.mail-mode=local-file`, bind `127.0.0.1`
a cookies bez Secure pouze pro lokální HTTP. Ostatní profily mají Secure zachované.
Databázové ochrany IT se nemění; aplikaci nespouštěj s testovacími údaji místo vývojových.
Schránka je ve výchozím stavu `$HOME\.rallye\local-mail`, mimo repozitář.
Volitelný `RALLYE_AUTH_LOCAL_MAIL_DIRECTORY` musí také mířit mimo repozitář
do soukromého adresáře aktuálního uživatele. Na Windows zkontroluj jeho děděná ACL;
na POSIX se nové zprávy vytvářejí s oprávněním 600. Nepoužívej sdílenou složku.
Zprávy obsahují aktivační/resetovací odkazy v otevřeném tvaru, stejně jako e-mail;
neposílej je do chatu, logů ani Gitu a po ověření je odstraň. V DB zůstává pouze hash.
Souborová schránka vyžaduje profil `local` a výslovný loopback bind; nejde o veřejný endpoint.

V druhém PowerShellu proveď následující (API používáme jako mobilní klient bez cookies):

```powershell
$base = 'http://127.0.0.1:8080/api/v1/auth'
$headers = @{ 'X-Rallye-Client' = 'mobile' }
$email = 'local-' + [guid]::NewGuid().ToString('N') + '@example.test'
$password = [System.Net.NetworkCredential]::new('', (Read-Host 'Lokální testovací heslo (8–128 znaků)' -AsSecureString)).Password
$registration = @{ email=$email; password=$password; displayName='Lokální test' } | ConvertTo-Json
Invoke-RestMethod "$base/register" -Method Post -Headers $headers -ContentType 'application/json' -Body $registration
# Přihlášení před aktivací musí vrátit 401 INVALID_CREDENTIALS.
$credentials = @{ email=$email; password=$password } | ConvertTo-Json
try { Invoke-RestMethod "$base/login" -Method Post -Headers $headers -ContentType 'application/json' -Body $credentials | Out-Null } catch { Write-Host $_.Exception.Message }
# Vyber zprávu právě registrovaného účtu bez vypisování jejího obsahu.
$message = Get-ChildItem "$HOME\.rallye\local-mail\message-*.txt" |
    Where-Object { (Get-Content -LiteralPath $_.FullName -Raw).Contains("To: $email`n") } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $message) { throw 'Aktivační zpráva nebyla nalezena.' }
$text = Get-Content -LiteralPath $message.FullName -Raw
$token = [regex]::Match($text, '#token=([^\r\n]+)').Groups[1].Value
Invoke-RestMethod "$base/email-verifications" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{ token=$token } | ConvertTo-Json)
$login = Invoke-RestMethod "$base/login" -Method Post -Headers $headers -ContentType 'application/json' -Body $credentials
Invoke-RestMethod "$base/me" -Headers @{ Authorization="Bearer $($login.token)" }
Invoke-RestMethod "$base/logout" -Method Post -Headers @{ Authorization="Bearer $($login.token)" }
Remove-Item -LiteralPath $message.FullName
Remove-Variable password,registration,credentials,token,text,login
```

Aktivace vrací 204, přihlášení po aktivaci uspěje a `/me` vrátí vlastní účet.
Opakované použití téhož aktivačního tokenu vrací 400; limit 24 hodin zůstává zachován.
Odkazy v souborech míří na budoucí klientské stránky; bez klienta token předává výše
uvedený POST. Pro obnovu hesla se obdobně vytvoří zpráva po `/password-reset-requests`
a její token se předá `/password-resets`. Nikdy se nepřeskakuje serverové ověření.


| Proměnná | Účel |
| --- | --- |
| `RALLYE_SMTP_HOST`, `RALLYE_SMTP_PORT` | SMTP server; výchozí localhost:25 je jen konfigurace pro vývoj, žádná služba se neinstaluje. |
| `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | Přístupové údaje z prostředí. |
| `RALLYE_SMTP_AUTH` | Výchozí true. |
| `RALLYE_SMTP_STARTTLS_REQUIRED` | Výchozí true; nevypínat pro vzdálené SMTP. Jen lokální testovací SMTP může mít vědomě false. |
| `RALLYE_AUTH_MAIL_FROM` | Schválená adresa odesílatele; výchozí rallye@localhost je pouze vývojová. |
| `RALLYE_AUTH_PUBLIC_BASE_URL` | HTTPS origin/cesta webového klienta; lokální HTTP dovoleno jen pro localhost/127.0.0.1. Výchozí http://localhost:3000. |
| `RALLYE_AUTH_SECURE_COOKIES` | Výchozí true. False pouze pro vědomý lokální HTTP vývoj, nikoli nasazení. |

Aktivační e-mail má platnost 24 hodin. Odkazy míří na klientské `/verify-email`
a `/reset-password` s `#token=...`, takže token není v HTTP query/access logu.
Stránky klienta nejsou součástí backendové etapy; klient přečte fragment a odešle
token v JSON příslušného POST. GET odkazu nic nepotvrzuje ani nemění heslo.

E-mail se předává k odeslání až po potvrzení DB transakce. SMTP běží v omezeném
executor poolu (1–2 vlákna, fronta 100), s timeouty 5 sekund, bez debug logování.
Chyba SMTP ani plná fronta nevrací existenci účtu; log obsahuje jen obecné varování
bez adresy, tokenu nebo exception detailů. Token je v paměti pouze po dobu odeslání.
Fronta není trvalá; při pádu procesu/SMTP chybě požádat o nové ověření/reset.
Původní registrace po SMTP chybě nezaniká a její heslo nelze přepsat opakováním registrace.
Skutečné SMTP připojení nebylo bez dodaných údajů ověřeno; automatické testy nikdy
neodesílají skutečné e-maily.

## Ověření a navazující práce

Výchozí `mvnw.cmd verify` používá pouze unit a MVC slice testy bez DB. Integrační
`IdentityIT` a test kontextu se spouštějí jen `-Pit verify` s původním
`TestDatabaseInitializer` a explicitními `RALLYE_TEST_DB_*`. Viz [testing.md](testing.md).
IT používají unikátní účty a uklidí pouze vlastní fixture data; nikdy nemažou
schéma, Flyway historii nebo cizí záznamy. Souběžné testy ověřují skutečné DB zámky.

**V1 je nyní skutečná migrace.** Start běžné aplikace by ji aplikoval na cílovou DB
z běžné konfigurace. V této etapě se aplikace proti rallye_dev/rallye nespouští.
Integrační ověření smí měnit výhradně rallye_test; nevypínat ochrany, nepoužívat
Flyway clean, baseline-on-migrate, repair ani automatické DDL.

Navazující úkoly: změna e-mailu s opětovným ověřením, zrušení/anonymizace účtu,
Google/Apple a bezpečné propojování identit, správce oprávnění a admin bootstrap,
frontendové stránky odkazů, provozní úklid expirovaných tokenů/relací, případná
trvalá fronta e-mailů a sdílený limiter při více instancích. Tyto funkce nejsou
v etapě 2A implementovány a nezavádějí další pravidla závodů.

## Soubory změněné v etapě 2A

- `pom.xml`: Spring Security, Mail, Bouncy Castle a security test support.
- `src/main/java/cz/mh/rallye/rallye_backend/RallyeBackendApplication.java`: vypnutí výchozího generovaného uživatele Spring Boot, aby nezůstalo alternativní in-memory přihlášení.
- `src/main/resources/application.properties`: SMTP host/port, timeouty a bezpečné výchozí STARTTLS; původní DB konfigurace zachována.
- `src/main/resources/db/migration/V1__user_accounts.sql`: čtyři tabulky účtů, identit, jednorázových tokenů a relací.
- `identity/api`: AuthController, AuthConfiguration, AuthCookies, AuthSessionFilter a ApiErrors.
- `identity/application`: AuthService, AuthFailure, PasswordPolicy, SecretTokens, LoginThrottle, AccountMailSender a SmtpAccountMailSender.
- `identity/persistence/AccountStore.java`: JDBC dotazy a zámky.
- `src/test/java/cz/mh/rallye/rallye_backend/identity`: AuthServiceTest, AuthPrimitivesTest, AuthHttpTest, AccountMailTest, IdentityIT a MutableClock.
- `AGENTS.md`, `README.md`, `docs/backend-plan.md`, `docs/testing.md`, `docs/postgresql-test-setup.md` a tato dokumentace: stav implementace, kontrakt a návody.

Schválená specifikace 0.5.0 se v této etapě nepřepisovala. Stávající ochranné třídy testovacího připojení zůstaly beze změny.
## Výsledky ověření etapy 2A (21. 10. 2026)

Aktuální stav po doplnění lokální souborové schránky:

- Codex spustil `mvnw.cmd verify` s JDK 21: **68 testů, 0 failures, 0 errors, 0 skipped**, sestavení JAR úspěšné.
- Uživatel potvrdil `.\mvnw.cmd -Pit verify` proti vyhrazené databázi `rallye_test`: **BUILD SUCCESS**, včetně nového `LocalMailboxIT`. Jde o uživatelem potvrzený výsledek, nikoli vlastní integrační běh Codexu. Integrační ověření lokální schránky je uzavřeno.
- Výběr SMTP poskytovatele a ověření skutečného doručování zůstávají odloženy před nasazení. Tento běh je neověřuje.

Předchozí ověření před doplněním lokální schránky (historický záznam):

- Čistý build `mvnw.cmd clean verify` s JDK 21 prošel; po posledních doplněných regresních testech také `mvnw.cmd verify`: **65 testů, 0 failures, 0 errors, 0 skipped**, spustitelný JAR vytvořen.
- `mvnw.cmd -Pit verify`: 65 lokálních testů prošlo; všech 12 integračních případů (11 IdentityIT + 1 test kontextu) nemohlo načíst kontext, protože chybí povinné RALLYE_TEST_DB_ENABLED=true a ostatní testovací proměnné. Guard selhal před připojením, takže migrace ani testy nebyly vykonány proti žádné databázi. Jde o blokaci prostředím, nikoli o úspěšné PostgreSQL ověření.
- Testovací proměnné nebyly nalezeny ani v uživatelském/strojovém environmentu Windows; listener tunelu 5434 nebyl v této relaci dostupný. Nebyly hledány ani použity vývojové/produkční přihlašovací údaje.
- Uživatel následně potvrdil funkční tunel a úspěšné `mvnw.cmd -Pit verify` ve svém PowerShellu (`BUILD SUCCESS`). PostgreSQL integrační ověření je tedy potvrzené uživatelem; nejde o vlastní úspěšný běh Codexu. Předchozí selhání popisuje pouze prostředí Codexu.
- `git diff --check` a whitespace kontroly nových Java/SQL/dokumentačních souborů prošly. Skutečné SMTP připojení zůstává neověřené; integrační testy používají testovací odesílač.
- Maven nadále vypisuje metadata HTTP 401 z Flyway/Red Gate zdrojů a Mockito upozornění na dynamické připojení Java agenta; nebrání lokálnímu sestavení/testům.
- Žádný commit, push, deployment, cloudová změna ani přístup Codexu k rallye_dev/rallye. Další integrační běhy provádí uživatel ručně příkazem `.\mvnw.cmd -Pit verify` se zachováním ochranných kontrol. Codex při potřebě nového ověření předá příkaz a počká na výsledek; vlastní přístup k PostgreSQL nezajišťuje. Kompilaci a testy bez databáze spouští Codex.
