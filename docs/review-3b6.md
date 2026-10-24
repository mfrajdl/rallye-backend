# Závěrečná kontrola 3b.6 – uzavření bloku 3b

Stav k 24. 10. 2026: etapy 3b.6a–d jsou dokončené a blok 3b je funkčně a testově uzavřený ve schváleném rozsahu. Připraveno k samostatnému schválení commitu a pushe; ani jedna operace zatím neproběhla. Následuje návrh/realizace 3c, nikoli její implementace v tomto kroku.

## Stav částí

| Část | Výsledek | Důkaz |
| --- | --- | --- |
| 3b.6a – pokrytí | Dokončeno | coverage-3b6.md mapuje funkční pravidla 2A a 3b.1–3b.5 na U/H/PG testy; 75 konkrétních odkazů na metody bylo ověřeno vůči zdrojům |
| 3b.6b – regrese | Dokončeno a ověřeno | Posílen existující test bezpečného logování neplatného přihlášení; čtyři nové Stage3bRegressionIT. Uživatel potvrdil PostgreSQL BUILD SUCCESS |
| 3b.6c – Postman | Dokončeno, šest scénářů PROŠEL | Uživatel potvrdil ruční průchod proti rallye_dev; přesný rozsah v postman-3b6.md §10 |
| 3b.6d – konsolidace | Dokončeno | Opakované verify, validace kolekce, kontrola Git diffu, aktuální souhrny dokumentace a explicitní oddělení budoucích návazností |

## Automatické ověření

Codex při závěrečné kontrole skutečně spustil Maven Wrapper s Microsoft JDK 21.0.12.1:

```powershell
.\mvnw.cmd verify
```

Procesu byly pouze dočasně omezeny prostředky JVM (Maven Xms16m/Xmx192m, testovací JVM Xms16m/Xmx384m, ActiveProcessorCount=2), stejně jako při předchozích ověřeních na tomto Windows. Produkční konfigurace ani testovací pravidla se tím nemění.

Výsledek: **BUILD SUCCESS, 234 testů, 0 failures, 0 errors, 0 skipped; JAR sestaven**. Nová integrační třída se také kompiluje, ale bez profilu it se PostgreSQL testy nespouštějí. Závěrečný běh je zaznamenán v target/verify-3b6d.log; target není verzovaný artefakt a není součástí připravovaného commitu.

**Nový PostgreSQL běh Codex neprovedl.** Podle AGENTS.md jej spouští uživatel pouze proti rallye_test. Úspěch integrační části není odvozován z běžného verify: vychází z dřívějšího explicitního potvrzení uživatele po změnách 3b.6b a z dostupných reportů v target/failsafe-reports. Souhrn uvádí completed=133, errors=0, failures=0, skipped=0, timeout=false; součet deseti XML reportů je rovněž 133:

| PostgreSQL třída | Úspěšných testů |
| --- | ---: |
| AccountCancellationIT | 18 |
| AccountCancellationServiceIT | 21 |
| AdministrationIT | 13 |
| AuditMaintenanceIT | 17 |
| CrewsIT | 13 |
| CrewTransfersIT | 34 |
| IdentityIT | 11 |
| LocalMailboxIT | 1 |
| Stage3bRegressionIT | 4 |
| RallyeBackendApplicationIT | 1 |
| Celkem | 133 |

PostgreSQL data ani schéma nebyly při závěrečné kontrole Codexem měněny. Ruční Postman průchod proti rallye_dev se nesmí zaměnit s automatickými IT proti rallye_test.

Kolekce: Node.js JSON.parse a PowerShell ConvertFrom-Json, oficiální JSON schema Postman Collection v2.1, **95 requestů / 157 skriptů**, validní syntaxe skriptů a JSON těl. Synteticky bez sítě ověřeny výchozí CSRF větev, Bearer tokeny A/B bez fallbacku, odmítnutí chybějícího tokenu a explicitní Cookie hlavičky, login/uložení tokenu pouze do environmentu, zákaz jeho přepsání a devět opravených UUID generátorů. Nejde o nové spuštění requestů proti aplikaci.

## Manuální ověření a návaznost

Uživatel potvrdil všech šest scénářů proti rallye_dev: ochranu cizích posádek a fotografií, přijetí převodu v původních relacích, zneplatnění žádosti při ADMIN změně, okamžité odebrání administrace, zrušení účtu a opětovnou registraci stejného e-mailu bez vlastnictví původní posádky. Stav původní posádky s kapitánem NULL byl ověřen i v databázi. Přesné statusy a rozsah jsou v postman-3b6.md, bez hesel a tokenů.

Dočasné konfigurace Cookie Jar, cookie/bearer režimu a starého tokenu byly uživatelem opraveny. Následný úspěch sám o sobě není podkladem pro tvrzení backendové chyby. Žádný nový backendový defekt nebyl prokázán; produkční oprava nebyla potřebná.

