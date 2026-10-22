# Etapa 3b.2 – správa posádek

Implementovaný rozsah: CRUD trvalých posádek, soukromé fotografie, okamžité
administrátorské kapitánství, audit a sdílená idempotence. Potvrzované převody
zůstávají v 3b.3. Účasti, závody, rušení účtů ani export/úklid auditu se nepřidávají.
Etapa 3b.2 je dokončena. Závěrečné dostupné reporty potvrzují 118 testů bez DB a 39 PostgreSQL IT bez chyb. Databázový běh provedl uživatel, nikoli Codex; historické záznamy níže popisují i dřívější neúspěšný běh a opravu. Úplný manuální průchod všemi Postman scénáři zde není tvrzen.

## Potvrzená pravidla a technické rozhodnutí

- Specifikace 0.6.0, oddíl 2.2 výslovně vyžaduje aktivní, e-mailem ověřený
  účet i pro administrátorské přiřazení kapitána. Výjimka se týká potvrzení,
  nikoli požadavků na cílový účet.
- Administrátor může přiřadit sebe cizí či osiřelé posádce, změnit kapitána
  nebo jej odebrat. Stejný dosavadní kapitán se odmítne CAPTAIN_UNCHANGED;
  totéž platí pro opakované odebrání již chybějícího kapitána novým příkazem.
  Přesný replay téhož klíče je naopak úspěšný.
- Specifikace techniku version neurčuje. Všechny údaje, fotografie i kapitánství
  sdílejí jednu verzi posádky. Vytvoření má version=0. Změna zvyšuje verzi o 1;
  smazání vyžaduje aktuální verzi. Odebrání již chybějící fotografie je beze změny
  verze a bez dalšího auditu. Konflikt je 409 CREW_VERSION_CONFLICT.
- Name/RZ/description mají limity 100/32/2000 Unicode code points. Název nesmí
  být prázdný ani jen bílé znaky. Název/RZ nejsou unikátní. RZ a popis dovolují null.
  Textová editace neumožňuje měnit kapitána.

## Migrace, transakce a audit

Nová V3__crews.sql přidává crew (nullable FK kapitána, texty, verze, UTC časy
a metadata soukromé fotografie). V1 a V2 zůstávají beze změny. Neprovádět clean,
baseline, repair ani ruční přepis historie. Hibernate nadále pouze validate.

V3 rozšiřuje existující admin_role_command o operation a JSONB požadavek/výsledek.
Název tabulky se kvůli kompatibilitě ponechává. Nejde o paralelní mechanismus:
CommandStore nyní zajišťuje společnou evidenci i vkládání pro ADMIN a posádky,
AdminStore deleguje původní rozhraní. Původní klíče dostanou ADMIN_ROLE, zachovají
cíl, grant/revoke, čas i expiraci. Žádné původní záznamy se migrací nemažou.

Identita klíče je stále (actor_id, command_key); UUID klíč je povinný pro vytvoření
posádky a změnu kapitána. Stejná operace a normalizované parametry vrátí původní
snapshot výsledku (201 u vytvoření, 200 u kapitána). Jiná operace či parametry,
včetně jiné očekávané verze, vrátí 409 IDEMPOTENCY_CONFLICT. Retence je 24 hodin.
Po expiraci lze daný klíč znovu použít; pouze jeho expirovaný záznam se nahradí.
Snapshot se nepřepočítává z později změněné posádky. Před replay se ověřuje relace
a aktuální oprávnění k danému příkazu; replay administrátorské změny vyžaduje ADMIN.
U změny kapitána se replay vyhodnocuje před novou kontrolou verze a vlastníka.

Operace používají Spring TransactionTemplate a JDBC, nikoli perzistentní REST DTO.
Zápisy se serializují přes existující admin_control, potom se zamykají účty a posádka.
Přímé čtení detailu rovněž kontroluje relaci/vlastnictví/ADMIN podle aktuální DB.
Toto jednoduché řešení je určené malé soukromé aplikaci; administrativní a posádkové
služby sdílejí zámek. Před větší zátěží bude vhodné změřit propustnost.

AuditService nadále vyžaduje obchodní transakci. Události CREW_CREATED,
CREW_UPDATED, CREW_DELETED, CREW_CAPTAIN_CHANGED, CREW_PHOTO_CHANGED a
CREW_PHOTO_DELETED se ukládají atomicky s operací a idempotentním výsledkem.
Audit nově rozlišuje targetType; staré záznamy mají ACCOUNT, nové posádkové CREW.
U kapitánství zachovává předchozí a nové interní ID. Neobsahuje obsah fotografie,
e-mail, tokeny, hesla, název uploadu ani cestu k souboru. Čtení administrátorského
auditu vrací také targetType, previousCaptainId a newCaptainId; existující filtry
targetId a eventType lze využít pro posádky.

