package cz.mh.rallye.rallye_backend.templates.application;

import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;

/** One portable name policy for DB uniqueness, URLs and physical paths. Client input is never a path. */
public final class ImageNames {
 private ImageNames(){}
 public static String validate(String raw){
  if(raw==null)throw invalid();String name=Normalizer.normalize(raw,Normalizer.Form.NFC);
  if(name.isBlank()||!name.equals(name.strip())||name.startsWith(".")||name.endsWith(".")||name.endsWith(" ")
    ||name.codePointCount(0,name.length())>100||name.getBytes(StandardCharsets.UTF_8).length>200)throw invalid();
  if(name.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.SURROGATE||c==0xfffd||"/\\<>:\"|?*%#".indexOf(c)>=0))throw invalid();
  String stem=name.split("\\.",2)[0].stripTrailing().toUpperCase(Locale.ROOT);
  if(stem.matches("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]"))throw invalid();return name;
 }
 public static String key(String name){return Normalizer.normalize(validate(name).toUpperCase(Locale.ROOT),Normalizer.Form.NFC);}
 public static String url(UUID template,String name){return "/api/v1/race-templates/"+template+"/images/"+URLEncoder.encode(validate(name),StandardCharsets.UTF_8).replace("+","%20");}
 public static String fileUrl(UUID template,String name){return url(template,name).replace("/images/","/files/");}
 public static String eventUrl(UUID event,String name){return fileUrl(event,name).replace("/race-templates/","/race-events/");}
 public static String reference(UUID template,String destination){return reference(template,destination,false);}
 public static String simulationUrl(UUID id,String name){return "/api/v1/admin/race-simulations/"+id+"/files/"+URLEncoder.encode(validate(name),StandardCharsets.UTF_8).replace("+","%20");}
 public static String reference(UUID template,String destination,boolean event){return reference(template,destination,event,false);}
 public static String reference(UUID template,String destination,boolean event,boolean simulation){
  try{
   String prefix=simulation?"/api/v1/admin/race-simulations/"+template+"/files/":"/api/v1/"+(event?"race-events":"race-templates")+"/"+template+"/images/";
   String filePrefix=simulation?prefix:"/api/v1/"+(event?"race-events":"race-templates")+"/"+template+"/files/";
   String raw=destination.startsWith(prefix)?destination.substring(prefix.length()):destination.startsWith(filePrefix)?destination.substring(filePrefix.length()):destination;
   String decoded=URLDecoder.decode(raw.replace("+","%2B"),StandardCharsets.UTF_8);
   return validate(decoded);
  }catch(RuntimeException failure){throw new AuthFailure(400,"TEMPLATE_IMAGE_REFERENCE_INVALID");}
 }
 private static AuthFailure invalid(){return new AuthFailure(400,"INVALID_IMAGE_NAME");}
}
