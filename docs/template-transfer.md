# Přenositelná šablona závodu – ZIP v1

Implementovaný backendový kontrakt. Zdroj instrukcí je výhradně Markdown;
HTML se po importu znovu bezpečně renderuje. Všichni aktuální ADMIN mají stejné
oprávnění ke všem šablonám podle specifikace 0.9.0 §3.1. Šablona nemá vlastníka;
importující se zaznamenává v auditu, žádná role ani účet se z archivu nepřenáší.

## API

Základ `/api/v1/race-templates`, stejné ověřování relací jako ostatní šablonové API.

| Metoda a cesta | Vstup | Odpověď |
|---|---|---|
| GET `/{id}/export` | ID existující šablony | 200 application/zip, attachment rallye-template.zip |
| POST `/imports/preview` | multipart část `file` obsahující ZIP | 200 Preview; žádné trvalé objekty |
| POST `/imports` | stejný ZIP v multipart `file`, UUID Idempotency-Key | 201 Created + Location nové šablony |

Všechny endpointy vyžadují platnou relaci a aktuální roli ADMIN. Cookie POST
vyžaduje CSRF; Bearer používá dosavadní výjimku. Odpovědi mají no-store; ZIP
navíc attachment, nosniff a no-referrer. Export obsahuje otevřené CODE odpovědi:
ukládejte jej jako soukromý administrační soubor, nikoli veřejný obsah.

Preview:

```json
{"name":"Podzimní projížďka","stageCount":1,"fileCount":1,"warnings":["IMPORTED_AS_DRAFT"]}
```

Varování: IMPORTED_AS_DRAFT vždy, NO_STAGES při prázdné trase, NOT_READY pro
neúplnou DRAFT konfiguraci. Chybějící či neplatné soubory/odkazy nejsou varování:
archiv je odmítnut. Náhled nic nerezervuje a nevytváří token ani trvalé úložiště.
Frontend při potvrzení odešle tentýž soubor znovu; server jej plně znovu ověří.

Created:

```json
{"id":"11111111-1111-4111-8111-111111111111","templateId":"11111111-1111-4111-8111-111111111111","version":0}
```

Nová šablona je vždy DRAFT, i pro zdrojovou READY; její obsah lze standardně
upravit, ověřit a publikovat. Import nikdy nepřepisuje existující šablonu.
Stejný ZIP a stejný klíč vrací stejný výsledek po dobu 24 hodin. Tentýž klíč
s odlišnými bajty ZIP nebo jinou operací vrací 409 IDEMPOTENCY_CONFLICT.
Nový klíč znamená nový nezávislý import i pro shodný obsah. Do CommandStore jde
pouze SHA-256 celého archivu a Created, nikoli definice, CODE ani soubory.

## ZIP a manifest

ZIP obsahuje přesně manifest.json, template.json a položky files/{fileName}.
Adresářové položky se nepoužívají. Standardní single-disk ZIP, UTF-8 názvy;
ZIP64, šifrování a komentář archivu nejsou ve v1 podporovány. Komprese DEFLATE
nebo STORED, jedinečné názvy. Central directory, počty, názvy, CRC a velikosti
musí souhlasit se skutečnými položkami; useknutý archiv je neplatný.

manifest.json:

```json
{
 "format":"rallye-template",
 "formatVersion":1,
 "exportedAt":"2026-10-29T18:00:00Z",
 "files":[{"fileName":"pravidla.md","contentType":"text/markdown","byteSize":9,
 "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}]
}
```

Checksum v příkladu je pouze ukázka formátu. SHA-256 je lowercase hex skutečných
bajtů položky files/{fileName}; byteSize je jejich délka. exportedAt je UTC Instant.
Žádná původní ID nejsou potřebná ani exportovaná. Manifest neobsahuje účet,
oprávnění, URL instalace či metadata skutečných Akcí a výsledků.

## template.json

Všechna pole uvedená níže jsou povinná; nepovinné hodnoty mají explicitní null.
Neznámá pole, duplicitní JSON klíče, převody string→number/boolean a desetinná
čísla místo int64 se odmítají. Všechna celá čísla musí být v příslušném rozsahu.
Klienti nesmějí zaokrouhlovat int64 přes JavaScript Number.

```json
{
 "name":"Podzimní projížďka",
 "description":null,
 "skipPenaltyPoints":2,
 "defaultWrongCodePenaltyPoints":3,
 "stages":[{
  "sequenceNo":1,"name":"Most","expectedDurationMs":60000,
  "maxPenaltyPoints":10,
  "instructionsMarkdown":"# Most\n\n[Pravidla](pravidla.md)",
  "configuration":{
   "gps":{"enabled":false,"latitude":null,"longitude":null,"radiusMeters":null,
    "outsidePenaltyPoints":null,"spoofingPenaltyPoints":null},
   "code":{"enabled":true,"value":"MOST","wrongAttemptPenaltyPoints":null,"valueUnavailable":false},
   "timePenalty":{"enabled":true,"intervalMs":60000,"pointsPerInterval":3},
   "delayed":{"enabled":false,"minimumDurationMs":null,"pointsPerSecond":null}
  }
 }]
}
```