## REST API

Všechny endpointy vyžadují přihlášení. Web zachovává cookie + CSRF, mobil Bearer.
Odpovědi jsou no-store. Cizí nepřístupná a neexistující posádka shodně vrací 404.
Aktuální vlastník nebo ADMIN smí číst, upravovat a smazat posádku i fotografii.
Osiřelé posádky spravuje pouze ADMIN.

| Metoda a cesta | Vstup / výsledek |
| --- | --- |
| POST /api/v1/crews | JSON + Idempotency-Key UUID; 201, Location a detail |
| GET /api/v1/crews | Vlastní posádky, page=0, size=50 (1–100), items/page/size/hasMore |
| GET /api/v1/crews?scope=all | Jen ADMIN; volitelně captainId nebo orphaned=true |
| GET /api/v1/crews/{id} | Detail, 200 |
| PUT /api/v1/crews/{id} | Úplná náhrada textů + version; detail, 200 |
| DELETE /api/v1/crews/{id}?version=N | Smazání, 204 |
| PUT /api/v1/crews/{id}/captain | Jen ADMIN, UUID klíč, captainAccountId (povinné pole, dovoluje null) a version; detail, 200 |
| PUT /api/v1/crews/{id}/photo?version=N | multipart/form-data, pole file; detail, 200 |
| GET /api/v1/crews/{id}/photo | Autorizovaný JPEG/PNG, 200, nosniff |
| DELETE /api/v1/crews/{id}/photo?version=N | Detail s novou verzí, 200 |
| POST /api/v1/crews/photo-cleanups | Jen ADMIN; výslovný úklid souborů, 200 s removed |

Vytvoření bez captainAccountId znamená vlastní posádku i pro ADMIN. Výslovné
captainAccountId=null znamená osiřelou posádku; povoleno pouze ADMIN. Běžný účet
může výslovně uvést jen vlastní UUID, jinak se požadavek odmítne 403.
Filtry captainId a orphaned jsou použitelné pouze s administrativním scope=all.

```json
{
  "name": "Modrá posádka",
  "registrationPlate": "TEST-001",
  "description": "Soukromá poznámka"
}
```

Úprava předává úplné texty a aktuální version. Kapitána mění pouze samostatný
endpoint, například {"captainAccountId": null, "version": 2}.
Detail obsahuje id, captainAccountId, name, registrationPlate, description,
hasPhoto, createdAt, updatedAt a version. Nevrací e-mail kapitána ani cestu souboru.

Chyby: 400 INVALID_REQUEST (včetně chybějící verze či klíče), 401 neplatná relace,
403 ADMIN_REQUIRED nebo ACCESS_DENIED při CSRF, 404 CREW_NOT_FOUND/PHOTO_NOT_FOUND,
409 CREW_VERSION_CONFLICT/CAPTAIN_UNAVAILABLE/CAPTAIN_UNCHANGED/IDEMPOTENCY_CONFLICT,
413 PHOTO_TOO_LARGE, 415 PHOTO_UNSUPPORTED_FORMAT či UNSUPPORTED_MEDIA_TYPE,
400 INVALID_PHOTO/INVALID_PHOTO_DIMENSIONS, 503 PHOTO_STORAGE_UNAVAILABLE.

## Soukromé fotografie a úklid

Konfigurace: rallye.crews.photo-directory, přes prostředí RALLYE_CREWS_PHOTO_DIRECTORY.
Výchozí adresář je ${user.home}/.rallye/crew-photos. Pro Docker později připojit
privátní perzistentní volume; nyní se Docker ani infrastruktura nezavádí.
Adresář musí používat právě jednu databázovou instalaci; nesdílet jej mezi
vývojem, testy a produkcí. OS přístup omezit na účet služby, zejména ACL na Windows.
Na POSIX se adresář nastavuje na 0700. Nezveřejňovat jej přes reverse proxy.

