# 3b.6c – společný ruční regresní postup v Postmanu

Uživatel dne 24. 10. 2026 potvrdil úspěšné ruční ověření všech šesti scénářů proti rallye_dev. Přesný doložený rozsah je v oddílu 10; následující podrobný postup zůstává návodem pro opakování, nikoli tvrzením provedení každého volitelného kroku. Codex ruční testy nespouštěl, účty ani databázová data neměnil. Automatické ověření a závěrečná konsolidace jsou v testing.md a [review-3b6.md](review-3b6.md).

## 1. Prostředí, účty a bezpečné oddělení relací

Použijte backend s profilem local a existující lokální schránkou `$HOME/.rallye/local-mail`. baseUrl je obvykle `http://localhost:8080`. Pracujte pouze s vlastními jednorázovými regresními účty a posádkami. Scénář 5 účet C skutečně ruší přes aplikaci; žádný požadavek není určen pro skutečná uživatelská data. Spouštějte jednotlivé požadavky tlačítkem Send, nikoli celou kolekci nebo Runner. Retenční úklid ani úklid fotografií zde ručně nespouštíme.

Importujte aktuální `postman/Rallye-Auth-2A.postman_collection.json`, ideálně jako místní pracovní kopii pro tento průchod. Původní webový režim kolekce zůstává výchozí a používá cookies + automatické CSRF. Pro současné zachování několika relací použijeme již podporovanou mobilní Bearer větev.

