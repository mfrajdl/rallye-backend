# Závěrečná kontrola 3b.4b

Review neodhalilo funkční ani bezpečnostní chybu vyžadující změnu produkčního
kódu. Zkontrolovány byly i diagnostické změny během ručního testování.
Review je uzavřeno: uživatel následně potvrdil závěrečné `mvnw.cmd verify`
(217 testů), úspěšné `mvnw.cmd -Pit verify` ve svém prostředí a všechny
plánované ruční scénáře. 3b.4b i celá 3b.4 jsou dokončeny a připraveny
k závěrečnému commitu. Push se neprovádí. Rozpracované `.gitignore` a
`docs/Rallye_specifikace.md` zůstávají mimo commit.

## Zkontrolované invarianty

- Vlastní zrušení vyžaduje aktuální LOCAL heslo a aktuálně platnou relaci;
  ADMIN endpoint odmítá vlastní účet. ADMIN role se ověřuje z databáze.
- Společný zámek `admin_control` sjednocuje rušení, autentizaci, role,
  převody a administrátorské kapitánství. Posledního aktivního ověřeného
  ADMIN chrání služba i zachované databázové triggery.
- Odpojení posádek/verze, vyřešení převodů na obou stranách, odstranění
  přihlašovacích prostředků, úklid ledgeru, smazání/anonymizace i audit jsou
  v jedné vnější transakci. Primitivní operace a audit vyžadují MANDATORY.
- Fotografie ani jejich soubory se při rušení nemění; historické převody
  a audit nemají mazací kaskádu přes účet. Audit neobsahuje heslo ani e-mail.
- ADMIN replay se řeší před hledáním cíle, ale po autorizaci. Cíl je součástí
  JSON parametrů; stejný actor/key s jinými parametry/operací vrací konflikt.
  Výsledek bez osobních údajů se zapisuje až po úklidu, ve stejné transakci.
- Vlastní zrušení odstraní všechny relace, neukládá heslo ani replay;
  původní credentials již nelze použít. Nová registrace má jiné UUID.
- Úklid CommandStore zahrnuje všechny aktéry a expirované řádky. Nepoužívá
  target_id jako univerzální účet; pracuje s metadaty a známými legacy formáty,
  hledá doslovný e-mail v JSON hodnotách a maže celé řádky. Neznámý formát
  zrušení odmítne a celou transakci vrátí zpět.
- CSRF platí pro cookies, Bearer používá stávající autentizaci; oba endpointy
  mají oddělené rate-limit namespace. Chyby zůstávají v bezpečném API formátu.
- Obecná i explicitní aplikační 5xx a chyby autentizačního filtru mají
  sanitizovanou diagnostiku typů, příčin, stack frames a SQLSTATE. Původní
  texty výjimek a autentizační údaje se nelogují. Běžné 4xx se nehlásí jako 500.

## Výsledky

Uživatel potvrdil ruční Postman scénáře: vlastní zrušení 204 a původní session
401; novou registraci stejného e-mailu 202 s úspěšným ověřením/přihlášením;
ADMIN zrušení 204, replay 204 a nový klíč po zrušení 404; zákaz vlastní ADMIN
cesty 403; ochranu posledního ADMIN 409 se zachováním účtu/role; zachovanou
osiřelou posádku a následné přiřazení nového kapitána ADMIN včetně zvýšení verze.
To je uživatelské ověření, nikoli vlastní Postman běh Codexu.

Dřívější automatické výsledky: 217 unit/HTTP testů bez chyb po diagnostické
úpravě a uživatelem potvrzených 112 PostgreSQL IT před touto úpravou.

Při této revizi původní `mvnw.cmd verify` přerušil nedostatek nativní paměti
Windows (1455, limit stránkovacího souboru), také při prvním omezení heapu.
Následující `mvnw.cmd -Pit verify` s omezenou pamětí úspěšně provedlo všech
217 unit/HTTP testů (0 failures/errors/skipped), ale celek skončil BUILD FAILURE:
integrační inicializátor správně odmítl chybějící `RALLYE_TEST_DB_ENABLED=true`.
V prostředí Codexu chybí také URL, username a password. Nejde o selhání SQL
nebo aplikačního scénáře; nedošlo k připojení na testovací ani vývojovou databázi.
Failsafe eviduje 18 chyb inicializace/kontextu, nikoli 18 neúspěšných DB operací.
Jeho aktuální report nahrazuje předchozí lokální report uživatelského běhu.

Po úspěšném unit běhu uvnitř profilu it bylo znovu spuštěno i samostatné
`mvnw.cmd verify` se stejnými dočasnými paměťovými limity: **BUILD SUCCESS,
217 testů, 0 failures, 0 errors, 0 skipped**, JAR sestaven.

Při běhu byly pouze pro aktuální proces použity MAVEN_OPTS `-Xms16m -Xmx192m
-XX:ActiveProcessorCount=2` a testovací argLine `-Xms16m -Xmx384m
-XX:ActiveProcessorCount=2`; chybové JVM soubory tohoto review jsou v `target/`.
Projektová ani systémová konfigurace se neměnila. Kolekce znovu prošla JSON
a strukturální kontrolou: 87 požadavků, 147 syntakticky platných skriptů;
původní požadavky a nastavení jsou zachované.

## Zbývající omezení

Závěrečné opakování `.\mvnw.cmd -Pit verify` uživatel potvrdil jako
BUILD SUCCESS ve svém prostředí s chráněnou konfigurací `rallye_test`.
Tím je předchozí překážka tohoto review vyřešena; nejde o vlastní PostgreSQL
úspěšný běh Codexu. Výše uvedený neúspěšný pokus zachycuje historický průběh
kontroly, nikoli konečný stav ověření.

Adaptér účastí úmyslně podporuje pouze V1–V6; před závodními migracemi je třeba
skutečný adaptér historické/běžící účasti. Anonymizace a blokace běžící účasti
jsou testovány testovacími fakty. Úklid celého ledgeru pod společným zámkem
je přijatá varianta malého projektu; neznámá legacy data vyžadují řešení příčiny,
nikoli vypnutí ochrany. Rate limiting je nadále v paměti jednotlivé instance.
Příčina dříve hlášené ruční 500 nebyla doložena; nyní potvrzené scénáře procházejí,
nelze proto přisuzovat dřívější 500 určité databázové chybě.
