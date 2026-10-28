# Markdown instrukce a pojmenované obrázky šablon

> Aktuální rozšíření V20 a závazný kontrakt knihovny souborů: [template-files.md](template-files.md). Doplňuje odkazy, dopředné reference a read-only varování; původní obrazové endpointy zůstávají.

Nahrazuje HTML/UUID kontrakt 4c. Ostatní operace šablon, CODE/GPS/časová konfigurace,
pořadí a ADMIN oprávnění zůstávají podle [templates-4d.md](templates-4d.md).
Frontend zatím změněn není. Všechny níže uvedené cesty vyžadují aktuální ADMIN,
platnou Rallye relaci, cookie mutace navíc CSRF. Bearer token nikdy nepatří do URL.

## Instrukce

`PUT /api/v1/race-templates/{id}/stages/{stageId}`:

```json
{
  "version": 7,
  "name": "Most",
  "expectedDurationMs": 120000,
  "maxPenaltyPoints": 10,
  "instructionsMarkdown": "## Úkol\n\nNajděte **most**.\n\n![Most](most.jpg)"
}
```

Vrací 200 detail celé šablony; příslušná položka `stages` i GET detailu stanoviště má:

```json
{
  "id": "UUID-stanoviste",
  "sequenceNo": 1,
  "name": "Most",
  "expectedDurationMs": 120000,
  "maxPenaltyPoints": 10,
  "instructionsMarkdown": "## Úkol\n\nNajděte **most**.\n\n![Most](most.jpg)",
  "instructionsRenderedHtml": "<h2>Úkol</h2>\n<p>Najděte <strong>most</strong>.</p>\n<p><img src=\"/api/v1/race-templates/UUID-sablony/images/most.jpg\" alt=\"Most\"></p>\n"
}
```

Příklad vynechává nezměněné konfigurační skupiny. HTML whitespace není API záruka.
Jediný uložený zdroj je přesný Markdown včetně CRLF, mezer a relativních cest.
Limit 100 KiB (102400 bajtů) UTF-8, NUL odmítnut. Chyba 400
`INVALID_INSTRUCTIONS_MARKDOWN`. Vynechané pole zachová zdroj, explicitní null jej
vymaže. Prázdné instrukce jsou možné v DRAFT, READY vyžaduje obsah a dosavadní
úplnou konfiguraci. Toto PUT jinak nahrazuje základní údaje stanoviště.
`instructionsHtml`, staré `instructions` a vstupní `instructionsRenderedHtml`
jsou nepodporovaná pole (400 INVALID_REQUEST).

CommonMark Java 0.31.0 + GFM tables, strikethrough, task-list-items a autolink:
nadpisy, odstavce, strong/em/del, odkazy, obrázky, seznamy, citace, čáry,
tabulky, zakázané checkboxy a inline/fenced/indented kód. Žádné vlastní sloupce.
Raw HTML se escapuje, nepoužívá jako aktivní HTML. Výstup navíc čistí jsoup 1.22.1
přes explicitní allowlist; žádné skripty, event handlery, styly nebo nebezpečné
URL. Běžné odkazy mohou být HTTP/HTTPS. Vygenerované HTML se neukládá.

No-op znamená přesně stejný zdroj, nikoli podobný vykreslený výsledek.
Skutečná změna zdroje vrací READY do DRAFT, zvyšuje společnou version a audituje
bez obsahu instrukcí. Zdroj, reference obrázků, verze a audit jsou jedna transakce.

## Knihovna obrázků a názvy

| Metoda / cesta vůči `/api/v1/race-templates` | Vstup | Výstup |
| --- | --- | --- |
| POST `/{id}/images` | multipart file (původní filename), version, UUID Idempotency-Key | 201 ImageCreated + Location |
| GET `/{id}/images` | — | 200 ImageView[] |
| GET `/{id}/images/{fileName}` | URL-encoded segment | 200 skutečný image/jpeg nebo image/png |
| DELETE `/{id}/images/{fileName}` | query version | 204 |

ImageCreated: `{fileName, templateId, url, version}`.
ImageView: `{fileName, templateId, url, contentType, byteSize, width, height,
createdAt, used}`. UUID obrázku není veřejný identifikátor. Například:

