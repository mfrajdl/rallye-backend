# Knihovna souborů šablony – závazný kontrakt

Schválený kontrakt pro paralelní implementaci frontendu. Základ:
`/api/v1/race-templates/{templateId}`. Nenahrazuje ostatní CRUD/configuration API.
Implementace používá JDBC, aktuální ADMIN, existující relace, audit a CommandStore.
Žádný frontend zde implementován není.

## Endpointy

| Metoda a relativní cesta | Vstup | Odpověď |
| --- | --- | --- |
| GET `/files` | bez parametrů | 200 FileLibraryResponse |
| POST `/files` | multipart file, query version, UUID Idempotency-Key | 201 FileUploadResponse + Location |
| GET `/files/{fileName}` | URL-encoded název | 200 obsah podle typu |
| DELETE `/files/{fileName}` | query version | 204 bez těla |

FileLibraryResponse:

```typescript
type FileKind = "IMAGE" | "MARKDOWN" | "PDF";
interface FileView {
  fileName: string;
  templateId: string;
  kind: FileKind;
  url: string;
  contentType: string;
  byteSize: number;
  width: number | null;
  height: number | null;
  createdAt: string;
  used: boolean;
}
interface FileReferenceWarning {
  code: "FILE_NOT_FOUND";
  sourceType: "STAGE" | "MARKDOWN_FILE";
  sourceStageId: string | null;
  sourceFileName: string | null;
  targetFileName: string;
  referenceType: "IMAGE" | "LINK";
}
interface FileLimits {
  imageMaxBytes: number;
  imageMaxWidth: number;
  imageMaxHeight: number;
  markdownMaxBytes: number;
  pdfMaxBytes: number;
}
interface FileLibraryResponse {
  templateId: string;
  version: number;
  status: "DRAFT" | "READY";
  items: FileView[];
  missingReferences: FileReferenceWarning[];
  limits: FileLimits;
}
interface FileUploadResponse {
  fileName: string;
  templateId: string;
  url: string;
  version: number;
  status: "DRAFT" | "READY";
  missingReferences: FileReferenceWarning[];
}
```

UUID jsou řetězce, createdAt je UTC ISO-8601. Seznam není stránkovaný, řadí se
podle NFC/Unicode uppercase klíče názvu. Width/height jsou null pro MD/PDF.
MissingReferences je vždy pole; knihovna i upload vracejí varování celé šablony.
StageView v detailu stanoviště/šablony přidává read-only missingReferences pouze
pro dané stanoviště. Dosavadní fields instructionsMarkdown/instructionsRenderedHtml
zůstávají. PUT stanoviště přijímá pouze zdroj, základní údaje a version, ne varování.
Vynechaný zdroj zachová obsah, null jej odstraní. DTO odmítá neznámá pole.

### Upload

```http
POST /api/v1/race-templates/{templateId}/files?version=7
Idempotency-Key: {uuid}
Content-Type: multipart/form-data
```

Jediná souborová část: file, filename například `České pokyny.md`.
Výsledek například:

```json
{
  "fileName": "České pokyny.md",
  "templateId": "UUID-sablony",
  "url": "/api/v1/race-templates/UUID-sablony/files/%C4%8Cesk%C3%A9%20pokyny.md",
  "version": 7,
  "status": "DRAFT",
  "missingReferences": []
}
```

Jedna neměnná knihovna JPEG (.jpg/.jpeg), PNG (.png), UTF-8 Markdown (.md) a PDF
(.pdf). Přípony se porovnávají bez rozlišení velikosti písmen; samotná přípona
ani MIME deklarované klientem nestačí. Obrázky se skutečně dekódují a znovu
zakódují bez metadat, přípona nového uploadu musí odpovídat formátu.
MD vyžaduje validní UTF-8 bez binárních řídicích znaků; prostý text je validní
Markdown. PDF se strukturálně parsuje Apache PDFBox 3.0.8, bez renderování,
spouštění akcí nebo přepisování jeho bajtů. Poškozený/nečitelný PDF se odmítne.

Výchozí limity: obrázky 5242880 bajtů / 4096 × 4096 px, MD 102400 bajtů,
PDF 10485760 bajtů. Konfigurace:
- rallye.templates.files.markdown-max-bytes: 1 až 1048576, default 102400.
- rallye.templates.files.pdf-max-bytes: 1 až 52428800, default 10485760.

