package com.pvzh.sharecode;

import org.json.*;
import java.io.*;
import java.util.*;
import static com.pvzh.sharecode.NativeJson.*;

/** Offline card table parsing, structure checks and localization lookup. */
public final class NativeManualCardCodec {
    public static final Map<String,String> TEXT_FIELDS=new LinkedHashMap<>();
    static {TEXT_FIELDS.put("name","_name");TEXT_FIELDS.put("short_description","_shortDesc");TEXT_FIELDS.put("long_description","_longDesc");TEXT_FIELDS.put("flavor_text","_flavorText");TEXT_FIELDS.put("target_hint","_Targeting");}
    private NativeManualCardCodec(){}
    public static JSONObject parseObject(String raw)throws Exception{
        if (raw == null || raw.length() > 8 * 1024 * 1024) throw new IOException("Missing or oversized card JSON");
        return PreviewJson.object(raw);
    }
    public static JSONObject phantom(String raw)throws Exception{
        JSONObject input=parseObject(raw);JSONObject cards=input.has("cards")?input.optJSONObject("cards"):input;
        if(cards==null)throw new IOException("Expected a cards object");
        if(cards.length()>10000)throw new IOException("Too many cards");
        JSONObject out=new JSONObject();for(String key:keys(cards)){int guid=guid(key);JSONObject row=cards.optJSONObject(key);if(row==null)throw new IOException("Expected a card object for GUID " + key);validate(guid,row);out.put(key,copy(row));}return out;
    }
    public static int guid(String raw)throws Exception{try{int n=Integer.parseInt(raw);if(n<1||!String.valueOf(n).equals(raw))throw new Exception();return n;}catch(Exception e){throw new IOException("Invalid card GUID: " + raw);}}
    public static void validate(int guid,JSONObject card)throws Exception{
        if(card==null||card.toString().length()>2*1024*1024)throw new IOException("Missing or oversized card");JSONObject entity=card.optJSONObject("entity");if(entity==null||entity.optJSONArray("components")==null)throw new IOException("Card needs an entity with a components array");
        int inner=number(component(entity,"Card",false).getJSONObject("$data").opt("Guid"),"Card.Guid",1,Integer.MAX_VALUE);if(inner!=guid)throw new IOException("Card GUID differs from its table key");
        Object prefab=card.opt("prefabName");if(prefab!=null&&prefab!=JSONObject.NULL&&!(prefab instanceof String))throw new IOException("Expected a string prefabName");
        for(String field:new String[]{"usable","isFighter","isEnv","isPower","isPrimaryPower","isTeamup","isAquatic","ignoreDeckLimit"})if(card.has(field)&&!(card.get(field)instanceof Boolean))throw new IOException(field+" must be a boolean");
        for(String field:new String[]{"displaySunCost","displayAttack","displayHealth"})if(card.has(field))number(card.get(field),field,Integer.MIN_VALUE,Integer.MAX_VALUE);
        walk(card,0,new int[]{0},false);validateEntity(entity);
    }
    private static void validateEntity(JSONObject entity)throws Exception{
        JSONArray components=entity.getJSONArray("components");Set<String> types=new HashSet<>();for(int i=0;i<components.length();i++){JSONObject c=components.optJSONObject(i);if(c==null||!(c.opt("$type")instanceof String)||c.optJSONObject("$data")==null)throw new IOException("Invalid native component");String type=shortType(c.getString("$type"));if(!types.add(type))throw new IOException("Duplicate native component: " + type);}
        walk(entity,0,new int[]{0},true);
    }
    private static void walk(Object value,int depth,int[] count,boolean graph)throws Exception{
        if(depth>160||++count[0]>100000)throw new IOException("Card exceeds the nesting or node limit");
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)walk(a.get(i),depth+1,count,graph);return;}
        if(!(value instanceof JSONObject))return;JSONObject o=(JSONObject)value;
        if(graph&&o.has("$type")){if(!(o.get("$type")instanceof String)||o.getString("$type").isEmpty()||o.getString("$type").length()>1000||o.optJSONObject("$data")==null)throw new IOException("Invalid native type or data object");}
        JSONArray components=graph?o.optJSONArray("components"):null;if(components!=null){Set<String> types=new HashSet<>();for(int i=0;i<components.length();i++){JSONObject c=components.optJSONObject(i);if(c==null||!(c.opt("$type")instanceof String)||c.optJSONObject("$data")==null)throw new IOException("Invalid native component");String type=shortType(c.getString("$type"));if(!types.add(type))throw new IOException("Duplicate native component: " + type);}}
        for(String key:keys(o))walk(o.get(key),depth+1,count,graph);
    }
    public static JSONObject texts(JSONObject card,Map<String,String> cn)throws Exception{JSONObject out=new JSONObject();String prefab=card.optString("prefabName","");for(String field:TEXT_FIELDS.keySet())out.put(field,cn.getOrDefault(prefab+TEXT_FIELDS.get(field),""));return out;}

    /** Copies one card and changes only the selected native stats and their display mirrors. */
    public static JSONObject withStats(int guid, JSONObject card, JSONObject stats) throws Exception {
        validate(guid, card);
        if (stats == null || stats.length() == 0) throw new IOException("Supply cost, attack or health");
        String[] names = {"cost", "attack", "health"};
        String[] components = {"SunCost", "Attack", "Health"};
        String[] fields = {"SunCostValue", "AttackValue", "MaxHealth"};
        String[] displays = {"displaySunCost", "displayAttack", "displayHealth"};
        exact(stats, names);
        JSONObject result = copy(card);
        JSONObject entity = result.getJSONObject("entity");
        for (int i = 0; i < names.length; i++) {
            if (!stats.has(names[i])) continue;
            int replacement = number(stats.get(names[i]), names[i], Integer.MIN_VALUE, Integer.MAX_VALUE);
            JSONObject node = component(entity, components[i], false);
            JSONObject value = node.getJSONObject("$data").optJSONObject(fields[i]);
            if (value == null) throw new IOException("Nonstandard stat field: " + fields[i]);
            number(value.opt("BaseValue"), fields[i] + ".BaseValue", Integer.MIN_VALUE, Integer.MAX_VALUE);
            value.put("BaseValue", replacement);
            result.put(displays[i], replacement);
        }
        return result;
    }
}
