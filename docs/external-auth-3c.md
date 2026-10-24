# Etapa 3c – externí přihlášení s jedinou neměnnou identitou

## Aktuální závazný základ a evidence rozhodnutí

Závazná je [specifikace 0.8.0](Zavody_specifikace_v0.8.0.md). Vydání 0.7.0 zůstává historicky beze změny. Původní dokumentační revize byla následována schválenou implementací 3c.3a; aktuální stav V10 a uživatelem potvrzené PG ověření: [single-identity-3c3a.md](single-identity-3c3a.md). Návrhy 3c.3b/c a 3c.4/5 nejsou implementované. 3c.1 a 3c.2 byly dokončeny podle tehdejšího rozsahu: verify 271 testů, uživatelem potvrzený PostgreSQL BUILD SUCCESS a lokální Postman 16 (6/6). Skutečné Google/Apple přihlášení a pozitivní Postman 17 jsou neověřené.

| Rozhodnutí | Aktuální platnost |
| --- | --- |
| D01 | E-mail ověřený jen validovaným údajem poskytovatele, jinak vlastní ověření; žádné propojení podle adresy. |
| D02 | Doplnění profilu bez plné relace; vlastní ověření doplněného e-mailu. Dokončovací kontext 15 minut, aktivace 24 hodin. |
| D03 | **Nahrazeno D10.** Původní externí čerstvé ověření a 5minutová oprávnění nejsou součástí nového plánu. |
| D04, D05 | **Nahrazeny D09.** Link/unlink, dodatečný LOCAL a jedna identita každého typu již neplatí. |
| D06 | Nezbytný šifrovaný Apple prostředek a revokace při zrušení včetně administrátorského, bezpečné retry bez obnovení účtu. |
| D07 | Skutečná Apple integrace vyžaduje schválené HTTPS prostředí; nyní se nezřizuje. |
| D08 | Mobilní systémový browser, state 10 minut, handoff 60 sekund, PKCE S256, Bearer nikdy v URL. |
| D09 | Každý aktivní účet má právě jednu neměnnou LOCAL/GOOGLE/APPLE identitu. Typ ani konkrétní identitu nelze vyměnit, přidat další, odpojit ani externímu účtu přidat heslo. Externí klíč je (provider, issuer, subject). |
| D10 | LOCAL vlastní zrušení: platná relace a opětovné ověření hesla. GOOGLE/APPLE vlastní zrušení: platná relace a samostatné výslovné potvrzení, bez auth_time/max_age či nového provider loginu. Potvrzení není nové ověření identity, jde o schválený bezpečnostní kompromis. |

Změna jména nebo kontaktního e-mailu nemění identitu. U LOCAL lze nadále měnit/resetovat heslo; tím se nemění identita. API změny e-mailu se nyní nepřidává. ADMIN rušení nadále ověřuje aktuálního aktéra, zakazuje sebe přes ADMIN cestu a chrání posledního aktivního ADMIN; nepřidává se nový požadavek čerstvého důkazu.

## Dopady na existující implementaci 3c.1 a 3c.2

