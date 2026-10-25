# 3c.5 – Apple Token Revocation při zrušení účtu: technický návrh

Stav: **3c.5a.1 dokončena a lokálně ověřena jako přechodný perzistentní základ; uživatel potvrdil PostgreSQL BUILD SUCCESS. Zbytek 3c.5 je návrh.** Základ c3d22805ef9507233e14249ba669a02edd7898e0, specifikace 0.8.0 a schválená rozhodnutí níže. Rallye je nový, dosud neprovozovaný systém. Podle výslovného upřesnění uživatele neexistují historické Apple tokeny ani produkční Apple autorizace. 3c.4a je dokončena; 3c.4b se nezahajuje. Aktuální ověření 3c.5a.1: vlastní verify bez PostgreSQL BUILD SUCCESS, 349 testů. Apple volání ani PostgreSQL testy Codex nespouštěl.

## 1. Schválená pravidla a rozhodnutí

- D06: nezbytný šifrovaný revokační prostředek; revokace při self i ADMIN zrušení; trvalá práce a retry mimo lokální cancellation transakci, bez obnovení zrušeného účtu.
- D09/D10: jedna neměnná identita; LOCAL heslo; GOOGLE/APPLE platná relace a výslovné potvrzení. Žádné auth_time, max_age ani nové Apple přihlášení pro cancellation. CSRF, audit, poslední ADMIN, anonymizace a historie zůstávají.
- D11–D13: zachovat dokončené mobilní flow, launch, PKCE, 60s grant a exchange. Nezavádět správu dalších identit.
- Schváleno: novou registraci stejné Apple identity blokovat do dokončení předchozí revokace. Ostatní Apple identity a LOCAL/GOOGLE tato blokace neomezuje.
- Schváleno: nedokončené úlohy uchovávat do vyřešení; dokončená minimální metadata 30 dní, bez tokenového ciphertextu.
- Schváleno: client-secret JWT generuje backend z chráněného privátního klíče; monitoring a řízené opakování blokovaných úloh, bez ADMIN UI.
- **D14 – schváleno:** po definitivní expiraci všech souvisejících registračních kontextů lze automaticky zahájit revokaci, pouze pokud je jednoznačně doloženo, že prostředek nepatří aktivnímu účtu ani jiné platné registraci. Při nejednoznačnosti případ zůstane BLOCKED k prověření.
- **D15 – zrušeno:** původní návrh legacy karantény se neimplementuje. Číslo se neznovupoužívá ani nepřečíslovává. Odpadá rekonstrukce subjectu z historických tokenů, identifikační refresh, historický backfill i širší blokace kvůli neexistujícím prostředkům.

Původní pracovní otázky Q1/Q2/Q4/Q6 jsou uzavřené výše uvedenými rozhodnutími; Q5 uzavírá D14 a Q3 odpadá upřesněním nového systému a zrušením D15. Nejde o požadavek implementovat tyto funkce tímto dokumentačním zadáním.

## 2. Existující implementace a skutečně chybějící části

| Oblast | Dnešní kód | Co doplnit |
| --- | --- | --- |
| Ověření | ExternalOidcVerifier ověřuje ID token, issuer/subject, audience, podpis, časy a nonce; vrací refreshToken. | Předat také přesný client kontext skutečně použitý při exchange; nečíst později jinak nastavený client_id. |
| Šifrování | AppleTokenVault: AES-256-GCM, náhodné IV 12 bajtů, tag 128 bitů, AAD JSON [credential UUID, client_id], AES klíče podle key_id mimo Git. | Zachovat; staré klíče ponechat, dokud jsou nutné pro uložené prostředky a zálohy. Rotace klíče během budoucího provozu je běžný provozní stav. |
| Uložení V9 | apple_revocation_credential: id, account_id bez FK, registration_id FK ON DELETE SET NULL, client_id, key_id, IV, ciphertext, created_at. Každý nově vrácený refresh token je samostatný řádek. | Povinná vazba na ověřený subject a autorizační případ, nezávislá na krátkodobém registračním formuláři. Žádné dodatečné hádání vlastníka. |
| Registrace | Nová identita má omezenou registraci; completion může vytvořit ACTIVE nebo PENDING_EMAIL účet. Generic 202 nesděluje kolizi e-mailu. | Zachovat lifecycle všech prostředků při completion, paralelních kontextech, expiraci, kolizi a smazání formuláře. |
| Cancellation | Self/ADMIN sdílejí control → account zámky, audit a místní transakci; prostředky zůstávají i při DELETE/anonymizaci. Guard chybějícího Apple materiálu vrací 503 a rollback. | Atomické enqueue, bariéra stejné identity, worker a dokončení. Žádná vzdálená síť v cancellation transakci. |
| Relace a ledger | Existující sessions, mobilní granty a oprávnění se při zrušení odvolají; ADMIN replay je 24 h. | Protokolová tajemství nadále neukládat do CommandStore; job deduplikovat trvale podle credential UUID. |
| Client-secret | Nyní konfigurační hodnota, bez generátoru JWT. | Backendový podpis a obnova pro konkrétní client_id; revokační profily nezávislé na zapnutí interaktivního loginu. |

