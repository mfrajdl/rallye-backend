# 3b.4b – transakční služba a API rušení účtů

Implementace navazuje na V6 a [perzistentní základ 3b.4a](account-cancellation-3b4a.md).
Samotná 3b.4b nevytvářela V7: stávající audit dovoloval nové typy událostí.
Následná 3b.5 přidala V7 pro auditní metadata a retenci; viz [audit-3b5.md](audit-3b5.md).
Migrace V1–V6 se nepřepisují a konfigurace databází zůstává zachovaná.
Etapa 3b.4b i celá 3b.4 jsou dokončeny. Uživatel potvrdil závěrečné
`mvnw.cmd verify` (217 testů), `mvnw.cmd -Pit verify` – BUILD SUCCESS ve svém
prostředí a všechny plánované ruční scénáře. Review neodhalilo problém
vyžadující opravu. Viz [review-3b4b.md](review-3b4b.md).

## API

| Operace | Požadavek | Úspěch |
| --- | --- | --- |
| Vlastní zrušení | `POST /api/v1/auth/account-cancellations`, JSON `{"currentPassword":"<aktuální heslo>"}` | 204 bez těla, odstranění session cookie |
| Zrušení jiného účtu ADMIN | `POST /api/v1/admin/users/{accountId}/cancellations`, bez těla | 204 bez těla |

Obě cesty vyžadují autentizaci a `Idempotency-Key` jako UUID. Pro web platí
session cookie a CSRF získané přes `/api/v1/auth/csrf`; mobilní Bearer používá
stávající bezpečnostní mechanismus. ADMIN cesta má navíc filtr ADMIN, ale služba
znovu ověřuje aktuální účet, relaci a roli pod databázovým zámkem.

Vlastní LOCAL zrušení vyžaduje aktuální heslo přes `CancellationIdentityCheck`.
Aktuální implementace `LocalCancellationIdentityCheck` ověřuje hash hesla;
bez místního hesla bezpečně odmítne. Budoucí externí reautentizace musí dodat
skutečné ověření za tímto rozhraním, nikoli obejít kontrolu. OAuth se nepřidává.

Pro obě operace se používají samostatné namespace existujícího `LoginThrottle`:
`account-cancellation` a `admin-account-cancellation`. Každý má současné limity
10 pokusů na účet a 50 na zdroj za 15 minut. Počítají se i neúspěšné pokusy.
Jde o stávající omezenou evidenci v paměti jednoho procesu; více instancí
aplikace nesdílí počítadla. Heslo není součástí klíče ani evidence.

| HTTP | Kód / význam |
| --- | --- |
| 400 | `INVALID_REQUEST`: chybějící/neplatný klíč nebo JSON |
| 401 | `INVALID_CREDENTIALS`: chybné současné heslo nebo již neplatná relace; bezpečnostní filtr při chybějící autentizaci vrací `UNAUTHENTICATED` |
| 403 | `SELF_ACCOUNT_CANCELLATION_FORBIDDEN`: ADMIN musí svůj účet rušit vlastní cestou; `ADMIN_REQUIRED` při změně role během požadavku; filtr/CSRF používá stávající `ACCESS_DENIED` |
| 404 | `ACCOUNT_NOT_FOUND`: nový klíč pro neexistující nebo již anonymizovaný účet |
| 409 | `LAST_ADMIN_REQUIRED`, `ACCOUNT_PARTICIPATION_RUNNING`, `IDEMPOTENCY_CONFLICT` |
| 429 | `TOO_MANY_ATTEMPTS`, `Retry-After: 900` |
| 503 | `PARTICIPATION_CHECK_UNAVAILABLE` |

## Transakce a souběhy

`AccountCancellationService` obsluhuje obě cesty jednou transakcí:

1. Zamkne `admin_control` a aktuální účet aktéra, zkontroluje relaci/oprávnění.
2. Pro vlastní cestu ověří heslo; pro ADMIN zkontroluje replay před hledáním cíle.
3. Zamkne cíl, ověří jeho stav, posledního aktivního ADMIN a fakta o účastech.
4. Ukončí čekající převody na obou stranách, odpojí vlastní posádky a zvýší jejich verze.
5. Odstraní identity, relace, účelové tokeny a role; vyčistí související idempotenci včetně jiných aktérů a expirovaných řádků.
6. Bez historie účet fyzicky odstraní, s historií anonymizuje, zapíše audit a případný ADMIN replay.

