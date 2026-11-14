package com.pvzh.sharecode;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Read-only v22 type-tree decoder. Values retain exact original byte ranges. */
final class NativeArtworkTree {
    static final int LIMIT=128*1024*1024, MAX_VALUES=2000000;
    static final class Reader {
        final byte[] data; final boolean little; final int end,alignmentBase; int p;
        Reader(byte[] data,int p,int end,boolean little)throws IOException{
            this(data,p,end,little,0);
        }
        Reader(byte[] data,int p,int end,boolean little,int alignmentBase)throws IOException{
            if(p<0||end<p||end>data.length)throw new IOException("美术资源范围无效");
            if(alignmentBase<0||alignmentBase>p)throw new IOException("美术资源对齐基点无效");
            this.data=data;this.p=p;this.end=end;this.little=little;this.alignmentBase=alignmentBase;
        }
        void need(int n)throws IOException{if(n<0||n>end-p)throw new IOException("美术资源数据越界");}
        int u8()throws IOException{need(1);return data[p++]&255;}
        int u16()throws IOException{int a=u8(),b=u8();return little?a|(b<<8):(a<<8)|b;}
        int i32()throws IOException{need(4);int v=ByteBuffer.wrap(data,p,4).order(little?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN).getInt();p+=4;return v;}
        long i64()throws IOException{need(8);long v=ByteBuffer.wrap(data,p,8).order(little?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN).getLong();p+=8;return v;}
        float f32()throws IOException{return Float.intBitsToFloat(i32());}
        double f64()throws IOException{return Double.longBitsToDouble(i64());}
        void skip(int n)throws IOException{need(n);p+=n;}
        byte[] bytes(int n)throws IOException{need(n);byte[] v=Arrays.copyOfRange(data,p,p+n);p+=n;return v;}
        void align()throws IOException{int q=alignmentBase+((p-alignmentBase+3)&~3);skip(q-p);}
        void align16()throws IOException{int q=(p+15)&~15;skip(q-p);}
        String cstr()throws IOException{int start=p;while(u8()!=0){}return utf8(data,start,p-start-1);}
    }
    static String utf8(byte[] data,int start,int length)throws IOException{
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data,start,length)).toString();}
        catch(CharacterCodingException ex){throw new IOException("美术资源文字不是有效UTF-8",ex);}
    }
    static int bounded(long n)throws IOException{if(n<0||n>LIMIT)throw new IOException("美术资源超过大小预算");return(int)n;}
    static int count(int n,int max)throws IOException{if(n<0||n>max)throw new IOException("美术资源计数无效");return n;}
    static final class Node {
        String type,name; int size,flags,level; final List<Node> children=new ArrayList<>();
    }
    static final class Value {
        final Node node; final int offset; byte[] source; boolean changed; int length,rawOffset=-1,rawLength=-1,arrayCount=-1;
        Object scalar; byte[] rawData; List<Value> children=Collections.emptyList(),elements=Collections.emptyList();
        Value(Node n,int offset){node=n;this.offset=offset;}
        Value optional(String name)throws IOException{
            Value found=null;for(Value v:children)if(v.node.name.equals(name)){if(found!=null)throw new IOException("美术资源字段重名："+name);found=v;}return found;
        }
        Value field(String name)throws IOException{Value v=optional(name);if(v==null)throw new IOException("美术资源缺少字段："+name);return v;}
        Value at(String... names)throws IOException{Value v=this;for(String n:names)v=v.field(n);return v;}
        long integer()throws IOException{if(!(scalar instanceof Long))throw new IOException("美术资源字段不是整数："+node.name);return((Long)scalar).longValue();}
        double number()throws IOException{if(!(scalar instanceof Number))throw new IOException("美术资源字段不是数字："+node.name);return((Number)scalar).doubleValue();}
        String text()throws IOException{if(node.type.equals("string")&&rawData!=null)return utf8(rawData,rawOffset,rawLength);if(!(scalar instanceof String))throw new IOException("美术资源字段不是文字："+node.name);return(String)scalar;}
    }
    static final class Budget{int values;void add()throws IOException{if(++values>MAX_VALUES)throw new IOException("美术资源类型树展开超过预算");}}
    static Node readTree(Reader reader)throws IOException{
        int n=count(reader.i32(),100000),strings=bounded(reader.i32());if(n==0)throw new IOException("美术资源缺少类型树");
        byte[] table=reader.bytes(Math.multiplyExact(n,32)),buffer=reader.bytes(strings);
        Reader t=new Reader(table,0,table.length,true);Node root=null;List<Node> parents=new ArrayList<>();
        for(int i=0;i<n;i++){
            t.u16();int level=t.u8();t.u8();int type=t.i32(),name=t.i32();Node node=new Node();node.level=level;node.size=t.i32();t.i32();node.flags=t.i32();t.i64();
            node.type=string(buffer,type);node.name=string(buffer,name);
            if(level>64||level>parents.size()||(i==0&&level!=0)||(i>0&&level==0))throw new IOException("美术资源类型树层级无效");
            while(parents.size()>level)parents.remove(parents.size()-1);
            if(level==0)root=node;else parents.get(level-1).children.add(node);
            parents.add(node);
        }return root;
    }
    private static String string(byte[] buffer,int offset)throws IOException{
        if(offset<0){String v=NativeArtworkTypeStrings.value(offset&0x7fffffff);if(v==null)throw new IOException("尚不支持此类型树公共字符串编号");return v;}
        if(offset>=buffer.length)throw new IOException("类型树字符串越界");Reader r=new Reader(buffer,offset,buffer.length,true);return r.cstr();
    }
    static Value read(Node node,Reader r,Budget budget)throws IOException{
        try{return readValue(node,r,budget);}catch(IOException ex){throw new IOException("无法读取美术字段 "+node.name+"("+node.type+")："+ex.getMessage(),ex);}
    }
    private static Value readValue(Node node,Reader r,Budget budget)throws IOException{
        budget.add();Value v=new Value(node,r.p);v.source=r.data;boolean aligned=(node.flags&0x4000)!=0;
        switch(node.type){
            case "bool":case "UInt8":case "char":case "unsigned char":v.scalar=Long.valueOf(r.u8());break;
            case "SInt8":v.scalar=Long.valueOf((byte)r.u8());break;
            case "SInt16":case "short":v.scalar=Long.valueOf((short)r.u16());break;
            case "UInt16":case "unsigned short":v.scalar=Long.valueOf(r.u16());break;
            case "int":case "SInt32":v.scalar=Long.valueOf(r.i32());break;
            case "UInt32":case "unsigned int":v.scalar=Long.valueOf(r.i32()&0xffffffffL);break;
            case "SInt64":case "UInt64":case "long long":case "unsigned long long":case "FileSize":v.scalar=Long.valueOf(r.i64());break;
            case "float":v.scalar=Double.valueOf(r.f32());break;
            case "double":v.scalar=Double.valueOf(r.f64());break;
            // TextAsset.m_Script may contain binary bytes although its native
            // type is string. Preserve it without decoding; only requested
            // names/resource paths require strict UTF-8 via Value.text().
            case "string":{int n=bounded(r.i32());r.need(n);v.rawData=r.data;v.rawOffset=r.p;v.rawLength=n;r.skip(n);r.align();break;}
            case "TypelessData":{int n=bounded(r.i32());r.need(n);v.rawOffset=r.p;v.rawLength=n;r.skip(n);break;}
            case "ManagedReferencesRegistry":case "ReferencedObject":case "ReferencedObjectData":throw new IOException("首期美术编辑不支持动态托管引用类型树");
            default:
                Node array=node.type.equals("Array")?node:node.children.size()==1&&node.children.get(0).type.equals("Array")?node.children.get(0):null;
                if(array!=null){
                    if(array.children.size()!=2||!array.children.get(0).type.equals("int"))throw new IOException("美术资源数组描述无效");
                    int n=count(r.i32(),LIMIT);Node element=array.children.get(1);v.arrayCount=n;
                    if(isByte(element)&&element.children.isEmpty()&&(element.flags&0x4000)==0){r.need(n);v.rawOffset=r.p;v.rawLength=n;r.skip(n);}
                    else{if(n>500000)throw new IOException("美术资源复合数组超过预算");List<Value> rows=new ArrayList<>(n);for(int i=0;i<n;i++)rows.add(read(element,r,budget));v.elements=rows;}
                    if((array.flags&0x4000)!=0)aligned=true;
                }else if(!node.children.isEmpty()){
                    List<Value> rows=new ArrayList<>(node.children.size());for(Node child:node.children)rows.add(read(child,r,budget));v.children=rows;
                }else{
                    if(node.type.startsWith("PPtr<")||node.size<0)throw new IOException("无法完整读取美术资源类型："+node.type);
                    v.rawOffset=r.p;v.rawLength=bounded(node.size);r.skip(v.rawLength);
                }
        }
        if(aligned)r.align();v.length=r.p-v.offset;return v;
    }
    private static boolean isByte(Node n){return n.type.equals("UInt8")||n.type.equals("SInt8")||n.type.equals("char")||n.type.equals("unsigned char");}
    static void set(Value value,long number){value.scalar=Long.valueOf(number);value.changed=true;}
    static void set(Value value,double number){value.scalar=Double.valueOf(number);value.changed=true;}
    static void text(Value value,String text){value.rawData=text.getBytes(StandardCharsets.UTF_8);value.rawOffset=0;value.rawLength=value.rawData.length;value.changed=true;}
    static void bytes(Value value,byte[] data){value.rawData=data.clone();value.rawOffset=0;value.rawLength=data.length;value.arrayCount=data.length;value.changed=true;}
    static void array(Value value,List<Value> elements){value.elements=new ArrayList<>(elements);value.arrayCount=elements.size();value.changed=true;}
    static Value cloneValue(Value value)throws IOException{
        byte[] encoded=encode(value);Reader r=new Reader(encoded,0,encoded.length,true);Value copy=read(value.node,r,new Budget());if(r.p!=encoded.length)throw new IOException("原生字段副本回读不完整");return copy;
    }
    static String signature(Node node){StringBuilder out=new StringBuilder();signature(node,out);return out.toString();}
    private static void signature(Node n,StringBuilder out){out.append(n.type).append('|').append(n.name).append('|').append(n.size).append('|').append(n.flags).append('(');for(Node c:n.children)signature(c,out);out.append(')');}
    static byte[] encode(Value value)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();write(value,out);return out.toByteArray();}
    private static void le(ByteArrayOutputStream out,long number,int size){for(int i=0;i<size;i++)out.write((int)(number>>>(i*8))&255);}
    private static void align(ByteArrayOutputStream out){while((out.size()&3)!=0)out.write(0);}
    private static void write(Value v,ByteArrayOutputStream out)throws IOException{
        Node node=v.node;boolean aligned=(node.flags&0x4000)!=0;
        if(v.scalar!=null){
            if(!v.changed&&v.source!=null){int size=node.size>=0?node.size:v.length;out.write(v.source,v.offset,size);}
            else if(v.scalar instanceof Long){int size=node.size;if(size!=1&&size!=2&&size!=4&&size!=8)throw new IOException("原生整数宽度无效");le(out,((Long)v.scalar).longValue(),size);}
            else if(node.type.equals("float"))le(out,Float.floatToRawIntBits(((Number)v.scalar).floatValue()),4);
            else if(node.type.equals("double"))le(out,Double.doubleToRawLongBits(((Number)v.scalar).doubleValue()),8);
            else throw new IOException("原生字段写出类型未知");
        }else if(node.type.equals("string")){
            le(out,v.rawLength,4);out.write(v.rawData,v.rawOffset,v.rawLength);align(out);
        }else if(node.type.equals("TypelessData")){
            le(out,v.rawLength,4);out.write(v.changed?v.rawData:v.source,v.rawOffset,v.rawLength);
        }else{
            Node a=node.type.equals("Array")?node:node.children.size()==1&&node.children.get(0).type.equals("Array")?node.children.get(0):null;
            if(a!=null){le(out,v.arrayCount,4);if(v.rawLength>=0){out.write(v.changed?v.rawData:v.source,v.rawOffset,v.rawLength);}else for(Value e:v.elements)write(e,out);if((a.flags&0x4000)!=0)aligned=true;}
            else if(!v.children.isEmpty())for(Value c:v.children)write(c,out);
            else if(v.rawLength>=0)out.write(v.source,v.rawOffset,v.rawLength);
            else throw new IOException("未知原生字段无法保持");
        }
        if(aligned)align(out);
        if(out.size()>LIMIT)throw new IOException("原生字段超过写出预算");
    }
    interface Visitor{void visit(Value value,String path)throws IOException;}
    static void walk(Value value,String path,Visitor visitor)throws IOException{
        visitor.visit(value,path);for(Value child:value.children)walk(child,path.isEmpty()?child.node.name:path+"."+child.node.name,visitor);
        for(int i=0;i<value.elements.size();i++)walk(value.elements.get(i),path+"["+i+"]",visitor);
    }
}
