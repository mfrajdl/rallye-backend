# Backend: architektura a plán po etapě 2A a rozhodnutích 3b

Aktuální funkční základ je [specifikace 0.6.0](Zavody_specifikace_v0.6.0.md), která doplňuje schválená pravidla etapy 3b. Vydání 0.5.0 a starší i rozpracovaná kopie Rallye_specifikace.md zůstávají beze změny. Potvrzené požadavky mají přednost před technickými návrhy. Etapa 2A je dokončena; etapa 3b.1 je dokončena a ověřena, etapa 3b.2 je dokončena; dostupné reporty potvrzují 118 testů bez DB a 39 IT bez chyb. Dokumentace sama nedává oprávnění k databázovým operacím či nasazení.

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

Etapa 3b.1 je dokončena a ověřena: V2 přidává ADMIN, společný
zámek/bootstrap a DB ochrany posledního administrátora, auditní události a evidenci
idempotentních změn rolí. API spravuje role jiných účtů a čte filtrovaný audit.
Bezpečnostní operace 2A už zapisují transakční audit. `mvnw.cmd verify` prošel:
90 testů při závěrečné kontrole 3b.1, 0 failures/errors/skipped, JAR sestaven.
Uživatelův PostgreSQL běh měl 21/23 úspěšných testů; dva skončily na očekávání
IllegalStateException místo Springem přeložené InvalidDataAccessApiUsageException.
Testy jsou posílené o kontrolu původní příčiny a nezměněných dat po každém selhání,
včetně nového případu chyby po zápisu idempotence. Dostupné Failsafe reporty následného
běhu obsahují 24/24 úspěšných IT; nejde o vlastní DB běh Codexu. Uživatel potvrdil
Postman: přihlášení, audit, grant/revoke, zákaz vlastní role, replay a konflikt klíče
i audit bootstrapu, přihlášení a změn rolí. Závěrečná kontrola doplnila odmítnutí
chybějícího admin_control a nekonzistentního bootstrap příznaku. Rozšířených 26 IT
uživatel následně ověřil příkazem `.\mvnw.cmd -Pit verify`: BUILD SUCCESS. Jde o uživatelem potvrzený běh nad rallye_test, nikoli DB běh Codexu. Codex SQL bootstrap nespouštěl. Postup a omezení
jsou v [administration-3b1.md](administration-3b1.md).

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

Etapa 2A používá Spring Security a neprůhledné databázové relace s hashovaným tokenem; OAuth2 Client/OIDC je návrh až pro budoucí externí přihlášení; nebudovat vlastní autorizační server ani infrastrukturu JWT s obnovovacími tokeny. Jeden interní účet v existující tabulce `user_account` může mít místní hash hesla a několik ověřených externích identit. Členové účasti nejsou identity. Lokální heslo se hashuje Argon2id přes Spring Security PasswordEncoder; povolená délka je schválených 8–128 Unicode znaků bez kategorií. Registrace uživatelského účtu e-mailem vyžaduje ověření adresy jednorázovým aktivačním odkazem zaslaným e-mailem; obnova hesla používá krátkodobý jednorázový náhodný token uložený jako hash. Obnova hesla má potvrzenou platnost 30 minut, jednorázové použití a jednotnou odpověď bez odhalení existence účtu. Login také nerozlišuje chybný e-mail a heslo. Změna e-mailu vyžaduje nové ověření uživatele i nové adresy. Potvrzovací odkaz má schválenou platnost 24 hodin. Výběr poskytovatele a ověření doručování jsou odloženy před nasazení; lokální profil používá souborovou schránku bez SMTP.

Web provozovat pokud možno na stejném originu jako API. Relace přes `Secure`, `HttpOnly`, přiměřený `SameSite` cookie; stavové požadavky chránit CSRF tokenem v hlavičce. Při externích callbackách zvlášť ověřit chování Apple `form_post` a cookie SameSite; neuvolňovat globálně CSRF. Přihlašování i odhlašování mění relaci bezpečně; regenerovat identifikátor po přihlášení. Neumisťovat session ID do URL nebo browser localStorage.

