package lab.franziska.pixelbridge;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.*;

/** Talk-only, bounded observational journal. No I/O, payloads or Throwable retention on socket paths. */
final class GatewayTransportTrace {
    enum Event {
        OPENED, OPEN_FAILURE, READ_FAILURE, READ_EOF, PEER_CLOSE, WRITE_FAILURE,
        WRITE_LOCK_DEADLINE, WRITE_WATCHDOG, WRITE_DEADLINE, PONG_FAILURE,
        PONG_QUEUE_FULL, LOCAL_CLOSE, INPUT_FAILURE, MARK_FAILURE, TALK_FAILURE,
        TALK_CANCEL, CLOSE_GRACE_EXPIRED, RELAY_CLOSE
    }
    interface Sink { void line(String line); }
    private static final AtomicLong NEXT=new AtomicLong();
    private final long id=NEXT.incrementAndGet(),started=now();
    private final EnumSet<Event> seen=EnumSet.noneOf(Event.class);
    private final ArrayList<String> events=new ArrayList<String>();
    private long rxFrames,rxBytes,txFrames,txBytes,pingRx,pongTx;
    private long lastRead=-1,lastWrite=-1,lastPing=-1,lastPong=-1,writeStarted=-1;
    private int writeOpcode=-1;
    private boolean emitted;
    private Event firstTerminal;
    static long now(){return System.nanoTime()/1000000L;}
    private static long age(long at,long now){return at<0?-1:Math.max(0,now-at);}

    synchronized void readFrame(int opcode,int bytes){
        if(emitted)return;
        rxFrames++;rxBytes+=bytes;lastRead=now();
        if(opcode==9){pingRx++;lastPing=lastRead;}
    }
    synchronized void writeStart(int opcode){
        if(emitted)return;
        writeOpcode=opcode;writeStarted=now();
    }
    synchronized void writeDone(int opcode,int bytes){
        if(emitted)return;
        txFrames++;txBytes+=bytes;lastWrite=now();
        if(opcode==10){pongTx++;lastPong=lastWrite;}
    }
    synchronized void writeEnd(){if(!emitted){writeOpcode=-1;writeStarted=-1;}}
    private String counters(long at){
        return " RX_FRAMES="+rxFrames+" RX_BYTES="+rxBytes+" TX_FRAMES="+txFrames+" TX_BYTES="+txBytes
            +" PING_RX="+pingRx+" PONG_TX="+pongTx+" RX_AGE_MS="+age(lastRead,at)
            +" TX_AGE_MS="+age(lastWrite,at)+" PING_AGE_MS="+age(lastPing,at)
            +" PONG_AGE_MS="+age(lastPong,at)+" WRITE_OP="+writeOpcode+" WRITE_AGE_MS="+age(writeStarted,at);
    }
    /** Store once per fixed boundary. Failure reporting cannot replace the original exception. */
    synchronized void record(Event event,Throwable failure,int code){
        if(emitted||seen.contains(event))return;
        long at=now();
        String category=kind(failure),signal=signal(failure),root=category;
        Throwable cursor=failure;
        for(int i=0;cursor!=null&&i<4;i++){
            root=kind(cursor);
            if("OTHER".equals(signal))signal=signal(cursor);
            Throwable next=cursor.getCause();if(next==cursor)break;cursor=next;
        }
        boolean first=firstTerminal==null&&event!=Event.OPENED;
        if(first)firstTerminal=event;
        // Only a validated close frame can populate a WebSocket code. Never infer 1006 from EOF.
        int closeCode=event==Event.PEER_CLOSE&&code>=1000&&code<5000?code:-1;
        String line="TRACE="+id+" SEQ="+(events.size()+1)+" EVENT="+event.name()+" FIRST="+(first?1:0)
            +" AT_MS="+at+" ELAPSED_MS="+Math.max(0,at-started)+" ERROR="+category+" ROOT="+root
            +" SIGNAL="+signal+" WS_CODE="+closeCode+counters(at);
        events.add(line);seen.add(event);
    }
    synchronized List<String> snapshot(){return new ArrayList<String>(events);}
    void emit(Sink sink){
        List<String> lines;
        try{
            synchronized(this){if(emitted)return;emitted=true;lines=new ArrayList<String>(events);}
        }catch(Throwable unavailable){return;}
        for(String line:lines)try{sink.line(line);}catch(Throwable unavailable){/* observational only */}
    }
    private static String kind(Throwable failure){
        if(failure==null)return "NONE";
        if(failure instanceof GatewayException){
            String code=((GatewayException)failure).code;
            for(String fixed:new String[]{"CANCELLED","TIMEOUT","WRITE_DEADLINE","CONNECTION_CLOSED",
                    "WEBSOCKET_INVALID","RESPONSE_TOO_LARGE","CONTROL_QUEUE_FULL","TALK_TIMEOUT",
                    "TALK_CLOSE_GRACE_EXPIRED","TALK_PROVIDER_ERROR","CREDENTIAL_REVOKED"})
                if(fixed.equals(code))return fixed;
            return "GATEWAY_OTHER";
        }
        if(failure instanceof SocketTimeoutException)return "SOCKET_TIMEOUT";
        if(failure instanceof SSLHandshakeException)return "TLS_HANDSHAKE";
        if(failure instanceof SSLException)return "TLS";
        if(failure instanceof EOFException)return "EOF";
        if(failure instanceof UnknownHostException)return "DNS";
        if(failure instanceof ConnectException)return "CONNECT";
        if(failure instanceof SocketException)return "SOCKET";
        if(failure instanceof InterruptedIOException)return "INTERRUPTED_IO";
        if(failure instanceof IOException)return "IO";
        if(failure instanceof InterruptedException)return "INTERRUPTED";
        return "OTHER";
    }
    private static String signal(Throwable failure){
        if(failure==null)return "NONE";
        if(failure instanceof SocketTimeoutException)return "TIMEOUT";
        if(failure instanceof EOFException)return "EOF";
        // Match known OS phrases; never keep or emit arbitrary messages/endpoints/credentials.
        String message=failure.getMessage();if(message==null)return "OTHER";
        String lower=message.toLowerCase(Locale.ROOT);
        if(lower.contains("broken pipe"))return "BROKEN_PIPE";
        if(lower.contains("connection reset"))return "RESET";
        if(lower.contains("connection abort"))return "ABORTED";
        if(lower.contains("socket closed")||lower.contains("socket is closed"))return "CLOSED";
        if(lower.contains("timed out"))return "TIMEOUT";
        return "OTHER";
    }
}
