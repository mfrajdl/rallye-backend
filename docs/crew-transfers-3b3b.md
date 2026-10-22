# Etapa 3b.3b – založení převodu a doručování odkazu

Navazuje na [interní základ 3b.3a](crew-transfers-3b3a.md). Databázové schéma
se nemění; migrace V1–V4 zůstávají nedotčené. Audit převodů, REST přijetí,
odmítnutí a zrušení i ochrany mazání/administrátorských změn jsou podle zadání
odloženy do 3b.3c. Celá 3b.3 není dokončená a tento mezikrok ještě není určen
k vydání celého procesu převodů.

## REST kontrakt

Oba endpointy vyžadují přihlášení aktuálního kapitána, UUID v hlavičce
`Idempotency-Key` a při webových cookies také CSRF token. Existující Bearer
autentizace zůstává podporovaná. ADMIN nemůže tímto API zahájit běžný převod
cizí posádky. Autorizace vychází z aktuální databázové relace a vlastnictví.

### Založení

`POST /api/v1/crews/{id}/transfers`

```json
{
  "targetEmail": "novy-kapitan@example.test",
  "version": 3
}
```

E-mail se normalizuje stejným způsobem jako při registraci účtu. Existující
aktivní ověřený cílový účet se vyhledá a žádost se uloží s jeho interním UUID.
Další životní cyklus ani opakované zaslání nejsou vázány na zadanou adresu.
Na sebe převádět nelze. Platí aktuální verze posádky a maximálně jeden živý
čekající převod; právě expirovaný PENDING uzavře interní základ pod zámkem.

Odpověď je `201 Created` s `Location: /api/v1/crews/{id}/transfers/{transferId}`:

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "crewId": "22222222-2222-4222-8222-222222222222",
  "fromAccountId": "33333333-3333-4333-8333-333333333333",
  "toAccountId": "44444444-4444-4444-8444-444444444444",
  "status": "PENDING",
  "createdAt": "2026-10-22T12:00:00Z",
  "expiresAt": "2026-10-29T12:00:00Z"
}
```

Location identifikuje žádost; GET detailu převodu není v této etapě zaveden.
Odpověď neobsahuje e-mail, token ani jeho hash.

### Opakované zaslání

`POST /api/v1/crews/{id}/transfers/{transferId}/resend`

Bez těla, se samostatným `Idempotency-Key`. Vrací `200 OK` se stejným bezpečným
typem detailu. Vyžaduje aktuálního původního kapitána a dosud platnou žádost
pro danou posádku. Příjemce musí stále být aktivní a ověřený. Na jeho aktuální
e-mail se odešle nový náhodný token; databázový hash původního tokenu se atomicky
nahradí. `created_at`, `expires_at` ani `crew.version` se nemění.

### Chybové odpovědi

- `400 INVALID_REQUEST`: neplatné UUID, chybějící hlavička, e-mail nebo verze.
- `401`: nepřihlášený uživatel či již neplatná relace.
- `403 ACCESS_DENIED`: zejména chybějící/neplatný CSRF při webových cookies.
- `404 CREW_NOT_FOUND`: chybějící nebo cizí posádka.
- `404 TRANSFER_NOT_FOUND`: resend chybějící/cizí žádosti nebo žádosti jiné posádky.
- `409 CAPTAIN_UNAVAILABLE`: stejná odpověď pro chybějící, neaktivní a neověřený cíl.
- `409 CAPTAIN_UNCHANGED`: převod na současného kapitána.
- `409 CREW_VERSION_CONFLICT`: neaktuální verze při založení.
- `409 TRANSFER_ALREADY_PENDING`: již existuje živá čekající žádost.
- `409 TRANSFER_NOT_PENDING`: resend ukončené nebo expirované žádosti.
- `409 IDEMPOTENCY_CONFLICT`: stejný klíč s jinými parametry či pro jinou operaci.
- `429 TOO_MANY_ATTEMPTS`: vyčerpání limitu pokusů.

Autorizovaný kapitán se může dozvědět dostupnost zadaného cíle; API nezveřejňuje
detailní důvod nedostupnosti, seznam účtů ani jiná data cíle.

## Transakce, idempotence a tokeny

`CrewTransferService` používá existující `CommandStore`, společný prostor
klíčů podle aktéra a retenci 24 hodin. Parametry založení zahrnují ID posádky,
normalizovaný e-mail a původní verzi; resend ID posádky a žádosti. Opakování
stejného klíče vrátí uložený původní detail včetně původní expirace a stavu,
bez dalšího vložení, rotace nebo zprávy. Aktuální oprávnění se ověřují i pro
replay. Klíč po expiraci retence již nezaručuje původní výsledek.

Singleton admin_control, účet aktéra, cílový účet, posádka a žádost se zamykají
v zavedeném pořadí. Zápis žádosti nebo rotace hashe a uložení idempotentního
výsledku tvoří jednu transakci. Selhání zápisu vrátí všechny její změny zpět.
Při založení se volá MANDATORY služba CrewTransferLifecycle uvnitř této transakce.

`SecretTokens` generuje 32 náhodných bajtů pomocí SecureRandom a Base64URL bez
paddingu. Do tabulky patří pouze SHA-256 digest. Interní accept/reject z 3b.3a
ověřují hash konstantním porovnáním; REST vrstva 3b.3c bude hashovat předložený
otevřený token. Token nebude v REST odpovědi, evidenci idempotence ani auditu.

Stávající LoginThrottle omezuje odděleně create a resend: 10 pokusů na účet
a 50 na IP za 15 minut. Počítají se i replay požadavky. Jde o stávající
omezenou paměťovou evidenci v jednom procesu, resetovanou restartem; nejde
o distribuovaný limit. Používá skutečnou remoteAddr, nikoli nedůvěryhodné
Forwarded hlavičky.

## E-mail a konfigurace

AccountMailSender má samostatnou operaci sendTransfer. SMTP i lokální souborový
adaptér používají stejný text: název posádky, sedmidenní platnost od založení,
skutečný UTC termín a odkaz pro přijetí/odmítnutí po přihlášení.

```properties
rallye.crews.transfer-client-page=https://client.example.test/crew-transfer
```

Ve Spring prostředí lze použít `RALLYE_CREWS_TRANSFER_CLIENT_PAGE`. Výchozí hodnota
je `${rallye.auth.public-base-url}/crew-transfer` (lokálně
`http://localhost:3000/crew-transfer`). Konfigurace musí být HTTPS nebo HTTP na
localhost/127.0.0.1, bez userinfo, query a fragmentu.