Skutečný JPEG/PNG se ověřuje dekodérem, nikoli názvem/MIME uploadu. Kontroluje
5 MiB (5 242 880 bajtů), oba rozměry nejvýše 4096 px před úplným dekódováním
a úspěšné dekódování. Překódovaný výstup obsahuje pouze obraz bez přenosu EXIF/GPS
a musí také splnit limit 5 MiB. Neprůhledná ID souborů se generují na serveru.
Multipart obálka dovoluje 6 MiB, vlastní soubor 5 MiB. Žádný veřejný statický endpoint.

Upload, čtení, odstranění a úklid drží stejný procesní i OS souborový zámek
.storage.lock. Aplikace/instance sdílející volume musí používat tento protokol;
ruční zásahy do souborů během provozu nejsou podporované. DB a filesystem nemají
společný commit: nový soubor se atomicky přesune před DB commitem, při rollbacku
se odstraní až po ověření, že na něj DB neodkazuje. Při nejistém výsledku commitu
nebo nedostupné DB zůstane k pozdějšímu úklidu. Starý soubor se odstraňuje až po
úspěšném commitu. Selhání jeho odstranění nevrací již commitnutou operaci zpět.

Úklid se spouští pouze výslovným POST, nikdy automaticky. Odstraňuje jen známé
*.photo a upload-*.tmp soubory starší než 24 hodin; ignoruje cizí soubory a symlinky.
Před odstraněním fotografie dvakrát ověří reference v DB a znovu stáří. Výhradní
zámek chrání i souběžný upload. Není to endpoint pro mazání auditních záznamů.
Před nevratným souborovým úklidem commitne audit PHOTO_CLEANUP_STARTED
s targetType PHOTO_STORAGE. Záznam popisuje pokus, nikoli záruku dokončení;
při I/O chybě mohl proběhnout částečný úklid pouze nereferencovaných souborů.
Při selhání tohoto auditu se žádné soubory nemažou. Zálohovat DB i volume společně.

## Návaznosti a omezení

Specifikace zakazuje smazat posádku s jakoukoli účastí a při čekajícím převodu.
V 3b.2 ještě žádné takové tabulky/záznamy neexistují. Jejich moduly musejí před
prvním zápisem přidat FK RESTRICT a transakční kontroly mazání pod stejným zámkem
posádky. Historický kapitán patří budoucí účasti, nikoli aktuálnímu crew.captain_account_id.
Nevytváří se ručně přepínatelná náhrada historie ani předčasná tabulka závodů.
Při doplnění 3b.3 musí administrativní změna/odebrání kapitána ve stejné transakci
zrušit čekající převod. Nyní žádné čekající převody nelze vytvořit.

## Postman

Importuj stávající Rallye-Auth-2A.postman_collection.json (Collection v2.1).
Původní požadavky 2A/3b.1 jsou zachované. Přibyly složky 06 a 07.

1. Připrav aktivovaný běžný účet a bootstrapovaného ADMIN. Nastav baseUrl,
   crewOwnerEmail/crewOwnerPassword a adminEmail/adminPassword pouze lokálně.
2. Povol cookie jar a localhost v allowlist pro skriptovaný přístup. CSRF se
   načítá před každým měnícím požadavkem. Nekombinuj cookie a Bearer.
3. Složku 06 spouštěj jednotlivě v pořadí: login, create/replay/conflict, seznam,
   detail, editace, konflikt verze, fotografie a negativní oprávnění/validace.
   U uploadu vyber skutečný lokální JPEG/PNG v Body → form-data → file.
4. Složka 07 přihlásí ADMIN, vypíše všechny posádky, založí osiřelou posádku,
   přiřadí ji administrátorovi, ověří replay se starou původní verzí, odmítne
   stejného kapitána s novým klíčem a kapitána odebere. Zkontroluj audit.
5. Poslední požadavky výslovně provedou úklid fotografií a smažou obě testovací
   posádky podle uložených ID. Před odesláním zkontroluj, že jde o testovací data.

crewCreateKey/crewOrphanCreateKey/crewCaptainKey se vytvářejí jako UUID a zůstávají
pro replay. Pro nový celý scénář je vymaž. crewId/crewVersion a orphan varianty
se průběžně aktualizují. GET detailu obnoví aktuální version; původní create
snapshot může mít starší verzi. Soubory/chybný obsah, cizí přístup a chybějící
CSRF lze ručně zkusit nad kopiemi požadavků; automatické testy tyto stavy pokrývají.
Skutečné heslo, fotografie ani cookies neexportuj do Gitu.

## Automatické ověření

