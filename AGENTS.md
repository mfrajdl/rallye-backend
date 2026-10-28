# Pravidla práce na Rallye Backend

Tato pravidla jsou závazná pro další práci Codexu v tomto repozitáři.

## Základ a rozsah

- Respektuj existující funkční konfiguraci a skutečnou strukturu projektu. Základ je Java 21, Spring Boot 4.1.1, Maven Wrapper, PostgreSQL 17, JPA, Flyway, Spring MVC a Jakarta Validation.
- Kořenový Java package je `cz.mh.rallye.rallye_backend`. Neměň jej bez zadání.
- Architektura je modulární monolit v jedné Spring Boot aplikaci. Čleň podle funkčních oblastí; návrh je v `docs/backend-plan.md`. Nevytvářej prázdné moduly ani zbytečné frameworky a abstrakce.
- Závazná funkční specifikace je `docs/Zavody_specifikace_v0.9.0.md` (verze 0.9.0), vydání jsou v `docs/Zavody_specifikace_vX.Y.Z.md`. Rozlišuj potvrzená pravidla od pracovních návrhů a respektuj závaznou terminologii stanovišť a konání. Nevymýšlej chybějící obchodní pravidla. Nejasnosti zapisuj jako otázky k rozhodnutí a neimplementuj závislé chování před jejich vyřešením.
- Schválená etapa 2A zahrnuje uživatelské účty, lokální autentizaci, tokeny, databázové relace, Flyway migrace a testy. Bez dalšího zadání neimplementuj posádky, závody, Google/Apple OAuth ani změnu e-mailu. `/api/hello` může zůstat. Účet patří kapitánovi; trvalá posádka nemá seznam členů, členové patří pouze konkrétní účasti.
- Schválená implementace 3b.1 přidává ADMIN, správu rolí jiných aktivních účtů, jednorázový SQL bootstrap, audit změn rolí a úspěšných bezpečnostních operací 2A a administrativní čtení auditu. Dokončená 3b.5 přidává streamovaný CSV export s následným best-effort auditem a automatickou retenci 365 dní (minimum 90), denně 03:15 Europe/Prague. Ruční archivace ani dvoukrokové mazání se nezavádějí; podrobnosti v docs/audit-3b5.md. Změny rolí, bootstrap a budoucí rušení/deaktivace účtů musejí sdílet invariant posledního aktivního ADMIN a konzistentní pořadí zámků podle docs/administration-3b1.md. Bootstrap nespouštěj bez výslovného zadání cílového prostředí.
- Schválená 3b.2 zahrnuje CRUD posádek, soukromé fotografie, okamžité administrátorské přiřazení/změnu/odebrání kapitána, společný audit a rozšíření existující idempotence. Potvrzované převody zůstávají v 3b.3. Cíl kapitánství je aktivní ověřený účet; stejný dosavadní kapitán se odmítá, ADMIN smí převzít cizí/osiřelou posádku. Texty, fotografie a kapitánství sdílejí version. Souborový úklid pouze ručně a s kontrolou referencí a ochranným zámkem; viz docs/crews-3b2.md.

Aktuální rozhodnutí 3c jsou převzata ve specifikaci 0.9.0 a docs/external-auth-3c.md. D09 nahrazuje D04/D05: aktivní účet má právě jednu neměnnou LOCAL/GOOGLE/APPLE identitu; zakázané jsou LINK/UNLINK, změna identity i dodatečný LOCAL. D10 nahrazuje D03: vlastní LOCAL rušení vyžaduje znovu heslo; GOOGLE/APPLE platnou relaci a samostatné výslovné potvrzení, bez auth_time/max_age, nového provider loginu či pětiminutových grantů. Potvrzení není nové ověření, jde o schválený kompromis. Zachovat CSRF/audit/atomicitu/ADMIN a 24h/30d relace. Profilové jméno/e-mail nejsou identita; API změny e-mailu se nyní nezavádí. Nezavádět automatickou alternativní obnovu či výměnu subjectu.

3c.1/V8 a 3c.2/V9 jsou dokončené podle tehdejšího rozsahu. V8 dovoluje jednu identitu každého typu, nový invariant implementuje připravená V10 podle docs/single-identity-3c3a.md, s uživatelem potvrzeným PostgreSQL ověřením BUILD SUCCESS. Zachovat přesný migration guard a cancellation/anonymizaci. Nepřepisovat V1–V9 ani starší specifikace. Mobilní state má 10 minut, handoff 60 sekund s PKCE S256; nový plán nemá mobilní správu identit. Apple ciphertext i revokace při zrušení/retry zůstávají v rozsahu 3c.5; žádná revokace při zakázaném unlink. Skutečné provider/browser ověření je oddělené od simulovaných testů. 3c.3a i 3c.3b jsou schválené implementace; D10 kontrakt a ochrana Apple materiálu jsou v docs/account-cancellation-3c3b.md, PG ověření 3c.3b potvrdil uživatel jako BUILD SUCCESS. 3c.3c a 3c.4/5 bez dalšího zadání nepřidávat. Žádné SQL operace na aplikačních DB ani infrastruktura nejsou tímto autorizované.

