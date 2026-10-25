# Apple Token Revocation – implementovaný stav 3c.5

Etapa 3c.5 je implementačně dokončena a automaticky ověřena. Předchozí dělení 3c.5 je uzavřeno do jednoho funkčního celku; další podetapy se neplánují. Základ V13/V14 a opravený MobileExchangeIT uživatel potvrdil PostgreSQL BUILD SUCCESS. Uživatel potvrdil závěrečný `.\mvnw.cmd -Pit verify` proti rallye_test jako BUILD SUCCESS včetně V15 a nových scénářů dokončení. Skutečné Apple/HTTPS ověření dosud neproběhlo.

Platí specifikace 0.8.0, D09–D14; D15 je zrušené. Jedna neměnná identita, žádné link/unlink/reauth granty. Neexistují historické produkční Apple tokeny a neprovádí se jejich rekonstrukce.

## Skutečné chování

- Ověřený Apple callback samostatně a trvale uloží autorizační případ, subject, přesný client kontext, vlastníka a AES-GCM ciphertext. Poté vznikne webová relace nebo mobilní handoff; rollback obchodního dokončení neztratí zachycený token. Spotřebovaný callback nelze opakovat, je potřeba nový login.
- Registrace má 15 minut. Completion přiřadí všechny související případy stejné identity účtu; e-mail účty nepropojuje. PENDING_EMAIL/DISABLED účet není opuštěný formulář. První mobilní registrace používá browserové dokončení a vyžaduje nový login podle D12.
- D14 databázová údržba běží každých 60 sekund. Revokaci opuštěné registrace založí až po expiraci všech jejích souvisejících kontextů a bez vlastnícího účtu. Nejednoznačný/porušený stav se nevyřeší smazáním materiálu ani falešným úspěchem. Údržbu lze vypnout přes rallye.auth.apple-registration-maintenance.enabled.
- Self/ADMIN cancellation zůstává lokální atomická operace: D10, poslední ADMIN, posádky/převody, audit, sessions/granty a idempotence. Před odstraněním identity odpojí případy a založí jednu trvalou úlohu na každý prostředek. Enqueue chyba rollbackne celé zrušení. Apple API se v cancellation transakci nevolá.
- Nová registrace stejné Apple identity je blokována do vyřešení všech případů za bariérou. Týká se i prostředků zachycených během čekající revokace. Cookie formuláře s pouze shodným navrženým e-mailem se zneplatní, není to důvod revokovat jinou Apple identitu.

## Worker, chyby a odstranění materiálu

Worker při zapnuté konfiguraci běží po 60 sekundách, nejvýše 5 úloh na běh. Existující FOR UPDATE SKIP LOCKED / dvouminutová lease koordinuje instance; převzetí je commitnuté před sítí. Volání POST https://appleid.apple.com/auth/revoke používá client_id uloženého případu, backendem podepsaný client_secret a dešifrovaný token s token_type_hint=refresh_token. Přesměrování se nenásledují, připojení má 5s timeout a celý request 10s timeout. Tělo odpovědi se nezapisuje do logu ani evidence a nepotřebuje se pro rozhodnutí.

Pouze HTTP 200 znamená dokončení (Apple tak potvrzuje i dříve zneplatněný token). Ověření aktuální lease/verze, SUCCEEDED, odstranění credential řádku včetně ciphertextu/IV/key_id, uzavření vyřešeného případu a SYSTEM audit APPLE_REVOCATION_SUCCEEDED commitnou společně. Chyba DB/auditu rollbackne vše. Po expiraci lease se pokus zopakuje; Apple revokace je opakovatelná. Stará lease nesmí měnit výsledek ani odstraňovat materiál. Lease není příslib přesně jednoho síťového doručení po pádu či dlouhém zastavení procesu.

| Výsledek | Postup |
| --- | --- |
| Síťová chyba/timeout, HTTP 408, 429, 5xx | RETRY_WAIT, 1 min → 2 min → 4 min, dále nejvýše jednou za 24 hodin; materiál zůstává. |
| HTTP 400/ostatní odmítnutí, nečekané 2xx/redirect | BLOCKED / PROVIDER_REJECTED, bez automatických HTTP opakování. Prověřit client kontext a token. |
| HTTP 401/403 nebo neplatná klientská konfigurace | BLOCKED / CLIENT_CONFIG, opravit podpis/konfiguraci. |
| Chybějící prostředek nebo nemožné dešifrování | BLOCKED / MATERIAL či KEY; neoznačovat za úspěch, obnovit správnou vazbu/klíč. |

Nedokončené úlohy a materiál zůstávají do vyřešení. Dokončená metadata případu/jobů se odstraňují po 30 dnech od dokončení celého případu; ciphertext je pryč ihned při potvrzeném úspěchu. Audit podléhá běžné auditní retenci. Worker nepřidává tabulky ani nový stavový automat. V15 upravuje existující triggery pro kontrolované dokončení a ochranu bariéry. V1–V14 se nepřepisují; přesný migration guard přijímá známou V15 a odmítá budoucí V16. V14 nadále odmítá nepřiřazené prostředky bez jejich mazání/backfillu.

## Konfigurace

