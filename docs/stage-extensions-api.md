# Rozšíření stanovišť – implementovaný kontrakt

Rozšíření ze zadání 30. 10. 2026; doplňuje specifikaci 0.9.0 a nemění pravidlo
GPS + CODE = AND u STANDARD. Ostrý průchod posádek dosud není implementovaný.
OpenAPI: [stage-extensions-openapi.yaml](stage-extensions-openapi.yaml).

## Konfigurace a čtení

Stávající `PUT /api/v1/race-templates/{id}/stages/{stageId}/configuration` má navíc
volitelná pole `type` (STANDARD/INFORMATION/RADAR) a `radarPenaltyPoints` (int64).
Ostatní čtyři konfigurační objekty a očekávaná společná `version` zůstávají povinné.
ADMIN, cookie + CSRF nebo Bearer, původní audit, optimistické verzování a no-op.
Chybějící/null type zachová současný typ i radarovou sazbu; samotná sazba bez typu
se odmítá. Explicitní typ znamená úplné nastavení typu a jeho sazby. Změna je
podstatná a přes stávající touch vrací READY do DRAFT.

```json
{
  "version": 7,
  "type": "RADAR",
  "radarPenaltyPoints": 5,
  "gps": {"enabled": true, "latitude": 50, "longitude": 14,
          "radiusMeters": 100, "outsidePenaltyPoints": null, "spoofingPenaltyPoints": null},
  "code": {"enabled": false, "newCode": null, "wrongAttemptPenaltyPoints": null},
  "timePenalty": {"enabled": false, "intervalSeconds": null, "pointsPerInterval": null},
  "delayed": {"enabled": false, "minimumDurationMs": null, "pointsPerSecond": null}
}
```

Úspěch 200 vrací stávající TemplateService.View s aktualizovanou verzí a seznamem
stanovišť. Stanoviště v administračních GET šablony i snapshotu Akce mají navíc
`type: string` a `radarPenaltyPoints: int64|null`. Ostatní pole zůstávají zachována.
Pouze ADMIN smí číst konfiguraci včetně cílových souřadnic a definovaných odpovědí.
Původní API Akcí nadále umožňuje pouze editaci instrukcí, nikoli konfigurace.

INFORMATION: GPS, CODE, časové penalizace i DELAYED musí být vypnuté, radarová
sazba null. Název, instrukce a očekávaná doba zůstávají součástí obvyklé validace
READY; očekávaná doba není podmínkou dokončení. STANDARD zachovává původní pravidla.
RADAR vyžaduje zapnuté GPS, platnou zeměpisnou šířku/délku, konečný kladný perimetr,
vypnutý CODE a nezápornou sazbu v int64. Běžné GPS outside/spoofing sazby se pro
RADAR nepoužívají a mohou být null. Nezávislá existující časová pravidla zůstávají
použitelná; při neplatném měření nevzniká měřicí penalizace.

Chyby zůstávají 400 INVALID_REQUEST, 401 UNAUTHENTICATED, 403 ACCESS_DENIED nebo
ADMIN_REQUIRED, 404 TEMPLATE_NOT_FOUND/TEMPLATE_STAGE_NOT_FOUND a
409 TEMPLATE_VERSION_CONFLICT. Žádné nové oprávnění ani paralelní verze.

## CODE

`code.newCode` zůstává text; normalizovaná ukládaná definice má nejvýše 128 Unicode
kódových bodů (po strip a převodu na velká písmena).
Schválené upřesnění: nově zadané `code.newCode` se ukládá po `strip()` a
`toUpperCase(Locale.ROOT)`, stejně jako původní API. Diakritika se při ukládání
neodstraňuje. Existující hodnoty, kopie, snapshoty a archivní obsah se hromadně
nepřepisují. Porovnávací normalizace každé varianty: strip → upper-case Locale.ROOT → Unicode NFD →
odstranění kombinujících značek. České znaky jsou tímto pokryty včetně rozložených
sekvencí. Vnitřní mezery se neslučují. Znak | rozděluje varianty, nepodporuje escape.
Prázdná varianta (i po odstranění značek) je chyba 400; duplicitní normalizované
varianty jsou přípustné, při porovnání tvoří množinu. Prázdná odpověď nikdy neuspěje.
Nová definice s totožnou množinou variant je no-op a zachová dřívější text.
Příklad `Praha | Hlavní město | PRAGUE` přijme `hlavni mesto`, nikoli `hlavni`.
Nesprávný CODE používá dosavadní vlastní/zděděnou sazbu. Definované odpovědi nejsou
zapisovány do auditu ani idempotentního úložiště konfiguračních operací.

## Simulátor – implementované operace