| Oblast | Skutečný stav | Potřebná budoucí změna |
| --- | --- | --- |
| Registrace LOCAL | AccountStore.create vytváří účet i jednu LOCAL identitu v transakci. | Zachovat, doplnit databázový invariant. |
| Externí registrace | ExternalLoginService.complete vytváří samostatný účet s jedinou externí identitou, bez LOCAL. Kolize normalizovaného e-mailu má generické 202 bez propojení. | Zachovat; odstranit z návodů doporučení budoucího explicitního linku. Kolize nezakládá další účet pod stejnou adresou, unikátnost e-mailu zůstává. |
| Další externí login | Vyhledání podle provider/issuer/subject, stávající user_session a transakční LOGIN_SUCCEEDED; ACTIVE kontrola, uložené jméno se nepřepisuje. | Zachovat. Nový subject není pokračováním původního účtu. |
| V1/V8 | V1 má UNIQUE(provider,issuer,subject) a částečnou LOCAL unikátnost. V8 UNIQUE(account_id,provider) dovoluje různé typy u jednoho účtu. | V10 navrhne UNIQUE(account_id), kontrolu existence a neměnnosti. V1–V9 nikdy nepřepisovat. |
| V9 | Omezená registrace a šifrované Apple prostředky. Více řádků apple_revocation_credential neznamená více identit. | Zachovat, revokační životní cyklus dopracovat v 3c.5. |
| Účely toku | ExternalFlowService.Purpose a CHECK ve V8 dovolují LOGIN/LINK/REAUTHENTICATE; start umí kontexty vytvořit, citlivé callbacky jsou blokované EXTERNAL_FRESH_AUTH_UNAVAILABLE. | LINK/REAUTHENTICATE jsou nyní nepotřebné. Explicitně odmítnout jejich start, odstranit příslušné větve/API návrhy a následně zúžit DB CHECK na LOGIN. Nestačí spoléhat na odmítnutí callbacku. |
| external_auth_grant | V8 připravila REAUTH/MOBILE a 5minutový REAUTH; obchodní vydávání REAUTH není implementované. | REAUTH nevydávat, odstranit nepotřebnou podporu. MOBILE 60 sekund zachovat pro 3c.4. Tabulku ani datové prostředky nyní nemažeme. |
| Identitní API | Nejsou implementované link/unlink/LOCAL-add endpointy. | Nezavádět je. Případné zobrazení vlastního typu může být pouze čtecí profilový údaj, není správou způsobů a nyní není nový funkční požadavek. |
| Rušení | AccountCancellationService má transakční orchestraci, LocalCancellationIdentityCheck ověřuje heslo; identita se maže před DELETE/anonymizací, relace/granty/kontexty se uklízejí. | Přidat externí výslovné potvrzení podle typu identity načteného z DB. Klient nesmí zvolením typu obejít LOCAL heslo. Zachovat audit a ochrany historie/ADMIN. |
| CommandStore | Stávající idempotence a úklid při rušení bez tajemství. | Nepřidávat link/unlink operace. Upravit otisk cancellation jen podle schváleného netajného kontraktu, nikdy heslo/token nebo jejich náhradní hash. |
| Flyway guard | PreRaceAccountParticipationFacts má přesný allowlist V1–V9 a test odmítající neznámou V10. | Při implementaci konkrétní V10 přidat přesný název a posunout unknown-version fixture; nepovolit libovolné vyšší verze. |

### Data a read-only preflight před budoucí migrací

Kód běžných registrací je v souladu s jedinou identitou, ale to nedokazuje skutečný stav všech databází. Žádné DB připojení ani diagnostické SQL nebylo v této revizi spuštěno. Před migrací prověřit účty s více identitami, ACTIVE bez identity, nesoulad LOCAL/password_hash a stavů účtu a existující LINK/REAUTH kontexty a granty. Konflikt zastaví migraci s diagnostikou bez osobních údajů; nikdy automaticky nevybírat vítěznou identitu, neslučovat ani nemazat účty. Způsob řešení případných skutečných konfliktů musí schválit uživatel.

## Návrh V10 a následná implementace

V10 je nyní připravena v repozitáři; skutečné chování je popsáno v single-identity-3c3a.md. Níže je původní zdůvodnění návrhu, nikoli potvrzení aplikace na databázi.

1. Přidat UNIQUE(user_identity.account_id). Tím se při souběhu vynutí **nejvýše jedna**, ne existence alespoň jedné. Globální unikátnost externího klíče zůstává.
2. Doporučený odložený constraint trigger kontroluje **právě jednu použitelnou identitu u ACTIVE** na konci transakce. Vyvolat jej na změny user_account i INSERT/UPDATE/DELETE user_identity; kontrolovat oba účty při změně vazby. Stejná transakce může nejprve založit účet a potom identitu nebo nejprve identitu odstranit a účet anonymizovat/smazat. ANONYMIZED a neexistující účet jsou terminální výjimky bez identity.
3. Pro souběh kontrol uzamykat rodičovský účet konzistentně s pořadím admin_control -> účet -> další řádky. UNIQUE řeší dva INSERT; constraint trigger a zámky řeší nulovou kardinalitu při změnách stavu. Testovat proti skutečnému PostgreSQL, ne pouze mockům.
4. Neměnnost nelze zajistit samotným UNIQUE. Doporučené DB triggery odmítnou UPDATE id/account_id/provider/issuer/subject existující identity. LOCAL password_hash a profil účtu zůstávají měnitelné. Samostatný odložený DELETE constraint trigger musí povolit odstranění identity pouze tehdy, když při commitu rodič již neexistuje nebo je ANONYMIZED bez přihlašovacích prostředků. Odmítne tak i DELETE+INSERT náhradu u přeživšího účtu (včetně stejného UUID), nejen UPDATE. Návrat ANONYMIZED do přihlásitelného stavu zakázat. Tím není potřeba ukládat duplicitní historický externí subject. Přesné SQL, pořadí triggerů a chování stávajících cancellation transakcí ověřit v implementaci a PG souběhových testech; nyní se nic nemění.
5. PENDING_EMAIL/DISABLED nesmějí být cestou k výměně identity. Doporučené omezení počtu a neměnnosti platí i pro ně; přesná kontrola právě jedné je závazná pro ACTIVE, u ostatních stavů je nutné zachovat registrační a cancellation transakce. Nikdy nezavádět okamžitý DELETE trigger, který rozbije stávající anonymizaci.
6. V10 zúží podporované účely na LOGIN a MOBILE. Pokud preflight najde dosud živé LINK/REAUTH údaje, migrace je nesmí tiše odstraňovat; předložit cílené rozhodnutí o jejich zneplatnění. Nezvyšovat platnost stávajících stavů.

