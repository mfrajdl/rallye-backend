# Backend: architektura a plán po etapě 2A a rozhodnutích 3b

Aktuální funkční základ je [specifikace 0.8.0](Zavody_specifikace_v0.8.0.md), která přebírá pravidla 3b a nahrazuje D04/D05 rozhodnutím D09 (jedna neměnná identita) a D03 rozhodnutím D10 (LOCAL heslo, externí potvrzení zrušení bez reautentizace). Vydání 0.7.0 a starší i rozpracovaná kopie Rallye_specifikace.md zůstávají beze změny. Potvrzené požadavky mají přednost před technickými návrhy. Etapa 2A i celý blok 3b.1–3b.6 jsou dokončeny a ověřeny. Závěrečné ověření 24. 10. 2026: 234 testů bez DB; dostupné reporty uživatelem potvrzeného PostgreSQL běhu obsahují 133 IT bez chyb. Šest ručních scénářů 3b.6c potvrdil uživatel proti rallye_dev. Etapa 3b.6 byla commitnuta a odeslána do origin/main jako 19f5ad52a5ece6d520206dcebbf382fdfc82b32b. Dokumentace sama nedává oprávnění k databázovým operacím či nasazení.

## Aktuální stav projektu

Dokončené jsou lokální autentizace, ADMIN/bootstrap, správa posádek a fotografií, potvrzované převody, rušení účtů, auditní export a automatická retence. 3b.6 uzavírá jejich společnou regresi a dokumentaci bez nových endpointů či migrací. Přesné výsledky a omezení: [review-3b6.md](review-3b6.md), [matice pokrytí](coverage-3b6.md) a [ruční výsledky](postman-3b6.md#10-záznam-výsledků-a-omezení).

Následuje samostatná etapa **3c – externí přihlášení**. Analýza byla revidována D09/D10, které mají přednost před původními D03–D05; implementace 3c.1 byla schválena a protokolový základ prošel verify a uživatelem potvrzeným PostgreSQL během; 3c.2 přidává webové přihlášení a registraci, její PostgreSQL ověření po opravě testovacího mockování uživatel potvrdil jako BUILD SUCCESS. Části 3c.3–3c.5 nejsou implementované. Podrobný plán, API, testy a technické brány jsou v [external-auth-3c.md](external-auth-3c.md). Závodní moduly ani změna e-mailu nejsou implementované; SMTP doručování zbývá ověřit před nasazením.

### Historické ověření 2A a 3b.1

**Etapa 2A – lokální autentizace je dokončena.** Automatické testy bez databáze
prošly (68 testů, `mvnw.cmd verify`, JDK 21). Uživatel potvrdil úspěšné integrační
testy nad `rallye_test` včetně lokální schránky (`mvnw.cmd -Pit verify`, BUILD SUCCESS)
a všechny manuální scénáře Postman kolekce včetně negativních případů 400/401/403.
Podrobný záznam je v [testing.md](testing.md#uzavření-etapy-2a).

Otevřený bod před produkčním nasazením: lokální souborové doručování nahradit
skutečným poskytovatelem SMTP nebo e-mailovým API a ověřit reálné doručování.
Existující konfigurovatelný SMTP adaptér je připravený; lokální profil zůstává
vývojovou pomůckou. Tento bod nebrání uzavření lokální autentizace.

Java 21, Spring Boot 4.1.1, Maven Wrapper 3.10.0, Spring MVC, Validation, JPA, Flyway a PostgreSQL driver. Produkční kód obsahuje vedle veřejného GET /api/hello moduly identity, crews, administration a audit. Perzistentní operace jsou implementovány přes JDBC; Data JPA zůstává mezi závislostmi. Produkční konfigurace zůstává zachována: PostgreSQL přes `127.0.0.1:5433/rallye_dev`, heslo z prostředí, Hibernate `validate`, Open Session in View vypnuté, Flyway zapnuté. Implementované jsou účty/LOCAL identita, e-mailové tokeny a databázové relace, role ADMIN a bootstrap, posádky/fotografie, převody kapitána, rušení účtů a auditní export/retence. Schéma nyní spravují V1–V9 a V10 (uživatelem potvrzené PostgreSQL ověření BUILD SUCCESS). Protokolový a webový základ Google/Apple přidávají 3c.1–3c.2; PostgreSQL ověření 3c.2 a skutečný provider smoke jsou vedené odděleně níže. Závody, konsolidace jediné identity a potvrzení zrušení 3c.3, mobilní předání, Apple revokace a změna e-mailu zatím nejsou implementované.

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
| `identity` | Samostatný účet s neměnným ID, e-mail/heslo a Google/Apple, ověření adresy, právě jedna neměnná LOCAL/GOOGLE/APPLE identita, obnova LOCAL hesla, relace a anonymizace. |
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

Etapa 2A používá Spring Security a neprůhledné databázové relace s hashovaným tokenem; OAuth2 Client/OIDC je základ 3c s platnými D09/D10; nebudovat vlastní autorizační server ani infrastrukturu JWT s obnovovacími tokeny. Aktivní interní účet má právě jednu neměnnou LOCAL/GOOGLE/APPLE identitu. LOCAL umožňuje změnu/reset svého hesla, externí účet heslo dodatečně nezíská. Změna jména/kontaktního e-mailu nemění identitu; API změny e-mailu zůstává mimo aktuální rozsah. Členové účasti nejsou identity. Lokální heslo se hashuje Argon2id přes Spring Security PasswordEncoder; povolená délka je schválených 8–128 Unicode znaků bez kategorií. Registrace uživatelského účtu e-mailem vyžaduje ověření adresy jednorázovým aktivačním odkazem zaslaným e-mailem; obnova hesla používá krátkodobý jednorázový náhodný token uložený jako hash. Obnova hesla má potvrzenou platnost 30 minut, jednorázové použití a jednotnou odpověď bez odhalení existence účtu. Login také nerozlišuje chybný e-mail a heslo. Změna e-mailu vyžaduje nové ověření uživatele i nové adresy. Potvrzovací odkaz má schválenou platnost 24 hodin. Výběr poskytovatele a ověření doručování jsou odloženy před nasazení; lokální profil používá souborovou schránku bez SMTP.

Web provozovat pokud možno na stejném originu jako API. Relace přes `Secure`, `HttpOnly`, přiměřený `SameSite` cookie; stavové požadavky chránit CSRF tokenem v hlavičce. Při externích callbackách zvlášť ověřit chování Apple `form_post` a cookie SameSite; neuvolňovat globálně CSRF. Přihlašování i odhlašování mění relaci bezpečně; regenerovat identifikátor po přihlášení. Neumisťovat session ID do URL nebo browser localStorage.

Mobil v etapě 2A získává samostatnou relaci přes X-Rallye-Client: mobile a používá Authorization: Bearer. Web má zabezpečenou HttpOnly cookie a CSRF ochranu; oba způsoby se v jednom požadavku nekombinují. Tajemství ukládat prostředky Android Keystore/iOS Keychain podle volby uchování. Následující základ externího toku je schválen D08; konkrétní API a realizace jsou návrhem v external-auth-3c.md. Externí přihlášení otevře systémový browser, nikoli embedded webview. Backend provede authorization code tok s Google/Apple, validuje issuer, audience, podpis, čas, state a nonce; PKCE použít tam, kde jej daný tok poskytovatele podporuje. Browserová relace se do mobilu nepředává přes URL: schválen je jednorázový předávací kód na 60 sekund vázaný na mobilní PKCE S256 challenge, výměna za mobilní relaci přes HTTPS a ověřené app/universal links. Prototyp musí ověřit Google i Apple specifika včetně Apple client-secret JWT a první odpovědi se jménem. Autorizační stav platí 10 minut, návraty jen z explicitní konfigurace. Protokolový základ je dokončen v 3c.1; obchodní externí přihlášení ani skutečné ověření poskytovatelů zatím neproběhly.

Etapa 2A místo původně zvažovaného Spring Session JDBC používá jednoduchou tabulku user_session s digestem náhodného tokenu a integrací do Spring Security filter chain. Tím se relační tajemství neukládá do DB v otevřené podobě a neukládá se serializovaný SecurityContext. Běžná relace má maximální nečinnost 24 hodin a session cookie/uchování jen během klientské relace. Trvalá má 30 dní nečinnosti a obnovuje se při používání. Serverové odhlášení je okamžité; změna hesla odvolá ostatní relace, obnova všechny. Relace přežijí restart serveru, protože autorita je v DB. Detaily API, cookies/Bearer, SMTP a omezení pokusů jsou v [authentication-2a.md](authentication-2a.md).

## Jediná neměnná přihlašovací identita

D09 nahrazuje D04/D05: žádný link/unlink, výměna identity ani dodatečný LOCAL. Externí klíč je (provider, issuer, subject), nikoli e-mail. Kolize e-mailu nezakládá právo na existující účet; generické chování 3c.2 se zachovává. D10 nahrazuje D03: vlastní LOCAL cancellation ověří znovu heslo, GOOGLE/APPLE vyžadují platnou relaci a výslovné potvrzení, bez auth_time/max_age či 5minutových grantů. Potvrzení není nové ověření identity.

V8 omezuje jen jednu identitu každého typu, proto plánovaná nová V10 doplní UNIQUE(account_id), odloženou kontrolu právě jedné identity u ACTIVE a zákaz změny/výměny s výjimkou řádného zrušení. Před migrací read-only preflight, bez automatického mazání či slučování konfliktů. V1–V9 se nemění. Přesný návrh triggerů, dopady na anonymizaci a testy jsou v [external-auth-3c.md](external-auth-3c.md). Zachovat bezpečné Apple šifrování a plán revokace při zrušení v 3c.5, nikoli při již zakázaném unlink.

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

## Akceptační scénáře realizovaných i budoucích částí

Konkrétní dosažené pokrytí 2A a 3b je v coverage-3b6.md; závodní scénáře níže zůstávají podklady pro budoucí implementaci.

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
| 409 | Konflikt stavu, změněná verze, idempotency key s jiným payloadem, konfliktní stav účtu. |
| 429 | Omezení pokusů; `Retry-After`. |
| 500/503 | Neočekávaná chyba/dočasná nedostupnost, klient může opakovat tentýž klíč. |

Přesné obchodní error codes a status uzávěrky potvrdit spolu s API kontraktem. Přijatý neúspěšný GPS/CODE pokus je běžný uložený výsledek pokusu, nikoli technická HTTP chyba.

## Audit

Navržená tabulka audit_event: ID události, UTC čas, interní aktér nebo systém/provozní správce, typ operace, typ/ID zdroje, request ID a omezená strukturovaná metadata. Audit obchodních změn ukládat ve stejné transakci. Běžné operace události pouze přidávají; schválený automatický retenční úklid je výslovná výjimka, nikoli obecné API pro editaci historie.

V etapě 3b auditovat změny posádek, převody, administrátorské zásahy včetně bootstrapu a správy rolí, úspěšná přihlášení, odhlášení, změny a resety hesel a rušení účtů. Neúspěšná přihlášení zatím pouze do provozních logů. V původní 2A transakční audit nebyl; 3b.1 jej již přidává do úspěšných bezpečnostních operací při zachování jejich funkčních pravidel a generických odpovědí. Audit posádek, převodů a rušení účtů je implementován současně s jejich operacemi v dokončených krocích 3b.2–3b.4. Do žádného logu ani auditu nepatří hesla, tokeny nebo nadbytečné osobní údaje.

CSV export používá read-only snapshot; po jeho dokončení zapíše AUDIT_EXPORT v samostatné transakci best effort. Selhání tohoto zápisu se bezpečně loguje.

Nadále auditovat budoucí start/konec, kódy a závodní zařízení, bodové úpravy, publikace a remízy, pozdní výjimky, uzamčení členů, konfiguraci, zrušení stanovišť a bezpečnostní operace účtů podle aktuální specifikace. Historie pokusů a výsledků není auditní tabulkou určenou k administrativnímu úklidu.

Schválení 3b.5 nahrazuje původní neomezenou retenci a ruční archivaci/mazání bezpečnostního auditu. Automatická retence je výchozích 365 dní, konfigurovatelné minimum 90 dní. Denně v 03:15 Europe/Prague se odstraní pouze události přísně starší než UTC now minus retence v 24hodinových dnech. Historie závodů, posádek a převodů se nemaže. Ruční archivace a dvoukrokové mazání se neimplementují.

CSV export je dostupný aktuálnímu ADMIN s platnou relací, bez omezení počtu řádků a stáří. UTF-8 s BOM, středník, escapování a ochrana proti vzorcům; časové údaje v Europe/Prague včetně offsetu. JDBC kurzor čte konzistentní REPEATABLE_READ snapshot otevřený až ve streamovacím vlákně, bez dlouhého zámku admin_control. Ověření session a aktuální role se opakuje před streamem. Běžné prodlužování session z autentizačního filtru zůstává zachováno; zákaz zápisů platí pro samotnou exportní transakci, nikoli celý HTTP požadavek.

Úklid koordinuje PostgreSQL advisory lock. Úspěšný den se eviduje v audit_cleanup_control, neúspěch se trvale neeviduje. Bez automatického dohánění po restartu či opakovací smyčky. Backfill related_account_ids u všech existujících idempotentních záznamů včetně expirovaných předchází mazání auditní historie. Neznámý formát nebo neověřitelná vazba způsobí rollback celého úklidu. Výmaz, AUDIT_CLEANUP a evidence úspěchu jsou jedna transakce. V7 zachovává explicitní seznam podporovaných migrací adaptéru účastí; neznámé budoucí verze zůstávají zakázané.

Fyzické smazání účtu nekaskáduje do auditu ani historických převodů: jejich pseudonymní UUID nemají blokující FK na účet. E-mail/jméno účtu se do auditu neukládají. 3b.4 zavedla anonymizaci a transakční úklid související idempotence včetně cizích aktérů a expirovaných záznamů. 3b.5 chrání tyto vazby před výmazem auditní historie; neověřitelný legacy CREW_CAPTAIN úklid zastaví. V1–V7 se při konsolidaci nepřepisují.

## Fotografie a HTML

Pro první jednu instanci navržen privátní adresář na perzistentním Docker volume mimo webroot; v DB pouze náhodný neprůhledný identifikátor a bezpečná metadata. Žádná veřejná cesta podle názvu uploadu; stahování přes autorizovaný endpoint podle vlastnictví/administrativních oprávnění. Později lze přejít na privátní objektové úložiště bez změny veřejných ID. Fotografie zálohovat spolu s konzistentní DB evidencí, odděleně od image aplikace.

Implementované fotografie vozidla jsou JPEG/PNG do 5 MiB (5 242 880 bajtů), maximálně 4096 × 4096 px. 3b.2 ověřuje skutečné dekódování, bajty a rozměry, znovu zakóduje obsah bez původních metadat a ukládá jej soukromě. SVG/HTML se nepřijímají. Ukládání a rollback i ručně spouštěný úklid nereferencovaných souborů starších než 24 hodin s ochranným zámkem a opakovanou kontrolou referencí jsou v crews-3b2.md; nejde o plánovač retence auditu. Klientovi se nevracejí cesty. HTML instrukce budoucích závodních modulů bude třeba sanitizovat na serveru podle malé allowlist s bezpečnými URL; správné kódy ani odpovědi nesmí být v klientském DTO, HTML ani logu.

## Rizika a zbývající rozhodnutí

- Databázové IT jsou uživatelem úspěšně ověřené nad rallye_test; další běhy provádí uživatel podle testing.md. Nedostupnost tunelu/údajů v prostředí Codexu není důvod měnit infrastrukturu ani oslabovat ochrany.
- Před novými migracemi ověřit návaznost na aplikovanou V1 a existující Flyway historii v povoleném prostředí. Žádný automatický baseline/repair/clean; V1 se nepřepisuje.
- Pravidla 3b ve specifikaci 0.6.0 jsou doplněna: historická vazba kapitána, rušení účtů/převodů, zakládání bez kapitána administrátorem, aktivní ověřený cíl a zákaz převodu na sebe sama. 3b.1 (auditní základ, ADMIN a SQL bootstrap) nemá blokující obchodní rozpor. Před závodní návazností určit přesný okamžik historického snímku vůči oficiálnímu/individuálnímu startu a pozdním účastem. Funkční pravidla 2A se nemění; SMTP/e-mailové API zbývá před nasazením.
- Před etapou účastí/průběhu: rozhodnout nejednoznačnosti oddílu 14 specifikace, atomickou uzávěrku a příjem příkazů. Nově potvrzená pravidla se znovu neotevírají.
- Před statistikami: přesné vyjádření mediánu při sudém počtu vzorků, pokud vyjde půl milisekundy; neupravovat kvůli tomu zaznamenané doby.
- Audit: schválená automatická retence 365 dní, minimum 90; streamovaný CSV export a best-effort AUDIT_EXPORT. Selhání rekonstrukce legacy idempotence zablokuje úklid bezpečně. Idempotence zůstává 24 hodin.
- Maven v minulém prostředí vybíral Javu 17 a PATH Javu 11: explicitně ověřovat JDK 21. Metadata některých zdrojů Flyway hlásí HTTP 401; nevkládat přístupové údaje do projektu.
- Budoucí ARM64 ověřit při kontejnerizaci; lokální Windows testy nevyžadují Docker, WSL ani systémové instalace.

## Následující samostatně ověřitelné etapy

| Etapa | Rozsah a ověření |
| --- | --- |
| 2A – lokální autentizace (dokončena) | Účty, LOCAL identita, ověření e-mailu, obnova a změna hesla, databázové relace, cookies/Bearer a CSRF. Automatické i manuální ověření úspěšné; skutečné doručování zbývá před nasazením. |
| 3a – infrastruktura testů (testovací připojení ověřeno) | Vyhrazená DB/role rallye_test je dostupná a uživatel potvrdil úspěšné IT včetně lokální schránky. Postup a read-only kontroly oprávnění zůstávají v ops/postgresql-test a postgresql-test-setup.md; úspěch IT sám nedokládá provedení všech administrátorských kontrol. Žádné další cloudové změny nespouštět automaticky. |
| 3b – dokončeno a ověřeno | 3b.1–3b.6 dokončeny; 234 běžných testů bez chyb, 133 PostgreSQL IT podle uživatelem potvrzeného běhu a dostupných reportů, šest potvrzených ručních regresních scénářů. Následuje 3c; bez zahájení implementace. |
| 3c – 3c.1/3c.2 dokončeny podle tehdejšího rozsahu | Aktuální D09/D10: jedna neměnná identita, LOCAL cancellation s heslem, externí cancellation s relací a potvrzením. 3c.3 konsoliduje invariant a potvrzení; 3c.4 zachová mobilní PKCE a 3c.5 Apple revokaci. Žádné skutečné provider ověření dosud neproběhlo. |
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
na sebe sama. Pro úklid otestovat přesnou hranici retence, minimum 90 dní, souběh instancí, rollback auditu a bezpečnost legacy vazeb. U CSV ověřit BOM, středník, ochranu před vzorci, snapshot při souběžném úklidu a best-effort audit; u idempotence shodný výsledek, HTTP 409 a 24 hodin.

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
| 3b.3 – dokončeno a ověřeno | Běžný převod na ID cíle do 7 dní, zrušení původním kapitánem, editace ano/smazání ne; administrativní změna bez potvrzení je již v 3b.2; 3b.3c doplňuje atomické zrušení čekající žádosti při změně/odebrání kapitána. Část 3b.3a je dokončená a ověřená: V4 a interní životní cyklus s povinnou vnější transakcí; 137 testů bez DB a 48 integračních testů bez chyb, integrační BUILD SUCCESS potvrdil uživatel. Část 3b.3b je dokončená a ověřená; přidává REST založení a resend, tokeny, SMTP/souborové doručení po commitu a stávající idempotenci; verify prošlo se 159 testy bez DB, 56 IT prošlo v uživatelem potvrzeném běhu. Část 3b.3c implementuje accept/reject/cancel, audit včetně REQUESTED, ochrany DELETE a ADMIN změn, V5 a oddělené otisky idempotence. 3b.3c dokončeno a ověřeno: verify 176 testů bez DB, 71 IT bez chyb v uživatelem potvrzeném běhu; Revize 3b.3d dokončila kontrolu převodů, opravila Postman replay a doplnila souběh resend/accept a 24h retenci; verify 176 testů prošlo, uživatel potvrdil nový integrační BUILD SUCCESS (73 IT, 0 chyb). Automatická revize je ověřená; uživatel potvrdil také ruční scénáře přístupu obou kapitánů provedené v 3b.3c. Převody 3b.3 jsou uzavřené. Viz [crew-transfers-3b3d.md](crew-transfers-3b3d.md). Uzavírá se pouze 3b.3, dále následují 3b.4 → 3b.5 → 3b.6 → 3c. Viz [crew-transfers-3b3c.md](crew-transfers-3b3c.md). Viz [crew-transfers-3b3b.md](crew-transfers-3b3b.md). Podrobnosti a rozdělení 3b.3a–d: [crew-transfers-3b3a.md](crew-transfers-3b3a.md). |
| 3b.4 – dokončeno a ověřeno | Rušení účtu uživatelem i ADMIN: bez historické účasti smazat, jinak anonymizovat; během závodění odmítnout, pouhou přihlášku zachovat. Všechny čekající převody daného uživatele zrušit, posádky zachovat bez kapitána. Ověřit transakční rollback, FK, neměnnou historickou vazbu i po převodu a zákaz zrušení posledního ADMIN. |
| 3b.5 – dokončeno a ověřeno | V7, streamovaný CSV export, best-effort AUDIT_EXPORT a automatická retence 365 dní (minimum 90), advisory lock a bezpečný legacy backfill. Po opravě P2 verify: 234 testů bez DB. Uživatel potvrdil původní PostgreSQL běh i ruční CSV export, filtry, AUDIT_EXPORT a zákaz běžného uživatele. Legacy CREW_CAPTAIN bez metadat nyní retenci konzervativně zastaví; uživatel potvrdil úspěšné `.\mvnw.cmd -Pit verify` i po opravě P2 včetně 17 scénářů AuditMaintenanceIT. Viz [audit-3b5.md](audit-3b5.md). |
| 3b.6 – dokončeno a ověřeno | 3b.6a: matice pokrytí; 3b.6b: čtyři společné IT a posílení testu bezpečných logů; 3b.6c: šest scénářů potvrdil uživatel proti rallye_dev; 3b.6d: dokumentace konsolidována a verify znovu prošlo s 234 testy. Dřívější PostgreSQL běh potvrdil uživatel; dostupné reporty 133/133 bez chyb. Produkční kód ani migrace se nemění. Viz [review-3b6.md](review-3b6.md). Commit a push dokončeny jako 19f5ad52a5ece6d520206dcebbf382fdfc82b32b. |

Převodní zprávy již mají samostatný účel a odkaz v SMTP i lokálním adaptéru přes sendTransfer; neprocházejí větví obnovy hesla. Zakládání, změna vlastníka, zrušení čekajícího převodu, audit a idempotentní výsledek musejí být konzistentní v jedné transakci. Administrátorské zásahy také neobcházejí zákaz mazání historické posádky. Fyzické rušení účtů neznamená mazání historie posádek ani auditu.

### Dílčí realizace 3b.4a

Připraven databázový základ rušení účtů: V6 (ANONYMIZED, cancelled_at a
related_account_ids), interní perzistentní operace s povinnou transakcí,
odpojení posádek, ukončení čekajících převodů na obou stranách a úklid
idempotentních záznamů včetně starších formátů a e-mailů v JSON hodnotách.
Podrobnosti a omezení jsou v [account-cancellation-3b4a.md](account-cancellation-3b4a.md).
3b.4a je dokončena a ověřena: `mvnw.cmd verify` prošlo se 184 testy bez DB;
uživatel potvrdil `mvnw.cmd -Pit verify` – BUILD SUCCESS, 91 PostgreSQL IT.
Navazující 3b.4b je nyní také dokončena; tím je uzavřena celá 3b.4.

### Dílčí realizace 3b.4b – dokončeno a ověřeno

Společná AccountCancellationService spojuje vlastní a ADMIN rušení v jediné
transakci s aktuálním oprávněním, kontrolou hesla, posledního aktivního ADMIN,
odpojením posádek, ukončením převodů, odstraněním přihlašovacích prostředků,
úklidem idempotence a auditem výsledku DELETED/ANONYMIZED i navazujících změn.
Nové POST endpointy jsou `/api/v1/auth/account-cancellations` a
`/api/v1/admin/users/{accountId}/cancellations`; oba vyžadují UUID Idempotency-Key.
ADMIN replay funguje i po odstranění cíle; vlastní cesta odstraní cookie a relace.

Adaptér AccountParticipationFacts nyní dovolí prázdná fakta pouze pro přesné
V1–V6 nebo výslovně schválenou posloupnost do V7__audit_export_and_retention.sql;
neznámé budoucí schéma odmítá 503. Samotná 3b.4b V7 nepřidávala, následná 3b.5 ano.
Závody ani OAuth se tím neimplementují; závodní migrace musí dodat skutečný adaptér účastí.
Pořadí zámků autentizace je sjednoceno s rušením a ošetřuje zmizení účtu před
zámkem. Připraveny jsou unit/HTTP testy, PostgreSQL souběhy/rollback a Postman.
Viz [account-cancellation-3b4b.md](account-cancellation-3b4b.md).
Codex ověřil `mvnw.cmd verify`: BUILD SUCCESS, 211 testů bez DB;
uživatel potvrdil `mvnw.cmd -Pit verify`: BUILD SUCCESS, 112 PostgreSQL testů
bez chyb včetně 21 nových scénářů. Nejde o vlastní PostgreSQL běh Codexu.
Uživatel následně potvrdil ruční Postman scénáře včetně osiřelé posádky a jejího
převzetí. Review neodhalilo funkční problém. Závěrečné `-Pit verify` v prostředí
Codexu po 217 úspěšných testech bez DB odmítlo chybějící testovací konfiguraci;
nový PostgreSQL běh tedy není ověřen. Viz [review-3b4b.md](review-3b4b.md).
Samostatné závěrečné `verify` s dočasně omezenou pamětí JVM prošlo: 217 testů,
BUILD SUCCESS. Produkční kód nebylo při review potřeba měnit.
Uživatel následně potvrdil závěrečné `mvnw.cmd verify` – BUILD SUCCESS,
217 testů, `mvnw.cmd -Pit verify` – BUILD SUCCESS ve svém prostředí a všechny
plánované ruční scénáře. Předchozí překážka opakování IT je tím vyřešena;
3b.4b i celá 3b.4 jsou uzavřeny. U 3b.5 uživatel potvrdil původní PostgreSQL běh a ruční CSV scénáře. Následná oprava P2 prošla verify bez DB i uživatelem potvrzeným PostgreSQL během; 3b.5 je dokončena a ověřena. Následná 3b.6 je nyní rovněž dokončena; plánovaná zůstává 3c.

## Implementační plán 3c – schválená rozhodnutí a stav realizace

Stav 3c.1: **dokončena a otestována** ve schváleném protokolovém rozsahu; V8 a unit/HTTP/PG testy jsou součástí implementace. Verify prošlo s 250 testy; úspěšné PostgreSQL ověření potvrdil uživatel (`.\mvnw.cmd -Pit verify`, BUILD SUCCESS). Codex PostgreSQL běh neprovedl a skutečný provider nebyl použit. Podrobný stav: [external-auth-3c1.md](external-auth-3c1.md). Část 3c.2 je implementačně dokončena a lokálně otestována: verify 271 testů, uživatelem potvrzený PostgreSQL BUILD SUCCESS a 6/6 asercí Postman složky 16; 3c.3a je implementována a uživatelem integračně ověřena (BUILD SUCCESS); 3c.3b je implementována a uživatelem potvrzeným PG během ověřena; Samostatná 3c.3c je zrušena jako překonaný rozsah po D09/D10 (regrese a dokumentace součástí dokončených 3c.3a/3c.3b); 3c.4–3c.5 zůstávají plánované.

Aktuálně platí specifikace 0.8.0: D09 nahrazuje D04/D05 a D10 nahrazuje D03. Ostatní rozhodnutí zůstávají. Historie 0.7.0 se nepřepisuje; external-auth-3c.md rozlišuje současnou implementaci a navrženou V10.

| Část | Funkční rozsah a DB/API | Ověření a dokončení | Závislosti |
| --- | --- | --- | --- |
| 3c.1 – dokončena a otestována | OAuth2 Client/OIDC, konfigurace providerů, V8, UNIQUE(account_id,provider), krátkodobé kontexty a bezpečnostní infrastruktura; přesný allowlist migrací adaptéru účastí a úklid nových vazeb | Unit/HTTP validace state/nonce/claims/callback/CSRF a chyb; PG upgrade V7, jednorázovost, souběhy, rollback a odmítnutí neznámého schématu. Postman bezpečné chyby, provider simulace. Historicky ověřeno podle 0.7.0; původní brána čerstvosti byla následně zrušena D10 | Historický rozsah D01–D08 před D09/D10, ověření knihovních extension points; doména není nutná pro simulované testy |
| 3c.2 – implementačně dokončena a lokálně otestována | První/další webový login obou providerů, ověření e-mailu, dokončení profilu bez plné session, cookie a audit; registrační kontext/callback/dokončovací API | Unit/HTTP claims, chybějící jméno, kolize e-mailu, Apple form_post; PG první souběžný login a rollback účet/identita/session/audit. Browser skutečný smoke, Postman /me/logout; vykazovat mock a skutečnou integraci odděleně | 3c.1; expirace kontextu uživatelem schválena 15 minut, korelace podpořena automatickými testy; Postman 16 ověřen uživatelem (6/6); skutečný Google/Apple login a pozitivní Postman 17 neověřeny, vyžadují HTTPS testovací prostředí |
| 3c.3 – 3c.3a dokončena, 3c.3b dokončena a lokálně ověřena | 3c.3a: V10, jedna neměnná identita, odstranění LINK/REAUTH podpory; 3c.3b: LOCAL heslo / externí potvrzení cancellation; 3c.3c: zrušena jako překonaný samostatný krok po D09/D10; regrese a dokumentace zahrnuty do 3c.3a/3c.3b. Žádné čerstvé granty, link/unlink nebo LOCAL-add API. | Unit/HTTP odmítnutí zakázaných účelů, CSRF a potvrzení; PG souběhy, neměnnost včetně DELETE+INSERT, ACTIVE bez identity, anonymizace/rollback/guard, regrese cancellation. | 3c.1–2, schválené D09/D10; případné konflikty dat před V10 vyžadují rozhodnutí. |
| 3c.4 | Systémový browser, LOGIN, 60s handoff/PKCE S256 a existující Bearer; žádné mobilní propojování. | Replay, verifier, expiry, souběh exchange, rollback session/auditu, cancellation mezi callbackem a exchange; skutečné mobilní odkazy samostatně. | 3c.1–2 a konsolidace invariantů 3c.3a, bez závislosti na čerstvých grantech. |
| 3c.5 | Externí vlastní cancellation podle D10 a ADMIN cesta, Apple revokační enqueue/worker/retry/lease, bezpečný úklid po dokončení. Žádná revokace při unlink. | Atomita cancellation+enqueue, DELETE/anonymizace, restart/crash/souběhy, nová registrace během staré revokace, bezpečné klíče/logy; skutečný Apple smoke samostatně. | 3c.3b, mobilní regrese 3c.4 podle rozsahu, rozhodnutí o korelaci nové autorizace s čekající starou revokací. Apple reautentizace není podmínkou. |

Po každé implementační části Maven Wrapper verify na JDK 21. PostgreSQL *IT výhradně uživatelem přes `.\mvnw.cmd -Pit verify` s TestDatabaseInitializer nad rallye_test. Běžné testy nesmějí kontaktovat poskytovatele. Skutečný smoke vyžaduje samostatné schválení konfigurace/prostředí, žádnou infrastrukturu nyní nezřizovat.

Technické brány: skutečná browserová korelace Apple form_post a bezpečný souběh nové registrace se starou Apple revokací. Čerstvost Google/Apple ani politika relací při link/unlink již nejsou otevřené otázky: D10 čerstvé ověření nevyžaduje a D09 tyto změny identit zakazuje. 15minutová registrace je schválená. Žádná automatická obnova identity ani provider subject migrace se nezavádí.

### Realizace 3c.2 – implementačně dokončena a lokálně otestována

[external-auth-3c2.md](external-auth-3c2.md) popisuje V9, webové session, profil a vlastní ověření e-mailu, 15minutový kontext schválený uživatelem a nově schválené minimální šifrované Apple úložiště. Při neznámé identitě callback vždy vydá jen omezenou registrační cookie; dokončení je generické 202 bez session i při kolizi. Po případné aktivaci následuje nové externí přihlášení. Žádné automatické propojení podle adresy.

V1–V8 se nepřepisují, V9 je výslovně přidána do migration guardu; neznámé V10 jsou odmítané. Rušení účtu zneplatní i dokončovací kontexty, nezbytný Apple ciphertext zůstává bez FK pro pozdější revokaci. Apple revokační volání, worker/retry ani externí vlastní potvrzení cancellation se v 3c.2 neimplementují. Před produkčním zapnutím zůstávají nutné navazující 3c.3/3c.5 a skutečný HTTPS/provider smoke. Nevzniklo nasazení ani testovací doména.

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


### Realizace 3c.3a

[Databázový invariant a odstranění toků](single-identity-3c3a.md) popisuje V10, preflight bez mazání dat, odložené triggery pro registraci/cancellation, souběhy a nepřístupné LINK/REAUTH. LOCAL změna/reset a Apple ciphertext zůstávají. PostgreSQL testy po opravě úklidu úspěšně spustil uživatel (BUILD SUCCESS), nikoli Codex. 3c.3b přidává externí potvrzení zrušení a PostgreSQL ověření uživatel potvrdil jako BUILD SUCCESS.


Uzavření 3c.3a: verify bez DB 274 testů (BUILD SUCCESS, běh Codexu), uživatelem potvrzený PostgreSQL verify po opravě testovacího úklidu (BUILD SUCCESS) a Postman složka 18 (6/6 Passed). V10 se po aplikaci nezměnila, V11 nevznikla. Nová specifikace 0.8.0 je závazná, 0.7.0 historická. 3c.3b je implementována a integračně ověřena uživatelem; LOCAL manuální ověření dokončeno; skutečné externí zrušení čeká na HTTPS; skutečný Google/Apple login a pozitivní složka 17 zůstávají neověřené.


### Realizace 3c.3b – D10 cancellation

[Kontrakt a ověření](account-cancellation-3c3b.md): zachovaný POST account-cancellations s currentPassword pro LOCAL, confirmed=true pro GOOGLE/APPLE. Typ jedině z DB, stávající transakce/audit/CSRF a odvolání sessions. Apple materiál zůstává mimo FK účtu; chybějící prostředek blokuje self i ADMIN zrušení. Bez migrace, V1–V10 beze změny; revokace/retry zůstávají 3c.5. Verify bez DB prošlo s 284 testy; PG běh této podetapy uživatel potvrdil jako BUILD SUCCESS; uživatel potvrdil Postman 19 (35/0) a skutečné LOCAL SELF zrušení 204, odvolání relace, odmítnutý login a reset ochranného povolení na false. 3c.3b je dokončena a lokálně ověřena; skutečné GOOGLE/APPLE zrušení a složka 20 čekají na HTTPS testovací prostředí.


### Příprava 3c.4 – schválený návrh D11–D13; bootstrap 3c.4a.1 implementován a automaticky ověřen

[Technický návrh a konečný rozsah](mobile-auth-3c4.md) rozlišují existující Bearer relace, state/nonce a V8 MOBILE primitiva od dosud chybějícího browser bootstrapu, vydání grantu a exchange. D11–D13 jsou následná schválená rozhodnutí k závazné specifikaci 0.8.0; specifikace se tímto dokumentačním úkolem nepřepisuje.

- **D11:** krátkodobý jednorázový launch ticket. Spotřeba ticketu a založení flow jsou atomické, browserová korelace se bezpečně doručí po commitu. Ticket sám neumožňuje přihlášení.
- **D12:** první externí registrace používá existující browserové dokončení profilu a případnou aktivaci. Poté nový mobilní LOGIN. Žádné paralelní mobilní registrační API. Chybějící webová stránka je závislost reálného end-to-end ověření, nikoli blokace backendu 3c.4a.
- **D13:** oddělené webové a mobilní návratové cíle, pouze předem povolené adresy a bezpečný HTTPS fallback bez automatické výměny kódu. Konkrétní doména a podpisy aplikace se řeší v 3c.4b.

**3c.4a – konečný schválený rozsah backendu (plánováno):** mobilní větev stávajícího start API, browser launch, rozlišení callbacků, omezená browserová registrace dle D12, vydání 60s grantu a atomický exchange na existující Bearer session + LOGIN_SUCCEEDED. Mobilní PKCE S256 musí být nezávislé na provider PKCE; verifier/challenge jsou vázané na konkrétní pokus/flow/grant. requestId není autentizační prostředek. Po zrušení účtu se grant nesmí uplatnit ani převázat na nový účet. Žádné přístupové nebo obnovovací tokeny v návratových URL. Autorizační kontext zůstává 10 minut.

Do implementačního úkolu patří navržená V11 bez změn V1–V10, přesný migration guard, konfigurace a validace cílů/fallbacku, bezpečnostní kontroly, cancellation a V10 regrese, unit/HTTP testy a uživatelem spouštěné rallye_test IT (expirace, replay, souběh, rollback auditu/session/grantu a cancellation proti exchange). Dokumentace a budoucí Postman sady oddělí lokální negativní scénáře od připravených HTTPS pozitivních. V nynějším dokumentačním úkolu se kód, migrace ani kolekce nemění.

**3c.4b – mobilní integrace a skutečné ověření (plánováno):** Android systémový browser a App Links, chráněné uložení tokenu podle rememberMe, skutečné zařízení/HTTPS/Google/Apple a profilová stránka jako závislost registrace; příprava iOS Universal Links. Bez samostatného zadání nezřizovat doménu, klientské aplikace ani nasazení. Backendové syntetické testy tuto akceptaci nenahrazují.

**Blokující otázky 3c.4a: žádné.** Q1–Q3 jsou uzavřené D11–D13. Skutečná Apple provider PKCE podpora není potvrzena; povinná S256 ochrana handoff dle D08 platí nezávisle. Provider interoperabilita se vykazuje samostatně. Apple revokační worker/retry zůstávají 3c.5 a nadále podmiňují produkční Apple cestu.

### Vyjasnění 3c.3c po D09/D10

Samostatný krok **3c.3c je zrušen jako překonaný rozsah**, nikoli označen za nově implementovanou etapu. D09 odstranilo správu více identit, link/unlink a dodatečný LOCAL; D10 zrušilo požadavek čerstvé externí reautentizace pro cancellation. Potřebné odmítací a bezpečnostní regrese i aktualizace dokumentace byly zahrnuty do dokončených 3c.3a a 3c.3b (viz single-identity-3c3a.md a account-cancellation-3c3b.md). Nezůstává samostatný funkční rozsah k implementaci před 3c.4a. Toto vyjasnění neruší testy a neprohlašuje skutečné Google/Apple ověření za dokončené; historické zmínky o plánované 3c.3c v jiných dokumentech jsou překonány tímto aktuálním plánem.


### Realizace 3c.4a.1 – mobilní start a browser launch

[Kontrakt, konfigurace a ověření](mobile-bootstrap-3c4a1.md): mobilní větev stávajícího POST requests vrací requestId/browserStartUrl/expiresAt; nový veřejný GET mobile/launch atomicky spotřebuje hashovaný ticket a vytvoří browserový OAuth flow s korelační cookie. Desetiminutová hranice se neprodlužuje. Samotný ticket/requestId nevytváří přihlášení. Webový start, state/nonce a provider PKCE zachovány. V11 přidává jen mobile_login_attempt, nikoli grantové změny původně uvažované pro celou 3c.4a; další podetapa případně přidá navazující migraci bez změn V11.

Verify bez DB prošlo: BUILD SUCCESS, 296 testů; PostgreSQL MobileLoginIT má 10 nových scénářů; uživatel podle AGENTS.md potvrdil nový `.\mvnw.cmd -Pit verify` proti rallye_test s výsledkem BUILD SUCCESS. Codex PostgreSQL běh neprovedl; celkový počet testů běhu nebyl sdělen. Postman 21 je lokální negativní sada při vypnutých providerech, 22 oddělený připravený HTTPS bootstrap bez následování provider redirectu. Postman 21 uživatel potvrdil jako All Passed i po bezpečnostních opravách; HTTPS/provider běh a složka 22 nejsou potvrzené. Callback zůstává EXTERNAL_MOBILE_UNAVAILABLE; 60s grant, exchange, mobilní session ani 3c.4a.2 nejsou implementovány.


Bezpečnostní revize 3c.4a.1: [ticket privacy report](review-launch-privacy-3c4a1.md). Doplněny časné hlavičky všech launch odpovědí, redakce request logování a ochrana konfigurace Tomcat access/wire logů. Nový verify bez DB: BUILD SUCCESS, 306 testů. Uživatel potvrdil původní Postman 21 a PostgreSQL běh; nové privacy aserce Postman 21 uživatel následně potvrdil jako All Passed. PostgreSQL verify proběhlo před těmito opravami, nikoli po nich. Žádná změna V11 či zahájení 3c.4a.2. Reálné browser/proxy ověření zůstává 3c.4b.


Uzavření 3c.4a.1: implementačně dokončena a lokálně ověřena v popsaném rozsahu, včetně uživatelem potvrzené Postman 21 s privacy kontrolami. Zbytek 3c.4a (včetně 3c.4a.2), skutečná mobilní integrace 3c.4b a Apple revokace 3c.5 zůstávají plánované.
