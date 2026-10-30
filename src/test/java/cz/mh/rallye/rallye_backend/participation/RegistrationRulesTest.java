package cz.mh.rallye.rallye_backend.participation;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import cz.mh.rallye.rallye_backend.participation.domain.RegistrationRules;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;

class RegistrationRulesTest {
 final Instant now=Instant.parse("2026-10-30T10:00:00Z");
 String reason(String status,Instant started,Instant end,Instant from,Instant to,String mode,Instant time){return RegistrationRules.state(status,started,end,from,to,mode,time).reason();}
 @Test void defaultIsOpenButActualStartClosesIt(){assertEquals("DEFAULT_OPEN",reason("PLANNED",null,null,null,null,"AUTO",now));assertEquals("OFFICIAL_START_REACHED",reason("RUNNING",now,null,null,null,"AUTO",now));assertEquals("OFFICIAL_START_REACHED",reason("PLANNED",now,null,null,null,"AUTO",now));}
 @Test void windowIncludesFromAndExcludesTo(){var from=now;var to=now.plusSeconds(60);assertEquals("BEFORE_INTERVAL",reason("PLANNED",null,null,from,to,"AUTO",now.minusMillis(1)));assertEquals("WITHIN_INTERVAL",reason("PLANNED",null,null,from,to,"AUTO",from));assertEquals("WITHIN_INTERVAL",reason("PLANNED",null,null,from,to,"AUTO",to.minusMillis(1)));assertEquals("AFTER_INTERVAL",reason("PLANNED",null,null,from,to,"AUTO",to));}
 @Test void manualDecisionsOverrideWindowAndActualStart(){for(String status:new String[]{"PLANNED","RUNNING"}){assertEquals("MANUALLY_OPEN",reason(status,null,null,now.plusSeconds(60),now.plusSeconds(90),"OPEN",now));assertEquals("MANUALLY_CLOSED",reason(status,null,null,null,null,"CLOSED",now));}}
 @Test void endHasPriorityAtExactBoundaryEvenForManualOpen(){assertEquals("EVENT_ENDED",reason("PLANNED",null,now,null,null,"OPEN",now));assertEquals("EVENT_ENDED",reason("ENDED",null,null,null,null,"OPEN",now));assertTrue(RegistrationRules.state("PLANNED",null,now.plusMillis(1),null,null,"OPEN",now).open());}
 @Test void completeWindowAndKnownModeAreRequired(){for(Instant[] w:new Instant[][]{{now,null},{null,now},{now,now},{now.plusSeconds(1),now}})assertEquals("REGISTRATION_INTERVAL_INVALID",assertThrows(AuthFailure.class,()->RegistrationRules.settings(w[0],w[1],"AUTO")).code());assertEquals("INVALID_REQUEST",assertThrows(AuthFailure.class,()->RegistrationRules.settings(null,null,"auto")).code());RegistrationRules.settings(null,null,"CLOSED");}
 @Test void normalizesOnlyLineEndingsAndCountsNonblankLines(){var m=RegistrationRules.members("  Jana  \r\n\t\rPetr\n\n",1);assertEquals("  Jana  \n\t\nPetr\n\n",m.text());assertEquals(2,m.count());assertEquals(1,m.children());assertFalse(m.toString().contains("Jana"));}
 @Test void moreThanFiveMembersAndAllChildrenAreAccepted(){var m=RegistrationRules.members("A\nB\nC\nD\nE\nF",6);assertEquals(6,m.count());assertEquals(6,m.children());}
 @Test void codePointsNotUtf16UnitsAndRawInputLengthAreUsed(){String supplementary="\uD83D\uDE80";assertEquals(1,RegistrationRules.members(supplementary.repeat(2000),0).count());assertEquals("REGISTRATION_MEMBERS_TEXT_TOO_LONG",assertThrows(AuthFailure.class,()->RegistrationRules.members(supplementary.repeat(2001),0)).code());assertEquals("REGISTRATION_MEMBERS_TEXT_TOO_LONG",assertThrows(AuthFailure.class,()->RegistrationRules.members("A"+"\r\n".repeat(1000),0)).code());}
 @Test void blankNulAndChildrenBeyondCountAreRejected(){for(String text:new String[]{""," \n\t\u3000","A\u0000"})assertEquals("REGISTRATION_MEMBERS_INVALID",assertThrows(AuthFailure.class,()->RegistrationRules.members(text,0)).code());assertEquals("REGISTRATION_MEMBERS_INVALID",assertThrows(AuthFailure.class,()->RegistrationRules.members("A",2)).code());assertEquals("INVALID_REQUEST",assertThrows(AuthFailure.class,()->RegistrationRules.members("A",-1)).code());}
 @Test void javaWhitespaceExcludesNbspAndFigureSpace(){assertEquals(2,RegistrationRules.members("\u00A0\n\u2007\u202F",0).count());assertEquals(1,RegistrationRules.members("\u1680\u2000\u205F\nX",0).count());}
}
