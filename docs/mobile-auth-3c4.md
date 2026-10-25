# 3c.4 – mobilní externí přihlášení: schválený technický návrh

Stav 2026-10-25: **technický návrh schválen s rozhodnutími D11–D13; dokončen bootstrap 3c.4a.1: PostgreSQL BUILD SUCCESS před bezpečnostními opravami, verify bez DB 306 testů po opravách a následná Postman 21 All Passed (PG/Postman potvrzeno uživatelem); 3c.4a.2 je implementačně dokončena a lokálně ověřena (verify bez DB 317 testů, PG BUILD SUCCESS a Postman 23 All Passed potvrzeny uživatelem); exchange 3c.4a.3 a 3c.4b nejsou implementovány**. Základ: commit 94501a29f9772c620db7786e7280cf87d5c86b6b, [specifikace 0.8.0](Zavody_specifikace_v0.8.0.md), D01/D02/D06–D10, [technický návrh](external-auth-3c.md). Specifikace 0.8.0 se touto dokumentační aktualizací nepřepisuje. D11–D13 jsou následná výslovně schválená rozhodnutí uživatele, evidovaná zde a v backend-plan.md. Po následném zadání jsou implementovány [3c.4a.1](mobile-bootstrap-3c4a1.md) a [callback/grant 3c.4a.2](mobile-callback-3c4a2.md); exchange a klientská integrace níže zůstávají plánem.

## 1. Co je závazné a co již existuje

Závazné D08: systémový prohlížeč, Authorization Code Flow, kontext 10 minut, jednorázový handoff 60 sekund s PKCE S256, jen konfigurované návraty, žádný Bearer v URL. D09: právě jedna neměnná identita LOCAL/GOOGLE/APPLE, externí klíč provider/issuer/subject, nikdy párování podle e-mailu. D01/D02: doplnění profilu a případné vlastní ověření e-mailu před plnou relací. D06: Apple prostředek zachovat pro revokaci; worker není součástí 3c.4. App Links / Universal Links jsou požadovaná forma návratu podle zadání.

| Komponenta | Skutečný stav a využití |
| --- | --- |
| AuthController.login, AuthService, AccountStore | LOCAL s X-Rallye-Client: mobile již vrací JSON token/tokenType/rememberMe. Nejde o JWT ani nový provider refresh token: je to neprůhledný Rallye token, v user_session pouze hash. |
| AuthSessionFilter / AuthConfiguration | Čte cookie nebo Bearer, současné oba odmítá AMBIGUOUS_CREDENTIALS. Každým požadavkem ověřuje DB účet/session a aktuální ADMIN. STATELESS Spring Security neznamená absenci databázových relací. |
| Životnost relací | Nečinnost 24 hodin nebo 30 dní s rememberMe, prodlužování používáním. Bez rememberMe klient drží token pouze po dobu své relace; mobil nesmí předstírat trvalé přihlášení. Logout odvolá jednu relaci, zrušení účtu všechny. Mobilní závodní oprávnění není přihlášení účtu. |
| ExternalProviders | GOOGLE/APPLE konfigurace a pevné issuer/token/JWK adresy, výchozí vypnutí, HTTPS callback a pojmenovaný allowlist returns. Neodvozuje adresy od HTTP klienta nebo Host hlavičky. Současná returns mapa nerozlišuje web a mobil. |
| ExternalFlowService / ExternalFlowStore | LOGIN-only, hash state/nonce/korelace, přesně 10 minut, atomický consume. Challenge je volitelně uložena ve flow, HANDOFF_LIFE=60 s. verifyWebCallback je nadále web-only; controller v 3c.4a.2 používá společný verifyCallback a navazující mobilní větev. |
| PKCE backend–provider | GOOGLE používá S256; verifier se odvozuje z tajné browserové korelace a není totožný s mobileChallenge. APPLE větev dnes neposílá PKCE parametry. To není důkaz, že Apple podporuje či nepodporuje PKCE. |
| ExternalAuthController | Start vrací authorizationUrl a nastaví korelační cookie v klientovi, který provedl POST. GOOGLE GET / APPLE form_post callback vyžaduje tuto cookie. Mobilní HTTP klient a browser cookies nesdílejí. Otevření samotné authorizationUrl z mobilního POST tedy nestačí. |
| ExternalOidcVerifier | Knihovní výměna kódu mimo DB zámky; podpis, issuer, audience/azp, exp/iat a nonce. Bez změny identity podle profilu. Simulované testy nejsou provider interoperabilita. |
| ExternalLoginService | Známé ACTIVE identitě při webovém callbacku vytvoří user_session + LOGIN_SUCCEEDED. Neznámá identita vždy přechází přes omezenou registraci, i když claims obsahují vše. Mobil potřebuje samostatný výsledek bez webové session, ne volat dnešní webový úspěch a převést jeho cookie. |
| Registrace V9 | RALLYE_EXTERNAL_REG, 15 minut, GET current / POST completions, generic 202 bez session; vlastní aktivace 24 hodin. Kolize e-mailu nesloučí účty. Apple relay a chybějící opakované jméno již zohledněny. |
| external_auth_grant V8/V10 | Existuje DB základ MOBILE/LOGIN, hash tokenu, challenge, account FK, volitelná session FK, přesných 60 sekund a consumed_at. Služba vydávání grantu je implementována; služba výměny ani veřejný exchange endpoint zatím nejsou. IT ověřují vydání a úklid, nikoli kompletní mobilní přihlášení. V12 v 3c.4a.2 doplňuje povinné UNIQUE attempt_id a vazbu na konkrétní flow/rememberMe přes pokus. Vydávání je implementované, exchange ne. |
| V10 / rušení účtu | Jedna neměnná identita včetně zákazu DELETE+INSERT náhrady. Cancellation explicitně maže MOBILE granty i při anonymizaci, registrační kontexty a sessions. Apple ciphertext zůstává mimo FK účtu. Nové vazby musejí tento úklid zachovat. |