Jde o navržené technické řešení, ne aplikovanou ochranu. Administrátor databáze schopný vypnout triggery je mimo aplikační bezpečnostní hranici; aplikace nemá dostat taková oprávnění.

## Přepracovaný plán 3c.3

### 3c.3a – invariant jediné neměnné identity

- Read-only preflight, nová V10 a přesný migration guard; odstranit podporu zahajování LINK/REAUTHENTICATE. LOGIN a Apple form_post/state/nonce zachovat.
- Žádné link/unlink/LOCAL-add ani vícezpůsobové API, čerstvé granty či odvolávání relací kvůli již zakázaným operacím.
- Unit/HTTP: odmítnutí starých účelů bez kontaktování poskytovatele, normální registrace/login, změna/reset pouze LOCAL, změna jména nemění identitu.
- PG: upgrade V9 -> V10 beze změny účtů a relací; fail preflight bez změny konfliktních dat; dva souběžné INSERT různých providerů; ACTIVE bez identity; změna provider/subject/vazby i DELETE+INSERT náhrada; LOCAL password update; atomická registrace, DELETE i anonymizace; rollback a neznámá budoucí migrace.
- Hotovo až po verify a uživatelem potvrzeném rallye_test běhu. Historické úspěchy V8/V9 nejsou ověřením V10.

### 3c.3b – potvrzení vlastního zrušení podle D10

- Rozšířit existující POST /api/v1/auth/account-cancellations, nikoli zakládat paralelní službu. Návrh DTO: dosavadní currentPassword pro LOCAL; samostatné explicitní boolean confirmed=true pro externí účet, výchozí false. Přesný název DTO/error kód uzavřít při implementaci, žádné nové API zatím neexistuje.
- Typ identity zjistit z DB v téže transakci. Platná relace je povinná v obou větvích, LOCAL heslo se vždy znovu ověří; externí potvrzení se nesmí vydávat za reautentizaci. Bez true nedělat žádnou změnu. Pro web zachovat CSRF a pro mobil stávající Bearer pravidla.
- Potvrzení není přenosný token, nevytváří grant ani prodloužení relace. Nové přihlášení u poskytovatele ani auth_time/max_age se nevyžaduje. Samostatné potvrzovací UI musí vyjadřovat vědomý souhlas; backend vynucuje explicitní příznak, frontend nelze nahradit automatickým potvrzením na pozadí.
- Zachovat aktuální idempotenci cancellation, replay po aktuální autorizaci, rollback, úklid CommandStore, relací, převodů a vlastnictví, audit ACCOUNT_CANCELLED a invariant posledního ADMIN. Nové auditní kategorie přidat jen s doloženou potřebou; žádné REAUTHENTICATION_SUCCEEDED při pouhém potvrzení.
- HTTP/unit/PG: neplatná relace, chybné/chybějící LOCAL heslo, externí potvrzení false/chybí/true, nemožnost podvrhnout typ, CSRF, cancellation souběhy a replay, audit failure rollback, osiřelé posádky, anonymizace, stejný e-mail nového účtu bez práv.
- ADMIN endpoint nemění kontrakt ani nevyžaduje heslo cíle. Apple revokační enqueue/worker je teprve 3c.5; před jeho dokončením neoznačovat externí cancellation za kompletně produkčně připravenou.

### 3c.3c – regrese a dokumentace

- Upravit staré LINK/REAUTH testy z podpory kontextu na bezpečné odmítnutí; nevypínat jejich bezpečnostní smysl. Test 5min grantů pro správu identit již není požadavkem, MOBILE grant testy zůstávají.
- V 3c.3a byla přidána negativní složka 18; pozitivní cancellation požadavky zatím nepřidávat. V budoucnu doplnit lokální LOCAL cancellation scénáře, nepřidávat reauthentication/link/unlink/local-add. Stávající složky 16/17 zachovat, opravit případné popisky kolize odkazující na link. Externí potvrzení cancellation lze otestovat syntetickou fixture pouze v IT; manuálně vyžaduje skutečný externí účet a HTTPS, nikoli ruční SQL do dev.
- Verify bez DB, uživatelem spuštěné PostgreSQL testy, zvlášť skutečný browser/provider smoke. Nezřizovat infrastrukturu v této etapě bez nového zadání.

