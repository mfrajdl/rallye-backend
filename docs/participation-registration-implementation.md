# Registrace posádek – implementace registration-api-v1

Implementace první etapy odpovídá schválenému kontraktu
[participation-registration-api.md](participation-registration-api.md) a
[OpenAPI 3.1](participation-registration-openapi.yaml). Kontrakty se nemění.
Schválená pravidla kontraktu mají přednost před staršími rozdíly specifikace 0.9.0.

## Rozsah

Pod `/api/v1/race-events/{eventId}` jsou implementovány:

| Metoda | Cesta | Oprávnění |
|---|---|---|
| GET | `/registration-status` | Aktivní ověřený účet; bezpečný souhrn a serverem určená otevřenost |
| PUT | `/registration-settings` | ADMIN; společná verze konání |
| GET | `/registration-crews` | Vlastní posádky, předvyplnění, stránkování |
| POST | `/registrations` | Současný kapitán; UUID Idempotency-Key; 201 + Location |
| GET | `/registrations` | scope=own; scope=all pouze ADMIN |
| GET | `/registrations/{registrationId}` | Současný kapitán nebo ADMIN |
| PUT | `/registrations/{registrationId}/members` | Současný kapitán; vlastní verze přihlášky |

Výchozí page=0, size=50, maximum size=100. Odpovědi mají `Cache-Control: no-store`.
ADMIN bez kapitánství nemůže měnit složení. Běžným účtům nejsou otevřeny ostatní
endpointy konání, CODE ani knihovna souborů. Cookie zápisy zachovávají CSRF;
Bearer používá stávající autentizaci.

Konec konání má přednost před OPEN/CLOSED, skutečným startem a intervalem
`[from,to)`. Ruční režim se změnou intervalu neruší. Uzavření registrací nebrání
úpravě existující přihlášky; brání jí individuální start nebo konec konání.
`scheduledStartAt` není skutečný start.

## V26 a trvalé údaje

`V26__crew_registrations.sql` přidává interval/override do `race_event` a tabulku
`crew_registration`. Existující data a V1–V25 se nemění. Výchozí režim je AUTO.
UNIQUE(event,crew), FK RESTRICT a indexy chrání jedinečnost, historii a stránkování.
Poslední registrace pro předvyplnění se načítá v jednom SQL dotazu, bez N+1.

Délka členů se kontroluje před normalizací jako 2000 Unicode code points.
Normalizují se jen CRLF/CR na LF. Java i SQL používají stejnou množinu bílých
znaků při počítání neprázdných řádků; NBSP není prázdný řádek. Počet 15+ je
odvozený. Registrační název posádky je neměnný. Budoucí individuální start má
připravené nullable sloupce a ochranu úplnosti/neměnnosti snapshotu; žádný API start nevzniká.

Pouhá registrace nezakládá historickou účast kapitána a nebrání fyzickému zrušení
účtu. Posádka osiří, přihláška a složení zůstávají. Budoucí start s historickým
kapitánem vede k zachování účtu anonymizací. Přesný migration guard rozpoznává
V26; neznámé budoucí verze nadále odmítá. Při implementaci skutečného lifecycle
se adaptér musí znovu prověřit a rozšířit o konkrétní známou migraci.

## Transakce a idempotence

Zámky: admin_control → účet → posádka → konání → přihláška → ledger/audit.
Nastavení sdílí verzi konání; složení má vlastní verzi a nemění posádku/konání.
No-op se správnou verzí nemění čas, verzi ani audit. Změna, audit a POST ledger
se potvrzují v jediné transakci.

POST používá CommandStore a 24hodinovou retenci. Aktuální kapitánství se ověří
i při replay; replay předchází kontrole otevřenosti a duplicity a vrací původní
id/verzi 0. Ledger obsahuje UUID, SHA-256 normalizovaného textu a počet dětí,
nikoli jména členů. related_account_ids obsahuje pouze aktéra. Existující
CommandCleanupStore používá rozšířený CommandAccounts; registrační UUID se
nezaměňuje za účet. Audit obsahuje jen UUID a verzi, nikoli členy.

## Ověření a hranice

Unit/HTTP testy pokrývají sedm endpointů, role, CSRF/Bearer, striktní JSON,
časové hranice, režimy, Unicode, replay a verze. `RegistrationIT` pokrývá
PostgreSQL ověření V25→V26, omezení, předvyplnění, kapitánství, cancellation,
anonymizace, souběhů a rollbacků v izolovaném schématu. Migrační regrese
EventsIT/SimulationIT používají jako neznámou následnou verzi V27.

Uživatelský běh `.\mvnw.cmd -Pit verify` z 30. 10. 2026 prošel. Existující reporty
dokládají 764 běžných a 446 integračních testů bez failures/errors/skipped,
včetně všech 25 RegistrationIT, PostgreSQL 17.11 a úspěšné V26 i upgradu V25→V26.
Codex tento databázový běh neprováděl. Společné přihlášené frontend–backend
ověření nebylo provedeno. Příkazy jsou v [testing.md](testing.md).

Neimplementuje se odhlášení, skutečný start/konec konání, zařízení, aktivační kódy
ani průchod stanovišti. DB dál připouští pouze PLANNED a `event.startedAt=null`.
Pravidla skutečného startu mají unit testy; nejde o hotový produkční lifecycle.