Relevantní kód: AppleTokenVault, ExternalOidcVerifier, ExternalLoginService, ExternalAccountStore, AccountCancellationService/Store, MobileCallbackService a MobileExchangeService. V1–V12 se nemění. Existující simulované testy zachování ciphertextu nejsou ověřením vzdálené revokace.

## 3. Cílový invariant a schválená přechodná hranice

Cíl po dokončení 3c.5a.2/V14: při prvním bezpečném DB commitu každého Apple tokenu musí být doloženy současně následující údaje. Již v 3c.5a.1 je vyžadují všechny nové interní operace; původní ukládací cesta zůstává výslovně mimo tento globální invariant do V14:

1. Validovaný Apple issuer/subject a z něj deterministicky odvozený identity_digest pro korelaci. Digest je SHA-256 kanonického (issuer, subject), pseudonymní osobní údaj, ne autentizační prostředek. Jeho vazba vzniká přímo z validovaného důkazu, nikdy z e-mailu.
2. Přesný client_id a serverový klientský profil použitý pro získání tokenu; žádný klientem předaný libovolný client_id nebo URL.
3. Konkrétní apple_authorization_case a jeho generace, původní flow a aktuální vlastník: existující účet nebo konkrétní rozpracovaná registrace.
4. Šifrovaný refresh token a potřebné key_id/IV/AAD údaje.

Pokud údaje nelze ověřit, autorizace nesmí být úspěšná, nesmí vzniknout session ani mobilní grant a nesmí se commitnout neidentifikovatelný credential. Žádná větev nepovoluje NULL digest/NULL case jako později opravovanou standardní situaci. Konflikt později poškozených vazeb se řeší jako běžný incident BLOCKED, ne jako dovolený typ příjmu tokenu.

Účet může být později odstraněn a registrační formulář může expirovat; trvalý případ pak vlastní revokační práci s doloženým původním account/registration UUID. To není token bez identifikovatelného původu. Samotný FK registration_id z V9 nesmí být jedinou evidencí vlastnictví nebo subjectu. Původní generace a credential se nepřevazují na znovu registrovaný účet.

## 4. Uložení a životní cyklus registrací

Jediný INSERT tokenu nyní vede přes ExternalAccountStore.credential z ExternalLoginService.preserveApple. Webový i mobilní callback používají stejnou cestu; klientský refresh pro identifikaci uživatele se do 3c.5 nepřidává. Access/ID tokeny se pro tento návrh nově dlouhodobě neukládají.

