package lab.franziska.pixelbridge;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import lab.franziska.protocol.AssistWire;
import lab.franziska.protocol.AssistRequestIds;

/** One-shot synchronous worker API. No capture, reconnect, upload retry, DNS or route mutation.
 * Create one client per operation. Call cancel from lifecycle/Stop before revoking credentials.
 */
public final class GatewayAssistClient {
    public static final String PHOTO_ACK="Фото добавлено в эту беседу. Продолжим разговор.";
    public static final long TIMEOUT_MS=175000;
    public static final long UPGRADE_TIMEOUT_MS=330000;
    public interface UpgradeListener { void pending(String requestId); }
    private final GatewayCredentials credentials;
    private final GatewayWebSocket socket;
    private final long timeout;
    private volatile boolean cancelled, completed, uploaded, photoReceiptCanonical, succeeded;
    private boolean used, abortStarted;
    private volatile String runId, sessionKey, agentId;
    private long deadline, credentialEpoch;
    private int maxPayload=1048576, maxImage=262144, maxAttachment=512044;
    private long lastSeq=-1;
    private String delta="";
    public GatewayAssistClient(GatewayCredentials credentials){this(credentials,new GatewayWebSocket(),TIMEOUT_MS);}
    GatewayAssistClient(GatewayCredentials c,GatewayWebSocket s,long timeout){this.credentials=c;this.socket=s;this.timeout=timeout;}
    private interface Work { String run() throws Exception; }
    private String bounded(final Work work)throws GatewayException{return bounded(work,false);}
    private String bounded(final Work work,final boolean readOnly)throws GatewayException{
        synchronized(this){if(used){succeeded=false;photoReceiptCanonical=false;throw new GatewayException("CLIENT_ALREADY_USED");}used=true;}
        credentialEpoch=readOnly?credentials.observeEpoch():credentials.acquire();deadline=GatewayWebSocket.now()+timeout;
        final FutureTask<String> task=new FutureTask<String>(new Callable<String>(){public String call()throws Exception{
            try{check();return work.run();}
            catch(Exception failure){if(runId!=null)abortAndClose();throw failure;}
            finally{completed=true;socket.close();if(!readOnly)credentials.release();}
        }});
        Thread worker=new Thread(task,"glass-gateway-request");worker.setDaemon(true);worker.start();
        try{String answer=task.get(timeout,TimeUnit.MILLISECONDS);synchronized(this){check();succeeded=true;}return answer;}
        catch(TimeoutException e){cancel();throw new GatewayException("TIMEOUT");}
        catch(InterruptedException e){cancel();Thread.currentThread().interrupt();throw new GatewayException("CANCELLED");}
        catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof GatewayException)throw (GatewayException)cause;if(GatewayWebSocket.now()>=deadline||cause instanceof java.net.SocketTimeoutException)throw new GatewayException("TIMEOUT");
            if(cancelled)throw new GatewayException("CANCELLED");
            if(cause instanceof javax.net.ssl.SSLException)throw new GatewayException("TLS_FAILED");
            if(cause instanceof java.net.UnknownHostException)throw new GatewayException("DNS_FAILED");
            if(cause instanceof java.net.ConnectException)throw new GatewayException("CONNECTION_FAILED");
            if(cause instanceof java.io.IOException)throw new GatewayException("NETWORK_FAILED");
            throw new GatewayException("GATEWAY_FAILED");}
    }
    private void check()throws GatewayException{
        if(cancelled)throw new GatewayException("CANCELLED");if(GatewayWebSocket.now()>=deadline)throw new GatewayException("TIMEOUT");credentials.checkEpoch(credentialEpoch);
    }
    /** Explicit owner setup only. Empty bootstrap is only for an independently verified identity-bearing ingress.
     * The supplied char[] is consumed and wiped; no bootstrap secret is stored, even on failure.
     */
    public void pair(final String url,final char[] bootstrap)throws GatewayException{
        if(bootstrap==null)throw new GatewayException("BOOTSTRAP_INVALID");
        final char[] copy=bootstrap.clone();Arrays.fill(bootstrap,'\0');
        try{credentials.revokeIfEndpointChanged(url);bounded(new Work(){public String run()throws Exception{
            if(copy.length>4096)throw new GatewayException("BOOTSTRAP_INVALID");
            for(char c:copy)if(c<33||c>126||c=='|')throw new GatewayException("BOOTSTRAP_INVALID");
            URI route=GatewayWebSocket.route(url);connect(route,new String(copy),true,copy);return "paired";
        }});}catch(GatewayException e){throw e;}catch(Exception e){throw new GatewayException("CREDENTIAL_STORAGE_FAILED");}
        finally{Arrays.fill(copy,'\0');}
    }
    /** Sensor-free, auth-only reachability. No conversation, request ID, token write or media.
     * Read-only credential access does not occupy the voice/upload lease. */
    public void checkAccess(final String url)throws GatewayException{
        bounded(new Work(){public String run()throws Exception{
            URI route=GatewayWebSocket.route(url);
            String token=credentials.token(route.toString());
            if(token.isEmpty())throw new GatewayException("PAIRING_REQUIRED");
            connect(route,token,false,null,false);check();return "authenticated";
        }},true);
    }
    /** Uses only an already stored exact-endpoint device token. Media buffers are caller-owned. */
    public String execute(final String url,final String agent,final String requestUuid,final byte[] image,final byte[] audio)throws GatewayException{
        return bounded(new Work(){public String run()throws Exception{
            if(agent==null||!agent.matches("[a-z][a-z0-9_-]{0,63}"))throw new GatewayException("AGENT_INVALID");
            try{AssistRequestIds.counter(requestUuid);if(image==null||audio==null||image.length>262144||audio.length>512044||image.length+audio.length==0)throw new Exception();if(image.length>0)AssistWire.validateJpeg(image);if(audio.length>0)AssistWire.validateWav(audio);}
            catch(Exception e){throw new GatewayException("MEDIA_INVALID");}
            final boolean photoOnly=image.length>0&&audio.length==0;final int jpegLength=image.length;
            URI route=GatewayWebSocket.route(url);String token=credentials.token(route.toString());if(token.isEmpty())throw new GatewayException("BOOTSTRAP_REQUIRED");
            connect(route,token,false,null);token=null;check();
            if(image.length>maxImage||audio.length>maxAttachment)throw new GatewayException("MEDIA_BUDGET_EXCEEDED");
            agentId=agent;sessionKey="agent:"+agent+":glass:"+credentials.conversation();
            send("history","chat.history",GatewayJson.obj("sessionKey",sessionKey,"agentId",agent,"limit",1,"maxBytes",65536));
            Map<?,?> history=response("history");String sessionId=null;
            if(history.get("sessionId") instanceof String)sessionId=GatewayJson.string(history.get("sessionId"),128);
            if(history.get("inFlightRun") instanceof Map)throw new GatewayException("SESSION_BUSY");
            Object info=history.get("sessionInfo");if(info instanceof Map&&Boolean.TRUE.equals(((Map<?,?>)info).get("hasActiveRun")))throw new GatewayException("SESSION_BUSY");
            List<Object> attachments=new ArrayList<Object>();
            if(image.length>0)attachments.add(GatewayJson.obj("type","image","mimeType","image/jpeg","fileName","scene.jpg","content",GatewayCredentials.b64(image)));
            if(audio.length>0)attachments.add(GatewayJson.obj("type","audio","mimeType","audio/wav","fileName","question.wav","content",GatewayCredentials.b64(audio)));
            Map<String,Object> params=GatewayJson.obj("sessionKey",sessionKey,"agentId",agent,"message",audio.length>0?
                "Ответь по-русски на вопрос из приложенной аудиозаписи, учитывая фото, если оно есть. Если речь не удалось распознать, прямо скажи об этом; не угадывай вопрос. Отвечай кратко по-русски, простым текстом, без таблиц и Markdown, не более 4000 символов.":
                "Пользователь попросил включить камеру и сделать снимок. Фото приложено к этой беседе. Пока не анализируй его без вопроса; ответь кратко: «Сняла. Что тебе нужно узнать об этом снимке?» Следующий голосовой вопрос относится к этому фото, если пользователь не скажет иное.",
                "deliver",false,"timeoutMs",Math.max(1,deadline-GatewayWebSocket.now()-1000),"idempotencyKey",requestUuid,"attachments",attachments);
            if(sessionId!=null&&!sessionId.isEmpty())params.put("sessionId",sessionId);
            byte[] frame=GatewayJson.encode(GatewayJson.obj("type","req","id","send","method","chat.send","params",params));
            try{if(frame.length>maxPayload)throw new GatewayException("PAYLOAD_BUDGET_EXCEEDED");
                check();credentials.burnRequest(requestUuid,credentialEpoch);runId=requestUuid;check();socket.send(frame);uploaded=true;check();
            }finally{Arrays.fill(frame,(byte)0);}
            Map<?,?> ack=response("send");if(!requestUuid.equals(ack.get("runId")))throw new GatewayException("RUN_MISMATCH");
            while(true){check();Map<?,?> event=next();if(!"event".equals(event.get("type"))||!"chat".equals(event.get("event")))continue;
                Map<?,?> p=GatewayJson.map(event.get("payload"));
                if(!sessionKey.equals(p.get("sessionKey"))||!runId.equals(p.get("runId")))continue;
                if(p.get("agentId")!=null&&!agent.equals(p.get("agentId")))throw new GatewayException("RUN_MISMATCH");
                long seq=GatewayJson.integer(p.get("seq"),0,Long.MAX_VALUE);if(seq<=lastSeq)continue;lastSeq=seq;
                String state=GatewayJson.string(p.get("state"),32);
                if("error".equals(state))throw new GatewayException("ASSISTANT_FAILED");if("aborted".equals(state))throw new GatewayException("ASSISTANT_ABORTED");
                if("delta".equals(state)&&!photoOnly){String text=GatewayJson.string(p.get("deltaText"),4000);delta=Boolean.TRUE.equals(p.get("replace"))?text:delta+text;if(delta.length()>4000)throw new GatewayException("ANSWER_TOO_LARGE");}
                if("final".equals(state)){
                    if(Boolean.TRUE.equals(p.get("yielded")))throw new GatewayException("ASSISTANT_YIELDED");
                    if(photoOnly){
                        check();
                        send("photo-receipt","chat.history",GatewayJson.obj("sessionKey",sessionKey,"agentId",agent,
                            "limit",32,"maxBytes",65536,"maxChars",4096));
                        Map<?,?> receipt;
                        try{receipt=response("photo-receipt");}
                        catch(Exception failure){check();throw new GatewayException("PHOTO_RECEIPT_UNVERIFIED");}
                        check();verifyPhotoReceipt(receipt,sessionKey,sessionId,agent,requestUuid,jpegLength);check();
                        photoReceiptCanonical=true;return PHOTO_ACK;
                    }
                    String text=finalText(p.get("message"));if(text==null)throw new GatewayException("ANSWER_UNAVAILABLE");
                    check();return clean(text);
                }
            }
        }});
    }
    /** Claim-level receipt only. No remote digest/readback or model-vision assertion. */
    public synchronized boolean photoReceiptVerified(){
        if(!succeeded||!photoReceiptCanonical)return false;
        try{check();return true;}catch(GatewayException lost){return false;}
    }
    private static GatewayException receiptUnverified(){return new GatewayException("PHOTO_RECEIPT_UNVERIFIED");}
    /** Pure strict predicate over the bounded display projection, never over authored text. */
    static void verifyPhotoReceipt(Map<?,?> history,String key,String backing,String agent,String request,int jpegLength)throws GatewayException{
        try{
            if(jpegLength<=0||!key.equals(history.get("sessionKey"))
                    ||backing==null||backing.isEmpty()||!backing.equals(history.get("sessionId"))
                    ||(history.containsKey("agentId")&&!agent.equals(history.get("agentId"))))throw receiptUnverified();
            Object rows=history.get("messages");if(!(rows instanceof List)||((List<?>)rows).size()>32)throw receiptUnverified();
            String correlation=request+":user",foundId=null,foundUrl=null;long foundSeq=0;
            for(Object raw:(List<?>)rows){
                if(!(raw instanceof Map))throw receiptUnverified();
                Map<?,?> row=(Map<?,?>)raw;Object meta=row.get("__openclaw");
                if(!(meta instanceof Map))continue;Map<?,?> m=(Map<?,?>)meta;
                if(!correlation.equals(m.get("idempotencyKey")))continue;
                if(!"user".equals(row.get("role"))
                        ||(row.containsKey("idempotencyKey")&&!correlation.equals(row.get("idempotencyKey")))
                        ||m.containsKey("truncated")||m.containsKey("reason")||hasOmission(row)||hasOmission(m))throw receiptUnverified();
                String id=GatewayJson.string(m.get("id"),256);
                if(!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")||id.startsWith("pending:"))throw receiptUnverified();
                long seq=GatewayJson.integer(m.get("seq"),1,Long.MAX_VALUE);
                Object media=m.get("media");if(!(media instanceof List)||((List<?>)media).size()!=1)throw receiptUnverified();
                Map<?,?> fact=GatewayJson.map(((List<?>)media).get(0));
                if(!"image".equals(fact.get("kind"))||!"image/jpeg".equals(fact.get("contentType"))
                        ||!"scene.jpg".equals(fact.get("fileName"))||hasOmission(fact)
                        ||GatewayJson.integer(fact.get("sizeBytes"),1,262144)!=jpegLength)throw receiptUnverified();
                String url=GatewayJson.string(fact.get("url"),512);
                if(!url.matches("media://inbound/[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
                        ||url.endsWith("/.")||url.endsWith("/.."))throw receiptUnverified();
                if(foundId!=null&&(!foundId.equals(id)||foundSeq!=seq||!foundUrl.equals(url)))throw receiptUnverified();
                foundId=id;foundSeq=seq;foundUrl=url;
            }
            if(foundId==null)throw receiptUnverified();
        }catch(GatewayException invalid){throw receiptUnverified();}
    }
    private static boolean hasOmission(Object value){
        if(value instanceof Map){Map<?,?> m=(Map<?,?>)value;
            if(m.containsKey("omitted")||m.containsKey("pending")||m.containsKey("placeholder"))return true;
            for(Object part:m.values())if(hasOmission(part))return true;
        }else if(value instanceof List)for(Object part:(List<?>)value)if(hasOmission(part))return true;
        return false;
    }
    /** Same-device, owner-approved scope upgrade. Never uses bootstrap or edits the endpoint. */
    public String upgradeAccess(final String url,final UpgradeListener listener)throws GatewayException{
        return bounded(new Work(){public String run()throws Exception{
            URI route=GatewayWebSocket.route(url);
            String token=credentials.token(route.toString());
            if(token.isEmpty())throw new GatewayException("BOOTSTRAP_REQUIRED");
            if(credentials.hasAdmin())return "already-approved";
            connect(route,token,false,null);token=null;check();
            send("scope-request","device.scopes.requestUpgrade",GatewayJson.obj("scopes",GatewayCredentials.ADMIN_SCOPES));
            Map<?,?> pending=response("scope-request");
            String requestId=GatewayJson.string(pending.get("requestId"),128);
            if(!requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw new GatewayException("SCOPE_UPGRADE_RESPONSE_INVALID");
            if(listener!=null)try{listener.pending(requestId);}catch(RuntimeException ignored){/* UI observer is not authorization. */}
            send("scope-wait","device.scopes.waitUpgrade",GatewayJson.obj("requestId",requestId));
            Map<?,?> result=response("scope-wait",true);
            String status=GatewayJson.string(result.get("status"),32);
            if("rejected".equals(status))throw new GatewayException("SCOPE_UPGRADE_REJECTED");
            if("expired".equals(status))throw new GatewayException("SCOPE_UPGRADE_EXPIRED");
            if(!"approved".equals(status)||!requestId.equals(result.get("requestId"))
                    ||!GatewayCredentials.ADMIN_SCOPES.equals(result.get("scopes")))
                throw new GatewayException("SCOPE_UPGRADE_RESPONSE_INVALID");
            String upgraded=GatewayJson.string(result.get("deviceToken"),4096);
            check();credentials.accept(route.toString(),upgraded,GatewayCredentials.ADMIN_SCOPES,credentialEpoch);
            return "approved";
        }});
    }
    private void connect(URI route,String token,boolean setup,char[] bootstrapBuffer)throws Exception{connect(route,token,setup,bootstrapBuffer,true);}
    private void connect(URI route,String token,boolean setup,char[] bootstrapBuffer,boolean persistIssued)throws Exception{
        socket.open(route,deadline);Map<?,?> challenge=null;
        for(int i=0;i<16;i++){Map<?,?> frame=next();if("event".equals(frame.get("type"))&&"connect.challenge".equals(frame.get("event"))){challenge=GatewayJson.map(frame.get("payload"));break;}}
        if(challenge==null)throw new GatewayException("CHALLENGE_MISSING");String nonce=GatewayJson.string(challenge.get("nonce"),512);if(nonce.isEmpty()||nonce.indexOf('|')>=0)throw new GatewayException("CHALLENGE_INVALID");
        long ts=GatewayJson.integer(challenge.get("ts"),0,9007199254740991L);String id=credentials.deviceId();
        List<String> requestedScopes=credentials.scopes();
        String signature=credentials.sign(authPayload(id,ts,token,nonce,requestedScopes));
        Map<String,Object> params=GatewayJson.obj("minProtocol",4,"maxProtocol",4,
            "client",GatewayJson.obj("id","gateway-client","version","glass-assist-1","platform","android","mode","backend","deviceFamily","Pixel"),
            "role","operator","scopes",requestedScopes,"caps",Collections.emptyList(),
            "device",GatewayJson.obj("id",id,"publicKey",credentials.publicKey(),"signature",signature,"signedAt",ts,"nonce",nonce));
        if(!token.isEmpty())params.put("auth",GatewayJson.obj("token",token));
        try{send("connect","connect",params);}finally{
            // Auth bytes are wiped in send(); discard map/string references before waiting for hello.
            Object authInput=params.remove("auth");if(authInput instanceof Map)((Map<?,?>)authInput).clear();
            token=null;if(bootstrapBuffer!=null)Arrays.fill(bootstrapBuffer,'\0');
        }
        Map<?,?> hello=response("connect");check();
        if(!"hello-ok".equals(hello.get("type"))||GatewayJson.integer(hello.get("protocol"),4,4)!=4)throw new GatewayException("PROTOCOL_VERSION");
        Map<?,?> auth=GatewayJson.map(hello.get("auth"));if(!"operator".equals(auth.get("role")))throw new GatewayException("SCOPE_MISMATCH");
        Object scopes=auth.get("scopes");if(!requestedScopes.equals(scopes))throw new GatewayException("SCOPE_MISMATCH");
        Map<?,?> features=GatewayJson.map(hello.get("features"));Object methods=features.get("methods");
        if(!(methods instanceof List)||!((List<?>)methods).containsAll(Arrays.asList("chat.send","chat.abort","chat.history")))throw new GatewayException("CAPABILITY_MISSING");
        Object events=features.get("events");if(!(events instanceof List)||!((List<?>)events).contains("chat"))throw new GatewayException("CAPABILITY_MISSING");
        Map<?,?> policy=GatewayJson.map(hello.get("policy"));maxPayload=(int)Math.min(1500000,GatewayJson.integer(policy.get("maxPayload"),1,Integer.MAX_VALUE));
        Object budgets=policy.get("attachments");if(budgets instanceof Map){Map<?,?> b=(Map<?,?>)budgets;maxImage=(int)Math.min(262144,GatewayJson.integer(b.get("maxImageBytes"),1,Integer.MAX_VALUE));maxAttachment=(int)Math.min(512044,GatewayJson.integer(b.get("maxBytes"),1,Integer.MAX_VALUE));}
        Object issued=auth.get("deviceToken");if(issued instanceof String&&persistIssued){check();credentials.accept(route.toString(),GatewayJson.string(issued,4096),requestedScopes,credentialEpoch);}
        else if(setup)throw new GatewayException("DEVICE_TOKEN_MISSING");
    }
    static String authPayload(String id,long ts,String token,String nonce){return "v3|"+id+"|gateway-client|backend|operator|operator.read,operator.write|"+ts+"|"+token+"|"+nonce+"|android|pixel";}
    static String authPayload(String id,long ts,String token,String nonce,List<String> scopes){
        if(!GatewayCredentials.validScopes(scopes))throw new IllegalArgumentException("unexpected scopes");
        return "v3|"+id+"|gateway-client|backend|operator|"+(GatewayCredentials.ADMIN_SCOPES.equals(scopes)
            ?"operator.admin,operator.read,operator.write":"operator.read,operator.write")+"|"+ts+"|"+token+"|"+nonce+"|android|pixel";
    }
    private void send(String id,String method,Object params)throws Exception{check();byte[] bytes=GatewayJson.encode(GatewayJson.obj("type","req","id",id,"method",method,"params",params));try{if(bytes.length>maxPayload)throw new GatewayException("PAYLOAD_BUDGET_EXCEEDED");socket.send(bytes);}finally{Arrays.fill(bytes,(byte)0);}}
    private Map<?,?> next()throws Exception{return next(false);}
    private Map<?,?> next(boolean waitForFirstByte)throws Exception{return next(waitForFirstByte,2097152);}
    private Map<?,?> next(boolean waitForFirstByte,int maxBytes)throws Exception{check();byte[] b=socket.receive(waitForFirstByte);try{if(maxBytes==65536&&b.length>maxBytes)throw receiptUnverified();return GatewayJson.map(GatewayJson.parse(b));}finally{Arrays.fill(b,(byte)0);}}
    private Map<?,?> response(String id)throws Exception{return response(id,false);}
    private Map<?,?> response(String id,boolean waitForFirstByte)throws Exception{
        for(int i=0;i<1024;i++){Map<?,?> f=next(waitForFirstByte,"photo-receipt".equals(id)?65536:2097152);if(!"res".equals(f.get("type"))||!id.equals(f.get("id")))continue;
            if(!Boolean.TRUE.equals(f.get("ok")))throw peerError(f.get("error"));return GatewayJson.map(f.get("payload"));
        }throw new GatewayException("RESPONSE_LIMIT");
    }
    private static GatewayException peerError(Object error){
        String code="REQUEST_REJECTED",request=null;if(error instanceof Map){Map<?,?> e=(Map<?,?>)error;Object details=e.get("details");if(details instanceof Map){Map<?,?> d=(Map<?,?>)details;Object c=d.get("code");if(c instanceof String&&((String)c).matches("[A-Z][A-Z0-9_]{0,63}"))code=(String)c;Object r=d.get("requestId");if("PAIRING_REQUIRED".equals(code)&&r instanceof String&&((String)r).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))request=(String)r;}}
        return new GatewayException(code,request);
    }
    static String finalText(Object message)throws GatewayException{
        if(!(message instanceof Map))return null;Map<?,?> m=(Map<?,?>)message;if(!"assistant".equals(m.get("role")))throw new GatewayException("ANSWER_INVALID");
        Object content=m.get("content");if(!(content instanceof List))return null;StringBuilder text=new StringBuilder();
        for(Object part:(List<?>)content){if(!(part instanceof Map))continue;Map<?,?> p=(Map<?,?>)part;Object type=p.get("type");if("text".equals(type)||"output_text".equals(type)){String s=GatewayJson.string(p.get("text"),4000);if(text.length()>0)text.append('\n');text.append(s);if(text.length()>4000)throw new GatewayException("ANSWER_TOO_LARGE");}}
        return text.toString();
    }
    private static String clean(String text)throws GatewayException{
        StringBuilder out=new StringBuilder();for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='\n'||c=='\t'||(c>=32&&c!=127&&!(c>=0x80&&c<=0x9f)&&!(c>=0x202a&&c<=0x202e)&&!(c>=0x2066&&c<=0x2069)))out.append(c);}
        String result=out.toString().trim();if(result.isEmpty()||result.length()>4000)throw new GatewayException("ANSWER_INVALID");return result;
    }
    /** Never blocks the caller on I/O. Best effort abort gets <=100ms before unconditional local close. */
    public void cancel(){
        synchronized(this){if(cancelled)return;cancelled=true;succeeded=false;photoReceiptCanonical=false;}
        abortAndClose();
    }
    private void abortAndClose(){
        synchronized(this){if(abortStarted)return;abortStarted=true;}
        if(completed){socket.close();return;}
        final String run=runId,session=sessionKey,agent=agentId;
        if(!uploaded||run==null||session==null){socket.close();return;}
        Thread closer=new Thread(new Runnable(){public void run(){try{Thread.sleep(100);}catch(InterruptedException ignored){}socket.close();}},"glass-gateway-close");closer.setDaemon(true);closer.start();
        Thread abort=new Thread(new Runnable(){public void run(){try{socket.send(GatewayJson.encode(GatewayJson.obj("type","req","id","abort","method","chat.abort","params",GatewayJson.obj("sessionKey",session,"agentId",agent,"runId",run))));}catch(Exception ignored){}finally{socket.close();}}},"glass-gateway-abort");abort.setDaemon(true);abort.start();
    }
}