Bez DB: PhotoFilesTest, CrewServiceTest, CrewHttpTest a regrese 2A/3b.1.
CrewsIT navíc používá TestDatabaseInitializer, rallye_test, vlastní schéma
it_crews_<UUID> a dočasné soukromé fotografie. Po ověření DB/role uklidí jen
vlastní schéma/adresář; migrační regresní test má ještě vlastní *_upgrade schéma.
Kryje CRUD, oprávnění, kapitánství, replay/konflikt/retenci a společný klíčový
prostor s ADMIN, souběhy, verze, fotografie, cleanup a skutečný DB rollback po
již provedeném zápisu auditu nebo příkazu. Ověřuje identitu injektované příčiny
a stav po skončení transakce; nejde o pouhé očekávání obecné výjimky.

Codex spouští `.\mvnw.cmd verify`; uživatel spustí PostgreSQL testy:

```powershell
.\mvnw.cmd -Pit verify
```

Databázový běh po V3 zatím není potvrzený. Nesmí se nahrazovat rallye_dev,
oslabovat opt-in, měnit bezpečnostní initializer ani používat Flyway clean.

Výsledek vlastního běhu Codexu 22. 10. 2026: JDK 21.0.12.1, mvnw.cmd verify,
BUILD SUCCESS, 111 testů bez chyb a přeskočení, JAR sestaven. CrewsIT má 13 nových
případů; celá databázová sada má nyní 39 případů a čeká na uživatelův běh.
Postman: 60 požadavků, všech 33 původních zachováno, 94 skriptů syntakticky
ověřeno Node vm.Script, JSON ověřen JSON.parse/PowerShell ConvertFrom-Json
a oficiálním schématem Collection v2.1 (lokální draft-04 validátor).
Hesla/tokeny/CSRF proměnné jsou v exportu prázdné. Kolekce nebyla HTTP spouštěna.
### Oprava přípravy rollback testů 3b.2

Uživatelův PostgreSQL běh měl 39 případů, 0 failures a 3 errors. TXT i XML
Failsafe reporty ukazují IllegalTransactionStateException již při přípravě
Mockito doAnswer(...).when(audit).crew(...), na původních řádcích CrewsIT
166/192/202. Přes AuditService Spring CGLIB proxy se ještě před Mockito spy
spustí TransactionInterceptor a kontrola Propagation.MANDATORY; test v tomto
okamžiku správně nemá obchodní transakci. Potlačené InvalidUseOfMatchersException
jsou následkem přerušeného nastavení spy. Testované operace se vůbec nezavolaly.

Produkční AuditService a jeho MANDATORY se nemění. CrewService vytváří obchodní
transakce pomocí TransactionTemplate a za normálního běhu do nich audit vstupuje.
CrewsIT nyní vkládá selhání do AuditStore.appendCrew, který nemá transakční
precondition při nastavování spy. Při operaci nejprve provede skutečný INSERT
a teprve potom vyhodí jedinečnou testovací výjimku. Kontrola zachovává původní
příčinu výjimky, již ukončenou transakci, DB stav, audit, nepoužitý idempotentní
klíč a původní fotografii/verzi i počet souborů. Kapitánství se navíc po chybě
úspěšně zopakuje se stejným klíčem. Testy nemají vnější testovací transakci.

AuditTransactionTest nově bez DB ověřuje, že konfigurace repository spy nevolá
JDBC, skutečná služba MANDATORY nadále odmítá volání mimo transakci a vložené
selhání po auditním JDBC zápisu vyvolá jediný rollback vnější transakce. Používá
skutečné Spring proxy a HibernateJpaDialect; nenahrazuje PostgreSQL ověření dat.
Cílených 5 transakčních testů prošlo. Nový databázový běh stále provádí uživatel:

```powershell
.\mvnw.cmd -Pit verify
```

Úspěšné opakování opravené sady 39 IT zatím není potvrzené.

Po opravě přípravy testů: vlastní běh Codexu s JDK 21.0.12.1, mvnw.cmd verify,
**BUILD SUCCESS**, 113 testů bez DB, 0 failures/errors/skipped; JAR sestaven
a všech 39 IT zkompilováno. PostgreSQL testy Codex nespouštěl; opakování po
této opravě zůstává na uživateli.

### Ruční DELETE posádky a odpověď 400

