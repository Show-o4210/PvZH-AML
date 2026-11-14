package com.pvzh.sharecode;

import java.io.*;
import java.util.*;
import net.jpountz.lz4.LZ4Factory;

/** Reads UnityFS 7/8 artwork with one little-endian SerializedFile v22. */
public final class OfflineUnityArtwork {
    private static final int LIMIT=128*1024*1024, MAX_RGBA=32*1024*1024;
    private static final LZ4Factory LZ4=LZ4Factory.safeInstance();
    private final byte[] full;
    private final Entry cab;
    private final String unityVersion;
    private final List<Entry> entries=new ArrayList<>();
    private final LinkedHashMap<Long,Obj> objects=new LinkedHashMap<>();
    private static final class Block{int plain,encoded,flags;}
    private static final class Entry{String name;int offset,size,flags;}
    private static final class Obj{long id;int classId,typeId,start,size;NativeArtworkTree.Value tree;}
    private static NativeArtworkTree.Reader reader(byte[] b,int p,int end,boolean little)throws IOException{return new NativeArtworkTree.Reader(b,p,end,little);}
    private static int bounded(long n)throws IOException{return NativeArtworkTree.bounded(n);}
    private static int count(int n,int max)throws IOException{return NativeArtworkTree.count(n,max);}
    static void requireMemory(long bytes,String stage)throws IOException{Runtime runtime=Runtime.getRuntime();long available=runtime.maxMemory()-runtime.totalMemory()+runtime.freeMemory(),reserve=Math.min(8L*1024*1024,Math.max(1024*1024,runtime.maxMemory()/32));if(bytes>=0&&bytes>available-reserve){System.gc();available=runtime.maxMemory()-runtime.totalMemory()+runtime.freeMemory();}if(bytes<0||bytes>available-reserve)throw new IOException("美术资源内存预算不足（"+stage+"），未保存修改；请保留原件并重新进入编辑器后重试");}
    static byte[] allocate(int bytes,String stage)throws IOException{bounded(bytes);requireMemory(bytes,stage);try{return new byte[bytes];}catch(OutOfMemoryError error){throw new IOException("美术资源内存分配失败（"+stage+"），未保存修改，原件保持",error);}}
    private static byte[] decode(byte[] bytes,int expected,int flags)throws IOException{
        bounded(expected);int mode=flags&63;if(mode==0){if(bytes.length!=expected)throw new IOException("美术Bundle未压缩块长度不一致");return bytes;}
        if(mode!=2&&mode!=3)throw new IOException("首期只支持未压缩或LZ4美术Bundle");
        try{byte[] out=new byte[expected];int n=LZ4.safeDecompressor().decompress(bytes,0,bytes.length,out,0,out.length);if(n!=expected)throw new IOException("美术Bundle解压长度错误");return out;}
        catch(RuntimeException ex){throw new IOException("美术Bundle压缩数据损坏",ex);}
    }
    public OfflineUnityArtwork(byte[] source)throws IOException{
        if(source==null||source.length>LIMIT)throw new IOException("美术Bundle过大或为空");byte[] original=allocate(source.length,"读取原始Bundle");System.arraycopy(source,0,original,0,source.length);
        NativeArtworkTree.Reader r=reader(original,0,original.length,false);
        if(!r.cstr().equals("UnityFS"))throw new IOException("需要真实UnityFS美术Bundle");
        int bundleVersion=r.i32();if(bundleVersion<7||bundleVersion>8)throw new IOException("首期支持UnityFS 7/8");
        r.cstr();r.cstr();if(r.i64()!=original.length)throw new IOException("美术Bundle长度不一致");
        int comp=bounded(r.i32()),plain=bounded(r.i32());int flags=r.i32();if((flags&64)==0||(flags&~1023)!=0)throw new IOException("不支持此UnityFS目录布局或加密标记");r.align16();
        int headerEnd=r.p;int infoStart=(flags&128)!=0?original.length-comp:r.p;
        if(infoStart<r.p||comp>original.length-infoStart)throw new IOException("美术Bundle块表越界");
        byte[] info=decode(Arrays.copyOfRange(original,infoStart,infoStart+comp),plain,flags);
        NativeArtworkTree.Reader table=reader(info,0,info.length,false);table.skip(16);List<Block> blocks=new ArrayList<>();int n=count(table.i32(),50000);long total=0;
        for(int i=0;i<n;i++){Block b=new Block();b.plain=bounded(table.i32());b.encoded=bounded(table.i32());b.flags=table.u16();total+=b.plain;bounded(total);blocks.add(b);}
        int nodeCount=count(table.i32(),4096);Set<String> names=new HashSet<>();Entry found=null;
        for(int i=0;i<nodeCount;i++){
            Entry e=new Entry();e.offset=bounded(table.i64());e.size=bounded(table.i64());e.flags=table.i32();e.name=table.cstr();
            if(e.offset>total||e.size>total-e.offset||e.name.isEmpty()||!names.add(e.name.toLowerCase(Locale.ROOT)))throw new IOException("美术Bundle目录重复或范围无效");
            if((e.flags&4)!=0){if(found!=null)throw new IOException("首期只支持单SerializedFile美术Bundle");found=e;}entries.add(e);
        }
        if(found==null)throw new IOException("美术Bundle缺少SerializedFile目录");cab=found;
        List<Entry> sorted=new ArrayList<>(entries);Collections.sort(sorted,Comparator.comparingInt(e->e.offset));int previous=0;
        for(Entry e:sorted){if(e.size>0&&e.offset<previous)throw new IOException("美术Bundle目录区域互相重叠");previous=Math.max(previous,e.offset+e.size);}
        int start=(flags&128)!=0?headerEnd:infoStart+comp;if((flags&512)!=0)start=(start+15)&~15;
        int bodyEnd=(flags&128)!=0?infoStart:original.length;NativeArtworkTree.Reader stream=reader(original,start,bodyEnd,false);
        full=allocate((int)total,"读取原生资源");int plainOffset=0;for(Block b:blocks){stream.need(b.encoded);int mode=b.flags&63;if(mode==0){if(b.encoded!=b.plain)throw new IOException("美术Bundle未压缩块长度不一致");System.arraycopy(original,stream.p,full,plainOffset,b.plain);}else if(mode==2||mode==3){try{int decoded=LZ4.safeDecompressor().decompress(original,stream.p,b.encoded,full,plainOffset,b.plain);if(decoded!=b.plain)throw new IOException("美术Bundle解压长度错误");}catch(RuntimeException error){throw new IOException("美术Bundle压缩数据损坏",error);}}else throw new IOException("首期只支持未压缩或LZ4美术Bundle");stream.p+=b.encoded;plainOffset+=b.plain;}
        if(stream.p!=bodyEnd)throw new IOException("美术Bundle含未识别的压缩区间");
        NativeArtworkTree.Reader h=reader(full,cab.offset,cab.offset+cab.size,false);h.skip(8);int version=h.i32();h.skip(4);int endian=h.u8();h.skip(3);
        if(version!=22||endian!=0)throw new IOException("首期支持小端SerializedFile v22");
        int metadataSize=bounded(h.i32());if(h.i64()!=cab.size)throw new IOException("美术CAB长度无效");int dataRelative=bounded(h.i64());h.skip(8);
        int metadataEnd=h.p+metadataSize;int dataOffset=cab.offset+dataRelative;if(metadataEnd>dataOffset||dataOffset>cab.offset+cab.size)throw new IOException("美术CAB元数据越界");
        NativeArtworkTree.Reader meta=new NativeArtworkTree.Reader(full,h.p,metadataEnd,true,cab.offset);unityVersion=meta.cstr();meta.i32();if(meta.u8()==0)throw new IOException("美术编辑需要原包实际类型树");
        int typeCount=count(meta.i32(),4096);int[] classes=new int[typeCount];NativeArtworkTree.Node[] trees=new NativeArtworkTree.Node[typeCount];
        for(int i=0;i<typeCount;i++){int cls=meta.i32();classes[i]=cls;meta.u8();meta.u16();if(cls==114)meta.skip(16);meta.skip(16);trees[i]=NativeArtworkTree.readTree(meta);int deps=count(meta.i32(),100000);meta.skip(Math.multiplyExact(deps,4));}
        int objectCount=count(meta.i32(),50000);NativeArtworkTree.Budget budget=new NativeArtworkTree.Budget();
        for(int i=0;i<objectCount;i++){
            meta.align();Obj o=new Obj();o.id=meta.i64();long offset=meta.i64();o.size=bounded(meta.i32());o.typeId=meta.i32();
            if(o.typeId<0||o.typeId>=typeCount||objects.containsKey(o.id)||offset<0||offset>cab.size-dataRelative||o.size>cab.size-dataRelative-offset)throw new IOException("美术CAB对象表无效");
            o.start=bounded((long)dataOffset+offset);o.classId=classes[o.typeId];NativeArtworkTree.Reader body=new NativeArtworkTree.Reader(full,o.start,o.start+o.size,true,cab.offset);o.tree=NativeArtworkTree.read(trees[o.typeId],body,budget);
            if(body.p!=o.start+o.size)throw new IOException("美术对象类型树未完整覆盖原始数据："+o.classId);objects.put(o.id,o);
        }
        int scripts=count(meta.i32(),100000);for(int i=0;i<scripts;i++){meta.i32();meta.align();meta.i64();}
        int ext=count(meta.i32(),4096);for(int i=0;i<ext;i++){meta.cstr();meta.skip(16);meta.i32();meta.cstr();}
        int referenceTypes=count(meta.i32(),4096);if(referenceTypes!=0)throw new IOException("首期不支持带动态引用类型的美术CAB");meta.cstr();
        if(meta.p!=metadataEnd)throw new IOException("美术CAB存在未识别的元数据尾部");
        List<Obj> byStart=new ArrayList<>(objects.values());Collections.sort(byStart,Comparator.comparingInt(o->o.start));previous=dataOffset;
        for(Obj o:byStart){if(o.start<previous)throw new IOException("美术CAB对象数据互相重叠");previous=o.start+o.size;}
        int[] referenceCount = {0};
        for(final Obj o:objects.values())NativeArtworkTree.walk(o.tree,"",new NativeArtworkTree.Visitor(){public void visit(NativeArtworkTree.Value v,String path)throws IOException{
            if(v.node.type.startsWith("PPtr<")&&v.node.type.endsWith(">")){
                long f=v.field("m_FileID").integer(),id=v.field("m_PathID").integer();
                if(f<0||f>ext)throw new IOException("美术资源外部引用编号越界");
                if(f==0&&id!=0&&!objects.containsKey(id))throw new IOException("美术资源存在缺失的本地对象引用");
                if(++referenceCount[0]>500000)throw new IOException("美术资源引用超过预算");
            }
        }});
        validateRegistration();
    }
    private void validateRegistration()throws IOException{
        Obj registration=null;for(Obj o:objects.values())if(o.classId==142){if(registration!=null)throw new IOException("美术Bundle有多个原生注册对象");registration=o;}
        if(registration==null)throw new IOException("美术Bundle缺少原生注册对象");
        NativeArtworkTree.Value preload=registration.tree.field("m_PreloadTable"),container=registration.tree.field("m_Container");
        if(preload.arrayCount<0||container.arrayCount<0)throw new IOException("原生资源注册表不是数组");
        for(NativeArtworkTree.Value pair:container.elements)validateSlice(pair.field("second"),preload.arrayCount);
        validateSlice(registration.tree.field("m_MainAsset"),preload.arrayCount);
    }
    private static void validateSlice(NativeArtworkTree.Value info,int length)throws IOException{
        long start=info.field("preloadIndex").integer(),size=info.field("preloadSize").integer();if(start<0||size<0||start>length||size>length-start)throw new IOException("原生资源预加载切片越界");
    }
    public String unityVersion() { return unityVersion; }

