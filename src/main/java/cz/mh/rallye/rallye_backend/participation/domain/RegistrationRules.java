package cz.mh.rallye.rallye_backend.participation.domain;

import java.time.Instant;
import java.util.Set;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;

/** Pure registration rules. The caller supplies the common effective lifecycle projection; evaluation never writes a transition. */
public final class RegistrationRules {
 public static final int MAX_MEMBERS_TEXT_LENGTH=2000;
 private RegistrationRules(){}
 public record Members(String text,int children,int count){@Override public String toString(){return "Members[redacted]";}}
 public record State(String effectiveState,String reason){public boolean open(){return effectiveState.equals("OPEN");}}
 public static Members members(String text,int children){
  if(text==null||children<0)throw new AuthFailure(400,"INVALID_REQUEST");
  if(text.codePointCount(0,text.length())>MAX_MEMBERS_TEXT_LENGTH)throw new AuthFailure(400,"REGISTRATION_MEMBERS_TEXT_TOO_LONG");
  String normalized=text.replace("\r\n","\n").replace('\r','\n');
  int count=(int)normalized.lines().filter(line->!line.isBlank()).count();
  if(normalized.indexOf(0)>=0||count==0||children>count)throw new AuthFailure(400,"REGISTRATION_MEMBERS_INVALID");
  return new Members(normalized,children,count);
 }
 public static void settings(Instant from,Instant to,String override){
  if(override==null||!Set.of("AUTO","OPEN","CLOSED").contains(override))throw new AuthFailure(400,"INVALID_REQUEST");
  if((from==null)!=(to==null)||from!=null&&!from.isBefore(to))throw new AuthFailure(400,"REGISTRATION_INTERVAL_INVALID");
 }
 public static boolean ended(String status,Instant end,Instant now){return status.equals("ENDED")||end!=null&&!now.isBefore(end);}
 public static State state(String status,Instant started,Instant end,Instant from,Instant to,String override,Instant now){
  if(ended(status,end,now))return new State("CLOSED","EVENT_ENDED");
  if(override.equals("OPEN"))return new State("OPEN","MANUALLY_OPEN");
  if(override.equals("CLOSED"))return new State("CLOSED","MANUALLY_CLOSED");
  if(started!=null||status.equals("RUNNING"))return new State("CLOSED","OFFICIAL_START_REACHED");
  if(from!=null&&now.isBefore(from))return new State("CLOSED","BEFORE_INTERVAL");
  if(to!=null&&!now.isBefore(to))return new State("CLOSED","AFTER_INTERVAL");
  return new State("OPEN",from==null?"DEFAULT_OPEN":"WITHIN_INTERVAL");
 }
}
