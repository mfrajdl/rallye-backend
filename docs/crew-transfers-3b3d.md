# Revize 3b.3d – převody kapitána

Revize podle specifikace 0.6.0 §2.2 a zadání 3b.3d, stav 2026-10-23.
Na základě upřesnění uživatele tato etapa uzavírá pouze převody **3b.3**.
Nesmí označit za dokončené rušení účtů (3b.4), export/úklid auditu (3b.5)
ani závěrečné scénáře celého 3b (3b.6). Následuje 3b.4 → 3b.5 → 3b.6 → 3c.

## Výsledek revize

Nebyl zjištěn nový bezpečnostní nebo transakční problém produkčního kódu
převodů. Produkční kód, REST kontrakty, specifikace a migrace V1–V5 se v této
revizi nemění. Opraven byl skutečný nedostatek ručního testu: Postman replay
založení při prázdném klíči generoval nový UUID. Nyní bez původního klíče
požadavek neodešle. Nedoplňovaly se již pokryté scénáře ani další funkce.

## Kontrolní přehled

| Oblast | Zjištění a existující ověření |
| --- | --- |
| Založení | Pouze aktuální kapitán; normalizovaný e-mail se překládá na interní UUID aktivního ověřeného účtu. Kontroluje se verze, zákaz převodu na sebe a jediná živá žádost. |
| Token | SecureRandom, 32 bajtů, Base64URL; v crew_transfer pouze SHA-256. Rozhodnutí ověřuje hash konstantním porovnáním a PENDING s původní expirací. |
| E-mail | Samostatný převodní účel v SMTP i souborovém adaptéru, text s názvem a skutečnou expirací, až po commitu. Rollback neodešle zprávu. |
| Resend | Jen aktuální původní kapitán, stále platný převod a dostupný příjemce; rotuje pouze hash. Původní token přestane platit, expires_at se nezmění. |
| Accept | Přihlášený cílový účet, aktivní ověřená identita a aktuální token. Stav, kapitán, aktuální version + 1, audit a ledger jsou atomické. Textové/fotografické editace žádost nezneplatní. |
| Reject/cancel | Cíl s tokenem / dosavadní kapitán bez tokenu. Nemění kapitána ani verzi. Nový klíč po uzavření vrací TRANSFER_NOT_PENDING. |
| Expirace | Přesně now >= expires_at již neplatí. Nové založení uzavře starý expirovaný PENDING pod zámkem; nevyžaduje periodickou úlohu. |
| ADMIN a smazání | Živý převod blokuje DELETE. Úspěšná změna či odebrání kapitána ruší živou žádost a audit v téže transakci. Expirovaný uložený PENDING se uzavře jako EXPIRED. |
| Přístup | Ověření aktuální relace, účtu a vlastnictví z DB. Původní běžný kapitán ztrácí přístup i s nadále platnou relací, nový jej získá. ADMIN si zachovává nezávislá administrátorská oprávnění. |
| Cizí prostředky | Veřejná orchestrace nezveřejňuje cizí žádosti; používá 404. Pouze oprávněný kapitán dostane obecný CAPTAIN_UNAVAILABLE bez rozlišení důvodu. |
| Web/mobil | Cookies vyžadují CSRF. Existující Bearer větev a kontrola platnosti serverové relace zůstávají. Samotný GET odkazu nic nepotvrdí; chybějící GET API vrací 405. |
| Omezení pokusů | Oddělené názvy operací create/resend/accept/reject/cancel; 10 na účet a 50 na IP za 15 minut. Replay se také počítá. |
| Idempotence | Společný CommandStore, actor/key prostor, 24 hodin. Shodné parametry vracejí původní DTO, jiné 409; replay nepíše audit ani neposílá další mail. |
| Otisky tokenů | Ledger obsahuje pouze oddělený SHA-256 otisk s označením účelu, operací a ID převodu; ne otevřený token ani autentizační hash. Neumožňuje obnovit náhodný 256bitový token. |
| Audit | REQUESTED/ACCEPTED/REJECTED/CANCELLED, aktér, čas, ID převodu/posádky a obou účtů. ADMIN zrušení uvádí důvod. Žádné tajné údaje. |
| Schéma | V4: částečný unikátní PENDING index, CHECK stavy, 168 hodin a údaje vyřízení. V5: nullable auditní vazby/indexy. Bez blokujících FK na historické převody či audit. |
| Diagnostika | Očekávané HTTP chyby mají správné 4xx; neočekávané 500 zachovává obecnou odpověď a bezpečný diagnostický stack bez textů obsahujících tajné údaje. |

## Transakce a souběh