Neplatná konfigurace zablokuje start. Multipart obálka je 50 MiB na soubor a
51 MiB na požadavek; služby zachovávají vlastní menší limity, včetně posádek a
původního obrazového API. Frontend čte skutečné limity z odpovědi knihovny.
Limit instrukcí stanoviště zůstává 100 KiB UTF-8 nezávisle na limitu .md souboru.

Názvy: Unicode NFC, maximálně 100 code pointů/200 UTF-8 bajtů; mezery uvnitř a
česká diakritika povoleny. Zůstává společná ImageNames validace proti traversal,
oddělovačům, řídicím/bidi znakům, Windows rezervovaným jménům a nebezpečným znakům.
Kolize je přes NFC + uppercase Locale.ROOT, tedy shodně na Windows/Linuxu.
Obrázky, PDF a MD sdílejí jeden prostor názvů. Duplicitní upload nikdy nepřepíše
existující soubor. Neexistuje editace, přejmenování ani overwrite souboru.

## Obsah a hlavičky

JPEG/PNG: skutečné image/jpeg nebo image/png. Metadata MD mají contentType
text/markdown a byteSize původního zdroje; GET jejich URL však vrací kompletní
samostatný dokument text/html; charset=UTF-8. Syrový MD nemá veřejný endpoint.
HTML obsahuje čitelný responzivní CSS vzhled, bez JS či externích stylů.

PDF: application/pdf, Content-Disposition attachment s UTF-8 filename.
PDF se při kliknutí v nové záložce může stáhnout. Backend neslibuje odstranění
aktivních prvků uvnitř staženého PDF; aplikace jej aktivně nezobrazuje ani nespouští.

Obsahové odpovědi mají Cache-Control no-store, X-Content-Type-Options nosniff,
Referrer-Policy no-referrer a Content-Security-Policy. HTML povoluje pouze vlastní
hashovaný stylesheet a same-origin obrázky, zakazuje JS/formuláře/vkládání do rámů.
Sandbox dovoluje nové záložky pro dohodnuté odkazy. PDF má restriktivní sandbox.
Přístup ke všem souborům, včetně HTML a obrázků v něm, vyžaduje aktuální ADMIN.
Web s cookie relací může otevřít URL přímo. Bearer token se nikdy neposílá v URL;
běžná browserová nová záložka neumí přidat Authorization hlavičku. Bearer-only
klient musí zajistit autorizované načítání obsahu i následných zdrojů sám.

## Odkazy a renderování

Používá se společný CommonMark/GFM parser a jsoup sanitizer. Raw HTML se escapuje;
není alternativou k Markdownu. Dříve uložený nepovolený Link se při čtení zobrazí
bez aktivního href, aby šlo původní zdroj opravit; nové uložení jej odmítne a
readiness jej označí INVALID. Přitom se nemění uložený zdroj. Podporovány jsou například:

```markdown
[Web](https://example.cz)
[Web](http://example.cz)
[E-mail](mailto:info@example.cz)
[Telefon](tel:+420123456789)
[Zpět](#sekce)
[Dokument](pokyny.md#sekce)
[PDF](pravidla.pdf)
[Mapa](mapa.jpg)
![Mapa](mapa.png)
![Mapa](<Česká mapa.png>)
[Dokument](/api/v1/race-templates/UUID-sablony/files/pokyny.md)
```

Relativní názvy se při renderování převádějí na zabezpečenou URL aktuální šablony,
nikoliv React URL. Běžné lokální odkazy používají /files; obrázky nadále mohou
používat /images. Obě explicitní cesty téže šablony jsou podporované.
Zdroj se při uložení nepřepisuje. Obrázek musí cílit na skutečný obrazový typ,
nikoliv MD/PDF. Nepovolují se externí/data/file/javascript obrázky.

Běžné HTTP/HTTPS a všechny lokální souborové odkazy mají target=_blank a
rel='noopener noreferrer'. Mailto/tel a kotvy novou záložku nevynucují.
Nadpisy mají NFC/lowercase slug (Sekce → sekce), duplicitní slug dostane -1, -2.
Fragment se zachová i v odkazu na jiný dokument; existenci samotné kotvy nekontrolujeme.
Zakázané jsou nebezpečné protokoly, protocol-relative URL, traversal, query u
lokálního souboru a odkazy do jiné šablony. Parsují se skutečné AST uzly Image/Link,
ne textové nahrazování. Kódové bloky, inline kód a escapovaný raw HTML nejsou reference.

## Reference, READY, verze a idempotence

