package lab.franziska.protocol;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** No wire paths. Adapter supplies an exact fresh indexed row; all items pass before mutation. */
public final class MediaDeleteGuard {
    private MediaDeleteGuard() {}
    public interface Guard {void check() throws Exception;}
    public interface Index {
        Source query(MediaWire.Frame request) throws Exception;
        // Called under the lifecycle monitor after full hash and final row/path requery.
        boolean remove(Source source) throws Exception;
    }
    public interface Results {void item(long id,boolean success) throws Exception;}
    public static final class Source {
        public final MediaWire.Frame item;
        public final File root,file;
        public Source(MediaWire.Frame item,File root,File file) {this.item=item;this.root=root;this.file=file;}
    }
    public static boolean same(MediaWire.Frame a,MediaWire.Frame b) {
        return a!=null&&b!=null&&a.id==b.id&&a.kind==b.kind&&a.size==b.size
                &&a.modified==b.modified&&a.name.equals(b.name);
    }
    private static void fail() throws IOException {throw new IOException("MEDIA_DELETE_CHANGED");}
    private static void path(Source s,MediaWire.Frame r) throws Exception {
        if(s==null||!same(s.item,r)||s.file==null||s.root==null)fail();
        File canonical=s.file.getCanonicalFile();
        // Reject symlinks and traversal, including any symlinked parent.
        if(!canonical.equals(s.file.getAbsoluteFile())
                ||!canonical.getPath().startsWith(s.root.getCanonicalPath()+File.separator)
                ||!s.file.isFile()||s.file.length()!=r.size||s.file.lastModified()/1000!=r.modified)fail();
    }
    public static void hash(File file,long size,byte[] expected,Guard guard) throws Exception {
        guard.check();MessageDigest digest=MessageDigest.getInstance("SHA-256");
        FileInputStream in=new FileInputStream(file);long count=0;
        try {byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1) {
            guard.check();count+=n;if(count>size)fail();digest.update(buffer,0,n);
        }}finally{in.close();}
        guard.check();if(count!=size||file.length()!=size||!MediaWire.sameHash(digest.digest(),expected))fail();
    }
    private static Source verify(MediaWire.Frame r,Source previous,Index index,Guard guard) throws Exception {
        guard.check();Source s=index.query(r);path(s,r);
        if(previous!=null&&!previous.file.equals(s.file))fail();
        hash(s.file,r.size,r.hash,guard);
        guard.check();Source fresh=index.query(r);path(fresh,r);
        if(!s.file.equals(fresh.file))fail();return fresh;
    }
    public static int execute(List<MediaWire.Frame> requests,Index index,Guard guard,Object lock,Results results) throws Exception {
        if(requests==null||requests.isEmpty()||requests.size()>128)fail();
        HashSet<Long> ids=new HashSet<Long>();ArrayList<Source> sources=new ArrayList<Source>();
        for(MediaWire.Frame r:requests) {
            if(r==null||r.type!=MediaWire.DELETE_ITEM||!ids.add(r.id))fail();
            sources.add(verify(r,null,index,guard));
        }
        int count=0;
        for(int i=0;i<requests.size();i++) {
            MediaWire.Frame r=requests.get(i);boolean success;
            try {
                Source fresh=verify(r,sources.get(i),index,guard);
                synchronized(lock) {
                    guard.check();Source finalRow=index.query(r);path(finalRow,r);
                    if(!fresh.file.equals(finalRow.file))fail();
                    guard.check();success=index.remove(finalRow);
                }
            }catch(Exception failure) {results.item(r.id,false);return count;}
            results.item(r.id,success);
            if(!success)return count;
            count++;
        }
        return count;
    }
}
