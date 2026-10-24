# 3c.3a – jediná neměnná přihlašovací identita

Stav: **3c.3a dokončena a lokálně ověřena** (Postman 18: 6/6 Passed, potvrzeno uživatelem); po opravě testovacího úklidu uživatel potvrdil PostgreSQL verify s výsledkem BUILD SUCCESS. Závazná specifikace 0.8.0, D09/D10. Tato část neimplementuje externí zrušení účtu s potvrzením (3c.3b), mobilní handoff (3c.4) ani Apple revokaci/retry (3c.5).

## Skutečný životní cyklus a V10

V1–V9 se nemění. Nová `V10__single_immutable_identity.sql` probíhá transakčně ve Flyway a nejprve uzamkne dotčené tabulky. Preflight odmítne více identit jednoho účtu, ACTIVE bez identity, ANONYMIZED s identitou a jakékoliv staré LINK/REAUTH kontexty nebo granty (včetně expirovaných/spotřebovaných). Nic nemaže ani nevybírá vítěznou identitu. Chyba SQLSTATE 23514 zastaví migraci a rollbackne její změny. Stav skutečných aplikačních databází Codex nekontroloval; nelze tvrdit, že jejich preflight prošel.

- `UNIQUE(account_id)` okamžitě vynucuje nejvýše jednu identitu celkem, včetně souběžných INSERT. Původní globální unikátnost `(provider, issuer, subject)` zůstává.
- BEFORE trigger odmítá změnu `id`, `account_id`, `provider`, `issuer`, `subject` pomocí SQLSTATE 23514. LOCAL password_hash se smí změnit/resetovat podle stávajících pravidel; samotný hash není identita. Původní provider/password_hash CHECK zůstává.
- DEFERRABLE INITIALLY DEFERRED constraint triggery při commitu kontrolují právě jednu identitu u ACTIVE a žádnou u ANONYMIZED. Aktivace účtu bez identity se vrátí zpět. PENDING_EMAIL/DISABLED mohou mít nejvýše jednu; jejich nulový počet neumožňuje přihlášení ani aktivaci bez doplnění identity. Již vytvořenou identitu ani u nich nelze vyměnit.
- Samostatná kontrola každé DELETE události dovolí commit jen po skutečném zrušení účtu: rodič již neexistuje nebo je ANONYMIZED. DELETE+INSERT se odmítne i při zachování původního UUID identity a i u PENDING_EMAIL/DISABLED. Nestačí konečný počet jedna.
- ANONYMIZED je terminální: nepřidá se k němu identita a stav se nevrátí na přihlásitelný. Interní ID účtu se nemění.
- Při registraci se účet a identita vloží v jedné transakci. U stávajícího zrušení se nejprve odstraní identity/relace/tokeny a účet se pak smaže nebo anonymizuje ve stejné transakci; odložené kontroly vidí konečný stav, takže pořadí zůstává funkční. Samostatné perzistentní odstranění identity bez dokončení cancellation již nelze commitnout.

Triggery identit uzamykají rodičovský účet FOR UPDATE a používají jeho schéma z TG_TABLE_SCHEMA, aby kontroly nečetly jiné schéma při izolovaných upgrade testech. Aplikační pořadí admin_control -> účet -> další řádky zůstává. Souběžná změna stejného účtu čeká na zámek; unikátní omezení a commit kontrola odmítnou nepřípustný výsledek. Nezávisí to na klientské version nebo počtu načteném před transakcí. Deadlock/serialization error se nesmí zaměnit za úspěch operace. DDL a oprávnění vlastníka schopného vypnout triggery jsou mimo aplikační bezpečnostní hranici.

## Protokolová infrastruktura

`Purpose` má pouze LOGIN, REAUTH_LIFE a vázané citlivé větve byly odstraněny. JSON LINK/UNLINK/REAUTHENTICATE vrací 400 INVALID_REQUEST už při parsování DTO, před poskytovatelem i databází; proto stejně funguje i s vypnutými poskytovateli. Načtený starý nebo podvržený citlivý kontext se odmítne EXTERNAL_STATE_INVALID před výměnou kódu. Žádné endpointy link/unlink/LOCAL-add/reauth nejsou registrované; autentizovaný požadavek vrací 404 (neautentizovaný může být zastaven 401, požadavek bez CSRF 403).