```json
{"fileName":"Česká mapa.png","templateId":"UUID-sablony","url":"/api/v1/race-templates/UUID-sablony/images/%C4%8Cesk%C3%A1%20mapa.png","version":7}
```

Název se normalizuje do Unicode NFC a zachová se jeho velikost písmen. Pro unikátnost
se používá NFC + Unicode uppercase Locale.ROOT, stejně na Windows a Linuxu.
Case varianty odkazují na tentýž soubor; kolidující upload vrací 409
`TEMPLATE_IMAGE_NAME_CONFLICT` a nepřepisuje existující bajty. Shodný název v jiné
šabloně je povolen. Limit 100 Unicode code pointů a 200 UTF-8 bajtů.
Mezery uvnitř a česká diakritika jsou povolené; okrajové mezery, úvodní tečka,
koncová tečka/mezera, absolutní cesty, oddělovače, řídicí/bidi znaky, Windows
rezervovaná jména a znaky `<>:"|?*%#` jsou zakázané (400 INVALID_IMAGE_NAME).
Přípona není důkaz formátu: skutečné JPEG/PNG se dekóduje, rozměry kontrolují a
znovu zakóduje bez metadat. Limit vstupu/výstupu 5 MiB, nejvýše 4096 × 4096 px.

Upload replay se stejným klíčem, názvem, verzí a hashem normalizovaných bajtů
vrací původní výsledek; jiné parametry 409 IDEMPOTENCY_CONFLICT. Ledger má obvyklou
24h retenci a neukládá obrázkové bajty, instrukce ani CODE. Samotný upload nebo
smazání nepoužitého obrázku nezvyšuje version ani nemění READY, ale vyžaduje platnou
verzi. Použitý obrázek vrací 409 TEMPLATE_IMAGE_IN_USE; neznámý 404
TEMPLATE_IMAGE_NOT_FOUND. Původní filename se nikdy nepoužije jako cesta adresářů.

Soubor je soukromě v `rallye.templates.image-directory/race-templates/{id}/images/{fileName}`,
výchozí kořen `${user.home}/.rallye/template-images`. Žádný webroot/static mapping.
Čtení má no-store/nosniff. Bearer frontend musí obrázky autorizovaně načíst a
zobrazit např. blob URL; běžné img neumí přidat Bearer hlavičku. Cookie klient
používá stávající session. Při více instancích je nutný sdílený filesystem se
zámky. Symlink soubory/adresáře se nepřijímají; kořen musí spravovat pouze aplikace.

## Markdown obrázky a kopírování

```markdown
![Most](most.jpg)
![Mapa](mapa%20trasy.jpg)
![Mapa](<mapa trasy.jpg>)
![Most](/api/v1/race-templates/UUID-sablony/images/most.jpg)
```

Backend parsuje skutečné Image uzly CommonMark AST, také reference-style obrázky.
Kódové bloky a inline kód nejsou reference. Relativní zdroj zůstává v DB beze změny;
pouze při renderování vznikne správně URL-encoded zabezpečená API cesta.
Není povolen obrázek jiné šablony, externí URL, data/javascript/file, traversal,
query ani fragment. Chybějící soubor v knihovně / chybný odkaz vrací 400
TEMPLATE_IMAGE_REFERENCE_INVALID. Více výskytů je jedna DB vazba, více stanovišť
sdílí stejný soubor. FK vynucuje společnou šablonu. Použitý obrázek nelze smazat.

Kopie šablony kopíruje všechny soubory pod stejnými názvy do nové šablony, interní
UUID vazeb jsou nová. Relativní Markdown zůstává přesný, jen explicitní Image uzly
se přepíší na cílovou šablonu. Reference-style explicitní obraz může přejít na
inline syntaxi; nepoužitá definice může zůstat ve zdroji, nemá vliv na vykreslení.
Ostatní text/kód/odkazy se nemění. Kopie stanoviště zachová přesně celý zdroj a
sdílí soubory v téže šabloně. Mazání šablony kaskáduje metadata a po commitu
odstraní její soubory. Při rollbacku kopie/uploadu se nové nereferencované soubory
odstraní, nikdy se nepřepíší původní. Při pádu nebo nejistém commitu může zůstat
nepřístupný orphan; po 24h jej při dalším uploadu odstraní existující úklid pod
společným file lockem s opakovanou kontrolou DB referencí. Nevzniká nový scheduler.

