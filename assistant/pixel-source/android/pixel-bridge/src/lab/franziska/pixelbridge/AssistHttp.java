package lab.franziska.pixelbridge;

import java.net.URI;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import lab.franziska.protocol.Json;
import lab.franziska.protocol.Protocol;

/** One-shot owner-configured HTTPS only. Platform trust and hostname verifier remain untouched. */
public final class AssistHttp {
    public interface Encoder { String encode(byte[] bytes); }
    public interface Clock { long now(); }
    public static final long TIMEOUT_MS = 180000;
    private volatile boolean cancelled;
    private volatile HttpsURLConnection connection;
    private final Clock clock;
    public AssistHttp(Clock clock) { this.clock = clock; }
    public static URI route(String value) {
        try {
            URI u = new URI(value);
            if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getHost().length() == 0
                    || u.getRawUserInfo() != null || u.getRawFragment() != null || u.getRawQuery() != null
                    || u.getRawPath() == null || !u.getRawPath().startsWith("/")
                    || u.getPort() == 0 || u.getPort() > 65535) throw new Exception();
            return u;
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid HTTPS route"); }
    }
    public static boolean credential(String token) {
        if (token == null || token.length() < 1 || token.length() > 4096) return false;
        for (int i=0;i<token.length();i++) if (token.charAt(i)<33 || token.charAt(i)>126) return false;
        return true;
    }
    public void cancel() { cancelled = true; HttpsURLConnection c = connection; if (c != null) c.disconnect(); }
    private void check(long deadline) throws IOException { if (cancelled || clock.now() >= deadline) throw new IOException("Assistant request cancelled"); }
    public String execute(String url, String token, String id, byte[] image, byte[] audio, Encoder encoder) throws Exception {
        final long deadline = clock.now() + TIMEOUT_MS;
        if (!credential(token)) throw new IOException("Assistant configuration invalid");
        if (image.length > 262144 || audio.length > 512044 || image.length + audio.length == 0) throw new IOException("Assistant media invalid");
        final Thread watchdog = new Thread(new Runnable() { public void run() {
            try { long left; while ((left = deadline-clock.now()) > 0) Thread.sleep(left); cancel(); }
            catch (InterruptedException done) { Thread.currentThread().interrupt(); }
        }}, "assist-absolute-deadline");
        watchdog.setDaemon(true); watchdog.start();
        try {
            check(deadline);
            HttpsURLConnection c = (HttpsURLConnection) route(url).toURL().openConnection(); connection = c;
            check(deadline);
            c.setInstanceFollowRedirects(false); c.setUseCaches(false); c.setConnectTimeout((int) TIMEOUT_MS); c.setReadTimeout((int) TIMEOUT_MS);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            Map<String,Object> body = new LinkedHashMap<String,Object>();
            body.put("request_id", id); body.put("image_base64", encoder.encode(image)); body.put("audio_base64", encoder.encode(audio));
            byte[] bytes = Json.stringify(body).getBytes(Protocol.UTF8);
            c.setFixedLengthStreamingMode(bytes.length); // Streaming forbids implicit auth/redirect replay.
            check(deadline);
            OutputStream out = c.getOutputStream(); try { out.write(bytes); } finally { out.close(); Arrays.fill(bytes,(byte)0); }
            check(deadline);
            if (c.getResponseCode() != 200) throw new IOException("Assistant server rejected request");
            if (c.getContentLength() > 65536) throw new IOException("Assistant response invalid");
            InputStream in = c.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try { byte[] chunk = new byte[4096]; int n; while ((n=in.read(chunk)) != -1) { check(deadline); if (buffer.size()+n>65536) throw new IOException("Assistant response invalid"); buffer.write(chunk,0,n); } }
            finally { in.close(); }
            check(deadline); return response(buffer.toByteArray(), id);
        } catch (Exception failure) { throw new IOException(cancelled ? "Assistant request cancelled" : "Assistant request failed"); }
        finally { watchdog.interrupt(); HttpsURLConnection c=connection; connection=null; if(c!=null)c.disconnect(); }
    }
    public static String response(byte[] bytes, String id) throws Exception {
        if (bytes.length>65536) throw new IOException("Assistant response invalid");
        String json = Protocol.UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map)) throw new IOException("Assistant response invalid");
        Map<?,?> map = (Map<?,?>)parsed;
        if (map.size()!=2 || !id.equals(map.get("request_id")) || !(map.get("text") instanceof String)) throw new IOException("Assistant response invalid");
        String text=(String)map.get("text");
        if(text.length()==0 || text.length()>4000) throw new IOException("Assistant response invalid");
        return text;
    }
}
