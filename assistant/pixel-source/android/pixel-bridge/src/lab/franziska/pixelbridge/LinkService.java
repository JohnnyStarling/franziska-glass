package lab.franziska.pixelbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import lab.franziska.protocol.AppIdentityStore;
import lab.franziska.protocol.CallWire;
import lab.franziska.protocol.Protocol;
import lab.franziska.protocol.DisplayCalibration;
import lab.franziska.protocol.DisplaySender;
import lab.franziska.protocol.SessionState;
import lab.franziska.protocol.SecureTransport;
import lab.franziska.protocol.Tls;

public final class LinkService extends Service {

    public static final String DISPLAY="lab.franziska.pixelbridge.DISPLAY";
    private final DisplaySender displaySender=new DisplaySender();
    private boolean displayCapable;
    private int displayGeneration;
    private String displayStatus="Откройте Франциску на Glass вручную. Связь — через «Подготовить и проверить»; затем вернитесь сюда.";
    private String displayJson="",displayOwner="";
    private final Handler displayHandler=new Handler(Looper.getMainLooper());
    private final Runnable displayTick=new Runnable(){public void run(){synchronized(LinkService.this){
        if(!displaySender.active())return;
        if(displaySender.timedOut(SystemClock.elapsedRealtime())){
            Map<String,Object> cancel=displaySender.abort();
            if(cancel!=null)enqueueDisplay(cancel);
            displayStatus="Нет подтверждения Glass. Отмена отправлена; если связь потеряна, откат не позднее 2 минут.";
            publish(linkStatus);return;
        }
        flushDisplay();
        if(displaySender.active())displayHandler.postDelayed(this,120);
    }}};
    private synchronized void displayReset(){
        displayCapable=false;displaySender.clear();displayJson="";displayHandler.removeCallbacks(displayTick);
        displayStatus="Калибровка недоступна: нет подтверждённого foreground-канала Glass.";
    }
    private boolean enqueueDisplay(Map<String,Object> command){
        if(peer==null||stopRequested||inbound.isStopped())return false;
        // Bound to this exact peer in Outbound; replacement connection cannot inherit it.
        return outbound.offerLast(new Outbound("display.command",command,false));
    }
    private void flushDisplay(){
        Map<String,Object> command=displaySender.next(SystemClock.elapsedRealtime());
        if(command!=null&&!enqueueDisplay(command)){
            displaySender.clear();displayStatus="Канал занят. Предпросмотр откатится по таймауту Glass.";
            publish(linkStatus);
        }
    }
    private synchronized void displayAction(Intent intent){
        String op=intent.getStringExtra("op"),owner=intent.getStringExtra("owner");
        if(owner==null||owner.length()>80||owner.length()==0)return;
        if("status".equals(op)){publish(linkStatus);return;}
        if("begin".equals(op)){
            if(displaySender.active()){displayStatus="Настройка уже открыта. Завершите её.";publish(linkStatus);return;}
            displayOwner=owner;displayJson="";
            if(!displayCapable||peer==null||stopRequested||inbound.isStopped()){
                displayStatus="Недоступно. Подготовьте связь на главной, откройте Франциску на Glass вручную (не голосом). Старый клиент не поддерживает настройку.";
                publish(linkStatus);return;
            }
            if(talkId!=null||assistId!=null||pendingCallId!=null){
                displayStatus="Завершите разговор, фото или звонок; затем начните настройку.";publish(linkStatus);return;
            }
            String slot=intent.getStringExtra("slot");
            if(!"indoor".equals(slot)&&!"outdoor".equals(slot))return;
            displaySender.begin(owner,slot,++displayGeneration,SystemClock.elapsedRealtime());
            displayStatus="Ожидаем применения на Glass…";
            displayHandler.removeCallbacks(displayTick);displayHandler.post(displayTick);
        }else if(owner.equals(displaySender.id())){
            if("update".equals(op)){
                try{
                    DisplayCalibration.Profile value=DisplayCalibration.Profile.parse(lab.franziska.protocol.Json.parse(intent.getStringExtra("profile")));
                    int pattern=intent.getIntExtra("pattern",0);if(pattern<0||pattern>2)return;
                    displaySender.update(value,pattern,intent.getBooleanExtra("original",false));
                    displayStatus="Изменение ожидает ACK Glass…";
                }catch(Exception invalid){displayStatus="Некорректные параметры";}
            }else if("save".equals(op))displaySender.finish(true);
            else if("cancel".equals(op))displaySender.finish(false);
        }
        publish(linkStatus);
    }
    private synchronized void displayState(Protocol.Envelope envelope){
        if(!displayCapable||!"glass".equals(envelope.sourceId)||!displaySender.acknowledge(envelope.payload))return;
        try{displayJson=lab.franziska.protocol.Json.stringify(envelope.payload);}catch(Exception ignored){displayJson="";}
        String status=(String)envelope.payload.get("status");
        displayStatus="applied".equals(status)?"Применено на Glass (ACK), оптический эффект не измерен":
            "saved".equals(status)?"Сохранено на Glass · действует в интерфейсе Франциски":
            "busy".equals(status)?"Glass занят: завершите разговор, фото или чтение":
            "storage_error".equals(status)?"Не удалось сохранить. Предпросмотр остаётся временным; повторите или отмените":
            "timeout".equals(status)?"Время настройки истекло · несохранённое отменено":
            "cancelled".equals(status)?"Отменено · предыдущий профиль восстановлен":
            "Отклонено Glass: "+status;
        publish(linkStatus);
    }

    public static final String START = "lab.franziska.pixelbridge.START";
    public static final String CARD = "lab.franziska.pixelbridge.CARD";
    public static final String VECTORS = "lab.franziska.pixelbridge.VECTORS";
    public static final String STOP = "lab.franziska.pixelbridge.STOP";
    public static final String REVOKE = "lab.franziska.pixelbridge.REVOKE";
    public static final String STATUS = "lab.franziska.pixelbridge.STATUS";

    public static final String SEND = "lab.franziska.pixelbridge.SEND";
    public static final String REFRESH = "lab.franziska.pixelbridge.REFRESH";
    public static final String PREVIOUS = "lab.franziska.pixelbridge.PREVIOUS";
    public static final String NEXT = "lab.franziska.pixelbridge.NEXT";
    public static final String MARK = "lab.franziska.pixelbridge.MARK";
    public static final String CLEAR = "lab.franziska.pixelbridge.CLEAR";
    public static final String AGENT_ENABLE = "lab.franziska.pixelbridge.AGENT_ENABLE";
    public static final String AGENT_DISABLE = "lab.franziska.pixelbridge.AGENT_DISABLE";
    public static final String PREPARE_CHECK = "lab.franziska.pixelbridge.PREPARE_CHECK";
    public static final String ROUTE_RESTORE = "lab.franziska.pixelbridge.ROUTE_RESTORE";
    public static final String HOTSPOT_ARM = "lab.franziska.pixelbridge.HOTSPOT_ARM";
    public static final String HOTSPOT_RETURN = "lab.franziska.pixelbridge.HOTSPOT_RETURN";
    private final RoutePreparation routePreparation = new RoutePreparation();
    private GatewayAssistClient routeCheckClient;
    private Runnable routeHealthExpiry;
    private long routeObservationTicket=-1L;
    private boolean routeCheckRequested;
    private boolean restoringRoute;
    public static final String ASSIST_START = "lab.franziska.pixelbridge.ASSIST_START";
    public static final String ASSIST_ENABLE = "lab.franziska.pixelbridge.ASSIST_ENABLE";
    public static final String ASSIST_CONFIGURE = "lab.franziska.pixelbridge.ASSIST_CONFIGURE";
    public static final String ASSIST_DISABLE = "lab.franziska.pixelbridge.ASSIST_DISABLE";
    public static final String MIRROR_SHOW = "lab.franziska.pixelbridge.MIRROR_SHOW";
    public static final String MYGLASS_CONFIG_CHANGED = "lab.franziska.pixelbridge.MYGLASS_CONFIG_CHANGED";
    public static final String CALL_PREPARE = "lab.franziska.pixelbridge.CALL_PREPARE";
    public static final String CALL_LABEL = "call_label";
    public static final String CALL_E164 = "call_e164";
    private final AssistantStart assistantStart = new AssistantStart();
    private AssistantProduct product;
    private boolean assistantSurface;
    private final AssistAuthority assist = new AssistAuthority();
    private final lab.franziska.protocol.AssistTransfer transfer = new lab.franziska.protocol.AssistTransfer();
    private AssistUpstream assistUpstream;
    private boolean assistWorker;
    private long assistTicket;
    private String assistId;
    private GatewayTalkClient talkClient;
    private GatewayTalkClient.Photo assistPhoto;
    private GatewayTalkClient assistPhotoClient;
    private long assistPhotoTalkRevision;
    private String assistPhotoTalkId;
    // Revocation is immediate; readiness waits separately for the exact worker
    // whose finally block releases the shared Gateway credential lease.
    private GatewayTalkClient talkCleanupClient;
    private Socket talkCleanupPeer;
    private long talkCleanupRevision;
    private String talkId;
    private Socket talkPeer;
    private long talkRevision;
    private int talkInputIndex,talkOutputIndex;
    private AgentEndpoint agentEndpoint;
    private final AgentAuthority authority = new AgentAuthority(this);
    private boolean agentLoading;
    private boolean agentLoaderActive;
    private boolean pairLoading;
    private long pairLoadToken;
    private final Checklist checklist = new Checklist();
    private String linkStatus = "OFF / требуется Start";
    private MyGlassWorker myGlassWorker;
    private long myGlassRevision;
    private final PhoneCallGate callGate = new PhoneCallGate();
    private Socket callPeer;
    private String callPeerId;
    private String callState = "Звонок через Glass не подготовлен";
    private String pendingCallId;
    private final Handler callTimer = new Handler(Looper.getMainLooper());
    private Runnable callExpiry;

    private static final String CHANNEL = "glass-link";
    private static final long PING_INTERVAL_MS = 5000L;
    private static final long FRAME_DEADLINE_MS = 3000L;
    private static final long HANDSHAKE_DEADLINE_MS = 3000L;
    private static final long READ_OPERATION_DEADLINE_MS = 10000L;
    private static final long WRITE_OPERATION_DEADLINE_MS = 3000L;
    private static final int OUTBOUND_CAPACITY = 16;
    private static final long TALK_QUEUE_WAIT_MS = 3000L;

    private final SessionState inbound = new SessionState();
    private final LinkedBlockingDeque<Outbound> outbound =
            new LinkedBlockingDeque<Outbound>(OUTBOUND_CAPACITY);
    private final Map<String, Long> pendingPings = new LinkedHashMap<String, Long>();

    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile Socket peer;
    private volatile ServerSocket server;
    private volatile boolean identityCreating;
    private volatile AppIdentityStore.LifecycleToken identityToken;
    private volatile long generation;
    private String epoch;
    private long sequence;
    private int events;
    private volatile String lastCardNonce = "";
    private boolean authorizeStartOnNextPeer;