## Migrace a ověření

V19 zachovává V1–V18 beze změny, zahazuje výslovně postradatelné testovací HTML,
metadata obrázků a vazby; nejde o převod obsahu. Příslušné šablony mají zvýšenou
verzi, READY přejde do DRAFT s SYSTEM auditem. Sloupec se jmenuje
instructions_markdown, metadata mají file_name/name_key a UNIQUE(template,name_key).
Starý image-create ledger se odstraní; nový upload má vlastní uzavřený formát.
Původní UUID soubory mimo nový strom nejsou dostupné přes API; po této migraci je
lze v zastavené aplikaci odstranit z vyhrazeného privátního kořene. Migrace sama
filesystem nemaže. Nespouštěla se proti vývojové ani produkční DB.

Nové PostgreSQL testy TemplatesIT pokrývají V18→V19, názvy, vazby, oprávnění,
no-op/READY, idempotenci, souběhy a rollback souborů/DB. Jsou připravené,
Codex je nespouštěl. Uživatelský běh odhalil chybu upgradu V19 popsanou níže;
oprava čeká na opakované ověření. Spuštění podle
docs/testing.md: `.\mvnw.cmd -Pit verify` proti rallye_test.

Frontend následně nahradí TipTap HTML editaci Markdown editorem, posílá
instructionsMarkdown, náhled bere z instructionsRenderedHtml a obrázky identifikuje
fileName + url z API. Musí respektovat no-store, CSRF, společnou version a nové chyby.
Staré HTML payloady/UUID obrázků nejsou kompatibilní. Frontend zde upraven nebyl.

Ověření této změny Codexem: Maven Wrapper verify na JDK 21.0.12.1, **BUILD SUCCESS, 577 testů, 0 failures/errors/skipped**. Git diff --check prošel. PostgreSQL integrační testy se pouze zkompilovaly a čekají na manuální běh uživatele.

## Soubory této změny

- `docs/backend-plan.md`
- `docs/templates-4a.md`
- `docs/templates-4b.md`
- `docs/templates-4c.md`
- `docs/templates-4d.md`
- `docs/templates-markdown.md`
- `docs/testing.md`
- `pom.xml`
- `src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/PreRaceAccountParticipationFacts.java`
- `src/main/java/cz/mh/rallye/rallye_backend/shared/persistence/CommandAccounts.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/api/TemplateController.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/api/TemplateImageController.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/application/ImageNames.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/application/InstructionsHtml.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/application/InstructionsMarkdown.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/application/TemplateDuplicationService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/application/TemplateImageFiles.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/application/TemplateImageService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/application/TemplateReadiness.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/application/TemplateService.java`
- `src/main/java/cz/mh/rallye/rallye_backend/templates/persistence/TemplateStore.java`
- `src/main/resources/db/migration/V19__template_markdown_and_named_images.sql`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/AccountCancellationServiceIT.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/PreRaceAccountParticipationFactsTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/identity/TemplatesIT.java`
- `src/test/java/cz/mh/rallye/rallye_backend/shared/persistence/CommandAccountsTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/templates/ImageNamesTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/templates/InstructionsHtmlTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/templates/InstructionsMarkdownTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/templates/TemplateHttpTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/templates/TemplateImageFilesTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/templates/TemplateImageHttpTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/templates/TemplateReadinessTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/templates/TemplateServiceTest.java`
- `src/test/java/cz/mh/rallye/rallye_backend/templates/application/TemplateDuplicationServiceTest.java`

### Dodatečná oprava pořadí kontrol V19

Při uživatelském PG ověření upgrade fixture s existujícím obrázkem a FK vazbou
odhalila chybu pending trigger events (55006). V19 před DDL nyní explicitně
provede čekající odložená omezení pomocí SET CONSTRAINTS ALL IMMEDIATE.
Test nebyl změněn ani oslaben; oprava čeká na opakované PostgreSQL ověření.
Podrobnosti a upozornění na případný checksum již úspěšné V19: docs/testing.md.