Související podklady: authentication-2a.md, external-auth-3c1.md, external-auth-3c2.md, single-identity-3c3a.md, account-cancellation-3c3b.md, testing.md. Samostatná 3c.3c je v aktuálním backendovém plánu zrušena jako překonaný rozsah po D09/D10: regrese a dokumentace byly zahrnuty do dokončených 3c.3a/3c.3b, podpora zakázaných identitních toků se neobnovuje. Nejde o zrušení bezpečnostních testů ani o tvrzení skutečného provider ověření. Historické odkazy na plánovanou 3c.3c v jiných podkladech jsou překonány tímto rozhodnutím; AGENTS.md se v tomto dokumentačním úkolu nemění.

## 2. Schválený tok pro existující externí účet

1. Mobil vytvoří náhodný verifier (doporučeno 32 bajtů -> 43 znaků base64url), challenge=BASE64URL(SHA256(ASCII(verifier))), bez paddingu. Verifier neopustí aplikaci do exchange. Aplikace drží oddělený pending pokus; nesmí jej převzít z příchozího odkazu.
2. Mobil zavolá existující POST /api/v1/auth/external/{provider}/requests s purpose=LOGIN, mobileChallenge, returnId a rememberMe. Bez Rallye cookie, X-Rallye-Client: mobile. Mobilní větev vrátí requestId, browserStartUrl a expiresAt. **Ne** provider URL vyžadující cookie vytvořenou v mobilním klientovi. Webový kontrakt zůstane beze změny.
3. Mobil uloží requestId/verifier/očekávaný provider a otevře browserStartUrl v systémovém browseru (Android Custom Tabs nebo standardní browser), nikdy embedded WebView. URL obsahuje jen jednorázový náhodný launch ticket; ten není session ani OAuth autorizační kód. Ukládá se jen jeho hash, nesmí být v přístupových logách.
4. Nový GET browser-launch atomicky spotřebuje ticket a vytvoří flow v jedné DB transakci; teprve po úspěšném commitu odešle korelační cookie a redirect. Pád nebo ztráta odpovědi po commitu vyžaduje nový pokus, nikoli opětovné použití ticketu. Ticket sám nemůže vydat grant ani session. Nastaví bezpečnou korelační cookie **v browseru**, poté 303 na provider authorization endpoint se state, nonce a provider PKCE tam, kde je doloženě podporované. Korelace pro Apple zůstává Secure/HttpOnly/SameSite=None a přesná callback path. Návrh přebírá dnešní jednu korelaci na providera v browseru: souběžné browser pokusy stejného providera mohou bezpečně selhat, nebudou se kombinovat.
5. Backendový callback zkontroluje správnou metodu, jediný state/code nebo error, cookie korelaci, provider, callback URI a deadline. Provider výměna proběhne mimo DB zámky. Claims se ověří dosavadním verifierem; callback stejné identity nezmění profil ani práva podle claims.
6. Transakce znovu ověří a spotřebuje state, vyhledá neměnnou identitu, uzamkne účet a ověří ACTIVE. Uchová Apple materiál stejným vaultem. Pro známý účet vytvoří **jen MOBILE grant**, s hash náhodného kódu, vazbou na flow/requestId, účet, mobileChallenge a původní rememberMe; session_id=NULL. **Nevytvoří webovou cookie ani Rallye session a ještě nezapíše LOGIN_SUCCEEDED.**
7. Po commitu 303 na přesnou nakonfigurovanou App Link / Universal Link adresu. Návrh query: requestId, code, result=login. Žádné provider tokeny, Rallye tokeny, profil ani e-mail. requestId je veřejný korelační identifikátor, ne autentizační tajemství. Mobil odmítne neznámý requestId/provider/host/path a nepoužije jiný lokální verifier jako fallback.
8. Mobil POSTne requestId/code/verifier do exchange. Backend ověří hash kódu, vazbu na konkrétní request/flow/účet, S256 a expiraci, nikoliv pouze shodu verifieru s nějakým posledním challenge. Načte aktuální ACTIVE účet a identitu. V jedné transakci spotřebuje grant, vytvoří existující user_session a zapíše LOGIN_SUCCEEDED. Teprve po commitu vrátí token/tokenType=Bearer/rememberMe v JSON, bez Set-Cookie.
9. Mobil token použije na /auth/me a další API. Uloží jej podle rememberMe; pro trvalé přihlášení použije platformní chráněné úložiště, bez něj jen paměť relace aplikace. Logout a D10 cancellation používají stávající API a pravidla. Nezavádí se vlastní refresh endpoint.