Mobil v etapě 2A získává samostatnou relaci přes X-Rallye-Client: mobile a používá Authorization: Bearer. Web má zabezpečenou HttpOnly cookie a CSRF ochranu; oba způsoby se v jednom požadavku nekombinují. Tajemství ukládat prostředky Android Keystore/iOS Keychain podle volby uchování. Následující externí tok je pouze návrhem pro další etapu. Externí přihlášení otevře systémový browser, nikoli embedded webview. Backend provede authorization code tok s Google/Apple, validuje issuer, audience, podpis, čas, state a nonce; PKCE použít tam, kde jej daný tok poskytovatele podporuje. Browserová relace se do mobilu nepředává přes URL: navržen je krátkodobý jednorázový předávací kód vázaný na mobilní PKCE challenge, výměna za mobilní relaci přes HTTPS a ověřené app/universal links. Prototyp musí ověřit Google i Apple specifika včetně Apple client-secret JWT a první odpovědi se jménem. Tato část je návrh, nikoli hotový kontrakt.

Etapa 2A místo původně zvažovaného Spring Session JDBC používá jednoduchou tabulku user_session s digestem náhodného tokenu a integrací do Spring Security filter chain. Tím se relační tajemství neukládá do DB v otevřené podobě a neukládá se serializovaný SecurityContext. Běžná relace má maximální nečinnost 24 hodin a session cookie/uchování jen během klientské relace. Trvalá má 30 dní nečinnosti a obnovuje se při používání. Serverové odhlášení je okamžité; změna hesla odvolá ostatní relace, obnova všechny. Relace přežijí restart serveru, protože autorita je v DB. Detaily API, cookies/Bearer, SMTP a omezení pokusů jsou v [authentication-2a.md](authentication-2a.md).

## Bezpečné propojení identit

Externí identitu identifikovat `(issuer, subject)`, s databázovou unikátností, nikdy jen shodným e-mailem. Apple relay e-mail není důvod ke spojení účtů; e-mail může být odlišný a jméno se při dalších přihlášeních nemusí vrátit. Neukládat nepotřebné scopes/profil ani hesla či dlouhodobé tokeny poskytovatelů. Jméno načtené při prvním přihlášení zachovat, prázdná další odpověď ho nesmaže.

Propojení vyžaduje aktivní účet, čerstvé ověření jeho identity a samostatně ověřený tok druhého způsobu přihlášení, s CSRF/state/nonce a vazbou na cílový účet. Pokud identita již patří jinému účtu, vrátit konflikt; žádné tiché slučování. Stejný e-mail může vyvolat nabídku přihlášení existujícím způsobem, sám není důkazem identity. Paralelní propojení řeší unikátní omezení a transakce. Auditovat propojení/odpojení bez tokenů. Nedovolit odpojit poslední použitelný způsob bez bezpečné náhrady; obnova hesla a převody už mají potvrzená pravidla ve specifikaci 0.5.0, detaily odpojení poslední identity zůstávají technickým návrhem.

## Autorizace

Administrátor je jediná administrativní role a má plná oprávnění ke správě posádek včetně cizích a bez kapitána: údaje, soukromé fotografie, okamžitá změna kapitána bez potvrzení. Ani administrátor nesmí smazat posádku s historickou účastí nebo během čekajícího převodu; okamžitá změna kapitána čekající převod atomicky zruší. Běžný kapitán spravuje vlastní posádky podle aktuálního interního ID, nikoli podle klientského captain_user_id či cachovaného vlastnictví. Změna vlastníka ani administrativní odebrání kapitána neovlivní aktivní závodní zařízení. Běžný uživatel zakládá posádku se sebou jako kapitánem a kapitána přímo odebrat nemůže. Administrátor zakládá posádku pro libovolného aktivního uživatele s ověřeným e-mailem nebo bez kapitána a existující posádce může kapitána odebrat; čekající převod se při změně/odebrání kapitána zruší.

Administrátor spravuje role ADMIN jiných aktivních uživatelů. Navržená tabulka user_account_role a načítání oprávnění při požadavku navazují na dosavadní ROLE_USER; kapitánství není globální role. Po prvním bootstrapu musí zůstat alespoň jeden aktivní administrátor. Odebrání role, rušení či deaktivace účtu musejí tento invariant kontrolovat atomicky i při souběhu. Vlastní správu role nelze dovodit z oprávnění spravovat jiné uživatele. Administrátor může rušit účty podle stejných pravidel jako uživatel; neobchází ochranu posledního administrátora ani historie.

První administrátor vznikne jednorázovým SQL bootstrapem konkrétního existujícího aktivního účtu, bez veřejného endpointu a bez automatického povyšování při startu či registraci. V 3b.1 je připraven transakční skript ops/admin/bootstrap-first-admin.sql: sdílený zámek, aktivní/ověřený účet, trvalý příznak bootstrapu a audit provozního aktéra. Skript Codex nespouští; ruční postup je v administration-3b1.md.

