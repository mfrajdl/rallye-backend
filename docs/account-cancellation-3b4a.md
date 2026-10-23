# 3b.4a – databázový základ rušení účtů

Implementovaný je pouze interní perzistentní základ. Nevzniká veřejné API ani
kompletní operace zrušení účtu. Specifikace 0.6.0 se nemění.

## Migrace V6

`V6__account_cancellation.sql` přidává stav `ANONYMIZED` a `cancelled_at`.
Anonymizovaný účet musí mít e-mail, normalizovaný e-mail, zobrazované jméno
a čas ověření e-mailu NULL a vyplněný čas zrušení. Ostatní stavy nadále vyžadují
identifikační údaje a nesmějí mít čas zrušení. Stávající unikátnost
`normalized_email` dovoluje okamžitou registraci uvolněného e-mailu s novým ID.
Migrace V1–V5 se nemění.

## Perzistentní operace

`AccountCancellationStore` vyžaduje existující transakci (`MANDATORY`). Zamyká
nejprve `admin_control`, pak účet. Nabízí samostatné odstranění identit,
relací, jednorázových tokenů a rolí, fyzické odstranění a anonymizaci účtu.
FK záměrně brání fyzickému smazání před odstraněním závislostí.
Stávající databázová ochrana posledního aktivního ADMIN zůstává zapnutá.

Odpojení všech vlastněných posádek nastaví kapitána na NULL, zvýší jejich verzi
a aktualizuje čas změny. Nemění fotografie ani jejich soubory. Ukončení převodů
zamkne související posádky před převody, zpracuje oba směry: dosud platný PENDING
se změní na CANCELLED / ACCOUNT_CANCELLED, již vypršelý na EXPIRED / EXPIRED.
Koncové stavy, historie převodů a audit se nemění ani nemažou.

Tyto primitivní metody samy nerozhodují o oprávnění, historii účasti ani auditu
rušení. Nejsou určeny k samostatnému volání z controlleru. Transakční orchestrátor
v 3b.4b musí všechny potřebné kroky spojit s autorizací a auditem.

## Úklid idempotence

Stávající ledger `admin_role_command`, replay a 24hodinová platnost zůstávají.
V6 přidává nullable `related_account_ids uuid[]` s GIN indexem. NULL označuje
starší záznamy; nové zápisy obsahují aktéra a účty podle konkrétní operace:

- ADMIN_ROLE: cílový účet.
- CREW_CREATE: požadovaný a výsledný kapitán; ID posádky není ID účtu.
- CREW_CAPTAIN: nový i původní kapitán. Původního doplňuje `relate` ve stejné transakci.
- CREW_TRANSFER_*: obě strany z trvale uchované historie převodu, případně také z odpovědi.

`CommandCleanupStore.deleteRelated(accountId, email)` vyžaduje vnější transakci,
sdílí zámek `admin_control` se zapisujícími operacemi a zamyká celý ledger.
Pro malý projekt záměrně prochází i expirované záznamy všech aktérů. Nejdříve
ověří všechny formáty a teprve potom maže celé související řádky.

U starých řádků rekonstruuje vazby podle známé operace, JSON polí a historie
převodu. U CREW_CAPTAIN použije předchozí/nové kapitány z auditní historie posádky;
jde o konzervativní výběr, může odstranit více replay záznamů téže posádky.
Pokud nezbytná historie chybí, úklid se zastaví. Neznámá operace, neočekávaná
struktura nebo neznámé pole odpovědi také operaci zastaví; výjimka neobsahuje payload.
Budoucí nové formáty musí rozšířit tento uzavřený seznam.

Vedle UUID se v textových JSON hodnotách požadavku i odpovědi hledá doslovný
výskyt rušeného e-mailu bez rozlišení velikosti písmen, také uvnitř delšího textu.
Nejde o SQL LIKE ani regulární výraz. Volající předá e-mail získaný pod zámkem
**před** anonymizací/smazáním účtu. Úklid nemění skutečné údaje posádky ani audit.
Hesla a otevřené jednorázové tokeny do ledgeru nepatří; potvrzení převodů nadále
ukládá pouze existující účelově oddělený otisk požadavku. Bez HMAC, šifrování či nových klíčů.

## Ověření a návaznost

`CommandAccountsTest` ověřuje klasifikaci UUID, osiřelou posádku, textové e-maily,
odmítnutí neznámého formátu a otevřeného tokenu. `AccountCancellationIT` ověřuje
migraci V5→V6, CHECK/FK, fyzické smazání, anonymizaci a opětovnou registraci,
ochranu posledního ADMIN, odpojení posádek se zachováním fotografie, převody
na obou stranách, metadata, legacy formáty, expirované i nesouvisející záznamy,
fail-closed úklid a rollback databázových změn. Používá vlastní izolované schéma.

PostgreSQL běh provádí uživatel: `.\mvnw.cmd -Pit verify` proti `rallye_test`.
Uživatel potvrdil závěrečný BUILD SUCCESS: 91 PostgreSQL integračních testů
včetně V6 a opravené kontroly posledního ADMIN. Jde o uživatelem provedený běh
proti `rallye_test`, nikoli vlastní PostgreSQL běh Codexu. 3b.4a je ověřena.
Lokální `mvnw.cmd verify` s JDK 21 prošlo: 184 testů bez DB, žádné chyby ani přeskočené testy.

3b.4b musí doplnit celkovou transakci, rozhodování o účasti/historii s bezpečným
odmítnutím neznámého schématu, autorizaci a potvrzení heslem, audit rušení,
REST API a souběhy s existující autentizací včetně opětovné registrace.
Tato část zatím neposkytuje uživatelsky použitelnou funkci zrušení účtu.