### Čas, chyby a opakování

Autorizační pokus i flow budou mít společnou desetiminutovou hranici od přijetí mobilního startu; browser-launch ji neprodlouží. Při vytvoření flow se použije původní created_at a expires_at, aby zůstalo přesné V8 omezení. Přihlášení dokončené před touto hranicí vydá grant na nových přesných 60 sekund od vytvoření grantu; autorizace a exchange mají různé životnosti. now >= expires_at je neplatné. Registrace má vlastní již schválených 15 minut, není prodloužením state.

Neplatný state/korelace: bezpečná 400, žádný redirect podle nedůvěryhodného klienta. Validně korelované odmítnutí souhlasu: jednorázově uzavřít flow a vrátit pouze obecné result=failed + requestId na uložený cíl. Timeout/zavřený browser: žádná session, mobil odstraní pending kontext a nabídne nový pokus. Nepřebírat raw error_description od providera. Expirace grantu nebo chybný verifier: obecná chyba bez údajů o účtu, nový pokus.

Po úspěšné výměně je kód neobnovitelně spotřebovaný; opakování nevydá druhou relaci ani původní token. Při ztrátě úspěšné HTTP odpovědi klient zahájí nový tok. Nevracet token z CommandStore a nezavádět Idempotency-Key pro protokolová tajemství. Neúspěšná DB transakce rollbackne grant/session/audit; po rollbacku samotného exchange může být retry v rámci 60 s platný. Po neúspěchu callbacku nelze spoléhat na opakovanou výměnu již spotřebovaného provider kódu.

## 3. Nová registrace není dokončený mobilní LOGIN

**Schváleno D12:** zachovat dosavadní browserové doplnění registrace bez paralelního mobilního registračního API.

Neznámá identita v callbacku: vytvořit omezený V9 kontext/cookie a bezpečně přesměrovat browser na samostatně konfigurovanou HTTPS stránku dokončení profilu (ne rovnou na App Link s grantem). Nepřenášet registrační cookie ani její tajemství do mobilu či URL. Existující GET registrations/current a POST registrations/completions se volají v témže browser cookie jar s CSRF. Po generic 202 neoznamovat kolizi ani vznik účtu; stránka může nabídnout návrat do aplikace s obecným registration-submitted + requestId, **bez kódu**. Toto tvrzení nikdy neopravňuje k vytvoření relace.

