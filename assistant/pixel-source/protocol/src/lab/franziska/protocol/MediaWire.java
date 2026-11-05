package lab.franziska.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;

/** Bounded Glass media inventory and bulk-copy protocol on the separate pinned TLS lane. */
public final class MediaWire {
    public static final byte HELLO=1, PLAN=2, CATALOG_BEGIN=3, ITEM=4, CATALOG_END=5,
            CATALOG_ACK=6, FILE_BEGIN=7, DATA=8, COMMIT=9, ACK=10, DONE=11, DONE_ACK=12, SHARE_HELLO=13, GALLERY_HELLO=14, SELECT_END=15, DELETE_HELLO=16, DELETE_ITEM=17, DELETE_RESULT=18;
    public static final byte PHOTO=1, VIDEO=2;
    public static final int CHUNK=48*1024, HASH_BYTES=32, MAX_ITEMS=8192, MAX_THUMB=12*1024;
    public static final long MAX_PHOTO=32L*1024*1024, MAX_VIDEO=4L*1024*1024*1024;
    public static final long MAX_CATALOG_SIZE=64L*1024*1024*1024;
    public static final byte PLAN_CATALOG=1, PLAN_ALL=2, PLAN_SELECTED=4, PLAN_DELETE=8;
    private static final Charset UTF8=Charset.forName("UTF-8");
    private MediaWire() {}

