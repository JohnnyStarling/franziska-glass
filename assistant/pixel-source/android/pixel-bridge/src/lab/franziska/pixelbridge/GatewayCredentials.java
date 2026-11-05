package lab.franziska.pixelbridge;

import java.io.*;
import java.security.*;
import java.util.*;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.util.encoders.Base64;
import lab.franziska.protocol.AssistRequestIds;
import lab.franziska.protocol.ProtocolException;

/** Independent identity and endpoint-bound device token. Never exposes private key/token publicly.
 * Production construction is exclusively GatewayAndroidCredentials.open(Context).
 */
public final class GatewayCredentials {
    static final List<String> BASIC_SCOPES=Collections.unmodifiableList(Arrays.asList("operator.read","operator.write"));
    static final List<String> ADMIN_SCOPES=Collections.unmodifiableList(Arrays.asList("operator.admin","operator.read","operator.write"));
    interface Protector { byte[] seal(byte[] plain) throws Exception; byte[] open(byte[] sealed) throws Exception; }
    private final File root, record;
    private final Protector protector;
    private byte[] seed;
    private String token="", endpoint="", conversation;
    private List<String> scopes=BASIC_SCOPES;
    private AssistRequestIds.Ledger requestLedger=new AssistRequestIds.Ledger();
    private boolean busy;
    private long epoch;
    GatewayCredentials(File appFiles, Protector protector) throws Exception {
        this.protector=protector;
        root=new File(appFiles,"gateway-assist-private");
        if(!root.exists()&&!root.mkdir())throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
        if(!root.getCanonicalFile().equals(root.getAbsoluteFile())||!root.isDirectory())throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
        privateMode(root,true); record=new File(root,"credentials.bin");
        if(record.exists()){
            if(!record.getCanonicalFile().equals(record.getAbsoluteFile())||record.length()>32768)throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
            privateMode(record,false);byte[] encrypted=readRecord(record);byte[] plain=null;
            try{plain=protector.open(encrypted);Map<?,?> m=GatewayJson.map(GatewayJson.parse(plain));
                seed=decode(GatewayJson.string(m.get("seed"),64));if(seed.length!=32)throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
                token=GatewayJson.string(m.get("token"),4096);endpoint=GatewayJson.string(m.get("endpoint"),1024);
                conversation=GatewayJson.string(m.get("conversation"),36);if(!UUID.fromString(conversation).toString().equals(conversation))throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
                // Undeployed legacy/partial formats fail closed; never reset the identity or ledger.
                if(m.containsKey("used")||!"namespace-highwater-v1".equals(m.get("replayFormat"))||!(m.get("requestHighWatermarks") instanceof Map))throw new GatewayException("CREDENTIAL_LEDGER_FORMAT_INVALID");
                Map<?,?> persisted=(Map<?,?>)m.get("requestHighWatermarks");
                if(persisted.size()>AssistRequestIds.MAX_NAMESPACES)throw new GatewayException("CREDENTIAL_LEDGER_FORMAT_INVALID");
                Map<Long,Long> high=new LinkedHashMap<Long,Long>();
                try{for(Map.Entry<?,?> item:persisted.entrySet()){
                    if(!(item.getKey() instanceof String))throw new Exception();String key=(String)item.getKey();long namespace=Long.parseLong(key);
                    if(!Long.toString(namespace).equals(key))throw new Exception();
                    if(!(item.getValue() instanceof String))throw new Exception();
                    String value=(String)item.getValue();long counter=Long.parseLong(value);
                    if(counter<=0||!Long.toString(counter).equals(value))throw new Exception();
                    high.put(namespace,counter);
                }requestLedger=new AssistRequestIds.Ledger(high);}catch(Exception invalid){throw new GatewayException("CREDENTIAL_LEDGER_FORMAT_INVALID");}
                if(!token.isEmpty()){
                    if(!validToken(token)||!deviceId().equals(m.get("deviceId"))||!"operator".equals(m.get("role"))
                        ||!validScopes(m.get("scopes"))
                        ||!GatewayWebSocket.route(endpoint).toString().equals(endpoint))throw new GatewayException("CREDENTIAL_BINDING_INVALID");
                    scopes=ADMIN_SCOPES.equals(m.get("scopes"))?ADMIN_SCOPES:BASIC_SCOPES;
                }
            }finally{Arrays.fill(encrypted,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}
        }else{seed=new Ed25519PrivateKeyParameters(new SecureRandom()).getEncoded();conversation=UUID.randomUUID().toString();save();}
    }
    static void privateMode(File f,boolean directory) throws IOException {
        if(!f.setReadable(false,false)||!f.setWritable(false,false)||!f.setExecutable(false,false)
                ||!f.setReadable(true,true)||!f.setWritable(true,true)||(directory&&!f.setExecutable(true,true)))
            throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
    }
    /** Fixed buffer and exact bound: do not trust a pre-read File.length() check alone. */
    static byte[] readRecord(File file) throws IOException {
        if(!file.isFile()||file.length()<1||file.length()>32768)throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
        byte[] buffer=new byte[32768];FileInputStream input=new FileInputStream(file);
        try{int count=0;while(count<buffer.length){int got=input.read(buffer,count,buffer.length-count);if(got<0)break;count+=got;}
            if(count==0||input.read()!=-1)throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
            return Arrays.copyOf(buffer,count);
        }finally{Arrays.fill(buffer,(byte)0);input.close();}
    }
    /** Same-directory rename on Android/Linux; NEVER delete/truncate old record as fallback.
     * File.renameTo may fail on other platforms. Such failure is safe and visible to the caller.
     */
    static void replaceRecord(File temp,File destination) throws IOException {
        File parent=destination.getParentFile().getCanonicalFile();
        if(!temp.getParentFile().getCanonicalFile().equals(parent)||!temp.getCanonicalFile().equals(temp.getAbsoluteFile())
            ||!destination.getCanonicalFile().equals(destination.getAbsoluteFile())||!temp.isFile()
            ||(destination.exists()&&!destination.isFile()))throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
        if(!temp.renameTo(destination))throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
    }
    private void save() throws Exception {
        Map<String,Object> high=new LinkedHashMap<String,Object>();
        for(Map.Entry<Long,Long> item:requestLedger.snapshot().entrySet())high.put(Long.toString(item.getKey()),Long.toString(item.getValue()));
        byte[] plain=GatewayJson.encode(GatewayJson.obj("seed",b64(seed),"token",token,"endpoint",endpoint,"conversation",conversation,"replayFormat","namespace-highwater-v1","requestHighWatermarks",high,"deviceId",deviceId(),"role","operator","scopes",scopes));
        byte[] sealed=null;File temp=File.createTempFile("credentials-",".tmp",root);
        try{privateMode(temp,false);sealed=protector.seal(plain);if(sealed.length>32768)throw new GatewayException("CREDENTIAL_STORAGE_FAILED");
            FileOutputStream out=new FileOutputStream(temp);try{out.write(sealed);out.getFD().sync();}finally{out.close();}
            replaceRecord(temp,record);
        }finally{Arrays.fill(plain,(byte)0);if(sealed!=null)Arrays.fill(sealed,(byte)0);if(temp.exists())temp.delete();}
    }
    public synchronized String deviceId() throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(publicBytes()));}
    private byte[] publicBytes(){return new Ed25519PrivateKeyParameters(seed,0).generatePublicKey().getEncoded();}
    synchronized String publicKey(){return url64(publicBytes());}
    synchronized String sign(String payload){Ed25519Signer s=new Ed25519Signer();s.init(true,new Ed25519PrivateKeyParameters(seed,0));byte[] b=payload.getBytes(GatewayJson.UTF8);try{s.update(b,0,b.length);return url64(s.generateSignature());}finally{Arrays.fill(b,(byte)0);}}
    synchronized void burnRequest(String id,long expected)throws Exception {
        checkEpoch(expected);
        try{requestLedger.reserve(id);}catch(ProtocolException invalid){
            if("ASSIST_REQUEST_REPLAY".equals(invalid.getMessage()))throw new GatewayException("REQUEST_ALREADY_USED");
            if("ASSIST_REQUEST_NAMESPACE_CAPACITY".equals(invalid.getMessage()))throw new GatewayException("REQUEST_NAMESPACE_CAPACITY");
            throw new GatewayException("REQUEST_ID_INVALID");
        }
        // Do not roll back on failure. No bytes may be uploaded unless this save succeeds.
        try{save();}catch(Exception e){throw new GatewayException("CREDENTIAL_STORAGE_FAILED");}
    }
    /** Owner settings hook, off UI thread, after cancelling active client. Invalid/blank new URL also revokes.
     * Call for every URL edit/change, not only when enabling or sending. It never contacts the Gateway.
     */
    public synchronized void revokeIfEndpointChanged(String url) throws Exception {
        String next="";try{next=GatewayWebSocket.route(url).toString();}catch(Exception invalid){}
        if(!endpoint.equals(next)){epoch++;token="";scopes=BASIC_SCOPES;endpoint=next;save();}
    }
    public synchronized boolean isPaired(String url){try{return !token.isEmpty()&&endpoint.equals(GatewayWebSocket.route(url).toString());}catch(Exception e){return false;}}
    synchronized String token(String url){return endpoint.equals(url)?token:"";}
    synchronized List<String> scopes(){return new ArrayList<String>(scopes);}
    public synchronized boolean hasAdmin(){return !token.isEmpty()&&scopes.equals(ADMIN_SCOPES);}
    synchronized String conversation(){return conversation;}
    /** Read-only authentication probe; never occupies or releases the request lease. */
    synchronized long observeEpoch(){return epoch;}
    synchronized long acquire() throws GatewayException {if(busy)throw new GatewayException("BUSY");busy=true;return epoch;}
    synchronized void release(){busy=false;}
    synchronized void checkEpoch(long expected)throws GatewayException{if(epoch!=expected)throw new GatewayException("CREDENTIAL_REVOKED");}
    synchronized void accept(String url,String newToken,long expected)throws Exception{
        accept(url,newToken,BASIC_SCOPES,expected);
    }
    synchronized void accept(String url,String newToken,List<String> newScopes,long expected)throws Exception{
        checkEpoch(expected);if(!url.equals(endpoint))throw new GatewayException("CREDENTIAL_BINDING_INVALID");if(!validToken(newToken))throw new GatewayException("DEVICE_TOKEN_INVALID");
        if(!validScopes(newScopes))throw new GatewayException("SCOPE_MISMATCH");
        String oldToken=token,oldEndpoint=endpoint;List<String> oldScopes=scopes;
        token=newToken;endpoint=url;scopes=new ArrayList<String>(newScopes);
        try{save();}catch(Exception e){token=oldToken;endpoint=oldEndpoint;scopes=oldScopes;throw new GatewayException("CREDENTIAL_STORAGE_FAILED");}
    }
    /** Call activeClient.cancel() first. Increments epoch so in-flight setup cannot resurrect token. */
    public synchronized void clearToken() throws Exception {epoch++;token="";scopes=BASIC_SCOPES;endpoint="";save();}
    static boolean validScopes(Object value){return BASIC_SCOPES.equals(value)||ADMIN_SCOPES.equals(value);}
    static boolean validToken(String s){if(s==null||s.length()<1||s.length()>4096)return false;for(int i=0;i<s.length();i++)if(s.charAt(i)<33||s.charAt(i)>126||s.charAt(i)=='|')return false;return true;}
    static String b64(byte[] b){return Base64.toBase64String(b);}
    static byte[] decode(String s){return Base64.decode(s);}
    static String url64(byte[] b){return b64(b).replace('+','-').replace('/','_').replace("=","");}
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString();}
}