## Rozlišení registrace účtu, přihlášky a závodních kódů

| Operace | Požadované ověření |
| --- | --- |
| Registrace uživatelského účtu e-mailem | Jednorázový aktivační odkaz zaslaný e-mailem k ověření adresy. |
| Registrace posádky do závodu | Přihlášený kapitán podá přihlášku za svou posádku, bez aktivačního kódu. |
| Start posádky do závodu | Jednorázový startovací kód předaný organizátorem mimo aplikaci. |
| Převzetí rozehraného závodu na jiném zařízení | Náhradní startovací kód; vydání neodpojí původní zařízení, až úspěšné použití. |

Odstranění aktivačního kódu se týká pouze registrace posádky do závodu. Ověření e-mailu při registraci uživatelského účtu zůstává povinné. Aktivační odkaz účtu, odkaz na závod a startovací/náhradní kód mají různé účely a nesmějí se zaměňovat.

## Dopady potvrzených pravidel 0.6.0 na data a přístup

| Oblast | Návrh a požadované záruky |
| --- | --- |
| Účet | Existující user_account z V1. Historická účast patří uživateli, který byl kapitánem při skutečném zahájení závodu, nikoli při přihlášce; pozdější převod tuto vazbu nemění. Bez historie účet trvale smazat, s historií anonymizovat. Při závodění posádky zrušení odmítnout do skončení účasti. Pouhou přihlášku zachovat, všechny čekající převody rušeného uživatele v obou rolích zrušit a jeho posádky ponechat bez kapitána. Odebrat přihlašovací prostředky a relace; vše transakčně a s auditem. Stejná pravidla pro ADMIN. |
| Posádka | Více posádek na účet; nullable captain_user_id, posádky i bez kapitána. Povinný název do 100 znaků, nepovinná RZ do 32, popis do 2 000 a fotografie. Název ani RZ nejsou unikátní. Bez kapitána nelze podat novou přihlášku. Historickou posádku nelze smazat; čekající převod blokuje smazání, nikoli editaci. Žádná archivace posádek. |
| Převod | crew_transfer: původní a cílové ID účtu, hash tokenu, 7 dní, stav, časy a rozhodnutí. Částečný unikátní index pro jednu aktivní žádost. Cíl se změnou e-mailu nemění. Musí existovat, být aktivní a mít ověřený e-mail; převod na sebe sama je zakázán, a to i u administrátorské varianty. Přijímá/odmítá přihlášený cílový účet; původní kapitán může čekající převod zrušit. Administrátor mění kapitána bez potvrzení a čekající žádost ruší atomicky s auditem. |
| Účast | Trvalý neměnný snímek interního ID kapitána při skutečném zahájení závodu a času jeho pořízení (návrh samostatné vazby nebo polí účasti), nezávislý na pozdějším vlastnictví. crew_name_at_registration jako historický snímek, members_text a children_under_15; počet členů je počet neprázdných řádků, počet 15+ jejich rozdíl. individual_started_at nastavit jednou při úspěšném původním startovacím kódu a nikdy nepřepsat náhradním. Žádná tabulka participant_member ani příznak dítěte u jednotlivých jmen. Kopie z historie není živá vazba. |
| Limity | max_crew_members v konfiguraci závodu, výchozí 5; návrh kopie šablony do konání. Nejméně jeden člen, children_under_15 mezi 0 a počtem členů. Složení uzamknout při oficiálním startu, u pozdní účasti při zařazení. |
| Zařízení | participation_code typu START/REPLACEMENT a race_device_authorization oddělené od user_session. Více account relací neznamená více platných oprávnění řídit účast. Token předání mobilní login relace z technického návrhu není startovací/náhradní kód závodu. |
| Body a remízy | Samostatná score_adjustment s podepsanými body a vazbou na náhradní kód; účinná právě jednou při úspěšném použití náhradního kódu atomicky s převodem závodního oprávnění. Nepoužitý/zneplatněný nepoužitý kód má nulový dopad. tie_decision uchová vzájemné pořadí dvou i více posádek a vazbu na příslušnou úplnou remízu; audit a nepovinný interní důvod. Při přepočtu použít, jen pokud remíza trvá; po jejím zániku rozhodnutí pořadí neovlivňuje. |
| Publikace | result_publication/result_entry pro verze definitivních výsledků, veřejná projekce bez jmen členů. Oprava vytváří novou publikaci při zachování historie. |