| Událost | Navržené chování |
| --- | --- |
| Známý ACTIVE účet, nový refresh token | Uložit se subject/client/case a účtem; všechny prostředky dané generace zahrnout při cancellation. Nevybírat jen poslední řádek. |
| Opakovaný login bez nového refresh tokenu | Zachovat dosavadní bezpečný postup s existujícím prostředkem stejného účtu/clienta. Nevytvořit fiktivní credential ani přijmout novou registraci bez potřebného prostředku. |
| Nová ověřená identita | V témže capture commitu vytvořit/ověřit rozpracovanou registraci, případ a credential. Rozpracovaná registrace nemusí již mít klientovi doručenou cookie, ale vždy má konkrétní UUID a deadline. |
| Úspěšné completion | Atomicky přejít na ACCOUNT_BOUND včetně prostředků souvisejících kontextů stejné identity a doložené generace. ACTIVE i PENDING_EMAIL jsou účty, nikoli opuštěné formuláře. |
| Více kontextů stejného subjectu | Pod společným zámkem správně přiřadit k témuž případu/generaci; nevytvořit další identitu. Dokud existuje platný související kontext, D14 se neuplatní. |
| Kolize e-mailu s jiným účtem | Zachovat generic 202, nic neslučovat podle e-mailu. Případ zůstává bez vzniklého účtu, s vlastní identitou a lifecycle; po definitivní expiraci jej posoudí D14. |
| Chybný profil nebo rollback completion | Již uložené prostředky přežijí; retry je možný jen do původní expirace. Nevydat plnou relaci. |
| Chyba odeslání aktivačního e-mailu | Dnešní odesílání je až po commitu; PENDING_EMAIL účet a prostředek zůstávají. Nerevokovat jako opuštěnou registraci, zachovat opětovnou žádost o aktivaci. |
| Replay completion/callbacku | Nevydat další oprávnění ani duplicitní job. Každý skutečně nově zachycený token musí přesto mít vlastní evidenci a případ. |
| Zavření browseru nebo odstranění cookie | Samo není důkazem opuštění; žádné nové veřejné cancel API. Rozhodují serverové kontexty a jejich definitivní expirace. |
| Purge expirovaného formuláře | Nejprve transakčně zachovat případ, původ a stav. Mazání FK nesmí odstranit jedinou vazbu na subject. Pravidelný reconciler nečeká na příští login. |
| Cancellation a deleteIdentities | Před smazáním kontextů převést všechny doložené prostředky stejné identity/generace do revokačního lifecycle. Kontekst jiného subjectu zasažený pouze shodou e-mailu není důvod odvolat cizí autorizaci. |
| Callback během bariéry revokace | Nevydat přístup. Pokud už Apple vrátil nový validovaný token, bezpečně jej zachytit s konkrétní registrací/případem před obchodním odmítnutím a zahrnout do příslušné revokační práce. Nezahodit jej rollbackem AuthFailure ani neuvolnit bariéru před vyřešením všech položek. |

Capture je krátká samostatná transakce po ověření provider odpovědi, před obchodním dokončením callbacku. Pod control zámkem najde aktuální účet nebo vytvoří konkrétní rozpracovanou registraci; současně commitne případ a credential. Následná transakce teprve atomicky spotřebuje flow a vydá přípustný obchodní výsledek. Soukromý kontext při blokovaném výsledku nesmí umožnit obejít bariéru přes completion. Pád či rollback druhé transakce neztratí již uložený token ani jeho vlastníka.

Nelze garantovat distribuovanou atomitu mezi odpovědí Apple a prvním DB zápisem: při pádu před capture commitem nebyl token bezpečně uložen. Taková chyba nesmí být prezentována jako úspěšná autorizace. Záruka úplného lifecycle platí pro všechny commitnuté prostředky, nikoli pro token, který se backendu nepodařilo přijmout a uložit.

### D14 – rozhodnutí reconcileru

Pod control/identity pořadím zámků ověřit definitivní expiraci **všech** souvisejících kontextů, ne pouze jednoho cookie/formuláře. Pokud prostředek patří existujícímu účtu, jiné platné registraci nebo dosud přípustnému souvisejícímu procesu, automatické revoke neprovést. Ani DISABLED/PENDING_EMAIL účet není automaticky opuštěná registrace. Úplná nepřítomnost účtu/platné registrace musí být doložena k témuž subjectu a generaci.

Při jednoznačném splnění D14 v jedné transakci uzavřít případné nepoužitelné kontexty, nastavit REVOCATION_REQUIRED, založit bariéru a joby s důvodem REGISTRATION_ABANDONED. Bez další grace period po definitivní expiraci; kontexty mají již schválených 15 minut. Změna vlastníka/completion, která vyhraje zámek dříve, revokaci zabrání; pokud vyhraje rozhodnutí D14, pozdější completion nevytvoří účet přes bariéru. Při nejednoznačnosti BLOCKED k prověření, bez smazání materiálu a bez falešného dokončení.

## 5. Zjednodušený návrh V13

