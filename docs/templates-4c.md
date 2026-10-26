# Etapa 4c – HTML instrukce a soukromé obrázky šablon

Aktuální společný přehled API včetně duplikování pro editor je v
[templates-4d.md](templates-4d.md). Tento dokument zůstává podrobným HTML/image kontraktem.

Implementace navazuje na 4a/4b. Frontend/editor, duplikování (4d), konání ani
vyhodnocování pokusů nejsou součástí. PostgreSQL ověření uživatel následně potvrdil jako BUILD SUCCESS.

## Kontrakt instrukcí

`PUT /api/v1/race-templates/{templateId}/stages/{stageId}` přijímá:

```json
{
  "version": 3,
  "name": "Stanoviště 1",
  "expectedDurationMs": 120000,
  "maxPenaltyPoints": 10,
  "instructionsHtml": "<h2>Úkol</h2><p>Najděte <strong>kód</strong>.</p>"
}
```

Jde nadále o existující editaci základních údajů stanoviště, nikoliv PATCH pouze
instrukcí: jméno a číselné údaje pošlete v požadované výsledné podobě. Vynechané
`instructionsHtml` zachová instrukce, explicitní `null` je odstraní. Řetězec se
sanitizuje před porovnáním se stávajícím obsahem. Jiný datový typ vrací 400.
Původní pole `instructions` se odmítá jako neznámé; dvě reprezentace se nezavádějí.
Detail šablony, seznam i detail stanovišť vrací pouze `instructionsHtml`.
Čtení vrací přesně uložené HTML, znovu je nepřepisuje ani nesanitizuje.

Limit je **102400 bajtů UTF-8 před i po sanitizaci**. Překročení vrací
400 `INSTRUCTIONS_HTML_TOO_LARGE`; NUL 400 `INVALID_INSTRUCTIONS_HTML`.
Neplatná strukturovaná interní reference vrací 400 `TEMPLATE_IMAGE_REFERENCE_INVALID`.
Žádné instrukce nebyly podle zadání dosud uloženy: není převod textu ani souběžná
textová reprezentace. V17 ve skutečnosti přejmenovala `instructions_html` na
`instructions`; V18 tento název vrací zpět. V16/V17 zůstávají beze změny.

## Povolené HTML

