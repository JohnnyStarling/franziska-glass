package lab.franziska.pixelbridge;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.*;

/** Bounded RFC6455 text transport. No redirects, extensions, automatic reconnect or trust override. */
final class GatewayWebSocket implements Closeable {
    static final int MAX_MESSAGE=2097152, MAX_HEADER=16384;
    static final int ASSIST_MAX_RECEIVED_BYTES=8*1024*1024, ASSIST_MAX_FRAMES=8192;
    // One hour of 24-kHz mono PCM is 172.8 MB raw (~230.4 MB base64), plus event envelopes.
    // This is a lifetime receive bound, not a larger per-message or parser allowance.
    static final int TALK_MAX_RECEIVED_BYTES=384*1024*1024, TALK_MAX_FRAMES=393216;
    static final long TALK_WRITE_OPERATION_MS=3000L, ASSIST_WRITE_OPERATION_MS=15000L;
    interface TestConnector { Socket connect(URI uri) throws Exception; } // package-private synthetic tests only
    private enum ReceiveBudget {
        ASSIST(ASSIST_MAX_RECEIVED_BYTES,ASSIST_MAX_FRAMES),
        TALK(TALK_MAX_RECEIVED_BYTES,TALK_MAX_FRAMES);
        final int bytes,frames;
        ReceiveBudget(int bytes,int frames){this.bytes=bytes;this.frames=frames;}
    }
    private final TestConnector testConnector;
    private final ReceiveBudget receiveBudget;
    private final long writeOperationMs;
    private final GatewayTransportTrace diagnostics;
    private final ReentrantLock writeLock=new ReentrantLock(true);
    private final ArrayBlockingQueue<byte[]> pendingPongs=new ArrayBlockingQueue<byte[]>(4);
    private final ScheduledThreadPoolExecutor writeWatchdog=new ScheduledThreadPoolExecutor(1,new ThreadFactory(){
        public Thread newThread(Runnable task){Thread t=new Thread(task,"gateway-raw-write-watchdog");t.setDaemon(true);return t;}
    });
    private volatile Socket rawSocket;
    private volatile Socket socket;
    private volatile boolean closed;
    private volatile Thread pongSender;
    private InputStream in;
    private OutputStream out;
    private final SecureRandom random=new SecureRandom();
    private long deadline;
    private int receivedBytes, frames;
    GatewayWebSocket(){this(null,ReceiveBudget.ASSIST,ASSIST_WRITE_OPERATION_MS);}
    GatewayWebSocket(TestConnector testConnector){this(testConnector,ReceiveBudget.ASSIST,ASSIST_WRITE_OPERATION_MS);}
    static GatewayWebSocket forTalk(){return new GatewayWebSocket(null,ReceiveBudget.TALK,TALK_WRITE_OPERATION_MS);}
    static GatewayWebSocket forTalk(TestConnector testConnector){return new GatewayWebSocket(testConnector,ReceiveBudget.TALK,TALK_WRITE_OPERATION_MS);}
    static GatewayWebSocket forTalk(TestConnector testConnector,long writeOperationMs){
        if(writeOperationMs<=0||writeOperationMs>TALK_WRITE_OPERATION_MS)throw new IllegalArgumentException("write deadline");
        return new GatewayWebSocket(testConnector,ReceiveBudget.TALK,writeOperationMs);
    }
    private GatewayWebSocket(TestConnector testConnector,ReceiveBudget receiveBudget,long writeOperationMs){
        this.testConnector=testConnector;this.receiveBudget=receiveBudget;this.writeOperationMs=writeOperationMs;
        diagnostics=receiveBudget==ReceiveBudget.TALK?new GatewayTransportTrace():null;
        writeWatchdog.setRemoveOnCancelPolicy(true);
    }
    void trace(GatewayTransportTrace.Event event,Throwable failure,int code){
        try{if(diagnostics!=null)diagnostics.record(event,failure,code);}catch(Throwable unavailable){}
    }
    private void traceRead(int opcode,int bytes){try{if(diagnostics!=null)diagnostics.readFrame(opcode,bytes);}catch(Throwable unavailable){}}
    private void traceWriteStart(int opcode){try{if(diagnostics!=null)diagnostics.writeStart(opcode);}catch(Throwable unavailable){}}
    private void traceWriteDone(int opcode,int bytes){try{if(diagnostics!=null)diagnostics.writeDone(opcode,bytes);}catch(Throwable unavailable){}}
    private void traceWriteEnd(){try{if(diagnostics!=null)diagnostics.writeEnd();}catch(Throwable unavailable){}}
    void emitTrace(GatewayTransportTrace.Sink sink){try{if(diagnostics!=null)diagnostics.emit(sink);}catch(Throwable unavailable){}}
    java.util.List<String> traceSnapshot(){return diagnostics==null?java.util.Collections.<String>emptyList():diagnostics.snapshot();}
    static URI route(String value) throws GatewayException {
        try{
            URI u=new URI(value);
            if(!"wss".equals(u.getScheme())||u.getHost()==null||u.getHost().isEmpty()||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null
                ||u.getPort()==0||u.getPort()>65535||u.getHost().contains("%")||value.length()>1024)throw new Exception();
            String path=u.getRawPath();if(path==null||path.isEmpty())path="/";
            if(!path.matches("/[A-Za-z0-9/._~-]*"))throw new Exception();
            return new URI("wss",null,u.getHost().toLowerCase(Locale.ROOT),u.getPort()==443?-1:u.getPort(),path,null,null);
        }catch(Exception e){throw new GatewayException("ROUTE_INVALID");}
    }
    static long now(){return System.nanoTime()/1000000L;}
    private void check()throws GatewayException{if(closed)throw new GatewayException("CANCELLED");if(now()>=deadline)throw new GatewayException("TIMEOUT");}
    private void attach(Socket s)throws IOException{
        if(rawSocket==null)rawSocket=s;
        socket=s;
        if(closed){abortRaw();throw new GatewayException("CANCELLED");}
    }
    /** Never close Conscrypt's wrapper on a cancellation path: close() can itself
     * write a TLS close_notify and block in sendto behind the stuck sender. */
    private void abortRaw(){
        closed=true;
        Socket raw=rawSocket;if(raw!=null)try{raw.close();}catch(IOException ignored){}
    }
    private long writeDeadline(){return Math.min(deadline,now()+writeOperationMs);}
    private ScheduledFuture<?> armWriteWatchdog(long operationDeadline)throws GatewayException{
        long remaining=operationDeadline-now();
        if(remaining<=0){trace(GatewayTransportTrace.Event.WRITE_DEADLINE,null,-1);abortRaw();throw new GatewayException("WRITE_DEADLINE");}
        try{return writeWatchdog.schedule(new Runnable(){public void run(){trace(GatewayTransportTrace.Event.WRITE_WATCHDOG,null,-1);abortRaw();}},remaining,TimeUnit.MILLISECONDS);}
        catch(RejectedExecutionException closedExecutor){throw new GatewayException("CANCELLED");}
    }
    private void checkWriteDeadline(long operationDeadline)throws GatewayException{
        if(now()>=operationDeadline){trace(GatewayTransportTrace.Event.WRITE_DEADLINE,null,-1);throw new GatewayException("WRITE_DEADLINE");}
        check();
    }
    void open(URI uri,long deadline)throws Exception{
        try{openTransport(uri,deadline);trace(GatewayTransportTrace.Event.OPENED,null,-1);}
        catch(Exception failure){trace(GatewayTransportTrace.Event.OPEN_FAILURE,failure,-1);throw failure;}
    }
    private void openTransport(URI uri,long deadline)throws Exception{
        this.deadline=deadline;check();
        if(testConnector!=null){Socket test=testConnector.connect(uri);attach(test);if(test.getInetAddress()==null||!test.getInetAddress().isLoopbackAddress())throw new GatewayException("TEST_TRANSPORT_NOT_LOOPBACK");}
        else{
            Socket raw=new Socket();attach(raw);raw.connect(new InetSocketAddress(uri.getHost(),uri.getPort()<0?443:uri.getPort()),remaining(15000));check();
            SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,uri.getHost(),uri.getPort()<0?443:uri.getPort(),true);attach(tls);
            SSLParameters p=tls.getSSLParameters();boolean endpointVerifiedByHandshake=enableEndpointVerification(p);tls.setSSLParameters(p);
            List<String> versions=new ArrayList<String>();for(String v:tls.getSupportedProtocols())if(v.equals("TLSv1.3")||v.equals("TLSv1.2"))versions.add(v);
            if(versions.isEmpty())throw new GatewayException("TLS_UNAVAILABLE");tls.setEnabledProtocols(versions.toArray(new String[versions.size()]));
            tls.setSoTimeout(remaining(15000));tls.startHandshake();check();
            // API23 lacks SSLParameters endpoint identification; platform verifier remains mandatory.
            if(!endpointVerifiedByHandshake)verifyHostname(uri.getHost(),tls.getSession(),HttpsURLConnection.getDefaultHostnameVerifier());
        }
        in=socket.getInputStream();out=socket.getOutputStream();
        byte[] nonce=new byte[16];random.nextBytes(nonce);String key=GatewayCredentials.b64(nonce);
        String host=uri.getHost();if(host.indexOf(':')>=0&&!host.startsWith("["))host="["+host+"]";if(uri.getPort()!=-1)host+=":"+uri.getPort();
        String upgrade="GET "+uri.getRawPath()+" HTTP/1.1\r\nHost: "+host+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\n\r\n";
        long upgradeDeadline=writeDeadline();ScheduledFuture<?> upgradeWatchdog=armWriteWatchdog(upgradeDeadline);
        try{out.write(upgrade.getBytes("US-ASCII"));out.flush();checkWriteDeadline(upgradeDeadline);}
        catch(IOException failure){trace(GatewayTransportTrace.Event.OPEN_FAILURE,failure,-1);if(now()>=upgradeDeadline)throw new GatewayException("WRITE_DEADLINE");throw failure;}
        finally{upgradeWatchdog.cancel(false);}
        ByteArrayOutputStream headers=new ByteArrayOutputStream();int state=0;
        while(state<4){int b=readByte();if(headers.size()>=MAX_HEADER)throw new GatewayException("UPGRADE_INVALID");headers.write(b);state=(state==0&&b==13)?1:(state==1&&b==10)?2:(state==2&&b==13)?3:(state==3&&b==10)?4:(b==13?1:0);}
        String[] lines=new String(headers.toByteArray(),"US-ASCII").split("\r\n");
        if(lines.length>=1&&lines[0].matches("HTTP/1\\.[01] 401(?: .*)?"))throw new GatewayException("AUTH_REQUIRED");
        if(lines.length>=1&&lines[0].matches("HTTP/1\\.[01] 403(?: .*)?"))throw new GatewayException("AUTH_FORBIDDEN");
        if(lines.length<2||!lines[0].matches("HTTP/1\\.1 101(?: .*)?"))throw new GatewayException("UPGRADE_REJECTED");
        Map<String,String> fields=new HashMap<String,String>();
        for(int i=1;i<lines.length;i++){int n=lines[i].indexOf(':');if(n<1||Character.isWhitespace(lines[i].charAt(0)))throw new GatewayException("UPGRADE_INVALID");String name=lines[i].substring(0,n).toLowerCase(Locale.ROOT);if(fields.containsKey(name))throw new GatewayException("UPGRADE_INVALID");fields.put(name,lines[i].substring(n+1).trim());}
        String accept=GatewayCredentials.b64(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("US-ASCII")));
        if(!"websocket".equalsIgnoreCase(fields.get("upgrade"))||!hasToken(fields.get("connection"),"upgrade")||!accept.equals(fields.get("sec-websocket-accept"))
            ||fields.containsKey("sec-websocket-extensions")||fields.containsKey("sec-websocket-protocol"))throw new GatewayException("UPGRADE_INVALID");check();
    }
    static boolean enableEndpointVerification(SSLParameters parameters) throws SSLException {
        try{SSLParameters.class.getMethod("setEndpointIdentificationAlgorithm",String.class).invoke(parameters,"HTTPS");return true;}
        catch(NoSuchMethodException api23){return false;}
        catch(Exception failed){throw new SSLException("TLS_IDENTITY_CONFIGURATION_FAILED");}
    }
    static void verifyHostname(String host,SSLSession session,HostnameVerifier verifier) throws SSLException {
        if(!verifier.verify(host,session))throw new SSLHandshakeException("TLS_HOSTNAME_REJECTED");
    }
    private static boolean hasToken(String s,String token){if(s==null)return false;for(String p:s.split(","))if(p.trim().equalsIgnoreCase(token))return true;return false;}
    private int remaining(int cap)throws GatewayException{check();return(int)Math.max(1,Math.min(cap,deadline-now()));}
    private int readByte()throws IOException{
        check();socket.setSoTimeout(remaining(30000));int b=observedRead();
        if(b<0)throw new GatewayException("CONNECTION_CLOSED");return b;
    }
    private int observedRead()throws IOException{
        try{int b=in.read();if(b<0)trace(GatewayTransportTrace.Event.READ_EOF,null,-1);return b;}
        catch(IOException failure){trace(GatewayTransportTrace.Event.READ_FAILURE,failure,-1);throw failure;}
    }
    private byte[] readBytes(int n)throws IOException{
        byte[] b=new byte[n];int at=0;
        while(at<n){check();socket.setSoTimeout(remaining(30000));int got;
            try{got=in.read(b,at,n-at);}catch(IOException failure){trace(GatewayTransportTrace.Event.READ_FAILURE,failure,-1);throw failure;}
            if(got<0){trace(GatewayTransportTrace.Event.READ_EOF,null,-1);throw new GatewayException("CONNECTION_CLOSED");}at+=got;
        }return b;
    }
    void send(byte[] bytes)throws Exception{check();if(bytes.length>1500000)throw new GatewayException("PAYLOAD_TOO_LARGE");frame(1,bytes);}
    /** Best-effort control admission: a blocked microphone write must not make the
     * provider reader wait or let a queued mark close the transport while it drains. */
    boolean sendIfWritable(byte[] bytes,long waitMs)throws Exception{
        if(waitMs<=0L||waitMs>250L)throw new IllegalArgumentException("control wait");
        check();if(bytes.length>1500000)throw new GatewayException("PAYLOAD_TOO_LARGE");
        boolean acquired;
        try{acquired=writeLock.tryLock(waitMs,TimeUnit.MILLISECONDS);}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new GatewayException("CANCELLED");}
        if(!acquired)return false;
        try{frame(1,bytes);return true;}finally{writeLock.unlock();}
    }
    interface WriteGuard { void check() throws GatewayException; }
    /** Revalidate request authority after acquiring the shared writer, before any bytes. */
    boolean sendGuarded(byte[] bytes,long expires,WriteGuard guard)throws Exception{
        if(bytes.length>1500000)throw new GatewayException("PAYLOAD_TOO_LARGE");
        if(!writeLock.tryLock(100L,TimeUnit.MILLISECONDS))return false;
        try{guard.check();frame(1,bytes,expires,guard);return true;}finally{writeLock.unlock();}
    }
    private void frame(int opcode,byte[] b)throws IOException{frame(opcode,b,Long.MAX_VALUE,null);}
    private void frame(int opcode,byte[] b,long expires,WriteGuard guard)throws IOException{
        long operationDeadline=Math.min(writeDeadline(),expires);boolean locked;
        try{locked=writeLock.tryLock(Math.max(1L,operationDeadline-now()),TimeUnit.MILLISECONDS);}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new GatewayException("CANCELLED");}
        if(!locked){trace(GatewayTransportTrace.Event.WRITE_LOCK_DEADLINE,null,-1);abortRaw();throw new GatewayException("WRITE_DEADLINE");}
        ScheduledFuture<?> watchdog=null;
        traceWriteStart(opcode);
        try{
            if(guard!=null)guard.check();
            checkWriteDeadline(operationDeadline);watchdog=armWriteWatchdog(operationDeadline);
            byte[] mask=new byte[4];random.nextBytes(mask);out.write(0x80|opcode);
            if(b.length<126)out.write(0x80|b.length);else if(b.length<=65535){out.write(0x80|126);out.write(b.length>>>8);out.write(b.length);}else{out.write(0x80|127);for(int i=7;i>=0;i--)out.write((int)(((long)b.length>>>(i*8))&255));}
            out.write(mask);byte[] chunk=new byte[Math.min(8192,b.length)];
            try{for(int at=0;at<b.length;){checkWriteDeadline(operationDeadline);int n=Math.min(chunk.length,b.length-at);for(int j=0;j<n;j++)chunk[j]=(byte)(b[at+j]^mask[(at+j)%4]);out.write(chunk,0,n);at+=n;}out.flush();checkWriteDeadline(operationDeadline);traceWriteDone(opcode,b.length);}
            finally{Arrays.fill(chunk,(byte)0);}
        }catch(IOException failure){trace(GatewayTransportTrace.Event.WRITE_FAILURE,failure,-1);if(now()>=operationDeadline)throw new GatewayException("WRITE_DEADLINE");throw failure;}
        finally{if(watchdog!=null)watchdog.cancel(false);traceWriteEnd();writeLock.unlock();}
    }
    /** A peer ping must not pin the only provider reader behind an in-flight mic write.
     * The bounded worker serializes pongs with data writes; a failed pong closes the
     * raw transport so the reader observes an explicit connection failure. */
    private void queuePong(byte[] payload)throws GatewayException{
        check();
        if(!pendingPongs.offer(payload.clone())){trace(GatewayTransportTrace.Event.PONG_QUEUE_FULL,null,-1);abortRaw();throw new GatewayException("CONTROL_QUEUE_FULL");}
        synchronized(this){
            if(pongSender==null&&!closed){
                pongSender=new Thread(new Runnable(){public void run(){sendPongs();}},"gateway-pong-sender");
                pongSender.setDaemon(true);pongSender.start();
            }
        }
    }
    private void sendPongs(){
        try{
            while(!closed){
                byte[] payload=pendingPongs.poll(100,TimeUnit.MILLISECONDS);
                if(payload==null)continue;
                try{frame(10,payload);}finally{Arrays.fill(payload,(byte)0);}
            }
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();}
        catch(IOException failed){trace(GatewayTransportTrace.Event.PONG_FAILURE,failed,-1);abortRaw();}
        finally{clearPongs();}
    }
    private void clearPongs(){byte[] payload;while((payload=pendingPongs.poll())!=null)Arrays.fill(payload,(byte)0);}
    byte[] receive()throws Exception{return receive(false);}
    /** A long-running approval may be silent. Only the first byte of a new message
     * may use the remaining operation deadline; every subsequent frame byte keeps
     * the short read timeout so a partial frame cannot silently stall or be retried.
     * Closing the raw socket still interrupts this read on cancellation.
     */
    byte[] receive(boolean waitForFirstByte)throws Exception{
        ByteArrayOutputStream message=new ByteArrayOutputStream();boolean fragmented=false;
        while(true){
            if(++frames>receiveBudget.frames)throw new GatewayException("RESPONSE_TOO_LARGE");
            int a;
            if(waitForFirstByte&&message.size()==0&&!fragmented){check();socket.setSoTimeout(remaining(Integer.MAX_VALUE));a=observedRead();if(a<0)throw new GatewayException("CONNECTION_CLOSED");}
            else a=readByte();
            int b=readByte();boolean fin=(a&128)!=0;int op=a&15;
            if((a&112)!=0||(b&128)!=0)throw new GatewayException("WEBSOCKET_INVALID");
            long length=b&127;if(length==126){length=(readByte()<<8)|readByte();if(length<126)throw new GatewayException("WEBSOCKET_INVALID");}
            else if(length==127){length=0;for(int i=0;i<8;i++){int v=readByte();if(i==0&&v!=0)throw new GatewayException("RESPONSE_TOO_LARGE");length=(length<<8)|v;}if(length<=65535)throw new GatewayException("WEBSOCKET_INVALID");}
            boolean control=op>=8;if(control&&(!fin||length>125))throw new GatewayException("WEBSOCKET_INVALID");
            if(length>MAX_MESSAGE||length+message.size()>MAX_MESSAGE||((long)receivedBytes)+length>receiveBudget.bytes)throw new GatewayException("RESPONSE_TOO_LARGE");
            receivedBytes+=(int)length;byte[] payload=readBytes((int)length);
            traceRead(op,payload.length);
            if(op==8){if(payload.length==1)throw new GatewayException("WEBSOCKET_INVALID");if(payload.length>=2){int code=((payload[0]&255)<<8)|(payload[1]&255);if(code<1000||code>=5000||code==1004||code==1005||code==1006||code==1015||(code>=1016&&code<3000))throw new GatewayException("WEBSOCKET_INVALID");}if(payload.length>2)GatewayJson.utf8(Arrays.copyOfRange(payload,2,payload.length));trace(GatewayTransportTrace.Event.PEER_CLOSE,null,payload.length>=2?((payload[0]&255)<<8)|(payload[1]&255):-1);frame(8,payload);throw new GatewayException("CONNECTION_CLOSED");}
            if(op==9){queuePong(payload);continue;}if(op==10)continue;
            if(op==1){if(fragmented)throw new GatewayException("WEBSOCKET_INVALID");fragmented=!fin;}
            else if(op==0){if(!fragmented)throw new GatewayException("WEBSOCKET_INVALID");}else throw new GatewayException("WEBSOCKET_INVALID");
            message.write(payload);if(fin){byte[] result=message.toByteArray();GatewayJson.utf8(result);return result;}
        }
    }
    public void close(){trace(GatewayTransportTrace.Event.LOCAL_CLOSE,null,-1);abortRaw();Thread sender=pongSender;if(sender!=null)sender.interrupt();clearPongs();writeWatchdog.shutdownNow();}
}