Při běžné správě posádky a jejích účastí ověřovat aktuální vlastnictví ze stavu DB; administrátorskou správu povolit podle aktuální role a schváleného rozsahu. Oprávnění neodvozovat dlouhodobě
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

Pro vytváření záznamů a změny vlastnictví etapy 3b používat Idempotency-Key. Schválená retence je 24 hodin; stejný klíč a stejné parametry vrátí původní výsledek bez další operace/auditu, stejný klíč s odlišnými parametry vrátí HTTP 409. Po 24 hodinách lze evidenci klíče odstranit. Závodní pokusy mají vlastní nezkrácené záruky včetně replay po end_at; auditní retence je také oddělená. Přesné endpointy a technický úklid evidence dopracovat v návrhu.

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

Navržená tabulka audit_event: ID události, UTC čas, interní aktér nebo systém/provozní správce, typ operace, typ/ID zdroje, request ID a omezená strukturovaná metadata. Audit obchodních změn ukládat ve stejné transakci. Běžné operace události pouze přidávají; potvrzený administrativní úklid je výslovná výjimka, nikoli obecné API pro editaci historie.

V etapě 3b auditovat změny posádek, převody, administrátorské zásahy včetně bootstrapu a správy rolí, úspěšná přihlášení, odhlášení, změny a resety hesel a rušení účtů. Neúspěšná přihlášení zatím pouze do provozních logů. V původní 2A transakční audit nebyl; 3b.1 jej již přidává do úspěšných bezpečnostních operací při zachování jejich funkčních pravidel a generických odpovědí. Audit posádek, převodů a rušení účtů vznikne současně s jejich operacemi v dalších krocích. Do žádného logu ani auditu nepatří hesla, tokeny nebo nadbytečné osobní údaje.

Výjimkou z auditování administrativních operací je samostatný read-only CSV export:
podle schváleného pravidla nemění DB ani vložením události exportu. Archivace a
mazání jsou jiné operace a jejich úklid se audituje povinně.

Nadále auditovat budoucí start/konec, kódy a závodní zařízení, bodové úpravy, publikace a remízy, pozdní výjimky, uzamčení členů, konfiguraci, zrušení stanovišť a propojení identit podle specifikace. Historie pokusů a výsledků není auditní tabulkou určenou k administrativnímu úklidu.

Standardní uchovávání auditu je bez časového omezení a bez automatického úklidu. Archivace nebo mazání vyžaduje minimální stáří přesně 90 dní (ne kalendářní měsíce), potvrzení administrátorem a audit úklidu. Technický návrh: při provedení porovnat UTC čas události s hranicí now minus 90 dní, s povolením záznamů starých alespoň 90 dní.

Samostatný CSV export je kdykoliv bez omezení stáří a bez jakýchkoliv změn DB, tedy i bez přidání auditního záznamu exportu. CSV: UTF-8 s BOM, středník jako oddělovač, korektní escapování hodnot. Návrh: chránit export před interpretací textových buněk jako vzorců v Excelu a minimalizovat osobní údaje.

Návaznost na skutečnou implementaci 2A: AuthService.authenticate nyní při každém
autentizovaném požadavku prodlužuje relaci zápisem do user_session. Pro doslovné
splnění exportu bez změn DB bude potřeba navrhnout ověření platné relace a aktuální
role bez tohoto zápisu právě pro samostatný export; nesmí se obejít expirace,
odvolání relace, stav účtu ani autorizace ADMIN. Tato read-only varianta dosud
není implementovaná a neblokuje auditní základ či bootstrap 3b.1.

Archivace = úspěšný export starších záznamů a následné odstranění po potvrzení; bez dokončeného exportu se archivace nemaže. Samostatné mazání bez exportu je povolené. Každé mazání je dvoukrokové: ověřit podmínky a zobrazit počet dotčených záznamů, pak výslovně potvrdit. Při provedení znovu ověřit oprávnění, výběr a časovou hranici; výmaz a nový audit úklidu transakčně spojit. Nový audit úklidu nesmí odstranit tentýž úklid. Přesný transport potvrzení a doručení archivu jsou technickým návrhem, nikoli nevyřešená retenční pravidla.

