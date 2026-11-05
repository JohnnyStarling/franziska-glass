package lab.franziska.pixelbridge;

import android.app.Activity;
import android.os.Build;

/** UI-only Back ownership. Leave the platform in charge at the Activity root. */
final class CompanionBack {
    private final Runnable internalBack;
    private final PlatformBack platform;
    private boolean enabled, destroyed;

    CompanionBack(Activity activity, Runnable internalBack) {
        this.internalBack = internalBack;
        // API33 window types are confined to the guarded implementation, not Activity fields.
        platform = Build.VERSION.SDK_INT >= 33 ? new Api33(activity, new Runnable() {
            @Override public void run() { handleBack(); }
        }) : null;
    }

    void setEnabled(boolean enabled) {
        if (destroyed || this.enabled == enabled) return;
        this.enabled = enabled;
        if (platform != null) {
            if (enabled) platform.register();
            else platform.unregister();
        }
    }

    /** Shared with legacy onBackPressed; never re-dispatch to the platform callback. */
    boolean handleBack() {
        if (destroyed || !enabled) return false;
        internalBack.run();
        return true;
    }

    void destroy() {
        if (destroyed) return;
        destroyed = true;
        if (enabled && platform != null) platform.unregister();
        enabled = false;
    }

    private interface PlatformBack {
        void register();
        void unregister();
    }

    @android.annotation.TargetApi(33)
    private static final class Api33 implements PlatformBack {
        private final android.window.OnBackInvokedDispatcher dispatcher;
        private final android.window.OnBackInvokedCallback callback;

        Api33(Activity activity, final Runnable internalBack) {
            dispatcher = activity.getOnBackInvokedDispatcher();
            callback = new android.window.OnBackInvokedCallback() {
                @Override public void onBackInvoked() { internalBack.run(); }
            };
        }

        @Override public void register() {
            dispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        }

        @Override public void unregister() {
            dispatcher.unregisterOnBackInvokedCallback(callback);
        }
    }
}
