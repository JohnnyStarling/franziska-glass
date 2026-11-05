package lab.franziska.pixelbridge;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import lab.franziska.protocol.Json;
import lab.franziska.protocol.Protocol;

/** Dedicated HTTPS plane. Never shares Glass credentials or accepts executable operations. */
public final class AgentEndpoint implements Closeable {
    public interface Bridge {
        Object capture(long deadlineNanos);
        Map<String,Object> invoke(Object request, String op, Map<String,Object> payload) throws Exception;
        void failed();
    }
    private final SSLServerSocket server;
    private final Bridge bridge;
    private final byte[] authorization;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<Runnable>());
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<Socket>());
    private volatile boolean closed;
    private long window, stopWindow;
    private int calls, stops;
    public AgentEndpoint(SSLContext tls, InetAddress address, int port, String token, Bridge bridge) throws Exception {
        if (!(address.isSiteLocalAddress() || address.isLoopbackAddress()) || address.isAnyLocalAddress())
            throw new IllegalArgumentException("private explicit bind required");
        if (!token.matches("[A-Za-z0-9_-]{43,128}")) throw new IllegalArgumentException("dedicated credential required");
        this.bridge = bridge;
        authorization = ("Bearer " + token).getBytes("US-ASCII");
        server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(port, 8, address);
        try { server.setEnabledProtocols(new String[]{"TLSv1.2"}); }
        catch (RuntimeException invalid) { server.close(); throw invalid; }
    }
    public int port() { return server.getLocalPort(); }
    public void start() {
        new Thread(new Runnable() { public void run() {
            while (!closed) try {
                final SSLSocket socket = (SSLSocket) server.accept();
                final long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                final Object request = bridge.capture(expires);
                clients.add(socket);
                try { workers.execute(new Runnable() { public void run() { serve(socket, request, expires); } }); }
                catch (RejectedExecutionException full) { clients.remove(socket); socket.close(); }
            } catch (Exception failure) { if (!closed) { close(); bridge.failed(); } }
        }}, "pixel-agent-https").start();
    }
    private synchronized boolean permit(boolean stop) {
        long now = System.nanoTime() / 1000000L;
        if (stop) { if (now - stopWindow >= 1000) { stopWindow = now; stops = 0; } return ++stops <= 5; }
        if (now - window >= 10000) { window = now; calls = 0; } return ++calls <= 20;
    }
    private void serve(final SSLSocket socket, Object requestAuthority, long expires) {
        ScheduledFuture<?> deadline;
        try { deadline = deadlines.schedule(new Runnable() { public void run() {
            try { socket.close(); } catch (IOException ignored) {}
        }}, Math.max(0L, expires - System.nanoTime()), TimeUnit.NANOSECONDS); } catch (RejectedExecutionException stopping) {
            clients.remove(socket); try {socket.close();} catch(IOException ignored){} return;
        }
        try {
            socket.setSoTimeout(1500); socket.startHandshake();
            InputStream input = socket.getInputStream();
            String first = line(input, 256);
            Map<String,String> headers = new HashMap<String,String>();
            int bytes = first.length();
            for (;;) {
                String next = line(input, 1024); bytes += next.length() + 2;
                if (bytes > 4096) throw new IOException();
                if (next.length() == 0) break;
                int colon = next.indexOf(':'); if (colon < 1) throw new IOException();
                String key = next.substring(0, colon).toLowerCase(Locale.US);
                if (!key.matches("[a-z0-9-]+") || headers.put(key, next.substring(colon+1).trim()) != null) throw new IOException();
            }
            String auth = headers.get("authorization");
            if (auth == null || !MessageDigest.isEqual(authorization, auth.getBytes("US-ASCII"))) { reply(socket, 401, "{}"); return; }
            if (!"POST /openclaw-adapter/v1/invoke HTTP/1.1".equals(first)
                    || !"application/json".equals(headers.get("content-type"))
                    || headers.containsKey("transfer-encoding") || headers.containsKey("content-encoding")) throw new IOException();
            String length = headers.get("content-length");
            if (length == null || !length.matches("[0-9]{1,5}")) throw new IOException();
            int size = Integer.parseInt(length);
            if (size > 16384) { reply(socket, 413, "{}"); return; }
            byte[] body = new byte[size]; new DataInputStream(input).readFully(body);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body)).toString();
            Map<String,Object> request = object(Json.parse(text));
            exact(request, "op", "payload");
            Object opValue = request.get("op"); if (!(opValue instanceof String)) throw new IOException();
            String op = (String) opValue;
            Map<String,Object> payload = object(request.get("payload"));
            validate(op, payload);
            if (!permit("stop".equals(op))) { reply(socket, 429, "{}"); return; }
            reply(socket, 200, Json.stringify(bridge.invoke(requestAuthority, op, payload)));
        } catch (Exception rejected) { try { reply(socket, 400, "{}"); } catch (Exception ignored) {} }
        finally { deadline.cancel(false); clients.remove(socket); try { socket.close(); } catch (IOException ignored) {} }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws IOException {
        if (!(value instanceof Map)) throw new IOException(); return (Map<String,Object>) value;
    }
    private static void exact(Map<String,Object> value, String... keys) throws IOException {
        if (!value.keySet().equals(new HashSet<String>(Arrays.asList(keys)))) throw new IOException();
    }
    private static void text(Object value, int max) throws IOException {
        if (!(value instanceof String) || ((String)value).length() < 1 || ((String)value).length() > max) throw new IOException();
    }
    public static void validate(String op, Map<String,Object> p) throws Exception {
        if ("status".equals(op) || "stop".equals(op)) { exact(p); return; }
        if ("show_card".equals(op)) {
            exact(p, "title", "lines"); text(p.get("title"),120);
            if (!(p.get("lines") instanceof List)) throw new IOException();
            List<?> lines = (List<?>)p.get("lines"); if (lines.size()<1 || lines.size()>3) throw new IOException();
            for (Object line:lines) text(line,240);
            Protocol.validatePayload("ui.card",p); return;
        }
        if ("show_vectors".equals(op)) {
            exact(p,"shapes"); if (!(p.get("shapes") instanceof List)) throw new IOException();
            List<?> shapes=(List<?>)p.get("shapes"); if(shapes.size()<1 || shapes.size()>64) throw new IOException();
            for(Object shape:shapes) {
                Map<String,Object> item=object(shape); exact(item,"kind","points");
                if (!(item.get("points") instanceof List)) throw new IOException();
                int n=((List<?>)item.get("points")).size(); Object kind=item.get("kind");
                if ("point".equals(kind) ? n!=1 : "polyline".equals(kind) ? n<2 || n>256 : n!=2) throw new IOException();
            }
            Protocol.validatePayload("ui.vector_scene",p); return;
        }
        throw new IOException();
    }
    private static String line(InputStream input, int max) throws IOException {
        StringBuilder b = new StringBuilder();
        for (;;) { int c=input.read(); if(c<0) throw new EOFException();
            if(c=='\r') { if(input.read()!='\n') throw new IOException(); return b.toString(); }
            if(c<32 || c>126 || b.length()>=max) throw new IOException(); b.append((char)c);
        }
    }
    private static void reply(Socket socket,int status,String json) throws IOException {
        byte[] body=json.getBytes("UTF-8"); if(body.length>32768) throw new IOException();
        OutputStream out=socket.getOutputStream();
        out.write(("HTTP/1.1 "+status+" Result\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes("US-ASCII"));
        out.write(body); out.flush();
    }
    public void close() {
        closed=true; try { server.close(); } catch(IOException ignored) {}
        // Drain already accepted replies (notably Stop); existing absolute deadlines still close slow clients.
        workers.shutdown(); deadlines.shutdown();
    }
}
