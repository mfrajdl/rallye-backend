# Etapa 3b.3a – interní základ převodů kapitána

Implementována je pouze migrace a interní životní cyklus. Nevznikají nové
REST endpointy, e-mailové šablony ani změny Postman kolekce. Platí specifikace
0.6.0, oddíl 2.2, a upřesnění zadání 3b.3a. Stávající API 2A–3b.2 zůstává beze změny.

## Datový model

Nová migrace `V4__crew_transfers.sql` přidává `crew_transfer`:

| Údaj | Význam |
| --- | --- |
| `id`, `crew_id` | UUID žádosti a posádky |
| `from_account_id`, `to_account_id` | Historická interní ID původního a cílového kapitána |
| `token_hash` | Unikátní SHA-256 digest; otevřený token se neukládá |
| `status` | PENDING, ACCEPTED, REJECTED, CANCELLED, EXPIRED |
| `created_at`, `expires_at` | UTC okamžiky s přesností na milisekundy; rozdíl přesně 168 hodin |
| `resolved_at`, `resolved_by`, `resolution_reason` | Okamžik, aktér a důvod ukončení; expirace nemá aktéra |

Částečný unikátní index dovoluje nejvýše jeden uložený PENDING na posádku.
Další indexy podporují historii posádky a hledání podle účastníků převodu.
CHECK omezení hlídají různé účastníky, formát hashe, stavy, životnost a konzistenci
údajů vyřízení. Přijetí/odmítnutí vyřizuje cílový účet. Aplikační kontrola navíc
ověřuje skutečné účty, oprávnění, relaci a vlastnictví.

Historická UUID úmyslně nemají FK na posádku ani účet: fyzické odstranění těchto
objektů nesmí smazat ani blokovat historii převodů. Nejde o oprávnění k mazání
posádky během živého převodu; příslušná ochrana patří do navazující orchestrace.
V1–V3 se nemění.

## Interní rozhraní a transakce

`CrewTransferStore` zajišťuje načtení, zámky, vložení a podmíněné vyřízení
PENDING. `CrewTransferLifecycle` vyžaduje již otevřenou transakci pomocí
`Propagation.MANDATORY`; bez ní selže před první databázovou operací.

| Operace | Vstupy | Výsledek |
| --- | --- | --- |
| `create` | Principal, ID posádky, ID příjemce, očekávaná crew.version, hash tokenu | PENDING a nezměněná verze posádky |
| `accept` | Principal příjemce, ID žádosti, hash předloženého tokenu | ACCEPTED, nový kapitán a aktuální verze + 1 |
| `reject` | Principal příjemce, ID žádosti, hash předloženého tokenu | REJECTED, nezměněná verze |
| `cancel` | Principal původního kapitána, ID žádosti | CANCELLED, nezměněná verze |

Jde o interní hodnoty, nikoli REST DTO; hash se nesmí serializovat do API ani
logovat. `CrewTransfer.toString()` jej neobsahuje. Generování otevřeného tokenu
a jeho hashování před voláním rozhraní zajistí až navazující vrstva.

Pořadí zámků odpovídá existujícím operacím: singleton `admin_control`, účet
aktéra, související účty (seřazená UUID), posádka, žádost. Aktér musí mít aktuálně
platnou databázovou relaci a aktivní ověřený účet. ADMIN zde nenahrazuje kapitána.
Příjemcem může být jen jiný existující aktivní účet s ověřeným e-mailem.

Platnost je `now < expires_at`; přesně v okamžiku expirace již žádost neplatí.
Bez automatického úklidu může zůstat expirovaný řádek uložen jako PENDING.
Nové založení jej pod zámkem uzavře jako EXPIRED a vloží nový řádek v téže
transakci. Selhání vložení obnoví původní stav rollbackem.

Běžné úpravy nejsou navázané na verzi žádosti: přijetí načte aktuální posádku
a zvýší její aktuální verzi jednou. Ukončená či expirovaná žádost se pro novou
operaci odmítá jako `409 TRANSFER_NOT_PENDING`. Další interní chyby zahrnují
`TRANSFER_ALREADY_PENDING`, `TRANSFER_FORBIDDEN`, `TRANSFER_OWNER_CHANGED`,
`CAPTAIN_UNAVAILABLE`, `CAPTAIN_UNCHANGED`, `CREW_VERSION_CONFLICT`,
`CREW_NOT_FOUND`, `TRANSFER_NOT_FOUND` a `INVALID_OR_EXPIRED_TOKEN`.
Jejich veřejný kontrakt a omezení zveřejňovaných informací dokončí REST vrstva.

