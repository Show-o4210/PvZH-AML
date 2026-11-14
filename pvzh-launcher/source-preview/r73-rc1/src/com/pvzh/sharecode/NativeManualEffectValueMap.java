package com.pvzh.sharecode;

import java.io.IOException;
import java.util.*;
import org.json.*;
import static com.pvzh.sharecode.NativeJson.*;

/** Author-side controls for the two dictionary mappings observed in original cards.
 * Reads never migrate a node. Explicit edits preserve unrelated and unknown entries.
 */
public final class NativeManualEffectValueMap {
    private NativeManualEffectValueMap(){}
    public static boolean isNode(JSONObject node){
        return node!=null&&node.optString("$type").split(",",2)[0].trim().equals("PvZCards.Engine.Components.EffectValueDescriptor");
    }
    public static JSONObject read(JSONObject node)throws Exception{
        if(!isNode(node))throw new IOException("Expected an EffectValueDescriptor node");
        JSONObject data=node.optJSONObject("$data");
        if(data==null)throw new IOException("Effect value node needs $data");
        if(data.has("DestToSourceMap")){
            Object value=data.get("DestToSourceMap");
            if(!(value instanceof JSONObject))throw new IOException("DestToSourceMap must be an object");
            return copy((JSONObject)value);
        }
        // Compatibility is a view only: never insert native data just by opening a card.
        String legacy=data.optString("MappingType","");
        if(legacy.equals("DamageToHeal"))return obj("HealAmount","DamageAmount");
        if(legacy.equals("HealToDamage"))return obj("DamageAmount","HealAmount");
        return new JSONObject();
    }
    public static JSONObject preset(JSONObject node,String id)throws Exception{
        JSONObject map=read(node);String destination,source;
        if("DamageToHeal".equals(id)){destination="HealAmount";source="DamageAmount";}
        else if("HealToDamage".equals(id)){destination="DamageAmount";source="HealAmount";}
        else throw new IOException("Unknown effect value preset");
        if(map.has(destination)&&!equal(map.get(destination),source))throw new IOException("Existing destination uses a different source");
        map.put(destination,source);return map;
    }
    public static JSONObject remove(JSONObject node,String destination)throws Exception{
        JSONObject map=read(node);
        if(!map.has(destination)||!recognized(destination,map.get(destination)))throw new IOException("Only a recognized mapping can be removed");
        map.remove(destination);return map;
    }
    public static boolean recognized(String destination,Object source){
        return destination.equals("HealAmount")&&"DamageAmount".equals(source)||destination.equals("DamageAmount")&&"HealAmount".equals(source);
    }
    public static JSONObject checked(JSONObject node,Object value)throws Exception{
        if(!(value instanceof JSONObject))throw new IOException("Expected an effect mappings object");
        JSONObject before=read(node),after=copy((JSONObject)value);
        Set<String> names=new HashSet<>(keys(before));names.addAll(keys(after));
        for(String name:names){
            boolean had=before.has(name),has=after.has(name);
            if(had&&has&&equal(before.get(name),after.get(name)))continue;
            if(had&&!recognized(name,before.get(name)))throw new IOException("Preserve unknown mapping: " + name);
            if(has&&!recognized(name,after.get(name)))throw new IOException("Unsupported effect mapping");
        }
        return after;
    }
}