Audit obsahuje UUID aktéra/cíle a výsledek v typu `ACCOUNT_DELETED` nebo
`ACCOUNT_ANONYMIZED`; neobsahuje e-mail, heslo ani token. Odpojení kapitánů
se eviduje jako `CREW_CAPTAIN_CHANGED`, platné zrušené převody jako
`CREW_TRANSFER_CANCELLED / ACCOUNT_CANCELLED`, odebraná role jako `ADMIN_REVOKED`.
Vypršelé převody zachovají existující způsob ukončení EXPIRED.
Jakékoli selhání auditu nebo úklidu vrátí zpět i předchozí změny.
Fotografie ani historické převody a dosavadní audit se nemažou.

`AccountStore.lockAccount` nyní vždy dodržuje pořadí control → účet → token/relace,
také při přihlášení, obnově/změně hesla, aktivaci či autentizaci. Registrace
před zápisem získá stejný control zámek. Tím se odstraní opačné pořadí vůči
triggerům ADMIN a rušení. Nalezení účtu podle e-mailu před získáním zámku může
být zastaralé: login při následném chybějícím účtu vrátí jednotnou chybu,
žádosti o reset/ověření bezpečně skončí bez vydání tokenu.

Převody a administrátorské kapitánství již tento společný zámek používají.
Souběžná operace buď doběhne před zrušením, nebo po něm uvidí nový stav.
Žádná dřívější relace rušeného UUID se nesmí obnovit. Nová registrace stejného
e-mailu vytvoří nové UUID bez původních rolí, vlastnictví či historie.

## Účasti: bezpečně omezený adaptér

`AccountParticipationFacts` vrací historickou a běžící účast nezávisle na aktuálním
vlastnictví posádky. Protože závody zatím neexistují, `PreRaceAccountParticipationFacts`
dovoluje `(false,false)` pouze při přesné posloupnosti úspěšných SQL migrací
V1–V6 nebo přesné posloupnosti V1–V7 s výslovně schváleným skriptem
V7__audit_export_and_retention.sql v aktuálním schématu. Neznámá budoucí,
chybějící či neúspěšná migrace, nepodporovaný typ
nebo nedostupná Flyway historie vede k 503 před měnícími operacemi.
Nesmí se jen zvýšit horní verze: budoucí závodní schéma vyžaduje skutečný adaptér.

V podporovaném předzávodním schématu V1–V7 tedy zrušení vede k fyzickému odstranění.
Anonymizaci a blokaci běžící účasti testy služby simulují pouze testovacím adaptérem;
nejde o implementaci závodních účastí.

## Idempotence a soukromí

ADMIN využívá stávající actor/key namespace a 24hodinovou platnost:
`ADMIN_ACCOUNT_CANCEL`, request `[targetUuid]`, result `{id,outcome}`.
Metadata obsahují aktéra a cíl. Neobsahuje heslo, e-mail ani otevřený token.
Stejný klíč a cíl vrátí 204 bez nového auditu i po smazání cíle; jiný cíl/operace
vrátí 409. Nový klíč pro smazaný cíl vrátí 404.

Vlastní cesta kontroluje konflikt se stávajícím namespace, ale po úspěchu
neukládá replay: všechny její relace a staré osobní záznamy jsou odstraněny.
Opakování s původními credentials vrátí 401. Není zaváděno šifrování ani nové klíče.
Při pozdějším rušení aktéra či souvisejícího účtu se jeho replay záznamy mohou
odstranit; ochrana osobních údajů má přednost. Neznámý legacy formát úklid
nadále zastaví a transakci vrátí zpět podle 3b.4a.

## Ruční Postman ověření

Importujte aktualizovanou kolekci `postman/Rallye-Auth-2A.postman_collection.json`.
Složku **13 — Account cancellation 3b.4b** spouštějte jednotlivě, nikoli jako celek.
Úspěšné požadavky ruší účty; používejte pouze vyhrazené testovací účty.
Cookie jar, localhost allowlist a automatické získávání CSRF zůstávají stejné.

1. Založte/aktivujte a přihlaste testovací LOCAL účet pomocí stávajících složek.
   Ověřte `/auth/me`. Nastavte `cancellationPassword` na jeho současné heslo.
2. Ověřte chybné heslo 401 a chybějící klíč 400. Volitelný test posledního
   ADMIN 409 provádějte pouze v odpovídajícím přihlášení; poté se přepněte zpět.
3. Vlastní zrušení má vrátit 204 a odstranit cookie. Následující požadavek
   s původními credentials musí vrátit 401. Heslo proměnná po úspěchu vymaže.
4. Přihlaste ADMIN. Nastavte `adminAccountId` a odlišný vyhrazený cíl
   `accountCancellationTargetId`. Ověřte zákaz vlastní ADMIN cesty 403.
5. Zrušte cíl: 204. Klíč `adminCancellationKey` i úspěšný cíl
   `accountCancellationOriginalTargetId` ponechte pro replay 204.