Potřebné vlastní ověření e-mailu zůstává 24 hodin. Po dokončení/aktivaci mobil zahájí nový LOGIN s novým verifierem a state. Nikdy nevydat 60s grant ještě nehotovému účtu ani jej držet do pozdější aktivace. Ani ověřený provider e-mail neobchází dnešní dokončovací krok nebo unikátnost e-mailu. LOCAL registrace/aktivace a přihlášení zůstávají existující oddělenou cestou.

Současný backend vrací generic 202 bez navigace a nemá hotovou stránku doplnění profilu. Browserovou obrazovku ani Android UI tento backendový krok automaticky nedodá. Reálné end-to-end ověření nové registrace vyžaduje tuto samostatnou integrační část; nejde o blokaci backendové implementace 3c.4a. Backendová větev omezené registrace se implementuje a testuje bez hotové obrazovky. Po dokončení profilu a případné aktivaci se vždy zahájí nové mobilní přihlášení (D12).

## 4. Navržené API (nikoli existující kontrakt)

| Kdo / endpoint | Vstup a úspěch | Přístup a ochrany |
| --- | --- | --- |
| Mobil POST /api/v1/auth/external/{GOOGLE nebo APPLE}/requests | Stávající JSON purpose=LOGIN, returnId=android, rememberMe=false, mobileChallenge=<S256>. 200 stage=MOBILE_BOOTSTRAP, requestId UUID, browserStartUrl, expiresAt. Pole authorizationUrl a webové stage zůstanou pro web beze změny. | Veřejné; mobil bez cookie, explicitní X-Rallye-Client: mobile; nezíská přihlášení z existující relace. Challenge povinná v mobilní větvi; mobilní returnId bez challenge odmítnout. |
| Browser GET /api/v1/auth/external/mobile/launch?ticket=... | Jednorázový ticket; 303 na uloženého providera, Set-Cookie jen pro korelaci. | Veřejné, protokolový ticket, ne plná relace. Nepřijímá redirect_uri/provider/challenge z URL navíc. GET nevydává přihlašovací oprávnění; jednorázovost může při přednačtení odkazu způsobit bezpečné selhání a nový pokus. |
| Provider přes browser GET GOOGLE/callback nebo POST APPLE/callback | Stávající code/state nebo error; nová mobilní větev vybere výsledek podle uloženého flow. 303 do aplikace nebo na profilovou stránku. | Veřejné, state+nonce+cookie+proof; Apple POST má stávající úzkou CSRF výjimku, nikoli obecnou mobile výjimku pro callback. |
| Mobil POST /api/v1/auth/external/mobile/exchanges | JSON requestId UUID, code, verifier. 200 {token,tokenType:"Bearer",rememberMe}. | Veřejné, prokazuje držení kódu+verifieru, X-Rallye-Client: mobile; bez Rallye cookie a bez jiného Bearer. Cookie požadavek odmítnout, ne autentizovat/transformovat jeho session. |
| Browser GET registrations/current / POST registrations/completions | Dosavadní profil / email+displayName, 200 / generic 202. | Registrační cookie; POST vždy CSRF i s mobile hlavičkou nebo Bearer. Žádné oslabování výjimky AuthConfiguration. |
| Mobil GET /api/v1/auth/me, POST /logout, POST /account-cancellations | Dosavadní kontrakty; cancellation LOCAL heslo, externí confirmed=true. | Platný Bearer, aktuální DB oprávnění, stávající cookie/CSRF pravidla při cookie autentizaci. |

Nové bezpečné kódy navržené ke sjednocení s ApiErrors: 400 INVALID_REQUEST (tvar), EXTERNAL_MOBILE_REQUEST_INVALID (ticket), EXTERNAL_HANDOFF_INVALID (kód/requestId/verifier/replay/expirace/zaniklý účet bez rozlišení), stávající EXTERNAL_STATE_INVALID/EXTERNAL_PROOF_INVALID. Provider disabled 503 EXTERNAL_PROVIDER_DISABLED; chybná konfigurace 503 EXTERNAL_CONFIGURATION_INVALID; Apple vault 503 dle stávajícího kontraktu; limit 429; cookie CSRF 403 ACCESS_DENIED; neveřejné API bez relace 401. Nesprávná metoda zůstává 405. Vše no-store, redigované DTO toString.