Odkaz používá fragment `#transferId=...&token=...`, takže token necestuje jako
query serverového GET. Samotný GET nic nepotvrzuje. Klientskou stránku tento
backend neimplementuje; bez ní lze obsah zprávy kontrolovat v lokální schránce.

Pro lokální prostředí nadále platí profil `local`,
`rallye.auth.mail-mode=local-file`, explicitní loopback bind a soukromá složka
`$HOME/.rallye/local-mail` (nebo již existující konfigurace adresáře). Zpráva má
`Purpose: CREW_TRANSFER`. Žádný externí SMTP server není pro tento režim potřeba.
Nevkládat zprávy ani naplněné Postman proměnné do Gitu.

Odesílání se registruje až po databázových zápisech a probíhá v afterCommit;
SMTP dále používá existující asynchronní executor. Při rollbacku se zpráva
neodešle. Selhání doručení po commitu žádost neruší, loguje se pouze obecná
zpráva bez adresy či tajných údajů. Náprava je nový resend s novým klíčem.
Bez outboxu není garantované doručení při pádu procesu po commitu. Replay
původního klíče zprávu znovu neposílá. Ověření skutečného SMTP zůstává před nasazením.

## Ruční ověření v Postmanu

Kolekce `postman/Rallye-Auth-2A.postman_collection.json` obsahuje novou složku
`10 — Crew transfers 3b.3b`, která dědí cookies a automatické CSRF skripty.
Celá kolekce má 67 požadavků. Prošla striktním JSON parserem i validací proti
oficiálnímu Postman Collection v2.1 schématu (JSON Schema Draft4); JavaScript
skripty byly syntakticky zkontrolovány. Původní požadavky, proměnné a společné
autentizační/CSRF skripty zůstaly zachované. Kolekce nebyla spuštěna proti serveru.