Endpoint je implementovaný jako DELETE /api/v1/crews/{id}?version=N. Povinný
query parametr version je celé nezáporné číslo převzaté z aktuálního GET detailu.
Nevyžaduje ani nezpracovává Idempotency-Key a nečte JSON tělo. UUID hlavička tedy
nenahrazuje version; ani {"version":0} v těle nestačí. Chybějící query parametr
vyvolá MissingServletRequestParameterException před zavoláním metody controlleru
či mazací služby a ApiErrors.invalid vrátí 400 INVALID_REQUEST. Prázdná/nečíselná
verze také vrací 400, zápornou odmítne validace controlleru. Zastaralá platná verze
vrací 409 CREW_VERSION_CONFLICT. Vlastník nebo ADMIN musí být přihlášen; cookie
požadavek vyžaduje čerstvý CSRF. Smazání vrací 204 bez těla, další přístup 404.

V Postmanu:

1. Přihlas aktuálního vlastníka nebo ADMIN, nastav crewId na testovací posádku.
2. GET {{baseUrl}}/api/v1/crews/{{crewId}}; převezmi aktuální version do crewVersion.
3. DELETE {{baseUrl}}/api/v1/crews/{{crewId}}?version={{crewVersion}}, Body → none.
   Kolekce sama získá CSRF a zachová cookies. Content-Type ani Idempotency-Key
   pro toto mazání nepřidávej. Alternativně mobil používá pouze Bearer bez cookie.

Kolekce již obsahovala administrátorské mazání 3b2 25 (osiřelá posádka)
a 3b2 27 (původní testovací posádka), obě s query version. Přidána složka
08 — Optional crew deletion by owner or ADMIN s obnovením verze a mazáním.
Spouštět jednotlivě: smazání vybraného ID ovlivní další scénáře nad stejnou
posádkou; po smazání je potřeba pro nový scénář založit novou posádku novým klíčem.

Version je schválená technická ochrana souběhu, specifikace její transport
neurčuje. Idempotency-Key zůstává povinný pro vytvoření a změnu kapitána;
mazání nevytváří nový záznam ani nemění vlastnictví, klíč pro něj zaveden není.
Ochrany budoucích účastí a čekajících převodů zůstávají podle výše uvedených návazností.
Produkční kód se při této diagnostice neměnil. Přidány HTTP testy chybějící,
prázdné/nečíselné/záporné verze, úspěšného DELETE a konfliktu 409 a službový test,
že zastaralé mazání nesmaže posádku, fotografii ani audit. Postman je striktní
JSON podle oficiálního schématu v2.1: 62 požadavků, 97 syntakticky validních skriptů;
původní požadavky jsou zachované.

Ověření diagnostiky DELETE: cílených 21 testů prošlo; následné mvnw.cmd verify
(JDK 21) skončilo BUILD SUCCESS, 118 testů, 0 failures/errors/skipped a JAR
sestaven. PostgreSQL testy nebyly spouštěny, produkční API ani migrace se neměnily.

## Závěrečný stav před commitem

Kontrola Git změn: do výběru patří pouze implementace 3b.2, V3, její automatické
testy, dokumentace a Postman. Předchozí migrace a vydaná specifikace se nemění.
Dočasné soubory, target, test-env.ps1, fotografie a lokální přihlašovací údaje
se nezahrnují. Nesouvisející .gitignore a docs/Rallye_specifikace.md zůstávají mimo commit.

Konečné dostupné Surefire reporty: 118 testů, 0 failures/errors/skipped.
Failsafe summary i jednotlivé XML: 39 testů, 0 failures/errors/skipped.
DB běh je výsledek uživatelova prostředí; Codex PostgreSQL testy nespouštěl.
Tyto konečné výsledky nahrazují výše uvedené průběžné stavy čekání na ověření.

Finální Postman kolekce obsahuje 63 požadavků a 99 syntakticky ověřených skriptů,
prošla oficiálním schématem Collection v2.1. Složka 09 obsahuje samostatný požadavek
„3b2 Change captain of existing crew“ pro přihlášeného ADMIN: nastav crewId,
newCaptainAccountId a crewCaptainChangeVersion z GET detailu. crewCaptainChangeKey
se vygeneruje při prvním odeslání; pro replay se nemění klíč ani vstupní verze.
baseUrl a administrátorské přihlášení se nastavují stejně jako u ostatních požadavků.

Následuje 3b.3: potvrzované převody mezi uživateli, platnost 7 dní, zrušení/odmítnutí
a atomické zrušení čekajícího převodu při již existující administrátorské změně.
Historické účasti a jejich ochrany vzniknou až s modulem účastí; nejsou nahrazené
ručním příznakem v crew. Tato etapa jejich tabulky ani procesy nezavádí.