Ověřené pořadí je admin_control → účet aktéra → související účty → posádka
→ žádost. Společný singleton serializuje převodní i administrátorské zápisy.
CrewTransferLifecycle a AuditService vyžadují MANDATORY vnější transakci;
CrewTransferService/CrewService používají TransactionTemplate. Nenastává
samostatný commit auditu. Platnost se kontroluje znovu po zamčení žádosti.

Dosavadní IT pokrývají accept/reject, accept/cancel, dvě accept, accept/ADMIN,
create/DELETE, nové založení po expiraci, převod po editaci a skutečný rollback
po zápisech stavu, kapitána, auditu i idempotence. Nedokládají se pouze očekávaným
typem výjimky: kontrolují příčinu, stav po ukončení transakce a úspěšný retry.

Doplněny pouze chybějící případy v CrewTransfersIT:

1. **Resend proti accept:** buď accept vyhraje a resend ztratí oprávnění bez
   zprávy, nebo resend vyhraje, původní token je odmítnut a nový lze přijmout.
   Kontroluje se stav, verze, audit a počet zpráv v obou výsledcích.
2. **24hodinová retence:** těsně před hranicí create/resend vracejí replay bez
   zprávy; na hranici již create vyhodnotí živou žádost jako konflikt a resend
   s opět použitelným klíčem skutečně rotuje token. Životnost žádosti se neprodlužuje.
3. Posílen původní reject/cancel test o replay i nový klíč po smazání posádky:
   historie i původní výsledek zůstanou dostupné oprávněnému aktérovi, další
   změna je odmítnuta jako TRANSFER_NOT_PENDING.

## Postman a ruční postup

Kolekce má 76 požadavků a 125 syntakticky ověřených skriptů. Složky 10 a 11
obsahují create/replay/conflict/resend a accept/reject/cancel, starý token,
blokovaný DELETE a ADMIN zrušení. Replay založení již negeneruje nový klíč.
Accept replay vyžaduje původní klíč; ADMIN scénář uchovává i původní verzi.
Striktní JSON parser a validace proti oficiálnímu schématu Postman Collection
v2.1 (Draft4) prošly. Opravený replay skript byl ověřen s původním i chybějícím
klíčem: zachová klíč, respektive požadavek zablokuje bez generování nového.

Nová složka 12 obsahuje dvě výslovné kontroly přístupu. Po accept ponechat
crewId a přihlásit se nejprve jako nový kapitán (GET detailu → 200), pak jako
původní běžný kapitán (stejný GET → 404 CREW_NOT_FOUND). Přihlášení se přepíná
existujícím Login požadavkem; test nepředpokládá, že ADMIN přijde o svou roli.
Používá se crewTransferId pro ID žádosti a crewTransferVersion pro verzi při
založení; proměnná crewVersion označuje aktuální verzi posádky. Nezaměňovat je.

Při nové nezávislé operaci vyprázdnit její klíč, při replay zachovat klíč i
parametry. Tokeny vyplňovat pouze lokálně ze schránky a nikdy neexportovat.
Uživatel potvrdil ruční scénáře přístupu nového a původního kapitána provedené
již během 3b.3c. Tato revize neopakuje jejich běh ani netvrdí vlastní manuální
průchod Codexu celou kolekcí.

## Výsledky a podmínky uzavření

Codex provedl `.\mvnw.cmd verify` s JDK 21: **BUILD SUCCESS, 176 testů,
0 failures, 0 errors, 0 skipped**. Nové IT se zkompilovaly.

Uživatel dne 2026-10-23 potvrdil BUILD SUCCESS nového PostgreSQL běhu 3b.3d.
Dostupný Failsafe report obsahuje 73 testů, 0 errors, 0 failures a 0 skipped,
včetně 34 případů CrewTransfersIT. Nový souběh resend/accept i retenční hranice
jsou tím integračně ověřené. Jde o uživatelem potvrzený běh, nikoli běh Codexu.

```powershell
.\mvnw.cmd -Pit verify
```

Revize 3b.3d je dokončená a ověřená. Uživatel samostatně potvrdil i ruční
scénáře přístupu obou kapitánů z 3b.3c. Převody 3b.3 jsou tím uzavřené;
celá etapa 3b uzavřena není.
Další krok je **3b.4 – rušení účtů**; pak 3b.5 a 3b.6. Externí identity 3c
se nyní neimplementují ani se k nim nepřechází.

Zbývající provozní omezení: SMTP poskytovatel a skutečné doručování před
nasazením, bez outboxu není garantované doručení při pádu po commitu,
rate limiting je paměťový v jedné instanci a globální zámek omezuje propustnost.
Historické účasti a oprávnění závodního zařízení doplní teprve jejich moduly;
převody je dnes nemění. Nový produktový požadavek ani změna architektury nevznikly.