V DRAFT lze mít dopředné odkazy. Uložení vrací zdroj i varování, nenahraný cíl
při otevření vrací skutečné 404. Cyklus A.md ↔ B.md je povolený, dokumenty se
nikdy nevkládají rekurzivně. READY vyžaduje úplné reference všech stanovišť a MD
souborů knihovny. Existující POST /validations přidává chyby MISSING_FILE;
publikace neúplné šablony má 409 TEMPLATE_NOT_READY se stejným readiness DTO jako dříve.

Změna instrukcí má dosavadní aggregate version, DRAFT a transakční audit.
Samotný upload/delete nepoužitého souboru verzi nezvyšuje. Pokud upload dokumentu
zavede chybějící reference do READY, šablona přejde atomicky do DRAFT, version +1,
s auditem TEMPLATE_DRAFTED. Response obsahuje výslednou version/status.
Platná version je povinná i pro no-op či neměnící asset operaci.

Upload používá společný CommandStore s 24h retencí; stejný actor/key/operace,
normalizovaný název, vstupní version a hash uložených bajtů vracejí původní 201.
Jiné parametry/operace jsou 409 IDEMPOTENCY_CONFLICT. Ledger neukládá obsah MD,
PDF/obrázkové bajty ani CODE; jen metadata, digest a response včetně názvů varování.
Replay varování je původní snapshot; pro aktuální varování frontend znovu načte GET /files.
Mezi /images a /files nepřepínejte cestu u retry téhož idempotentního požadavku.

Smazání kontroluje Image i Link reference ze stanovišť a ostatních MD.
Samotný vlastní odkaz dokumentu jeho odstranění neblokuje. Vzájemně odkazující
skupinu nelze odstranit po jednom souboru; není force-delete. Odstranění celé
šablony všechny její soubory odstraní po commitu.
Kopie šablony kopíruje všechny soubory pod stejnými názvy. MD explicitní lokální
URL v dokumentech i stanovištích přepíše přes AST; relativní source zůstává stejný.
MD cílové bajty/byteSize odpovídají přepsanému zdroji. Obrázky/PDF se binárně nemění.
Kopie stanoviště sdílí knihovnu a zachovává zdroj. Zdrojová šablona se nemění.

## Interní model, migrace a konzistence

V20 rozšiřuje existující tabulku template_image na společnou knihovnu (historický
interní název se kvůli zbytečnému přejmenování všech závislostí nemění).
Metadata povolují pouze čtyři skutečné MIME typy, nullable rozměry pro dokumenty
a immutable markdown_source snapshot pro MD. Snapshot je přesná UTF-8 kopie
uložených neměnných bajtů, nikoliv renderované HTML. Read/copy kontroluje jeho
shodu s fyzickým souborem; nesoulad bezpečně selže. DB CHECK kontroluje také
velikosti, rozměry, přítomnost snapshotu a shodu jeho UTF-8 délky s byte_size.

Názvy, ID obrázků, FK, všechny instrukce a konfigurace se zachovají. Dosavadní
READY šablony se s version +1 a SYSTEM TEMPLATE_DRAFTED auditem vrátí do DRAFT:
nově aktivní běžné lokální odkazy musí projít novou kontrolou. SQL neodhaduje AST.
V1–V19 se nemění. Přesné migration guardy nově povolují pouze známou V20;
neznámé budoucí migrace jsou nadále odmítnuty při dotčených mazacích operacích.

Reference se spolehlivě rekonstruují z DB zdrojů pod existujícím zámkem šablony;
není zaveden druhý historický index, který by se mohl rozcházet s dopřednými odkazy.
Použití a varování seznamu se spočítají dávkově, bez SQL dotazu na každý soubor a
bez rekurzivního načítání dokumentů. Existující FK image index stanovišť se dál
udržuje pro známé obrazové cíle. Běžné odkazy a MD závislosti kontroluje aplikační
transakce nad skutečnými AST, včetně předchozích uložených instrukcí.

Původní konfigurovaný soukromý root rallye.templates.image-directory a fyzická
struktura race-templates/{id}/images/{fileName} zůstávají i pro nové typy. Jde o
jeden virtuální adresář, žádný veřejný filesystem mapping ani přesun starých souborů.
Všechny cesty používají stejnou ochranu názvů, symlink kontrolu a společný file lock.
Pořadí zámků: dokončená kontrola oprávnění → file lock → admin/account/template.
DB metadata, snapshot/reference stav, audit a ledger se commitují společně.
Selhání odstraní pouze nově vytvořené nereferencované soubory; nejistý commit
nejprve ověří DB. Opuštěné soubory starší 24h uklidí dosavadní uzamčený uploadový
úklid s opakovanou kontrolou referencí. Není nový scheduler ani databázový clean/repair.

