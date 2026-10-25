# Bezpečnostní kontrola 3c.4a.1 – soukromí launch ticketu

Datum: 2026-10-25. Rozsah pouze mobilní start/launch, nikoli 3c.4a.2. Výchozí PostgreSQL verify a Postman složku 21 uživatel potvrdil jako úspěšné. Nové ověření níže se týká následných bezpečnostních oprav; nejde o nový PostgreSQL ani skutečný provider běh.

## Zjištění a opravy

1. **Hlavičky chyb před controllerem:** původní Referrer-Policy nastavoval controller až po navázání argumentů. Chybějící ticket či odmítnutí Spring Security mohly skončit bez této hlavičky. LaunchTicketPrivacyFilter nyní běží před Security/MVC a nastavuje Cache-Control: no-store a Referrer-Policy: no-referrer pro úspěch i chyby včetně error dispatch. Testy pokrývají 303, 400 (missing ticket i aplikační), 401, 403, 429, 500 a 503, včetně vnořeného error dispatch s obnoveným původním requestem. Diagnostické 500 zachovává bezpečný obecný kontrakt.
2. **Request logging:** samotné nelogování v aplikační službě nechránilo před obecným MVC/request loggingem. Časný filtr skryje query string a rediguje hodnoty parametrů a příchozí Referer v downstream servletovém requestu. Původní ticket uchová pouze v privátním request atributu s redigovaným toString; pouze controller jej vyzvedne pro službu. Validace počtu parametrů zůstává zachována. Testy skutečného MVC TRACE s enableLoggingRequestDetails=true a CommonsRequestLoggingFilter s query/headers ověřují nepřítomnost syntetického ticketu. Nedochází k vypnutí diagnostiky skutečných chyb.
3. **Tomcat access/wire logging:** access log je ve výchozím prostředí vypnutý, ale jeho běžný common/combined formát by po zapnutí obsahoval query nebo Referer. Nastaven je bezpečný path-only vzor `%h %l %u %t "%m %U %H" %s %b`. LaunchLoggingConfiguration po Boot customizerech odmítne start, pokud instalovaný Tomcat AccessLogValve používá jiný formát. Nezapisuje ani nebezpečnou hodnotu konfigurace do výjimky. U HTTP connector/parser loggerů je WARN a start odmítá DEBUG/TRACE včetně explicitních child loggerů, protože ty běží před servletovým filtrem. Access log se tím automaticky nezapíná. Test se skutečným embedded Tomcatem a zapnutým access logem provedl HTTP požadavek s ticketem v query i Referer a ověřil, že log obsahuje jen cestu, nikoli ticket. Test nepoužil DB, externí síť ani Google/Apple.
4. **Chyby, audit a redirecty:** controller ticket nevrací v chybové odpovědi. ApiErrors nadále loguje pouze typy, bezpečný SQLSTATE a stack frames bez citlivých exception messages; regresní test zachyceného logu ověřil, že ani výjimka obsahující syntetický ticket jej nevypíše. Start/launch nepřidávají auditní události a persistence dostává pouze hash ticketu. Jediná zamýšlená URL s ticketem je startovní browserStartUrl. Skutečná konstrukce provider authorization URL a uloženého finálního return_uri byla unit testem ověřena bez ticketu; provider PKCE je nezávislé na mobileChallenge. Nové return/handoff URL se v této podetapě nevydávají.

## Referer při redirectu

Launch odpověď posílá `Referrer-Policy: no-referrer` i na 303. Podle standardu se tato politika zohlední při následování redirectu a kompatibilní browser Referer k poskytovateli neodešle. Ticket navíc není v provider Location. Automatické HTTP testy prokazují hlavičku/Location, nikoli chování skutečného Google/Apple browser toku. Embedded HTTP klient záměrně redirect nenásleduje; jeho případná absence Referer by nebyla důkazem chování browseru.