Pořadí je souvislé od 1 a odpovídá pořadí pole. Všechny současné konfigurační
skupiny a jejich hodnoty, i vypnuté uložené hodnoty, se přenášejí. Samostatný
station type v aktuálním modelu není: ověřování určuje enabled GPS/CODE,
kombinace je AND. Časy jsou ms; bodové sazby podepsaný int64, maxPenaltyPoints
je nezáporné/null. Importovaná šablona začíná s technickou version=0.
Statistické verze stanovišť byly zrušeny ve 4b (V17 a specifikace 0.9.0 §6);
archiv je neobsahuje ani je znovu nezavádí. Neznámá pole archiv odmítá.
Hash-only CODE bez dostupné odpovědi nelze bezeztrátově přenést a export se odmítá;
valueUnavailable musí být v přenosovém formátu false. Kvízové parametry model
neobsahuje, formát si je nevymýšlí.

DRAFT může zůstat neúplný (např. null jméno stanoviště/instrukce a prázdná
konfigurace), pokud splňuje současné validační/DB podmínky. Import upozorní
NOT_READY; nevytváří neúplnou READY šablonu. Root name je povinný do 100 znaků,
description do 2000, jméno stanoviště null nebo neprázdné do 100 znaků.

Formát tvoří samostatná přenosová DTO, nikoli serializace tabulek či REST detailu.
Změna jejich významu/struktury vyžaduje explicitní podporu nové formatVersion;
server v1 jiné verze odmítá. Budoucí rozšíření nelze tiše zahodit.

## Výběr souborů a odkazy

Export začíná skutečnými odkazy ve stanovištích (včetně ověření explicitních
image vazeb) a prochází tranzitivně odkazované .md dokumenty. Cykly jsou povoleny,
soubor se přidá jednou. Nedosažitelné soubory z knihovny se neexportují.

Explicitní relativně absolutní API cesty zdrojové knihovny v Markdown odkazech,
obrázcích a referenčních definicích se převedou na URL-encoded relativní názvy
s uchovaným fragmentem. Původní relativní odkazy se nemění. Literální text/kódové
bloky nejsou aktivní odkazy a nepřepisují se. Tím samostatný ZIP nevyžaduje znalost
zdrojových ID či domény. Absolutní HTTP odkazy na interní /api/v1/ jsou odmítnuty
jako nepřenositelné; běžné externí HTTP/HTTPS/mailto/tel odkazy se zachovávají.
Importované knihovně parser/renderer automaticky přiřadí nové templateId.

Binární soubory mají po importu přesně stejné bajty jako v ZIP, včetně JPEG
bez další ztrátové rekomprese. Obsah se skutečně validuje/dekóduje. ZIP přijímá
normalizované PNG/JPEG bez EXIF/textových metadat a příloh; cizí metadata se
odmítnou namísto skrytého odstranění. Soubory současného exportu vznikly již
validovaným uploadem. PDF se validuje PDFBoxem a nadále poskytuje jako attachment;
Markdown vyžaduje platné UTF-8 a stávající bezpečné odkazy. Raw HTML v Markdownu
zůstává neaktivní text, HTML render prochází existujícím allowlistem.

## Limity a ochrany

- ZIP do 50 MiB; HTTP multipart limit projektu 50 MiB/file a 51 MiB/request.
- Celkem do 100 MiB rozbalených dat včetně JSON; každý JSON do 4 MiB.
- Nejvýše 202 položek: 2 JSON + 200 souborů; nejvýše 500 stanovišť.
- Obrázky do 5 MiB, 4096×4096; MD/PDF podle současné konfigurace
  (výchozí 100 KiB / 10 MiB, maximálně 1 MiB / 50 MiB).
- Instrukce stanoviště do 100 KiB. JSON hloubka nejvýše 30.
- Jeden normalizovaný název bez cest; NFC a case-insensitive kolize se odmítají.
- Žádná extrakce do cest z archivu, symlinky se nevytvářejí; zápisy pouze pod
  novým UUID šablony s existující kontrolou adresářů a CREATE_NEW.
- Rozbalování je měřené průběžně, nikoli podle důvěry v deklarovanou ZIP velikost.
  I vysoce komprimovaný vstup je omezen rozbaleným limitem.

Platná šablona přesahující přenosové limity se odmítne; nic se potichu nevynechá.

## Transakce, audit a kompenzace

