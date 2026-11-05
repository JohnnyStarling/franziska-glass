package lab.franziska.pixelbridge;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.view.WindowManager;
import android.view.View;
import android.widget.*;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import java.util.Arrays;

/** Private owner WSS pairing. Bootstrap is transient, never put in preferences or Intent. */
public final class AssistSetupActivity extends Activity {
    private EditText route, token;
    private TextView status, identity;
    private long pairingTicket = -1;
    private volatile GatewayAssistClient pairing;
    private volatile GatewayWebSocket routeProbe;
    private static volatile GatewayAssistClient activeUpgrade;
    private static volatile boolean upgradeInFlight;
    private static volatile String upgradeStatus="";
    private static volatile AssistSetupActivity visibleSetup;
    private volatile boolean closed;
    private boolean pairingBusy, clearingBusy;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        // Retire the old unconnected HTTP setup credential, never migrate it to Gateway auth.
        getSharedPreferences("assist-private",MODE_PRIVATE).edit().remove("token").apply();
        LinearLayout[] holder = new LinearLayout[1];
        ScrollView scroll = CompanionUi.screen(this, holder);
        LinearLayout layout = holder[0];
        CompanionUi.eyebrow(this, layout, "ДОСТУП К ФРАНЦИСКЕ");
        CompanionUi.heading(this, layout, "Подключение");
        LinearLayout intro = CompanionUi.card(this, layout);
        CompanionUi.section(this, intro, "Связь с OpenClaw");
        CompanionUi.text(this, intro, "Сначала сохраните адрес, затем подключите это устройство. Вопрос начнётся только после вашего действия на Glass.", 16, CompanionUi.MUTED);
        status = CompanionUi.text(this, intro, "Подключение пока не проверено", 16, CompanionUi.INK);
        if(!upgradeStatus.isEmpty())status.setText(upgradeStatus);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        LinearLayout address = CompanionUi.card(this, layout);
        CompanionUi.section(this, address, "1 · Адрес OpenClaw");
        CompanionUi.text(this, address, "Защищённый адрес wss:// без логина и пароля. Редактирование адреса выключает разговор.", 14, CompanionUi.MUTED);
        route=new EditText(this); CompanionUi.fieldLabel(this,address,route,"Адрес Gateway"); route.setSingleLine(true); route.setHint("wss://…"); route.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI); route.setText(getSharedPreferences("assist-private",MODE_PRIVATE).getString("route","")); route.setSaveEnabled(false); CompanionUi.input(this,route); address.addView(route);
        Button probe=button(address,"Проверить доступность адреса",new View.OnClickListener(){public void onClick(View v){probeRoute();}});
        CompanionUi.secondary(probe,this);
        button(address,"Сохранить адрес · разговор выключится",new View.OnClickListener(){public void onClick(View v){save();}});

        LinearLayout access = CompanionUi.card(this, layout);
        CompanionUi.section(this, access, "2 · Подключить устройство");
        CompanionUi.text(this, access, "Временный ключ нужен только при первом подключении. Он не сохраняется на Pixel.", 14, CompanionUi.MUTED);
        token=new EditText(this); CompanionUi.fieldLabel(this,access,token,"Временный ключ"); token.setSingleLine(true); token.setHint("Временный ключ · если потребуется"); token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); token.setSaveEnabled(false); CompanionUi.input(this,token);
        if(android.os.Build.VERSION.SDK_INT>=26) { token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); route.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); }
        access.addView(token);
        identity = CompanionUi.text(this,access,"Идентификатор устройства появится после подключения",14,CompanionUi.MUTED);
        route.setEnabled(!upgradeInFlight);
        route.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(upgradeInFlight)return;invalidatePairing();send(LinkService.ASSIST_CONFIGURE);}
            public void afterTextChanged(Editable e){}
        });
        button(access,"Подключить после одобрения",new View.OnClickListener(){public void onClick(View v){pair();}});

        LinearLayout conversation = CompanionUi.card(this, layout);
        CompanionUi.section(this, conversation, "3 · Разговор");
        CompanionUi.text(this, conversation, "Подготовка не запускает микрофон. После неё задайте вопрос на Glass.", 14, CompanionUi.MUTED);
        button(conversation,"Подготовить и проверить",new View.OnClickListener(){public void onClick(View v){if(save()){send(LinkService.PREPARE_CHECK);status.setText("Запуск запрошен. Статус — на главном экране; запись не начинается.");}}});
        Button disable=button(conversation,"Выключить разговор",new View.OnClickListener(){public void onClick(View v){invalidatePairing();send(LinkService.ASSIST_DISABLE);status.setText("Разговор выключен, подключение отменено");}});
        CompanionUi.secondary(disable,this);

        LinearLayout account = CompanionUi.card(this, layout);
        CompanionUi.section(this, account, "Управление доступом");
        CompanionUi.text(this, account, "Это отдельное устройство OpenClaw. Полный доступ запрашивается только для этого Pixel и требует одобрения на другом доверенном устройстве. Фото и голос могут сохраняться в истории OpenClaw и у провайдера модели. Камера и микрофон Pixel не используются.", 14, CompanionUi.MUTED);
        button(account,"Запросить полный доступ для этого Pixel",new View.OnClickListener(){public void onClick(View v){upgradeAccess();}});
        Button forget=button(account,"Забыть доступ на Pixel",new View.OnClickListener(){public void onClick(View v){
            if(clearingBusy)return;
            GatewayAssistClient pending=activeUpgrade;if(pending!=null)pending.cancel();
            invalidatePairing();clearingBusy=true;
            send(LinkService.ASSIST_DISABLE);
            new Thread(new Runnable(){public void run(){String text;try{GatewayAndroidCredentials.open(AssistSetupActivity.this).clearToken();text="Доступ на телефоне удалён. Отозвать устройство можно также в OpenClaw → Устройства.";}catch(Exception e){text="Не удалось удалить доступ";}final String result=text;runOnUiThread(new Runnable(){public void run(){clearingBusy=false;if(!closed)status.setText(result);}});}},"assist-clear-device-token").start();
        }});
        CompanionUi.stop(forget,this);
        setContentView(scroll);
        visibleSetup=this;
        if(!upgradeStatus.isEmpty())status.setText(upgradeStatus);
        route.setEnabled(!upgradeInFlight);
    }
    private boolean save(){
        if(pairingBusy||clearingBusy||upgradeInFlight||routeProbe!=null){status.setText("Предыдущая операция ещё заканчивается. Повторите через несколько секунд.");return false;}
        try { GatewayWebSocket.route(route.getText().toString()); }
        catch(Exception invalid){status.setText("Нужен адрес wss:// без пароля, параметров и фрагмента");return false;}
        invalidatePairing();
        send(LinkService.ASSIST_CONFIGURE);
        boolean ok=getSharedPreferences("assist-private",MODE_PRIVATE).edit().putString("route",route.getText().toString()).remove("token").commit();
        status.setText(ok?"Адрес сохранён, разговор выключен":"Не удалось сохранить адрес");return ok;
    }
    private void pair(){
        if(!save())return;
        final long ticket=AssistPairing.INSTANCE.begin();
        if(ticket<0){status.setText("Предыдущее подключение ещё заканчивается. Повторите через несколько секунд.");return;}
        pairingTicket=ticket;
        final String url=route.getText().toString();
        final char[] bootstrap=new char[token.length()];token.getText().getChars(0,token.length(),bootstrap,0);token.setText("");
        pairingBusy=true;status.setText("Подключаю это устройство. Фото и голос не отправляются.");
        new Thread(new Runnable(){public void run(){
            String result="Не удалось подключиться";
            GatewayCredentials credentials=null;
            GatewayAssistClient local=null;
            try{
                if(!AssistPairing.INSTANCE.current(ticket))return;
                credentials=GatewayAndroidCredentials.open(AssistSetupActivity.this);
                final String publicId=credentials.deviceId();
                runOnUiThread(new Runnable(){public void run(){if(!closed&&AssistPairing.INSTANCE.revisionMatches(ticket))identity.setText("Идентификатор устройства: "+publicId);}});
                local=new GatewayAssistClient(credentials);
                final GatewayAssistClient candidate=local;
                if(!AssistPairing.INSTANCE.install(ticket,new AssistPairing.Cancellation(){public void cancel(){candidate.cancel();}}))return;
                pairing=local;
                local.pair(url,bootstrap);
                result="Подключено. Вернитесь на главный экран и нажмите «Подготовить и проверить».";
            }catch(GatewayException e){result=e.pairingRequestId==null?pairingError(e.code):"Одобрите устройство в OpenClaw. Запрос: "+e.pairingRequestId;}
            catch(Exception e){result="Не удалось подключиться";}
            finally{
                Arrays.fill(bootstrap,'\0');if(local!=null)local.cancel();pairing=null;
                final boolean current=AssistPairing.INSTANCE.settle(ticket);
                // Credential epoch prevents an interrupted Gateway worker reinstalling a late token.
                if(!current&&credentials!=null){try{credentials.clearToken();}catch(Exception ignored){}}
                AssistPairing.INSTANCE.finish(ticket);
                final String text=current?result:"Подключение отменено. Для новой попытки нажмите «Подключить».";
                runOnUiThread(new Runnable(){public void run(){if(pairingTicket==ticket){pairingBusy=false;if(!closed&&(!current||AssistPairing.INSTANCE.revisionMatches(ticket)))status.setText(text);}}});
            }
        }},"assist-owner-pair").start();
    }
    private void upgradeAccess(){
        synchronized(AssistSetupActivity.class){
            if(upgradeInFlight||pairingBusy||clearingBusy||routeProbe!=null){status.setText("Предыдущая операция ещё заканчивается");return;}
            upgradeInFlight=true;
        }
        final String saved=getSharedPreferences("assist-private",MODE_PRIVATE).getString("route","");
        if(!saved.equals(route.getText().toString())){
            upgradeInFlight=false;status.setText("Сначала сохраните адрес подключения");return;
        }
        route.setEnabled(false);
        upgradeStatus="Проверяю устройство и запрашиваю повышение прав…";status.setText(upgradeStatus);
        final android.content.Context appContext=getApplicationContext();
        new Thread(new Runnable(){public void run(){
            GatewayAssistClient candidate=null;
            String result;
            try{
                GatewayCredentials credentials=GatewayAndroidCredentials.open(appContext);
                if(!credentials.isPaired(saved))throw new GatewayException("BOOTSTRAP_REQUIRED");
                final String exactDeviceId=credentials.deviceId();
                candidate=new GatewayAssistClient(credentials,new GatewayWebSocket(),GatewayAssistClient.UPGRADE_TIMEOUT_MS);
                activeUpgrade=candidate;
                String outcome=candidate.upgradeAccess(saved,new GatewayAssistClient.UpgradeListener(){public void pending(String requestId){
                    publishUpgradeStatus("Одобрите запрос "+requestId+" в OpenClaw → Устройства, сверив Pixel ID: "+exactDeviceId+". Дождитесь результата на телефоне.",false);
                }});
                result="already-approved".equals(outcome)?"Полный доступ для этого Pixel уже сохранён":"Полный доступ для этого Pixel сохранён. Существующая беседа Glass не сброшена.";
            }catch(GatewayException e){result=upgradeError(e.code);}
            catch(Exception e){result="Не удалось завершить запрос прав. Текущие данные подключения не удалены.";}
            finally{activeUpgrade=null;upgradeInFlight=false;}
            publishUpgradeStatus(result,true);
        }},"assist-owner-scope-upgrade").start();
    }
    private static String upgradeError(String code){
        if("BUSY".equals(code))return "Разговор или другой запрос ещё выполняется. Завершите его и повторите; доступ не изменён.";
        if("SCOPE_UPGRADE_REJECTED".equals(code))return "Повышение прав отклонено. Старые права сохранены.";
        if("SCOPE_UPGRADE_EXPIRED".equals(code)||"TIMEOUT".equals(code))return "Время одобрения истекло. Проверьте права устройства в OpenClaw; не сбрасывайте пару.";
        if("CREDENTIAL_STORAGE_FAILED".equals(code))return "Ответ об одобрении получен, но сохранить новый ключ не удалось. Проверьте устройство в OpenClaw; не сбрасывайте пару.";
        if("BOOTSTRAP_REQUIRED".equals(code))return "Pixel ещё не подключён к сохранённому адресу OpenClaw.";
        return "Повышение прав не завершено ("+code+"). Старые данные на Pixel не удалены; проверьте права устройства в OpenClaw.";
    }
    /** HTTPS/WSS upgrade only: no connect/auth frame, no identity, credential or media. */
    private void probeRoute() {
        if (routeProbe != null || pairingBusy || clearingBusy) return;
        final java.net.URI target;
        try { target = GatewayWebSocket.route(route.getText().toString()); }
        catch (Exception invalid) { status.setText("Укажите правильный адрес wss://"); return; }
        final GatewayWebSocket probe = new GatewayWebSocket(); routeProbe = probe;
        status.setText("Проверяю защищённый маршрут. Фото, голос и ключи не передаются.");
        final android.os.Handler timer = new android.os.Handler();
        final Runnable close = new Runnable(){public void run(){probe.close();}};
        timer.postDelayed(close, 10000L);
        new Thread(new Runnable(){public void run(){
            String result;
            try { probe.open(target, GatewayWebSocket.now()+10000L); result="Защищённая связь с OpenClaw доступна. Авторизация ещё не проверялась."; }
            catch (Exception unavailable) { result="Защищённый маршрут недоступен. Проверьте адрес и сеть Pixel; доступ к OpenClaw не запрашивался."; }
            finally { probe.close(); timer.removeCallbacks(close); }
            final String text=result;
            runOnUiThread(new Runnable(){public void run(){if(routeProbe==probe){routeProbe=null;if(!closed)status.setText(text);}}});
        }}, "assist-route-probe").start();
    }
    private static String pairingError(String code) {
        if ("TIMEOUT".equals(code) || "GATEWAY_FAILED".equals(code) || "CONNECTION_CLOSED".equals(code))
            return "Нет связи с OpenClaw. Проверьте адрес и доступ Pixel к этой сети. Код: " + code;
        if (code.startsWith("AUTH_") || "BOOTSTRAP_REQUIRED".equals(code) || "BOOTSTRAP_INVALID".equals(code))
            return "Нужен временный ключ подключения. Вставьте его только в это поле на телефоне, не в чат. Код: " + code;
        if ("SCOPE_MISMATCH".equals(code)) return "Права устройства не совпали с ожидаемыми. Требуются только чтение и отправка сообщений.";
        return "Подключение не завершено. Код: " + code;
    }
    private void invalidatePairing(){if(routeProbe!=null){routeProbe.close();routeProbe=null;}AssistPairing.INSTANCE.invalidate();}
    private static void publishUpgradeStatus(final String text,final boolean finished){
        upgradeStatus=text;
        final AssistSetupActivity current=visibleSetup;
        if(current!=null)current.runOnUiThread(new Runnable(){public void run(){
            if(visibleSetup==current&&!current.closed){current.status.setText(text);if(finished)current.route.setEnabled(true);}
        }});
    }
    private void send(String action){Intent intent=new Intent(this,LinkService.class).setAction(action);if(android.os.Build.VERSION.SDK_INT>=26&&(LinkService.ASSIST_START.equals(action)||LinkService.PREPARE_CHECK.equals(action)))startForegroundService(intent);else startService(intent);}
    private Button button(LinearLayout l,String text,View.OnClickListener listener){return CompanionUi.button(this,l,text,listener);}
    @Override protected void onPause(){closed=true;if(visibleSetup==this)visibleSetup=null;if(routeProbe!=null)routeProbe.close();invalidatePairing();token.setText("");super.onPause();finish();}
}