    @Override public void onCreate() {
        super.onCreate();
        android.content.SharedPreferences history = getSharedPreferences("assistant-status", MODE_PRIVATE);
        product = new AssistantProduct(history.getString("last", "NONE"), history.getString("previous", "NONE"));
        android.content.SharedPreferences draft = getSharedPreferences("checklist-draft", MODE_PRIVATE);
        try { checklist.edit(draft.getString("title", Checklist.DEFAULT_TITLE),
                draft.getString("steps", Checklist.DEFAULT_STEPS)); }
        catch (IllegalArgumentException invalid) { /* Retain safe default, never publish invalid storage. */ }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Glass local link", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if(intent==null||ROUTE_RESTORE.equals(action)) {
            // Reopen only an explicitly enabled, still complete pair; never provision at boot.
            if(routeRestoreEnabled()&&Provisioning.isComplete(this)){restoringRoute=true;startConversation();}
            else {stopSelf();return START_NOT_STICKY;}
            return restartPolicy();
        }
        if(HOTSPOT_ARM.equals(action)) {
            routePreparation.arm(intent.getStringExtra("route_nonce"),SystemClock.elapsedRealtime());
            return restartPolicy();
        }
        if(HOTSPOT_RETURN.equals(action)) {
            if(routePreparation.returned(intent.getStringExtra("route_nonce"),SystemClock.elapsedRealtime()))prepareAndCheck();
            return restartPolicy();
        }
        if(PREPARE_CHECK.equals(action)) {prepareAndCheck();return restartPolicy();}
        if (CALL_PREPARE.equals(action)) {
            prepareOwnerCall(intent);
            if (!running && !pairLoading && !identityCreating) stopSelf();
            return restartPolicy();
        }
        if (MYGLASS_CONFIG_CHANGED.equals(action)) {
            stopMyGlassWorker();
            updateMyGlassWorker();
            publish(MyGlassWorker.enabled(this)
                    ? "MyGlass: доступ включён; синхронизация работает вместе со связью Glass"
                    : "MyGlass: синхронизация выключена");
            if (!running && !pairLoading && !identityCreating) stopSelf();
            return restartPolicy();
        }
        if (ASSIST_START.equals(action) || ASSIST_ENABLE.equals(action)) {
            if (ASSIST_ENABLE.equals(action) && (!running || stopRequested)) {
                publish("Assistant OFF / native Start required"); return restartPolicy();
            }
            restoringRoute=false;setRouteRestore(true);startConversation();
            return restartPolicy();
        }
        if (ASSIST_CONFIGURE.equals(action)) {
            setRouteRestore(false);cancelRouteCheck();
            assistantStart.invalidate(); disableAssist();
            recordProduct(AssistantProduct.Code.CONFIG_CHANGED); assistStatus(); publish(linkStatus);
            return restartPolicy();
        }
        if (ASSIST_DISABLE.equals(action)) {
            setRouteRestore(false);cancelRouteCheck();
            assistantStart.invalidate(); AssistPairing.INSTANCE.invalidate(); disableAssist();
            recordProduct(AssistantProduct.Code.CONVERSATION_DISABLED); assistStatus(); publish("Assistant disabled");
            return restartPolicy();
        }
        if (MIRROR_SHOW.equals(action)) {
            if (!running || stopRequested || peer == null || inbound.isStopped()) {
                publish("Локальная карточка сохранена; сначала включите связь с Glass");
                return restartPolicy();
            }
            try {
                int index = intent.getIntExtra("index", -1);
                LocalMirrorCatalog catalog = LocalMirrorCatalog.decode(
                        getSharedPreferences("local-mirror", MODE_PRIVATE).getString("cards", ""));
                if (index < 0 || index >= catalog.size()) throw new IllegalArgumentException("card index");
                LocalMirrorCatalog.Card card = catalog.get(index);
                if (!card.title.equals(intent.getStringExtra("title")) || !card.body.equals(intent.getStringExtra("body")))
                    throw new IllegalArgumentException("card changed");
                if (enqueueLatest(new Outbound("ui.card", card.payload(), false)))
                    publish("Запрос показа карточки в очереди · показ на Glass ещё не подтверждён");
                else publish("Запрос показа карточки не передан · очередь занята");
            } catch (IllegalArgumentException invalid) { publish("Локальная карточка недоступна"); }
            return restartPolicy();
        }
        if (AGENT_ENABLE.equals(action)) {
            if (!running || stopRequested) { publish("Agent OFF / native Start required"); return restartPolicy(); }
            enableAgentAsync();
            return restartPolicy();
        }
        if (AGENT_DISABLE.equals(action)) { disableAgent(); publish("Agent OFF"); return restartPolicy(); }
        if (REVOKE.equals(action)) {
            revokePair();
            return restartPolicy();
        }
        if (STOP.equals(action)) {
            requestLocalStop();
            return restartPolicy();
        }
        if (DISPLAY.equals(action)){displayAction(intent);return restartPolicy();}
        if (REFRESH.equals(action)) {
            publish(linkStatus);
        } else if (START.equals(action) || SEND.equals(action)) {
            try {
                String title = intent.getStringExtra("title");
                String steps = intent.getStringExtra("steps");
                // Existing explicit START callers need no new extras; never applies to SEND.
                if (START.equals(action) && title == null && steps == null) {
                    android.content.SharedPreferences draft = getSharedPreferences("checklist-draft", MODE_PRIVATE);
                    title = draft.getString("title", Checklist.DEFAULT_TITLE);
                    steps = draft.getString("steps", Checklist.DEFAULT_STEPS);
                }
                Checklist.validate(title, steps);
                getSharedPreferences("checklist-draft", MODE_PRIVATE).edit()
                        .putString("title", title).putString("steps", steps).apply();
                if (stopRequested && running) { publish("STOP выполняется; повторите Start после OFF"); return restartPolicy(); }
                checklist.edit(title, steps);
                if (START.equals(action)) { assistantSurface = false; if (!running) startEndpoint(); }
                if (running && !stopRequested) { checklist.start(); queueChecklist(); }
                else if (SEND.equals(action)) publish("Черновик сохранён. Требуется Start; endpoint OFF");
            } catch (IllegalArgumentException invalid) { publish("Некорректный список: заголовок 1–40, шаги 1–20 по 1–120 символов"); }
        } else if (PREVIOUS.equals(action) || NEXT.equals(action) || MARK.equals(action)) {
            if (running && !stopRequested && checklist.input(PREVIOUS.equals(action) ? "previous" : NEXT.equals(action) ? "next" : "tap")) queueChecklist();
            else publish("OFF / требуется Start");
        } else if (CLEAR.equals(action)) {
            checklist.clearProgress();
            queueChecklist();
        } else if (CARD.equals(action) && running && !stopRequested) {
            checklist.stop();
            publish(enqueueLatest(new Outbound("ui.card", cardPayload(), false))
                    ? "Демо-карточка в очереди" : "Демо-карточка не передана · очередь занята");
        } else if (VECTORS.equals(action) && running && !stopRequested) {
            checklist.stop();
            publish(enqueueLatest(new Outbound("ui.vector_scene", vectorPayload(), false))
                    ? "Демо-векторы в очереди" : "Демо-векторы не переданы · очередь занята");
        } else if (CARD.equals(action) || VECTORS.equals(action)) {
            publish("START REQUIRED / endpoint remains OFF");
        } else {
            publish("REFUSED / unknown or missing action / endpoint unchanged");
        }
        return restartPolicy();
    }

    private boolean routeRestoreEnabled(){return getSharedPreferences("route-mode",MODE_PRIVATE).getBoolean("enabled",false);}
    private void setRouteRestore(boolean enabled){getSharedPreferences("route-mode",MODE_PRIVATE).edit().putBoolean("enabled",enabled).commit();}
    private int restartPolicy(){return routeRestoreEnabled()&&!stopRequested?START_STICKY:START_NOT_STICKY;}
    private synchronized void prepareAndCheck(){
        if(stopRequested&&running)return;
        restoringRoute=false;setRouteRestore(true);routeCheckRequested=true;
        startConversation();
        if(running&&assistantStart.phase()==AssistantStart.Phase.ENABLED)beginRouteCheck();
    }
    private synchronized void cancelRouteCheck(){
        routeCheckRequested=false;routePreparation.invalidate();routeObservationTicket=-1L;
        if(routeHealthExpiry!=null){displayHandler.removeCallbacks(routeHealthExpiry);routeHealthExpiry=null;}
        GatewayAssistClient prior=routeCheckClient;routeCheckClient=null;
        if(prior!=null)prior.cancel();
    }
    private synchronized void beginRouteCheck(){
        if(!routeCheckRequested||!running||stopRequested)return;
        routeCheckRequested=false;
        final long ticket=routePreparation.begin();if(ticket<0L)return;
        routeObservationTicket=-1L;
        if(routeHealthExpiry!=null){displayHandler.removeCallbacks(routeHealthExpiry);routeHealthExpiry=null;}
        final long owner=generation;
        final String route=getSharedPreferences("assist-private",MODE_PRIVATE).getString("route","");
        publish(linkStatus);
        new Thread(new Runnable(){public void run(){
            GatewayAssistClient client=null;boolean authenticated=false;
            try {
                GatewayCredentials credentials=GatewayAndroidCredentials.open(LinkService.this);
                // A read-only auth probe does not acquire the voice/media credential lease.
                client=new GatewayAssistClient(credentials,new GatewayWebSocket(),12000L);
                synchronized(LinkService.this){
                    if(owner!=generation||!running||stopRequested||!routePreparation.current(ticket))return;
                    routeCheckClient=client;
                }
                client.checkAccess(route);authenticated=true;
            }catch(Exception unavailable){ /* Only fixed product copy; no route/server/token text. */ }
            finally{
                synchronized(LinkService.this){
                    if(routeCheckClient==client)routeCheckClient=null;
                    if(owner==generation&&running&&!stopRequested&&routePreparation.finish(ticket,authenticated,SystemClock.elapsedRealtime())) {
                        routeObservationTicket=ticket;publish(linkStatus);
                        if(authenticated){
                            routeHealthExpiry=new Runnable(){public void run(){synchronized(LinkService.this){
                                if(owner==generation&&routeObservationTicket==ticket&&running&&!stopRequested)publish(linkStatus);
                            }}};
                            displayHandler.postDelayed(routeHealthExpiry,RoutePreparation.FRESH_MS+1L);
                        }
                    }
                }
            }
        }},"pixel-route-check").start();
    }

    /** Single deliberate owner action; pair completion, not an immediate second Intent, enables WSS. */
    private synchronized void startConversation() {
        if (stopRequested && running || identityCreating) { publish(linkStatus); return; }
        if (assistantStart.pending() || assistantStart.phase() == AssistantStart.Phase.ENABLED) {
            publish(linkStatus); return;
        }
        assistantSurface = true;
        checklist.stop();
        synchronized (outbound) {
            Iterator<Outbound> it = outbound.iterator();
            while (it.hasNext()) if (isUi(it.next().type)) it.remove();
        }
        long ticket = assistantStart.start(running);
        if (ticket < 0) return;
        recordProduct(AssistantProduct.Code.STARTING);
        if (running) enableConversationAsync(ticket);
        else startEndpoint();
        publish(linkStatus);
    }

    private synchronized void enableConversationAsync(final long ticket) {
        disableAssist();
        final long startGeneration = generation;
        final String route = getSharedPreferences("assist-private", MODE_PRIVATE).getString("route", "");
        new Thread(new Runnable() { public void run() {
            boolean paired = false;
            try { paired = GatewayAndroidCredentials.open(LinkService.this).isPaired(route); }
            catch (Exception invalid) { /* Fixed code only. */ }
            synchronized (LinkService.this) {
                if (!running || stopRequested || generation != startGeneration
                        || !assistantStart.routeValidated(ticket, paired)) return;
                assist.enable(true, paired);
                recordProduct(paired ? AssistantProduct.Code.ACCESS_ENABLED : AssistantProduct.Code.ROUTE_REQUIRED);
                assistStatus(); publish(linkStatus);
                if(paired)beginRouteCheck();else {routeCheckRequested=false;}
            }
        }}, "pixel-assist-enable").start();
    }

    private synchronized void startEndpoint() {
        if (running || (pairLoading && pairLoadToken == generation)) return;
        disableAgent();
        disableAssist();
        stopRequested = false;
        final long token = ++generation;
        pairLoading = true; pairLoadToken = token;
        startForeground(7, notification("Связь с Glass · проверка пары"));
        new Thread(new Runnable() { public void run() {
            try {
                // Disk and private-identity validation are lifecycle work, never network status work.
                AppIdentityStore.IdentityState state = AppIdentityStore.identityState(
                        getFilesDir(), "pixel", getPackageName());
                boolean complete = state == AppIdentityStore.IdentityState.READY && Provisioning.isComplete(LinkService.this);
                final AppIdentityStore.LoadedIdentity identity = complete ? AppIdentityStore.loadIdentity(getFilesDir()) : null;
                final AppIdentityStore.TrustConfig trust = complete ? AppIdentityStore.loadTrust(getFilesDir(), true) : null;
                synchronized (LinkService.this) {
                    if (token != generation || stopRequested) return;
                    if (state == AppIdentityStore.IdentityState.ABSENT) {
                        assistantStart.fail(); recordProduct(AssistantProduct.Code.PAIR_REQUIRED);
                        if(restoringRoute){setRouteRestore(false);publish("Нужно подготовить пару вручную · автоматическая подготовка не создаёт идентичность");stopSelf();return;}
                        createIdentityAsync(); return;
                    }
                    if (!complete) {
                        if(restoringRoute)setRouteRestore(false);
                        assistantStart.fail(); recordProduct(AssistantProduct.Code.PAIR_REQUIRED);
                        publish("OFF / VALID PAIR REQUIRED / PUBLIC BOOTSTRAP OR REVOKE");
                        stopSelf();
                        return;
                    }
                    running = true;
                    authorizeStartOnNextPeer = true;
                    authority.start(token, true);
                    updateMyGlassWorker();
                    if (!assistantSurface) { checklist.start(); queueChecklist(); }
                    long startTicket = assistantStart.ticket();
                    if (assistantStart.pairValidated(startTicket)) enableConversationAsync(startTicket);
                    publish(linkStatus);
                    new Thread(new Runnable() {
                        @Override public void run() { endpointLoop(token, identity, trust); }
                    }, "pixel-link-listener").start();
                }
            } catch (Exception invalid) {
                synchronized (LinkService.this) {
                    if (token == generation) { assistantStart.fail(); recordProduct(AssistantProduct.Code.PAIR_FAILED); publish("OFF / pair validation failed"); stopSelf(); }
                }
            } finally { synchronized (LinkService.this) { if (pairLoadToken == token && token == generation) { pairLoading = false; publish(linkStatus); } } }
        }}, "pixel-pair-validation").start();
    }

    private synchronized void createIdentityAsync() {
        if (identityCreating) return;
        identityCreating = true;
        final AppIdentityStore.LifecycleToken operation =
                AppIdentityStore.beginIdentityCreation(getFilesDir());
        identityToken = operation;
        publish("OFF / CREATING PRIVATE IDENTITY");
        final long startedAt = SystemClock.elapsedRealtime();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    AppIdentityStore.createIdentity(getFilesDir(), "pixel", getPackageName(), operation,
                            new AppIdentityStore.Cancellation() {
                                public boolean cancelled() {
                                    return !AppIdentityStore.isCurrent(operation) || stopRequested
                                            || SystemClock.elapsedRealtime() - startedAt > 30000L;
                                }
                            }, new AppIdentityStore.StageListener() {
                                public void stage(String stage) {
                                    if (identityToken == operation && AppIdentityStore.isCurrent(operation)
                                            && !stopRequested) publishIdentity(operation, "OFF / " + stage);
                                }
                            });
                    if (identityToken == operation && AppIdentityStore.isCurrent(operation)
                            && !stopRequested) publishIdentity(operation,
                                    "OFF / IDENTITY READY / PUBLIC BOOTSTRAP REQUIRED");
                } catch (Exception failure) {
                    if (identityToken == operation && AppIdentityStore.isCurrent(operation)
                            && !stopRequested) publishIdentity(operation,
                                    "OFF / IDENTITY CREATION FAILED / NO ROTATION");
                } finally {
                    if (identityToken == operation) {
                        identityCreating = false;
                        identityToken = null;
                        if (AppIdentityStore.isCurrent(operation) && !stopRequested) stopSelf();
                    }
                }
            }
        }, "pixel-identity-create").start();
    }

    private void endpointLoop(long token, AppIdentityStore.LoadedIdentity identity, AppIdentityStore.TrustConfig trust) {
        int retry=0;
        try {
            while(isCurrent(token)&&!stopRequested) {
                ServerSocket listener=null;
                try {
                    // Dynamic DHCP/hotspot addresses do not own listener lifetime. Every accepted
                    // connection still requires the unchanged exact Glass certificate pin.
                    listener=SecureTransport.listen("0.0.0.0",trust.port);
                    if(!ownServer(token,listener))return;
                    retry=0;publish("Endpoint: local networks / waiting for exact pinned peer");
                    while(isCurrent(token)&&!stopRequested) {
                        Socket accepted=listener.accept();
                        try {servePeer(token,accepted,identity,trust.peerPin);}
                        catch(Exception peerFailure) {
                            if(isCurrent(token)&&!stopRequested) {
                                closePeer();purgeTransientOutbound();clearPeerPingState();
                                publish("Связь Glass прервалась · ожидаем автоматическое переподключение");
                            }
                            if(listener.isClosed())break;
                        }
                    }
                }catch(Exception unavailable) {
                    if(isCurrent(token)&&!stopRequested)publish("Связь временно недоступна · повторим автоматически");
                }finally{
                    synchronized(this){if(server==listener)server=null;}
                    if(listener!=null)try{listener.close();}catch(IOException ignored){}
                }
                synchronized(this){
                    if(!isCurrent(token)||stopRequested)break;
                    try{wait(Math.min(10000L,1000L<<Math.min(retry++,4)));}
                    catch(InterruptedException stopped){Thread.currentThread().interrupt();break;}
                }
            }
        }finally{shutdownEndpoint(token);}
    }

    private void servePeer(final long token, final Socket connected,
            AppIdentityStore.LoadedIdentity identity, byte[] peerPin) throws Exception {
        if (!ownPeer(token, connected)) throw new IOException("endpoint stopped");
        connected.setSoTimeout(1000);
        Tls.DeadlineWatchdog handshake = Tls.closeAtDeadline(
                connected, HANDSHAKE_DEADLINE_MS, "pixel-handshake-deadline");
        SecureTransport secure;
        try {
            secure = SecureTransport.acceptOwned(connected, identity, peerPin);
        } finally {
            handshake.cancel();
        }
        if (!isOwned(token, connected)) throw new IOException("endpoint stopped");

        final boolean explicitStart;
        synchronized (this) {
            if (!isOwned(token, connected) || stopRequested) throw new IOException("endpoint stopped");
            displayReset();
            epoch = "pixel-" + UUID.randomUUID().toString();
            sequence = 0L;
            cancelAssistWork();
            assist.peer(connected, epoch);
            transfer.reset(epoch);
            explicitStart = authorizeStartOnNextPeer;
            if (explicitStart) {
                inbound.beginEpoch(epoch);
                authorizeStartOnNextPeer = false;
            } else {
                inbound.reconnectEpoch(epoch);
            }
        }

        DataOutputStream output = secure.output();
        writeDirect(token, connected, output, "hello", emptyPayload());
        if (explicitStart) writeDirect(token, connected, output, "mode.start", emptyPayload());
        writeDirect(token, connected, output, "capabilities", lab.franziska.protocol.AssistWire.payload("talk_text_v2",Boolean.TRUE,"talk_photo_v1",Boolean.TRUE,DisplayCalibration.CAPABILITY,Boolean.TRUE));

        synchronized (this) {
            if (!isOwned(token, connected) || !authority.ready(token, connected)) throw new IOException("endpoint stopped");
            callPeer = connected;
            callPeerId = pinIdentity(peerPin);
            queueChecklist();
        }
        final DataOutputStream peerOutput = output;
        Thread writer = new Thread(new Runnable() {
            @Override public void run() { writerLoop(token, connected, peerOutput); }
        }, "pixel-link-writer");
        writer.start();
        publish("SECURE LINK / sensors OFF / epoch " + epoch);

        Tls.FrameReader input = Tls.frameReader(secure.input(), secure.rawCloser(),
                FRAME_DEADLINE_MS, READ_OPERATION_DEADLINE_MS);
        try {
            while (isOwned(token, connected)) {
                try {
                    byte[] frame = input.read();
                    long receivedAt = SystemClock.elapsedRealtime();
                    Protocol.Envelope envelope = Protocol.decode(frame);
                    long processedAt = SystemClock.elapsedRealtime();
                    synchronized (this) {
                        if (!isOwned(token, connected)) break;
                        // Stop owns the priority writer/watchdog shutdown. An incoming
                        // frame must not make this reader's finally close its socket first.
                        // Drain without accepting any input while that Stop is pending.
                        if (stopRequested) continue;
                        SessionState.Result result = inbound.accept(envelope, receivedAt, processedAt);
                        if (result == SessionState.Result.ACCEPTED) handleAccepted(token, envelope);
                    }
                } catch (Tls.IdleTimeoutException timeout) {
                    synchronized (this) { expireAssist(); }
                    // Timeout only lets us observe shutdown; periodic ping is in writer.
                }
            }
        } finally {
            closePeer(connected);
            writer.interrupt();
            if (isCurrent(token)) clearPeerPingState();
        }
    }

    private void writerLoop(long token, Socket connected, DataOutputStream output) {
        try {
            long nextPingAt = SystemClock.elapsedRealtime() + PING_INTERVAL_MS;
            while (isOwned(token, connected)) {
                long wait = Math.max(1L, nextPingAt - SystemClock.elapsedRealtime());
                Outbound message = outbound.pollFirst(wait, TimeUnit.MILLISECONDS);
                if (message != null) synchronized (this) { notifyAll(); }
                if (!isOwned(token, connected)) {
                    if (message != null && message.generation == generation) {
                        outbound.offerFirst(message);
                    }
                    return;
                }
                if (message == null) {
                    message = new Outbound("ping", emptyPayload(), false);
                    nextPingAt = SystemClock.elapsedRealtime() + PING_INTERVAL_MS;
                }
                if (message.generation != token) continue;
                Protocol.Envelope envelope;
                synchronized (this) {
                    if (!isOwned(token, connected)) continue;
                    if (message.checklistRevision >= 0 && !checklist.current(message.checklistRevision)) continue;
                    if (stopRequested && isUi(message.type)) continue;
                    if (!authority.writeAllowed(message.agentRequest, connected)) continue;
                    if (message.assistRevision >= 0 && (stopRequested || !assist.writable(message.assistRevision, connected))) continue;
                    if (message.type.startsWith("talk.") && (stopRequested || !assist.capable())) continue;
                    if (message.type.startsWith("talk.")
                            && (!"talk.error".equals(message.type) || "TALK_CLOSED".equals(message.payload.get("code")))
                            && (talkId == null || !talkId.equals(message.payload.get("request_id")) || talkPeer != connected)) continue;
                    if ("call.prepare".equals(message.type)) {
                        PhoneCallGate.Ticket ticket = callGate.pending(SystemClock.elapsedRealtime());
                        if (ticket == null || callPeer != connected || !ticket.requestId.equals(message.payload.get("request_id"))
                                || !ticket.glassPeerId.equals(callPeerId)) continue;
                    }
                    if (message.remotePeer != null && (message.remotePeer != connected || SystemClock.elapsedRealtime() - message.createdAt > 5000L)) {
                        if (message.type.startsWith("talk.") && message.remotePeer == connected) {
                            Log.i("PixelTalkDiag","LINK_TALK_FRAME_EXPIRED"); closePeer(connected);
                        }
                        continue;
                    }
                    envelope = createEnvelope(message.type, message.payload);
                    if (message.checklistRevision >= 0) checklist.written(envelope.messageId, message.checklistRevision);
                }
                if ("ping".equals(message.type)) rememberPing(envelope.messageId);
                long writeStartedAt = SystemClock.elapsedRealtime();
                Tls.write(output, Protocol.encode(envelope), connected,
                        WRITE_OPERATION_DEADLINE_MS, "pixel-frame-write-deadline");
                completeTalkWrite(message, connected);
                if("talk.audio".equals(message.type)){
                    int index=((Number)message.payload.get("index")).intValue();
                    if(index%5==0)Log.i("PixelTalkDiag","GLASS_OUTPUT_WRITTEN_INDEX="+index
                            +" WRITE_MS="+(SystemClock.elapsedRealtime()-writeStartedAt));
                }
                if (message.closeAfterWrite) {
                    shutdownEndpoint(token);
                    return;
                }
            }
        } catch (Exception writerFailure) {
            Log.i("PixelTalkDiag",writerFailure instanceof Tls.WriteDeadlineException ? "LINK_WRITE_DEADLINE" : "LINK_WRITE_FAILED");
            closePeer(connected);
            if (stopRequested && isCurrent(token)) shutdownEndpoint(token);
        }
    }

    private synchronized void handleAccepted(long token, Protocol.Envelope envelope) {
        if (!isCurrent(token) || stopRequested || !envelope.sessionId.equals(inbound.sessionId())) return;
        expireAssist();
        if("capabilities".equals(envelope.type)&&"glass".equals(envelope.sourceId)){
            displayCapable=Boolean.TRUE.equals(envelope.payload.get(DisplayCalibration.CAPABILITY));
            displayStatus=displayCapable?"Glass поддерживает настройку · начните явно":"Этот клиент Glass не поддерживает настройку";
            publish(linkStatus);return;
        }
        if("display.state".equals(envelope.type)){displayState(envelope);return;}
        if ("assist.hello".equals(envelope.type)) {
            if (assist.hello(peer, envelope.sessionId, envelope.sourceId, envelope.type, envelope.payload.isEmpty())) { assistStatus(); publish(linkStatus); }
            return;
        }
        if (envelope.type.startsWith("talk.")) { handleTalk(envelope); return; }
        if (envelope.type.startsWith("assist.")) { handleAssist(envelope); return; }
        if (envelope.type.startsWith("call.")) { handleCall(envelope); return; }
        if ("ping".equals(envelope.type)) {
            enqueueFirst(new Outbound("ack", ackPayload(envelope.messageId), false));
            return;
        }
        if ("ack".equals(envelope.type)) {
            if (checklist.acknowledge(String.valueOf(envelope.payload.get("reply_to"))))
                publish("Карточка получена приложением Glass");
            recordRtt(envelope);
            return;
        }
        if ("input.event".equals(envelope.type)) {
            events++;
            if (!inbound.isStopped() && checklist.remoteInput(String.valueOf(envelope.payload.get("action")))) queueChecklist();
            else publish("Событие Glass получено, но не применено: текущая карточка не подтверждена или список не опубликован");
            enqueueFirst(new Outbound("ack", ackPayload(envelope.messageId), false));
            return;
        }
        if ("mode.stop".equals(envelope.type)) {
            setRouteRestore(false);cancelRouteCheck();
            stopRequested = true;
            recordProduct(AssistantProduct.Code.STOPPED);
            publish("REMOTE STOP / endpoint closing");
            shutdownEndpoint(token);
        }
    }

    /** Only this app's owner-operated Activity can start this non-exported service action.
     * The peer identity below is taken from the TLS pin after the handshake, never extras.
     */
    private synchronized void prepareOwnerCall(Intent intent) {
        String failure = prepareCall(UUID.randomUUID().toString(),
                intent.getStringExtra(CALL_LABEL), intent.getStringExtra(CALL_E164), 0L);
        if (failure == null) return;
        callState = "glass_offline".equals(failure)
                ? "Glass не подключён; звонок не подготовлен"
                : "permission_denied".equals(failure)
                ? "Нужно разрешение Pixel на телефонные звонки; запрос не отправлен"
                : "invalid_payload".equals(failure)
                ? "Имя или E.164 некорректны; звонок не подготовлен"
                : "Другой звонок ожидает ответа или канал занят; запрос не отправлен";
        publish(callState);
    }

    /** The broker and the owner-operated Pixel UI converge only at this typed
     * gate. Admission queues a prompt; Telecom is called exclusively after an
     * authenticated, fresh physical confirmation from the Glass TLS peer.
     */
    private synchronized String prepareCall(String id, String label, String number, long expiresAtSeconds) {
        if (!running || stopRequested || inbound.isStopped() || peer == null
                || callPeer != peer || callPeerId == null || !authority.connected()) return "glass_offline";
        if (assistId != null || talkId != null || pendingCallId != null
                || callGate.pending(SystemClock.elapsedRealtime()) != null) return "local_error";
        if (expiresAtSeconds > 0L && expiresAtSeconds-System.currentTimeMillis()/1000L < 35L)
            return "invalid_payload";
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return "permission_denied";
        Map<String,Object> payload = CallWire.prepare(id, label, number);
        try {
            CallWire.validate("call.prepare", payload);
            callGate.prepare(id, callPeerId, label, number, SystemClock.elapsedRealtime());
        } catch (lab.franziska.protocol.ProtocolException invalid) {
            return "invalid_payload";
        } catch (IllegalArgumentException invalid) {
            return "invalid_payload";
        } catch (IllegalStateException full) {
            return "local_error";
        }
        pendingCallId = id;
        scheduleCallExpiry(id);
        synchronized (outbound) {
            for (Outbound queued : outbound)
                if ("call.prepare".equals(queued.type)) outbound.remove(queued);
            if (!outbound.offerFirst(new Outbound("call.prepare", payload, false))) {
                callGate.cancel(id, callPeerId);
                pendingCallId = null;
                callTimer.removeCallbacks(callExpiry);
                return "local_error";
            }
        }
        callState = "Запрос на Glass в очереди; ожидается физическое подтверждение на очках";
        publish(callState);
        return null;
    }

    private synchronized void handleCall(Protocol.Envelope envelope) {
        if (!"glass".equals(envelope.sourceId) || callPeer != peer || callPeerId == null
                || !authority.connected() || inbound.isStopped()) return;
        String id = (String) envelope.payload.get("request_id");
        if ("call.cancel".equals(envelope.type)) {
            if (callGate.cancel(id, callPeerId)) {
                pendingCallId = null;
                if (callExpiry != null) callTimer.removeCallbacks(callExpiry);
                callState = "Запрос звонка отменён на Glass; вызов не отправлялся";
                enqueueFirst(new Outbound("call.result", CallWire.result(id, "CANCELLED"), false));
                publish(callState);
            }
            return;
        }
        if (!"call.confirm".equals(envelope.type)) return;
        PhoneCallGate.Ticket ticket = callGate.pending(SystemClock.elapsedRealtime());
        if (ticket == null || !ticket.requestId.equals(id) || !ticket.glassPeerId.equals(callPeerId)) return;
        PhoneCallLauncher.Result result = PhoneCallLauncher.placeConfirmed(this, callGate, id, callPeerId);
        pendingCallId = null;
        if (callExpiry != null) callTimer.removeCallbacks(callExpiry);
        callState = result == PhoneCallLauncher.Result.REQUESTED
                ? "REQUESTED · запрос передан Telecom; дозвон не подтверждён"
                : result == PhoneCallLauncher.Result.PERMISSION_REQUIRED
                ? "Разрешение на звонки отсутствует; запрос не выполнен"
                : "Запрос звонка не выполнен: " + result.name();
        enqueueFirst(new Outbound("call.result", CallWire.result(id, result.name()), false));
        publish(callState);
    }

    private synchronized void cancelPendingCall() {
        pendingCallId = null;
        if (callExpiry != null) callTimer.removeCallbacks(callExpiry);
        PhoneCallGate.Ticket ticket = callGate.pending(SystemClock.elapsedRealtime());
        if (ticket != null) {
            callGate.cancel(ticket.requestId, ticket.glassPeerId);
            callState = "Связь прервана; ожидавший запрос звонка отменён";
        }
        for (Outbound queued : outbound)
            if ("call.prepare".equals(queued.type)) outbound.remove(queued);
    }

    private synchronized void scheduleCallExpiry(final String id) {
        if (callExpiry != null) callTimer.removeCallbacks(callExpiry);
        callExpiry = new Runnable() { @Override public void run() {
            synchronized (LinkService.this) {
                if (!id.equals(pendingCallId)) return;
                if (callGate.pending(SystemClock.elapsedRealtime()) != null) return;
                pendingCallId = null;
                callState = "Время подтверждения на Glass вышло; вызов не запрашивался";
                publish(callState);
            }
        }};
        callTimer.postDelayed(callExpiry, PhoneCallGate.CONFIRM_WINDOW_MS + 25L);
    }

    private static String pinIdentity(byte[] pin) {
        if (pin == null || pin.length != 32) throw new IllegalArgumentException("peer pin");
        char[] out = new char[pin.length * 2];
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < pin.length; i++) {
            out[2 * i] = hex[(pin[i] >>> 4) & 15];
            out[2 * i + 1] = hex[pin[i] & 15];
        }
        return new String(out);
    }

    private synchronized void requestLocalStop() {
        setRouteRestore(false);cancelRouteCheck();notifyAll();
        cancelPendingCall();
        assistantStart.invalidate(); recordProduct(AssistantProduct.Code.STOPPED);
        AssistPairing.INSTANCE.invalidate();
        disableAssist();
        assist.peer(null, null);
        disableAgent();
        authority.stop();
        checklist.stop();
        cancelIdentityCreation();
        if (stopRequested) return;
        stopRequested = true;
        stopMyGlassWorker();
        inbound.stopLocal();
        outbound.clear();
        if (peer == null || epoch == null) {
            shutdownEndpoint(generation);
        } else {
            enqueueFirst(new Outbound("mode.stop", emptyPayload(), true));
            publish("STOP requested / pair retained");
            startStopWatchdog(generation);
        }
    }

    private void startStopWatchdog(final long token) {
        final Socket target = peer;
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(2000L); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (stopRequested && isCurrent(token)
                        && (peer == target || peer == null)) shutdownEndpoint(token);
            }
        }, "pixel-stop-watchdog").start();
    }

    private synchronized void cancelAssistWork() {
        cancelTalkWork();
        if (assistUpstream != null) assistUpstream.cancel();
        assistUpstream = null;
        if (assistId != null) { transfer.release(assistId); assist.cancel(assistId); }
        assistId = null;
        synchronized (outbound) {
            Iterator<Outbound> it = outbound.iterator();
            while (it.hasNext()) if (it.next().type.startsWith("assist.")) it.remove();
        }
    }
    /** Photo cancellation revokes only its request, never the persistent relay. */
    private synchronized void cancelPhotoAssist() {
        if(assistPhoto==null)return;
        assistPhoto.abandon();assistPhoto=null;assistPhotoClient=null;assistPhotoTalkId=null;
        if(assistId!=null){transfer.release(assistId);assist.cancel(assistId);}
        assistId=null;assistWorker=false;
        synchronized(outbound){Iterator<Outbound> it=outbound.iterator();while(it.hasNext())if(it.next().type.startsWith("assist."))it.remove();}
    }
    private synchronized void cancelTalkWork() {
        cancelPhotoAssist();
        GatewayTalkClient old=talkClient;
        talkClient=null;talkId=null;talkPeer=null;talkRevision++;
        notifyAll();
        if(old!=null)old.cancel();
        synchronized(outbound){
            Iterator<Outbound> it=outbound.iterator();while(it.hasNext())if(it.next().type.startsWith("talk."))it.remove();
        }
    }
    /** Local protocol/relay failure still needs a terminal frame on the live Glass link.
     * Owner Stop and peer loss instead use cancelTalkWork() for immediate revocation.
     */
    private synchronized void failTalkLocally(String cause) {
        GatewayTalkClient client=talkClient;
        String id=talkId;Socket owner=talkPeer;long revision=talkRevision;
        if(client==null||id==null||owner==null||!talkCurrent(revision,owner,id,client))return;
        Log.i("PixelTalkDiag","LINK_TALK_LOCAL_FAIL="+cause);
        client.cancel();
        finishTalk(revision,owner,id,client,"TALK_FAILED");
    }
    private synchronized void completeTalkWrite(Outbound message,Socket connected) {
        if ("talk.error".equals(message.type) && "TALK_CLOSED".equals(message.payload.get("code"))
                && talkPeer == connected && talkId != null
                && talkId.equals(message.payload.get("request_id"))) {
            talkId = null; talkPeer = null; talkRevision++; assistStatus();
        }
    }
    private synchronized void finishTalk(long revision,Socket owner,String id,GatewayTalkClient client,String code) {
        if(!talkCurrent(revision,owner,id,client))return;
        cancelPhotoAssist();
        if(!"TALK_CLOSED".equals(code)){
            synchronized(outbound){
                Iterator<Outbound> it=outbound.iterator();
                while(it.hasNext())if(it.next().type.startsWith("talk."))it.remove();
            }
        }
        boolean queued=enqueueTalk("talk.error",lab.franziska.protocol.AssistWire.payload("request_id",id,"code",code));
        // enqueueTalk may wait and release this monitor; Stop/new work can win.
        if(!talkCurrent(revision,owner,id,client))return;
        talkClient=null;
        if(!"TALK_CLOSED".equals(code)||!queued){
            talkId=null;talkPeer=null;talkRevision++;assistStatus();
        }
    }
    /** Called only by GatewayTalkClient.ended, after its credential release.
     * Never clear a newer worker, restore revoked authority, or reuse an old ID.
     */
    private synchronized void releasedTalk(long revision,Socket owner,String id,GatewayTalkClient client,String code) {
        if(talkCleanupClient!=client||talkCleanupRevision!=revision||talkCleanupPeer!=owner)return;
        talkCleanupClient=null;talkCleanupPeer=null;
        finishTalk(revision,owner,id,client,code);
        // Recompute from current peer/authority, not the cancelled worker's state.
        assistStatus();
    }
    private synchronized boolean enqueueTalk(String type,Map<String,Object> payload) {
        if(!running||stopRequested||peer==null||talkPeer!=peer||talkId==null
                ||!talkId.equals(payload.get("request_id")))return false;
        if("talk.text".equals(type))for(Outbound queued:outbound)
            if("talk.text".equals(queued.type)&&lab.franziska.protocol.TalkWire.sameTextSlot(payload,queued.payload))outbound.remove(queued);
        Outbound item=new Outbound(type,payload,false);
        long queuedAt=SystemClock.elapsedRealtime();
        long deadline=SystemClock.elapsedRealtime()+TALK_QUEUE_WAIT_MS;
        while(!outbound.offerLast(item)){
            long remaining=deadline-SystemClock.elapsedRealtime();
            if(remaining<=0){Log.i("PixelTalkDiag","LINK_TALK_QUEUE_DEADLINE");closePeer(peer);return false;}
            try{wait(remaining);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
            if(!running||stopRequested||peer==null||talkPeer!=peer||talkId==null
                    ||!talkId.equals(payload.get("request_id")))return false;
        }
        if("talk.audio".equals(type)){
            int index=((Number)payload.get("index")).intValue();
            if(index%5==0)Log.i("PixelTalkDiag","GLASS_OUTPUT_QUEUED_INDEX="+index
                    +" QUEUE_DEPTH="+outbound.size()+" WAIT_MS="+(SystemClock.elapsedRealtime()-queuedAt));
        }
        return true;
    }
    private synchronized void handleTalk(lab.franziska.protocol.Protocol.Envelope e) {
        if(!running||stopRequested||!assist.capable()||!"glass".equals(e.sourceId)||peer==null)return;
        final String id=(String)e.payload.get("request_id");
        if("talk.stop".equals(e.type)){
            if(id.equals(talkId)&&peer==talkPeer){Log.i("PixelTalkDiag","GLASS_STOP");cancelTalkWork();assistStatus();}return;
        }
        if("talk.start".equals(e.type)){
            final boolean textV2=lab.franziska.protocol.TalkWire.textV2(e.payload);
            if(talkId!=null||talkCleanupClient!=null||assistId!=null||assistWorker)return;
            final long revision=++talkRevision;
            final Socket owner=peer;
            talkId=id;talkPeer=owner;talkInputIndex=0;talkOutputIndex=0;
            Log.i("PixelTalkDiag","GLASS_START");
            assistStatus();
            final String route=getSharedPreferences("assist-private",MODE_PRIVATE).getString("route","");
            new Thread(new Runnable(){public void run(){
                final GatewayTalkClient client;
                try{client=new GatewayTalkClient(GatewayAndroidCredentials.open(LinkService.this));}
                catch(Exception failure){synchronized(LinkService.this){
                    if(talkRevision==revision&&id.equals(talkId)&&talkPeer==owner){
                        enqueueTalk("talk.error",lab.franziska.protocol.AssistWire.payload("request_id",id,"code","TALK_CREDENTIALS"));
                        talkId=null;talkPeer=null;talkRevision++;assistStatus();
                    }
                }return;}
                synchronized(LinkService.this){
                    if(talkRevision!=revision||!id.equals(talkId)||talkPeer!=owner||peer!=owner
                            ||!running||stopRequested||!assist.capable())return;
                    talkClient=client;
                    talkCleanupClient=client;talkCleanupRevision=revision;talkCleanupPeer=owner;
                }
                client.run(route,new GatewayTalkClient.TranscriptListener(){
                private long fallbackTextId;
                public void ready(){synchronized(LinkService.this){if(talkCurrent(revision,owner,id,client))enqueueTalk("talk.ready",lab.franziska.protocol.AssistWire.payload("request_id",id));}}
                public void audio(byte[] pcm){
                    try{synchronized(LinkService.this){
                        if(talkCurrent(revision,owner,id,client)){
                            java.util.ArrayList<String> parts=new java.util.ArrayList<String>(8);
                            for(int at=0;at<pcm.length;at+=2880){int count=Math.min(2880,pcm.length-at);
                                parts.add(lab.franziska.protocol.AssistWire.encode(pcm,at,count));}
                            enqueueTalk("talk.audio",lab.franziska.protocol.AssistWire.payload(
                                "request_id",id,"index",talkOutputIndex++,"parts",parts));
                        }
                    }}finally{java.util.Arrays.fill(pcm,(byte)0);}
                }
                public void clear(){synchronized(LinkService.this){
                    if(talkCurrent(revision,owner,id,client))enqueueTalk("talk.clear",lab.franziska.protocol.AssistWire.payload("request_id",id));
                }}
                public void text(String role,String value){
                    // Routes with committed-text-only callbacks still get distinct final utterances.
                    text(role,value,"committed-"+(++fallbackTextId),true);
                }
                public void text(String role,String value,String utteranceId,boolean isFinal){synchronized(LinkService.this){
                    if(!talkCurrent(revision,owner,id,client))return;
                    Map<String,Object> payload=lab.franziska.protocol.AssistWire.payload("request_id",id,"role",role,"text",GatewayTalkText.displayText(value));
                    if(textV2){payload.put("utterance_id",utteranceId);payload.put("final",Boolean.valueOf(isFinal));}
                    enqueueTalk("talk.text",payload);
                }}
                public void camera(){synchronized(LinkService.this){
                    if(talkCurrent(revision,owner,id,client)){
                        Log.i("PixelTalkDiag","CAMERA_COMMAND");
                        enqueueTalk("talk.camera",lab.franziska.protocol.AssistWire.payload("request_id",id));
                    }
                }}
                public void ended(String code){releasedTalk(revision,owner,id,client,code);}
            });}},"pixel-talk-relay").start();
            return;
        }
        if("talk.flow".equals(e.type)&&id.equals(talkId)&&peer==talkPeer&&talkClient!=null){
            int released=((Number)e.payload.get("released_bytes")).intValue();
            if(!talkClient.offerOutputCredit(released)){
                failTalkLocally("OUTPUT_FLOW_REJECTED");
            }
            return;
        }
        if("talk.audio".equals(e.type)&&id.equals(talkId)&&peer==talkPeer&&talkClient!=null){
            int index=((Number)e.payload.get("index")).intValue();
            if(index!=talkInputIndex++){failTalkLocally("INPUT_SEQUENCE");return;}
            byte[] pcm;
            try{pcm=lab.franziska.protocol.AssistWire.decode((String)e.payload.get("data"));}
            catch(Exception invalid){failTalkLocally("INPUT_DECODE");return;}
            try{
                boolean offered;
                try{offered=talkClient.offer(pcm);}
                catch(Exception relayFailure){failTalkLocally("INPUT_RELAY");return;}
                if(!offered)failTalkLocally("INPUT_REJECTED");
                else if(index==0||index%10==0)Log.i("PixelTalkDiag","GLASS_INPUT_FRAME="+index);
            }finally{java.util.Arrays.fill(pcm,(byte)0);}
        }
    }
    private synchronized boolean talkCurrent(long revision,Socket owner,String id,GatewayTalkClient client){
        return talkRevision==revision&&running&&!stopRequested&&talkPeer==owner&&peer==owner
            &&id.equals(talkId)&&talkClient==client&&assist.capable();
    }
    private synchronized void disableAssist() { cancelAssistWork(); transfer.reset(epoch); assist.disable(); }
    private synchronized void assistStatus() {
        publish(linkStatus);
        if (!running || stopRequested || !assist.capable()) return;
        Map<String,Object> p = new LinkedHashMap<String,Object>();
        p.put("state", (assistWorker||talkId!=null||talkCleanupClient!=null) && "ready".equals(assist.state()) ? "busy" : assist.state()); p.put("detail", "");
        // Coalesce only assistant statuses, preserving existing checklist/Stop queues.
        synchronized (outbound) {
            Iterator<Outbound> it=outbound.iterator(); while(it.hasNext()) if("assist.status".equals(it.next().type))it.remove();
            enqueueAssist(new Outbound("assist.status", p, false));
        }
    }
    /** Release bytes/work, but retain the failed ID in authority until Glass acknowledges
     * the error with its matching cancel. Never manufacture ready before error delivery.
     * No new begin can replace this terminal state; Stop/disable/peer loss still revoke it.
     */
    private synchronized void failAssistTransfer(AssistantProduct.Code code) {
        if (assistId == null || !assist.current(assistTicket, peer, assistId)) return;
        recordProduct(code);
        if(assistPhoto!=null){assistPhoto.abandon();assistPhoto=null;assistPhotoClient=null;assistPhotoTalkId=null;assistWorker=false;}
        if (assistUpstream != null) assistUpstream.cancel();
        assistUpstream = null;
        transfer.release(assistId);
        assistId = null;
        assist.finish(false);
        assistStatus();
    }
    private synchronized void expireAssist() {
        String expired = transfer.expire(SystemClock.elapsedRealtime());
        if (expired != null) { failAssistTransfer(AssistantProduct.Code.TRANSFER_TIMEOUT); }
    }
    private synchronized void handleAssist(lab.franziska.protocol.Protocol.Envelope e) {
        final long receivedNow = SystemClock.elapsedRealtime();
        if (!running || stopRequested || !assist.capable() || !"glass".equals(e.sourceId)
                || epoch == null || !epoch.equals(e.sessionId)) return;
        try {
            if ("assist.cancel".equals(e.type)) {
                String cancelledId = lab.franziska.protocol.AssistWire.id(e.payload.get("request_id"));
                assist.cancel(cancelledId);
                if (cancelledId.equals(assistId)) { recordProduct(AssistantProduct.Code.CANCELLED); if(assistPhoto!=null)cancelPhotoAssist();else cancelAssistWork(); }
                assistStatus(); return;
            }
            // Late chunks/commits after a terminal failure must not erase its completed ID.
            if (assistId == null && !"assist.begin".equals(e.type)) return;
            if ("assist.begin".equals(e.type)) {
                if (assistWorker || assistId != null) return;
                boolean inTalk=talkId!=null;
                if(inTalk){
                    if(talkClient==null||talkPeer!=peer||!talkClient.photoReady()
                            ||GatewayJson.integer(e.payload.get("image_bytes"),0,lab.franziska.protocol.AssistWire.MAX_IMAGE)<=0
                            ||GatewayJson.integer(e.payload.get("audio_bytes"),0,lab.franziska.protocol.AssistWire.MAX_AUDIO)!=0)return;
                }else if(talkCleanupClient!=null)return;
                String id = lab.franziska.protocol.AssistWire.id(e.payload.get("request_id"));
                long ticket = assist.begin(id);
                if (ticket < 0) { assistStatus(); return; }
                assistId = id; assistTicket = ticket;
                if(inTalk){
                    assistPhoto=talkClient.reservePhoto(id,GatewayWebSocket.now()+GatewayTalkClient.PHOTO_MS);
                    assistPhotoClient=talkClient;assistPhotoTalkRevision=talkRevision;assistPhotoTalkId=talkId;
                }
                recordProduct(AssistantProduct.Code.RECEIVING);
                final long receiveTicket = ticket;
                final long receiveDeadline = receivedNow + 30000L;
                new Thread(new Runnable() { public void run() {
                    try { long left; while ((left = receiveDeadline - SystemClock.elapsedRealtime()) > 0) Thread.sleep(left); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    synchronized (LinkService.this) { if (assist.revision() == receiveTicket) expireAssist(); }
                }}, "pixel-assist-receive-deadline").start();
            }
            lab.franziska.protocol.AssistTransfer.Result result = transfer.accept(e, receivedNow,
                    assistId != null && assist.current(assistTicket, peer, assistId));
            if (result.kind == lab.franziska.protocol.AssistTransfer.Result.Kind.CANCELLED) {
                recordProduct(AssistantProduct.Code.CANCELLED); if(assistPhoto!=null)cancelPhotoAssist();else cancelAssistWork(); assistStatus(); return;
            }
            if (result.kind == lab.franziska.protocol.AssistTransfer.Result.Kind.COMPLETE) submitAssist(result);
            if ("assist.begin".equals(e.type)) assistStatus();
        } catch (Exception invalid) { failAssistTransfer(AssistantProduct.Code.TRANSFER_FAILED); }
    }
    private synchronized void submitAssist(final lab.franziska.protocol.AssistTransfer.Result media) {
        if(assistPhoto!=null){submitTalkPhoto(media);return;}
        final long ticket = assistTicket;
        final Socket owner = peer;
        final String id = media.requestId;
        final android.content.SharedPreferences p = getSharedPreferences("assist-private", MODE_PRIVATE);
        final String route = p.getString("route", "");
        final AssistGatewayUpstream request = new AssistGatewayUpstream(this);
        assistUpstream = request; assistWorker = true;
        recordProduct(AssistantProduct.Code.WAITING); publish(linkStatus);
        new Thread(new Runnable() { public void run() {
            String answer = null;
            final boolean photoOnly = media.image.length > 0 && media.audio.length == 0;
            String imageSha256 = null;
            try {
                if (photoOnly) imageSha256 = lab.franziska.protocol.AssistWire.sha256(media.image);
                synchronized (LinkService.this) { if (!assist.current(ticket, owner, id)) return; }
                answer = request.execute(route, "main", id, media.image, media.audio);
                if (answer == null || answer.length() == 0 || answer.length() > 4000) throw new IOException("Assistant response invalid");
                if (photoOnly && (!request.photoReceiptVerified(imageSha256) || imageSha256 == null
                        || !imageSha256.matches("[0-9a-f]{64}"))) throw new IOException("Photo receipt unverified");
            } catch (Exception failure) { answer = null; /* Fixed status only; never log server errors, credentials or media. */ }
            finally {
                media.wipe();
                synchronized (LinkService.this) {
                    if (assistUpstream == request || assistUpstream == null) {
                        assistWorker = false;
                        completeAssist(ticket, owner, id, answer, photoOnly, imageSha256, request);
                        assistStatus();
                    }
                }
            }
        }}, "pixel-assist-wss").start();
    }

    private synchronized void submitTalkPhoto(final lab.franziska.protocol.AssistTransfer.Result media) {
        final GatewayTalkClient.Photo photo=assistPhoto;
        final GatewayTalkClient client=assistPhotoClient;
        final long revision=assistPhotoTalkRevision,ticket=assistTicket;
        final String relayId=assistPhotoTalkId,id=media.requestId;
        final Socket owner=peer;
        if(!talkCurrent(revision,owner,relayId,client)||media.image.length==0||media.audio.length!=0){
            media.wipe();failAssistTransfer(AssistantProduct.Code.REQUEST_FAILED);return;
        }
        assistWorker=true;recordProduct(AssistantProduct.Code.WAITING);publish(linkStatus);
        new Thread(new Runnable(){public void run(){
            GatewayTalkClient.PhotoResult result=null;
            try{result=photo.execute(media.image);}catch(Exception failure){/* Fixed local failure status only. */}
            finally{
                media.wipe();
                synchronized(LinkService.this){
                    if(assistPhoto==photo){
                        assistWorker=false;
                        boolean current=talkCurrent(revision,owner,relayId,client)&&assist.current(ticket,owner,id);
                        if(!current||GatewayWebSocket.now()>=photo.expires)result=null;
                        if(result!=null){
                            Map<String,Object> payload=lab.franziska.protocol.AssistWire.payload("request_id",id,"text",result.text,
                                "photo_receipt","conversation_image","image_sha256",result.imageSha256);
                            if(!enqueueAssist(new Outbound("assist.reply",payload,false)))result=null;
                        }
                        // enqueueAssist may release this monitor while waiting for queue capacity.
                        if(assistPhoto==photo){
                            transfer.release(id);assistId=null;assistPhoto=null;assistPhotoClient=null;assistPhotoTalkId=null;
                            if(current)assist.finish(result!=null);
                            recordProduct(result!=null?AssistantProduct.Code.REPLY_QUEUED:AssistantProduct.Code.REQUEST_FAILED);
                            assistStatus();
                        }
                    }
                }
            }
        }},"pixel-talk-photo").start();
    }

    private synchronized void completeAssist(long ticket, Socket owner, String id, String answer,
            boolean photoOnly, String imageSha256, AssistGatewayUpstream request) {
        if (!running || stopRequested || peer != owner || !assist.current(ticket, owner, id)
                || assistUpstream != request) return;
        if (photoOnly && (!request.photoReceiptVerified(imageSha256) || imageSha256 == null
                || !imageSha256.matches("[0-9a-f]{64}"))) answer = null;
        if (answer != null) {
            Map<String,Object> payload = new LinkedHashMap<String,Object>();
            payload.put("request_id", id); payload.put("text", answer);
            if (photoOnly) {
                payload.put("photo_receipt", "conversation_image");
                payload.put("image_sha256", imageSha256);
            }
            if (!enqueueAssist(new Outbound("assist.reply", payload, false))) return;
        }
        transfer.release(id); assistId = null; assistUpstream = null; assist.finish(answer != null);
        recordProduct(answer != null ? AssistantProduct.Code.REPLY_QUEUED : AssistantProduct.Code.REQUEST_FAILED);
    }

    /** Never record success for a lost reply or silently drop a terminal status.
     * Saturation closes only this peer: Glass revokes capture/request on link loss.
     * No retry, extra queue, authority grant, or delivery acknowledgement is implied.
     */
    private synchronized boolean enqueueAssist(Outbound message) {
        if (!running || stopRequested || peer == null || message.generation != generation
                || !assist.writable(message.assistRevision, peer)) return false;
        if (outbound.offerLast(message)) return true;
        Log.i("PixelTalkDiag","LINK_ASSIST_QUEUE_FULL");
        closePeer(peer);
        return false;
    }

    private synchronized void enableAgentAsync() {
        if (agentEndpoint != null || agentLoaderActive) return;
        final long ticket = authority.beginEnable();
        agentLoading = true;
        agentLoaderActive = true;
        publish("Agent provisioning / endpoint OFF");
        new Thread(new Runnable() { public void run() {
            AgentResourceLoader.run(LinkService.this, new AgentResourceLoader.Job<AgentEndpoint>() {
                public boolean current() { return authority.currentEnable(ticket); }
                public AgentEndpoint open() throws Exception {
                    return AgentProvisioning.open(getFilesDir(), new AgentEndpoint.Bridge() {
                        public Object capture(long deadline) {
                            synchronized (LinkService.this) { return authority.capture(ticket, deadline); }
                        }
                        public Map<String,Object> invoke(Object request, String op, Map<String,Object> payload) throws Exception {
                            return agentInvoke((AgentAuthority.Request) request, op, payload);
                        }
                        public void failed() {
                            synchronized (LinkService.this) {
                                if (authority.listenerFailed(ticket)) {
                                    disableAgent();
                                    publish("Agent OFF / listener failed");
                                }
                            }
                        }
                    });
                }
                public void install(AgentEndpoint candidate) {
                    if (!authority.install(ticket)) throw new IllegalStateException("cancelled");
                    agentEndpoint = candidate;
                    agentLoading = false;
                    candidate.start();
                    publish("Agent HTTPS enabled / dedicated credential");
                }
                public void failed() {
                    disableAgent();
                    publish("Agent OFF / dedicated provisioning invalid or missing");
                }
                public void finished() { agentLoaderActive = false; }
            });
        }}, "pixel-agent-provisioning").start();
    }

    private synchronized void disableAgent() {
        authority.disable();
        agentLoading = false;
        synchronized (outbound) {
            authority.purge(outbound);
        }
        if (agentEndpoint != null) agentEndpoint.close();
        agentEndpoint = null;
    }

    private synchronized Map<String,Object> agentInvoke(AgentAuthority.Request request, String op, Map<String,Object> payload) throws Exception {
        AgentEndpoint.validate(op, payload);
        if (!authority.check(request, System.nanoTime())) throw new IOException("stale request");
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        boolean connected = authority.connected();
        if ("status".equals(op)) {
            result.put("connected", connected);
            result.put("paired", authority.paired());
            result.put("mode_state", !running || stopRequested ? "OFF" : connected ? "ACTIVE" : "DEGRADED");
            result.put("remote_ui_owner_enabled", authority.enabled());
            result.put("stop_latched", stopRequested || inbound.isStopped());
            result.put("protocol_version", Protocol.VERSION);
            result.put("capabilities", java.util.Arrays.asList("status", "ui.card", "ui.vector_scene", "mode.stop"));
            return result;
        }
        if ("stop".equals(op)) {
            requestLocalStop();
            result.put("accepted", true);
            result.put("delivery", "queued"); // local latch, NOT a Glass delivery acknowledgement
            return result;
        }
        boolean accepted = authority.enabled() && connected && !inbound.isStopped();
        if (accepted) {
            checklist.stop();
            accepted = enqueueLatest(new Outbound("show_card".equals(op) ? "ui.card" : "ui.vector_scene", payload, false, request));
        }
        result.put("accepted", accepted);
        result.put("delivery", accepted ? "queued" : "rejected");
        return result;
    }

    private void revokePair() {
        setRouteRestore(false);cancelRouteCheck();
        assistantStart.invalidate(); recordProduct(AssistantProduct.Code.PAIR_REQUIRED);
        AssistPairing.INSTANCE.invalidate();
        authority.revoke();
        disableAgent();
        cancelIdentityCreation();
        shutdownEndpoint(generation);
        boolean removed = Provisioning.revoke(this);
        publish(removed
                ? "PAIR REVOKED / Pixel-local private files deleted / endpoint OFF"
                : "REVOKE FAILED / app-private credential deletion incomplete");
    }

    private synchronized Protocol.Envelope createEnvelope(
            String type, Map<String, Object> payload) {
        Protocol.Envelope envelope = new Protocol.Envelope();
        envelope.protocolVersion = Protocol.VERSION;
        envelope.sessionId = epoch;
        envelope.messageId = UUID.randomUUID().toString();
        envelope.type = type;
        envelope.sourceId = "pixel";
        envelope.modeId = type.startsWith("assist.") || type.startsWith("talk.") ? "assist" : "demo";
        envelope.sequence = ++sequence;
        envelope.monotonicTimestamp = SystemClock.elapsedRealtime();
        envelope.ttlMs = 5000L;
        envelope.idempotencyKey = envelope.messageId;
        envelope.payload = payload;
        return envelope;
    }

    private void writeDirect(long token, Socket connected, DataOutputStream output, String type,
                             Map<String, Object> payload) throws Exception {
        Protocol.Envelope envelope;
        synchronized (this) {
            if (!isOwned(token, connected) || stopRequested) throw new IOException("endpoint stopped");
            envelope = createEnvelope(type, payload);
        }
        Tls.write(output, Protocol.encode(envelope), connected,
                WRITE_OPERATION_DEADLINE_MS, "pixel-direct-write-deadline");
    }

    private void cancelIdentityCreation() {
        AppIdentityStore.LifecycleToken operation = identityToken;
        if (operation != null) AppIdentityStore.cancelIdentityCreation(operation);
    }

    private synchronized void updateMyGlassWorker() {
        if (!running || stopRequested || !MyGlassWorker.enabled(this)) {
            stopMyGlassWorker();
            return;
        }
        if (myGlassWorker != null) return;
        final long workerRevision=++myGlassRevision;
        myGlassWorker = new MyGlassWorker(this, new MyGlassWorker.Host() {
            public boolean active() {
                synchronized (LinkService.this) {
                    return running && !stopRequested && myGlassRevision==workerRevision
                            && MyGlassWorker.enabled(LinkService.this);
                }
            }
            public MyGlassWorker.State snapshot() {
                synchronized (LinkService.this) {
                    boolean connected=running && !stopRequested && authority.connected() && !inbound.isStopped();
                    String state=!connected?"offline":pendingCallId!=null?"call"
                            :(assistId!=null||talkId!=null)?"talk":"idle";
                    return new MyGlassWorker.State(connected,state);
                }
            }
            public String admitCard(MyGlassWire.Command command) {
                // setEnabled(false) and local admission share a lock: after
                // the disable action returns, no old worker can queue a card.
                synchronized (MyGlassWorker.class) { synchronized (LinkService.this) {
                    if (myGlassRevision!=workerRevision || !MyGlassWorker.enabled(LinkService.this))
                        return "glass_offline";
                    if (!running || stopRequested || !authority.connected() || inbound.isStopped()) return "glass_offline";
                    if (assistId!=null || talkId!=null) return "local_error";
                    synchronized (outbound) {
                        for (Outbound queued:outbound) if (isUi(queued.type)) return "local_error";
                        if (!outbound.offerLast(new Outbound("ui.card",command.cardPayload(),false)))
                            return "local_error";
                    }
                    publish("MyGlass: карточка принята Pixel в очередь; показ на Glass не подтверждён");
                    return null;
                }}
            }
            public String admitCall(MyGlassWire.Command command) {
                synchronized (MyGlassWorker.class) { synchronized (LinkService.this) {
                    if (myGlassRevision!=workerRevision || !MyGlassWorker.enabled(LinkService.this))
                        return "glass_offline";
                    return prepareCall(command.id, command.label, command.e164, command.expiresAt);
                }}
            }
            public void note(String safeStatus) {
                synchronized (LinkService.this) {
                    if (running && !stopRequested) publish(safeStatus);
                }
            }
        });
        myGlassWorker.start();
    }

    private synchronized void stopMyGlassWorker() {
        if (myGlassWorker != null) { myGlassRevision++; myGlassWorker.stop(); }
        myGlassWorker = null;
    }

    @Override public void onDestroy() {
        stopMyGlassWorker();
        AssistPairing.INSTANCE.invalidate();
        disableAgent();
        cancelIdentityCreation();
        shutdownEndpoint(generation);
        super.onDestroy();
    }

    private synchronized boolean enqueueLatest(Outbound message) {
        synchronized (outbound) {
            Iterator<Outbound> iterator = outbound.iterator();
            while (iterator.hasNext()) {
                Outbound queued = iterator.next();
                if (isUi(queued.type)) iterator.remove();
            }
            // Do not evict an admitted Assist reply/status (or priority control).
            if (!outbound.offerLast(message)) { Log.i("PixelTalkDiag","LINK_UI_QUEUE_FULL"); closePeer(peer); return false; }
            return true;
        }
    }

    private synchronized void enqueueFirst(Outbound message) {
        // Local Stop clears the queue before admission; ordinary ACKs must not
        // displace an Assist reply/status that is already waiting for the writer.
        if (!outbound.offerFirst(message)) { Log.i("PixelTalkDiag","LINK_CONTROL_QUEUE_FULL"); closePeer(peer); }
    }

    private synchronized void purgeTransientOutbound() {
        checklist.disconnected();
        synchronized (outbound) {
            Iterator<Outbound> iterator = outbound.iterator();
            while (iterator.hasNext()) {
                iterator.next(); iterator.remove();
            }
        }
    }

    private static boolean isUi(String type) {
        return "ui.card".equals(type) || "ui.vector_scene".equals(type);
    }

    private synchronized void rememberPing(String messageId) {
        if (pendingPings.size() >= 8) pendingPings.clear();
        pendingPings.put(messageId, Long.valueOf(SystemClock.elapsedRealtime()));
    }

    private synchronized void recordRtt(Protocol.Envelope ack) {
        Long sentAt = pendingPings.remove(String.valueOf(ack.payload.get("reply_to")));
        if (sentAt != null) {
            publish("SECURE LINK / RTT "
                    + (SystemClock.elapsedRealtime() - sentAt.longValue())
                    + " ms / live events " + events);
        }
    }

    private synchronized void clearPeerPingState() {
        pendingPings.clear();
    }

    private synchronized void queueChecklist() {
        Checklist.Snapshot snapshot = checklist.snapshot();
        if (!running || stopRequested || !snapshot.published) { publish(linkStatus); return; }
        checklist.queued(snapshot.revision);
        publish(enqueueLatest(new Outbound(snapshot))
                ? "Карточка в очереди / не подтверждено отображение владельцу"
                : "Карточка не передана / очередь занята");
    }

    private synchronized void recordProduct(AssistantProduct.Code code) {
        if (product == null) return;
        product.record(code);
        getSharedPreferences("assistant-status", MODE_PRIVATE).edit()
                .putString("last", product.last()).putString("previous", product.previous()).apply();
    }

    private synchronized void publish(String text) {
        linkStatus = text;
        Checklist.Snapshot snapshot = checklist.snapshot();
        Intent update = new Intent(STATUS);
        update.setPackage(getPackageName());
        update.putExtra("connection", AssistantProduct.connection(running, stopRequested, authority.connected(), pairLoading));
        update.putExtra("readiness", AssistantProduct.readiness(running, stopRequested, authority.connected(), assist.capable(), assistantStart.phase(), assist.state()));
        update.putExtra("route_check",routePreparation.status(SystemClock.elapsedRealtime()));
        update.putExtra("route_check_pending",routePreparation.checking());
        update.putExtra("last_attempt", product.last());
        update.putExtra("previous_attempt", product.previous());
        update.putExtra("setup_needed", product.setupNeeded());
        update.putExtra("start_pending", assistantStart.pending());
        update.putExtra("conversation_enabled", assistantStart.phase() == AssistantStart.Phase.ENABLED);
        update.putExtra("status", text + "\nAgent HTTPS: " + (authority.enabled() ? "ON / isolated bridge only" : agentLoading ? "STARTING / not enabled" : "OFF") + " / sensors OFF\nAssistant outbound: " + assist.state());
        update.putExtra("call_state", callState);
        update.putExtra("currentstep", snapshot.index + 1);
        update.putExtra("total", snapshot.total);
        update.putExtra("completedcount", snapshot.completed);
        update.putExtra("title", snapshot.title);
        update.putExtra("currenttext", snapshot.text);
        update.putExtra("queued", snapshot.queued);
        update.putExtra("display_status",displayStatus);
        update.putExtra("display_owner",displayOwner);
        update.putExtra("display_json",displayJson);
        update.putExtra("display_active",displaySender.active());
        update.putExtra("display_capable",displayCapable);
        update.putExtra("received", snapshot.received);
        update.putExtra("active", running && !stopRequested);
        update.putExtra("events", events);
        update.putExtra("test_nonce", lastCardNonce);
        sendBroadcast(update);
        if (running || pairLoading || identityCreating) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.notify(7, notification(AssistantProduct.connection(running, stopRequested, authority.connected(), pairLoading)));
        }
    }

    private void publishIdentity(final AppIdentityStore.LifecycleToken operation,
                                 final String text) {
        AppIdentityStore.runIfCurrent(operation, new Runnable() {
            public void run() {
                if (identityToken == operation && !stopRequested) publish(text);
            }
        });
    }

    private void closePeer() { closePeer(peer); }

    private synchronized void closePeer(Socket target) {
        try { if (target != null) target.close(); } catch (IOException ignored) {}
        if (peer == target) {
            displayReset();
            cancelPendingCall();
            callPeer = null; callPeerId = null;
            if (target != null && !stopRequested) recordProduct(AssistantProduct.Code.DISCONNECTED);
            cancelAssistWork(); assist.peer(null, null); transfer.reset(null); peer = null; authority.losePeer(target); publish(linkStatus); }
    }

    private void shutdownEndpoint(long token) {
        Socket connected;
        ServerSocket listener;
        synchronized (this) {
            if (token != generation) return;
            if (assistantStart.pending() || assistId != null) {
                if (!stopRequested) recordProduct(AssistantProduct.Code.INTERRUPTED);
            }
            cancelRouteCheck();notifyAll();
            assistantStart.invalidate();
            AssistPairing.INSTANCE.invalidate();
            checklist.stop();
            cancelPendingCall();
            callPeer = null; callPeerId = null;
            inbound.stopLocal();
            running = false;
            stopMyGlassWorker();
            pairLoading = false;
            disableAssist();
            assist.peer(null, null);
            disableAgent();
            authority.shutdown();
            generation++;
            connected = peer;
            displayReset();
            peer = null;
            listener = server;
            server = null;
            outbound.clear();
            pendingPings.clear();
        }
        try { if (connected != null) connected.close(); } catch (IOException ignored) {}
        try { if (listener != null) listener.close(); } catch (IOException ignored) {}
        synchronized (this) {
            if (generation != token + 1 || running) return;
            publish("OFF / публикация остановлена / сопряжение сохранено");
            stopForeground(true);
            stopSelf();
        }
    }

    private synchronized boolean ownServer(long token, ServerSocket listener) {
        if (!running || stopRequested || token != generation) {
            try { listener.close(); } catch (IOException ignored) {}
            return false;
        }
        server = listener;
        return true;
    }

    private synchronized boolean ownPeer(long token, Socket connected) {
        if (!running || stopRequested || token != generation) {
            try { connected.close(); } catch (IOException ignored) {}
            return false;
        }
        peer = connected;
        authority.ownPeer(token, connected);
        return true;
    }

    private synchronized boolean isOwned(long token, Socket connected) {
        return running && token == generation && peer == connected;
    }

    private synchronized boolean isCurrent(long token) {
        return running && token == generation;
    }

    private Map<String, Object> cardPayload() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("title", "Franziska Lab");
        ArrayList<Object> lines = new ArrayList<Object>();
        lines.add("Франциска: связь установлена");
        lines.add("TLS 1.2+ / expected peer");
        lastCardNonce = UUID.randomUUID().toString().substring(0, 8);
        lines.add("Test: " + lastCardNonce);
        payload.put("lines", lines);
        return payload;
    }

    private Map<String, Object> vectorPayload() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        ArrayList<Object> shapes = new ArrayList<Object>();
        shapes.add(shape("arrow", 40, 180, 560, 180));
        shapes.add(shape("line", 100, 260, 500, 90));
        payload.put("shapes", shapes);
        return payload;
    }

    private Map<String, Object> shape(String kind, int x1, int y1, int x2, int y2) {
        LinkedHashMap<String, Object> shape = new LinkedHashMap<String, Object>();
        shape.put("kind", kind);
        ArrayList<Object> points = new ArrayList<Object>();
        points.add(point(x1, y1));
        points.add(point(x2, y2));
        shape.put("points", points);
        return shape;
    }

    private List<Object> point(int x, int y) {
        ArrayList<Object> point = new ArrayList<Object>();
        point.add(Long.valueOf(x));
        point.add(Long.valueOf(y));
        return point;
    }

    private static Map<String, Object> ackPayload(String messageId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("reply_to", messageId);
        return payload;
    }

    private static Map<String, Object> emptyPayload() {
        return new LinkedHashMap<String, Object>();
    }

    private static String read(File file) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), Protocol.UTF8));
        try { return reader.readLine(); } finally { reader.close(); }
    }

    private Notification notification(String text) {
        MyGlassRouteStore.Route route=MyGlassRouteStore.pending(this);
        Intent activity = new Intent(this, route==null?BridgeActivity.class:MyGlassSetupActivity.class);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pending = PendingIntent.getActivity(this, 0, activity, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setContentTitle(route==null?"Franziska Pixel Bridge":"Франциска · маршрут на Pixel")
                .setContentText(route==null?text:"Пункт назначения ждёт вашего подтверждения")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private final class Outbound implements AgentAuthority.Work {
        public AgentAuthority.Request agentRequest() { return agentRequest; }
        final long assistRevision;
        final String type;
        final Map<String, Object> payload;
        final boolean closeAfterWrite;
        final long generation;
        final long checklistRevision;
        final Socket remotePeer;
        final AgentAuthority.Request agentRequest;
        final long createdAt = SystemClock.elapsedRealtime();

        Outbound(Checklist.Snapshot snapshot) {
            this.assistRevision = -1;
            this.agentRequest = null;
            this.remotePeer = null;
            this.type = "ui.card";
            this.payload = snapshot.payload();
            this.closeAfterWrite = false;
            this.generation = LinkService.this.generation;
            this.checklistRevision = snapshot.revision;
        }

        Outbound(String type, Map<String, Object> payload, boolean closeAfterWrite) {
            this(type, payload, closeAfterWrite, null);
        }

        Outbound(String type, Map<String, Object> payload, boolean closeAfterWrite, AgentAuthority.Request request) {
            // Photo begin/cancel changes Assist authority, not the live Talk audio sequence.
            this.assistRevision = type.startsWith("assist.") ? assist.revision() : -1;
            this.agentRequest = request;
            this.remotePeer = isUi(type) || type.startsWith("talk.") || type.startsWith("call.") || type.startsWith("display.") ? LinkService.this.peer : null;
            this.checklistRevision = -1L;
            this.type = type;
            this.payload = payload;
            this.closeAfterWrite = closeAfterWrite;
            this.generation = LinkService.this.generation;
        }
    }
}
