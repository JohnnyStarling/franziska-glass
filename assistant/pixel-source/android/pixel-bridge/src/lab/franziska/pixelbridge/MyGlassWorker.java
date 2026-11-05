package lab.franziska.pixelbridge;

import android.content.Context;
import java.util.Arrays;
import java.util.List;

/** Owner-enabled, bounded poller tied to the existing foreground Glass link. */
final class MyGlassWorker implements Runnable {
    interface Host {
        boolean active();
        State snapshot();
        /** Returns null only after local queue admission, never Glass optical delivery. */
        String admitCard(MyGlassWire.Command command);
        /** Returns null only after the exact call prompt was queued for Glass. */
        String admitCall(MyGlassWire.Command command);
        void note(String safeStatus);
    }
    static final class State {
        final boolean mtlsConnected;
        final String glassAppState;
        State(boolean mtls,String state) { mtlsConnected=mtls; glassAppState=state; }
    }
    private static final String PREFS="myglass-broker";
    private final Context context;
    private final Host host;
    private final Thread thread;
    private volatile boolean alive=true;
    MyGlassWorker(Context context,Host host) {
        this.context=context.getApplicationContext();
        this.host=host;
        this.thread=new Thread(this,"myglass-disabled-poll");
        thread.setDaemon(true);
    }
    static boolean enabled(Context context) {
        return false; // Broker is unconfigured, including when an old enabled preference exists.
    }
    static synchronized boolean setEnabled(Context context,boolean enabled) {
        if(enabled) return false;
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean("enabled",false).commit();
    }
    void start() { thread.start(); }
    void stop() { alive=false; thread.interrupt(); }
    private boolean current() { return alive&&enabled(context)&&host.active(); }

    @Override public void run() {
        char[] token=null;
        try {
            token=MyGlassCredentials.load(context);
            if(token==null) { host.note("MyGlass: токен Pixel не задан"); return; }
            MyGlassLedger ledger=MyGlassLedger.shared(context);
            MyGlassApi api=new MyGlassApi(token);
            int failures=0;
            while(current()) {
                try {
                    State state=host.snapshot();
                    // XE24 is a separate service; null is honest unknown, never a fabricated false.
                    api.heartbeat(null,state.mtlsConnected,state.glassAppState);
                    if(!current()) break;
                    // Navigation is Pixel-only; cards and calls need live, idle Glass.
                    boolean includeCards=cardLinkReady(state);
                    List<MyGlassWire.Command> claimed=api.claim(includeCards);
                    for(MyGlassWire.Command command:claimed) {
                        if(!current()) break;
                        process(api,ledger,command);
                    }
                    failures=0;
                    pause(15000);
                } catch(MyGlassApi.Failure rejected) {
                    if(rejected.httpStatus==401||rejected.httpStatus==403) {
                        host.note("MyGlass: доступ Pixel отклонён; проверьте токен");
                        break;
                    }
                    host.note("MyGlass: сервер временно недоступен");
                    pause(30000);
                } catch(Exception unavailable) {
                    host.note("MyGlass: брокер не настроен");
                    failures++;
                    pause(failures>=3?60000:30000);
                }
            }
        } catch(Exception storage) {
            host.note("MyGlass: закрытое хранилище недоступно; команды не исполняются");
        } finally {
            if(token!=null) Arrays.fill(token,'\0');
        }
    }

    private void process(MyGlassApi api,MyGlassLedger ledger,MyGlassWire.Command command) throws Exception {
        if(command.expiresAt<=System.currentTimeMillis()/1000L) {
            // Never actuate a destination or card after its server-declared deadline.
            api.ack(command,MyGlassLedger.FAILED,"invalid_payload");
            return;
        }
        if((MyGlassWire.SHOW_CARD.equals(command.kind) || MyGlassWire.CALL_PREPARE.equals(command.kind))
                && !cardLinkReady(host.snapshot())) {
            // The claim can race a link loss. Nothing was admitted or reserved locally.
            api.ack(command,"deferred","glass_offline");
            return;
        }
        MyGlassLedger.Record result=null;
        boolean deferred=false;
        // The preference write, old and replacement workers, reservation,
        // local admission and durable result share one ordering point. No
        // network ACK is made under this lock.
        synchronized(MyGlassWorker.class) {
            if(!current()) return;
            MyGlassLedger.Reservation reserved=ledger.reserve(command.id);
            result=reserved.record;
            if(!reserved.fresh && MyGlassLedger.RESERVED.equals(result.state)) {
                // Process death between reservation and result is ambiguous.
                result=ledger.finish(command.id,MyGlassLedger.FAILED,"local_error");
            } else if(reserved.fresh) {
                String failure;
                if(MyGlassWire.SHOW_CARD.equals(command.kind)) failure=host.admitCard(command);
                else if(MyGlassWire.CALL_PREPARE.equals(command.kind)) {
                    // Leave the entire 30-second physical-confirmation window
                    // inside the owner command deadline.
                    failure=command.expiresAt-System.currentTimeMillis()/1000L < 35L
                            ? "invalid_payload" : host.admitCall(command);
                } else if(MyGlassWire.NAVIGATE.equals(command.kind)) {
                    failure=MyGlassRouteStore.save(context,command)?null:"local_error";
                    if(failure==null) host.note("MyGlass: маршрут ждёт подтверждения на Pixel");
                } else failure="unsupported";
                if("glass_offline".equals(failure)
                        && (MyGlassWire.SHOW_CARD.equals(command.kind)
                                || MyGlassWire.CALL_PREPARE.equals(command.kind))) {
                    // Host guarantees no local admission on this return path.
                    ledger.releaseUnadmitted(command.id);
                    deferred=true;
                } else {
                    result=ledger.finish(command.id,
                            failure==null?MyGlassLedger.ACCEPTED:MyGlassLedger.FAILED,failure);
                }
            }
        }
        if(!current()) return;
        if(deferred) api.ack(command,"deferred","glass_offline");
        else api.ack(command,result.state,result.code);
    }

    private static boolean cardLinkReady(State state) {
        return state!=null && state.mtlsConnected && "idle".equals(state.glassAppState);
    }

    private void pause(long millis) {
        try { Thread.sleep(millis); }
        catch(InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }
}