Nebylo hlášeno samostatné provedení každého rozšířeného/volitelného kroku návodu: například dvou relací rušeného účtu, obou směrů převodů, všech SELECT dotazů nebo porovnání starého/nového UUID. Tyto záruky jsou pokryté automatickou sadou (IdentityIT, AccountCancellationServiceIT, CrewsIT a Stage3bRegressionIT). Výsledky neoznačujeme jako úplný ruční průchod celé kolekce. Souběhy a rollbacky dokládají PostgreSQL testy, ne manuální načasování requestů.

## Kontrola konzistence a provedené úpravy

- Aktuální souhrn plánu už neuvádí neimplementované posádky/rušení. Dokončené 3b.1–3b.6 jsou odlišené od plánované 3c a budoucích závodních modulů.
- Popsaná perzistence odpovídá JDBC; JPA zůstává závislostí, bez automatického DDL. Migrace V1–V7 se v 3b.6 nepřepisovaly ani nepřidávaly.
- Adaptér předzávodních účastí je dokumentován pro přesné V1–V6 nebo schválenou V7. Neznámé budoucí schéma nadále selhává bezpečně; nejde o automatické povolování vyšších verzí.
- Staré záznamy testů zůstaly zachované a označené jako historické. Pozdější závěrečný výsledek má samostatný aktuální souhrn.
- Specifikace 0.6.0 má u technických bodů 14/10–11 doplněn stav již implementovaného řešení. Nemění se její verze ani funkční pravidla. Starší vydání a pracovní Rallye_specifikace.md zůstaly nedotčené.
- AGENTS.md a starší dokumentace administrace odkazují na skutečně schválenou automatickou retenci 3b.5, nikoli na již zrušenou ruční archivaci.
- Při přípravě 3b.6c bylo opraveno devět chybných dynamických proměnných `{$guid}` → `{{$guid}}` v Postman skriptech. Přibyla opt-in izolace Bearer relací a tři chybějící request varianty; stávající webový cookie/CSRF režim zůstal zachován.
- Zkontrolován git status, diff, diff --check a zachování hashů dvou uživatelských souborů. Žádné dočasné logy, lokální konfigurace ani vyplněné autentizační údaje se nezahrnují mezi soubory etapy.

## Soubory etapy 3b.6 připravené ke schválení

1. AGENTS.md
2. README.md
3. docs/Zavody_specifikace_v0.6.0.md
4. docs/account-cancellation-3b4b.md
5. docs/administration-3b1.md
6. docs/audit-3b5.md
7. docs/backend-plan.md
8. docs/testing.md
9. docs/coverage-3b6.md
10. docs/postman-3b6.md
11. docs/review-3b6.md
12. postman/Rallye-Auth-2A.postman_collection.json
13. src/test/java/cz/mh/rallye/rallye_backend/identity/AuthServiceTest.java
14. src/test/java/cz/mh/rallye/rallye_backend/identity/Stage3bRegressionIT.java

Jde o kumulativní změny celé 3b.6a–d, nikoli jen dnešní dokumentační konsolidaci. Změny testů a kolekce vznikly již v předchozích částech. Uživatelské `.gitignore` a `docs/Rallye_specifikace.md` zůstávají v pracovním stromu beze změny a musejí být z případného budoucího commitu výslovně vynechány. Index zůstává prázdný.

## Zbývající omezení

- Skutečné SMTP doručování a výběr poskytovatele před nasazením. Současná schránka je pouze lokální vývojový režim; doručení po commitu není durable outbox.
- Limity pokusů jsou v paměti jedné instance; CSV drží DB snapshot a připojení. Před nasazením zohlednit pool, timeouty a zálohy; současné testy nejsou penetrační či zátěžový audit.
- Neověřitelný legacy CREW_CAPTAIN bez metadat bezpečně blokuje retenci i po expiraci. Individuální řešení musí mít průkazný zdroj, nikoli odhad nebo automatický výmaz.
- Bez skutečného závodního modelu nelze tvrdit end-to-end ověření historie účastí, zákazů rušení během závodu či ochrany historické posádky. Testovací fakta pokrývají větvení služby, budoucí závodní migrace musí dodat skutečný adaptér i ochrany smazání posádky.
- Google/Apple, propojení identit a změna e-mailu zůstávají mimo uzavřený blok. Budoucí otázky kapitoly 14 specifikace nezakládají blokaci současné 3b.6.

Žádný zbývající potvrzený problém neblokuje schválený rozsah 3b.6. **Doporučení: připraveno ke commitu a následnému pushi po výslovném souhlasu uživatele.** Tento report není oprávněním tyto operace ani nasazení provést.
