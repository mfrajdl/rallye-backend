# Etapa 3b.5 – export a automatická retence auditu

Implementace nahrazuje původní ruční archivaci a mazání bezpečnostního auditu podle dodatečně schválených pravidel specifikace 0.6.0. Uživatel potvrdil původní PostgreSQL běh nad rallye_test i ruční CSV export, filtrování, vznik AUDIT_EXPORT a zamítnutí běžného uživatele. Následnou cílenou opravu P2 uživatel také ověřil úspěšným `.\mvnw.cmd -Pit verify` nad rallye_test; následná 3b.6 je nyní uzavřena v [review-3b6.md](review-3b6.md), implementace 3c nezačala.

## CSV API

`GET /api/v1/admin/audit-events/export` vyžaduje platnou relaci a aktuální roli ADMIN aktivního ověřeného účtu. Podporuje cookie i Bearer autentizaci. Jde o GET bez CSRF tokenu a Idempotency-Key. Stávající stránkované čtení auditu se nemění.

Volitelné query parametry:

| Parametr | Význam |
| --- | --- |
| `from` | ISO okamžik s offsetem, dolní hranice včetně. |
| `until` | ISO okamžik s offsetem, horní hranice bez ní; musí být později než from. |
| `eventType` | Existující enum auditní události, také AUDIT_EXPORT a AUDIT_CLEANUP. |
| `actorId` | UUID aktéra. |
| `targetAccountId` | UUID cíle pouze u target_type ACCOUNT; nezaměňuje ID posádky nebo převodu za účet. |

Úspěch: 200, `text/csv;charset=UTF-8`, příloha `rallye-audit.csv`, `Cache-Control: no-store`. Chyby: 400 neplatný parametr / INVALID_AUDIT_FILTER pro interval, 401 neplatná relace, 403 ADMIN_REQUIRED. Selhání po zahájení streamu může způsobit neúplný soubor; již odeslanou odpověď nelze změnit na JSON chybu.

Export obsahuje všechny odpovídající řádky v pořadí occurred_at a UUID vzestupně. Sloupce: id, occurredAt, eventType, actorKind, actorId, targetType, targetId, outcome, previousCaptainId, newCaptainId, crewId, transferId, resolutionReason, metadata. Outcome se odvozuje u zrušení účtu; jinak je prázdný. Neexistující údaje se nevymýšlejí.

CSV používá UTF-8 BOM, středník, uvozované buňky a CRLF. Uvozovky se zdvojují; nové řádky v buňkách zůstávají zachovány. Potenciální vzorce a úvodní řídicí znaky jsou chráněny apostrofem, původní obsah se nezahazuje. Časy včetně časových hodnot strukturovaných metadat se zobrazují v Europe/Prague s konkrétním UTC offsetem; CET/CEST tak lze rozlišit. Metadata jsou JSON v jediné CSV buňce. Hesla, autentizační tokeny ani jejich hashe nejsou součástí auditních dat.

## Transakce a zdroje

Autorizace proběhne před vytvořením odpovědi a znovu ve streamovacím vlákně. To otevře samostatnou read-only REPEATABLE_READ transakci. JDBC forward-only kurzor s fetch size 1000 průběžně zapisuje do odpovědi bez načtení celého exportu do paměti a bez dočasného souboru. ResultSet, statement a transakce se uzavírají i při chybě zápisu či odpojení klienta. Servletový výstup vlastní Spring. Snapshot nedrží admin_control a může pokračovat přes souběžný úklid; změny po začátku snapshotu nevidí.

Po dokončení generování, flush a uzavření čtecí transakce vzniká v nové zapisovací transakci AUDIT_EXPORT s aktérem, filtry a počtem řádků. Selhání je best effort: bezpečná diagnostika bez citlivých zpráv výjimek, CSV zůstává úspěšné. Událost není součástí vlastního exportu. Nejde o potvrzení, že klient soubor skutečně uložil. Běžné prodlužování session autentizačním filtrem zůstává zachováno; read-only je exportní transakce.

## Retence a konfigurace

| Vlastnost / proměnná prostředí | Výchozí hodnota |
| --- | --- |
| `rallye.audit.retention-days` / `RALLYE_AUDIT_RETENTION_DAYS` | 365 |
| `rallye.audit.cleanup-enabled` / `RALLYE_AUDIT_CLEANUP_ENABLED` | true |

Minimum je 90 dní. Neplatná hodnota způsobí chybu konfigurace a nesmí povolit úklid. Den znamená 24 hodin; cutoff je UTC now minus retence. Mažou se pouze záznamy s occurred_at přísně před cutoff, přesná hranice zůstává. Týká se všech typů v audit_event včetně starších AUDIT_EXPORT/AUDIT_CLEANUP. Historie posádek, převodů a závodů se nemaže.

