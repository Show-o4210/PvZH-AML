package com.pvzh.sharecode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import static com.pvzh.sharecode.NativeJson.*;

/** R73 scalar and block-table validation for caller-supplied level JSON. */
public final class OfflineLevelEdits {
    private OfflineLevelEdits() {}

    public static String eventHash(JSONObject event) throws Exception {
        return OfflineModArchive.hex(OfflineModArchive.digest().digest(canonical(event).getBytes(StandardCharsets.UTF_8)));
    }

    /** Applies a validated subset of a draft and returns a detached copy. */
    public static JSONObject apply(JSONObject source, JSONObject draft) throws Exception {
        return apply(source, draft, null);
    }

    /** Optionally binds the draft to the caller's current card mapping fingerprint. */
    public static JSONObject apply(JSONObject source, JSONObject draft, String mappingSha256) throws Exception {
        exact(draft, "id", "mapping_sha256", "changes", "operations");
        if (!source.getString("Id").equals(draft.getString("id"))) throw new IOException("Draft is for another level");
        if (mappingSha256 != null && !mappingSha256.equals(draft.opt("mapping_sha256"))) {
            throw new IOException("Draft uses a different card mapping");
        }
        JSONObject result = copy(source);
        Set<String> blockTouched = new LinkedHashSet<>();
        JSONObject changes = draft.getJSONObject("changes");
        for (String path : keys(changes)) field(result, path, changes.get(path), blockTouched);
        JSONArray operations = draft.getJSONArray("operations");
        if (changes.length() == 0 && operations.length() == 0) throw new IOException("No level changes");
        JSONArray originalEvents = source.optJSONArray("GameEvents");
        List<Integer> originalIndices = new ArrayList<>();
        if (originalEvents != null) for (int i = 0; i < originalEvents.length(); i++) originalIndices.add(i);
        for (int i = 0; i < operations.length(); i++) {
            JSONObject operation = operations.getJSONObject(i);
            switch (operation.getString("op")) {
                case "set":
                    exact(operation, "op", "path", "value");
                    field(result, operation.getString("path"), operation.get("value"), blockTouched);
                    break;
                case "block_table":
                    blockTable(result, operation, blockTouched);
                    break;
                case "lane_type":
                    exact(operation, "op", "lane", "lane_number", "value");
                    JSONArray lanes = result.getJSONObject("BoardConfig").getJSONArray("LaneEntries");
                    if (operation.has("lane") == operation.has("lane_number")) throw new IOException("Supply one lane selector");
                    int lane = operation.has("lane") ? number(operation.get("lane"), "lane", 0, lanes.length() - 1)
                            : number(operation.get("lane_number"), "lane_number", 1, lanes.length()) - 1;
                    JSONObject laneRow = lanes.getJSONObject(lane);
                    if (!laneRow.has("LaneType")) throw new IOException("Lane has no terrain field");
                    laneRow.put("LaneType", number(operation.get("value"), "LaneType", 0, 3));
                    break;
                case "event_move":
                case "event_remove":
                    editEventOrder(result, originalEvents, originalIndices, operation);
                    break;
                default:
                    throw new IOException("Operation requires a full campaign context: " + operation.getString("op"));
            }
        }
        validateBlock(result, blockTouched);
        return result;
    }

    private static void editEventOrder(JSONObject result, JSONArray originals, List<Integer> indices,
                                       JSONObject operation) throws Exception {
        boolean move = operation.getString("op").equals("event_move");
        if (move) exact(operation, "op", "index", "expected_sha256", "position");
        else exact(operation, "op", "index", "expected_sha256");
        int index = number(operation.opt("index"), "event index", 0, Integer.MAX_VALUE);
        int current = indices.indexOf(index);
        if (current < 0 || !eventHash(originals.getJSONObject(index)).equals(operation.opt("expected_sha256"))) {
            throw new IOException("Original event SHA-256 no longer matches: " + index);
        }
        JSONArray events = result.getJSONArray("GameEvents");
        List<Object> order = new ArrayList<>();
        for (int i = 0; i < events.length(); i++) order.add(events.get(i));
        Object moving = order.remove(current);
        Integer originalIndex = indices.remove(current);
        if (move) {
            int target = number(operation.opt("position"), "event position", 0, order.size());
            order.add(target, moving);
            indices.add(target, originalIndex);
        }
        result.put("GameEvents", new JSONArray(order));
    }