V10 ponechává historické V8 CHECK omezení a přidává užší: flow pouze LOGIN s NULL account/session, grant pouze MOBILE/LOGIN. Nevydávají se REAUTH granty. Tabulka pro budoucí MOBILE zůstává s 60sekundovým limitem a PKCE, bez implementace vydání/výměny. LOGIN state/nonce/korelace, Google PKCE, Apple form_post, knihovní ověření claims a šifrované Apple prostředky se zachovávají. Stávající cookie/CSRF a Bearer pravidla se nemění.

Přesný guard historie nově přijímá V10 jen s názvem V10__single_immutable_identity.sql. Neznámý název V10 nebo V11 stále blokuje adaptér účastí a zrušení účtu. Future-migration fixture je přesunuta na V11.

## Testy a spuštění

Verify s JDK 21: BUILD SUCCESS, 274 testů, bez failures/errors/skipped. Předchozí běh jednorázově selhal v nesouvisejícím asynchronním AuditExportHttpTest; celý opakovaný běh prošel beze změny exportu. Viz docs/testing.md.

Unit/HTTP testy ověřují jediné parsovatelné LOGIN, odmítnutí odstraněných účelů, podvržený starý kontext bez síťové výměny a nepřítomnost identitních endpointů i po autentizaci. Běžné verify běží bez databáze a skutečných poskytovatelů.

Nový SingleIdentityIT připravuje registraci všech tří typů, nulovou ACTIVE kardinalitu, dokončení pending účtu, druhou identitu, změnu všech identifikačních sloupců, DELETE+INSERT včetně stejného UUID a stavů DISABLED/PENDING_EMAIL, zákaz samostatného DELETE, LOCAL změnu hesla a zrušení, anonymizaci, souběhy druhé identity/výměny, zákaz REAUTH grantů a V9->V10 preflight pro více identit, chybějící ACTIVE identitu, anonymizovaný účet s identitou a staré flow/granty. Kontroluje SQLSTATE v původní SQLException, nikoli náhodný Spring obal. Zachování konfliktních dat a rollback migrace jsou součástí IT.

Existující ExternalWebLoginIT nad V10 dále ověřuje skutečné dokončení registrace GOOGLE/APPLE s mockovaným verifierem, souběh, session/audit rollback a Apple ciphertext. Přidán negativní test neznámého názvu V10. Starý cancellation fixture nyní vytváří ACTIVE účet a LOCAL identitu v jedné transakci, ne neplatný stav mezi autocommit příkazy. Staré REAUTH cancellation fixture bylo nahrazeno budoucím MOBILE grantem; kontrola úklidu grantů i anonymizace zůstává.

PostgreSQL testy spouští výhradně uživatel ve svém PowerShellu podle docs/testing.md:

```powershell
.\mvnw.cmd -Pit verify
```

Pouze rallye_test a role rallye_test, přes TestDatabaseInitializer. Nikdy rallye_dev/rallye. V10 ani preflight Codex proti DB nespouští. Úspěch bez DB potvrzuje kompilaci IT, nikoli chování triggerů.

## Postman

Přibyla složka **18 Single immutable identity 3c.3a - LOCAL negative flows**: tři POST na GOOGLE/requests s účely LINK, UNLINK a REAUTHENTICATE. Každý očekává 400 INVALID_REQUEST (celkem šest asercí). Spustit lokálně v cookie režimu, cookie jar zapnutý, baseUrl localhost; collection pre-request získá CSRF. Poskytovatelé mají zůstat vypnutí a není potřeba přihlášení. Tyto požadavky nemění účty ani DB.

Složka 16 se svými šesti asercemi zůstává lokálně použitelná. Pozitivní složka 17 a skutečné Google/Apple přihlášení stále vyžadují schválené HTTPS prostředí. Postman neověřuje DB invariant přímým SQL; to je úloha PostgreSQL IT. Externí cancellation potvrzení se nyní nepřidává.

## Omezení a další krok

Uživatel po opravě úklidu potvrdil úspěšný PostgreSQL běh; V10, rollback a souběhové invarianty jsou tím integračně ověřené v testovaném prostředí. Případná existující konfliktní data se řeší samostatným rozhodnutím, ne repair/clean či automatickým mazáním. Trvalý ruční odchod starých nepoužívaných kontextů není v migraci skrytě povolen. Po akceptaci 3c.3a následuje 3c.3b: vlastní externí zrušení na základě relace a explicitního potvrzení podle D10. Apple revokace zůstává 3c.5 a vyžaduje vyřešit souběh nové autorizace se starou čekající revokací.


## Přehled souborů této podetapy