- `POST /api/v1/admin/race-simulations/{id}/stage-completions` dokončí INFORMATION
  explicitní akcí Pokračovat. STANDARD stále vrací STAGE_REQUIREMENTS_NOT_MET.
- `POST /api/v1/admin/race-simulations/{id}/radar-measurements` změří aktuální RADAR.

Obě operace používají tento JSON a povinný UUID `Idempotency-Key`:

```json
{"version": 4, "runId": "11111111-1111-4111-8111-111111111111",
 "stageId": "22222222-2222-4222-8222-222222222222"}
```

Pozice se nastavuje samostatnou existující operací `PUT /{id}/position` a zachovává
se skutečné pořadí historie. Platí ADMIN + vlastník, CSRF, běžící závod, aktivní
průchod/stanoviště a timeFloor. Úspěch 200 vrací stávající SimulationView.View.
`currentStage` má navíc `type` a `radarPenaltyPoints` (body zde jako desetinný string,
stejně jako ostatní simulační int64 body). requiredChecks obsahuje CONTINUE nebo
RADAR místo běžného GPS. GPS+CODE standardních stanovišť se nemění.

Výsledek je v `dashboard.lastAction`: type RADAR nebo COMPLETE, outcome
RADAR_OUTSIDE/RADAR_ACCEPTED/INFORMATION_COMPLETED; pointsDelta zůstává string int64.
`gps.distanceMeters` u RADAR je číslo s celočíselnou hodnotou zaokrouhlenou Math.round,
nikoli přesná vzdálenost. O hranici rozhoduje nezaokrouhlená vzdálenost <= radius.
GPS_UNAVAILABLE/GPS_INACCURATE/GPS_MOCK_REJECTED mají distanceMeters null a nulovou
měřicí penalizaci. Nevrací se azimut ani směr. Průběžné gpsPreview je pro RADAR null,
aby pouhé GET nebo změna pozice neprováděly zdarma měření. Simulátor je ADMIN nástroj;
jeho stávající TARGET/OUTSIDE polohové pomůcky nejsou návrhem participant API.

Každé nové úspěšné měření mimo perimetr přidá plnou RADAR_MEASUREMENT penalizaci,
bez stropu maxPenaltyPoints. Přetečení int64 se bezpečně odmítne a rollbackne.
Přeskočení i konec závodu používají stávající náhradu účinných penalizací, nikoli
jejich součet s maximem. Uvnitř perimetru měřicí penalizace nevzniká a stanoviště
se dokončí. INFORMATION za dokončení nepřidává žádné body.

Replay klíče vrací původní odpověď bez změny; jiné tělo se stejným klíčem je 409
IDEMPOTENCY_CONFLICT. Nový klíč se starou verzí je 409 SIMULATION_VERSION_CONFLICT.
Nové radarové měření po přechodu na další stanoviště je 409 STAGE_NOT_CURRENT; po dokončení
průchodu SIMULATION_NOT_RUNNING. Platí existující SIMULATION_RACE_NOT_STARTED,
SIMULATION_RACE_ENDED a SIMULATION_CLOCK_BEFORE_STATE. Neplatná poloha při jejím
nastavení je 400 INVALID_COORDINATES; pozice není zapsána jako přijatá herní akce.
Zámek simulace serializuje operace; u stejné očekávané verze vyhraje pouze jedna.
Historie, reload i obnovení větve obsahují radarové výsledky a účinné penalizace.

## Budoucí mobilní runtime – návrh, nikoli existující endpointy

Konkrétní návrh: `POST /api/v1/race-participations/{participationId}/radar-measurements`
s JSON `runId`, `stageId`, `version`, `latitude`, `longitude`, `accuracyMeters`,
`mockLocation` a UUID Idempotency-Key. Výsledek obsahuje pouze `distanceMeters`
(integer int64|null), `outcome`, `pointsDelta` a novou `version`; nikdy cíl/azimut/směr.
Obdobně `POST /api/v1/race-participations/{participationId}/stage-completions`
s runId/stageId/version pro INFORMATION. Budoucí služba musí použít stejný evaluator,
autoritativní serverový čas, aktuální oprávněné závodní zařízení, kontrolu aktivního
průchodu a existující hranici received_at <= end_at. Tyto dva endpointy nejsou
implementované ani veřejně dostupné. Bez schváleného modelu zařízení/účasti nelze
jejich autentizaci vydávat za hotový kontrakt. Není vytvořen celý závodní runtime.

## Persistence a kompatibilita