Export má storage lock a zámek šablony, takže obsah i soubory tvoří konzistentní
snapshot. Nejdříve se sestaví a ověří celý ZIP, teprve potom se vrací klientovi;
nevzniká zdánlivě platná neúplná stažená odpověď. TEMPLATE_EXPORTED je auditován
bez obsahu. Zdroj se nemění, jeho version také ne.

Import před DB zápisy ověří celý obsah; potom v jediné transakci pod pořadím
storage → admin_control/účet → nová šablona vytvoří šablonu s počáteční technickou verzí, stanoviště,
soubory a vazby, TEMPLATE_IMPORTED a idempotentní výsledek. Všechna interní
ID jsou nová UUID. Při rollbacku se odstraní pouze vlastní nově zapsané soubory.
Při neurčitém výsledku commitu se ověřuje DB reference; nedostupnost DB nebo selhání
unlink nesmí odstranit potenciálně platný obsah a může vyžadovat stávající ruční
orphan úklid. To je provozní omezení souborového úložiště, nikoli částečný import.

Duplikace nadále používá své existující DB kopírování bez ZIP. Přenos sdílí
validace StageConfiguration, ImageNames, TemplateFileContent, Markdown parser,
reference, úložiště, zámky, CommandStore a repository. Žádný rozsáhlý refaktoring
ani nové tabulky/migrace nejsou potřeba.

## Chyby

Standardní bezpečný ProblemDetail. 401 bez relace, 403 bez ADMIN/CSRF,
404 TEMPLATE_NOT_FOUND, 400 TEMPLATE_ARCHIVE_INVALID nebo
TEMPLATE_ARCHIVE_VERSION_UNSUPPORTED, 413 TEMPLATE_ARCHIVE_TOO_LARGE,
409 IDEMPOTENCY_CONFLICT/TEMPLATE_EXPORT_CODE_UNAVAILABLE/
TEMPLATE_EXPORT_FILE_MISSING, 503 TEMPLATE_EXPORT_FILE_UNAVAILABLE nebo
TEMPLATE_EXPORT_INCONSISTENT. Podrobné validační chyby souborů používají
existující FILE_* / INVALID_FILE / TEMPLATE_FILE_REFERENCE_INVALID kódy.
Chyba neobsahuje CODE, autentizační údaje ani nekontrolovaný JSON/ZIP obsah.

## Ověření

Unit a HTTP testy nevyžadují PostgreSQL. Připravené TemplatesIT navíc testují
round-trip více stanovišť/GPS/CODE/časů/souborů/cyklů, nezávislost na odstraněném
zdroji, nová ID, audit a idempotenci, souběh, rollback souborů a použití v simulátoru.
PostgreSQL Codex nespouští. Uživatelské cílené ověření:

```powershell
.\mvnw.cmd -Pit "-Dit.test=TemplatesIT" verify
```

Ruční postup: přihlásit ADMIN, stáhnout GET export jako ZIP, POST imports/preview
s multipart file, prohlédnout počty/varování, potvrdit POST imports se stejným
souborem a novým UUID Idempotency-Key, načíst Location a zkontrolovat DRAFT,
obsah a soubory. Při cookie režimu před POST získat aktuální CSRF dle existující
Postman kolekce. Nezaměňovat validační náhled za provedený import.


Poslední běžné `verify` na Microsoft JDK 21.0.12.1: **730 testů, 0 failures,
0 errors, 0 skipped, BUILD SUCCESS**. Nových 26 unit/HTTP regresí zahrnuje také
CommandStore formát, přesné JPEG/PNG bajty a kompenzaci druhého zápisu. Šest nových
TemplatesIT je zkompilovaných. Uživatel potvrdil úspěšný cílený PostgreSQL běh
celých TemplatesIT: **73 testů, 0 failures/errors/skipped, BUILD SUCCESS**.
Ručně ověřil také export/import. Samotný ZIP
přenos nevyžaduje migraci; rozpracovaná V25 patří předchozímu rozšíření simulátoru.

### Oprava integračního ověření exportu/importu

Nový transfer původně chybně četl a zapisoval `template_stage.statistics_version`.
V17 tento sloupec záměrně odstranila; specifikace 0.9.0 §6 statistické verze
nezavádí. Oprava odstraňuje neplatné SQL i pole `statisticsVersion` z nového
archivního formátu. Nejde o chybějící migraci. Archiv nadále přenáší úplnou
současnou konfiguraci a Markdown; neznámá pole se odmítají, nikoli tiše zahazují.
Šest PostgreSQL scénářů zůstává zachováno včetně autorizační kontroly,
souběžného exportu/importu, idempotence a rollbacku auditu a souborů.
Jejich opakované PostgreSQL ověření po opravě uživatel potvrdil jako úspěšné.
Toto potvrzení se nevztahuje na kompletní PostgreSQL sadu ani samostatný běh
SimulationIT pro rozšíření start/konec.
