package com.pvzh.sharecode;
import org.json.*;
import java.io.IOException;
import java.util.*;

final class NativeJson {
    static JSONObject obj(Object... pairs)throws Exception{JSONObject o=new JSONObject();for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]==null?JSONObject.NULL:pairs[i+1]);return o;}
    static JSONArray arr(Object... values){JSONArray a=new JSONArray();for(Object v:values)a.put(v);return a;}
    static String join(String separator,Iterable<String> values){StringBuilder s=new StringBuilder();for(String v:values){if(s.length()>0)s.append(separator);s.append(v);}return s.toString();}
    static JSONObject copy(JSONObject o)throws Exception{return new JSONObject(o.toString());}
    static JSONArray copy(JSONArray a)throws Exception{return new JSONArray(a.toString());}
    static Object copyValue(Object value)throws Exception{
        if(value instanceof JSONObject)return copy((JSONObject)value);
        if(value instanceof JSONArray)return copy((JSONArray)value);
        return value;
    }
    static Set<String> keys(JSONObject o){Set<String> out=new LinkedHashSet<>();Iterator<String> i=o.keys();while(i.hasNext())out.add(i.next());return out;}
    static Set<String> strings(JSONArray a)throws Exception{Set<String> s=new LinkedHashSet<>();for(int i=0;i<a.length();i++)s.add(a.getString(i));return s;}
    static void exact(JSONObject value,String... allowed)throws Exception{Set<String> extra=keys(value);extra.removeAll(Arrays.asList(allowed));if(!extra.isEmpty())throw new IOException("Unknown fields: " + extra);}
    static int number(Object value,String label,int low,int high)throws Exception{if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<low||((Number)value).longValue()>high)throw new IOException(label+" must be an integer in range "+low+"～"+high);return ((Number)value).intValue();}
    static String shortType(String s){String name=s.split(",",2)[0];return name.substring(name.lastIndexOf('.')+1);}
    static JSONObject component(JSONObject entity,String name,boolean optional)throws Exception{JSONObject found=null;JSONArray a=entity.getJSONArray("components");for(int i=0;i<a.length();i++){JSONObject n=a.getJSONObject(i);if(shortType(n.getString("$type")).equals(name)){if(found!=null)throw new IOException("Duplicate component: " + name);found=n;}}if(found==null&&!optional)throw new IOException("Missing component: " + name);return found;}
    static boolean contains(JSONArray a,Object v)throws Exception{for(int i=0;i<a.length();i++)if(equal(a.get(i),v))return true;return false;}
    static boolean equal(Object a,Object b)throws Exception{
        if(a==JSONObject.NULL)a=null;if(b==JSONObject.NULL)b=null;if(a==null||b==null)return a==b;
        if(a instanceof JSONObject&&b instanceof JSONObject){JSONObject x=(JSONObject)a,y=(JSONObject)b;if(!keys(x).equals(keys(y)))return false;for(String k:keys(x))if(!equal(x.get(k),y.get(k)))return false;return true;}
        if(a instanceof JSONArray&&b instanceof JSONArray){JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!equal(x.get(i),y.get(i)))return false;return true;}
        if(a instanceof Number&&b instanceof Number)return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()))==0;return a.equals(b);
    }
}