V28 přidává stage_type (výchozí STANDARD) a radar_penalty_points do template_stage
i event_stage. Existující data včetně CODE se nepřepisují. DB omezení vynucují
konzistentní typ a radarovou sazbu. Kopírování stanovišť, šablon, snapshotů Akcí,
simulací a ZIP přenos přenášejí oba atributy. Starší JSON konfigurace/ZIP bez obou
polí se čtou jako STANDARD. Starší servery nové typy neumějí; import na starší
verzi aplikace není podporován. Simulační stav a historické důkazy zůstávají
čitelné, replay CODE používá uložený výsledek. Fingerprint CODE zachovává původní
strip/uppercase algoritmus kvůli již uloženým idempotentním odpovědím.

PostgreSQL testy spouští uživatel: `.\mvnw.cmd -Pit "-Dit.test=SimulationIT,TemplatesIT,EventsIT,AccountCancellationServiceIT" verify`.
Codex je nespouští. Změna neimplementuje skutečnou účast, kvízy ani frontend.


Příklad navrženého mobilního měření (dosud neimplementováno):
```json
{"runId":"11111111-1111-4111-8111-111111111111",
 "stageId":"22222222-2222-4222-8222-222222222222","version":4,
 "latitude":50.01,"longitude":14,"accuracyMeters":5,"mockLocation":false}
```
Příklad navržené odpovědi 200:
```json
{"distanceMeters":1112,"outcome":"RADAR_OUTSIDE","pointsDelta":"5","version":5}
```
Návrh používá UUID a nezápornou int64 verzi; souřadnice jsou konečná čísla v rozsahu
[-90,90]/[-180,180], přesnost konečné nezáporné metry. Návrhové chyby: 400 neplatný
vstup, 401 chybějící relace/zařízení, 404 nepřístupná účast, 409 neaktivní průchod,
nesprávné stanoviště, konec nebo konflikt verze/idempotence. Kontrolu zařízení
musí definovat následná etapa účasti. Nejde o příslib již funkční autentizace
na těchto cestách ani o frontendový kontrakt pro dnešní ostrý runtime.


Oprava regresí ukládání CODE: čtyři selhání PostgreSQL testů vycházela ze změny
reprezentace hodnoty na původní lowercase text. Uživatel schválil obnovu
strip + uppercase při ukládání, bez odstranění diakritiky. Porovnávání bez
diakritiky/varianty, INFORMATION i RADAR se nemění. Původní integrační aserce
NEW-CODE/SYNTHETIC-CODE zůstaly zachované. Není potřeba nová migrace ani přepis
existujících hodnot. Uživatel následně potvrdil opakované cílené PostgreSQL ověření jako BUILD SUCCESS.

Ověření opravy ukládání CODE (Codex): Maven Wrapper / JDK 21.0.12.1,
`verify` bez DB – **816 testů, BUILD SUCCESS, 0 failures/errors/skipped**.
Dvě nové regrese ověřují uchování diakritiky při ukládání, porovnání variant bez
diakritiky a odmítnutí prázdných variant bez zápisu/auditu. OpenAPI 3.1 a
git diff --check prošly. Uživatel následně potvrdil příkaz
`.\mvnw.cmd -Pit "-Dit.test=SimulationIT,TemplatesIT,EventsIT,AccountCancellationServiceIT" verify`
jako **BUILD SUCCESS**, včetně opravy čtyř předchozích selhání. Codex PostgreSQL
nespouštěl; toto potvrzení se nevztahuje na nově spuštěnou kompletní integrační sadu.


## Historie radarových měření

Po upřesnění rozsahu je připraven společný model `competition.domain.RadarMeasurement`
a jeho převod z ověřeného výsledku `RaceEvaluator`. Používá jej simulátor. Reálný
průchod posádky, jeho persistence, autentizace zařízení a participant API dosud
nejsou implementované. Napojení historie na reálný průchod (včetně převzetí zařízení)
zůstává výslovně otevřené; toto rozšíření samo o sobě reálný průchod neposkytuje.
Budoucí runtime musí použít stejná pravidla evaluatoru a evidenci výsledků ve stejné
transakci jako stav průchodu a penalizace, s jednorázovou evidencí podle idempotence.
Čas měření bude u reálného průchodu autoritativní serverový čas, u simulátoru virtuální.

Implementovaný čtecí endpoint:
`GET /api/v1/admin/race-simulations/{id}/runs/{runId}/stages/{stageId}/radar-measurements?afterSequence=0&size=50`.
Pouze ADMIN a vlastník simulace; podporuje cookie i Bearer relaci. GET nevyžaduje
CSRF. Odpověď má `Cache-Control: no-store`. `size` je 1–100,
`afterSequence` nezáporné int64. Řazení podle sequence vzestupně; další stránka
použije nextAfterSequence. Nevytváří ani nemění verzi simulace.

