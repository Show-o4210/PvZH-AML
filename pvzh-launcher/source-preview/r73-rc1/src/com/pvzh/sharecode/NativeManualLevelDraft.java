package com.pvzh.sharecode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.pvzh.sharecode.NativeJson.*;

/** Typed edits to one level from a captured Mod and resource profile. */
public final class NativeManualLevelDraft {
    public static final class Event {
        final int sourceIndex;
        final String sha, kind, nativeType;
        final JSONObject original;
        final JSONObject edits=new JSONObject();

        public Event(int sourceIndex,String sha,String kind,String nativeType,JSONObject original){
            this.sourceIndex=sourceIndex;
            this.sha=sha;
            this.kind=kind;
            this.nativeType=nativeType;
            this.original=original;
        }

        Object value(String key){
            if(key.equals("round")&&edits.has("turn")||key.equals("turn")&&edits.has("round"))return null;
            return edits.has(key)?edits.opt(key):original.opt(key);
        }
        public void set(String key,Object value)throws Exception{
            if(key.equals("round"))edits.remove("turn");
            if(key.equals("turn"))edits.remove("round");
            if(sourceIndex>=0&&equal(original.opt(key),value))edits.remove(key);
            else edits.put(key,NativeJson.copyValue(value));
        }
    }

    final String id, mapping;
    private final JSONObject source;
    final JSONObject fields=new JSONObject();
    final Map<String,JSONObject> operations=new LinkedHashMap<>();
    final List<Event> events=new ArrayList<>();
    private final List<Event> originalEvents=new ArrayList<>();
    private final Map<Integer,JSONArray> boardOps=new LinkedHashMap<>();

    public NativeManualLevelDraft(JSONObject source,String mapping,JSONArray rows)throws Exception{
        this.source=copy(source);
        this.id=source.getString("Id");
        this.mapping=mapping;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            Event event=new Event(row.getInt("index"),row.getString("expected_sha256"),row.getString("kind"),
                    row.optString("native_type"),copy(row.getJSONObject("values")));
            events.add(event);
            originalEvents.add(event);
        }
    }

    static Object at(JSONObject source,String path){
        Object value=source;
        for(String key:path.split("\\.")){
            if(!(value instanceof JSONObject))return null;
            value=((JSONObject)value).opt(key);
        }
        return value;
    }

    public Object value(String path)throws Exception{return copyValue(fields.has(path)?fields.opt(path):at(source,path));}
    public void field(String path,Object value)throws Exception{
        if(equal(at(source,path),value))fields.remove(path);
        else fields.put(path,NativeJson.copyValue(value));
    }
    public void board(JSONObject op)throws Exception{
        int lane=op.optInt("lane_number")-1;
        boardOps.computeIfAbsent(lane,ignored->new JSONArray()).put(copy(op));
    }
    public void boardLane(int lane,JSONArray operations)throws Exception{
        if(operations.length()==0)boardOps.remove(lane);
        else boardOps.put(lane,copy(operations));
    }
    public List<Event> events(){ return events; }

    public boolean dirty(){
        if(fields.length()>0||!operations.isEmpty()||!boardOps.isEmpty()||!events.equals(originalEvents))return true;
        for(Event event:events)if(event.edits.length()>0)return true;
        return false;
    }

    public int changeCount(){
        int count=fields.length();
        Set<String> lanes=new HashSet<>();
        for(int lane:boardOps.keySet())lanes.add("lane."+lane);
        for(String key:operations.keySet()){
            if(key.startsWith("lane."))lanes.add(key);
            else count++;
        }
        Set<Event> changed=new HashSet<>();
        List<Event> retained=new ArrayList<>();
        for(Event event:originalEvents){
            if(events.contains(event))retained.add(event);
            else changed.add(event);
        }
        int position=0;
        for(Event event:events){
            if(event.sourceIndex<0){changed.add(event);continue;}
            int current=retained.indexOf(event);
            if(current!=position){
                retained.add(position,retained.remove(current));
                changed.add(event);
            }
            if(event.edits.length()>0)changed.add(event);
            position++;
        }
        return count+lanes.size()+changed.size();
    }

    public JSONObject row()throws Exception{
        JSONArray out=new JSONArray();
        for(JSONObject op:operations.values())out.put(copy(op));
        for(JSONArray lane:boardOps.values())for(int i=0;i<lane.length();i++)out.put(copy(lane.getJSONObject(i)));
        List<Event> order=new ArrayList<>(originalEvents);
        for(Event event:originalEvents)if(!events.contains(event)){
            out.put(eventOperation("event_remove",event));
            order.remove(event);
        }
        for(int i=0;i<events.size();i++){
            Event event=events.get(i);
            if(event.sourceIndex<0){
                out.put(obj("op","event_add","kind",event.kind,"values",copy(event.edits),"position",i));
                order.add(i,event);
                continue;
            }
            int current=order.indexOf(event);
            if(current!=i){
                out.put(eventOperation("event_move",event).put("position",i));
                order.add(i,order.remove(current));
            }
            if(event.edits.length()>0)
                out.put(eventOperation("event_update",event).put("kind",event.kind).put("values",copy(event.edits)));
        }
        if(fields.length()==0&&out.length()==0)throw new IOException("No level changes");
        return obj("id",id,"mapping_sha256",mapping,"changes",copy(fields),"operations",out);
    }

    private static JSONObject eventOperation(String kind,Event event)throws Exception{
        return obj("op",kind,"index",event.sourceIndex,"expected_sha256",event.sha);
    }
}
