package lab.franziska.pixelbridge;

import android.content.Context;
import java.io.IOException;

/** Cancellation latch also covers credential loading before Gateway client construction. */
final class AssistGatewayUpstream implements AssistUpstream {
    private final Context context;
    private boolean cancelled, used;
    private GatewayAssistClient client;
    private String photoSha256;
    AssistGatewayUpstream(Context context) { this.context = context.getApplicationContext(); }
    public String execute(String url, String agentId, String requestId, byte[] jpeg, byte[] wav) throws Exception {
        synchronized (this) { if (cancelled || used) { cancel(); throw new IOException("Assistant cancelled or already used"); } used = true; }
        GatewayCredentials credentials = GatewayAndroidCredentials.open(context);
        synchronized (this) {
            if (cancelled || !credentials.isPaired(url)) throw new IOException("Assistant pairing required");
            photoSha256 = jpeg != null && wav != null && jpeg.length > 0 && wav.length == 0
                ? lab.franziska.protocol.AssistWire.sha256(jpeg) : null;
            client = new GatewayAssistClient(credentials);
        }
        return client.execute(url, agentId, requestId, jpeg, wav);
    }
    /** Local hash binds the typed ACK to the input, not to remote byte readback. */
    synchronized boolean photoReceiptVerified(String imageSha256) {
        return !cancelled && photoSha256 != null && photoSha256.equals(imageSha256)
            && imageSha256.matches("[0-9a-f]{64}") && client != null && client.photoReceiptVerified();
    }
    public synchronized void cancel() { cancelled = true; if (client != null) client.cancel(); }
}