    public static final class Frame {
        public final byte type, kind, flags;
        public final long id, size, modified, offset;
        public final int count;
        public final String name;
        public final byte[] hash, data;
        private Frame(byte t,byte k,byte f,long id,long size,long modified,long offset,int count,
                      String name,byte[] hash,byte[] data) {
            type=t;kind=k;flags=f;this.id=id;this.size=size;this.modified=modified;
            this.offset=offset;this.count=count;this.name=name;this.hash=hash;this.data=data;
        }
    }
    private static IOException bad() { return new IOException("MEDIA_FRAME_INVALID"); }
    private static void checkHash(byte[] h) throws IOException { if(h==null||h.length!=HASH_BYTES)throw bad(); }
    private static void checkKind(byte k,long size) throws IOException {
        if((k!=PHOTO&&k!=VIDEO)||size<4||size>(k==PHOTO?MAX_PHOTO:MAX_VIDEO))throw bad();
    }
    private static byte[] name(String n) throws IOException {
        if(n==null||n.length()==0||n.indexOf('/')>=0||n.indexOf('\\')>=0||n.indexOf('\0')>=0)
            throw bad();
        byte[] b=n.getBytes(UTF8);if(b.length<1||b.length>120)throw bad();return b;
    }
    private static String readName(DataInputStream in) throws IOException {
        int length=in.readUnsignedByte();if(length<1||length>120)throw bad();
        byte[] b=new byte[length];in.readFully(b);String n=new String(b,UTF8);
        if(!MessageDigest.isEqual(n.getBytes(UTF8),b))throw bad();name(n);return n;
    }
    private static byte[] simple(byte t) { return new byte[]{t}; }
    public static byte[] deleteHello(byte[] digest) throws IOException {return hashFrame(DELETE_HELLO,digest);}
    public static boolean canDelete(byte hello) {return hello==DELETE_HELLO;}
    public static byte[] deleteItem(long id,byte kind,long size,long modified,String name,byte[] hash) throws IOException {
        byte[] b=fileBegin(id,kind,size,modified,name,hash);b[0]=DELETE_ITEM;return b;
    }
    public static byte[] deleteResult(long id,boolean success) throws IOException {
        if(id<=0)throw bad();ByteArrayOutputStream b=new ByteArrayOutputStream(10);
        DataOutputStream o=new DataOutputStream(b);o.writeByte(DELETE_RESULT);o.writeLong(id);o.writeByte(success?1:0);return b.toByteArray();
    }
    private static boolean validPlan(byte f) {return f==PLAN_DELETE||((f&~7)==0&&(f&6)!=6);}
    public static byte[] galleryHello(byte[] digest) throws IOException {return hashFrame(GALLERY_HELLO,digest);}
    public static byte[] selectEnd() {return simple(SELECT_END);}
    public static byte[] hello(byte[] digest) throws IOException {checkHash(digest);return hashFrame(HELLO,digest);}
    /** One explicit original selected on Glass; never authorizes archive or automatic-photo copying. */
    public static byte[] shareHello(byte[] digest,long selectedId) throws IOException {
        checkHash(digest);if(selectedId<=0)throw bad();
        ByteArrayOutputStream b=new ByteArrayOutputStream(41);DataOutputStream o=new DataOutputStream(b);
        o.writeByte(SHARE_HELLO);o.write(digest);o.writeLong(selectedId);return b.toByteArray();
    }
    public static byte[] plan(byte flags) throws IOException {
        if(!validPlan(flags))throw bad();return new byte[]{PLAN,flags};
    }
    public static byte[] catalogBegin(int count) throws IOException {
        if(count<0||count>MAX_ITEMS)throw bad();ByteArrayOutputStream b=new ByteArrayOutputStream(5);
        DataOutputStream o=new DataOutputStream(b);o.writeByte(CATALOG_BEGIN);o.writeInt(count);return b.toByteArray();
    }
    public static byte[] item(long id,byte kind,long size,long modified,String filename,byte[] thumb)
            throws IOException {
        if(id<=0||modified<0||(kind!=PHOTO&&kind!=VIDEO)||size<4||size>MAX_CATALOG_SIZE)
            throw bad();
        byte[] n=name(filename);
        if(thumb==null)thumb=new byte[0];if(thumb.length>MAX_THUMB)throw bad();
        ByteArrayOutputStream b=new ByteArrayOutputStream(31+n.length+thumb.length);
        DataOutputStream o=new DataOutputStream(b);o.writeByte(ITEM);o.writeLong(id);o.writeByte(kind);
        o.writeLong(size);o.writeLong(modified);o.writeByte(n.length);o.write(n);
        o.writeShort(thumb.length);o.write(thumb);return b.toByteArray();
    }
    public static byte[] catalogEnd(byte[] digest) throws IOException {checkHash(digest);return hashFrame(CATALOG_END,digest);}
    public static byte[] catalogAck(byte[] digest) throws IOException {checkHash(digest);return hashFrame(CATALOG_ACK,digest);}
    public static byte[] fileBegin(long id,byte kind,long size,long modified,String filename,byte[] hash)
            throws IOException {
        if(id<=0||modified<0)throw bad();checkKind(kind,size);checkHash(hash);byte[] n=name(filename);
        ByteArrayOutputStream b=new ByteArrayOutputStream(60+n.length);DataOutputStream o=new DataOutputStream(b);
        o.writeByte(FILE_BEGIN);o.writeLong(id);o.writeByte(kind);o.writeLong(size);o.writeLong(modified);
        o.writeByte(n.length);o.write(n);o.write(hash);return b.toByteArray();
    }
    public static byte[] data(long id,long offset,byte[] data,int length) throws IOException {
        if(id<=0||offset<0||data==null||length<1||length>CHUNK||length>data.length
                ||offset>MAX_VIDEO-length)throw bad();
        ByteArrayOutputStream b=new ByteArrayOutputStream(17+length);DataOutputStream o=new DataOutputStream(b);
        o.writeByte(DATA);o.writeLong(id);o.writeLong(offset);o.write(data,0,length);return b.toByteArray();
    }
    public static byte[] commit(long id,byte[] hash) throws IOException {return terminal(COMMIT,id,hash);}
    public static byte[] ack(long id,byte[] hash) throws IOException {return terminal(ACK,id,hash);}
    public static byte[] done(int count,boolean complete) throws IOException {
        if(count<0||count>MAX_ITEMS)throw bad();ByteArrayOutputStream b=new ByteArrayOutputStream(6);
        DataOutputStream o=new DataOutputStream(b);o.writeByte(DONE);o.writeInt(count);o.writeByte(complete?1:0);
        return b.toByteArray();
    }
    public static byte[] doneAck() {return simple(DONE_ACK);}
    private static byte[] hashFrame(byte t,byte[] h) throws IOException {
        checkHash(h);byte[] result=new byte[33];result[0]=t;System.arraycopy(h,0,result,1,32);return result;
    }
    private static byte[] terminal(byte t,long id,byte[] h) throws IOException {
        if(id<=0)throw bad();checkHash(h);ByteArrayOutputStream b=new ByteArrayOutputStream(41);
        DataOutputStream o=new DataOutputStream(b);o.writeByte(t);o.writeLong(id);o.write(h);return b.toByteArray();
    }
    public static Frame parse(byte[] raw) throws IOException {
        if(raw==null||raw.length<1||raw.length>CHUNK+17)throw bad();
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(raw));byte t=in.readByte();
        byte k=0,f=0;long id=0,size=0,modified=0,offset=0;int count=0;String n=null;
        byte[] h=null,d=null;
        if(t==HELLO||t==DELETE_HELLO||t==GALLERY_HELLO||t==CATALOG_END||t==CATALOG_ACK) {
            if(raw.length!=33)throw bad();h=new byte[32];in.readFully(h);
        } else if(t==SHARE_HELLO) {
            if(raw.length!=41)throw bad();h=new byte[32];in.readFully(h);id=in.readLong();if(id<=0)throw bad();
        } else if(t==PLAN) {
            if(raw.length!=2)throw bad();f=in.readByte();if(!validPlan(f))throw bad();
        } else if(t==CATALOG_BEGIN) {
            if(raw.length!=5)throw bad();count=in.readInt();if(count<0||count>MAX_ITEMS)throw bad();
        } else if(t==ITEM||t==FILE_BEGIN||t==DELETE_ITEM) {
            id=in.readLong();k=in.readByte();size=in.readLong();modified=in.readLong();
            n=readName(in);if(id<=0||modified<0)throw bad();
            if(t==ITEM) {if((k!=PHOTO&&k!=VIDEO)||size<4||size>MAX_CATALOG_SIZE)throw bad();}
            else checkKind(k,size);
            if(t==ITEM) {int length=in.readUnsignedShort();if(length>MAX_THUMB)throw bad();
                d=new byte[length];in.readFully(d);
            } else {h=new byte[32];in.readFully(h);}
        } else if(t==DATA) {
            id=in.readLong();offset=in.readLong();int length=in.available();
            if(id<=0||offset<0||length<1||length>CHUNK||offset>MAX_VIDEO-length)throw bad();
            d=new byte[length];in.readFully(d);
        } else if(t==COMMIT||t==ACK) {
            if(raw.length!=41)throw bad();id=in.readLong();if(id<=0)throw bad();
            h=new byte[32];in.readFully(h);
        } else if(t==DELETE_RESULT) {
            if(raw.length!=10)throw bad();id=in.readLong();f=in.readByte();if(id<=0||(f!=0&&f!=1))throw bad();
        } else if(t==DONE) {
            if(raw.length!=6)throw bad();count=in.readInt();f=in.readByte();
            if(count<0||count>MAX_ITEMS||(f!=0&&f!=1))throw bad();
        } else if((t!=DONE_ACK&&t!=SELECT_END)||raw.length!=1)throw bad();
        if(in.available()!=0)throw bad();
        return new Frame(t,k,f,id,size,modified,offset,count,n,h,d);
    }
    public static boolean sameHash(byte[] a,byte[] b) {
        return a!=null&&b!=null&&a.length==32&&b.length==32&&MessageDigest.isEqual(a,b);
    }
}