    public Map<String, Integer> entries() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Entry entry : entries) result.put(entry.name, entry.size);
        return Collections.unmodifiableMap(result);
    }

    public Map<Long, Integer> objectTypes() {
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (Obj object : objects.values()) result.put(object.id, object.classId);
        return Collections.unmodifiableMap(result);
    }

    public byte[] objectBytes(long pathId) throws IOException {
        Obj object = objects.get(pathId);
        if (object == null) throw new IOException("Unknown Unity object: " + pathId);
        return Arrays.copyOfRange(full, object.start, object.start + object.size);
    }

    private static long number(NativeArtworkTree.Value tree,String field)throws IOException{return tree.field(field).integer();}
    private static int positive(long n)throws IOException{if(n<=0||n>4096)throw new IOException("首期纹理尺寸需要在1～4096之间");return(int)n;}
    private static int rgbaLength(int width,int height)throws IOException{long size=(long)width*height*4;if(width<=0||height<=0||size>MAX_RGBA)throw new IOException("首期像素大小超过32MiB预算");return(int)size;}
    private static final class Pixels{int start,width,height,format,encodedLength;}
    private Entry resource(String path)throws IOException{
        for(Entry e:entries)if(e!=cab&&(path.equals(e.name)||path.equals("archive:/"+cab.name+"/"+e.name)))return e;
        throw new IOException("纹理流未精确绑定本Bundle的resS资源，跨CAB暂不支持");
    }
    private Pixels pixels(Obj o)throws IOException{
        if(!o.tree.node.type.equals("Texture2D"))throw new IOException("纹理对象类型树与原生classId不一致");
        int width=positive(number(o.tree,"m_Width")),height=positive(number(o.tree,"m_Height"));int length=rgbaLength(width,height);
        int format=(int)number(o.tree,"m_TextureFormat");if(format!=4&&format!=47)throw new IOException("仅支持读取单 mip RGBA32 或 ETC2_RGBA8 格式");int encodedLength=format==4?length:((width+3)/4)*((height+3)/4)*16;
        if(number(o.tree,"m_MipCount")!=1)throw new IOException("首期仅支持单mip纹理");
        NativeArtworkTree.Value stream=o.tree.field("m_StreamData"),image=o.tree.field("image data");if(!image.node.type.equals("TypelessData"))throw new IOException("尚不支持此纹理像素字段编码");long offset=number(stream,"offset"),size=number(stream,"size");
        Pixels p=new Pixels();p.width=width;p.height=height;p.format=format;p.encodedLength=encodedLength;
        if(size>0){
            String path=stream.field("path").text();Entry e=resource(path);if(offset<0||size!=encodedLength||offset>e.size||size>e.size-offset||image.rawLength!=0)throw new IOException("纹理流区间与RGBA32像素长度不一致");
            p.start=e.offset+(int)offset;
        }else{
            if(offset!=0||!stream.field("path").text().isEmpty()||size!=0||image.rawLength!=encodedLength||image.rawOffset<o.start||image.rawOffset>o.start+o.size-encodedLength)throw new IOException("内嵌纹理像素与流式描述不一致");
            p.start=image.rawOffset;
        }return p;
    }

    /** Returns a copy of the encoded pixels for a supported single-mip texture. */
    public EncodedTexture textureEncoded(long pathId) throws IOException {
        Obj object = objects.get(pathId);
        if (object == null || object.classId != 28) throw new IOException("Expected a Texture2D object: " + pathId);
        Pixels pixels = pixels(object);
        return new EncodedTexture(Arrays.copyOfRange(full, pixels.start, pixels.start + pixels.encodedLength),
                pixels.width, pixels.height, pixels.format);
    }

    public static final class EncodedTexture {
        public final int width, height, format;
        private final byte[] bytes;

        private EncodedTexture(byte[] bytes, int width, int height, int format) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
            this.format = format;
        }

        public byte[] bytes() { return bytes.clone(); }
    }

    /** Returns null when the Sprite layout is outside the supported mesh subset. */
    public SpriteMesh spriteMesh(long pathId, int textureWidth, int textureHeight) throws IOException {
        Obj object = objects.get(pathId);
        if (object == null || object.classId != 213) throw new IOException("Expected a Sprite object: " + pathId);
        NativeArtworkSpriteMesh.Mesh mesh = NativeArtworkSpriteMesh.read(object.tree, textureWidth, textureHeight);
        return mesh == null ? null : new SpriteMesh(mesh.triangles, mesh.pivotX, mesh.pivotY);
    }

    public static final class SpriteMesh {
        public final float pivotX, pivotY;
        private final float[] triangles;

        private SpriteMesh(float[] triangles, float pivotX, float pivotY) {
            this.triangles = triangles;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
        }

        public float[] triangles() { return triangles.clone(); }
    }
}