Skutečné browserové ověření zůstává v HTTPS prostředí 3c.4b: v DevTools zapnout Preserve log a u odchozí navigace na provider zkontrolovat **nepřítomnost Referer**, bez sdílení hodnot ticket/state/nonce. Sledovat skutečné response headers až za proxy. Není potřeba kvůli této kontrole měnit CSRF ani zapínat request-body/wire logy.

Zdroje: [W3C Referrer Policy – redirect integration a no-referrer](https://www.w3.org/TR/referrer-policy/), [Tomcat AccessLogValve – query/request line/header patterns](https://tomcat.apache.org/tomcat-11.0-doc/api/org/apache/catalina/valves/AbstractAccessLogValve.html).

## Hranice záruk a provozní podmínky

V repozitáři nejsou nalezeny konfigurace nasazené reverse proxy ani přidané request-dumper/logging filtry. Aplikace nemůže zabránit záznamu příchozí URL před sebou v cizí proxy, APM agentu, browser historii nebo klientském/Postman logu. Také chybné HTTP requesty odmítnuté konektorem před vstupem do servlet pipeline nelze pokrýt aplikačním filtrem. Ochrany se týkají podporovaného embedded Tomcat/Logback prostředí a standardních platných launch požadavků, nikoli libovolného budoucího loggeru schopného rozbalit původní request. Runtime administrativní přepnutí wire loggerů po startu není podporovaný bezpečný provozní režim.

Před HTTPS ověřením musí provoz zajistit: proxy access formát bez query/request line/Referer/Location, žádné ukládání response body mobilního startu do APM, žádné URL/request-detail/wire debug na konektoru, žádnou analytiku na těchto cestách a zachování no-store/no-referrer. Případný další access log valve či agent musí projít samostatnou kontrolou. Nelze tvrdit, že externí infrastruktura byla touto lokální kontrolou ověřena. Na úrovni databáze/auditu se nic neměnilo.

## Testy a opakování Postmanu

- Celé `mvnw.cmd verify` bez profilu it: **BUILD SUCCESS, 306 testů, 0 failures/errors/skipped**, Microsoft JDK 21.0.12.1. Zahrnuje izolovaný embedded Tomcat test i nové HTTP/privacy testy; PostgreSQL IT zkompilované, nikoli nyní spuštěné.
- Uživatel dříve potvrdil PostgreSQL `.\mvnw.cmd -Pit verify` a Postman 21. Po těchto HTTP/logging opravách nebyl nový uživatelský PostgreSQL běh dodán; předchozí úspěch není vydáván za nový běh. Migrace ani transakční služby se touto opravou nemění.
- Postman složky 21/22 dostaly aserce obou privacy hlaviček; připravený launch navíc ověřuje, že Location neobsahuje ticket. Statusy, těla a URL kontrakty se nemění; oprava doplňuje hlavičky na dříve nepokryté chyby.
- **Uživatel následně zopakoval celou lokální složku 21: All Passed**, včetně nových asercí no-store/no-referrer. Žádné destruktivní operace.
- Složku 22 opakovat pouze po přípravě HTTPS a výslovném povolení dle mobile-bootstrap-3c4a1.md; není podmínkou běžné lokální negativní kontroly. Skutečný browser/provider tok ani tuto sadu Codex nespouštěl.

Commit ani push neproveden, 3c.4a.2 nezahájena. Uživatelské .gitignore a docs/Rallye_specifikace.md zůstaly beze změny.


## Uzavření 3c.4a.1

Uživatel schválil uzavření implementovaného rozsahu: PostgreSQL verify před bezpečnostními opravami BUILD SUCCESS; po opravách verify bez DB BUILD SUCCESS, 306 testů; následné Postman 21 All Passed včetně privacy hlaviček. PostgreSQL běh po bezpečnostních opravách nebyl dodán ani proveden Codexem. Skutečný HTTPS/provider/browser smoke, Postman 22 a externí proxy logování zůstávají neověřené. Toto rozlišení nebrání uzavření samostatného bootstrapu; neprohlašuje hotový mobilní login. 3c.4a.2 zůstává nezahájena.