## Hranice etapy a návaznosti

Interní operace zatím nejsou volány z controllerů. Audit převodů a evidence
idempotentních výsledků se v této části nepřidávají. Navazující aplikační služba
musí obalit interní operaci, audit a stávající CommandStore jedinou transakcí;
nesmí volat životní cyklus samostatně a auditovat až po commitu.

- **3b.3b:** REST založení, bezpečné generování tokenu, samostatný účel zprávy
  v SMTP i souborovém adaptéru a opakované zaslání. Nový token zneplatní původní,
  ale nezmění původní expires_at. Bez databázového outboxu. Limity 10 pokusů na
  účet a 50 na IP za 15 minut podle schváleného zadání.
- **3b.3c:** úplná orchestrace přijetí/odmítnutí/zrušení, audit a stávající
  idempotence (24 hodin, stejný klíč a vstupy vracejí původní výsledek, jiné vstupy
  409). Doplnit zákaz mazání při živé žádosti a její atomické zrušení při
  administrátorské změně/odebrání kapitána; nynější CrewService tyto vazby ještě
  nemá. Veřejné založení převodu nelze vydat samostatně bez těchto ochran.
- **3b.3d:** úplné koncové scénáře, Postman a dokumentace veřejného API.

Změna kapitána nesmí měnit závodní zařízení; závodní model zde ještě není.
Rušení účtů a rušení jejich čekajících převodů zůstává v 3b.4. Žádné další
produktové rozhodnutí nyní neblokuje interní základ.

## Ověření

Bez databáze: `CrewTransferLifecycleTest` (17 případů) a
`CrewTransferTransactionTest` (2 případy) ověřují pravidla, hraniční expiraci,
verze, oprávnění a povinnou transakci. Skutečný databázový rollback tyto mockové
testy samy neprokazují.

Codex provedl `.\mvnw.cmd verify` s JDK 21: BUILD SUCCESS, 137 úspěšných testů,
žádné chyby ani přeskočení. Integrační zdrojové soubory se zkompilovaly.

`CrewTransfersIT` přidává 9 PostgreSQL testů: aplikace migrace v izolovaném
schématu, omezení PENDING a konzistence řádků, přijetí po editaci, odmítnutí,
zrušení, zachování historie po smazání, expirace, neoprávněné pokusy, rollback
po skutečném SQL zápisu a dva souběžné scénáře. Při injekci selhání se kontroluje
konkrétní příčina i stav po ukončení transakce a následný úspěšný pokus.
Testy používají TestDatabaseInitializer, databázi i roli rallye_test a náhodné
vlastní schéma; úklid kontroluje jeho název i skutečné DB připojení.

PostgreSQL testy spouští uživatel podle `docs/testing.md`:

```powershell
.\mvnw.cmd -Pit verify
```

Uživatel potvrdil úspěšné `.\mvnw.cmd -Pit verify` proti rallye_test.
Dostupný Failsafe report obsahuje 48 testů, 0 errors, 0 failures a 0 skipped,
včetně 9 nových případů CrewTransfersIT. Jde o uživatelem potvrzený běh,
nikoli vlastní spuštění PostgreSQL testů Codexem. Část 3b.3a je dokončená
a ověřená; celá 3b.3 zůstává rozpracovaná.

## Navazující implementace

Část 3b.3b již přidává REST založení a resend, doručování a idempotenci.
Tento dokument popisuje původní hranici 3b.3a. Aktuální kontrakt a zbývající
rozsah 3b.3c uvádí [crew-transfers-3b3b.md](crew-transfers-3b3b.md).

## Návaznost 3b.3c

Rozhodovací REST API, audit a ochrany mazání/ADMIN změn nyní implementuje
[crew-transfers-3b3c.md](crew-transfers-3b3c.md). Původní popis hranice etapy
v tomto dokumentu je historický; uživatel nyní potvrdil úspěšné integrační ověření 3b.3c.