Fyzické smazání účtu nesmí kaskádově odstranit audit ani být znemožněno jeho FK. Návrh: oddělit pseudonymní identifikaci auditního aktéra od životního cyklu user_account a neuchovávat v auditu jeho e-mail/jméno. Totéž pro historické převody a evidenci idempotence: vyřešit odkazování a minimalizaci bez uchovávání smazaného přihlašovacího účtu. Změny schématu a anonymizace se teprve navrhnou a otestují; V1 se nepřepisuje.

## Fotografie a HTML

Pro první jednu instanci navržen privátní adresář na perzistentním Docker volume mimo webroot; v DB pouze náhodný neprůhledný identifikátor a bezpečná metadata. Žádná veřejná cesta podle názvu uploadu; stahování přes autorizovaný endpoint podle vlastnictví/administrativních oprávnění. Později lze přejít na privátní objektové úložiště bez změny veřejných ID. Fotografie zálohovat spolu s konzistentní DB evidencí, odděleně od image aplikace.

Schválené fotografie vozidla jsou JPEG/PNG do 5 MiB (5 242 880 bajtů), maximálně 4096 × 4096 px, v soukromém souborovém úložišti. Technický návrh: ověřit skutečné dekódování, limit bajtů i každého rozměru, znovu zakódovat a odstranit EXIF/GPS. Nepovolovat SVG/HTML jako fotografii. Použít bezpečné dočasné soubory, atomický přesun a úklid osiřelých objektů po selhání DB transakce; klientovi nevracet cesty. Retence nahrazených/osiřelých souborů a konkrétní technický postup úklidu čekají na návrh; vstupní limity fotografií jsou již schválené. HTML instrukce sanitizovat na serveru podle malé allowlist s bezpečnými URL; správné kódy ani odpovědi nesmí být v klientském DTO, HTML ani logu.

## Rizika a zbývající rozhodnutí

- Databázové IT jsou uživatelem úspěšně ověřené nad rallye_test; další běhy provádí uživatel podle testing.md. Nedostupnost tunelu/údajů v prostředí Codexu není důvod měnit infrastrukturu ani oslabovat ochrany.
- Před novými migracemi ověřit návaznost na aplikovanou V1 a existující Flyway historii v povoleném prostředí. Žádný automatický baseline/repair/clean; V1 se nepřepisuje.
- Pravidla 3b ve specifikaci 0.6.0 jsou doplněna: historická vazba kapitána, rušení účtů/převodů, zakládání bez kapitána administrátorem, aktivní ověřený cíl a zákaz převodu na sebe sama. 3b.1 (auditní základ, ADMIN a SQL bootstrap) nemá blokující obchodní rozpor. Před závodní návazností určit přesný okamžik historického snímku vůči oficiálnímu/individuálnímu startu a pozdním účastem. Funkční pravidla 2A se nemění; SMTP/e-mailové API zbývá před nasazením.
- Před etapou účastí/průběhu: rozhodnout nejednoznačnosti oddílu 14 specifikace, atomickou uzávěrku a příjem příkazů. Nově potvrzená pravidla se znovu neotevírají.
- Před statistikami: přesné vyjádření mediánu při sudém počtu vzorků, pokud vyjde půl milisekundy; neupravovat kvůli tomu zaznamenané doby.
- Audit: bez časového omezení a bez automatického úklidu. Archivace/mazání od stáří 90 dní, s potvrzením a auditem; CSV export kdykoliv bez změn DB, UTF-8 s BOM a středníkem. Idempotence 3b: schválených 24 hodin, shodný výsledek, jiný payload HTTP 409, po retenci možnost odstranění evidence. Technický způsob úklidu souborů a evidence, konkrétní limity pokusů o převod a zálohy dopracovat.
- Maven v minulém prostředí vybíral Javu 17 a PATH Javu 11: explicitně ověřovat JDK 21. Metadata některých zdrojů Flyway hlásí HTTP 401; nevkládat přístupové údaje do projektu.
- Budoucí ARM64 ověřit při kontejnerizaci; lokální Windows testy nevyžadují Docker, WSL ani systémové instalace.

## Následující samostatně ověřitelné etapy

