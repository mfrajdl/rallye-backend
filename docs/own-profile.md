# Změna zobrazovaného jména vlastního účtu

Nově je dostupný `PATCH /api/v1/auth/me`; původní `GET /api/v1/auth/me` zůstává beze změny. Nevyžaduje roli ADMIN ani konkrétního poskytovatele identity. LOCAL, GOOGLE i APPLE používají stejnou autentizovanou relaci.

## Kontrakt

Požadavek má `Content-Type: application/json` a obsahuje právě jediné pole:

```json
{"displayName":"Nové jméno"}
```

`displayName` je povinný neprázdný řetězec, nejvýše 100 UTF-16 jednotek stejně jako `@Size(max=100)` při lokální registraci. Limit platí před normalizací; krajní bílé znaky odstraní `String.strip()`. Jméno tvořené pouze bílými znaky se odmítá. Není to změna přihlašovací identity, e-mailu ani rolí.

Odpověď `200 OK` má stejný tvar jako GET:

```json
{
  "id":"00000000-0000-4000-8000-000000000001",
  "email":"person@example.test",
  "displayName":"Nové jméno",
  "roles":["USER"]
}
```

Pole `roles` obsahuje aktuální oprávnění. Identifikátor účtu se bere výhradně z autentizované relace. DTO odmítá další pole, včetně `id`, `accountId`, `email`, `roles`, `identityProvider` nebo `password`; není možné měnit cizí účet ani jiné atributy. Pro nové čtení profilu použijte existující GET.

Cookies vyžadují dosavadní CSRF postup: GET `/api/v1/auth/csrf`, zachovat cookies a pro PATCH poslat vrácený token pod vráceným `headerName`. Bearer klient používá dosavadní Authorization režim; výjimka z CSRF neplatí při přítomnosti session cookie. Bez platného přihlášení je odpověď `401`, chybějící/neplatný CSRF má `403 ACCESS_DENIED`, neplatné tělo/jméno nebo další pole mají `400 INVALID_REQUEST`. Chyby zachovávají dosavadní ProblemDetail formát.

## Konzistence a audit

Služba v jedné transakci získá existující zámky `admin_control -> account`, znovu ověří aktivní účet a konkrétní relaci, aktualizuje pouze `user_account.display_name` a `updated_at` a zapíše `PROFILE_NAME_CHANGED`. Audit obsahuje pouze identifikátory vlastního účtu jako actor a target; staré ani nové jméno se do auditu neukládá. Při selhání auditu se změna vrací zpět.

Relace, e-mail, role, heslo a řádek neměnné LOCAL/GOOGLE/APPLE identity se nemění. Nová migrace ani změna databázového schématu nejsou potřeba. Není potřeba nové přihlášení, heslo ani kontakt s poskytovatelem.

Souběžné zápisy se serializují existujícími databázovými zámky. U různých jmen platí poslední úspěšný zápis; profil nemá nový version ani If-Match kontrakt. Opakované uložení stejného normalizovaného jména je no-op: nemění updated_at a nezapisuje další audit. Operace nevyžaduje `Idempotency-Key`.

## Testy

- `ProfileHttpTest`: nepřihlášený/expirující Bearer, cookie CSRF, platné cookie/Bearer, aktualizovaný tvar profilu, chybějící/null/prázdné/dlouhé jméno a odmítnutí dalších polí.
- `ProfileServiceTest`: stejná logika pro LOCAL/GOOGLE/APPLE, normalizace a hranice délky, aktivní účet/relace, pořadí zámku a zápisu, no-op, zachování rolí a přihlašovacích prostředků, propagace chyby auditu.
- `ProfileIT`: šest PostgreSQL scénářů ve vlastním schématu `it_profile_<uuid>` přes `TestDatabaseInitializer`. PATCH a následný GET pro tři identity, nedotčená cizí identita/účet a relace, rollback při chybě auditu, souběžné stejné požadavky s jedním auditem a odmítnutí odvolané relace.

Běžné testy běží bez databáze:

```powershell
.\mvnw.cmd verify
```

Maven musí používat JDK 21 (ověřte `.\mvnw.cmd -version`). PostgreSQL sadu spouští výhradně uživatel se stávajícím testovacím opt-in a připojením k samostatné databázi a roli `rallye_test` podle `docs/testing.md`:

```powershell
.\mvnw.cmd -Pit verify
```

PostgreSQL testy při této změně Codex nespouštěl. Integrační funkčnost, rollback a souběh na skutečném PostgreSQL byly následně ověřeny uživatelem, viz závěrečný výsledek níže. Testy nepoužívají `rallye_dev` ani produkční databázi. Frontend nebyl v rámci této backendové úpravy měněn.

### Výsledek ověření 26. 10. 2026

Codex ověřil Maven Wrapper s Microsoft JDK 21.0.12.1 a spustil `mvnw.cmd verify`: **BUILD SUCCESS**, 427 testů bez databáze, 0 failures, 0 errors a 0 skipped; JAR sestaven. Z toho nové `ProfileHttpTest` a `ProfileServiceTest` obsahují celkem 32 úspěšných případů. `ProfileIT` se zkompiloval, ale šest integračních scénářů nebylo spuštěno. `git diff --check` prošel. Následné uživatelské ověření proti `rallye_test` je zaznamenáno níže.


### Oprava testu rollbacku auditu

PostgreSQL výpis `ProfileIT` doložil překlad injektované `IllegalStateException`
na `InvalidDataAccessApiUsageException` přes repository exception translation
(`EntityManagerFactoryUtils` / `PersistenceExceptionTranslationInterceptor`).
Původní výjimka zůstává příčinou; nejde o chybu produkční transakční hranice.
Test nyní kontroluje přesnou instanci kořenové příčiny a před selháním provede
skutečný auditní INSERT. Uvnitř transakce ověří změněné jméno, čas a audit,
po ukončení transakce původní `display_name`, `updated_at` a nepřítomnost auditu.
Produkční kód ani databázová pravidla se kvůli opravě nemění.
PostgreSQL opakování provede uživatel: `.\mvnw.cmd -Pit -Dit.test=ProfileIT verify`
(nebo kompletní `.\mvnw.cmd -Pit verify`). Oprava byla následně zahrnuta do uživatelem potvrzeného úspěšného úplného běhu.

Po opravě: Codex `verify` na JDK 21 – **BUILD SUCCESS**, 427 testů,
0 failures/errors/skipped; kompilace ProfileIT prošla. PostgreSQL nebylo
spouštěno. `git diff --check` prošel.

### Závěrečné PostgreSQL ověření

Uživatel potvrdil kompletní `.\mvnw.cmd -Pit verify` proti `rallye_test`: **BUILD SUCCESS**, včetně opraveného ProfileIT a ověření rollbacku. Codex PostgreSQL testy nespouštěl. Při závěrečném commitu se testy neopakovaly a implementace se neměnila.