Plánovač běží denně v 03:15 Europe/Prague. PostgreSQL transakční advisory lock koordinuje instance. audit_cleanup_control uchovává pouze poslední úspěšný místní den a brání duplicitnímu úspěšnému dennímu běhu. Neúspěšné pokusy se trvale neevidují. Není opakovací smyčka ani dohánění po startu aplikace. Při selhání může další samostatně vyvolaný plánovaný běh zkusit úklid znovu; evidence neúspěchu jej neblokuje.

Před výmazem se zamkne admin_control a ověří všechny idempotentní záznamy, včetně expirovaných a záznamů jiných aktérů. Odvoditelné legacy vazby známých formátů se doplní do related_account_ids. Legacy CREW_CAPTAIN bez těchto metadat však úklid vždy zastaví: payload neobsahuje předchozího kapitána a audit nemá klíč příkazu ani verzi posádky. Čas auditu a příkazu se pořizuje samostatně. Ani jediná zdánlivě odpovídající událost proto není průkaznou vazbou; žádné párování podle podobnosti se neprovádí. Úplná metadata ukládaná novým příkazem transakčně včetně předchozího kapitána zůstávají použitelná i po odstranění auditu. Neznámý formát nebo neověřitelný důkaz znamená bezpečné selhání a rollback, nikoli odhad nebo smazání evidence. Doplnění vazeb, výmaz, systémový AUDIT_CLEANUP s cutoff a počtem a záznam úspěšného dne jsou atomické. Chyba auditu vrací všechny změny zpět a bezpečně se loguje.

Oprava P2 mění pouze certifikaci vazeb před retenčním odstraněním auditu. Dosavadní konzervativní vyhledání potenciálně souvisejících příkazů při rušení účtu zůstává zachováno: může využít historii stejné posádky, audit samotný nemaže a takto odhadnuté vazby nepersistuje jako ověřená metadata. Existující legacy CREW_CAPTAIN bez metadat bude blokovat další retenční běhy až do individuálního vyřešení z průkazného zdroje. Samotné uplynutí 24hodinové platnosti příkazu tuto ochranu neobchází; oprava žádné záznamy automaticky nemaže ani nenabízí neověřený SQL backfill.

V7 přidává JSONB metadata a singleton audit_cleanup_control. V1–V6 se nemění. Adaptér účastí výslovně přijímá V7 pouze pod očekávaným názvem migrace a nadále odmítá neznámé budoucí verze. Žádné ruční retenční endpointy, archivace ani dvoukrokové mazání nejsou implementované.

## Ověření

Před opravou P2 prošlo 232 testů bez DB a uživatel potvrdil úspěšný `.\mvnw.cmd -Pit verify` nad rallye_test včetně původních 14 scénářů AuditMaintenanceIT. Jde o uživatelem potvrzený výsledek, nikoli vlastní PostgreSQL běh Codexu; celkový počet IT nebyl uveden. Po opravě Codex spustil verify s JDK 21: BUILD SUCCESS, 234 testů bez DB, žádné chyby ani přeskočené testy. Je připraveno 17 scénářů AuditMaintenanceIT: navíc chybějící vlastní audit při existenci jiné změny téže posádky, odmítnutí zdánlivě odpovídající historie bez korelačního ID a funkčnost úplných metadat bez auditu. Pozitivní retenční testy používají metadata skutečně uložená pomocí save a relate; rollback po chybě auditu ověřuje také odvoditelný legacy ADMIN_ROLE backfill. Uživatel následně potvrdil úspěšné `.\mvnw.cmd -Pit verify` nad rallye_test také po opravě P2, včetně upravené sady 17 scénářů AuditMaintenanceIT. Jde o uživatelem potvrzené integrační ověření, nikoli běh Codexu. Scénáře nadále pokrývají kurzor nad více než 2000 řádky, snapshot přes souběžný úklid, filtry, role/session, hranici retence, koordinaci, rollback, exportní chyby, V7 a kontrolu budoucího schématu.

Uživatel spustí výhradně nad rallye_test podle testing.md:

```powershell
.\mvnw.cmd -Pit verify
```

Testovací profil vypíná skutečný časový plánovač (`rallye.audit.cleanup-enabled=false`); IT volají službu přímo v izolovaném schématu. Bezpečnostní kontroly TestDatabaseInitializer zůstávají platné. Provozní ani vývojová DB se nepoužívá.

V Postmanu importujte aktuální kolekci a složku `14 Audit export 3b.5`. Přihlaste ADMIN, spusťte export všech událostí (pro uložení Send and Download). U filtrovaného požadavku nastavte auditExportFrom/Until/EventType/ActorId/TargetAccountId a zapněte pouze vyplněné query parametry. Ověřte prázdný i neprázdný výsledek, audit následného exportu, běžného uživatele (403), neplatné UUID a interval (400). Denní retenční běh nemá veřejný testovací endpoint.

Provozní omezení: dlouhý export drží snapshot a databázové připojení; velikost poolu a HTTP/proxy timeouty je potřeba zohlednit před nasazením. Bez dokončeného legacy backfillu se úklid zastaví a vyžaduje diagnostiku. Retence není záloha; před nasazením musí být její záměrné automatické mazání provozně akceptováno.