Část dokumentačních souborů už obsahovala předchozí schválenou analytickou změnu. Ta byla zachována a doplněna o skutečný stav 3c.3a. Rozpracovaná nová specifikace 0.8.0 patří předchozí dokumentační změně; 3c.3a ji dále neměnila.

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
docs/single-identity-3c3a.md
postman/Rallye-Auth-2A.postman_collection.json
src/main/java/cz/mh/rallye/rallye_backend/identity/application/ExternalFlowService.java
src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/PreRaceAccountParticipationFacts.java
src/main/resources/db/migration/V10__single_immutable_identity.sql
src/test/java/cz/mh/rallye/rallye_backend/identity/AccountCancellationIT.java
src/test/java/cz/mh/rallye/rallye_backend/identity/AccountCancellationServiceIT.java
src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalAuthHttpTest.java
src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalAuthIT.java
src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalFlowTest.java
src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalOidcTest.java
src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalWebLoginIT.java
src/test/java/cz/mh/rallye/rallye_backend/identity/PreRaceAccountParticipationFactsTest.java
src/test/java/cz/mh/rallye/rallye_backend/identity/SingleIdentityIT.java
src/test/java/cz/mh/rallye/rallye_backend/identity/IdentityIT.java
src/test/java/cz/mh/rallye/rallye_backend/identity/LocalMailboxIT.java
```

Následná kompilace po úpravě starých cancellation fixture: Maven test-compile -DskipTests, BUILD SUCCESS; tento příkaz nespouští testy. Předchozí úplný verify 274/274 byl skutečně proveden, PostgreSQL běh zůstává uživatelským krokem.


### Oprava testovacího úklidu po aplikaci V10

Uživatelský PostgreSQL běh skončil 173 testy, 0 failures a 12 errors. Podrobné Failsafe reporty ukazují 11 chyb v IdentityIT.cleanupOnlyOwnFixtures a jednu v LocalMailboxIT finally: DELETE identity v autocommitu dokončil transakci před smazáním účtu. Trigger check_account_identity správně odmítl tento stav (identity_delete_requires_cancellation, SQLSTATE 23514). Nešlo o důvod ke změně produkčního triggeru.

Oba testovací úklidy nyní v jedné TransactionTemplate transakci vyberou a zamknou účet podle přesného UUID e-mailu vytvořeného testem, zapamatují jeho interní UUID a cíleně smažou sessions, action tokeny, identitu a nakonec účet. Při commitu odložený trigger zjistí neexistujícího rodiče a dovolí legitimní odstranění. Neočekávaná FK závislost způsobí rollback celého úklidu; nevypínají se triggery ani se nemažou cizí závislosti. Auditní historie zůstává podle dosavadního modelu bez FK. Starší zbytky předchozího neúspěšného běhu se automaticky plošně nemažou.

Revize všech 13 PostgreSQL IT tříd našla tento autocommit vzor pouze ve zmíněných dvou. Deset ostatních datových tříd uklízí vlastní náhodné schéma s existujícími kontrolami DB/role/jména; RallyeBackendApplicationIT nevytváří vlastní účtová data. Přímé DELETE v SingleIdentityIT jsou buď záměrné negativní testy, nebo součást celé cancellation transakce; perzistentní cancellation regrese už používají jednu transakci.

SingleIdentityIT nově obsahuje 17 scénářů. Dvě nové regrese ověřují cílený úplný úklid s tokenem a session při zachování jiného účtu/identity a rollback odstranění účtu i identity při vložené chybě. Stávající test zákazu samostatného DELETE (SQLSTATE 23514) a testy DELETE+INSERT zůstávají zachovány.

Po opravě Codex spustil verify na JDK 21: **BUILD SUCCESS, 274 testů, 0 failures/errors/skipped**, včetně kompilace všech IT. PostgreSQL testy Codex nespouštěl; opakované ověření `.\mvnw.cmd -Pit verify` proti rallye_test po opravě uživatel potvrdil jako BUILD SUCCESS. Počet testů opakovaného běhu nebyl uveden. Produkční Java kód ani V10 se v této opravě nezměnily a V11 nevznikla.


Závěrečné integrační ověření opravy úklidu: uživatel potvrdil `.\mvnw.cmd -Pit verify` proti `rallye_test` s výsledkem **BUILD SUCCESS**. Jde o uživatelem potvrzený běh, nikoli běh Codexu; celkový počet testů nového běhu nebyl sdělen. Automatické ověření 3c.3a je dokončené. Uživatel následně potvrdil také manuální Postman složku 18: **6/6 Passed**. Skuteční poskytovatelé ani pozitivní složka 17 tím nejsou ověřeni. 3c.3b není zahájena; V10 se po aplikaci neměnila a V11 nevznikla.