**Samotné přepnutí Postman environmentu nezajišťuje oddělení cookies.** Cookie manager uchovává cookies podle domény; u Bearer požadavků proto vypněte jejich automatické použití: otevřete konkrétní request → **Settings → Disable cookie jar = ON**. Platí to i pro GET a následné kontroly 401. Nové tři requesty ve složce 15 mají toto nastavení již v souboru kolekce; u níže uvedených znovu použitých requestů jej nastavte ručně. Neodstraňujte ani nepřepisujte cookies jiných relací. Zdroj nastavení: [oficiální Postman dokumentace cookies](https://learning.postman.com/docs/use/send-requests/response-data/cookies).

Vytvořte samostatné lokální environments:

| Environment | Účet / účel |
| --- | --- |
| R | Existující aktivní ověřený ADMIN; zůstává administrátorem po celý průchod. SQL bootstrap zde neprovádět. |
| A | Aktivní ověřený běžný uživatel, původní vlastník P1 a P2. |
| B | Aktivní ověřený běžný uživatel, příjemce převodů. |
| C | Aktivní ověřený jednorázový účet; ve scénáři 5 bude zrušen. |
| C2 | Stejný účet jako C, ale druhá samostatná přihlašovací relace. |
| D | Aktivní ověřený běžný uživatel; ve scénáři 4 dočasně získá ADMIN. |
| C-new | Vytvořte až po zrušení C; stejný e-mail, nový účet a nová relace. |

Pro každý environment nastavte pouze místní hodnoty:

| Environment proměnná | Hodnota |
| --- | --- |
| `regressionAuthMode` | `bearer` při přihlášení a všech autentizovaných krocích níže |
| `regressionEmail` | E-mail příslušného účtu; C a C2 mají stejný |
| `regressionPassword` | Heslo příslušného účtu, označit jako citlivé |
| `regressionSessionToken` | Zpočátku prázdné; doplní pouze úspěšný nový login, označit jako citlivé |
| `regressionAccountId` | Ručně zapište ID z `/auth/me` pro kontrolu identity |

Tokeny se ukládají výhradně do vybraného environmentu, nikdy do proměnných kolekce. Nesdílejte ani neexportujte vyplněné environments, kolekci, odpovědi loginu nebo Postman Console. Kolekce tokeny neloguje, ale samotný Postman je může zobrazovat v odpovědi/konzoli. Prostředí C2 nekopírujte včetně tokenu z C: před jeho prvním loginem musí mít vlastní prázdný token.

**Přihlášení:** v každém environmentu jednou spusťte `15 / 3b6 Login isolated mobile session`:

```http
POST /api/v1/auth/login
Content-Type: application/json
X-Rallye-Client: mobile
```

```json
{"email":"<e-mail vybraného účtu>","password":"<jeho heslo>","rememberMe":false}
```

Tělo skript bezpečně sestaví z environmentu. Očekává se 200, `tokenType=Bearer`, token a žádná cookie RALLYE_SESSION. Skript uloží token pouze do tohoto environmentu a další login při neprázdném tokenu zablokuje. Pro druhou relaci zvolte jiné prostředí; neodhlašujte ani znovu nepřihlašujte A/B/D během ověřování změn oprávnění. Následně `02 / 06 Current account` vrátí 200 a `id`, `email`, `displayName`; zapište ID do regressionAccountId. C a C2 musí mít shodné ID účtu, ale různé tokeny.

Ve všech autentizovaných krocích kolekční skript podle vybraného environmentu doplní `Authorization: Bearer <jeho token>` a `X-Rallye-Client: mobile`. V tomto režimu nedělá CSRF subrequest a nesahá do cookie jaru. Serverová Bearer větev je stávající zabezpečený mechanismus, nevypínáme ochrany webové větve. Requesty musejí mít vypnutý cookie jar a nesmějí mít ručně vložený Cookie header. Pokud je odešlete současně s RALLYE_SESSION, backend správně odmítne nejednoznačné credentials; nejde o selhání testovaného oprávnění.

Používejte pouze nový login ze složky 15. Staré requesty `05 Login (session cookie)`, `18 Login administrator` a `3b2 01/15 Login` patří k webovému průchodu; zde by vytvářely další nepotřebné relace. Pro přepnutí uživatele pouze vyberte jeho environment.

### Příprava nových účtů bez ručního SQL

R již musí existovat. Pokud A/B/C/D ještě neexistují, založte je před průchodem pomocí existujících `01 / 02 Register account` a `01 / 04 Activate account … fill activationToken first`. Pro tyto dva requesty ponechte cookie jar zapnutý a ve vybraném environmentu dočasně nastavte regressionAuthMode=`cookie` (nebo ji odstraňte); standardní CSRF skript pak funguje jako dosud. Přihlašování webovým requestem není potřeba.

Pro každý účet nastavte **collection** proměnné email, password, displayName; registrace vrátí 202 bez těla. Z příslušného lokálního souboru vezměte aktivační token do collection activationToken; ověření vrátí 204. Poté vymažte collection password/activationToken, odstraňte použitý soubor zprávy a pro autentizované kroky vraťte environment na bearer. Nezaměňte tyto collection proměnné s regressionEmail/Password dané relace. Stejný postup se použije později pro C-new.

## 2. Sdílené parametry a seznam používaných requestů

Proměnné objektů a idempotence zůstávají **collection** proměnné, protože existující skripty je přímo čtou a aktualizují. Nevytvářejte jejich stejnojmenné environment proměnné: měly by přednost při interpolaci a mohly by se rozcházet s hodnotou ve skriptu. Průchod provádí jeden operátor postupně; nejde o paralelní běh několika Runnerů.

Veďte si následující tabulku ID a verzí; lze použít vlastní environment aliasy `caseP1Id`, `caseP2Id` apod., které se neshodují s názvy proměnných kolekce:

| Označení | Význam |
| --- | --- |
| P1 | Posádka vytvořená A, ve scénáři 2 převedená B |
| P2 | Druhá posádka A, ve scénáři 3 přiřazená C a ve scénáři 5 osiřelá |
| Q | Další vlastní posádka C, ve scénáři 5 osiřelá |
| P3 | Další posádka A, ve scénáři 5 s čekajícím převodem na C |
| T1/T2 | Převody P1/P2 z A na B ve scénářích 2/3 |
| T-out / T-in | Čekající převody C→B nad P2 a A→C nad P3 pro scénář 5 |
| C-old / C-new | UUID účtu před zrušením a po nové registraci |

Před každým krokem nastavte collection crewId na správnou posádku. GET detailu přepíše crewId/crewVersion, vytvoření nové posádky je přepíše také. Založení převodu přepíše crewTransferId; před vytvořením dalšího si předchozí ID a token bezpečně poznamenejte lokálně. Vždy pracujte s aktuální verzí z GET, nikoli s číslem odhadnutým ze staršího kroku.

Při **nové nezávislé operaci** vyprázdněte její klíč, aby se vygeneroval nový UUID. Při **replay** zachovejte tentýž klíč i parametry včetně původní verze. Neměňte klíč jen proto, abyste obešli neočekávaný konflikt.

| Request, složka / přesný název | Vstup a co připravit |
| --- | --- |
| 02 / `06 Current account` | GET /api/v1/auth/me; 200 |
| 03 / `17 Logged-out account returns 401` | Tentýž GET, ale očekává neplatnou ponechanou relaci; 401 |
| 05 / `19 Read audit` | GET /api/v1/admin/audit-events?page=0&size=20; ADMIN 200 |
| 05 / `20 Grant ADMIN (repeat Send to verify replay)` | PUT /api/v1/admin/users/{adminTargetId}/roles/ADMIN; adminGrantKey; 204 |
| 05 / `22 Revoke ADMIN` | DELETE stejná cesta; adminRevokeKey; 204 |
| 06 / `3b2 02 Create own crew` | POST /api/v1/crews; crewName, nový crewCreateKey; 201, Location, id, captainAccountId, version=0 |
| 06 / `3b2 05 List own crews` | GET /api/v1/crews?page=0&size=50; 200, items/page/size/hasMore |
| 06 / `3b2 06 Detail and refresh version` | GET /api/v1/crews/{crewId}; 200, aktualizuje crewVersion |
| 06 / `3b2 07 Update text` | PUT /api/v1/crews/{crewId}; name=Updated crew, registrationPlate=TEST-001, description=null, version=crewVersion; 200 |
| 07 / `3b2 16 List all crews` | GET /api/v1/crews?scope=all; ADMIN 200 |
| 07 / `3b2 18 List orphans` | GET /api/v1/crews?scope=all&orphaned=true; ADMIN 200 |
| 07 / `3b2 23 Read crew audit` | GET /api/v1/admin/audit-events?eventType=CREW_CAPTAIN_CHANGED&targetId={crewOrphanId}; nastavte crewOrphanId i pro neosiřelou testovanou posádku |
| 10 / `3b3b Create captain transfer` | POST /api/v1/crews/{crewId}/transfers; targetEmail=crewTransferTargetEmail, version=crewTransferVersion, nový crewTransferKey; 201 |
| 11 / `3b3c Accept captain transfer` | POST /api/v1/crew-transfers/{crewTransferId}/accept; token=crewTransferToken, nový crewTransferAcceptKey; 200 |
| 11 / `3b3c ADMIN change captain cancels transfer` | PUT /api/v1/crews/{crewId}/captain; captainAccountId=newCaptainAccountId, version=crewTransferAdminVersion, crewTransferAdminKey; 200. Při prázdném klíči skript vezme výchozí verzi z crewVersion |
| 12 / `3b3d New captain can read transferred crew` | GET detailu crewId; 200 |
| 12 / `3b3d Original captain cannot read transferred crew` | Tentýž GET; 404 CREW_NOT_FOUND. Znovu použijte i pro cizí účet nebo novou identitu |
| 13 / `3b4b Self cancellation - DELETE TEST ACCOUNT (204)` | POST /api/v1/auth/account-cancellations; currentPassword=cancellationPassword, nový selfCancellationKey; 204 |
| 14 / `3b5 ADMIN Export all audit events` | GET /api/v1/admin/audit-events/export; 200 text/csv |
| 14 / `3b5 Ordinary user export forbidden` | Stejný GET s očekáváním 403 |
| 15 / `3b6 Login isolated mobile session` | Nový mobilní login uloží token vybraného environmentu; 200 |
| 15 / `3b6 Revoked ADMIN cannot read audit (403)` | Existující audit API s negativním očekáváním; 403 ACCESS_DENIED nebo ADMIN_REQUIRED |
| 15 / `3b6 Cancelled transfer cannot be accepted (409)` | Existující accept API s negativním očekáváním; aktuální původní token, nový regressionCancelledAcceptKey; 409 TRANSFER_NOT_PENDING |

U **každého** autentizovaného requestu z tabulky nastavte Disable cookie jar=ON, včetně `17 Logged-out account returns 401`. Nemusíte přidávat ručně Authorization ani X-Rallye-Client; doplní je kolekční skript. Nové requesty ve složce 15 to mají připravené. Hlavičky Idempotency-Key používají uvedené collection proměnné. Pro textovou úpravu, detail a seznam se klíč nevyžaduje.

## 3. Scénář 1 – více vlastních posádek a izolace cizího účtu

Předpoklady: existující relace A a B, oba běžní uživatelé. B nesmí mít ADMIN.

1. Environment A: nastavte crewName=`3b6 P1`, vyprázdněte crewCreateKey; spusťte `3b2 02 Create own crew`. Očekávejte 201, captainAccountId=A UUID a version=0. Zapište P1 ID.
2. Stejným requestem, s novým klíčem a crewName=`3b6 P2`, založte P2. Očekávejte jiné id, stejného kapitána a verzi 0. Zapište P2 ID.
3. A: `3b2 05 List own crews` → 200; obě ID jsou v items (při dalších datech stránkujte podle hasMore).
4. A: crewId=P1 → `3b2 06 Detail and refresh version`, potom `3b2 07 Update text` → 200; id/captain zůstávají a version se zvýší právě o 1. Název tohoto requestu se skutečně změní na `Updated crew`.
5. Přepněte environment B bez loginu/logoutu. `3b2 05 List own crews` → 200, P1/P2 nejsou v jeho seznamu. Pro každé ID spusťte `3b3d Original captain cannot read transferred crew` → 404 CREW_NOT_FOUND; ne 401. Tím se odliší platná cizí relace od nepřihlášeného uživatele.
6. V obou environments `06 Current account` → 200 s původním ID; nedošlo k přepisu relací.

Volitelné DB ověření: Q1 níže; P1 a P2 mají captain_account_id=A. Žádný účet ani posádka se v tomto scénáři nemaže.

## 4. Scénář 2 – přijetí převodu mění oprávnění existujících relací

Předpoklady: relace A/B z předchozího scénáře stále existují, A vlastní P1. Nikdo se znovu nepřihlašuje. P1 nemá živý převod.

1. A: crewId=P1, `3b2 06 Detail and refresh version` → 200. Zapište aktuální verzi v.
2. Nastavte crewTransferTargetEmail=B e-mail, crewTransferVersion=v, vyprázdněte crewTransferKey. `3b3b Create captain transfer` → 201; odpověď obsahuje id=T1, crewId=P1, fromAccountId=A, toAccountId=B, status=PENDING a expiresAt. Neobsahuje token. Vlastnictví a verze se založením nemění.
3. Ve schránce pro B najděte zprávu pro T1. Uložte její token do collection crewTransferToken; zkontrolujte crewTransferId=T1. Token nepochází z JSON API a nesmí se zaměnit za session token.
4. B: vyprázdněte crewTransferAcceptKey; `3b3c Accept captain transfer` → 200, status=ACCEPTED, crewId=P1, crewVersion=v+1.
5. B: `3b3d New captain can read transferred crew` → 200, captainAccountId=B. Může následně použít `3b2 06…` a `3b2 07 Update text` → 200 s další verzí.
6. A ve stále stejné relaci: `3b3d Original captain cannot read transferred crew` pro P1 → 404 CREW_NOT_FOUND; pro P2 nadále `3b2 06…` → 200. `/auth/me` v A i B vrací původní UUID.

Volitelné Q1/Q2: P1 patří B; T1 je ACCEPTED, jeho from/to zůstávají A/B. Audit přes R obsahuje CREW_TRANSFER_REQUESTED/ACCEPTED. Závodní zařízení zde netestujeme, protože závodní modul ještě neexistuje.

## 5. Scénář 3 – administrátorská změna zneplatní čekající převod

Předpoklady: A stále vlastní P2, B je běžný příjemce, C aktivní ověřený účet, R ADMIN. Všechny relace zachovejte.

1. A: crewId=P2, GET detailu a aktuální crewVersion. crewTransferTargetEmail=B, crewTransferVersion=aktuální verze, nový crewTransferKey. `3b3b Create captain transfer` → 201, T2 PENDING. Poznamenejte ID T2 a token z B schránky.
2. R: crewId=P2, `3b2 06…` → 200. Nastavte crewTransferId=T2, newCaptainAccountId=C UUID a vyprázdněte crewTransferAdminKey. `3b3c ADMIN change captain cancels transfer` → 200, captainAccountId=C a version o 1 vyšší. Skript uchová původní verzi pro případ přesného replay.
3. B: nastavte crewTransferId=T2, crewTransferToken=původní platný token T2 a vyprázdněte regressionCancelledAcceptKey. `3b6 Cancelled transfer cannot be accepted (409)` → 409 TRANSFER_NOT_PENDING. Použijte nový klíč; nereplayujte dřívější přijatý převod T1.
4. C: crewId=P2, `3b3d New captain can read transferred crew` → 200 s kapitánem C. A i B: `3b3d Original captain cannot read transferred crew` → 404 CREW_NOT_FOUND.
5. R: nastavte crewOrphanId=P2 a spusťte `3b2 23 Read crew audit` → 200; kontrolujte previousCaptainId=A, newCaptainId=C. `19 Read audit` lze filtrovat v Params podle targetId=T2 a eventType=CREW_TRANSFER_CANCELLED; důvod je ADMIN_CAPTAIN_CHANGED (úplné strukturované údaje v CSV nebo Q3).

Volitelné Q1/Q2/Q3: změna posádky a CANCELLED stav T2 musí být současně viditelné. Nedělejte další ADMIN změnu jen kvůli opakování chyby; přepsali byste potřebný stav scénáře 5.

## 6. Scénář 4 – odebrání ADMIN bez nové relace

Předpoklady: R zůstává ADMIN; D má aktivní relaci z přípravy a původně není ADMIN. Nevztahujte revoke na R.

1. R: adminTargetId=D UUID, nový adminGrantKey; `20 Grant ADMIN…` → 204. Volitelně stejný Send ověří replay 204 bez nové změny.
2. D se stejným tokenem: `19 Read audit` → 200 a `3b5 ADMIN Export all audit events` → 200 text/csv. Vytvoří se AUDIT_EXPORT best effort podle již ověřeného 3b.5 kontraktu.
3. R: adminTargetId=D, nový adminRevokeKey; `22 Revoke ADMIN` → 204.
4. D, bez loginu/logoutu a bez výměny tokenu: `3b6 Revoked ADMIN cannot read audit (403)` → 403; `3b5 Ordinary user export forbidden` → 403. Odmítnutý export nesmí založit nový AUDIT_EXPORT pro D.
5. D: `06 Current account` a `3b2 05 List own crews` → 200. Odvolala se administrace, nikoli uživatelská relace.
6. R: `21 Filter grant audit` s adminTargetId=D → ADMIN_GRANTED; pro odebrání lze u `19 Read audit` zadat eventType=ADMIN_REVOKED a targetId=D. Nepoužívejte ADMIN login request k přepnutí účtu.

Volitelné Q4: D nemá ADMIN, R jej stále má. Q3 potvrzuje grant/revoke a pouze úspěšný export D. Ponechte D bez ADMIN i pro další průchod.

## 7. Scénář 5 – zrušení účtu, posádky, převody a všechny relace

Předpoklady: **C je vyhrazený účet určený ke zrušení**, aktuálně vlastní P2 ze scénáře 3. C a C2 mají dvě různé stále platné relace téhož UUID. R zůstává ADMIN. V předzávodním modelu V7 nejsou skutečné historické účasti; tento účet se fyzicky odstraní, neanonymizuje.

1. R přidělí C dočasně ADMIN: adminTargetId=C-old, nový adminGrantKey, `20 Grant ADMIN…` → 204. To umožní ve scénáři 6 skutečně ověřit, že stará role se nové registraci nevrátí. C: `19 Read audit` → 200; `/auth/me` v C i C2 má stejné C-old.
2. C: založte Q přes `3b2 02 Create own crew` s novým crewCreateKey a crewName=`3b6 Q` → 201. P2 i Q patří C. Zaznamenejte jejich aktuální verze.
3. C: crewId=P2, GET detailu; nový crewTransferKey, crewTransferTargetEmail=B, crewTransferVersion=aktuální. `3b3b Create captain transfer` → 201, T-out PENDING. Token je v B schránce; uložte ID a token zvlášť.
4. A: založte P3 s novým crewCreateKey a jménem `3b6 P3`; z aktuální verze založte převod na e-mail C s novým crewTransferKey → 201, T-in PENDING. P3 nadále patří A. ID T-in si poznamenejte, protože přepíše sdílené crewTransferId.
5. Před zrušením `06 Current account` v C i C2 → 200. V collection cancellationPassword nastavte současné heslo C a vyprázdněte selfCancellationKey. Vyberte **environment C** a ověřte správné UUID ještě jednou. `3b4b Self cancellation - DELETE TEST ACCOUNT (204)` → 204 bez těla. Není to zrušení R ani jiného účtu. R jako další ADMIN umožňuje zrušení C; ochrana posledního administrátora zůstává zachována.
6. Tokeny C/C2 záměrně nevymazávejte ani nenahrazujte. V každém z těchto environments spusťte `17 Logged-out account returns 401` → 401 UNAUTHENTICATED. Cookie jar musí být vypnutý, aby se omylem nepoužila relace R.
7. R: `3b2 18 List orphans` → 200; P2 a Q jsou přítomné. Každou ověřte přes `3b2 06 Detail…`: captainAccountId=null, verze o 1 vyšší než těsně před zrušením. U většího seznamu použijte page/size. P1 nadále patří B, P3 nadále A.
8. B: nastavte crewTransferId=T-out, crewTransferToken=jeho původní token, vyprázdněte regressionCancelledAcceptKey; negativní accept ze složky 15 → 409 TRANSFER_NOT_PENDING. Pro T-in nelze použít zrušenou relaci C k ověření stavu žádosti: dostala by 401. Oba CANCELLED stavy doložte Q2 a auditem přes R.
9. R: `19 Read audit` s Params eventType=ACCOUNT_DELETED, targetId=C-old → právě jedna výsledná událost. Q3/CSV dokládá rovněž zrušení obou převodů s ACCOUNT_CANCELLED, odpojení kapitána a ADMIN_REVOKED. E-mail, heslo ani token nemají být v auditu.

Volitelné Q1–Q4: C-old v user_account neexistuje, žádná jeho session ani role nezůstává, obě čekající žádosti jsou CANCELLED a posádky jsou zachované. Historické T1/T2 se nemažou. Žádné ruční DELETE/UPDATE SQL nepoužívejte.

## 8. Scénář 6 – stejný e-mail, nová identita bez původních práv

Předpoklady: scénář 5 dokončený, známé UUID C-old a původní e-mail C. P2/Q jsou osiřelé. R nadále existuje; prostředí C/C2 stále obsahují neplatné původní tokeny.

1. Vytvořte nové prostředí C-new s původním e-mailem C, heslem pro novou registraci a prázdným regressionSessionToken. Pro registraci/aktivaci dočasně použijte cookie režim a zapnutý jar podle přípravy výše. `02 Register account` → 202; nový token z nové zprávy → `04 Activate account …` → 204. Neznovupoužívejte starý aktivační ani převodní token.
2. C-new přepněte do bearer režimu; nový login ze složky 15 → 200, `06 Current account` → 200 s **C-new UUID odlišným od C-old**. Zapište nové ID. Původní C/C2 tokeny tím nezískají platnost; jejich kontrolní request 17 má nadále 401.
3. C-new: `3b2 05 List own crews` → 200, items prázdné (pokud jste s tímto čerstvým účtem ještě nic nezaložili). P2 a Q přes stávající negativní detail ze složky 12 → 404 CREW_NOT_FOUND. Nepřiřazujte je novému účtu před touto kontrolou.
4. C-new: `3b6 Revoked ADMIN cannot read audit (403)` a `3b5 Ordinary user export forbidden` → 403. Původní role C se neobnovila shodným e-mailem.
5. R: `3b2 18 List orphans` → P2/Q stále bez kapitána. P3 patří A a P1 B. Žádný starý převod nebyl obnoven.

Volitelné Q1/Q2/Q4: nový aktivní účet má nové UUID, žádné ADMIN oprávnění a žádnou posádku. Staré převody nadále odkazují na původní interní ID C-old; nezměnily se na nové ID stejného e-mailu.

## 9. Volitelné read-only SQL kontroly

Použijte připojení ke stejné lokální vývojové databázi jako testovaná aplikace, nikoli automaticky připojení integračních testů. Nahraďte všechny `<…>` skutečnými UUID z tohoto průchodu. Dotazy nejsou součástí kolekce ani automaticky spouštěného skriptu; obsahují pouze SELECT a nic nemění. Nespouštějte je s ponechanými zástupnými hodnotami. Nevybírejte password_hash, token_hash ani obsah přihlašovacích identit.

Q1 – aktuální vlastnictví a verze:

```sql
SELECT id, name, captain_account_id, version
FROM crew
WHERE id IN ('<P1_UUID>'::uuid, '<P2_UUID>'::uuid,
             '<Q_UUID>'::uuid, '<P3_UUID>'::uuid)
ORDER BY id;
```

Q2 – zachovaná historie převodů:

```sql
SELECT id, crew_id, from_account_id, to_account_id, status,
       created_at, expires_at, resolved_at, resolution_reason
FROM crew_transfer
WHERE id IN ('<T1_UUID>'::uuid, '<T2_UUID>'::uuid,
             '<T_OUT_UUID>'::uuid, '<T_IN_UUID>'::uuid)
ORDER BY created_at, id;
```

Q3 – související audit bez autentizačních údajů:

```sql
SELECT id, occurred_at, event_type, actor_id, target_type, target_id,
       previous_captain_id, new_captain_id, crew_id, transfer_id, resolution_reason
FROM audit_event
WHERE target_id IN ('<P2_UUID>'::uuid, '<Q_UUID>'::uuid, '<C_OLD_UUID>'::uuid,
                    '<D_UUID>'::uuid, '<T2_UUID>'::uuid,
                    '<T_OUT_UUID>'::uuid, '<T_IN_UUID>'::uuid)
   OR (event_type = 'AUDIT_EXPORT' AND actor_id = '<D_UUID>'::uuid)
ORDER BY occurred_at, id;
```

Q4 – účet, role a počet relací:

```sql
SELECT id, status, cancelled_at
FROM user_account
WHERE id IN ('<C_OLD_UUID>'::uuid, '<C_NEW_UUID>'::uuid);

SELECT account_id, role
FROM user_account_role
WHERE account_id IN ('<R_UUID>'::uuid, '<D_UUID>'::uuid,
                     '<C_OLD_UUID>'::uuid, '<C_NEW_UUID>'::uuid);

SELECT count(*) AS old_account_sessions
FROM user_session
WHERE account_id = '<C_OLD_UUID>'::uuid;
```

Q4 používejte až po scénáři 6, kdy existuje C-new. Očekává se pouze nový aktivní účet z dvojice C, mezi vybranými účty role ADMIN pouze u R a nulový počet session starého C. Další nesouvisející administrátory tento dotaz nezobrazuje ani nemění.

## 10. Záznam výsledků a omezení

| Scénář | Výsledek potvrzený uživatelem | Doložené kontroly |
| --- | --- | --- |
| 1 Ochrana cizích posádek | PROŠEL | Vytvoření 201; vlastní detail 200; cizí detail a úprava 404 bez změny dat; úspěšný upload fotografie vlastníkem; cizí čtení a změna fotografie 404, původní fotografie zachována |
| 2 Přijetí převodu ve stávajících relacích | PROŠEL | User1 → User2: založení 201, accept 200; původní kapitán 404, nový 200; původní Bearer relace bez nového loginu |
| 3 ADMIN zneplatní čekající převod | PROŠEL | Čekající User2 → User1: založení 201; přímá ADMIN změna kapitána 200; přijetí původní žádosti odmítnuto 409 |
| 4 Odebrání ADMIN ve stávající relaci | PROŠEL | Grant User2 204; export 200; revoke 204; ve stejné relaci export 403 i ADMIN změna kapitána 403 |
| 5 Zrušení účtu | PROŠEL | Čekající převod 201; zrušení User1 204; původní Bearer relace 401; přijetí čekající žádosti 409; ADMIN detail původní posádky 200 s kapitánem NULL |
| 6 Nová registrace stejného e-mailu | PROŠEL | Registrace stejné testovací adresy 202; aktivace 204; nový login 200; vlastní posádky prázdné; původní posádka 404; databázově potvrzen její kapitán NULL |

Výsledky byly dodány uživatelem pro rallye_dev, nikoli pro integrační rallye_test. Při průchodu se dočasně objevily potíže s Cookie Jar, přepínáním cookie/bearer režimu a starým tokenem. Po opravě konfigurace příslušné kontroly prošly; nejde o prokázanou chybu backendu. Nebyly dodány konkrétní interní error codes pro všechny uvedené statusy, proto se výše neodvozují z očekávaného kontraktu.

Rozšířené kroky původního návodu, například několik vlastních posádek, obě strany převodů, dvě nezávislé relace rušeného účtu, porovnání starého/nového UUID, nepřevzetí staré ADMIN role a všechny volitelné SQL kontroly, nemají každý samostatné ruční potvrzení v poslední zprávě. Jejich kontrola se opírá o existující automatické testy v [matici pokrytí](coverage-3b6.md), zejména IdentityIT, CrewsIT, AccountCancellationServiceIT a Stage3bRegressionIT. Uzavření šesti scénářů neznamená prohlášení, že byl ručně spuštěn každý request celé kolekce.

Tento průchod používá Bearer autentizaci pro izolaci uživatelů. Nenahrazuje samostatné webové cookie/CSRF testy již pokryté 2A a automatickou sadou. Nový login záměrně neumožní přepsat existující token stejného environmentu; při expiraci je nutné vědomě založit další session a zopakovat dotčené kroky, nikoli tvrdit ověření původní relace. Běžná session má 24 hodin maximální nečinnosti.

Pokud se objeví 400 AMBIGUOUS_CREDENTIALS, zkontrolujte vypnutý cookie jar a ručně vložené hlavičky. Při 409 IDEMPOTENCY_CONFLICT porovnejte klíč, operaci a původní parametry; při CREW_VERSION_CONFLICT obnovte GET detail a pro skutečně novou operaci použijte nový klíč. Pro negativní očekávání používejte připravené negativní varianty, aby správné odmítnutí nebylo falešně hlášeno jako selhání testu očekávajícího 200. Při 429 respektujte 15minutové okno a neobcházejte limit.

Souběhy, rollback při vložené chybě a retenční čištění zůstávají automatickým testům. Neexistuje veřejné API pro spuštění retence. Příprava nepřináší závodní účasti, externí přihlášení, změnu e-mailu ani nové bezpečnostní mechanismy. Po provedení uveďte výsledky šesti řádků, nikoli hesla, tokeny nebo celé exporty environments. Před případným sdílením kolekce vyprázdněte všechny osobní údaje a citlivé proměnné; samotné označení Sensitive nezajišťuje bezpečné sdílení.
