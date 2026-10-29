# Editace instrukcí stanoviště Akce – závazný API kontrakt

## PUT /api/v1/race-events/{id}/stages/{stageId}/instructions

Samostatná úplná náhrada pouze Markdown instrukcí. Obě ID jsou UUID; stageId
je ID snapshotu z GET stages, nikoli sourceTemplateStageId. Content-Type: application/json.

```json
{"version":3,"instructionsMarkdown":"# Pokyny\n\n![Mapa](map.png)"}
```

Obě pole jsou povinná; version je nezáporné celé signed 64bit číslo.
instructionsMarkdown je string nebo explicitní null (vymazání); prázdný string
je povolen. Jiné typy, neznámá pole a vynechaný obsah vracejí 400 INVALID_REQUEST.
Idempotency-Key se nevyžaduje.

200 OK:

```json
{
  "raceEventId":"11111111-1111-4111-8111-111111111111",
  "stageId":"22222222-2222-4222-8222-222222222222",
  "version":4,
  "instructionsMarkdown":"# Pokyny\n\n![Mapa](map.png)",
  "instructionsRenderedHtml":"<h1 id=\"pokyny\">Pokyny</h1><p><img src=\"/api/v1/race-events/11111111-1111-4111-8111-111111111111/files/map.png\" alt=\"Mapa\"></p>",
  "missingReferences":[]
}
```

HTML je serverem renderované a sanitizované; jeho whitespace není stabilní kontrakt.
Při null obsahu jsou obě obsahová pole null. Zdroj Markdownu se uchová přesně.

## Verze, stav a oprávnění

Používá se jediná společná version celé Akce z existujícího
GET /api/v1/race-events/{id} (také z položky seznamu Akcí). GET seznam/detail
stanovišť se nemění a vlastní version nepřidává. Frontend načte verzi rodiče
a stageId/obsah z GET /api/v1/race-events/{id}/stages/{stageId}.
Úspěšná skutečná změna zvýší version Akce o 1, aktualizuje updatedAt a vytvoří
RACE_EVENT_STAGE_INSTRUCTIONS_UPDATED. Shodný obsah se správnou verzí je no-op.
Stará verze vždy vrací 409 RACE_EVENT_VERSION_CONFLICT, i při shodném obsahu;
frontend musí znovu načíst aktuální verzi i obsah a neprovádět slepé přepsání.
Opakování již úspěšného změnového požadavku se starou verzí je konflikt.

Jen aktuálně aktivní ověřený ADMIN s platnou relací, pouze stav PLANNED.
Cookie autentizace vyžaduje platnou X-XSRF-TOKEN hlavičku a cookies podle
stávajícího GET /api/v1/auth/csrf; Bearer zachovává stávající režim bez browser CSRF.

## Markdown a soubory

Stejný CommonMark/GFM parser a sanitizace jako šablony, limit 102400 UTF-8 bajtů,
bez NUL. Raw HTML se neinterpretuje, ale escapuje. Relativní odkazy se zachovají,
renderují do knihovny této Akce. Explicitní interní URL smí patřit pouze této
Akci (/api/v1/race-events/{id}/files/{fileName}; kompatibilně také alias /images/{fileName}); odkazy do šablony či jiné Akce,
traversal, nepovolené protokoly a externí obrázky se odmítají. Běžné externí
odkazy http/https/mailto/tel a fragmenty mají stejná pravidla jako šablony.
Povolené lokální cíle: JPEG/PNG, MD/PDF; obrázek musí cílit na obrázek.
Chybějící povolený lokální soubor nezablokuje uložení (stejně jako šablony),
ale vrátí missingReferences a čtení souboru zůstane 404. Warning má tvar:

```json
{"code":"FILE_NOT_FOUND","sourceType":"STAGE","sourceStageId":"22222222-2222-4222-8222-222222222222","sourceFileName":null,"targetFileName":"missing.png","referenceType":"IMAGE"}
```

referenceType je IMAGE nebo LINK. Endpoint nemění knihovnu ani konfiguraci;
nevytváří účastnické API a nemění READY pravidla šablon.

## Chyby ve stávajícím ProblemDetail formátu

- 400 INVALID_REQUEST: DTO, UUID, version; INVALID_INSTRUCTIONS_MARKDOWN: limit/NUL.
- 400 TEMPLATE_FILE_REFERENCE_INVALID / TEMPLATE_IMAGE_REFERENCE_INVALID / INVALID_IMAGE_NAME:
  stávající validátory URL a názvů (názvy kódů zůstávají sdílené se šablonami).
- 401 UNAUTHENTICATED: chybějící/neplatná relace.
- 403 ACCESS_DENIED (security/CSRF) nebo ADMIN_REQUIRED (aplikační kontrola).
- 404 RACE_EVENT_NOT_FOUND nebo RACE_EVENT_STAGE_NOT_FOUND (i cizí stageId).
- 409 RACE_EVENT_NOT_PLANNED nebo RACE_EVENT_VERSION_CONFLICT.
- 500 INTERNAL_ERROR: neočekávaná chyba; změna i audit se rollbackují.

Audit ukládá ID Akce a stanoviště, nikdy obsah instrukcí či CODE. Verze,
aktualizace snapshotu a audit se potvrzují v jediné transakci se zámkem rodiče.
Zdrojová šablona a jiné Akce zůstávají beze změny. Migrace není potřeba.

## Ruční ověření (Postman / frontend)

1. Přihlaste ADMIN; v cookie režimu po přihlášení znovu načtěte CSRF.
2. GET detail Akce: uložte version. GET stages: vyberte snapshot stageId.
3. PUT instrukcí s novým Markdownem a touto verzí: 200, version + 1.
4. GET detail stanoviště i Akce: nový obsah a nová rodičovská verze.
5. Ověřte, že zdrojová šablona a jiná Akce ze stejného zdroje mají původní text.
6. Zopakujte PUT se starou verzí: 409; s aktuální verzí a shodným textem: 200 bez zvýšení.
7. Vyzkoušejte relativní obrázek/MD/PDF z knihovny Akce; HTML používá /race-events/{id}/files.
8. Chybějící povolený soubor: 200 s warningem, čtení souboru 404. Cizí interní
   URL či nebezpečný protokol: 400 a žádná změna.
9. Explicitní null: 200, oba obsahové atributy null. Vynechané pole: 400.
10. Běžný uživatel: 403; cookie PUT bez CSRF: 403; bez relace: 401.

Tyto ruční scénáře nejsou automatickými testy považovány za provedené.

Ověření Codexem: Maven Wrapper `verify` na JDK 21.0.12.1 bez PostgreSQL –
**BUILD SUCCESS, 658 testů, 0 failures, 0 errors, 0 skipped**. PostgreSQL
ověření nových scénářů uživatel potvrdil jako BUILD SUCCESS. `git diff --check` prošel.