Implementovaná V13__apple_revocation_foundation.sql nemění V1–V12 ani nepřepisuje tokenová data. Uživatel výslovně schválil dvoufázové zavedení: V13 přidává nový model a volitelný FK case_id do existujícího úložiště; V14 v 3c.5a.2 zavede globální povinnou vazbu současně s napojením callbacku, associate/purge a cancellation. Nejde o rekonstrukci historických autorizací. Syntetické V12 fixtures a původní zápisy bez case_id musí přes V13 projít beze změny; je to záměrný ověřovaný kontrakt této podetapy.

| Objekt | Navržené minimum |
| --- | --- |
| apple_authorization_case | UUID, povinný identity_digest, issuer, client kontext, generace, původní flow/registration UUID, stav a vazba na aktuální účet/registraci nebo doloženou revokační fázi. Bez klíčů či tokenů. |
| apple_revocation_credential | Ve V13 volitelné case_id kvůli původní cestě; nové repository vždy uloží case_id, shodný client a vlastníka. V14 udělá vazbu globálně povinnou. UUID/client/AAD a ciphertext spravovaného prostředku jsou neměnné. |
| Seskupení práce | Ve 3c.5a.1 je skupinou samotný authorization_case, samostatná duplicitní batch tabulka není potřeba. Jeden případ může mít více prostředků a jeden job pro každý; reason je neměnná součást jobu. Seskupení případů při skutečné cancellation zůstává 3c.5a.2. |
| apple_revocation_job | UUID, case_id FK, credential_id UNIQUE, reason, status, attempts, next_attempt_at, lease_id/lease_until, last_error z pevného enumu, HTTP status, version a časová metadata. Indexy due/lease. |
| apple_revocation_barrier | Ve V13 identity_digest PK a created_at; vazba na případy přes digest. Mazání bariéry s nedokončeným případem je zakázané. Vyřešení/closed_at ochrana starších kontextů není v této podetapě implementována. |

Nezavádět resolution_source pro rekonstrukci, nullable subject pro dodatečné zjišťování, scope-wide karanténu ani opravný refresh klient. Běžný BLOCKED kvůli konfliktu/poškození se zachovává. Stav případu: REGISTRATION_PENDING → ACCOUNT_BOUND nebo CLEANUP_DUE; D14/cancellation → REVOCATION_REQUIRED → COMPLETED; BLOCKED jen pro skutečnou chybu/nejednoznačnost, nikoli jako standardní způsob uložení neidentifikovaného tokenu.

Vlastnické přechody a CHECK/FK musí vynutit platný stav případu: při prvním uložení účet nebo konkrétní registrace; při řádném zrušení přežití původních UUID a subject/client vazby mimo destruktivní kaskádu. Žádný credential bez případu. Po úspěchu jobu odstranit konkrétní credential; job může zachovat jeho původní UUID bez FK kvůli 30denní minimální historii. Předčasně chybějící credential je BLOCKED_MATERIAL, ne SUCCEEDED.

V navazující 3c.5a.2 ExternalOidcVerifier předá ověřený client kontext; capture/store vynutí globální invariant; ExternalLoginService.complete, purgeExpired a AccountCancellationStore.deleteIdentities zachovají lifecycle při všech přechodech. V13 přidat výslovně do PreRaceAccountParticipationFacts; test neznámé budoucí migrace posunout na V14. Auditní CHECK rozšířit novou migrací, pokud to nové události vyžadují. CommandStore a jeho 24h retence se nemění.

## 6. Cancellation, worker a transakce

1. Self/ADMIN cancellation pod dosavadním control → account zámkem ověří D10, participaci a posledního ADMIN. Před odstraněním identity založí bariéru a trvalé joby pro všechny prostředky příslušné generace.
2. Účet DELETE/ANONYMIZED, odvolání sessions/grantů, převody/posádky, enqueue, audit a ADMIN idempotence commitnou společně. Enqueue failure rollbackne vše. Stávající 204 potvrzuje lokální zrušení a trvalé převzetí práce, nikoli dokončení Apple API.
3. Worker krátce claimne způsobilý job přes FOR UPDATE SKIP LOCKED, přidělí lease UUID a deadline, zvýší attempts a commitne. Nikdy nezamkne job a poté nečeká na control/account v opačném pořadí.
4. Mimo transakci dešifruje token původním AAD a key_id a zavolá pevný Apple revoke endpoint. Neprovádí refresh pro identifikaci.
5. Dokončovací transakce ověří status a lease UUID. Při potvrzeném 200 atomicky zapíše úspěch/audit a odstraní konkrétní ciphertext/IV/nepotřebné klíčové vazby. Chyba DB/auditu rollbackne dokončení; další pokus použije tentýž prostředek.
6. Bariéra se uzavře až po vyřešení všech položek, včetně prostředků zachycených během blokovaného callbacku. Původní token/job se nikdy nepřeváže na nový účet.

