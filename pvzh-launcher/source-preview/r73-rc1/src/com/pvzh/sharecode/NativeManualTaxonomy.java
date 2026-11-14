package com.pvzh.sharecode;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.util.*;
import static com.pvzh.sharecode.NativeJson.*;

/** Offline parser for optional custom subtype author data. */
public final class NativeManualTaxonomy {
    public static final String PATH="authoring/custom-subtypes.json";
    private NativeManualTaxonomy(){}
    public static JSONObject empty()throws Exception{return obj("schema",1,"subtypes",new JSONArray());}
    public static String path(Map<String,byte[]> entries)throws Exception{
        String chosen=null;byte[] prior=null;
        for(String name:entries.keySet())if(name.equals(PATH)||name.endsWith("/"+PATH)){
            if(prior!=null&&!Arrays.equals(prior,entries.get(name)))throw new IOException("Conflicting custom subtype documents");
            if(chosen==null||name.length()<chosen.length())chosen=name;prior=entries.get(name);
        }
        return chosen==null?PATH:chosen;
    }
    public static String decode(byte[] raw)throws Exception{try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}catch(CharacterCodingException bad){throw new IOException("Expected UTF-8 custom subtype data",bad);}}
    public static JSONObject read(Map<String,byte[]> entries)throws Exception{byte[] raw=entries.get(path(entries));return raw==null?empty():parse(decode(raw));}
    public static JSONObject parse(String raw)throws Exception{
        if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>1024*1024)throw new IOException("Missing or oversized subtype document");
        JSONObject d=PreviewJson.object(raw);validate(d);return d;
    }
    public static void validate(JSONObject d)throws Exception{
        if(d.toString().getBytes(StandardCharsets.UTF_8).length>1024*1024)throw new IOException("Subtype document exceeds 1 MiB");
        if(!(d.opt("schema") instanceof Integer)&&!(d.opt("schema") instanceof Long)||number(d.opt("schema"),"Subtype schema",1,1)!=1)throw new IOException("Unsupported custom subtype schema");
        JSONArray rows=d.optJSONArray("subtypes");if(rows==null||rows.length()>2048)throw new IOException("Expected a bounded subtypes array");
        Set<Integer> ids=new HashSet<>();Set<String> names=new HashSet<>();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)throw new IOException("Subtype row must be an object");int id=id(row.opt("id"));String name=text(row.opt("display_name"),"Subtype display name",128,false);text(row.opt("cn_name"),"Chinese name",512,false);if(!ids.add(id)||!names.add(name))throw new IOException("Duplicate subtype ID or display name");}
    }
    static int id(Object value)throws Exception{return number(value,"Subtype ID",0,Integer.MAX_VALUE);}
    private static boolean sameId(Object value,int id){return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()==id;}
    static String text(Object raw,String label,int limit,boolean empty)throws Exception{
        if(!(raw instanceof String)||((String)raw).length()>limit||!empty&&((String)raw).isEmpty())throw new IOException(label + " must be a " + (empty ? "possibly empty" : "nonempty") + " string of at most " + limit + " characters");String v=(String)raw;
        for(int i=0;i<v.length();i++){char c=v.charAt(i);if(c<32||c==127||Character.isHighSurrogate(c)&&(i+1>=v.length()||!Character.isLowSurrogate(v.charAt(++i)))||Character.isLowSurrogate(c))throw new IOException("Invalid Unicode or control character in " + label);}return v;
    }
    public static String key(JSONObject row)throws Exception{return "subtype_"+text(row.opt("display_name"),"Subtype display name",128,false);}
}