## Chyby

Stávající application/problem+json: status, title, code, případně type/instance.
- 400 INVALID_REQUEST, INVALID_FILE_NAME, INVALID_FILE, INVALID_FILE_DIMENSIONS.
- 400 TEMPLATE_FILE_REFERENCE_INVALID pro nepovolený odkaz v novém souborovém API.
- 401 UNAUTHENTICATED; 403 ADMIN_REQUIRED / ACCESS_DENIED (security/CSRF).
- 404 TEMPLATE_NOT_FOUND / TEMPLATE_FILE_NOT_FOUND.
- 409 TEMPLATE_FILE_NAME_CONFLICT / TEMPLATE_FILE_IN_USE / TEMPLATE_VERSION_CONFLICT /
  IDEMPOTENCY_CONFLICT / TEMPLATE_NOT_READY.
- 413 FILE_TOO_LARGE; 415 FILE_UNSUPPORTED_FORMAT nebo UNSUPPORTED_MEDIA_TYPE.
- 503 FILE_STORAGE_UNAVAILABLE.

Původní /images endpointy nadále vracejí původní image DTO/chyby, pouze obrázky.
Čtou společnou knihovnu a odmítají smazání obrázku odkazovaného i běžným Link nebo MD.
Frontend nové knihovny má používat /files. Změna schváleného veřejného kontraktu
nebyla potřebná; fyzický interní adresář/tabulka nejsou součástí API.

## Ruční ověření

1. Přihlásit ADMIN, pro cookie mutace získat CSRF podle authentication-2a.md.
2. GET /files: zkontrolovat limits/status/version a kompatibilní staré obrázky.
3. Nahrát a.md s [B](b.md), ověřit varování a nepřipravenost šablony.
4. Nahrát b.md s [A](a.md), obrázek a PDF; ověřit zánik varování a obsahové hlavičky.
5. Otevřít dokument v browserové cookie relaci; ověřit tabulky, obrázek, kotvu,
   HTTP/mailto/tel a nové záložky. PDF má být attachment.
6. Ověřit 409 při kolizi názvu či smazání používaného souboru, 403 pro běžného uživatele.
7. Duplikovat šablonu; dokumenty/obrázky/PDF patří nové šabloně a zdroj zůstal stejný.

PostgreSQL testy připravené v TemplatesIT (V19→V20, PDF, dokumenty, cykly,
READY/replay, aliasy, souběh a rollback). Codex je nespouští; ověření uživatelem:
`.\mvnw.cmd -Pit verify` proti rallye_test podle docs/testing.md. Zatím neověřené.

Ověření Codexem: JDK 21.0.12.1, Maven Wrapper verify – **BUILD SUCCESS, 611 testů,
0 failures, 0 errors, 0 skipped**, včetně unit/HTTP regresí. PostgreSQL testy se
pouze zkompilovaly; 11 nových scénářů V20 v TemplatesIT čeká na uživatelský běh.
Git diff --check prošel. Kontrakt API zůstal beze změn.

## Soubory implementace V20

Nové:
- src/main/resources/db/migration/V20__template_file_library.sql
- templates/api/TemplateFileController.java
- templates/application/TemplateFileService.java
- templates/application/TemplateFileContent.java
- templates/application/TemplateFileReferences.java
- templates/application/TemplateDocumentHtml.java
- templates/TemplateFilesTest.java, TemplateFileServiceTest.java, TemplateFileHttpTest.java (testy)
- docs/template-files.md

Upravené existující komponenty:
- TemplateStore, TemplateService, InstructionsMarkdown, ImageNames.
- TemplateImageFiles, TemplateImageService, TemplateDuplicationService.
- AuditService, ApiErrors, CommandAccounts, PreRaceAccountParticipationFacts.
- pom.xml (PDFBox), application.properties (multipart obálka).
- TemplatesIT a související jednotkové regrese/guardy schématu.
- AccountCancellationServiceIT (neznámá budoucí migrace posunuta na V21).
- docs/templates-markdown.md, templates-4d.md, testing.md, backend-plan.md.

Java komponenty jsou pod cz/mh/rallye/rallye_backend v src/main/java nebo src/test/java.
Dříve rozpracované změny Markdown/V19 zůstávají zachované; V19, AGENTS.md,
.gitignore a docs/Rallye_specifikace.md tato etapa nemění. Bez commitu/pushe.
