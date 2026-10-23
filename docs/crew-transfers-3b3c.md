# Etapa 3b.3c – rozhodnutí převodů, audit a ochrany

Navazuje na 3b.3a/b a používá jejich tabulku, životní cyklus, tokeny a společný
CommandStore. Nová migrace V5 rozšiřuje pouze audit. V1–V4 se nemění.
Etapa 3b.3c je dokončená a ověřená: 176 testů bez DB a uživatelem potvrzený
BUILD SUCCESS integrační sady; Failsafe report uvádí 71 testů bez chyb.

## Rozhodovací REST API

Všechny operace vyžadují přihlášení a `Idempotency-Key: <UUID>`. Cookies
vyžadují CSRF jako dosud; mobilní Bearer autentizace zůstává podporovaná.

| Operace | Endpoint | Tělo |
| --- | --- | --- |
| Přijmout | POST /api/v1/crew-transfers/{transferId}/accept | `{"token":"<aktuální potvrzovací token>"}` |
| Odmítnout | POST /api/v1/crew-transfers/{transferId}/reject | stejné |
| Zrušit | POST /api/v1/crew-transfers/{transferId}/cancel | bez těla |

Úspěch vrací 200 a bezpečné DTO: `id`, `crewId`, `status`, `crewVersion`,
`resolvedAt`. Neobsahuje token, jeho hash, otisk idempotentního požadavku ani e-mail.
Žádné GET rozhodnutí neprovádí.

Accept/reject může provést pouze původně určený cílový účet, který musí být
stále aktivní, ověřený a mít platnou relaci. Cancel provádí původní kapitán,
který dosud vlastní posádku. ADMIN nemá implicitní oprávnění rozhodovat za
příjemce; pro něj zůstává okamžitá změna kapitána přes existující API.

Platí původní čas expirace a aktuální hash po případném resendu. Accept zvýší
aktuální crew.version právě jednou; mezilehlá úprava textu/fotografie žádost
nezneplatní. Reject/cancel verzi nemění. Dosavadní relace původního kapitána
zůstává přihlášená, ale při příštím požadavku již nezíská přístup k převedené
posádce. Nový kapitán získá přístup okamžitě.

Chyby:

- 400 INVALID_REQUEST: neplatný/chybějící token, nesouhlasící token, chybný vstup či UUID.
- 401: nepřihlášený, neaktivní/neověřený účet či neplatná relace.
- 403 ACCESS_DENIED: zejména neplatná webová CSRF ochrana.
- 404 TRANSFER_NOT_FOUND: neexistující nebo cizí převod; existence se nesděluje.
- 404 CREW_NOT_FOUND: chybějící posádka při nové operaci.
- 409 TRANSFER_NOT_PENDING: nová operace nad ukončenou/expirovanou žádostí.
- 409 TRANSFER_OWNER_CHANGED: interní ochrana před přepsáním změněného vlastníka.
- 409 IDEMPOTENCY_CONFLICT: jiný požadavek/operace se stejným klíčem.
- 429 TOO_MANY_ATTEMPTS: limit pokusů, odděleně pro accept/reject/cancel.

Limity zůstávají 10 na účet a 50 na IP za 15 minut podle existujícího
LoginThrottle; jde o paměťovou evidenci jednoho procesu. Replay se počítá do limitu.

## Transakce, idempotence a souběh

CrewTransferService.decide ověří aktuálního aktéra, vazbu příjemce/původního
kapitána a záznam idempotence, potom zavolá stávající MANDATORY životní cyklus.
Změna kapitána, stav žádosti, audit a idempotentní výsledek sdílejí jednu
transakci. Replay vrací původní DTO i po ukončení žádosti, bez další změny
verze nebo auditu. Nový klíč na stejný ukončený převod vrací 409.

Do request_data se ukládá ID žádosti a pouze oddělený jednosměrný otisk:
SHA-256 nad označením účelu `rallye:crew-transfer-command:v1`, operací, ID
žádosti a otevřeným náhodným tokenem. Tento otisk se liší od autentizačního
SHA-256 hashe v crew_transfer a není použitelný jako potvrzovací token.
Otevřený token ani jeho autentizační hash se do ledgeru, auditu nebo logů
neukládají. Cancel žádný token ani takový otisk nepotřebuje. Retence je 24 hodin.

Měnící operace sdílejí zámek admin_control, poté účet aktéra, související
účty, posádku a žádost. ADMIN kontrola cílového účtu nyní také předchází
zamčení posádky. Platnost se znovu ověří po zamčení žádosti: `now < expires_at`.
Rozhodný je tento okamžik pod zámkem, nikoli okamžik přijetí HTTP požadavku.
Souběžný požadavek se po odemčení musí řídit výsledným stavem.

## Mazání a administrátorská změna

