package lab.franziska.pixelbridge;

import java.net.URI;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import android.util.Log;
import org.bouncycastle.util.encoders.Base64;
import lab.franziska.protocol.TalkWire;

/** One foreground, non-reconnecting Gemini relay. The link owner must cancel on Stop/loss. */
final class GatewayTalkClient {
    static final long CLOSE_GRACE_MS=15000L;
    /** Fixed, content-free location of a rejected inbound Gateway frame. */
    private enum InboundStage {
        FRAME_READ, FRAME_JSON, FRAME_ROOT, CHALLENGE_PAYLOAD, CHALLENGE_NONCE,
        CHALLENGE_TIMESTAMP, HELLO_PROTOCOL, HELLO_AUTH, HELLO_FEATURES,
        HELLO_DEVICE_TOKEN, RESPONSE_PAYLOAD, RESPONSE_RUN_ID, CREATE_SESSION_ID,
        CHAT_PAYLOAD, CHAT_STATE, CHAT_RUN_ID, CHAT_RESULT_TEXT,
        TALK_PAYLOAD, TALK_TYPE, TALK_AUDIO_BASE64, TALK_MARK_NAME,
        TALK_TRANSCRIPT_ROLE, TALK_TRANSCRIPT_TEXT, TALK_TOOL_CALL_ID, TALK_TOOL_NAME
    }
    interface Listener {
        void ready();
        void audio(byte[] pcm);
        void clear();
        void text(String role,String text);
        void camera();
        void ended(String code);
    }
    /** Optional metadata path; old Listener implementations remain source/binary compatible. */
    interface TranscriptListener extends Listener {
        void text(String role,String text,String utteranceId,boolean isFinal);
    }
    private final GatewayCredentials credentials;
    private final GatewayWebSocket socket;
    private final ArrayBlockingQueue<byte[]> input=new ArrayBlockingQueue<byte[]>(8);
    private final ArrayBlockingQueue<String> pendingMarks=new ArrayBlockingQueue<String>(32);
    private volatile boolean cancelled;
    private volatile boolean stopInput,awaitingCloseAfterAppend;
    private volatile boolean closeGraceExpired;
    private volatile boolean terminalCloseSeen;
    private Thread closeGraceWatchdog;
    private final long closeGraceMs;
    private final java.util.concurrent.atomic.AtomicReference<String> cancelCause=new java.util.concurrent.atomic.AtomicReference<String>();
    private volatile String sessionId;
    private volatile boolean photoSupported,relayReady;
    private int photoMaxPayload=1500000,photoMaxImage=262144;
    private volatile Photo pendingPhoto;
    static final long PHOTO_MS=90000L;
    boolean photoReady(){return relayReady&&photoSupported&&!cancelled&&!stopInput&&!terminalCloseSeen;}
    /** Reservation is metadata-only; safe under the service lock. execute() requires a worker. */
    synchronized Photo reservePhoto(String request,long expires)throws GatewayException{
        if(!photoReady()||pendingPhoto!=null)throw new GatewayException("PHOTO_BUSY_OR_UNAVAILABLE");
        try{lab.franziska.protocol.AssistRequestIds.counter(request);}catch(Exception bad){throw new GatewayException("MEDIA_INVALID");}
        long now=GatewayWebSocket.now();
        if(expires<=now||expires-now>PHOTO_MS)throw new GatewayException("PHOTO_TIMEOUT");
        Photo photo=new Photo(request,sessionId,Math.min(deadline,expires));pendingPhoto=photo;return photo;
    }
    private void revokePhoto(){Photo p=pendingPhoto;if(p!=null)p.cancel();}
    static final class PhotoResult {
        final String text,imageSha256;
        PhotoResult(String text,String hash){this.text=text;this.imageSha256=hash;}
    }
    /** One request, one existing socket/lease. Reader only queues bounded correlated frames;
     * this worker performs writes and waits without blocking audio or service/UI monitors. */
    final class Photo implements GatewayWebSocket.WriteGuard {
        final String request,relay,prefix;final long expires;
        private final ArrayBlockingQueue<Map<?,?>> replies=new ArrayBlockingQueue<Map<?,?>>(32);
        private volatile boolean revoked,finished;
        private boolean used;
        Photo(String id,String relay,long expires){request=id;this.relay=relay;this.expires=expires;prefix="photo-"+id+"-";}
        public void check()throws GatewayException{
            GatewayTalkClient.this.check();
            if(revoked||finished||pendingPhoto!=this||!photoReady()||!relay.equals(sessionId))throw new GatewayException("PHOTO_CANCELLED");
            if(GatewayWebSocket.now()>=expires)throw new GatewayException("PHOTO_TIMEOUT");
        }
        void cancel(){revoked=true;replies.clear();}
        boolean accept(Map<?,?> frame,int bytes){
            boolean response="res".equals(frame.get("type"))&&frame.get("id") instanceof String&&((String)frame.get("id")).startsWith(prefix);
            Object raw=frame.get("payload");Map<?,?> p=raw instanceof Map?(Map<?,?>)raw:null;
            boolean chat="event".equals(frame.get("type"))&&"chat".equals(frame.get("event"))&&p!=null
                &&request.equals(p.get("runId"))&&talkSessionKey.equals(p.get("sessionKey"));
            if(!response&&!chat)return false;
            // Deltas are not needed. Bound terminal/response memory independently of Talk PCM.
            if(chat&&!Arrays.asList("final","error","aborted").contains(p.get("state")))return true;
            if(!revoked&&!finished&&(bytes>65536||!replies.offer(frame)))cancel();
            return true;
        }
        private Map<?,?> take()throws Exception{
            for(;;){check();Map<?,?> f=replies.poll(Math.min(100L,Math.max(1L,expires-GatewayWebSocket.now())),TimeUnit.MILLISECONDS);if(f!=null){check();return f;}}
        }
        private void write(String stage,String method,Object params)throws Exception{
            check();byte[] frame=GatewayJson.encode(GatewayJson.obj("type","req","id",prefix+stage,"method",method,"params",params));
            try{if(frame.length>photoMaxPayload)throw new GatewayException("PAYLOAD_BUDGET_EXCEEDED");
                for(;;){check();if(socket.sendGuarded(frame,expires,this))break;}check();
            }finally{Arrays.fill(frame,(byte)0);}
        }
        private Map<?,?> ack(String stage)throws Exception{
            for(int i=0;i<32;i++){Map<?,?> f=take();if(!"res".equals(f.get("type"))||!(prefix+stage).equals(f.get("id")))continue;
                if(!Boolean.TRUE.equals(f.get("ok")))throw new GatewayException("PHOTO_REQUEST_REJECTED");return GatewayJson.map(f.get("payload"));}
            throw new GatewayException("PHOTO_RESPONSE_LIMIT");
        }
        PhotoResult execute(byte[] image)throws Exception{
            synchronized(this){if(used)throw new GatewayException("PHOTO_ALREADY_USED");used=true;}
            try{
                check();if(image==null||image.length==0||image.length>photoMaxImage)throw new GatewayException("MEDIA_INVALID");
                try{lab.franziska.protocol.AssistWire.validateJpeg(image);}catch(Exception invalid){throw new GatewayException("MEDIA_INVALID");}
                int length=image.length;String hash=lab.franziska.protocol.AssistWire.sha256(image);
                write("before","chat.history",GatewayJson.obj("sessionKey",talkSessionKey,"agentId","main","limit",1,"maxBytes",65536));
                Map<?,?> history=ack("before");
                if(!talkSessionKey.equals(history.get("sessionKey")))throw new GatewayException("PHOTO_SESSION_MISMATCH");
                String backing=GatewayJson.string(history.get("sessionId"),128);if(backing.isEmpty())throw new GatewayException("PHOTO_SESSION_MISMATCH");
                if(history.get("inFlightRun") instanceof Map||(history.get("sessionInfo") instanceof Map&&Boolean.TRUE.equals(((Map<?,?>)history.get("sessionInfo")).get("hasActiveRun"))))throw new GatewayException("SESSION_BUSY");
                check();credentials.burnRequest(request,credentialEpoch);check();
                Map<String,Object> params=GatewayJson.obj("sessionKey",talkSessionKey,"sessionId",backing,"agentId","main",
                    "message","Рассмотри именно приложенный снимок. Ответь одной короткой фразой по-русски с конкретной видимой деталью, подтверждающей, что ты рассмотрела фото. Если содержимое неразличимо или изображение недоступно, прямо скажи об этом. Не утверждай наугад, что видишь или сохранила снимок. Без приветствия, Markdown и выдуманных действий. Следующий вопрос может относиться к этому фото.",
                    "deliver",false,"timeoutMs",Math.max(1L,expires-GatewayWebSocket.now()-1000L),"idempotencyKey",request,
                    "attachments",Collections.singletonList(GatewayJson.obj("type","image","mimeType","image/jpeg","fileName","scene.jpg","content",GatewayCredentials.b64(image))));
                try{write("send","chat.send",params);}finally{params.clear();Arrays.fill(image,(byte)0);}
                boolean sent=false;String answer=null;long last=-1;
                while(!sent||answer==null){Map<?,?> f=take();
                    if("res".equals(f.get("type"))&&(prefix+"send").equals(f.get("id"))){
                        if(!Boolean.TRUE.equals(f.get("ok"))||!request.equals(GatewayJson.map(f.get("payload")).get("runId")))throw new GatewayException("PHOTO_RUN_MISMATCH");sent=true;
                    }else if("event".equals(f.get("type"))){Map<?,?> p=GatewayJson.map(f.get("payload"));
                        if(p.get("agentId")!=null&&!"main".equals(p.get("agentId")))throw new GatewayException("PHOTO_RUN_MISMATCH");
                        long seq=GatewayJson.integer(p.get("seq"),0,Long.MAX_VALUE);if(seq<=last)continue;last=seq;
                        if(!"final".equals(p.get("state"))||Boolean.TRUE.equals(p.get("yielded")))throw new GatewayException("PHOTO_AGENT_FAILED");
                        answer=GatewayAssistClient.finalText(p.get("message"));if(answer==null||answer.trim().isEmpty())throw new GatewayException("ANSWER_UNAVAILABLE");
                        answer=answer.trim();
                    }
                }
                write("receipt","chat.history",GatewayJson.obj("sessionKey",talkSessionKey,"agentId","main","limit",32,"maxBytes",65536,"maxChars",4096));
                GatewayAssistClient.verifyPhotoReceipt(ack("receipt"),talkSessionKey,backing,"main",request,length);check();
                write("say","talk.session.sendText",GatewayJson.obj("sessionId",relay,"text",
                    "Скажи естественно и кратко фактический ответ агента по только что приложенному фото. Не начинай новый разговор, не приветствуй и не заявляй о новых действиях. Передай ответ без выдуманных деталей: "+answer));
                ack("say");check();return new PhotoResult(answer,hash);
            }finally{
                if(image!=null)Arrays.fill(image,(byte)0);finished=true;replies.clear();
                synchronized(GatewayTalkClient.this){if(pendingPhoto==this)pendingPhoto=null;}
            }
        }
        /** Release a receive-only reservation; late execute still fails closed. */
        void abandon(){cancel();synchronized(GatewayTalkClient.this){if(pendingPhoto==this)pendingPhoto=null;}}
    }
    private volatile Thread sender;
    private volatile Thread markSender;
    private volatile TalkOutputBatcher outputBatcher;
    private long deadline,credentialEpoch,pendingToolAt,lastProviderAudioAt;
    private int nextAppend,nextTool,providerAudioEvents;
    private String talkSessionKey,pendingCallId,pendingRequestId,pendingRunId,pendingAgentKey;
    private boolean pendingControl;
    private Map<?,?> earlyCompletion;
    private int earlyCompletionBytes=-1;
    private InboundStage inboundStage=InboundStage.FRAME_READ;
    private int inboundFrameBytes=-1;
    GatewayTalkClient(GatewayCredentials credentials){this(credentials,GatewayWebSocket.forTalk(),CLOSE_GRACE_MS);}
    GatewayTalkClient(GatewayCredentials credentials,GatewayWebSocket socket){this(credentials,socket,CLOSE_GRACE_MS);}
    GatewayTalkClient(GatewayCredentials credentials,GatewayWebSocket socket,long closeGraceMs){
        if(closeGraceMs<=0L||closeGraceMs>CLOSE_GRACE_MS)throw new IllegalArgumentException("close grace");
        this.credentials=credentials;this.socket=socket;this.closeGraceMs=closeGraceMs;
    }

