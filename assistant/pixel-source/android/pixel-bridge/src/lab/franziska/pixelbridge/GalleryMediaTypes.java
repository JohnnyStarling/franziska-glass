package lab.franziska.pixelbridge;

import java.util.Locale;
import lab.franziska.protocol.MediaWire;

final class GalleryMediaTypes {
    static boolean matches(GlassMediaCatalog.Item a,MediaWire.Frame b) {
        return a.id==b.id&&a.size==b.size&&a.modified==b.modified&&a.kind==b.kind&&a.name.equals(b.name);
    }
    static String extension(String name,byte kind) {
        int dot=name.lastIndexOf('.');String ext=dot<0?"":name.substring(dot+1).toLowerCase(Locale.US);
        return ext.matches("[a-z0-9]{1,8}")?ext:kind==MediaWire.PHOTO?"jpg":"mp4";
    }
    static String mime(String name,byte kind) {
        String e=extension(name,kind);
        if(kind==MediaWire.VIDEO) {
            if(e.equals("3gp"))return "video/3gpp";
            if(e.equals("webm"))return "video/webm";
            if(e.equals("mkv"))return "video/x-matroska";
            return "video/mp4";
        }
        if(e.equals("png"))return "image/png";
        if(e.equals("webp"))return "image/webp";
        if(e.equals("gif"))return "image/gif";
        if(e.equals("bmp"))return "image/bmp";
        if(e.equals("heic")||e.equals("heif"))return "image/heif";
        return "image/jpeg";
    }
}
