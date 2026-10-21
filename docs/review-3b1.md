# Závěrečná kontrola etapy 3b.1

Kontrola 21. 10. 2026: implementovaný rozsah odpovídá specifikaci 0.6.0 a plánu
3b.1. CSV, archivace a mazání auditu zůstávají v 3b.5. Posádky a rušení účtů
nejsou součástí tohoto kroku. Etapa je dokončena; uživatel potvrdil integrační BUILD SUCCESS i po poslední opravě.

## Opravy při kontrole

- AdminStore.lock vyžaduje právě jeden řídicí řádek; chybějící singleton již
  neumožní službě pokračovat bez společného zámku.
- Bootstrap odmítne existující ADMIN s bootstrapped=false, místo tichého návratu.
  Nekonzistenci automaticky neopravuje. Normální bootstrap nastaví příznak
  triggerem ve stejné transakci jako roli a audit. Opakování stejného platného
  bootstrapu je beze změny, jiný účet je odmítnut; chybějící řádek odmítne INTO STRICT.
- Nový AdminStoreTest a dva AdministrationIT případy kryjí poškozený řídicí stav.
  V2 ani jiné již aplikované migrace se neměnily.

## Pokrytí a výsledky

- Vlastní běh Codexu: JDK 21.0.12.1, mvnw.cmd verify, BUILD SUCCESS,
  90 testů, 0 failures/errors/skipped, sestavený JAR a kompilace všech IT.
- Dosavadní Failsafe XML reporty: 24/24 úspěšných IT (AdministrationIT 11,
  IdentityIT 11, LocalMailboxIT 1, RallyeBackendApplicationIT 1). Jde o výsledky
  nalezené v pracovním stromu před poslední opravou, nikoliv nový běh Codexu.
- Aktuální sada: 26 IT; uživatel po posledních změnách spustil `.\mvnw.cmd -Pit verify` nad rallye_test a potvrdil BUILD SUCCESS. Tento DB běh provedl uživatel, nikoli Codex.
- Uživatel potvrdil Postman: login/audit 200, grant/revoke a replay 204,
  zákaz vlastní role 403, konflikt klíče 409, audit bootstrapu/loginu/změn rolí.
- Testy pokrývají autentizaci a aktuální role z DB, zákaz vlastní změny,
  neaktivní/neověřené cíle, HTTP validaci a reálný CSRF cookie tok, auditní filtry,
  souběžný bootstrap a stejný klíč, vzájemné odebírání rolí, ochranu posledního
  ADMIN a idempotentní replay/konflikt/expiraci. Rollback testy kontrolují stav
  v nové transakci po reálných zápisech včetně auditu a idempotence; kryjí také
  login, logout, změnu a reset hesla. Bootstrap audit failure vrací zpět příznak i roli.

Příkaz úspěšného ověření v uživatelově PowerShellu s RALLYE_TEST_DB_* a tunelem 5434:

```powershell
.\mvnw.cmd -Pit verify
```

Ověřená sada: 90 běžných a 26 integračních testů bez selhání. Žádná náhrada testovací
DB vývojovou, žádný clean ani přepis migrací. Podle AGENTS.md databázový běh
provádí uživatel. Výsledek nového běhu potvrdil jako BUILD SUCCESS.

## Omezení

Společný řádkový zámek serializuje administrativní služby včetně čtení auditu.
Souběh je testován v běžné nakonfigurované izolaci; další izolační úrovně nejsou
samostatně ověřené. Schéma nemůže chránit před privilegovaným DB správcem, který
změní triggery či řídicí data. Skutečné SMTP doručování zůstává před nasazením.

## Soubory kandidující do commitu 3b.1

Seznam zahrnuje související dřívější změny i závěrečné opravy. Zahrnuje také
schválenou specifikaci 0.6.0 jako závazný podklad implementace. Push není součástí uzavření.

- `AGENTS.md`
- `docs/Zavody_specifikace_v0.6.0.md`
- `docs/administration-3b1.md`
- `docs/backend-plan.md`
- `docs/testing.md`
- `ops/admin/bootstrap-first-admin.sql`
- `postman/Rallye-Auth-2A.postman_collection.json`
- `README.md`
- `src/main/java/cz/mh/rallye/rallye_backend/administration/api/AdminController.java`
- `src/main/java/cz/mh/rallye/rallye_backend/audit/application/AuditService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/audit/persistence/AuditStore.java`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/api/ApiErrors.java`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/api/AuthConfiguration.java`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/api/AuthSessionFilter.java`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/application/AdminService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/application/AuthService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/AccountStore.java`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/AdminStore.java`
- `src/main/resources/db/migration/V2__administration_and_audit.sql`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/AdminHttpTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/AdministrationIT.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/AdminServiceTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/AdminStoreTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/AuditTransactionTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/AuthServiceTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/BootstrapScriptTest.java`
- `docs/review-3b1.md`

Mimo výběr: rozpracovaný docs/Rallye_specifikace.md (původně měl staged i pracovní
změny) a předchozí změna .gitignore. Jejich obsah zůstává zachovaný; rozpracovaná
specifikace je výslovně vyřazena ze stagingu a nepatří do commitu. Starší vydání
specifikace se nemění. Schválená specifikace 0.6.0 je součástí dokumentace 3b.1,
aby odkazy z AGENTS.md a plánu nevedly na soubor chybějící v repozitáři.