| Etapa | Rozsah a ověření |
| --- | --- |
| 2A – lokální autentizace (dokončena) | Účty, LOCAL identita, ověření e-mailu, obnova a změna hesla, databázové relace, cookies/Bearer a CSRF. Automatické i manuální ověření úspěšné; skutečné doručování zbývá před nasazením. |
| 3a – infrastruktura testů (testovací připojení ověřeno) | Vyhrazená DB/role rallye_test je dostupná a uživatel potvrdil úspěšné IT včetně lokální schránky. Postup a read-only kontroly oprávnění zůstávají v ops/postgresql-test a postgresql-test-setup.md; úspěch IT sám nedokládá provedení všech administrátorských kontrol. Žádné další cloudové změny nespouštět automaticky. |
| 3b – vlastnictví, posádky, administrace a audit (části 3b.1 a 3b.2 dokončeny) | ADMIN, SQL bootstrap, audit rolí a 2A jsou v kódu. Posádky/fotografie a okamžité administrátorské kapitánství jsou v 3b.2. Další kroky: potvrzované převody, rušení účtů podle historie, auditní export a ruční úklid. Rozdělení a kritéria níže; ostatní části se nevydávají za hotové. |
| 3c – externí přihlášení | Google a Apple, minimální údaje, bezpečné propojení/konflikty identit a mobilní předání relace. Automatické testy validace tokenů/state/nonce a souběhu propojení; manuální smoke s oběma poskytovateli až po dodání konfigurace. |
| 4 – šablony | GPS/CODE, časová pravidla, pořadí a statistické verze. Testy DTO validace, ms hranic bodování a rozsahu; žádné kvízy. |
| 5 – konání a účasti | Kopie šablony, přihláška přes odkaz, historický název, text členů a počet dětí, maximum (výchozí 5), kopie i z nedokončené účasti a uzamčení. Testy počítání řádků, limitů, unikátnosti a souběhu oficiálního startu s úpravami. |
| 6 – průběh | Ruční nevratný start konání, startovací/náhradní kódy a oprávnění zařízení, podepsané bodové úpravy bez automatické penalizace, GPS/CODE, idempotence, přeskočení, zrušení a uzávěrka. Testy předčasného kódu, výměny až při použití, souběhu vydání/uplatnění, restartu a end_at. |
| 7 – výsledky | Penalizace a podepsané úpravy, pořadí podle bodů a počtu 15+, 1, 2, 2, 4, ruční remízy platné jen při trvání shody. Průběžné jen adminovi, ruční veřejná publikace a opravy s verzemi. Testy anonymního přístupu, neveřejných členů/důvodů a historie publikací. |
| 8 – dashboard a statistiky | REST polling, medián od pěti vzorků, statistická verze, očekávané časy a oprávnění. |
| 9 – provoz | ARM64 image, konfigurace, health checks, zálohování a obnova. Žádné cloudové nasazení bez výslovného zadání. |

Etapa 3 je úmyslně rozdělena na malé části; nedoplňovat ještě všechny závodní moduly. Audit, oprávnění a idempotenci přidávat současně s operacemi, které je vyžadují. Každou část ukončit odpovídajícími automatickými testy a dokumentací. Commit ani push není automatickým krokem.

## Implementační kroky 3b a kritéria dokončení (návrh)

Nové regresní scénáře: pouhá přihláška dovolí zrušení účtu a zůstane zachovaná;
běžící účast zrušení odmítne bez částečných změn; dokončená historická účast vede
k anonymizaci i po převodu posádky. Ověřit zrušení čekajících převodů na obou
stranách, založení/odebrání kapitána podle role, neověřený/neaktivní cíl a převod
na sebe sama. Pro úklid otestovat hranici 90 dní, počet ve fázi náhledu a opětovné
ověření při potvrzení. U CSV ověřit BOM, středník a nulové DB zápisy včetně
autentizace; u idempotence shodný výsledek, HTTP 409 a retenční hranici 24 hodin.

Historická vazba je již schválená: kapitán při skutečném zahájení závodu; pouhá
přihláška ji nevytváří a převod ji nepřepisuje. Model ji musí trvale zachovat.
Přesný synchronizační bod snímku vůči startům dopracovat při etapě účastí/průběhu.
Posádky zrušeného účtu se automaticky nemažou, ani když ještě nemají historii.
Zrušení posledního aktivního ADMIN se odmítne, dokud existuje jen tento administrátor.