Deduplikace podle credential UUID a lokální transakce zabrání duplicitnímu enqueue při ADMIN replay i self/ADMIN souběhu. Síťové doručení přesně jednou při pádu či timeoutu nelze slíbit. Lease UUID chrání DB zápis, ne již odeslaný HTTP požadavek. Pád po Apple 200 před DB commitem vede k bezpečnému opakování, nikoli obnovení účtu. Restart obnoví polling trvalých jobů.

## 7. Apple API a standardní chybové stavy

Pevné `POST https://appleid.apple.com/auth/revoke`, form-urlencoded: client_id, client_secret, token, token_type_hint=refresh_token. Použít client původní autorizace. HTTP 200 znamená úspěšné odvolání nebo dřívější neplatnost předaného tokenu; prázdné tělo je přípustné. Toto podporuje retry po ztracené odpovědi, nikoli libovolné závěry o rozsahu mezi Apple skupinami. [Apple Token revocation](https://developer.apple.com/documentation/signinwithapplerestapi/revoke-tokens).

| Výsledek | Bezpečné zpracování |
| --- | --- |
| 200 | SUCCEEDED pro konkrétní token, atomické odstranění jeho materiálu. |
| Timeout, DNS/spojení, 429, 5xx | RETRY_WAIT; nejistý výsledek není úspěch. |
| invalid_client / podpisová konfigurace | BLOCKED_CONFIG; po opravě řízené retry, bez ztráty tokenu. |
| invalid_request, invalid_grant, jiné 4xx | BLOCKED_REVIEW, nikoli automatické SUCCEEDED. |
| Chybějící token, poškozený ciphertext nebo neplatné DB vazby | BLOCKED_MATERIAL/INTEGRITY/RELATION; incident, zachovat dostupné důkazy, nevymazat práci. |
| Nedostupný AES klíč | BLOCKED_KEY; obnovit správný klíč, nikoli založit nový a předstírat dešifrování. |
| Redirect/neznámý status/nadměrná či vadná odpověď | Bezpečná chyba bez raw body; neposlat tajemství jinému hostu. |

Tyto chyby mohou vzniknout i v novém systému. Nejsou označovány jako historická migrace. [Apple TN3107](https://developer.apple.com/documentation/technotes/tn3107-resolving-sign-in-with-apple-response-errors) nedovoluje vydávat libovolné invalid_grant za dokončenou revokaci.

Současný guard 503 při chybějícím revokačním materiálu se nemění. [Apple TN3194](https://developer.apple.com/documentation/technotes/tn3194-handling-account-deletions-and-revoking-tokens-for-sign-in-with-apple) popisuje i postup při nedostupném tokenu; případná budoucí výjimka pro ruční nápravu skutečného incidentu není podmínkou startu 3c.5a.1 a tímto se nezavádí.

Technické výchozí návrhy: poll 30 s, connect 5 s, read 10 s a celkový limit 20 s, lease 120 s. Exponenciální backoff od 1 min do 24 h s jitterem a ohraničeným Retry-After; neomezené retry pro dočasné chyby, alarm po 24 h. Celkový deadline musí klient skutečně vynutit, samotné connect/read limity nestačí. Časy UTC, jednotný DB čas pro claim/lease. Stale worker nemůže přepsat výsledek nové lease. BLOCKED se po odstranění příčiny opakuje řízeně a auditovaně, ne neustálou rychlou smyčkou.

## 8. Opakovaná registrace a bezpečnost

Bariéra platí jen pro stejnou doloženou Apple identitu do dokončení revokace. Kontroluje se po ověření subjectu v callbacku, při completion a před vydáním session či mobilního grantu. Začátek Apple stránky nelze filtrovat podle identity, kterou backend ještě nezná. Nikdy neslučovat účty podle e-mailu. D14 a registrace/cancellation se serializují pod control zámkem.

Po potvrzeném dokončení ponechat technickou ochranu proti dřívějším flow/registracím: closed_at/epocha, nové přihlášení musí být nový tok. Korelační záznam nejméně 15 minut po dokončení a dokud má platné starší reference; neprodlužovat state/grant. Nejde o plošné blokování nových identit. Nelze absolutně garantovat izolaci libovolně zpožděných vzdálených zpráv; skutečný Apple smoke musí tento provozní limit vykazovat odděleně. Zrušený Rallye účet se neobnovuje.

Client-secret JWT podepisuje backend ES256/P-256 s chráněným .p8 klíčem: kid, iss Team ID, sub přesný client_id, aud https://appleid.apple.com, iat/exp UTC. Navržená platnost 5 minut. Podpisový Apple klíč není AES vault klíč. Použít knihovnu JOSE, omezenou cache a allowlist klientských profilů; žádné klíče/tokeny v Gitu či CLI argumentech. Vypnutí interaktivního loginu nesmí vypnout zpracování již vzniklé revokační práce.

Po dokončení odstranit tokenový ciphertext, hotová metadata uchovat 30 dní. Nedokončené úlohy nečistí auditní ani idempotentní retence. Zálohy mají vlastní retenční pravidla; DB DELETE není okamžité fyzické odstranění všech kopií. Monitorovat pouze počty/stáří/stavy/poslední úspěch a konfigurační chyby, bez subjectů či tokenů v labels. Řízený neveřejný operátorský příkaz nad job UUID znovu ověří příčinu a bezpečně připraví retry; bez ADMIN UI a bez veřejného worker API.

Audit: enqueue/success/blocked/řízený retry, pouze interní UUID a bezpečný kód důvodu. Neauditovat raw token, subject/digest, IV/ciphertext ani každé síťové opakování. Zachovat no-store, redigované DTO/diagnostiku a zákaz wire/payload logování; reverse proxy/APM/heap dumps vyžadují stejnou ochranu. TLS validace, žádné redirects a omezené HTTP body. Java String nedovoluje slíbit úplné vymazání tokenu z heapu.

## 9. Rozdělení implementace a ověření

| Podetapa | Rozsah a kritérium dokončení |
| --- | --- |
| **3c.5a.1 – databázový základ a invariant nových prostředků** | Návrh/implementace V13, case/job/batch/barrier a povinné subject/client/case/owner vazby. Žádná rekonstrukce nebo karanténa historických dat. Přesný migration guard V13 a odmítnutí neznámé V14. Perzistentní operace, jednotkové a PostgreSQL testy omezení/rollbacku. |
| **3c.5a.2 – napojení callbacku, registrace a cancellation** | Capture s úplným vlastníkem; zachování při purge/completion/rollbacku; atomické self/ADMIN enqueue; D14 rozhodování a bariéra, souběhy s completion/mobilním grantem/exchange. Bez živého Apple workeru. |
| **3c.5b – klient a worker** | Backendový JWT podpis, pevný revoke klient, lease/fencing/poll/retry, standardní BLOCKED chyby, atomické dokončení a odstranění tokenu. Bez identifikačního refresh toku. |
| **3c.5c – konsolidace a provoz** | Regrese D09/D10/D14 a mobilu, monitoring/řízené retry/runbook, dokumentace a oddělená příprava skutečného HTTPS smoke. Žádné automatické zahájení 3c.4b. |

Unit/HTTP: ověření povinných vazeb od prvního uložení, redakce, klasifikace Apple chyb, signing a timeout/backoff; stávající CSRF/LOCAL heslo/externí potvrzení/ADMIN ochrany. HTTP stub pro revoke 200/4xx/5xx, redirect a ztracenou odpověď; není veřejným mock endpointem aplikace.

PostgreSQL: omezení a stavové přechody nového modelu, první capture commit a rollback obchodní části, completion a více kontextů, e-mailová kolize jiného subjectu, PENDING_EMAIL a selhání doručení, purge bez ztráty péče, D14 souběhy a nejednoznačnost, self/ADMIN replay/souběh, DELETE/anonymizace, více tokenů, callback za bariérou, souběžné workery/restart/lease/stale výsledky, chybějící token/klíč/konflikt jako běžné chyby, atomita audit+úspěch+odstranění ciphertextu. Nevytvářet testy obnovy historických Apple dat.

Běžné testy bez DB, PostgreSQL pouze uživatelem podle AGENTS.md přes `.\mvnw.cmd -Pit verify` a TestDatabaseInitializer/rallye_test. Postman až v implementační části rozliší lokální negativní kontroly a skutečné disposable Apple HTTPS ověření; bez syntetických účtů v aplikační DB nebo veřejných fake providerů. Zde se kolekce nemění a žádné testy neběžely.

## 10. Stav návrhu a blokace

**Před zahájením 3c.5a.1 nezůstává skutečně blokující produktová otázka.** D14 je schválené, D15 zrušené a nový systém nemá historické Apple autorizace. Podrobné DDL a testy budou implementační prací podle výše uvedených invariantů, ne novým rozhodováním o rekonstrukci neexistujících dat. Nedostupný privátní klíč/HTTPS prostředí blokuje živé ověření nebo provozní aktivaci, nikoli lokální implementaci s mocky.

Uživatel následně schválil implementaci pouze 3c.5a.1 a dvoufázové zavedení omezení. Níže je skutečný implementační stav; předchozí popis callbacku, D14 orchestrace a workeru zůstává cílovým návrhem. V1–V12 a Postman se nemění; bez commitu/pushe/deploymentu. Uživatelské .gitignore a docs/Rallye_specifikace.md jsou nedotčené.

## 11. Implementovaný stav 3c.5a.1 – přechodná infrastruktura

**Samostatná 3c.5a.1 se nesmí nasadit do prostředí umožňujícího skutečné Apple autorizace.** Před tím musí být dokončena i 3c.5a.2/V14 a ověřeny globální invarianty. Tato podetapa sama ještě nezajišťuje lifecycle všech Apple tokenů. Nezavádí opravu historických prostředků ani nové úspěšné revokační operace.

### Databáze

V13 vytváří apple_authorization_case, apple_revocation_job a apple_revocation_barrier, přidává nullable credential.case_id a potřebné indexy, CHECK/FK/UNIQUE a triggery. Case má povinný issuer/subject, SHA-256 digest kontrolovaný PostgreSQL, client_id, generation, jedinečný původní Apple LOGIN flow a přesně jednoho počátečního vlastníka (ověřený účet nebo registrační kontext stejné APPLE identity). Subject je chráněný interní údaj, není v auditním/HTTP výstupu ani toString. Case typ není tvrzení, že samy repository kryptograficky ověřují provider: v 3c.5a.2 jej vytvoří až ověřená aplikační cesta.

Neměnné vazby chrání triggery. Odložené owner kontroly při COMMIT umožňují transakčně přesunout případ i spravované prostředky z registrace na účet nebo do revokační fáze, ale nedovolí commit jejich nesouladu. Při REVOCATION_REQUIRED musí při commitu existovat bariéra a práce pro všechny prostředky. Jedinečné credential_id zabraňuje duplicitní práci; více prostředků jednoho případu správně tvoří více jednotlivých jobů. Souběžný enqueue je serializován control → case; původní owner UUID se zachovají i po odpojení živého FK.

Smazání nedokončeného případu/jobu/materiálu a odstranění bariéry s pending případem jsou zakázané. Spravovaný ciphertext nelze svévolně přepsat nebo odpojit od case. **V13 zatím odmítá i přímý SQL přechod/INSERT SUCCEEDED nebo COMPLETED.** Budoucí ověřené dokončení bude vyžadovat další výslovnou implementaci a navazující migraci, nikoli úpravu již aplikované V13. Existující tabulka drží ciphertext, job jen jeho UUID; po budoucím doloženém úspěchu bude možné odstranit konkrétní material row a ponechat minimální metadata jobu bez tokenového FK.

### Interní operace

AppleRevocationStore má Propagation.MANDATORY. AppleRevocationFoundation dodává vlastní transakce: create, addMaterial přes stávající AES-GCM vault/AAD, bindAccount, enqueue, find/jobs, claim, reportFailure a retryBlocked. Nemá controller, scheduler, Apple klienta ani dokončovací metodu. Tato interní API nejsou uživatelská autorizace; rozhodování D10/D14 a bezpečnostní audit skutečné orchestrace zůstávají v 3c.5a.2. Žádná současná aplikační cesta je nevolá.

Job přechody: PENDING/RETRY_WAIT → PROCESSING; expirované PROCESSING → PROCESSING s novou lease a navýšenými attempts; PROCESSING → RETRY_WAIT/BLOCKED; BLOCKED → PENDING řízenou CAS operací. Claim používá FOR UPDATE SKIP LOCKED a podmínku due/lease; hlášení chyby vyžaduje shodu UUID lease, version a stále platnou lease. Stale zápis vrací false. Počet pokusů, neměnné identifikátory a stavový tvar kontroluje také DB. Žádný obecný setter libovolného stavu není dostupný.

Existující ExternalAccountStore.credential(), associate() a purgeExpired(), callback, completion, cancellation a mobilní flow zůstaly nezměněné. Přesný migration guard přijímá pouze očekávanou V13; neznámá V14 je zatím bezpečně odmítnuta. Budoucí 3c.5a.2 ji přidá výslovně při implementaci.

### Ověření a další krok

Codex ověřil Microsoft JDK 21.0.12.1 a Maven Wrapper. `verify` bez PostgreSQL: **BUILD SUCCESS, 349 testů, 0 failures/errors/skipped**, JAR sestaven. Zahrnuje 13 nových unit testů a jednotkovou regresi přesného V13 guardu. PostgreSQL IT Codex pouze zkompiloval; jejich úspěšný běh následně potvrdil uživatel jako BUILD SUCCESS. Nové AppleRevocationIT má **19 scénářů** pro vazby, chybějící identifikátory, jedinečnost, vlastníky, více prostředků, šifrování/ochranu materiálu, zakázané dokončení, rollback, souběžné enqueue/claim, lease takeover/stale CAS a upgrade syntetické V12 se zachováním nezměněného starého writeru/registration cleanup.

Podle AGENTS.md spustí uživatel výhradně proti rallye_test:

```powershell
.\mvnw.cmd -Pit verify
```

Uživatel potvrdil BUILD SUCCESS celého PostgreSQL integračního běhu proti rallye_test včetně V13, PL/pgSQL kontrol a DB souběhů. 3c.5a.1 je implementačně dokončena a lokálně ověřena. Neproběhl Postman ani skutečné Apple/HTTPS ověření. `git diff --check` prošel. 3c.5a.2/3c.5b/3c.5c se nezahajují.

### Hranice použití nového modelu

Povinné owner FK nových případů záměrně nedovolí smazat jejich živý účet/registrační kontext bez předchozího interního vlastnického přechodu. Původní cesta nyní žádné takové případy nevytváří, proto její nepřiřazené V9 prostředky zůstávají kompatibilní. Ruční propojení nového interního modelu s účtem a následné používání staré orchestrace není podporovaný provozní scénář této přechodné podetapy; právě napojení všech těchto cest je úkolem 3c.5a.2/V14. Kontrola FK se kvůli přechodnosti nevypíná.

### Soubory 3c.5a.1

- src/main/resources/db/migration/V13__apple_revocation_foundation.sql
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/AppleRevocationStore.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/application/AppleRevocationFoundation.java
- src/main/java/cz/mh/rallye/rallye_backend/identity/persistence/PreRaceAccountParticipationFacts.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/AppleRevocationFoundationTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/AppleRevocationIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/PreRaceAccountParticipationFactsTest.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/AccountCancellationServiceIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/ExternalAuthIT.java
- src/test/java/cz/mh/rallye/rallye_backend/identity/MobileLoginIT.java
- docs/apple-revocation-3c5.md
- docs/backend-plan.md
- docs/testing.md

Poslední tři upravené dřívější IT jen posouvají záměrně neznámou budoucí migraci z V13 na V14; ochrana se neoslabuje. Postman, ExternalAccountStore a aplikované migrace se nemění.

Poznámka k běhům: jeden meziběh se 349 testy měl jediné selhání existujícího AuditExportHttpTest.adminStreamsCsvWithDownloadHeadersAndAllFilters (500; ConcurrentModificationException při souběžném zápisu hlaviček MockHttpServletResponse). Nové testy prošly. Beze změny kódu auditu byl celý verify zopakován: **BUILD SUCCESS, 349 testů, 0 failures/errors/skipped**. Tím se nedokládá oprava tohoto ojedinělého testového souběhu; zůstává odděleným pozorováním.
