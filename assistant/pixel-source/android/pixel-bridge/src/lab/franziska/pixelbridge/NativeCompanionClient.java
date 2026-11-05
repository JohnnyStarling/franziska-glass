package lab.franziska.pixelbridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

/** Owner-selected, secure RFCOMM lane for stock XE24 local companion functions. */
final class NativeCompanionClient {
    interface Listener {
        void ready(Xe24Wire.Reply hello);
        void received(Xe24Wire.Reply reply);
        void ended(String category);
    }
    private static final UUID XE24_SERVICE = UUID.fromString("F15CC914-E4BC-45CE-9930-CB7695385850");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayBlockingQueue<byte[]> outgoing = new ArrayBlockingQueue<byte[]>(8);
    private final Listener listener;
    private volatile BluetoothSocket socket;
    private volatile Thread writerThread;
    private volatile boolean closed, ready;
    private Xe24Wire.Reply latestScreen;
    private boolean screenDeliveryPending;
    NativeCompanionClient(Listener listener) { this.listener = listener; }

    void connect(final BluetoothDevice device) {
        if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED)
            throw new IllegalArgumentException("Выберите сопряжённые Glass");
        new Thread(new Runnable() { public void run() { runConnection(device); } }, "xe24-native-companion").start();
    }
    boolean send(byte[] command) {
        return !closed && ready && outgoing.offer(command);
    }
    void close() {
        closed = true; ready = false;
        byte[] queued; while ((queued = outgoing.poll()) != null) Arrays.fill(queued, (byte) 0);
        synchronized (this) { latestScreen = null; screenDeliveryPending = false; }
        Thread writer = writerThread; if (writer != null) writer.interrupt();
        BluetoothSocket owned = socket;
        if (owned != null) try { owned.close(); } catch (IOException ignored) { }
    }
    private void runConnection(BluetoothDevice device) {
        BluetoothSocket opened = null;
        try {
            opened = device.createRfcommSocketToServiceRecord(XE24_SERVICE);
            socket = opened;
            final BluetoothSocket connecting = opened;
            final Thread watchdog = new Thread(new Runnable() { public void run() {
                try { Thread.sleep(8000L); } catch (InterruptedException done) { return; }
                if (!ready && !closed && socket == connecting) try { connecting.close(); } catch (IOException ignored) { }
            } }, "xe24-connect-deadline");
            watchdog.setDaemon(true); watchdog.start();
            opened.connect();
            if (closed) return;
            InputStream input = opened.getInputStream(); OutputStream output = opened.getOutputStream();
            byte[] first = Xe24Wire.read(input);
            if (first == null) throw new IOException("native handshake missing");
            final Xe24Wire.Reply hello = Xe24Wire.parse(first);
            Xe24Wire.write(output, Xe24Wire.handshake());
            Xe24Wire.write(output, Xe24Wire.infoRequest());
            ready = true; watchdog.interrupt();
            main.post(new Runnable() { public void run() { if (!closed) listener.ready(hello); } });
            final OutputStream writer = output;
            writerThread = new Thread(new Runnable() { public void run() {
                try {
                    while (!closed) {
                        byte[] item = outgoing.take();
                        if (closed) { Arrays.fill(item, (byte) 0); return; }
                        try { Xe24Wire.write(writer, item); }
                        finally { Arrays.fill(item, (byte) 0); }
                    }
                } catch (Exception failure) { if (!closed) failure("NATIVE_WRITE_FAILED"); close(); }
            } }, "xe24-native-writer");
            writerThread.start();
            while (!closed) {
                byte[] frame = Xe24Wire.read(input);
                if (frame == null) throw new IOException("native link closed");
                final Xe24Wire.Reply reply = Xe24Wire.parse(frame);
                if (reply.screen != null) deliverLatestScreen(reply);
                else main.post(new Runnable() { public void run() { if (!closed) listener.received(reply); } });
            }
        } catch (SecurityException denied) { failure("BLUETOOTH_PERMISSION"); }
        catch (Exception failure) { if (!closed) failure("NATIVE_LINK_FAILED"); }
        finally {
            close();
            if (opened != null) try { opened.close(); } catch (IOException ignored) { }
        }
    }
    private void failure(final String category) {
        main.post(new Runnable() { public void run() { listener.ended(category); } });
    }
    private void deliverLatestScreen(Xe24Wire.Reply frame) {
        synchronized (this) {
            latestScreen = frame;
            if (screenDeliveryPending) return;
            screenDeliveryPending = true;
        }
        main.postDelayed(new Runnable() { public void run() {
            Xe24Wire.Reply latest;
            synchronized (NativeCompanionClient.this) {
                latest = latestScreen; latestScreen = null; screenDeliveryPending = false;
            }
            if (!closed && latest != null) listener.received(latest);
        } }, 80L);
    }
}