```json
{
  "simulationId": "ee72eeea-c007-46c0-9ce7-d742b812ec2c",
  "runId": "a82492a5-9c1f-4230-bb3a-30574809bd5f",
  "stageId": "fca7d580-49db-4893-a7a0-480156b9cf89",
  "items": [{
    "measurementId": "5a211ccd-5563-4c6f-9987-e85baa4bf55b",
    "sourceRunId": "a82492a5-9c1f-4230-bb3a-30574809bd5f",
    "sequence": 3,
    "measurementNumber": 1,
    "latitude": 51.123456789,
    "longitude": 15.987654321,
    "distanceMeters": 150000,
    "penaltyPoints": "7",
    "measuredAt": "2026-10-30T10:00:00Z",
    "recordedAt": "2026-10-30T11:00:00Z",
    "completedStage": false
  }],
  "hasMore": false,
  "nextAfterSequence": 3
}
```

UUID jsou stringy, sequence/measurementNumber jsou int64, souřadnice čísla double,
distanceMeters celé metry (Math.round), penaltyPoints desetinný string int64,
časy UTC Instant. measuredAt je čas herní události, recordedAt čas trvalého zápisu.
measurementNumber je pořadí úspěšného měření daného stanoviště v požadované větvi;
sequence je pořadí všech operací a používá se jako kurzor.

Poloha se zadává předchozím `PUT /api/v1/admin/race-simulations/{id}/position`,
mode INPUT s latitude/longitude/accuracyMeters/mockLocation, nebo stávajícím
TARGET/OUTSIDE/UNAVAILABLE. Poté POST radar-measurements s version/runId/stageId
a Idempotency-Key provede měření. Pouhé nastavení polohy není měření.
Platí původní HTTP kontrakt těchto operací.

Historie obsahuje jen RADAR_OUTSIDE a RADAR_ACCEPTED. Neplatná, nedostupná,
mockovaná ani nepřesná GPS nevytvoří úspěšné měření. Vnější měření přidá plnou
konfigurovanou sazbu bez stropu max_penalty_points; vnitřní měření má měřicí
penalizaci 0 i při vzniku jiných časových penalizací při dokončení. Pozdější SKIP
nemění historickou částku jednotlivého měření, ale může nahradit její účinnost
podle stávajících pravidel. Historie není součtem aktuálně účinných bodů.

Simulátor čte existující simulation_command.verified_facts: zápis stavu, výsledku,
penalizací a idempotence již probíhá atomicky. Nezakládá se druhý deník ani nová
tabulka. Expirace replay odpovědi nemaže facts. Starší úspěšná RADAR měření se
zobrazí z existujících facts. Obnovená větev obsahuje jen zděděný prefix a vlastní
měření; sourceRunId označuje původ záznamu. Restart má prázdnou historii měření,
původní průchod zůstává dostupný svým runId.

Chyby: 400 INVALID_REQUEST (parametry); 401 UNAUTHENTICATED; 403 ADMIN_REQUIRED /
ACCESS_DENIED; 404 SIMULATION_NOT_FOUND (též cizí simulace) nebo
SIMULATION_STAGE_NOT_FOUND; 409 SIMULATION_RESTORE_INVALID (neexistující run),
CHECK_NOT_ENABLED (jiný typ stanoviště); 503 SIMULATION_SNAPSHOT_UNAVAILABLE
(nekonzistentní uložený výsledek). Odpovědi používají stávající Problem DTO.
Nové DTO neobsahuje cílovou polohu, konfiguraci GPS, CODE ani autentizační údaje.

Ověření PostgreSQL je připravené v SimulationIT: přesné polohy, pořadí a stránkování,
zaokrouhlení, invalidní GPS, vlastnictví, reload i po úklidu replay, větvení a replay.
Spouští uživatel: `.\mvnw.cmd -Pit "-Dit.test=SimulationIT" verify`.
Skutečný reálný průchod a převzetí zařízení tím nejsou otestované.


Závěrečné ověření historie RADAR: Codex provedl Maven Wrapper `verify` bez PostgreSQL
na JDK 21.0.12.1: **822 testů, BUILD SUCCESS, 0 failures/errors/skipped**.
Uživatel následně potvrdil cílené `.\mvnw.cmd -Pit "-Dit.test=SimulationIT" verify`
jako **BUILD SUCCESS**. Toto je uživatelem provedené ověření, nikoli běh Codexu;
kompletní PostgreSQL sada nebyla po rozšíření historie nově spuštěna Codexem.
Reálný průchod posádky a převzetí zařízení zůstávají mimo implementovaný rozsah.