Výchozí revokace je vypnutá. Pro skutečný Apple provoz nastavit společně s existujícím Apple providerem:

| Proměnná prostředí | Význam |
| --- | --- |
| RALLYE_APPLE_REVOCATION_ENABLED=true | Zapne worker a backendové generování Apple client-secret JWT. |
| RALLYE_APPLE_TEAM_ID | Team ID (10 znaků). |
| RALLYE_APPLE_KEY_ID | Sign in with Apple Key ID (10 znaků). |
| RALLYE_APPLE_PRIVATE_KEY_FILE | Absolutní cesta k chráněnému PKCS#8 .p8 souboru mimo Git; v Dockeru read-only secret/volume. |
| RALLYE_APPLE_CLIENT_IDS | Čárkou oddělené přesné povolené client_id, včetně kontextů dosud čekajících úloh. |

JWT má ES256/P-256, kid, iss=Team ID, sub=konkrétní client_id, aud=https://appleid.apple.com, iat=UTC a exp za 5 minut. Stejný signer se při zapnutí používá pro Apple OAuth token exchange; statický provider client-secret není v tomto režimu potřeba. Google konfigurace se nemění. Pro lokální syntetické testy zůstává původní statický testovací secret, worker je vypnutý.

Zachovat původní rallye.auth.apple-vault konfiguraci a všechny AES klíče potřebné pro nedokončené prostředky; podpisový .p8 klíč je jiný klíč než AES klíče. Neukládat je do repozitáře. Zakázat HTTP/TLS wire logging: klient odmítne odeslat token při nastaveném jdk.httpclient.HttpClient.log, jdk.internal.httpclient.debug nebo javax.net.debug. Aplikační výpis blokace obsahuje pouze UUID úlohy, kategorii a HTTP status. Nezapínat payload logování ani na proxy.

## Dohled a řízené opakování

Bez nového UI nebo provozních endpointů. Pro diagnostiku stačí administrátorský audit a read-only SQL, bez výpisu tokenových sloupců:

```sql
SELECT id, case_id, version, status, attempts, next_attempt_at, last_error,
       last_http_status, lease_until, completed_at
FROM apple_revocation_job
WHERE status <> 'SUCCEEDED'
ORDER BY next_attempt_at, id;
```

BLOCKED zůstává uložený a loguje se při přechodu. Po opravě konfigurace/klíče provede oprávněný DB operátor cílené opakování, nikoli plošné mazání nebo změnu na SUCCEEDED. Následující psql příklad vyžaduje vědomé nastavení job_id a očekávané version; úspěšná změna i audit jsou atomické:

```sql
BEGIN;
WITH changed AS (
  UPDATE apple_revocation_job
  SET status='PENDING', last_error=NULL, last_http_status=NULL,
      next_attempt_at=now(), updated_at=now(), version=version+1
  WHERE id=:'job_id'::uuid AND version=:'version'::bigint AND status='BLOCKED'
  RETURNING id
)
INSERT INTO audit_event(id,occurred_at,actor_kind,event_type,target_id,target_type)
SELECT gen_random_uuid(),now(),'OPERATOR','APPLE_REVOCATION_RETRIED',id,'APPLE_REVOCATION'
FROM changed;
COMMIT;
```

Při změně 0 řádků ověřit aktuální stav/verzi. Zachovat ciphertext a původní client kontext; chybný či ztracený AES klíč nesmí být řešen předstíráním revokace.

## Ověření a uzavření

Běžné unit/HTTP testy a build probíhají bez Apple účtů. Testy klienta mockují HTTP odpovědi; ověřují správný request, podpis, timeout/síťovou chybu a retry/blocked rozhodnutí. PostgreSQL testy používají rallye_test a mock Apple klienta: dokončení a odstranění ciphertextu, dočasnou chybu/retry, blokaci, souběh, stale lease, rollback auditu, 30denní úklid a ochranu bariéry. Výsledky jsou v docs/testing.md. Celou PostgreSQL sadu spouští výhradně uživatel:

```powershell
.\mvnw.cmd -Pit verify
```

Skutečný manuální smoke vyžaduje již schválené HTTPS prostředí a skutečný Apple client/key: zaregistrovat testovací Apple účet, zrušit jej, ověřit lokální 204 bez čekání na síť, následně SUCCEEDED/audit/odstraněný ciphertext a uvolnění bariéry; zopakovat s dočasně nedostupným API a následným obnovením. Nezveřejňovat tokeny ani diagnostické HTTP body. Takové ověření dosud provedeno nebylo. Konfigurace SMTP a frontend/mobilní platformní stránky/odkazy jsou samostatné provozní/klientské závislosti; tento úkol je nezřizuje. Závěrečný PostgreSQL běh je uživatelem potvrzený; implementační etapa 3c.5 je uzavřena bez další plánované podetapy. Před skutečným Apple provozem nadále zbývá samostatné provider/HTTPS ověření a bezpečná konfigurace.

Zdroje: [Apple Token revocation](https://developer.apple.com/documentation/signinwithapplerestapi/revoke-tokens), [Apple response errors](https://developer.apple.com/documentation/technotes/tn3107-resolving-sign-in-with-apple-response-errors).