DELETE /api/v1/crews/{id}?version=... zachovává původní kontrakt. Pod stejnými
zámky kontroluje PENDING a skutečný čas expirace. Živý převod odmítne jako
409 CREW_TRANSFER_PENDING. Po vyřízení či expiraci je odstranění možné podle
dosavadních pravidel; historie převodů nemá blokující FK a zůstane zachovaná.
Expirace nevyžaduje automatický úklid a uložený stav může zůstat PENDING.
Budoucí modul účastí musí před zavedením historie přidat její ochranu mazání.

PUT /api/v1/crews/{id}/captain při úspěšné ADMIN změně nebo odebrání kapitána
uzavře živou čekající žádost jako CANCELLED s důvodem ADMIN_CAPTAIN_CHANGED,
zapíše audit a provede změnu kapitána v jedné transakci. Neúspěšná validace,
zápis či audit nesmí žádost ani posádku částečně změnit. Expirovaný uložený
PENDING se místo toho označí EXPIRED, protože již není živým převodem.
Replay ADMIN klíče neduplikuje zrušení ani audit.

## Audit a migrace V5

V5__crew_transfer_audit.sql přidává do audit_event nullable crew_id,
transfer_id, resolution_reason a indexy podle posádky/převodu. Historické
záznamy se nemění a vazby úmyslně nemají blokující FK.

CREW_TRANSFER_REQUESTED v původní implementaci 3b.3b nebyl, protože audit byl
odložen. Nyní se zapisuje při každém novém založení, bez zpětného vytváření
událostí pro staré žádosti. Rozhodnutí zapisují CREW_TRANSFER_ACCEPTED,
CREW_TRANSFER_REJECTED nebo CREW_TRANSFER_CANCELLED. Audit obsahuje aktéra,
čas, typ operace, převod, posádku a historická ID obou účtů. Předchozí pole
previousCaptainId/newCaptainId zde znamenají původního a zamýšleného cílového
kapitána žádosti, i pokud byla odmítnuta nebo zrušena.

Existující administrativní čtení auditu tato pole vrací; filtr targetId lze
použít s transferId, eventType s novými hodnotami enumu. ADMIN zrušení obsahuje
důvod ADMIN_CAPTAIN_CHANGED a vedle něj zůstává audit změny kapitána posádky.
Vypršení žádosti při navazující operaci nemá novou rozhodovací auditní událost.
Resend zachovává dosavadní chování bez dalšího typu auditní události.

## Postman

Nová složka `11 — Crew transfer decisions 3b.3c (run individually)` obsahuje
sedm požadavků: accept, replay, reject, cancel, starý token, blokované smazání
a ADMIN změnu kapitána. Dědí původní cookies a automatické CSRF získávání.

1. Připravit dva aktivní ověřené účty, přihlásit se jako původní kapitán,
   založit posádku a žádost pomocí složky 10. Zprávu získat ze souborové schránky.
2. Vyplnit crewTransferToken aktuálním tokenem a přihlásit se jako cílový účet.
   Accept vrátí 200. Ověřit přístup nového kapitána a ztrátu přístupu původního.
3. Replay ponechává stejný token a crewTransferAcceptKey; tento scénář nový
   klíč nikdy nevygeneruje. Pro novou samostatnou operaci vymazat příslušný klíč.
4. Reject a cancel vyzkoušet vždy s novou čekající žádostí; cancel jako původní
   kapitán. Po úspěchu lze posádku smazat, pokud není jiná překážka.
5. Pro starý token uložit původní hodnotu do crewTransferOldToken, provést
   resend jako kapitán a novou hodnotu vložit do crewTransferToken. Jako cíl
   zkusit starý token s vlastním klíčem: 400 INVALID_REQUEST; aktuální zůstává platný.
6. Jako původní kapitán či ADMIN načíst aktuální crewVersion. DELETE během
   živého převodu vrací 409 CREW_TRANSFER_PENDING.
7. Jako ADMIN nastavit newCaptainAccountId. Scénář zachytí crewTransferAdminVersion
   spolu s crewTransferAdminKey, takže replay nepoužije aktualizovanou verzi.
   Odebrání kapitána se zkouší tělem s JSON null. Následný accept původního
   cíle s novým klíčem musí vrátit 409 TRANSFER_NOT_PENDING.
8. V auditu zkontrolovat související ID, důvod a jedinou událost při replay.

Tokeny a vyplněné proměnné neexportovat do Gitu. Skutečné SMTP doručování
nadále čeká na výběr poskytovatele před nasazením. Bez outboxu stále platí
omezení doručení po pádu procesu po commitu, řešené opakovaným zasláním.

## Ověření a další krok

Codex provedl `.\mvnw.cmd verify`: BUILD SUCCESS, 171 testů bez databáze,
0 failures, 0 errors, 0 skipped. Obsahují HTTP/CSRF, povinnou auditní transakci,
rozhodnutí, otisky požadavků, replay a ochrany správy posádek.