Schválená 3c.2 přidává webový login/registraci podle docs/external-auth-3c2.md, V9 a dokončovací kontext na 15 minut. Uživatel schválil minimální šifrované Apple úložiště již v 3c.2, včetně zachování pro revokaci po zrušení účtu. Volání revokace, worker a retry zůstávají v 3c.5; nepřidávat je bez zadání. Kolize adresy nepropojuje účty a dokončení má generické 202 bez session; vlastní aktivace má 24 hodin. Google/Apple smoke nelze odvozovat ze simulací.

## Implementace

- Odděluj REST DTO a controllery, aplikační logiku a perzistenci. JPA entity nevystavuj v REST API.
- Server musí vynucovat pravidla soutěže i oprávnění; klientská validace nestačí.
- Konzistenci změn zajišťuj transakcemi v aplikační vrstvě a odpovídajícími databázovými omezeními. Pro každou měnící operaci zvaž souběh, opakování HTTP požadavku a idempotenci.
- Časové okamžiky zpracovávej konzistentně v UTC, přednostně pomocí `Instant`. Místní čas a časové pásmo používej explicitně pro zobrazení či plánování podle specifikace.
- Schéma spravuj výhradně verzovanými Flyway migracemi. Zachovej `spring.jpa.hibernate.ddl-auto=validate` a `spring.jpa.open-in-view=false`. Neměň již aplikované migrace; opravy prováděj novou migrací.
- Pro implementovanou aplikační logiku přidej automatické testy, včetně relevantních hraničních stavů, souběhu a opakovaných požadavků. Běžné testy musí běžet bez databáze. PostgreSQL integrační testy `*IT` spouštěj pouze profilem `it` a přes `TestDatabaseInitializer`, proti samostatné databázi a roli `rallye_test`; nikdy proti `rallye_dev` ani `rallye`. Na Windows kvůli testům neinstaluj Docker Desktop, WSL ani jiné systémové komponenty. Cloudovou testovací databázi nevytvářej ani neměň bez schválení. Podrobnosti v `docs/testing.md`.
- Důležité operace musí mít audit podle schválené specifikace. Do logů a auditu neukládej tajné údaje.

## Bezpečnost a provoz

- Hesla, tokeny a privátní klíče nikdy neukládej do repozitáře ani nevypisuj do výstupů. Používej proměnné prostředí. `.env` se automaticky nenačítá.
- Výjimkou pro ruční lokální vývoj je výslovný profil `local`: aktivační/resetovací zprávy ukládá do soukromé souborové schránky mimo Git, nikdy do logu ani API. Server musí poslouchat pouze na loopbacku. Zprávy obsahují jednorázové tokeny, po použití je uživatel odstraní. Výběr SMTP poskytovatele a ověření doručování jsou úkoly před nasazením, nyní bez další infrastruktury.
- Lokální vývoj používá SSH tunel `127.0.0.1:5433` do databáze `rallye_dev`. Při ověřování ber v úvahu, že Spring kontext spouští Flyway proti nakonfigurované databázi; neobcházej chyby pomocí `clean`, automatického DDL nebo neuváženého baseline/repair.
- Destruktivní změny databáze vyžadují výslovný souhlas uživatele.
- Nenasazuj na Oracle Cloud bez výslovného zadání. Budoucí prostředí je Docker na Ubuntu ARM64; ověř dostupnost ARM64 image a závislostí před nasazením.
- Neprováděj Git commit ani push bez souhlasu. Zachovej cizí rozpracované změny a před editací zkontroluj stav Gitu.

## Ověření a předání

- Integrační testy proti PostgreSQL spouští uživatel ručně ve svém PowerShellu. Codex je píše a udržuje, ale nezajišťuje vlastní DB přístup a nespouští je sám. Když změna vyžaduje integrační ověření, předej přesný příkaz (standardně `.\mvnw.cmd -Pit verify`) a počkej na výsledek před označením závislé změny za ověřenou. Kompilaci a testy bez databáze spouštěj samostatně dál. Výsledek dodaný uživatelem označ jako uživatelem potvrzený, nikoli jako vlastní běh Codexu.

- Používej Maven Wrapper a ověř JDK pomocí `./mvnw -version` nebo `.\mvnw.cmd -version`; Maven musí používat JDK 21.
- Spouštěj relevantní testy a `verify`. Přesně rozlišuj úspěšné sestavení, přeskočené testy a neověřenou integraci.
- Pokud chybí nástroj, připojení nebo tajný údaj, popiš konkrétní překážku; neoslabuj konfiguraci, aby kontrola zdánlivě prošla.
- Aktualizuj dokumentaci spuštění a rozhodnutí. Na konci uveď změněné soubory, výsledky ověření, omezení a další krok.