Sanitizace používá jsoup Cleaner s explicitním allowlistem, nikoliv regulární
výrazy pro parsování HTML ([oficiální postup](https://jsoup.org/cookbook/cleaning-html/safelist-sanitizer)).
Povoleny jsou `p`, `br`, `h1`–`h6`, `strong`, `b`, `em`, `i`, `u`, `s`, `del`,
`ul`, `ol`, `li`, `a`, `img` a jednoduché `table`, `thead`, `tbody`, `tfoot`,
`tr`, `th`, `td`. Tabulky nemají styly, atributy ani slučování buněk.

Odkazy mají pouze `href`, `title`, případně `target="_blank"`; pro `_blank`
server vždy doplní `rel="noopener noreferrer"`. `href` je pouze absolutní
HTTP/HTTPS URL. Relativní a protokolově relativní odkazy se nepovolují.
Obrázky mají pouze `src`, `alt`, `title`; zdroj musí být níže uvedená interní URL.
Žádné `style`, `class`, JS události, SVG, iframe, script, formuláře, `srcset`,
externí obrázky ani `data:` se neukládají. Zarovnání/CSS se zatím nepřidává.
Sanitizace normalizuje HTML deterministicky, bez pretty-print; opakování zachová
výsledek. Editor musí po uložení převzít serverem vrácený obsah.

## Obrázky a URL

Přesný stabilní zdroj pro HTML:

```html
<img src="/api/v1/race-templates/TEMPLATE_UUID/images/IMAGE_UUID" alt="Mapa">
```

UUID jsou kanonická malá písmena s pomlčkami, bez query, fragmentu či kódovaných
částí cesty. Do `src` nepatří origin, cesta k souboru ani token. Externí/nepovolený
zdroj sanitizace odstraní. Platně strukturovaný interní zdroj s jinou šablonou,
chybějícím obrázkem nebo nesprávným vlastnictvím vrátí validační chybu; nezmizí tiše.
Opakování stejného obrázku vytvoří jedinou vazbu na stanoviště. Více stanovišť téže
šablony sdílí jeden soubor. Jiná šablona jej použít nemůže.

| Metoda a cesta (za `/api/v1/race-templates/{templateId}`) | Kontrakt |
| --- | --- |
| `POST /images?version=N` | Multipart `file`, hlavička UUID `Idempotency-Key`; 201, Location, tělo `{id, templateId, version}`. |
| `GET /images` | Pole `{id, templateId, url, contentType, byteSize, width, height, createdAt, used}`. |
| `GET /images/{imageId}` | Binární obsah, skutečný `image/jpeg` či `image/png`, `nosniff`, `Cache-Control: no-store`. |
| `DELETE /images/{imageId}?version=N` | 204 pro nepoužívaný obrázek; 409 `TEMPLATE_IMAGE_IN_USE` pro používaný. |

Všechny operace nyní vyžadují aktuální ADMIN a platnou relaci, shodně s celou
správou šablon. Chybí-li šablona: 404 `TEMPLATE_NOT_FOUND`; obrázek v dané šabloně:
404 `TEMPLATE_IMAGE_NOT_FOUND`. Bez přihlášení 401; běžný uživatel 403; cookie
mutace vyžadují CSRF. Žádný endpoint není veřejný kvůli budoucím mobilním klientům.

Upload vrací pouze vytvořenou identitu, ne binární data do ledgeru. Existující
CommandStore eviduje `[templateId, version, SHA-256 normalizovaných bajtů]`, retence
24 hodin a obvyklé replay/409 konfliktu. Pořadí replay před novým porovnáním verze
je zachováno. DELETE nemá další idempotentní ledger; používá aktuální verzi.

## Validace a soubory

JPEG/PNG se zjišťuje z obsahu, nikoliv MIME nebo názvu. Limit vstupu i
normalizovaného výstupu je 5 MiB, šířka/výška 1–4096 px. Používá se existující
`PhotoFiles.validate`: dekódování a nové zapsání pixelů odstraní EXIF/GPS, PNG
texty a další zdrojová metadata. Sdílená validace nově odmítá i chybějící koncové
JPEG/PNG značky a varování dekodéru; regrese fotografií posádek zůstává pokrytá.
Chyby sdílejí dosavadní kódy `INVALID_PHOTO` (400), `INVALID_PHOTO_DIMENSIONS`
(400), `PHOTO_TOO_LARGE` (413), `PHOTO_UNSUPPORTED_FORMAT` (415) a
`PHOTO_STORAGE_UNAVAILABLE` (503). Odpověď multipart limitu používá stávající handler.

Konfigurace `rallye.templates.image-directory`, výchozí
`${user.home}/.rallye/template-images`, je privátní adresář mimo Git a veřejný
webroot. Lze jej nastavit proměnnou `RALLYE_TEMPLATES_IMAGE_DIRECTORY` přes Spring
relaxed binding; více instancí musí sdílet stejný volume a funkční souborové zámky.
Názvy souborů generuje server z UUID, originální jméno se nepoužívá. Nezavádí se
obecný systém příloh ani druhý dekodér: wrapper opětovně používá `PhotoFiles`.

Upload/read/delete drží shodný procesový a souborový zámek; DB část drží standardní
admin/account a následně template zámek. HTML změna i mazání obrázku serializují
kontrolu použití stejným template zámkem. HTML, vazby, verze a audit se zapisují
v jedné transakci. Při neúspěšném uploadu se nový nereferencovaný soubor odstraní;
při nejasném výsledku commit se před odstraněním znovu ověří DB reference.
Mazání souboru nastává až po úspěšném DB commitu; chyba auditu původní soubor zachová.
Mazání celé šablony odstraní DB metadata/vazby kaskádou a soubory po commitu.
Mazání stanoviště odstraní jeho vazby; obrázek ponechá jako nepoužívaný k dalšímu použití.

Pád procesu mezi zápisem souboru a DB commitem může zanechat **přes API nedostupný**
soubor. Stejně tak se neúspěšný fyzický unlink musí opakovat. Při každém dalším
uploadu se pod stejným souborovým zámkem uklidí nereferencované soubory starší
24 hodin, s opakovaným ověřením DB reference. Bez dalšího uploadu fyzický zbytek
může přetrvat; není veřejně dostupný. Není nový scheduler ani automatické mazání
používaných obrázků. Zálohujte databázi a volume společně.

## READY, verze a editor

READY vyžaduje skutečný textový obsah nebo alespoň jeden platný interní obrázek.
`<p></p>`, `<p><br></p>`, nezlomitelné mezery či samotné neviditelné formátovací
znaky nestačí. Existence a vlastnictví obrázků se ověřuje při zápisu; FK a vazby
zabraňují následnému smazání používaného obrázku.

Skutečná změna sanitizovaného HTML zvýší `template.version` právě jednou,
vrátí READY do DRAFT a audituje `TEMPLATE_STAGE_UPDATED`. No-op po sanitizaci
nezmění verzi, timestamp, stav ani audit. Upload/delete nepoužitého obrázku
ověří aktuální verzi, ale nemění ji ani READY; auditují `TEMPLATE_IMAGE_CREATED`
a `TEMPLATE_IMAGE_DELETED` bez HTML či binárního obsahu. Statistické verze nejsou.

Budoucí React editor musí generovat uvedený omezený formát, nejdříve nahrát
obrázek a vložit vrácenou `url`, posílat současnou template.version a pracovat
se sanitizovanou odpovědí. Relativní image URL vyžadují společný origin/API proxy
nebo explicitní resolve vůči backendu. Bearer token nepatří do URL: klient bez
cookie musí obrázky načítat autentizovaným fetch a použít lokální blob; mobilní
klient stejným způsobem autentizuje síťový požadavek. Přístup účastníků ke konáním
se v této etapě nepředjímá a nezpřístupňuje.

## Databáze a ověření

`V18__template_html_and_images.sql` vrací název HTML sloupce, zavádí 100KiB
CHECK a tabulky `template_image`, `template_stage_image`. Složené FK vynucují
shodnou šablonu obrázku a stanoviště, PK deduplikuje použití. Odložená image FK
umožňuje kaskádové odstranění celé šablony; aplikace používaný obrázek odmítá dřív.
Přesné guardy rušení účtů a mazání šablon přijímají explicitní V18, nikoliv libovolnou
budoucí migraci. V1–V17 se nepřepisují; nejsou datové převody instrukcí.

Rychlé testy ověřují allowlist, URL, XSS, UTF-8 limity před/po, idempotentní
sanitizaci, READY obsah, no-op, DTO, CSRF/ADMIN, metadata a sdílený image decoder.
TemplatesIT doplňuje skutečné FK, transakce, audit/ledger/file rollback, sdílení,
použití/mazání v souběhu, idempotentní upload, kaskádu, stažení a V18 guardy.
PostgreSQL testy Codex nespouští. Uživatel následně potvrdil BUILD SUCCESS
pro následující příkaz proti rallye_test:

```powershell
.\mvnw.cmd -Pit verify
```

Výsledek běžného verify a uživatelem potvrzené PostgreSQL ověření jsou zaznamenány v
[testing.md](testing.md). Žádný frontend, commit ani push se v této etapě neprovádí.


## Ruční průchod bez editoru

Použijte stávající přihlášení ADMIN a Bearer token (nebo cookie s aktuálním CSRF).
Ruční průchod Codex neprováděl. Pracujte se zkušební šablonou a vždy načtěte její
aktuální `version`; pro READY musejí být platné i ostatní parametry z 4b.

1. Načtěte `GET /api/v1/race-templates/{id}` (200), poznamenejte UUID stanoviště a verzi.
2. `POST /api/v1/race-templates/{id}/images?version=N`: multipart pole `file`
   s JPEG/PNG a nový UUID `Idempotency-Key`. Očekává se 201 a Location; verze N se nemění.
3. `GET /api/v1/race-templates/{id}/images` (200): vyberte `url` nového obrázku;
   stáhněte ji se stejnou autentizací (200, správný Content-Type, nosniff).
4. Existujícím PUT stanoviště uložte základní údaje a `instructionsHtml` s touto URL.
   Očekává se 200, deduplikované použití, zvýšení verze o 1 a DRAFT. Stejný obrázek
   vložte do druhého stanoviště: upload není potřeba.
5. DELETE obrázku s aktuální verzí musí vrátit 409 `TEMPLATE_IMAGE_IN_USE`.
6. Odstraňte odkazy z obou stanovišť pomocí PUT s aktuálními verzemi. Seznam obrázků
   pak vrátí `used:false`; DELETE vrátí 204. Získání smazané URL vrací 404.
7. Proveďte validaci a READY na kompletní šabloně. PUT stejných instrukcí s doplněným
   zakázaným `onclick` skončí po sanitizaci jako no-op; stav/verze se nemění.
   Skutečná změna textu vrátí DRAFT a zvýší verzi.
8. Zkuste externí/data obrázek (odstraněn sanitizací), chybějící interní UUID a URL
   obrázku jiné šablony (400 reference), nadlimitní HTML a poškozený soubor.
9. Bez relace čekejte 401; běžný účet má u správy šablon i obrázků 403.


Závěrečné ověření Codexem: `verify` bez PostgreSQL BUILD SUCCESS, 511 testů,
0 failures/errors/skipped, JDK 21.0.12.1; `git diff --check` bez chyb.
Uživatel následně potvrdil `.\mvnw.cmd -Pit verify` proti rallye_test jako
**BUILD SUCCESS**, včetně 14 nových PostgreSQL scénářů 4c a migrace V18.
Jde o uživatelský běh, nikoli běh Codexu. Ruční API/editor průchod tím není potvrzen.

## Soubory této úpravy

- `pom.xml` (jsoup 1.22.1).
- `templates/api/TemplateController.java`, nový `TemplateImageController.java`.
- `templates/application/TemplateService.java`, `TemplateReadiness.java`, nové
  `InstructionsHtml.java`, `TemplateImageFiles.java`, `TemplateImageService.java`.
- `templates/persistence/TemplateStore.java`.
- `V18__template_html_and_images.sql`.
- Sdílené `crews/application/PhotoFiles.java` (odmítnutí uříznutých streamů).
- `audit/application/AuditService.java`, `shared/persistence/CommandAccounts.java`,
  `identity/persistence/PreRaceAccountParticipationFacts.java`.
- `TemplatesIT`, `TemplateServiceTest`, `TemplateReadinessTest`, `TemplateHttpTest`,
  nové `InstructionsHtmlTest`, `TemplateImageHttpTest`, `TemplateImageFilesTest`.
- `CommandAccountsTest`, `PreRaceAccountParticipationFactsTest`,
  `AccountCancellationServiceIT` (neznámá budoucí fixture V19).
- `docs/templates-4c.md`, návazné `templates-4b.md`, `backend-plan.md`, `testing.md`.

Již rozpracované 4a/4b a úprava seznamu posádek zůstávají zachovány; nejde
všechny aktuální Git změny považovat za samotnou 4c. Chráněné `.gitignore`
a `docs/Rallye_specifikace.md` se neměnily, stejně jako migrace V1–V17.