    private static Object get(JSONObject root,String path)throws Exception {
        Object value=root;for(String part:path.split("\\.")){if(value instanceof JSONObject)value=((JSONObject)value).opt(part);else if(value instanceof JSONArray&&part.matches("[0-9]+")){int i=Integer.parseInt(part);JSONArray a=(JSONArray)value;value=i<a.length()?a.opt(i):null;}else return null;}return value;
    }
    private static void put(JSONObject root,String path,Object value)throws Exception {
        String[] parts=path.split("\\.");Object at=root;
        for(int i=0;i<parts.length-1;i++){if(at instanceof JSONObject)at=((JSONObject)at).opt(parts[i]);else if(at instanceof JSONArray&&parts[i].matches("[0-9]+"))at=((JSONArray)at).opt(Integer.parseInt(parts[i]));else at=null;if(at==null)throw new IOException("目标关卡缺少字段："+path);}
        String key=parts[parts.length-1];if(at instanceof JSONObject&&((JSONObject)at).has(key))((JSONObject)at).put(key,value);
        else if(at instanceof JSONArray&&key.matches("[0-9]+")&&Integer.parseInt(key)<((JSONArray)at).length())((JSONArray)at).put(Integer.parseInt(key),value);
        else throw new IOException("目标关卡缺少字段："+path);
    }
    private static boolean scalarPath(String path){return path.matches("(?:PlayerConfig|OpponentConfig)\\.Parameters\\.[A-Za-z][A-Za-z0-9]*")||path.matches("(?:PlayerConfig|OpponentConfig)\\.Parameters\\.SuperBlockTable\\.TableEntries\\.[0-9]+\\.(?:RngWeight|ChargeAmount)")||Arrays.asList("Status","MustPlayAllPlayableCards","DisableSurprisePhase","DelayHeroIntros","HideSurpriseVisual","DisableDynamicDifficulty","OpponentElo","EloGainMultiplier","EnableReadySetPlant","SkipHeroSelect","PlayerConfig.HeroSelection","OpponentConfig.HeroSelection","PlayerConfig.SuperblockMultiplier","OpponentConfig.SuperblockMultiplier","RepresentationConfig.Type").contains(path);}
    private static void enumRange(Object value,String name,int low,int high)throws Exception{number(value,name,low,high);}
    private static void field(JSONObject level,String path,Object value,Set<String> blockTouched)throws Exception {
        Object old=get(level,path);if(old==null||old==JSONObject.NULL)throw new IOException("目标关卡缺少可编辑字段："+path);
        if(!scalarPath(path))throw new IOException("尚未开放该关卡标量字段："+path);
        if(old instanceof Boolean){if(!(value instanceof Boolean))throw new IOException(path+"需要布尔值");}
        else if(path.equals("EloGainMultiplier")||path.endsWith(".SuperblockMultiplier")){
            if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())||Math.abs(((Number)value).doubleValue())>Float.MAX_VALUE)throw new IOException(path+"需要可序列化为32位浮点的数字");
        }else if(old instanceof Number)number(value,path,Integer.MIN_VALUE,Integer.MAX_VALUE);
        else throw new IOException("仅允许已有数字或布尔字段："+path);
        if(path.endsWith(".Faction")||path.endsWith(".HeroSelection"))enumRange(value,path,0,1);
        if(path.equals("Status"))enumRange(value,path,0,3);
        if(path.equals("RepresentationConfig.Type"))enumRange(value,path,0,5);
        if(path.contains(".SuperBlockTable.")){String side=path.startsWith("PlayerConfig.")?"PlayerConfig":"OpponentConfig";blockTouched.add(side);if(path.endsWith(".RngWeight"))enumRange(value,path,0,100);}
        put(level,path,value);
    }
    private static void blockTable(JSONObject level,JSONObject operation,Set<String> touched)throws Exception {
        exact(operation,"op","side","entries");String s=operation.getString("side"),side=s.equals("player")?"PlayerConfig":s.equals("opponent")?"OpponentConfig":null;if(side==null)throw new IOException("side需要player或opponent");
        JSONArray input=operation.getJSONArray("entries"),entries=new JSONArray();JSONObject table=(JSONObject)get(level,side+".Parameters.SuperBlockTable");if(table==null)throw new IOException("关卡缺少格挡表");
        JSONArray old=table.getJSONArray("TableEntries");if(input.length()==0)throw new IOException("格挡表不能为空");
        for(int i=0;i<input.length();i++){JSONObject row=input.getJSONObject(i);exact(row,"weight","charge");JSONObject entry=old.length()==0?new JSONObject():copy(old.getJSONObject(Math.min(i,old.length()-1)));entry.put("RngWeight",number(row.opt("weight"),"格挡weight",0,100));entry.put("ChargeAmount",number(row.opt("charge"),"格挡charge",Integer.MIN_VALUE,Integer.MAX_VALUE));entries.put(entry);}table.put("TableEntries",entries);touched.add(side);
    }
    private static void validateBlock(JSONObject level,Set<String> sides)throws Exception{for(String side:sides){JSONArray rows=((JSONObject)get(level,side+".Parameters.SuperBlockTable")).getJSONArray("TableEntries");long total=0;for(int i=0;i<rows.length();i++)total+=number(rows.getJSONObject(i).get("RngWeight"),"格挡weight",0,100);if(total!=100)throw new IOException(side+"格挡概率总和必须为100，实际："+total);}}

    private static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;List<String> sorted=new ArrayList<>(keys(o));Collections.sort(sorted);StringBuilder b=new StringBuilder("{");for(String key:sorted){if(b.length()>1)b.append(',');b.append(JSONObject.quote(key)).append(':').append(canonical(o.get(key)));}return b.append('}').toString();}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)b.append(',');b.append(canonical(a.get(i)));}return b.append(']').toString();}
        return value==null||value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
}
