package lab.franziska.pixelbridge;

/** Ephemeral owner action and observation, never a media-start or persistent health claim. */
final class RoutePreparation {
    static final long FRESH_MS=30000L, SETTINGS_MS=120000L;
    private long revision, checkedAt=-1L, settingsAt;
    private boolean checking, success;
    private String pendingSettings;
    synchronized long begin() {
        if(checking)return -1L;
        checking=true;checkedAt=-1L;return ++revision;
    }
    synchronized boolean finish(long ticket,boolean authenticated,long now) {
        if(!checking||ticket!=revision)return false;
        checking=false;success=authenticated;checkedAt=now;return true;
    }
    synchronized boolean checking(){return checking;}
    synchronized boolean current(long ticket){return checking&&ticket==revision;}
    synchronized void invalidate(){revision++;checking=false;checkedAt=-1L;pendingSettings=null;}
    synchronized boolean arm(String nonce,long now) {
        if(nonce==null||!nonce.matches("[a-f0-9-]{36}"))return false;
        pendingSettings=nonce;settingsAt=now;return true;
    }
    synchronized boolean returned(String nonce,long now) {
        if(pendingSettings==null||!pendingSettings.equals(nonce))return false;
        pendingSettings=null;
        return now>=settingsAt&&now-settingsAt<=SETTINGS_MS;
    }
    synchronized String status(long now) {
        if(checking)return "OpenClaw: проверяем доступ · без записи";
        if(checkedAt<0L)return "OpenClaw: проверка ещё не выполнена";
        if(!success)return "OpenClaw: доступ не подтверждён. Проверьте интернет Pixel и доступность вашего совместимого Gateway; затем повторите проверку.";
        if(now<checkedAt||now-checkedAt>FRESH_MS)return "OpenClaw: прежняя проверка устарела · нажмите «Подготовить и проверить»";
        return "OpenClaw: доступ подтверждён не более 30 с назад · голос ещё не проверен";
    }
}