    boolean offer(byte[] pcm){
        if(cancelled||sessionId==null||pcm==null||pcm.length<2||pcm.length>TalkWire.MAX_PCM||(pcm.length&1)!=0)return false;
        // Glass may still deliver mic frames while Gateway finalizes the close. A valid frame
        // must be acknowledged to LinkService but never queued for the closing relay.
        if(stopInput)return true;
        byte[] copy=pcm.clone();
        if(stopInput){Arrays.fill(copy,(byte)0);return !cancelled;}
        if(input.offer(copy)){if(stopInput)drain();return !cancelled;}
        if(stopInput){Arrays.fill(copy,(byte)0);return !cancelled;}
        Arrays.fill(copy,(byte)0);cancel("INPUT_QUEUE_FULL");return false;
    }
    void cancel(){cancel("OWNER_OR_LINK");}
    boolean offerOutputCredit(int releasedBytes){
        TalkOutputBatcher b=outputBatcher;
        return !cancelled&&b!=null&&b.offerCredit(releasedBytes);
    }
    private void cancel(String cause){
        // Publish the first cause before revoking: the reader may already be between
        // frames when an asynchronous output callback fails.
        socket.trace(GatewayTransportTrace.Event.TALK_CANCEL,null,-1);
        boolean first=cancelCause.compareAndSet(null,cause);
        cancelled=true;stopInput=true;relayReady=false;revokePhoto();
        if(first)Log.i("PixelTalkDiag","CANCEL_CAUSE="+cause);
        stopCloseGrace();
        TalkOutputBatcher b=outputBatcher;if(b!=null)b.abort();
        socket.close();Thread t=sender;if(t!=null)t.interrupt();t=markSender;if(t!=null)t.interrupt();
        pendingMarks.clear();drain();
    }
    private void drain(){byte[] b;while((b=input.poll())!=null)Arrays.fill(b,(byte)0);}
    private static boolean closedRelayAppendResponse(Map<?,?> frame){
        Object value=frame.get("error");if(!(value instanceof Map))return false;
        Map<?,?> error=(Map<?,?>)value;
        if(!"UNAVAILABLE".equals(error.get("code")))return false;
        Object valueMessage=error.get("message");if(!(valueMessage instanceof String))return false;
        String message=(String)valueMessage;
        return "Unknown Talk session".equals(message)||"Error: Unknown Talk session".equals(message)
            ||"Unknown realtime relay session".equals(message)||"Error: Unknown realtime relay session".equals(message);
    }
    private synchronized void waitForCloseAfterAppendRejection(){
        if(cancelled||awaitingCloseAfterAppend)return;
        awaitingCloseAfterAppend=true;stopInput=true;relayReady=false;revokePhoto();drain();
        // A sender IOException can win the race against the rejection response. In either
        // order, let the reader consume final audio/transcripts and the authoritative close.
        // A dedicated absolute watchdog bounds even a peer that keeps sending pings.
        closeGraceWatchdog=new Thread(new Runnable(){public void run(){
            try{Thread.sleep(closeGraceMs);}catch(InterruptedException stopped){return;}
            synchronized(GatewayTalkClient.this){
                if(!awaitingCloseAfterAppend||cancelled)return;
                closeGraceExpired=true;
            }
            socket.trace(GatewayTransportTrace.Event.CLOSE_GRACE_EXPIRED,null,-1);
            socket.close();
        }},"pixel-talk-close-grace");
        closeGraceWatchdog.setDaemon(true);closeGraceWatchdog.start();
        Log.i("PixelTalkDiag","APPEND_AWAIT_CLOSE");
    }
    private synchronized void stopCloseGrace(){
        awaitingCloseAfterAppend=false;
        Thread watchdog=closeGraceWatchdog;closeGraceWatchdog=null;
        if(watchdog!=null)watchdog.interrupt();
    }
    private static String issueCode(Map<?,?> p){return "realtime_unavailable".equals(p.get("code"))?"REALTIME_UNAVAILABLE":"UNSPECIFIED";}
    private static String issuePhase(Map<?,?> p){
        Object value=p.get("phase");
        return "connect".equals(value)?"CONNECT":"stream".equals(value)?"STREAM":"response".equals(value)?"RESPONSE":"UNSPECIFIED";
    }
    private void check()throws GatewayException{
        if(cancelled)throw new GatewayException("CANCELLED");
        if(closeGraceExpired)throw new GatewayException("TALK_CLOSE_GRACE_EXPIRED");
        if(GatewayWebSocket.now()>=deadline)throw new GatewayException("TALK_TIMEOUT");
        credentials.checkEpoch(credentialEpoch);
    }
    private void send(String id,String method,Object params)throws Exception{
        check();byte[] b=GatewayJson.encode(GatewayJson.obj("type","req","id",id,"method",method,"params",params));
        try{if(b.length>65536)throw new GatewayException("TALK_FRAME_BUDGET");socket.send(b);}finally{Arrays.fill(b,(byte)0);}
    }
    private Map<?,?> next()throws Exception{
        inboundStage=InboundStage.FRAME_READ;inboundFrameBytes=-1;
        check();byte[] b=socket.receive();inboundFrameBytes=b.length;
        try{
            inboundStage=InboundStage.FRAME_JSON;Object value=GatewayJson.parse(b);
            inboundStage=InboundStage.FRAME_ROOT;return GatewayJson.map(value);
        }finally{Arrays.fill(b,(byte)0);}
    }
    private Map<?,?> response(String id)throws Exception{
        for(int i=0;i<64;i++){
            Map<?,?> f=next();if(!"res".equals(f.get("type"))||!id.equals(f.get("id")))continue;
            if(!Boolean.TRUE.equals(f.get("ok")))throw new GatewayException("TALK_REQUEST_REJECTED");
            inboundStage=InboundStage.RESPONSE_PAYLOAD;return GatewayJson.map(f.get("payload"));
        }
        throw new GatewayException("TALK_RESPONSE_LIMIT");
    }
    private void connect(URI route)throws Exception{
        socket.open(route,deadline);
        Map<?,?> challenge=null;
        for(int i=0;i<16;i++){Map<?,?> f=next();if("event".equals(f.get("type"))&&"connect.challenge".equals(f.get("event"))){inboundStage=InboundStage.CHALLENGE_PAYLOAD;challenge=GatewayJson.map(f.get("payload"));break;}}
        if(challenge==null)throw new GatewayException("CHALLENGE_MISSING");
        inboundStage=InboundStage.CHALLENGE_NONCE;
        String nonce=GatewayJson.string(challenge.get("nonce"),512);
        if(nonce.isEmpty()||nonce.indexOf('|')>=0)throw new GatewayException("CHALLENGE_INVALID");
        inboundStage=InboundStage.CHALLENGE_TIMESTAMP;
        long ts=GatewayJson.integer(challenge.get("ts"),0,9007199254740991L);
        String token=credentials.token(route.toString());
        if(token.isEmpty())throw new GatewayException("BOOTSTRAP_REQUIRED");
        String id=credentials.deviceId();
        List<String> requestedScopes=credentials.scopes();
        String signature=credentials.sign(GatewayAssistClient.authPayload(id,ts,token,nonce,requestedScopes));
        Map<String,Object> params=GatewayJson.obj("minProtocol",4,"maxProtocol",4,
            "client",GatewayJson.obj("id","gateway-client","version","glass-talk-1","platform","android","mode","backend","deviceFamily","Pixel"),
            "role","operator","scopes",requestedScopes,"caps",Collections.emptyList(),
            "device",GatewayJson.obj("id",id,"publicKey",credentials.publicKey(),"signature",signature,"signedAt",ts,"nonce",nonce),
            "auth",GatewayJson.obj("token",token));
        try{send("connect","connect",params);}finally{
            Object auth=params.remove("auth");if(auth instanceof Map)((Map<?,?>)auth).clear();token=null;
        }
        Map<?,?> hello=response("connect");
        inboundStage=InboundStage.HELLO_PROTOCOL;
        if(!"hello-ok".equals(hello.get("type"))||GatewayJson.integer(hello.get("protocol"),4,4)!=4)throw new GatewayException("PROTOCOL_VERSION");
        inboundStage=InboundStage.HELLO_AUTH;
        Map<?,?> auth=GatewayJson.map(hello.get("auth"));
        if(!"operator".equals(auth.get("role"))||!requestedScopes.equals(auth.get("scopes")))throw new GatewayException("SCOPE_MISMATCH");
        inboundStage=InboundStage.HELLO_FEATURES;
        Map<?,?> features=GatewayJson.map(hello.get("features"));
        Object methods=features.get("methods"),events=features.get("events");
        if(!(methods instanceof List)||!((List<?>)methods).containsAll(Arrays.asList("talk.session.create","talk.session.appendAudio","talk.session.close"))
                ||!(events instanceof List)||!((List<?>)events).contains("talk.event"))throw new GatewayException("CAPABILITY_MISSING");
        photoSupported=((List<?>)methods).containsAll(Arrays.asList("chat.send","chat.history","talk.session.sendText"))&&((List<?>)events).contains("chat");
        if(photoSupported){
            Map<?,?> policy=GatewayJson.map(hello.get("policy"));
            photoMaxPayload=(int)Math.min(1500000,GatewayJson.integer(policy.get("maxPayload"),1,Integer.MAX_VALUE));
            Object limits=policy.get("attachments");if(limits instanceof Map){Map<?,?> b=(Map<?,?>)limits;
                photoMaxImage=(int)Math.min(262144,Math.min(GatewayJson.integer(b.get("maxImageBytes"),1,Integer.MAX_VALUE),GatewayJson.integer(b.get("maxBytes"),1,Integer.MAX_VALUE)));}
        }
        Object issued=auth.get("deviceToken");
        inboundStage=InboundStage.HELLO_DEVICE_TOKEN;
        if(issued instanceof String)credentials.accept(route.toString(),GatewayJson.string(issued,4096),requestedScopes,credentialEpoch);
    }
    void run(String url,Listener listener){
        String terminal="TALK_ENDED";
        boolean acquired=false;
        final GatewayTalkTranscript transcripts=new GatewayTalkTranscript();
        try{
            credentialEpoch=credentials.acquire();acquired=true;deadline=GatewayWebSocket.now()+TalkWire.MAX_SESSION_MS;
            URI route=GatewayWebSocket.route(url);connect(route);
            talkSessionKey="agent:main:glass:"+credentials.conversation();
            send("create","talk.session.create",GatewayJson.obj("sessionKey",talkSessionKey,"mode","realtime","transport","gateway-relay","brain","agent-consult"));
            Map<?,?> created=response("create");
            Object sid=created.get("relaySessionId");if(!(sid instanceof String))sid=created.get("sessionId");
            inboundStage=InboundStage.CREATE_SESSION_ID;
            sessionId=GatewayJson.string(sid,128);if(sessionId.isEmpty())throw new GatewayException("TALK_SESSION_MISSING");
            Log.i("PixelTalkDiag","GATEWAY_READY");
            final String ownedSession=sessionId;
            sender=new Thread(new Runnable(){public void run(){sendLoop(ownedSession);}},"pixel-talk-input");sender.start();
            // A provider mark may arrive while the microphone sender owns a blocked
            // WebSocket write. Never make the only provider reader wait for that lock:
            // final PCM and the authoritative close must remain readable.
            markSender=new Thread(new Runnable(){public void run(){markLoop(ownedSession);}},"pixel-talk-marks");markSender.start();
            final TalkOutputBatcher batched=new TalkOutputBatcher(listener,deadline,new Runnable(){
                public void run(){cancel("OUTPUT_FLOW_DEADLINE");}
            },new Runnable(){
                public void run(){cancel("OUTPUT_DELIVERY_FAILURE");}
            });outputBatcher=batched;
            relayReady=true;listener.ready();
            while(true){
                Map<?,?> frame=next();
                Photo photo=pendingPhoto;if(photo!=null&&photo.accept(frame,inboundFrameBytes))continue;
                if("res".equals(frame.get("type"))){
                    String id=frame.get("id") instanceof String?(String)frame.get("id"):"";
                    if(id.startsWith("append-")&&!Boolean.TRUE.equals(frame.get("ok"))){
                        if(closedRelayAppendResponse(frame)){waitForCloseAfterAppendRejection();continue;}
                        throw new GatewayException("TALK_APPEND_REJECTED");
                    }
                    if(id.startsWith("tool-result-")&&!Boolean.TRUE.equals(frame.get("ok")))throw new GatewayException("TALK_TOOL_RESULT_REJECTED");
                    if(id.equals(pendingRequestId)){
                        if(!Boolean.TRUE.equals(frame.get("ok"))){submitToolError(pendingCallId,"OpenClaw consult unavailable");logToolWait();clearPending();}
                        else{
                            inboundStage=InboundStage.RESPONSE_PAYLOAD;
                            Map<?,?> ack=GatewayJson.map(frame.get("payload"));
                            if(pendingControl){submitToolResult(pendingCallId,ack);logToolWait();clearPending();continue;}
                            inboundStage=InboundStage.RESPONSE_RUN_ID;
                            pendingRunId=GatewayJson.string(ack.get("runId"),128);
                            pendingAgentKey=ack.get("agentSessionKey") instanceof String?(String)ack.get("agentSessionKey"):talkSessionKey;
                            pendingRequestId=null;
                            if(earlyCompletion!=null){Map<?,?> early=earlyCompletion;earlyCompletion=null;
                                inboundFrameBytes=earlyCompletionBytes;earlyCompletionBytes=-1;handleChat(early);}
                        }
                    }
                    continue;
                }
                if("event".equals(frame.get("type"))&&"chat".equals(frame.get("event"))){
                    inboundStage=InboundStage.CHAT_PAYLOAD;handleChat(GatewayJson.map(frame.get("payload")));continue;
                }
                if(!"event".equals(frame.get("type"))||!"talk.event".equals(frame.get("event")))continue;
                inboundStage=InboundStage.TALK_PAYLOAD;
                Map<?,?> p=GatewayJson.map(frame.get("payload"));
                if(!ownedSession.equals(p.get("relaySessionId"))&&!ownedSession.equals(p.get("sessionId")))continue;
                inboundStage=InboundStage.TALK_TYPE;
                String type=GatewayJson.string(p.get("type"),32);
                if("audio".equals(type)){
                    inboundStage=InboundStage.TALK_AUDIO_BASE64;
                    String encoded=GatewayJson.string(p.get("audioBase64"),65536);
                    byte[] pcm=Base64.decode(encoded);
                    try{
                        if(pcm.length<2||pcm.length>49152||(pcm.length&1)!=0)throw new GatewayException("TALK_OUTPUT_INVALID");
                        long audioAt=GatewayWebSocket.now();
                        long gap=lastProviderAudioAt==0L?0L:audioAt-lastProviderAudioAt;
                        lastProviderAudioAt=audioAt;
                        if(gap>=500L)Log.i("PixelTalkDiag","PROVIDER_AUDIO_GAP_MS="+gap);
                        if(++providerAudioEvents==1||providerAudioEvents%50==0)
                            Log.i("PixelTalkDiag","PROVIDER_AUDIO_EVENTS="+providerAudioEvents);
                        batched.append(pcm);
                    }finally{Arrays.fill(pcm,(byte)0);}
                } else if("clear".equals(type)){Log.i("PixelTalkDiag","GATEWAY_CLEAR");batched.clear();}
                else if("toolCall".equals(type))handleToolCall(p,ownedSession);
                else if("mark".equals(type)){
                    inboundStage=InboundStage.TALK_MARK_NAME;
                    String mark=GatewayJson.string(p.get("markName"),128);
                    if(!mark.isEmpty()&&!pendingMarks.offer(mark))throw new GatewayException("TALK_MARK_QUEUE_FULL");
                }
                else if("transcript".equals(type)){
                    // Text fragments do not delimit PCM. Keep the bounded 100-ms audio flush;
                    // flushing each partial word defeats batching on the legacy Glass codec.
                    inboundStage=InboundStage.TALK_TRANSCRIPT_ROLE;
                    String role=GatewayJson.string(p.get("role"),16);
                    inboundStage=InboundStage.TALK_TRANSCRIPT_TEXT;
                    String text=GatewayJson.string(p.get("text"),GatewayTalkText.MAX_TEXT_CHARS);
                    Log.i("PixelTalkDiag","GATEWAY_TRANSCRIPT_ROLE="+role+" CHARS="+text.length());
                    boolean isFinal=Boolean.TRUE.equals(p.get("final"));
                    GatewayTalkTranscript.Snapshot transcript=listener instanceof TranscriptListener
                            ? transcripts.accept(role,text,isFinal,"snapshot".equals(p.get("textMode")),p.get("transcriptId")) : null;
                    if("user".equals(role)&&isFinal&&TalkCameraIntent.matches(text)){
                        // receive/parse may have outlived Stop or the absolute Talk
                        // deadline. Recheck at the sensor-authorizing action boundary.
                        check();
                        listener.camera();
                    }else if(("user".equals(role)||"assistant".equals(role))&&(!text.isEmpty()||transcript!=null)){
                        String preview=GatewayTalkText.displayText(text);
                        if(preview.length()!=text.length())Log.i("PixelTalkDiag","GATEWAY_TRANSCRIPT_PREVIEW_CHARS="+preview.length());
                        check();
                        if(listener instanceof TranscriptListener){
                            if(transcript!=null)((TranscriptListener)listener).text(role,transcript.text,transcript.id,transcript.isFinal);
                        }else listener.text(role,preview);
                    }
                } else if("error".equals(type)){
                    Log.i("PixelTalkDiag","GATEWAY_ERROR_CODE="+issueCode(p)+" PHASE="+issuePhase(p));
                    throw new GatewayException("TALK_PROVIDER_ERROR");
                }
                else if("close".equals(type)){
                    socket.trace(GatewayTransportTrace.Event.RELAY_CLOSE,null,-1);
                    terminalCloseSeen=true;relayReady=false;revokePhoto();pendingMarks.clear();
                    Thread marks=markSender;if(marks!=null)marks.interrupt();
                    Object reason=p.get("reason");
                    Log.i("PixelTalkDiag","GATEWAY_CLOSE_REASON="+("completed".equals(reason)?"COMPLETED":
                            "error".equals(reason)?"ERROR":"OTHER"));
                    if("error".equals(p.get("reason")))throw new GatewayException("TALK_PROVIDER_ERROR");
                    stopCloseGrace();stopInput=true;drain();
                    batched.flush();check();terminal="TALK_CLOSED";break;
                }
            }
        }catch(Exception failure){
            socket.trace(GatewayTransportTrace.Event.TALK_FAILURE,failure,-1);
            String cancellation=cancelCause.get();
            boolean deliveryFailed="OUTPUT_DELIVERY_FAILURE".equals(cancellation);
            terminal=deliveryFailed?"TALK_FAILED":cancellation!=null?"TALK_CANCELLED":GatewayWebSocket.now()>=deadline?"TALK_TIMEOUT":"TALK_FAILED";
            if(failure instanceof GatewayException){
                String code=((GatewayException)failure).code;
                if("PROTOCOL_INVALID".equals(code))
                    Log.i("PixelTalkDiag","GATEWAY_PROTOCOL_STAGE="+inboundStage.name()+" FRAME_BYTES="+inboundFrameBytes);
            }
            String category=deliveryFailed?"OUTPUT_DELIVERY_FAILURE":closeGraceExpired?"TALK_CLOSE_GRACE_EXPIRED":failure instanceof GatewayException?failure.getMessage():null;
            Log.i("PixelTalkDiag","GATEWAY_FAIL="+(category!=null&&category.matches("[A-Z][A-Z0-9_]{0,63}")
                    ?category:failure.getClass().getSimpleName()));
        }
        finally{
            cancelled=true;stopInput=true;relayReady=false;revokePhoto();stopCloseGrace();
            TalkOutputBatcher b=outputBatcher;if(b!=null)b.close();outputBatcher=null;drain();pendingMarks.clear();
            Thread t=sender;if(t!=null)t.interrupt();t=markSender;if(t!=null)t.interrupt();
            socket.close();if(acquired)credentials.release();
            socket.emitTrace(new GatewayTransportTrace.Sink(){
                public void line(String line){Log.i("PixelTalkTransport",line);}
            });
            // LinkService may publish photo-ready only after this release, never
            // merely after cancel() has revoked the old relay.
            Log.i("PixelTalkDiag","GATEWAY_END="+terminal);listener.ended(terminal);
        }
    }
    private void handleToolCall(Map<?,?> p,String ownedSession)throws Exception{
        inboundStage=InboundStage.TALK_TOOL_CALL_ID;
        String callId=GatewayJson.string(p.get("callId"),128);
        inboundStage=InboundStage.TALK_TOOL_NAME;
        String name=GatewayJson.string(p.get("name"),128);
        if(callId.isEmpty())throw new GatewayException("TALK_TOOL_INVALID");
        if(pendingCallId!=null){submitToolError(callId,"Tool busy");return;}
        Object args=p.get("args");if(!(args instanceof Map)){submitToolError(callId,"Invalid consult request");return;}
        pendingCallId=callId;pendingRequestId="tool-"+(++nextTool);pendingRunId=null;earlyCompletion=null;
        pendingToolAt=GatewayWebSocket.now();
        Log.i("PixelTalkDiag","AGENT_TOOL_START="+("openclaw_agent_consult".equals(name)?"CONSULT":"OTHER"));
        if("openclaw_agent_control".equals(name)){
            pendingControl=true;Map<?,?> control=(Map<?,?>)args;
            Object spoken=control.get("text"),mode=control.get("mode");
            String text=spoken instanceof String&&!( (String)spoken).trim().isEmpty()?((String)spoken).trim():"status";
            Map<String,Object> params=GatewayJson.obj("sessionId",ownedSession,"sessionKey",talkSessionKey,"text",text);
            if(mode instanceof String&&!((String)mode).trim().isEmpty())params.put("mode",((String)mode).trim());
            send(pendingRequestId,"talk.session.steer",params);
        }else if("openclaw_agent_consult".equals(name)){
            pendingControl=false;
            send(pendingRequestId,"talk.client.toolCall",GatewayJson.obj("sessionKey",talkSessionKey,
                "callId",callId,"name",name,"relaySessionId",ownedSession,"args",args));
        }else{clearPending();submitToolError(callId,"Tool unavailable");}
    }
    private void handleChat(Map<?,?> p)throws Exception{
        if(pendingCallId==null)return;
        inboundStage=InboundStage.CHAT_STATE;
        String state=GatewayJson.string(p.get("state"),32);
        if(!"final".equals(state)&&!"error".equals(state)&&!"aborted".equals(state))return;
        inboundStage=InboundStage.CHAT_RUN_ID;
        String run=GatewayJson.string(p.get("runId"),128);
        if(pendingRunId==null){if(earlyCompletion==null){earlyCompletion=p;earlyCompletionBytes=inboundFrameBytes;}return;}
        if(!pendingRunId.equals(run)||p.get("sessionKey")!=null&&!pendingAgentKey.equals(p.get("sessionKey")))return;
        if("final".equals(state)){
            inboundStage=InboundStage.CHAT_RESULT_TEXT;
            try{
                String answer=GatewayTalkText.finalText(p.get("message"));
                if(answer==null||answer.isEmpty())submitToolError(pendingCallId,"OpenClaw returned no answer");
                else{
                    submitToolResult(pendingCallId,GatewayJson.obj("text",answer));
                    Log.i("PixelTalkDiag","AGENT_TOOL_RESULT_CHARS="+answer.length());
                }
            }catch(GatewayException bounded){
                if(!"TALK_ANSWER_TOO_LARGE".equals(bounded.code))throw bounded;
                // An oversized answer is a tool failure, not a corrupt relay. Do not
                // truncate speech or retry automatically; leave the owner in control.
                Log.i("PixelTalkDiag","AGENT_TOOL_RESULT_REJECTED=TEXT_BUDGET");
                submitToolError(pendingCallId,"OpenClaw answer exceeds the Talk text limit; request a shorter section.");
            }
        }else submitToolError(pendingCallId,"OpenClaw run ended");
        logToolWait();clearPending();
    }
    private void submitToolError(String callId,String message)throws Exception{submitToolResult(callId,GatewayJson.obj("error",message));}
    private void submitToolResult(String callId,Object result)throws Exception{
        send("tool-result-"+(++nextTool),"talk.session.submitToolResult",GatewayJson.obj("sessionId",sessionId,"callId",callId,"result",result));
    }
    private void logToolWait(){
        if(pendingToolAt>0L)Log.i("PixelTalkDiag","AGENT_TOOL_WAIT_MS="+(GatewayWebSocket.now()-pendingToolAt));
    }
    private void clearPending(){pendingCallId=null;pendingRequestId=null;pendingRunId=null;pendingAgentKey=null;pendingControl=false;earlyCompletion=null;earlyCompletionBytes=-1;pendingToolAt=0L;}
    private void sendLoop(String ownedSession){
        Pcm16To24 resampler=new Pcm16To24();
        int sent=0,silent=0;
        try{
            while(!cancelled&&!stopInput){
                byte[] pcm=input.poll(100,TimeUnit.MILLISECONDS);if(pcm==null)continue;
                if(stopInput){Arrays.fill(pcm,(byte)0);break;}
                byte[] converted=resampler.convert(pcm);
                try{
                    if(stopInput)continue;
                    if(TalkSilenceGate.apply(converted))silent++;
                    if(stopInput)continue;
                    send("append-"+(++nextAppend),"talk.session.appendAudio",
                        GatewayJson.obj("sessionId",ownedSession,"audioBase64",GatewayCredentials.b64(converted),"timestamp",GatewayWebSocket.now()));
                    if(++sent==1||sent%10==0){Log.i("PixelTalkDiag","GATEWAY_INPUT_FRAMES="+sent+" QUIET_FRAMES="+silent);silent=0;}
                }finally{Arrays.fill(pcm,(byte)0);Arrays.fill(converted,(byte)0);}
            }
        }catch(Exception failure){
            socket.trace(GatewayTransportTrace.Event.INPUT_FAILURE,failure,-1);
            if(!stopInput&&!cancelled){
                if(failure instanceof IOException&&!(failure instanceof GatewayException)){
                    Log.i("PixelTalkDiag","INPUT_SEND_IO_AWAIT_CLOSE");
                    waitForCloseAfterAppendRejection();
                }else cancel(GatewayWebSocket.now()>=deadline?"INPUT_SEND_DEADLINE":"INPUT_SEND_FAILURE");
            }
        }
    }
    private void markLoop(String ownedSession){
        try{
            while(!cancelled&&!terminalCloseSeen){
                String mark=pendingMarks.poll(100,TimeUnit.MILLISECONDS);
                if(mark==null||cancelled||terminalCloseSeen)continue;
                byte[] request=GatewayJson.encode(GatewayJson.obj("type","req","id","mark-"+mark,
                    "method","talk.session.acknowledgeMark","params",GatewayJson.obj("sessionId",ownedSession,"markName",mark)));
                try{
                    if(request.length>65536)throw new GatewayException("TALK_FRAME_BUDGET");
                    while(!cancelled&&!terminalCloseSeen){
                        check();
                        if(socket.sendIfWritable(request,100L))break;
                    }
                }finally{Arrays.fill(request,(byte)0);}
            }
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();}
        catch(Exception failure){
            socket.trace(GatewayTransportTrace.Event.MARK_FAILURE,failure,-1);
            if(!cancelled&&!terminalCloseSeen){
                if(failure instanceof IOException&&!(failure instanceof GatewayException)){
                    Log.i("PixelTalkDiag","MARK_ACK_SEND_IO_AWAIT_CLOSE");
                    waitForCloseAfterAppendRejection();
                }else{
                    Log.i("PixelTalkDiag","MARK_ACK_SEND_FAILED");
                    cancel("MARK_ACK_SEND_FAILURE");
                }
            }
        }
    }
    /** Stateful linear PCM16 conversion: Glass mic 16 kHz, Gateway realtime input 24 kHz. */
    static final class Pcm16To24 {
        private long sampleIndex=-1,nextOutputNumerator;
        private int previous;
        byte[] convert(byte[] pcm){
            if(pcm==null||pcm.length<2||(pcm.length&1)!=0||pcm.length>TalkWire.MAX_PCM)throw new IllegalArgumentException("PCM");
            byte[] output=new byte[((pcm.length/2)*3/2+3)*2];int at=0;
            for(int p=0;p<pcm.length;p+=2){
                int current=(short)((pcm[p]&255)|((pcm[p+1]&255)<<8));
                sampleIndex++;
                if(sampleIndex==0){previous=current;output[at++]=(byte)current;output[at++]=(byte)(current>>>8);nextOutputNumerator=2;continue;}
                while(nextOutputNumerator<=sampleIndex*3){
                    long fraction=nextOutputNumerator-(sampleIndex-1)*3;
                    int value=(int)(((long)previous*(3-fraction)+(long)current*fraction)/3);
                    output[at++]=(byte)value;output[at++]=(byte)(value>>>8);
                    nextOutputNumerator+=2;
                }
                previous=current;
            }
            byte[] result=Arrays.copyOf(output,at);Arrays.fill(output,(byte)0);return result;
        }
    }
}
