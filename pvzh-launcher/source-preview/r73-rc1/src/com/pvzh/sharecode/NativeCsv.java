package com.pvzh.sharecode;
import java.io.IOException;
import java.util.*;

/** Keeps untouched CSV records exactly, including quoting and embedded newlines. */
public final class NativeCsv {
    static final class Row{int start,end;List<String> fields=new ArrayList<>();}
    static List<Row> parse(String source)throws IOException{
        List<Row> rows=new ArrayList<>();int p=0;
        while(p<source.length()){
            Row row=new Row();row.start=p;boolean done=false;
            while(!done){StringBuilder field=new StringBuilder();boolean quoted=p<source.length()&&source.charAt(p)=='"';
                if(quoted){p++;boolean closed=false;while(p<source.length()){char c=source.charAt(p++);if(c=='"'){if(p<source.length()&&source.charAt(p)=='"'){field.append('"');p++;}else{closed=true;break;}}else field.append(c);}if(!closed)throw new IOException("Unterminated quoted CSV field");}
                else {while(p<source.length()&&source.charAt(p)!=','&&source.charAt(p)!='\n'&&source.charAt(p)!='\r')field.append(source.charAt(p++));}
                row.fields.add(field.toString());
                if(p>=source.length()){done=true;}
                else if(source.charAt(p)==','){p++;}
                else if(source.charAt(p)=='\r'||source.charAt(p)=='\n'){char c=source.charAt(p++);if(c=='\r'&&p<source.length()&&source.charAt(p)=='\n')p++;done=true;}
                else throw new IOException("Unexpected character after a quoted CSV field");
            }row.end=p;rows.add(row);
        }return rows;
    }
    public static Map<String,String> values(String source)throws IOException{Map<String,String> out=new LinkedHashMap<>();for(Row r:parse(source))if(r.fields.size()==2)out.put(r.fields.get(0),r.fields.get(1));return out;}
    private static String quote(String value){return value.indexOf(',')>=0||value.indexOf('"')>=0||value.indexOf('\r')>=0||value.indexOf('\n')>=0?"\""+value.replace("\"","\"\"")+"\"":value;}
    public static String patch(String source,Map<String,String> replacements)throws Exception{
        StringBuilder out=new StringBuilder();Set<String> seen=new HashSet<>();
        for(Row r:parse(source)){
            String key=r.fields.get(0);
            if(!replacements.containsKey(key)){
                out.append(source,r.start,r.end);
                continue;
            }
            if(r.fields.size()!=2)throw new IOException("Expected two fields for localization key: " + key);
            if(!seen.add(key))throw new IOException("Duplicate localization key: " + key);
            out.append(quote(key)).append(',').append(quote(replacements.get(key))).append('\n');
        }
        for(Map.Entry<String,String> e:replacements.entrySet())if(!seen.contains(e.getKey())){if(out.length()>0&&out.charAt(out.length()-1)!='\n')out.append('\n');out.append(quote(e.getKey())).append(',').append(quote(e.getValue())).append('\n');}
        Map<String,String> before=values(source),after=values(out.toString());for(String key:before.keySet())if(!replacements.containsKey(key)&&!Objects.equals(before.get(key),after.get(key)))throw new IOException("An untouched localization row changed");return out.toString();
    }
}