Zahájení ani exchange nevyžadují předchozí anonymní Rallye session. Pro cookie webový start platí stávající CSRF. Veřejnost nových cest je nutné přidat explicitně a omezit HTTP metody; dnešní anyRequest().authenticated() je jinak odmítne. Non-safelisted mobile hlavička nepředstavuje autentizaci; chybějící cookies, PKCE a uzavřená CORS politika jsou samostatné podmínky. Nepřidávat plošný CORS wildcard ani celou /external/** CSRF výjimku. Browserové cookies nikdy nejsou náhradou mobilního verifieru.

## 5. Uložení, migrace a transakce

Návrh **V11__external_mobile_login.sql** (pouze plán, nevytvořeno):

- Nová krátkodobá evidence mobile_login_attempt: UUID requestId, provider, mobileChallenge S256, rememberMe, schválený návrat a profilový cíl, hash launch ticketu UNIQUE, created_at/expires_at přesně +10 min, launch_consumed_at, jedinečná vazba na external_auth_flow. Žádná osobní data, otevřené tokeny ani verifier. Explicitní typ WEB/MOBILE přes vazbu, ne odhad podle libovolné URL.
- external_auth_grant rozšířit o vazbu na konkrétní flow; jedno vydání na flow (unikátní vazba), dohledatelné requestId a rememberMe přes pokus. MOBILE grant zůstane s account_id a challenge, session_id=NULL před výměnou; platnost přesných 60 sekund zachována. Zkontrolovat preflight existujících grantů bez nové vazby: pokud nějaké existují, migrace zastaví s počtem, nic tiše neodstraní. Dnešní produkční kód granty nevydává; testovací SQL fixtures bude nutné aktualizovat.
- Záměrně nepřidávat druhou tabulku session, dočasnou plnou session v callbacku ani profilové informace do grantů. Původní V1–V10 neměnit. Přesně doplnit V11 do PreRaceAccountParticipationFacts a posunout neznámou budoucí verzi testu; nepovolit libovolnou verzi.
- Úklid expirovaných pokusů může být oportunistický při startu, pouze bezpečně expirované objekty a bez odstranění stále platného grantu. FK pravidla a pořadí výmazu nesmějí zkrátit 60 sekund; flow přežije aspoň všechny své granty. Není navržen nový scheduler. Záznamy neslouží jako audit. Otevřené launch tajemství zůstává pouze v odpovědi/URL, hash v DB; nonce se ověřuje dosavadním hashem.

Launch: atomické podmíněné spotřebování ticketu + založení flow v jedné krátké transakci. Callback: provider síťová výměna bez zámků, potom existující společný admin_control -> účet -> flow/pokus/grant; znovu ověřit stav/deadline, atomicky consume state a insert jediného grantu. Při duplicitním callbacku nevytvořit druhý grant ani opakovaně vracet otevřený kód.

Exchange: nezamykající lookup grantu pro určení účtu, potom **admin_control -> account -> grant/pokus**, opakovaná validace a podmíněný consume WHERE consumed_at IS NULL AND expires_at>now. Nikdy grant FOR UPDATE před účtem, aby nevznikla opačná hrana vůči cancellation. ACTIVE kontrola, session INSERT a audit tvoří jednu transakci. Stejný kód ve dvou instancích: přesně jeden commit; při chybě auditu se necommitne ani spotřeba. Cancellation vyhraje -> grant neexistuje/účet neaktivní -> žádná session; exchange vyhraje -> následná cancellation odstraní i novou session.

Doplnit úklid vazeb při DELETE i ANONYMIZED a otestovat, že FK nové evidence neblokují rušení. Grant musí zůstat vázaný na původní účet: po smazání a nové registraci téhož subjectu/e-mailu jej nikdy nepřevázat na nový účet. U anonymních ještě neověřených flow není znám účet; jejich pozdější callback se řídí běžnou novou registrací, nikoli obnovou zrušených práv. Apple credential řádky nesmějí být odstraněny kaskádou nových objektů.

## 6. Bezpečnostní hranice

| Hrozba | Ochrana a praktický limit |
| --- | --- |
| Zachycený handoff / jiná aplikace | Vlastní mobilní PKCE, provázané requestId a grant, 60 s, ověřené HTTPS App/Universal Links. Samotný odkaz nestačí. PKCE nedokazuje pravost aplikace: škodlivý klient s vlastním verifierem může vytvořit vlastní pokus; nejedná se o attestation. |
| Dva různé přihlašovací pokusy | Jeden lokální pending záznam na requestId, správný verifier jen pro něj, grant/flow/provider vazby; zákaz fallbacku na poslední verifier či novou URL hodnotu. |
| Replay a souběh | DB jednorázovost launch/state/grant, unikátní grant pro flow, přesné expirace, transakční audit a session. |
| Login CSRF, fixation, callback podvrh | Browserem založená korelace + state/nonce, pevný provider/callback, proof; nový náhodný session token až při exchange. Existující browserová relace nesmí zvolit mobilní účet. Nejsou přebírány libovolné klientské session ID. |
| Open redirect | Mobilní a webové returnId oddělit v konfiguraci; exact HTTPS host/path, žádný klientský redirect_uri, wildcard, userinfo, fragment ani předdefinované konfliktní query. Finální query sestavuje backend. Host a forwarded headers nejsou zdroj důvěry. |
| Úniky | No-store, Referrer-Policy: no-referrer pro launch/callback/fallback; žádná analytika/externí obsah na return page. Redigovat ticket, state, code, verifier, cookies, Location a tokeny v aplikaci, reverse proxy i mobile crash logu. Kód v query může vidět HTTPS server/proxy a historie: proto krátká platnost + PKCE, nikoli tvrzení o absolutním utajení URL. |
| Nedokončený účet | Žádná session/handoff před ACTIVE, nepoužít e-mail jako identitu, generic 202 a nový LOGIN po dokončení/aktivaci. |
| Zrušení účtu / odvolání rolí | Aktuální DB kontrola při exchange a při dalších Bearer požadavcích, společné zámky a existující D10. Apple ciphertext zachovat; úplná produkční Apple cesta stále závisí na 3c.5. |

**Dvě PKCE jsou oddělené:** backend–Google chrání výměnu provider kódu, mobil–Rallye chrání handoff. Mobil nesmí znát backendový verifier a backend nepřebírá mobilní verifier do provider výměny. APPLE je dnes důvěrný backendový klient s client-secret a bez provider PKCE; oficiální popis Apple autorizačního endpointu neprokazuje podporu parametrů PKCE. D08 výslovně váže S256 k výměně handoff, což návrh vždy splňuje. Pokud požadavek „PKCE S256“ znamená i Apple provider hop, je před touto částí potřeba ověřit podporu, nikoli ji předstírat nebo oslabit. Nepřecházet na přímý nativní Apple SDK ani nové subject/client-id mapování v této etapě.

## 7. Implementační pořadí a akceptace

### 3c.4a – pouze backend

1. Implementovat schválená D11–D13 a popsané kontrakty s rozlišením WEB/MOBILE. V11, preflight, guard a DB/store testy.
2. Mobilní start + browser launch, korelační cookies a allowlist. Webové starty nezměnit. Testovat neplatný ticket, opakování a deadline bez poskytovatelů.
3. Větev ověřeného callbacku: existující účet -> grant bez session; neznámá identita -> dosavadní omezená registrace, Apple vault zachován. Sdílet validaci a lookup, ne kopírovat celý webový login. Chyby/provider cancellation bezpečně směrovat jen po validní korelaci.
4. Exchange -> existující Bearer session + transakční LOGIN_SUCCEEDED. Nepřidávat novou auditní kategorii jen pro rozpracované granty; úspěšný login evidovat až při skutečném vydání relace. Provozní odmítnutí bez tajemství podle současných pravidel.
5. Regrese cancellation, webu a V10, dokumentace/Postman rozdělené na lokální negativní a připravené HTTPS pozitivní scénáře. Žádný veřejný mock callback nebo fixture endpoint.

Unit: S256 vektory a validace verifieru (43–128 RFC znaků, konzistentní mobilní doporučení 43), typy návratů, snapshot rememberMe, přesné 10 min/60 s hranice, proof/provider mixup, redigované DTO, dva PKCE. HTTP/security: veřejné cesty/metody, mobile header/cookies/CSRF, browser korelace, Apple form_post, duplicitní parametry, no-store/no-referrer, žádný session Set-Cookie v mobilním callbacku/exchange, bezpečné redirecty/chyby a neunikání tokenu v Location.

PostgreSQL: upgrade V10 -> V11; grant vazby/UNIQUE/check/FK/preflight; závod dvou launch/callback/exchange; špatný verifier/requestId bez spotřeby platného grantu; expirace; audit failure rollback+retry; cancellation před exchange, obě pořadí souběhu, anonymizace; zrušení a nová registrace stejné identity neobnoví starý grant; Apple materiál; neúplná registrace a generic kolize; neznámá budoucí migrace fail closed. Skutečné služby/DB s mock verifierem. Testy nesmějí kontaktovat poskytovatele.

Postman návrh: lokální složka disabled providers + invalid ticket/exchange bez session; oddělená HTTPS sada vytvoří vlastní verifier/challenge a uchová requestId, otevře browserStartUrl v browseru, ručně převezme jen testovací handoff návrat a vymění jej v bezcookie mobilním klientovi. Ověří /me/logout, replay a nesprávný verifier. Pozitivní kód musí pocházet ze skutečného callbacku nebo IT fixture interně, ne ručního SQL do dev. Postman nezastupuje ověření App Links/zařízení a jeho cookie jar se nepřenáší do browseru.

Akceptace 3c.4a: verify bez DB + uživatelem potvrzené rallye_test IT; API/regrese a schéma otestované synteticky. Bez skutečného Android/browser/provider běhu označit pouze backendovou připravenost, nikoli celý mobilní login za provozně ověřený.

### 3c.4b – mobilní integrace a skutečné ověření

Samostatný navazující úkol mimo tento backendový návrh: Android systémový browser/Custom Tabs, lokální pending verifier/requestId, bezpečné zpracování intentu (přesný host/path, jeden parametr každého typu, neznámé/duplicitní návraty odmítnout), Bearer storage podle rememberMe. Manifest autoVerify a /.well-known/assetlinks.json s package name a správnými podpisovými SHA-256 fingerprinty včetně rozdílu debug/release/Play signing. Provider callback a launch cesta nesmí být v App Links intent filtru; aplikaci patří pouze finální návratová cesta, aby nezachytila backendový callback.

Budoucí iOS: odpovídající HTTPS returnId, Associated Domains entitlement a apple-app-site-association s TeamID/bundleID a omezením cest. Nyní se nevytváří iOS aplikace ani infrastruktura.

Zařízení: instalovaná/neinstalovaná aplikace, zneplatněné/neověřené vazby, uživatelem vypnuté otevírání odkazů, cold/warm start, zavřený browser, SSO volba jiného účtu, dvě pending přihlášení, zachycený odkaz bez verifieru, expiry během přepnutí, offline exchange a ztracená odpověď, logout/cancellation, rememberMe. Přidat skutečný GOOGLE a APPLE HTTPS smoke včetně Apple form_post/SameSite a registrace s relay adresou/chybějícím jménem. Zapojení domény/proxy, provider klientů a asociací vyžaduje samostatné provozní zadání; tímto dokumentem není autorizováno.

## 8. Schválená rozhodnutí a zbývající závislosti

| Rozhodnutí | Závazný výsledek | Dopad na 3c.4a |
| --- | --- | --- |
| D11 – přechod mobil–browser (uzavírá Q1) | Krátkodobý jednorázový launch ticket. Atomická spotřeba a založení browserového flow; bezpečná korelace se doručí v browseru až po commitu. Samotný ticket neopravňuje k přihlášení. | Mobilní start + launch a rollback/souběhové testy jsou součástí backendu. |
| D12 – první registrace (uzavírá Q2) | Stávající browserový proces dokončení profilu a případná vlastní aktivace; potom nový mobilní LOGIN. Žádný paralelní mobilní registrační proces. | Backendová registrační větev je součástí 3c.4a. Chybějící webová stránka blokuje pouze skutečný end-to-end průchod, nikoli backend. |
| D13 – návratové cíle (uzavírá Q3) | Oddělené webové a mobilní návraty, jen předem povolené adresy a bezpečný HTTPS fallback. Žádná libovolná klientská URL ani automatický exchange ve fallbacku. | Implementovat konfiguraci, validaci a testy. Konkrétní doména a podpisy aplikace se řeší až v 3c.4b. |

Pro 3c.4a **nezůstává blokující funkční rozhodnutí**. Mobilní S256 verifier/challenge jsou nezávislé na provider PKCE a vázané na konkrétní requestId/flow/grant. requestId není autentizační prostředek. Výměna grantu je atomická a po zrušení účtu nesmí vytvořit session; grant se nikdy nepřeváže na nově registrovaný účet. Návratová URL nesmí obsahovat žádné přístupové ani obnovovací tokeny (Rallye ani provider). D08 časové limity 10 minut/60 sekund, D09/D10 a ochrany CSRF/auditu zůstávají.

Podpora provider PKCE u Apple zůstává samostatným technickým ověřením interoperability, nikoli novou produktovou otázkou ani blokací backendového základu. Neověřené skutečné Google/Apple chování se nesmí vydávat za otestované. Profilová stránka, HTTPS prostředí, doména a podpisy aplikace jsou závislosti reálného ověření 3c.4b; Apple revokační worker/retry a bezpečný produkční životní cyklus zůstávají v 3c.5. Samostatná 3c.3c je zrušena jako překonaná po D09/D10, s regresí a dokumentací již pokrytou 3c.3a/3c.3b.

## 9. Ověření analýzy a zdroje

Provedena analýza repozitáře, zápis schválených D11–D13 a kontrola dokumentačního diffu. Žádné Maven/PG/Postman/provider testy, migrace ani DB operace se nyní nespouštěly. Dosavadní potvrzené výsledky 3c.3b nejsou testem mobilního loginu.

Primární zdroje použité k ověření návrhu (ne důkaz interoperability této aplikace):

- [RFC 8252 – OAuth 2.0 for Native Apps](https://www.rfc-editor.org/rfc/rfc8252): systémový browser, PKCE a HTTPS app-claimed redirecty. Rallye zde působí jako backendový broker; návrh není tvrzení, že mobil přímo provozuje provider klienta.
- [RFC 7636 – PKCE](https://www.rfc-editor.org/rfc/rfc7636): S256 a verifier/challenge.
- [Android App Links](https://developer.android.com/training/app-links/about), [ověřování App Links](https://developer.android.com/training/app-links/verify-applinks): asociace aplikace/domény a zařízení.
- [Apple Associated Domains](https://developer.apple.com/documentation/xcode/supporting-associated-domains): Universal Links a asociace.
- [Apple authorization endpoint](https://developer.apple.com/documentation/signinwithapplerestapi/request-an-authorization-to-the-sign-in-with-apple-server): callback form_post; zdejší veřejný popis není potvrzením provider PKCE.

Aktuální stav: 3c.4a.1 prošla verify bez DB (296 testů) i uživatelem potvrzeným PostgreSQL verify (BUILD SUCCESS); podrobnosti v mobile-bootstrap-3c4a1.md. Postman 21 po bezpečnostních opravách uživatel potvrdil jako All Passed; skutečný HTTPS/provider běh a pozitivní složka 22 zůstávají nepotvrzené. Implementovaná V11 obsahuje pouze pokus/launch; V12 v 3c.4a.2 doplňuje grantové vazby. 3c.4a.2 prošla verify bez DB s 317 testy; PostgreSQL verify po opravě SingleIdentityIT a Postman 23 uživatel potvrdil jako úspěšné. Exchange 3c.4a.3 se neimplementuje.

Uzavření 3c.4a.2 (2026-10-25): implementačně dokončena a lokálně ověřena. Codex verify bez DB: 317 testů, BUILD SUCCESS. Uživatel potvrdil PostgreSQL verify včetně opravy SingleIdentityIT (BUILD SUCCESS) a Postman složku 23 (All Passed). Skutečné Google/Apple přihlášení přes HTTPS, browser/proxy a profilová stránka zůstávají neověřené. Exchange a mobilní Bearer relace patří až do nezahájené 3c.4a.3.