## Návaznosti 3c.4 a 3c.5

**3c.4 – mobilní přihlášení:** systémový browser, LOGIN, 60s jednorázový předávací kód s hash a PKCE S256, allowlist návratů a existující Bearer kontrakt. Žádné mobilní link/unlink nebo správa více metod. Navazuje na 3c.1/3c.2 a konsolidaci invariantů 3c.3a, nikoli na nyní zrušený čerstvý grant. Testy verifier/challenge/replay/expirace/souběh exchange, rollback session/auditu a cancellation mezi callbackem a exchange. Bez Bearer v URL; stávající 24h/30d beze změny. Skutečné app/universal links se ověřují samostatně.

**3c.5 – rušení účtů a Apple revokace:** navazuje na potvrzení D10 v 3c.3b. V lokální cancellation transakci enqueue trvalé chráněné revokační práce, uchovat pouze nezbytný prostředek a minimální vazby; síťové volání až po commitu. Lease/retry/crash recovery, koordinace instancí a bezpečné odstranění ciphertextu po doložené revokaci. Zachovat admin cancellation a anonymizaci. Revokace při unlink se neimplementuje, protože unlink není povolen. Souběh nové registrace stejné Apple identity s čekající revokací zůstává technický bod; zákaz změny identity v existujícím účtu jej sám neřeší. Žádná závislost na Apple reautentizaci, žádné auth_time/max_age pro cancellation.

## Otevřené otázky a doporučení

| Bod | Blokace | Varianty a doporučení |
| --- | --- | --- |
| Ztráta Google/Apple účtu | Neblokuje 3c.3a/b; blokuje případný slib obnovy přístupu jinou cestou. | Doporučujeme nejprve obnovu u poskytovatele. Bez obnovení nelze nynějším schváleným modelem vstoupit do Rallye účtu; nabídka nového účtu nezachraňuje vlastnictví/historii. Alternativní ověření/administrativní převod identity by vyžadovaly nové rozhodnutí, automaticky se nezavádějí. |
| Vlastní zrušení externího účtu bez auth_time | **Uzavřeno D10, není blokace.** | Platná autentizovaná relace a explicitní potvrzení. Apple reautentizace se nepožaduje. Kompromis: osoba s ovládnutou platnou relací může zrušit účet; CSRF a potvrzení tento fakt nemění. |
| Změna či zrušení subjectu u poskytovatele | Neblokuje bezpečné odmítnutí, blokuje případný automatický převod. | Identita zůstává neměnná; email ani nové tvrzení subjectu nepřevede účet. Doporučujeme fail closed a individuální analýzu doložených provider migrací; výjimka vyžaduje zvláštní schválení. Samotné ukončení identity u providera nyní automaticky neruší Rallye účet/relace; pravidla notifikací a průběžné revokace nejsou nově zaváděna. |
| Existující konfliktní data | Podmíněná blokace V10, pokud je preflight najde. | Zastavit s počty/technickými UUID, žádný automatický výběr či smazání. Reálné účty a DB nebyly nyní kontrolovány. |
| Stejná Apple identita nově registrovaná během retry staré revokace | Blokuje dokončení 3c.5 a produkční zapnutí této cesty. | Doporučená dočasná blokace nové autorizace do vyřízení staré revokace potřebuje minimální chráněnou korelaci a nové schválení konkrétní retence. Alternativa: providerem doložené bezpečné oddělení generací. Nyní neimplementovat. |

Původní otázky politiky relací při link/unlink a odloženého APPLE unlink jsou bezpředmětné. Není třeba je schvalovat; tyto operace se zakazují D09. Čerstvost Google/Apple již neblokuje 3c.3 ani vlastní cancellation. Google auth_time lze technicky získat, iat není jeho náhradou, ale nové chování jej nevyžaduje; žádný výzkum max_age není podmínkou zahájení.

## Aktuální doporučení

Schválená 3c.3a je implementována a uživatelem ověřena PostgreSQL během (BUILD SUCCESS). V1–V9 se nezměnily, V10 není Codexem aplikovaná na DB. Po úspěšném ověření a akceptaci pokračovat 3c.3b, zatím ji neimplementovat. Přesné testy a limity jsou v single-identity-3c3a.md.
