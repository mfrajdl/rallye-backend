package cz.mh.rallye.rallye_backend.audit.application;

import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One row at a time; text is never interpreted as a spreadsheet formula. */
public final class AuditCsv {
    public static final ZoneId ZONE=ZoneId.of("Europe/Prague");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private AuditCsv() { }
    public static String time(Instant at) {return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.atZone(ZONE));}
    public static void row(Writer writer,String... values) throws IOException {
        for(int i=0;i<values.length;i++) {
            if(i>0)writer.write(';');
            String value=values[i]==null?"":values[i];
            int first=0;
            while(first<value.length() && (Character.isWhitespace(value.charAt(first))
                    || Character.isISOControl(value.charAt(first)) || Character.getType(value.charAt(first))==Character.FORMAT))first++;
            if((first<value.length() && "=+-@".indexOf(value.charAt(first))>=0)
                    || (!value.isEmpty() && (Character.isISOControl(value.charAt(0))
                    || Character.getType(value.charAt(0))==Character.FORMAT)))value="'"+value;
            writer.write('"');writer.write(value.replace("\"","\"\""));writer.write('"');
        }
        writer.write("\r\n");
    }
    public static String metadata(String value) {
        JsonNode node=JSON.readTree(value);
        return JSON.writeValueAsString(localTimes(node));
    }
    private static Object localTimes(JsonNode node) {
        if(node.isObject()) {
            var result=new java.util.LinkedHashMap<String,Object>();
            for(String key:node.propertyNames())result.put(key,localTimes(node.get(key)));
            return result;
        }
        if(node.isArray()) {var result=new java.util.ArrayList<Object>();for(var child:node)result.add(localTimes(child));return result;}
        if(node.isString()) {
            String value=node.asString();
            try {return time(Instant.parse(value));}catch(java.time.format.DateTimeParseException ignored) {return value;}
        }
        return JSON.convertValue(node,Object.class);
    }
}