| Krok | Rozsah a dokončení |
| --- | --- |
| 3b.0 | Schválená pravidla jsou v 0.6.0; dopracovat technický kontrakt API, vazby a testy. Žádný dosud zjištěný obchodní rozpor neblokuje zahájení 3b.1. Okraje závodního startu řešit před implementací jeho návazností. |
| 3b.1 – dokončeno a ověřeno | Flyway V2, auditní základ a čtení s filtry, aktuální ADMIN, správa rolí jiných aktivních ověřených uživatelů, SQL bootstrap a DB ochrany posledního administrátora. Audit bezpečnostních operací 2A je podle aktuálního zadání již zahrnut. Grant/revoke i audit a idempotentní výsledek jsou atomické. Unit testy 90/90, integrační sada 26/26 a manuální Postman ověření dokončeny; integrační BUILD SUCCESS potvrdil uživatel. |
| 3b.2 – dokončeno | CRUD posádek, limity a soukromé fotografie, aktuální vlastnictví/ADMIN, sdílený Idempotency-Key a podle dodatečného schválení i okamžité administrátorské přiřazení/změna/odebrání kapitána. Jedna version pro texty/fotografie/kapitánství. V3, CrewsIT a Postman dokončeny; 118 testů bez DB a report 39/39 IT bez chyb. Podrobnosti v crews-3b2.md. |
| 3b.3 – rozpracováno | Běžný převod na ID cíle do 7 dní, zrušení původním kapitánem, editace ano/smazání ne; administrativní změna bez potvrzení je již v 3b.2; 3b.3c doplňuje atomické zrušení čekající žádosti při změně/odebrání kapitána. Část 3b.3a je dokončená a ověřená: V4 a interní životní cyklus s povinnou vnější transakcí; 137 testů bez DB a 48 integračních testů bez chyb, integrační BUILD SUCCESS potvrdil uživatel. Část 3b.3b je dokončená a ověřená; přidává REST založení a resend, tokeny, SMTP/souborové doručení po commitu a stávající idempotenci; verify prošlo se 159 testy bez DB, 56 IT prošlo v uživatelem potvrzeném běhu. Část 3b.3c implementuje accept/reject/cancel, audit včetně REQUESTED, ochrany DELETE a ADMIN změn, V5 a oddělené otisky idempotence. 3b.3c dokončeno a ověřeno: verify 176 testů bez DB, 71 IT bez chyb v uživatelem potvrzeném běhu; celek čeká na koncové ověření 3b.3d. Viz [crew-transfers-3b3c.md](crew-transfers-3b3c.md). Viz [crew-transfers-3b3b.md](crew-transfers-3b3b.md). Podrobnosti a rozdělení 3b.3a–d: [crew-transfers-3b3a.md](crew-transfers-3b3a.md). |
| 3b.4 | Rušení účtu uživatelem i ADMIN: bez historické účasti smazat, jinak anonymizovat; během závodění odmítnout, pouhou přihlášku zachovat. Všechny čekající převody daného uživatele zrušit, posádky zachovat bez kapitána. Ověřit transakční rollback, FK, neměnnou historickou vazbu i po převodu a zákaz zrušení posledního ADMIN. |
| 3b.5 | Audit úspěšného přihlášení, odhlášení a změny/resetu hesla je již implementován v 3b.1; znovu jej nepřidávat. Zbývá CSV export kdykoliv bez DB změn, UTF-8 s BOM a středníkem. Archivace/mazání od stáří 90 dní, dvoukrokové potvrzení a opětovná kontrola hranice při provedení; audit úklidu a rollback. Žádná automatika. |
| 3b.6 | Regresní testy 2A, jednotkové/HTTP testy, chráněné PostgreSQL IT a Postman scénáře více vlastníků a administrátorů. Codex spouští testy bez DB, uživatel integrační běh a potvrzuje výsledek. Aktualizovat dokumentaci podle skutečné implementace. |

Převodní zprávy potřebují samostatný účel a odkaz v SMTP i lokálním adaptéru; nynější větev pro jiný účel než aktivaci vytváří resetovací zprávu. Bez rozšíření ji pro převod nepoužívat. Zakládání, změna vlastníka, zrušení čekajícího převodu, audit a idempotentní výsledek musejí být konzistentní v jedné transakci. Administrátorské zásahy také neobcházejí zákaz mazání historické posádky. Fyzické rušení účtů neznamená mazání historie posádek ani auditu.

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

Pro první implementační části není otevřená žádná z těchto šesti obchodních otázek. Testovací DB i lokální přihlášení již byly ověřeny v 2A; skutečné doručování zbývá před nasazením. Před implementací příslušných okrajových operací zůstávají otázky aktuální kapitoly 14 specifikace: lhůty jiných budoucích potvrzení e-mailu než registrace 2A, kopírování počtu dětí a textové limity, aktivace prvního stanoviště a hranice uzávěrky, pozdní výjimka, rozsah veřejných historických verzí a rušení závěrečných stanovišť. Kvízy jsou odložené. Nejde o blokaci celého backendu; závislé operace vyžadují rozhodnutí před implementací.
## Implementace etapy 2A

