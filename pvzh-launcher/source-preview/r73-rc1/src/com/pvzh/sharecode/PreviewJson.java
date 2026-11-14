package com.pvzh.sharecode;

import java.io.IOException;
import java.math.BigDecimal;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded JSON grammar and duplicate-key checks from the R73 authoring reader. */
final class PreviewJson {
    private PreviewJson() {}

    static JSONObject object(String source) throws IOException {
        if (source == null) throw new IOException("Missing JSON object");
        new StrictDocument(source).validate();
        try { return new JSONObject(source); }
        catch (JSONException invalid) { throw new IOException("Expected a JSON object", invalid); }
    }

    private static void checkNumber(String token)throws IOException{
        try{
            new BigDecimal(token);
        }catch(NumberFormatException e){throw new IOException("JSON number outside the supported range",e);}
    }
    private static final class StrictDocument {
        final String source;int position,values;StrictDocument(String source){this.source=source;}
        void validate()throws IOException{space();value(0);space();if(position!=source.length())bad();}
        void value(int depth)throws IOException{
            space();if(position>=source.length()||depth>108||++values>300000)bad();char c=source.charAt(position);
            if(c=='{'){position++;space();java.util.Set<String> keys=new java.util.HashSet<>();if(take('}'))return;while(true){space();if(position>=source.length()||source.charAt(position)!='"')bad();String key=string();if(!keys.add(key))bad();space();if(!take(':'))bad();value(depth+1);space();if(take('}'))return;if(!take(','))bad();}}
            else if(c=='['){position++;space();if(take(']'))return;while(true){value(depth+1);space();if(take(']'))return;if(!take(','))bad();}}
            else if(c=='"')string();else if(c=='t')literal("true");else if(c=='f')literal("false");else if(c=='n')literal("null");else number();
        }
        String string()throws IOException{
            if(!take('"'))bad();StringBuilder out=new StringBuilder();boolean closed=false;
            while(position<source.length()){
                char c=source.charAt(position++);if(c=='"'){closed=true;break;}if(c<32)bad();
                if(c=='\\'){if(position>=source.length())bad();c=source.charAt(position++);switch(c){case '"':case '\\':case '/':break;case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;case 'u':int code=0;for(int i=0;i<4;i++){if(position>=source.length())bad();char hex=source.charAt(position++);int digit=hex>='0'&&hex<='9'?hex-'0':hex>='a'&&hex<='f'?hex-'a'+10:hex>='A'&&hex<='F'?hex-'A'+10:-1;if(digit<0)bad();code=(code<<4)|digit;}c=(char)code;break;default:bad();}}
                out.append(c);
            }
            if(!closed)bad();String result=out.toString();for(int i=0;i<result.length();i++){char c=result.charAt(i);if(Character.isHighSurrogate(c)){if(i+1>=result.length()||!Character.isLowSurrogate(result.charAt(++i)))bad();}else if(Character.isLowSurrogate(c))bad();}return result;
        }
        void number()throws IOException{int start=position;
            take('-');if(!take('0')){if(!digit19())bad();while(digit())position++;}else if(digit())bad();
            if(take('.')){if(!digit())bad();while(digit())position++;}
            if(take('e')||take('E')){if(!take('+'))take('-');if(!digit())bad();while(digit())position++;}checkNumber(source.substring(start,position));
        }
        boolean digit19(){if(position<source.length()&&source.charAt(position)>='1'&&source.charAt(position)<='9'){position++;return true;}return false;}
        boolean digit(){return position<source.length()&&source.charAt(position)>='0'&&source.charAt(position)<='9';}
        void literal(String value)throws IOException{if(!source.startsWith(value,position))bad();position+=value.length();}
        boolean take(char c){if(position<source.length()&&source.charAt(position)==c){position++;return true;}return false;}
        void space(){while(position<source.length()){char c=source.charAt(position);if(c!=' '&&c!='\t'&&c!='\r'&&c!='\n')break;position++;}}
        void bad()throws IOException{throw new IOException("Invalid JSON or duplicate key");}
    }
}