Uživatel potvrdil úspěšné spuštění PostgreSQL testů: BUILD SUCCESS, dostupný
Failsafe report obsahuje 71 testů, 0 errors, 0 failures, 0 skipped.
Ověřují audit, změnu oprávnění již přihlášených kapitánů, skutečný rollback,
ochrany a všech pět zadaných souběhů i expiraci po získání zámků. Codex je podle
AGENTS.md nespouští. Uživatel provedl proti rallye_test:

```powershell
.\mvnw.cmd -Pit verify
```

3b.3c je uzavřena na základě potvrzeného ověření. Následující 3b.3d
je koncové integrační a manuální ověření celého převodního procesu, nikoli
nový paralelní životní cyklus. Závody, rušení účtů ani úklid auditu se zde neimplementují.

Statická kontrola kolekce: striktně platný JSON, oficiální schéma v2.1
(Draft4) prošlo, 74 požadavků a 121 syntakticky platných skriptů. Původní
požadavky i společné bezpečnostní skripty zůstaly zachované; kolekce zatím
nebyla spuštěna proti serveru. Samostatně ověřeno zachování ADMIN klíče
a původní verze při opakovaném spuštění prerequest skriptu.

## Diagnostika neočekávané HTTP 500

ApiErrors dříve zachytil neočekávanou výjimku bez logování. Nyní na ERROR
zapíše `Unexpected API failure`, původní typy výjimek, stack frames, příčiny,
suppressed výjimky a JDBC SQLSTATE včetně navázaných SQL výjimek. Texty výjimek
se nezapisují: například PostgreSQL může v DETAIL uvést celý chybný řádek
včetně hashe tokenu. Stejně se nelogují těla, hlavičky nebo query požadavku.
Klient nadále dostává pouze obecné 500 INTERNAL_ERROR.

Regresní HTTP test simuluje selhání accept se SQLException a kontroluje log
i neměnnou bezpečnou odpověď, včetně nepřítomnosti citlivých textů v příčinách
a suppressed výjimkách. `mvnw.cmd verify` po změně prošlo se 172 testy,
0 failures, 0 errors, 0 skipped.

Příčina konkrétního ručního selhání accept zatím není prokázána. Prověřená
cesta používá TransactionTemplate, MANDATORY lifecycle i audit, zamčenou
aktuální verzi, následný stav žádosti, audit a společný idempotentní zápis.
SQL auditu odpovídá migraci V5 a rozhodnutí pravidlům V4. Dostupný report má
71 úspěšných IT; sám nepotvrzuje stav schématu právě běžící lokální aplikace.

Další postup: restartovat lokální backend s novým handlerem, zopakovat accept
a najít `Unexpected API failure`. Stack ukáže konkrétní vrstvu. SQLSTATE
42703 ukazuje na chybějící sloupec, 42P01 na tabulku, 23514 na CHECK,
23503 na FK a 23505 na unikátnost. IllegalTransactionStateException ukazuje
na chybějící transakční hranici; taková chyba se nemá obcházet vypnutím MANDATORY.

Případné ověření schématu pouze čtením, ve stejném připojení jako aplikace:

```sql
SELECT current_database(), current_schema(), current_user;
SELECT version, success FROM flyway_schema_history WHERE version IN ('4', '5');
SELECT column_name, data_type FROM information_schema.columns
WHERE table_schema = current_schema() AND table_name = 'audit_event'
  AND column_name IN ('crew_id', 'transfer_id', 'resolution_reason');
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
WHERE conrelid = 'crew_transfer'::regclass;
```

Tyto diagnostické dotazy zde nebyly spuštěny; vývojová databáze nebyla změněna.
Nezapínat logování JDBC parametrů a neposílat obsah tokenů či souborové schránky.

### Běžné HTTP chyby

Nepodporovaná metoda nyní vrací 405 METHOD_NOT_ALLOWED v obvyklém ProblemDetail
formátu a zachovává hlavičku Allow. GET seznamu převodů se tím nezavádí.
Chybějící handler/statický prostředek vrací 404 NOT_FOUND a nepodporovaný
Accept typ 406 NOT_ACCEPTABLE. Dosavadní 400, 413 a 415 zůstávají zachované.
Tyto očekávané chyby neprocházejí diagnostikou neočekávaných výjimek; skutečná
neočekávaná chyba stále vrací bezpečné 500 a serverový diagnostický stack trace.
HTTP regrese pokrývají 405 včetně Allow a absence neočekávaného error logu,
404, 406 a zachování 415 vedle dřívějšího testu bezpečné diagnostiky 500.
Po této opravě `.\mvnw.cmd verify`: BUILD SUCCESS, 176 testů, 0 failures,
0 errors, 0 skipped. Běh nevyžadoval databázi.

## Závěrečná revize převodů

Aktuální výsledek kontroly celého procesu a podmínky uzavření 3b.3 jsou
v [crew-transfers-3b3d.md](crew-transfers-3b3d.md). Revize 3b.3d neuzavírá
celou 3b; další kroky zůstávají 3b.4, 3b.5 a 3b.6, teprve potom 3c.
