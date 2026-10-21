# Backend: architektura a plán po etapě 2

Aktuální funkční základ je [specifikace 0.5.0](Zavody_specifikace_v0.5.0.md). Vydání 0.4.0 a starší i dosavadní pracovní kopie Rallye_specifikace.md jsou ponechány beze změny. Pro nové požadavky je rozhodná verze 0.5.0; AGENTS.md a README.md již odkazují na tuto závaznou verzi. Potvrzené požadavky specifikace mají přednost před technickými doporučeními níže. Návrh neznamená implementaci ani schválení cloudových změn.

## Stav projektu

**Etapa 2A – lokální autentizace je dokončena.** Automatické testy bez databáze
prošly (68 testů, `mvnw.cmd verify`, JDK 21). Uživatel potvrdil úspěšné integrační
testy nad `rallye_test` včetně lokální schránky (`mvnw.cmd -Pit verify`, BUILD SUCCESS)
a všechny manuální scénáře Postman kolekce včetně negativních případů 400/401/403.
Podrobný záznam je v [testing.md](testing.md#uzavření-etapy-2a).

Otevřený bod před produkčním nasazením: lokální souborové doručování nahradit
skutečným poskytovatelem SMTP nebo e-mailovým API a ověřit reálné doručování.
Existující konfigurovatelný SMTP adaptér je připravený; lokální profil zůstává
vývojovou pomůckou. Tento bod nebrání uzavření lokální autentizace.

Java 21, Spring Boot 4.1.1, Maven Wrapper 3.10.0, Spring MVC, Validation, JPA, Flyway a PostgreSQL driver. Produkční kód nyní obsahuje vedle startovací aplikace a veřejného GET /api/hello také modul identity etapy 2A. Produkční konfigurace zůstává zachována: PostgreSQL přes `127.0.0.1:5433/rallye_dev`, heslo z prostředí, Hibernate `validate`, Open Session in View vypnuté, Flyway zapnuté. Implementované jsou uživatelské účty, LOCAL identita, e-mailové tokeny, databázové relace a migrace V1__user_accounts.sql; Google/Apple OAuth, posádky, závody, změna e-mailu a zrušení účtu zatím nejsou implementované.

Etapa 2 odděluje testy bez DB od explicitních `*IT` přes Maven Failsafe v profilu `it`. Připojení IT kontroluje testovací initializer před vytvořením Spring beanů. Etapa 2A přidává Spring Security, SMTP a Bouncy Castle pro Argon2id. Failsafe používá verzi spravovanou Spring Boot parentem. Podrobnosti a omezení jsou v [testing.md](testing.md).

Pro etapu 3a je připraven [OCI postup](postgresql-test-setup.md) a SQL v `ops/postgresql-test/`. Použije se stávající `rallye-postgres-1`, oddělená DB/role `rallye_test` a tunel 5434. PUBLIC CONNECT/TEMPORARY v aplikačních DB jsou akceptované; nepřidávají se explicitní aplikační granty, nemění se jejich ACL, HBA, porty ani firewall. Kontroluje se nepřístupnost aplikačních tabulek/sloupců a dalších možných cest k datům read-only skriptem 25 v obou DB. Na OCI nebyly provedeny změny. Testovací initializer a ostatní ochrany zůstávají beze změny.

## Cílové balíčky

Vše zůstává pod `cz.mh.rallye.rallye_backend`:

| Balíček | Odpovědnost |
| --- | --- |
| `identity` | Samostatný účet s neměnným ID, e-mail/heslo a Google/Apple, ověření adresy, propojené identity, obnova, relace a anonymizace. |
| `crews` | Více trvalých posádek na účet, vozidlo, převody kapitána, smazání pouze bez účasti; bez archivace a trvalých členů. |
| `templates` | Šablony závodů, stanoviště, konfigurace a statistická verze. |
| `races` | Konkrétní konání a nezávislé kopie stanovišť, start a společný konec. |
| `participation` | Přihláška přes odkaz, historický název, members_text a children_under_15, kopie z libovolné předchozí účasti, limit členů, uzamčení a pozdní zařazení. |
| `competition` | Nevratný oficiální start, startovací/náhradní kódy, závodní zařízení, podepsané bodové úpravy, GPS/CODE, přeskočení a zrušení. |
| `results` | Průběžné výsledky jen administrátorovi, soutěžní pořadí 1, 2, 2, 4, ruční úplné remízy, verzované ruční publikace a veřejný archiv. |
| `audit` | Historie důležitých změn a bezpečnostních událostí. |
| `statistics` | Medián dob od pěti platných vzorků, očekávané časy a dashboardové dotazy. |
| `administration` | Jen skutečně samostatné administrativní případy užití, bez duplikace cizích pravidel. |
| `shared` | Malá sdílená technická podpora, například formát chyb; žádný univerzální obchodní framework. |

V každé oblasti podle potřeby `api` (controller a request/response DTO), `application` (případy užití, transakce), `domain` (pravidla) a `persistence` (JPA/repository). REST nevystavuje JPA entity. Oddělený doménový model od JPA zavádět jen tam, kde jej ospravedlňuje složitost pravidel. Mezi moduly používat explicitní aplikační operace a identifikátory, bez přímého zápisu do cizích repository a cyklů. Nezavádět mikroslužby, CQRS, message broker ani Spring Modulith bez konkrétní potřeby.

## Autentizace a relace – technické doporučení

Etapa 2A používá Spring Security a neprůhledné databázové relace s hashovaným tokenem; OAuth2 Client/OIDC je návrh až pro budoucí externí přihlášení; nebudovat vlastní autorizační server ani infrastrukturu JWT s obnovovacími tokeny. Jeden interní `app_user` může mít místní hash hesla a několik ověřených externích identit. Členové účasti nejsou identity. Lokální heslo se hashuje Argon2id přes Spring Security PasswordEncoder; povolená délka je schválených 8–128 Unicode znaků bez kategorií. Registrace uživatelského účtu e-mailem vyžaduje ověření adresy jednorázovým aktivačním odkazem zaslaným e-mailem; obnova hesla používá krátkodobý jednorázový náhodný token uložený jako hash. Obnova hesla má potvrzenou platnost 30 minut, jednorázové použití a jednotnou odpověď bez odhalení existence účtu. Login také nerozlišuje chybný e-mail a heslo. Změna e-mailu vyžaduje nové ověření uživatele i nové adresy. Potvrzovací odkaz má schválenou platnost 24 hodin. Výběr poskytovatele a ověření doručování jsou odloženy před nasazení; lokální profil používá souborovou schránku bez SMTP.

Web provozovat pokud možno na stejném originu jako API. Relace přes `Secure`, `HttpOnly`, přiměřený `SameSite` cookie; stavové požadavky chránit CSRF tokenem v hlavičce. Při externích callbackách zvlášť ověřit chování Apple `form_post` a cookie SameSite; neuvolňovat globálně CSRF. Přihlašování i odhlašování mění relaci bezpečně; regenerovat identifikátor po přihlášení. Neumisťovat session ID do URL nebo browser localStorage.

Mobil v etapě 2A získává samostatnou relaci přes X-Rallye-Client: mobile a používá Authorization: Bearer. Web má zabezpečenou HttpOnly cookie a CSRF ochranu; oba způsoby se v jednom požadavku nekombinují. Tajemství ukládat prostředky Android Keystore/iOS Keychain podle volby uchování. Následující externí tok je pouze návrhem pro další etapu. Externí přihlášení otevře systémový browser, nikoli embedded webview. Backend provede authorization code tok s Google/Apple, validuje issuer, audience, podpis, čas, state a nonce; PKCE použít tam, kde jej daný tok poskytovatele podporuje. Browserová relace se do mobilu nepředává přes URL: navržen je krátkodobý jednorázový předávací kód vázaný na mobilní PKCE challenge, výměna za mobilní relaci přes HTTPS a ověřené app/universal links. Prototyp musí ověřit Google i Apple specifika včetně Apple client-secret JWT a první odpovědi se jménem. Tato část je návrh, nikoli hotový kontrakt.

Etapa 2A místo původně zvažovaného Spring Session JDBC používá jednoduchou tabulku user_session s digestem náhodného tokenu a integrací do Spring Security filter chain. Tím se relační tajemství neukládá do DB v otevřené podobě a neukládá se serializovaný SecurityContext. Běžná relace má maximální nečinnost 24 hodin a session cookie/uchování jen během klientské relace. Trvalá má 30 dní nečinnosti a obnovuje se při používání. Serverové odhlášení je okamžité; změna hesla odvolá ostatní relace, obnova všechny. Relace přežijí restart serveru, protože autorita je v DB. Detaily API, cookies/Bearer, SMTP a omezení pokusů jsou v [authentication-2a.md](authentication-2a.md).

## Bezpečné propojení identit

Externí identitu identifikovat `(issuer, subject)`, s databázovou unikátností, nikdy jen shodným e-mailem. Apple relay e-mail není důvod ke spojení účtů; e-mail může být odlišný a jméno se při dalších přihlášeních nemusí vrátit. Neukládat nepotřebné scopes/profil ani hesla či dlouhodobé tokeny poskytovatelů. Jméno načtené při prvním přihlášení zachovat, prázdná další odpověď ho nesmaže.

Propojení vyžaduje aktivní účet, čerstvé ověření jeho identity a samostatně ověřený tok druhého způsobu přihlášení, s CSRF/state/nonce a vazbou na cílový účet. Pokud identita již patří jinému účtu, vrátit konflikt; žádné tiché slučování. Stejný e-mail může vyvolat nabídku přihlášení existujícím způsobem, sám není důkazem identity. Paralelní propojení řeší unikátní omezení a transakce. Auditovat propojení/odpojení bez tokenů. Nedovolit odpojit poslední použitelný způsob bez bezpečné náhrady; obnova hesla a převody už mají potvrzená pravidla ve specifikaci 0.5.0, detaily odpojení poslední identity zůstávají technickým návrhem.

## Autorizace

Dvě schopnosti: kapitán jedná za své posádky a jejich účasti; administrátor spravuje šablony, konání, kódy a operativní zásahy. Server načítá vlastnictví podle přihlášeného interního ID, nevěří `captain_user_id` ani rolím v klientském payloadu. Na každém zdroji i historickém kopírování ověřuje přístup; ID samo přístup nedává. Administrativní oprávnění se nepřiděluje veřejnou registrací nebo e-mailem poskytovatele. Bootstrap prvního administrátora provést řízeným samostatným postupem, nikoli veřejným endpointem. Kapitán může mít také administrativní oprávnění, pokud to bude schváleno; nepředpokládat to automaticky.

## Rozlišení registrace účtu, přihlášky a závodních kódů

| Operace | Požadované ověření |
| --- | --- |
| Registrace uživatelského účtu e-mailem | Jednorázový aktivační odkaz zaslaný e-mailem k ověření adresy. |
| Registrace posádky do závodu | Přihlášený kapitán podá přihlášku za svou posádku, bez aktivačního kódu. |
| Start posádky do závodu | Jednorázový startovací kód předaný organizátorem mimo aplikaci. |
| Převzetí rozehraného závodu na jiném zařízení | Náhradní startovací kód; vydání neodpojí původní zařízení, až úspěšné použití. |

Odstranění aktivačního kódu se týká pouze registrace posádky do závodu. Ověření e-mailu při registraci uživatelského účtu zůstává povinné. Aktivační odkaz účtu, odkaz na závod a startovací/náhradní kód mají různé účely a nesmějí se zaměňovat.

## Dopady potvrzených pravidel 0.5.0 na data a přístup

| Oblast | Návrh a požadované záruky |
| --- | --- |
| Účet | Neměnné interní ID, přihlašovací e-mail, ověření adresy, tokeny jen jako hash; reset 30 minut. Zrušení odstraní/anonymizuje e-mail, zobrazované jméno a přihlašovací identity samotného účtu. Historické posádky, názvy, výsledky a interní seznamy členů přihlášek zůstanou; veřejné projekce neobsahují jména členů. |
| Posádka | Více posádek na účet, aktuální captain_user_id nullable po zrušení účtu. Bez účasti lze smazat, s historií zachovat; bez aktuálního kapitána nelze podat novou přihlášku. Žádný archived příznak/workflow. |
| Převod | crew_transfer: původní/cílové interní ID, hash tokenu a expirace 7 dní, stav; částečný unikátní index pro aktivní žádost. Přijetí jen správným přihlášeným účtem, atomická změna vlastníka a audit. |
| Účast | crew_name_at_registration jako historický snímek, members_text a children_under_15; počet členů je počet neprázdných řádků, počet 15+ jejich rozdíl. individual_started_at nastavit jednou při úspěšném původním startovacím kódu a nikdy nepřepsat náhradním. Žádná tabulka participant_member ani příznak dítěte u jednotlivých jmen. Kopie z historie není živá vazba. |
| Limity | max_crew_members v konfiguraci závodu, výchozí 5; návrh kopie šablony do konání. Nejméně jeden člen, children_under_15 mezi 0 a počtem členů. Složení uzamknout při oficiálním startu, u pozdní účasti při zařazení. |
| Zařízení | participation_code typu START/REPLACEMENT a race_device_authorization oddělené od user_session. Více account relací neznamená více platných oprávnění řídit účast. Token předání mobilní login relace z technického návrhu není startovací/náhradní kód závodu. |
| Body a remízy | Samostatná score_adjustment s podepsanými body a vazbou na náhradní kód; účinná právě jednou při úspěšném použití náhradního kódu atomicky s převodem závodního oprávnění. Nepoužitý/zneplatněný nepoužitý kód má nulový dopad. tie_decision uchová vzájemné pořadí dvou i více posádek a vazbu na příslušnou úplnou remízu; audit a nepovinný interní důvod. Při přepočtu použít, jen pokud remíza trvá; po jejím zániku rozhodnutí pořadí neovlivňuje. |
| Publikace | result_publication/result_entry pro verze definitivních výsledků, veřejná projekce bez jmen členů. Oprava vytváří novou publikaci při zachování historie. |

Při správě posádky a jejích účastí ověřovat aktuální vlastnictví ze stavu DB, nikoli dlouhodobě
z role nebo crew ID uložených v relaci. Nový kapitán dostává ihned správcovská práva k posádce a jejím účastem, původní je ztrácí i při stále přihlášeném účtu. Aktivní závodní zařízení se převodem nemění a pokračuje; k jeho výměně je nutný náhradní startovací kód administrátora. Autorizaci správy posádky oddělit od závodních příkazů autorizovaných aktivním zařízením, aby změna vlastníka nezneplatnila pokračování dosavadního zařízení. Souběh přijetí/odmítnutí/zrušení/expirace
převodu musí mít jediný transakční výsledek. Odeslání e-mailu navrhnout po commitu
s bezpečným opakováním, bez tokenů v logu; konkrétní infrastruktura není předepsaná.

Veřejně povolit pouze publikované výsledky a schválený rozsah archivu.
Plánovaná/probíhající konání veřejně nenabízet; přihláška vyžaduje přihlášený účet
s odkazem, bez seznamu povolených e-mailů. Oddělit veřejná a interní DTO i dotazy,
aby jména členů a interní důvody neunikly. Soukromé endpointy zůstávají autorizované.

## Akceptační scénáře pro budoucí implementaci

- Ověření adresy, reset do 30 minut a jediné použití; login i reset bez enumerace účtů; změna e-mailu zachová interní ID.
- Více nezávislých souběžných relací, bez volby jen relace aplikace/prohlížeče, s volbou až 30 dní a prodlužování používáním; odhlášení odvolá danou relaci okamžitě, změna hesla ostatní, reset všechny.
- Více posádek na účet, mazání pouze bez účasti, zachování historického názvu, výsledků a interních seznamů členů i po zrušení účtu; anonymizace pouze údajů účtu a zákaz nové přihlášky bez kapitána.
- Převod po přihlášení správným účtem do 7 dní, jedna aktivní žádost, audit a okamžité odebrání správy původnímu vlastníkovi při zachování aktivního závodního zařízení, včetně souběhu požadavků.
- Text členů s prázdnými řádky, minimum/maximum, agregovaný počet dětí, kopie z nedokončené účasti bez změny historie a uzamčení při startu.
- Startovací kód před startem není spotřebován; nevratný oficiální start; náhradní kód neodpojí při vydání, ale až při úspěšném použití, včetně souběžných pokusů a opakování HTTP.
- Bodová úprava přijme kladné, nulové i záporné celé body bez automatické penalizace; projeví se právě jednou až použitím kódu a rollbackuje se spolu s neúspěšnou výměnou zařízení. Nepoužitý/nahrazený nepoužitý kód skóre nemění.
- Pořadí 1, 2, 2, 4 a tie-break podle počtu 15+, ruční rozřazení dvou i více posádek (např. 1, 2, 2, 4 na 1, 2, 3, 4), zachování přes přepočty jen při trvání remízy a audit; průběžné výsledky jen adminovi.
- Publikace pouze ručně, oprava novou verzí; anonymní čtení výsledků a archivu bez jmen členů a interních důvodů; přihlášky přes odkaz bez e-mail allowlistu.

## Idempotence, transakce a čas

Navržená tabulka idempotence: interní účet, typ operace, zdroj a klíč s unikátním omezením; hash kanonického významového payloadu včetně cílového zdroje a uložený HTTP status/bezpečné response DTO. Identita klíče musí zabránit opakovanému použití při přechodu na další stanoviště. Po autorizaci nejprve hledat dřívější výsledek, teprve pak pro nový požadavek rozhodovat o uzávěrce a stavu. Shodný klíč/payload vrací původní výsledek i po `end_at`, rozdílný obsah je konflikt 409. Výsledek, změna stavu, penalizace i audit se uloží v jedné transakci. Neočekávané technické selhání transakci rollbackuje; nezůstane trvalý rozpracovaný klíč bez výsledku. Při závodu o unikátní klíč nepokračovat v již neplatné transakci: po rollbacku načíst potvrzený výsledek v nové transakci.

Pro krátké změny průchodu navrženo řádkové zamykání účasti a konzistentní pořadí zámků (konání, účast, průchod); `@Version` pro úpravy konfigurace. Přihláška vzniká přes odkaz bez startovacího kódu, s unikátní dvojicí konání+posádka. Původní startovací kód se atomicky spotřebuje až po oficiálním zahájení a jednou nastaví individual_started_at; předčasné zadání ho zachová. Náhradní kód tento čas nesmí přepsat. Individuální start slouží pro skutečnou dobu absolvování a časové odhady, nikdy neposune end_at. Náhradní kód při vydání neodpojí původní zařízení, až jeho úspěšné použití v jediné transakci odvolá staré závodní oprávnění, aktivuje nové a právě jednou započte podepsanou bodovou úpravu. Navržené unikátní omezení score_adjustment na použitý náhradní kód a uložení idempotentního výsledku zabrání duplicitě při souběhu/opakování HTTP. Nepoužitý kód ani jeho zneplatnění skóre nemění. Nejvýše jeden platný nepoužitý náhradní kód, bez vlastní expirace. Převod kapitána nemění race_device_authorization ani individual_started_at. Přijetí žádosti je atomické, s nejvýše jednou aktivní žádostí na posádku. Uzamčení členů a start musejí sdílet synchronizační bod; kontrola času pouze v klientovi nebo před transakcí nestačí. Totéž pro pozdní zařazení, uzávěrku a zrušení stanoviště. Stanovit limity čekání zámků a bezpečné opakování po deadlocku pouze pro celou operaci s idempotencí.

`Instant` v UTC, databázové `timestamptz(3)`, doby v celých milisekundách, body v kontrolovaném celočíselném rozsahu (návrh `bigint`/`long`). Měřit jedním injektovaným `Clock` a zaokrouhlením okamžiku na milisekundy. DELAYED počítá nejprve nezáporný rozdíl v ms, poté počet započatých sekund, poté násobí body. Ověřit přetečení. Statistiky nepoužívat pro body.

**Koordinace příjmu s uzávěrkou je podmínkou etapy průběhu:** čas `received_at` po autentizaci a validaci zachytit na serveru před čekáním na obchodní zámky. Návrh: příkazy s tímto okamžikem trvale zařadit do jednoduché PostgreSQL fronty (stejný idempotentní záznam), pak je sekvenčně vyhodnotit; uzávěrka musí zpracovat všechny včas přijaté příkazy před finalizací. Je nutné definovat bod „přijetí“ vůči potvrzení zápisu a pádu procesu. Samotný plánovač, který v `end_at` zamkne konání, nezaručuje přijetí požadavku čekajícího před uzávěrkou. Pokud se zvolí synchronní varianta bez fronty, musí prototyp prokázat stejné záruky i při restartu; do té doby tato část zůstává technicky otevřená. Včasnost je `received_at <= end_at`; výsledek dřívějšího klíče se vrací i později. Nezavádět frontu v etapě 2 ani měnit potvrzenou hranici kvůli implementačnímu pohodlí.

## Jednotné chyby REST API

Navrženo `application/problem+json` (Spring `ProblemDetail`, RFC 9457) se standardními `type`, `title`, `status`, `detail`, `instance` a rozšířeními `code`, `traceId`, případně `violations` s polem a stabilním kódem. Bez stack trace, SQL, tokenů, správných kódů a citlivých vstupních hodnot. `instance` má být cesta bez citlivého query. `code` je stabilní pro klienta, text lze lokalizovat. `@RestControllerAdvice` i Spring Security entry point/denied handler musí vracet stejný formát.

| HTTP | Příklad významu |
| --- | --- |
| 400 | Neplatný JSON, parametry nebo DTO validace. |
| 401 | Chybějící/neplatné přihlášení, bez redirectu pro REST. |
| 403 | Chybějící oprávnění či neplatný CSRF. |
| 404 | Nedostupný zdroj; pro cizí soukromý zdroj sjednotit odpověď proti enumeraci. |
| 409 | Konflikt stavu, změněná verze, idempotency key s jiným payloadem, již propojená identita. |
| 429 | Omezení pokusů; `Retry-After`. |
| 500/503 | Neočekávaná chyba/dočasná nedostupnost, klient může opakovat tentýž klíč. |

Přesné obchodní error codes a status uzávěrky potvrdit spolu s API kontraktem. Přijatý neúspěšný GPS/CODE pokus je běžný uložený výsledek pokusu, nikoli technická HTTP chyba.

## Audit

Navržena append-only tabulka: ID události, UTC čas, interní aktér nebo systém, typ operace, typ/ID zdroje, correlation/request ID, důvod a omezený strukturovaný přehled změn. Transakčně s měněným stavem. Zahrnout start/konec, start a použití/náhradu kódu, výměnu závodního zařízení, podepsanou bodovou úpravu, převody kapitána, publikace/opravné verze a ruční remízy, pozdní výjimku, uzamčení členů, změny textu, zrušení, neutralizaci/nahrazení penalizací, propojení identit a bezpečnostní události. Nezaměňovat nezměnitelnou historii pokusů s účinným skóre. Vyloučit hesla, tokeny, platné tajné kódy a nadbytečné osobní údaje. Čtení auditu omezené administrací, retence a provozní role k rozhodnutí. Protokol serveru sám nenahrazuje transakční audit.

## Fotografie a HTML

Pro první jednu instanci navržen privátní adresář na perzistentním Docker volume mimo webroot; v DB pouze náhodný neprůhledný identifikátor a bezpečná metadata. Žádná veřejná cesta podle názvu uploadu; stahování přes autorizovaný endpoint podle vlastnictví/administrativních oprávnění. Později lze přejít na privátní objektové úložiště bez změny veřejných ID. Fotografie zálohovat spolu s konzistentní DB evidencí, odděleně od image aplikace.

Povolit pouze potřebné rastrové formáty (navržen JPEG/PNG), ověřit skutečné dekódování, limit bajtů i rozměrů, znovu zakódovat a odstranit EXIF/GPS. Nepovolovat SVG/HTML jako fotografii. Použít bezpečné dočasné soubory, atomický přesun a úklid osiřelých objektů po selhání DB transakce; klientovi nevracet cesty. Přesné limity a retence čekají na rozhodnutí. HTML instrukce sanitizovat na serveru podle malé allowlist s bezpečnými URL; správné kódy ani odpovědi nesmí být v klientském DTO, HTML ani logu.

## Rizika a zbývající rozhodnutí

- Blokující pro databázové IT: v této běhové relaci nejsou dostupné proměnné RALLYE_TEST_DB_* ani testovací tunel 5434; existující rallye_test se nevytváří ani nemění administrativním postupem. Výchozí unit testy to neblokuje. Cloudové změny nejsou provedeny.
- Blokující před první migrací: pouze pro čtení ověřit existující schéma a Flyway historii vývojové DB se souhlasem k přístupu; žádný automatický baseline/repair/clean.
- Etapa 3: schválit technický návrh relací a registrace/obnovy, e-mailové doručování, Google/Apple registrace aplikací a callbacky, bootstrap administrátora. Více posádek na účet, převody s odkazem na 7 dní, reset na 30 minut a pravidla odvolání relací jsou již potvrzené. Poskytovatelské klíče mimo repozitář.
- Před etapou účastí/průběhu: rozhodnout nejednoznačnosti oddílu 14 specifikace, atomickou uzávěrku a příjem příkazů. Nově potvrzená pravidla se znovu neotevírají.
- Před statistikami: přesné vyjádření mediánu při sudém počtu vzorků, pokud vyjde půl milisekundy; neupravovat kvůli tomu zaznamenané doby.
- Stanovit retenci auditu/idempotence/souborů, limity uploadů, relací a hádání kódů, ochranu a obnovu záloh.
- Maven v minulém prostředí vybíral Javu 17 a PATH Javu 11: explicitně ověřovat JDK 21. Metadata některých zdrojů Flyway hlásí HTTP 401; nevkládat přístupové údaje do projektu.
- Budoucí ARM64 ověřit při kontejnerizaci; lokální Windows testy nevyžadují Docker, WSL ani systémové instalace.

## Následující samostatně ověřitelné etapy

| Etapa | Rozsah a ověření |
| --- | --- |
| 2A – lokální autentizace (dokončena) | Účty, LOCAL identita, ověření e-mailu, obnova a změna hesla, databázové relace, cookies/Bearer a CSRF. Automatické i manuální ověření úspěšné; skutečné doručování zbývá před nasazením. |
| 3a – infrastruktura testů (testovací připojení ověřeno) | Vyhrazená DB/role rallye_test je dostupná a uživatel potvrdil úspěšné IT včetně lokální schránky. Postup a read-only kontroly oprávnění zůstávají v ops/postgresql-test a postgresql-test-setup.md; úspěch IT sám nedokládá provedení všech administrátorských kontrol. Žádné další cloudové změny nespouštět automaticky. |
| 3b – vlastnictví a posádky (následující implementační etapa) | Navázat na dokončené účty a lokální autentizaci 2A, neopakovat jejich migraci. Admin bootstrap, více posádek bez trvalých členů, převody (lze vyčlenit do 3d), bezpečná DTO a audit. Před implementací uzavřít relevantní zbývající rozhodnutí. Testy cizích zdrojů, oprávnění, duplicit, souběhu a transakčního rollbacku na izolované DB. |
| 3c – externí přihlášení | Google a Apple, minimální údaje, bezpečné propojení/konflikty identit a mobilní předání relace. Automatické testy validace tokenů/state/nonce a souběhu propojení; manuální smoke s oběma poskytovateli až po dodání konfigurace. |
| 4 – šablony | GPS/CODE, časová pravidla, pořadí a statistické verze. Testy DTO validace, ms hranic bodování a rozsahu; žádné kvízy. |
| 5 – konání a účasti | Kopie šablony, přihláška přes odkaz, historický název, text členů a počet dětí, maximum (výchozí 5), kopie i z nedokončené účasti a uzamčení. Testy počítání řádků, limitů, unikátnosti a souběhu oficiálního startu s úpravami. |
| 6 – průběh | Ruční nevratný start konání, startovací/náhradní kódy a oprávnění zařízení, podepsané bodové úpravy bez automatické penalizace, GPS/CODE, idempotence, přeskočení, zrušení a uzávěrka. Testy předčasného kódu, výměny až při použití, souběhu vydání/uplatnění, restartu a end_at. |
| 7 – výsledky | Penalizace a podepsané úpravy, pořadí podle bodů a počtu 15+, 1, 2, 2, 4, ruční remízy platné jen při trvání shody. Průběžné jen adminovi, ruční veřejná publikace a opravy s verzemi. Testy anonymního přístupu, neveřejných členů/důvodů a historie publikací. |
| 8 – dashboard a statistiky | REST polling, medián od pěti vzorků, statistická verze, očekávané časy a oprávnění. |
| 9 – provoz | ARM64 image, konfigurace, health checks, zálohování a obnova. Žádné cloudové nasazení bez výslovného zadání. |

Etapa 3 je úmyslně rozdělena na malé části; nedoplňovat ještě všechny závodní moduly. Audit, oprávnění a idempotenci přidávat současně s operacemi, které je vyžadují. Každou část ukončit odpovídajícími automatickými testy a dokumentací. Commit ani push není automatickým krokem.

## Zdroje technického návrhu

- [Spring Security OAuth2/OIDC](https://docs.spring.io/spring-security/reference/servlet/oauth2/)
- [Apple: přihlášení a údaje první autorizace](https://developer.apple.com/documentation/signinwithapple/authenticating-users-with-sign-in-with-apple)
- [Apple: konfigurace relay e-mailů](https://developer.apple.com/documentation/signinwithapple/configuring-your-environment-for-sign-in-with-apple)
- [Maven Failsafe](https://maven.apache.org/surefire/maven-failsafe-plugin/integration-test-mojo.html)
- [GitHub Actions: PostgreSQL service](https://docs.github.com/en/actions/tutorials/use-containerized-services/create-postgresql-service-containers)

Implementace musí ověřit konkrétní API proti verzím závislostí spravovaným existujícím Spring Boot parentem. Návrh identit nedává povolení kontaktovat či konfigurovat účty poskytovatelů.
## Ověření etapy 2 (21. 10. 2026)

- JDK 21.0.12.1: `mvnw.cmd clean verify` skončilo `BUILD SUCCESS`; 31 testů, 0 failures, 0 errors, 0 skipped. Vytvořen spustitelný JAR. Běh nevyžadoval připojení k DB.
- `mvnw.cmd -Pit verify` s úmyslným `RALLYE_TEST_DB_ENABLED=false`: 31 běžných testů prošlo; jediný IT správně selhal v initializeru na požadavku opt-in před JDBC připojením, Flyway a Hibernate. Ověřena je ochrana, nikoli úspěšná PostgreSQL integrace.
- Skutečný PostgreSQL IT nebyl spuštěn: chybí schválená a připravená izolovaná databáze, role a přístup. Žádná cloudová databáze ani infrastruktura nebyla vytvořena nebo změněna.
- Archiv 0.3.4 má stejný Git blob hash jako původní uživatelem připravená specifikace; pracovní kopie a vydání 0.4.0 jsou obsahově totožné. `git diff --check` prošlo.
- Maven nadále hlásí metadata HTTP 401 z Flyway/Red Gate zdrojů; sestavení i testy proběhly. Nevkládaly se přihlašovací údaje do repozitáře.
- Produkční Java a `application.properties` zůstaly beze změn. Žádné obchodní entity, migrace ani REST API, commit, push nebo nasazení.

Změněny/vytvořeny: `AGENTS.md`, `README.md`, `docs/Rallye_specifikace.md`, obě verzovaná vydání `docs/Zavody_specifikace_v0.3.4.md` a `docs/Zavody_specifikace_v0.4.0.md`, tento plán, `docs/testing.md`, `pom.xml`, `src/test/resources/application-it.properties`, `HelloControllerTest`, `RallyeBackendApplicationIT` (nahrazuje původní `RallyeBackendApplicationTests`) a testovací pomocné třídy `TestDatabaseSettings`, `TestDatabaseInitializer`, `TestDatabaseSettingsTest`.

## Dokumentační aktualizace 0.5.0

Změněn pouze tento plán a vytvořeno vydání 0.5.0. Starší vydání a pracovní kopie se nepřepisují; produkční Java, testy, konfigurace a migrace se nemění. Neproběhly databázové operace, commit, push ani deployment. Zbývající obchodní nejasnosti jsou konsolidované v oddílu 14 specifikace 0.5.0; dříve otevřený počet posádek na účet, převod, kopírování nedokončené účasti a publikace výsledků už jsou potvrzené. Těchto šest oblastí je nyní uzavřeno: úprava při úspěšném použití kódu, zachování zařízení při převodu kapitána, individuální start při původním kódu, běžné/prodloužené relace, anonymizace účtu se zachováním historických členů a auditované vzájemné pořadí remizujících posádek. Zbývající otázky oddílu 14 (kvízy, okraje kódů/průběhu, publikace a další dílčí detaily) se řeší před implementací příslušné části, nikoli opětovným otevřením těchto šesti rozhodnutí.
## Uzavření šesti rozhodnutí v rámci 0.5.0

Všech šest rozhodnutí je zapracováno do datového modelu, autentizace, transakcí a akceptačních scénářů. Anonymizační postup se týká osobních údajů samotného účtu; historický members_text se nesmí automaticky čistit spolu se jménem účtu. Veřejné DTO ani archiv členy nevystavují. Závodní zařízení je nezávislá autorita pro pokračování účasti a převod kapitána mění pouze správu; závodní operace se nesmějí omylem odmítnout jen kvůli změně captain_user_id. Individuální start je údaj účasti pro výpočet skutečné doby absolvování, ne nový společný limit. Ruční remíza nese vzájemné pořadí dvou i více účastí; audit se zachová i poté, co rozhodnutí po zániku remízy přestane ovlivňovat výsledky.

Pro první implementační části není otevřená žádná z těchto šesti obchodních otázek. Nadále je potřeba připravit testovací DB a technickou konfiguraci přihlášení/doručování e-mailů. Před implementací příslušných okrajových operací zůstávají otázky aktuální kapitoly 14 specifikace: potvrzovací lhůty e-mailu, nevyřízené žádosti při zrušení účtu, autorizace zrušení převodu, kopírování počtu dětí a textové limity, aktivace prvního stanoviště a hranice uzávěrky, pozdní výjimka, rozsah veřejných historických verzí a rušení závěrečných stanovišť. Kvízy jsou odložené. Nejde o blokaci celého backendu; závislé operace vyžadují rozhodnutí před implementací.
## Implementace etapy 2A

Přidány user_account, user_identity (LOCAL a připravené GOOGLE/APPLE), user_action_token a user_session ve Flyway V1. Přístup k datům je uvnitř identity.persistence přes JDBC; API má samostatná DTO. Tokeny a relace ukládají pouze SHA-256 digest náhodných tajemství, hesla Argon2id. Účet je do jednorázového ověření e-mailu nepoužitelný. Aktivace má 24 hodin, reset 30 minut. Jednotné pořadí zámků účtu chrání proti souběhu přihlášení, resetu, změny hesla a odvolání relací. Jednorázové tokeny spotřebuje podmíněný UPDATE v téže transakci jako změna účtu/hesla.

Schválené upřesnění běžné relace je 24 hodin nečinnosti, trvalé 30 dní, obě s obnovou při používání; uchování na klientu odpovídá volbě. Mobil používá Bearer, web HttpOnly cookie s CSRF. Omezení pokusů má samostatné 15minutové limity podle účtu a zdrojové IP; jde zatím o omezenou paměť jedné instance. E-mail jde po commitu do omezené asynchronní SMTP fronty, bez tokenů v logu a bez vracení existence účtu v resetovací odpovědi. Síťová chyba SMTP neprozradí existenci účtu; opětovné zaslání ověření/resetu má generickou odpověď. Změna e-mailu je výslovně navazující úkol spolu s anonymizací účtu a externími identitami.

Skutečné PostgreSQL a SMTP ověření vyžadují dostupné vyhrazené údaje a připojení. Bez testovacího opt-in a RALLYE_TEST_DB_* se integrace nesmí pokusit použít vývojovou DB. V této etapě se nenahrazuje ani nevytváří cloudová infrastruktura.

Ověření etapy 2A: po doplnění lokální schránky prošlo 68 lokálních testů a JAR je sestaven. Uživatel následně potvrdil funkční tunel a úspěšné `.\mvnw.cmd -Pit verify` ve svém PowerShellu (`BUILD SUCCESS`). Jde o uživatelem potvrzené PostgreSQL ověření; předchozí selhání před připojením se týkalo prostředí Codexu. Další integrační běhy spouští uživatel: Codex připraví testy, předá příkaz a počká na výsledek. Kompilaci a testy bez databáze spouští Codex. Podrobnosti a seznam změněných souborů jsou v authentication-2a.md. Skutečné SMTP spojení nebylo bez konfigurace ověřeno.

Lokální ověření účtů bez SMTP: profil local používá souborovou schránku mimo Git a bind na loopback. Uživatel dne 21. 10. 2026 potvrdil opakované `.\mvnw.cmd -Pit verify` proti `rallye_test` s výsledkem `BUILD SUCCESS`, včetně `LocalMailboxIT`; integrační ověření této změny je uzavřeno jako uživatelem potvrzené. Platnost a jednorázovost tokenů zůstávají stejné. Výběr SMTP poskytovatele a ověření skutečného doručování se provede před nasazením, nikoli jako podmínka lokálního vývoje. Návod je v authentication-2a.md.