6. Do `accountCancellationConflictTargetId` zadejte jiné UUID: stejný klíč
   musí vrátit 409. Požadavek na smazaný cíl s novým klíčem vrátí 404.
7. V auditu ověřte právě jednu výslednou událost a související změny posádek/převodů.
   Opakujte pro PENDING_EMAIL, DISABLED a účet s více relacemi a posádkami/fotografií.
8. Pro další samostatné rušení vymažte `selfCancellationKey` nebo
   `adminCancellationKey` a původní cíl; skript vytvoří nový UUID.

Proměnné hesel a tokenů nikdy neexportujte do Gitu. Automatické scénáře ověřují
také souběhy a rollback, které se v Postmanu ručně spolehlivě nevyvolávají.

## Ověření

Unit a HTTP testy běží bez databáze. `AccountCancellationServiceIT` používá
TestDatabaseInitializer, vlastní schéma a dočasný adresář fotografií.
Připravuje 21 PostgreSQL scénářů včetně deterministického odstranění účtu mezi
vyhledáním a zámkem, souběhů administrátorů/převodů/registrace, rollbacku,
skutečného Bearer HTTP toku a neznámé/nedostupné historie migrací.
Žádná kontrola testovací DB nebyla oslabena. Testy historie účasti nepoužívají
produkční závodní model; injektují testovací fakta za stejným rozhraním.

Spuštění PostgreSQL testů provádí uživatel: `.\mvnw.cmd -Pit verify`.
Uživatel potvrdil BUILD SUCCESS proti `rallye_test`; Failsafe report uvádí
112 integračních testů, 0 failures, 0 errors a 0 skipped, včetně 21 nových
scénářů 3b.4b. Jde o uživatelský běh, nikoli běh Codexu.
Uživatel potvrdil úspěšné ruční Postman ověření vlastní i ADMIN cesty,
zneplatnění session, opětovné registrace, replay/404, zákaz vlastní ADMIN
cesty, ochranu posledního ADMIN a zachování/převzetí osiřelé posádky s verzí.
Codex spustil `mvnw.cmd verify` s JDK 21: BUILD SUCCESS, 211 testů bez DB,
žádné chyby ani přeskočené testy. Nové IT se zkompilovaly. Kolekce prošla
JSON parsery Node.js/PowerShellu, kontrolou struktury v2.1 a syntaxe všech
147 JavaScript skriptů. Obsahuje 11 nových požadavků, původní obsah je zachován.

### Diagnostika ruční chyby 500

Příčina dříve hlášené 500 nebyla doložena runtime stack trace; při následném
uživatelem potvrzeném ručním ověření uvedené scénáře prošly.
Aktuální produkční zdroje obsahují `INTERNAL_ERROR` v obecném handleru
`ApiErrors.unexpected`, který již loguje `Unexpected API failure` s bezpečnou
kopií stack trace. Služba rušení účtu sama explicitně nevyhazuje AuthFailure 500.
Selhání kontroly účastí se překládá na 503, nikoli 500. Neočekávané chyby
autentizace před controllerem se rovněž překládají na 503.

Byly opraveny skutečné mezery v diagnostice: aplikační AuthFailure se statusem
5xx nyní handler i autentizační filtr logují jako `API server failure`;
filtr loguje také neočekávané výjimky. Překlad selhání kontroly účastí zachovává
původní příčinu. Log obsahuje pouze typy výjimek, SQLSTATE, stack frames a vazby
cause/suppressed/next SQL exception. Původní zprávy výjimek se nelogují, protože
mohou obsahovat SQL parametry, e-mail, heslo či token. Klientský kontrakt se nemění.
Samotné nalezení těchto mezer nedokazuje příčinu konkrétní ruční chyby 500.

Po překladu a restartu backendu zopakujte ruční požadavek. Pokud log není vidět,
ověřte, že IntelliJ zobrazuje ERROR výstup a že konfigurace nevypíná logger;
pro cílené povolení lze do VM options přidat
`-Dlogging.level.cz.mh.rallye.rallye_backend.identity.api=ERROR`.
Z dalšího pokusu stačí sdílet sanitizovaný blok `Unexpected API failure` nebo
`API server failure`, včetně typů příčin a případných SQLSTATE. Není třeba
sdílet tělo požadavku, cookies, Authorization ani heslo.

Po diagnostické úpravě Codex provedl `mvnw.cmd verify`: BUILD SUCCESS,
217 testů, 0 failures/errors/skipped. Regresní HTTP testy ověřují neočekávanou
transakční/SQL chybu, explicitní aplikační 500, chyby před controllerem a
kontrolu účastí 503 včetně nepřítomnosti citlivých údajů v logu a odpovědi.
Jde o testy se simulovanými výjimkami, nikoli reprodukci uživatelovy runtime chyby.