1. Vytvořit a ověřit dva běžné účty. Přihlásit se jako aktuální kapitán.
2. Vybrat existující crewId a načíst její detail.
3. Nastavit crewTransferTargetEmail a crewTransferVersion podle aktuálního detailu.
   crewTransferKey a crewTransferResendKey nechat prázdné; vygenerují se UUID.
4. Spustit Create: 201, Location a jedna zpráva ve schránce cíle. Uloží se
   crewTransferId a crewTransferExpiresAt. Verze posádky se nezmění.
5. Spustit Replay beze změny vstupů: stejné ID a expirace, žádná další zpráva.
6. Spustit Conflict: používá klíč a původní verzi zachycené při úspěšném Create
   v crewTransferCreatedKey a crewTransferCreatedVersion. Bez nich se požadavek
   vůbec neodešle. Jen pro tento požadavek se zvýší verze; očekává se 409
   IDEMPOTENCY_CONFLICT. Původní proměnná verze se nemění.
7. Spustit Resend: 200 a nový token ve schránce, stejná expirace. Opakovat
   beze změny klíče: žádná další zpráva. Pro další skutečné odeslání klíč vymazat.
8. Nejnovější token lze lokálně vložit do prázdné crewTransferToken pro budoucí
   3b.3c; nyní neexistuje veřejné přijetí/odmítnutí. Nic citlivého neexportovat.

Během tohoto mezikroku netestovat mazání či ADMIN změnu kapitána posádky s živým
převodem jako podporovaný proces: jejich ochrany a atomické rušení žádostí
jsou dosud odložené do 3b.3c podle zadání.

## Testy a zbývající rozsah

`.\mvnw.cmd verify`: BUILD SUCCESS, 159 testů bez databáze, žádné chyby ani
přeskočení. Testy pokrývají HTTP kontrakt, CSRF, oprávnění, replay, konflikty,
rotaci a expiraci, SMTP/souborovou zprávu, rate limiting a afterCommit.

CrewTransfersIT přidává 8 případů nad rámec 9 případů 3b.3a: skutečný digest,
replay a konflikt, rotaci a neplatnost starého tokenu, rollback žádosti/rotace
i idempotentního záznamu bez e-mailu, cílové účty, expiraci a souběžné vytvoření
se stejným klíčem. Uživatel potvrdil úspěšný integrační běh; Failsafe report
obsahuje 56 testů, 0 errors, 0 failures a 0 skipped. Nejde o běh Codexu.
PostgreSQL běh podle AGENTS.md provedl uživatel příkazem:

```powershell
.\mvnw.cmd -Pit verify
```

3b.3c doplní veřejné přijetí/odmítnutí/zrušení, audit celého procesu v téže
transakci, návaznou idempotenci, zákaz mazání a rušení čekajících převodů
při administrátorské změně kapitána. Další produktové rozhodnutí pro tento
mezikrok nebylo potřeba.

## Diagnostika konfliktu verze a idempotence

CrewTransferService.create porovnává CommandStore.replay ještě před voláním
CrewTransferLifecycle.create, kde se kontroluje verze. Pořadí backendu se nemění.
Stejný platný klíč stejného aktéra a odlišná verze musí vrátit IDEMPOTENCY_CONFLICT.
CREW_VERSION_CONFLICT odpovídá nové operaci, například s novým klíčem, jiným
aktérem nebo po uplynutí 24hodinové retence. Z dostupných údajů nelze určit,
která z těchto okolností nastala při původním ručním testu.

Původní konfliktní Postman scénář při prázdném klíči generoval nový UUID.
Nyní používá skutečnou hlavičku zachycenou úspěšným Create; nevytváří nový klíč.
Regresní unit i PostgreSQL test rozlišují původní replay, změněnou verzi se
stejným klíčem a změněnou verzi s novým klíčem. PostgreSQL případ navíc před
opakováním skutečně změní verzi posádky.