Přidány user_account, user_identity (LOCAL a připravené GOOGLE/APPLE), user_action_token a user_session ve Flyway V1. Přístup k datům je uvnitř identity.persistence přes JDBC; API má samostatná DTO. Tokeny a relace ukládají pouze SHA-256 digest náhodných tajemství, hesla Argon2id. Účet je do jednorázového ověření e-mailu nepoužitelný. Aktivace má 24 hodin, reset 30 minut. Jednotné pořadí zámků účtu chrání proti souběhu přihlášení, resetu, změny hesla a odvolání relací. Jednorázové tokeny spotřebuje podmíněný UPDATE v téže transakci jako změna účtu/hesla.

Schválené upřesnění běžné relace je 24 hodin nečinnosti, trvalé 30 dní, obě s obnovou při používání; uchování na klientu odpovídá volbě. Mobil používá Bearer, web HttpOnly cookie s CSRF. Omezení pokusů má samostatné 15minutové limity podle účtu a zdrojové IP; jde zatím o omezenou paměť jedné instance. E-mail jde po commitu do omezené asynchronní SMTP fronty, bez tokenů v logu a bez vracení existence účtu v resetovací odpovědi. Síťová chyba SMTP neprozradí existenci účtu; opětovné zaslání ověření/resetu má generickou odpověď. Změna e-mailu je výslovně navazující úkol spolu s anonymizací účtu a externími identitami.

Skutečné PostgreSQL a SMTP ověření vyžadují dostupné vyhrazené údaje a připojení. Bez testovacího opt-in a RALLYE_TEST_DB_* se integrace nesmí pokusit použít vývojovou DB. V této etapě se nenahrazuje ani nevytváří cloudová infrastruktura.

Ověření etapy 2A: po doplnění lokální schránky prošlo 68 lokálních testů a JAR je sestaven. Uživatel následně potvrdil funkční tunel a úspěšné `.\mvnw.cmd -Pit verify` ve svém PowerShellu (`BUILD SUCCESS`). Jde o uživatelem potvrzené PostgreSQL ověření; předchozí selhání před připojením se týkalo prostředí Codexu. Další integrační běhy spouští uživatel: Codex připraví testy, předá příkaz a počká na výsledek. Kompilaci a testy bez databáze spouští Codex. Podrobnosti a seznam změněných souborů jsou v authentication-2a.md. Skutečné SMTP spojení nebylo bez konfigurace ověřeno.

Lokální ověření účtů bez SMTP: profil local používá souborovou schránku mimo Git a bind na loopback. Uživatel dne 21. 10. 2026 potvrdil opakované `.\mvnw.cmd -Pit verify` proti `rallye_test` s výsledkem `BUILD SUCCESS`, včetně `LocalMailboxIT`; integrační ověření této změny je uzavřeno jako uživatelem potvrzené. Platnost a jednorázovost tokenů zůstávají stejné. Výběr SMTP poskytovatele a ověření skutečného doručování se provede před nasazením, nikoli jako podmínka lokálního vývoje. Návod je v authentication-2a.md.

## Implementace 3b.2

Rozsah a technické volby jsou v [crews-3b2.md](crews-3b2.md). V3 rozšiřuje původní
admin_role_command do společné evidence bez ztráty ADMIN klíčů a přidává crew
a bezpečné auditní údaje. Specifikace 2.2 vyžaduje aktivní ověřený cílový účet
i pro ADMIN. Po schválení je okamžitá administrativní změna kapitána již v 3b.2;
potvrzované převody zůstávají v 3b.3. ADMIN smí přiřadit sebe cizí či osiřelé
posádce, stejný dosavadní kapitán se odmítá. Texty/fotografie/kapitánství sdílejí
version. Ruční souborový úklid nad 24 hodinami používá zámek a opětovné kontroly
referencí; nemá vztah k budoucímu úklidu auditu. Závěrečné dostupné Surefire/Failsafe reporty potvrzují 118 testů bez DB a 39 IT bez chyb. Postman má 63 požadavků a 99 validních skriptů; zahrnuje samostatné mazání vlastníkem a změnu kapitána existující posádky. Úplný manuální běh všech scénářů zde není vydáván za samostatně potvrzený.
