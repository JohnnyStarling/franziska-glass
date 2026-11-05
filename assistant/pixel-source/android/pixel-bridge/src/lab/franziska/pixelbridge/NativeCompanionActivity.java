package lab.franziska.pixelbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Set;

/** Explicit owner controls for stock XE24 local companion commands. */
public final class NativeCompanionActivity extends Activity {
    private NativeCompanionService service;
    private boolean active, bound, connected, casting;
    private TextView status, details, locationStatus, routeStatus;
    private ImageView screen;
    private EditText ssid, password, address, latitude, longitude;
    private Button refresh, cast, wifi, navigate, disconnectButton;
    private LinearLayout advancedOptions;
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((NativeCompanionService.LocalBinder) binder).service(); render();
        }
        public void onServiceDisconnected(ComponentName name) { service = null; connected = false; controls(); }
    };
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) { render(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout[] holder = new LinearLayout[1];
        ScrollView scroll = CompanionUi.screen(this, holder);
        LinearLayout root = holder[0];
        CompanionUi.eyebrow(this, root, "ВАШЕ УСТРОЙСТВО");
        CompanionUi.heading(this, root, "Мои Glass");
        LinearLayout connectionCard = CompanionUi.card(this, root);
        CompanionUi.badge(this, connectionCard, "BLUETOOTH · ОТДЕЛЬНЫЙ КАНАЛ");
        label(connectionCard, "Батарея, Wi‑Fi и экран. Разговор с Франциской — отдельно, в разделе «Разговор».", 14);
        status = label(connectionCard, "Не подключено", 18);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        details = label(connectionCard, "Батарея и версия: нет данных", 16);
        button(connectionCard, "Выбрать и подключить Glass", new View.OnClickListener() { public void onClick(View v) { choose(); } });
        disconnectButton=button(connectionCard, "Отключиться", new View.OnClickListener() { public void onClick(View v) { disconnect(); } });
        CompanionUi.secondary(disconnectButton,this);
        refresh = button(connectionCard, "Обновить состояние Glass", new View.OnClickListener() { public void onClick(View v) { send(Xe24Wire.infoRequest()); } });
        CompanionUi.quiet(refresh, this);
        LinearLayout displayCard=CompanionUi.card(this,root);
        label(displayCard, "Показ экрана", 19);
        label(displayCard, "Это дисплей Glass, а не изображение камеры. Показ закончится при выходе с этой страницы.",14);
        cast = button(displayCard, "Показать экран Glass", new View.OnClickListener() { public void onClick(View v) {
            if (service != null && service.screen(!casting)) {
                casting = !casting; cast.setText(casting ? "Остановить показ экрана" : "Показать экран Glass");
                if (!casting) screen.setImageDrawable(null);
            }
        } });
        CompanionUi.secondary(cast, this);
        screen = new ImageView(this); screen.setAdjustViewBounds(true); displayCard.addView(screen);
        CompanionUi.section(this, root, "Дополнительные функции");
        Button expand = button(root, "Показать настройки Wi‑Fi", new View.OnClickListener() {
            public void onClick(View v) {
                boolean open = advancedOptions.getVisibility() != View.VISIBLE;
                advancedOptions.setVisibility(open ? View.VISIBLE : View.GONE);
                ((Button) v).setText(open ? "Скрыть настройки Wi‑Fi" : "Показать настройки Wi‑Fi");
            }
        });
        CompanionUi.secondary(expand, this);
        advancedOptions = new LinearLayout(this);
        advancedOptions.setOrientation(LinearLayout.VERTICAL);
        advancedOptions.setVisibility(View.GONE);
        root.addView(advancedOptions);
        LinearLayout wifiCard=CompanionUi.card(this,advancedOptions);
        label(wifiCard, "Wi‑Fi очков", 19);
        label(wifiCard, "Сеть передаётся только по вашему нажатию. Пароль не сохраняется в приложении.",14);
        ssid = input(wifiCard, "Имя сети (SSID)", false);
        password = input(wifiCard, "Пароль WPA/WPA2; пусто для открытой сети", true);
        wifi = button(wifiCard, "Передать сеть на Glass", new View.OnClickListener() { public void onClick(View v) {
            try {
                String key = password.getText().toString();
                boolean sent = send(Xe24Wire.wifiRequest(ssid.getText().toString(), key.isEmpty() ? "NONE" : "WPA", key));
                if (sent) { password.setText(""); status.setText("Данные Wi‑Fi отправлены; подключение Glass пока не подтверждено"); }
            } catch (IllegalArgumentException invalid) { status.setText(invalid.getMessage()); }
        } });
        LinearLayout navigationCard=CompanionUi.card(this,root);
        label(navigationCard, "Маршрут на Pixel · точка на Glass", 19);
        label(navigationCard, "Найдите место по адресу в Яндекс Навигаторе или введите точные координаты. Повороты и ETA пока не передаются на Glass.",14);
        address = input(navigationCard, "Адрес или место", false);
        button(navigationCard, "Найти место в Яндекс Навигаторе", new View.OnClickListener() { public void onClick(View v) {
            String query = address.getText().toString().trim();
            if (query.isEmpty() || query.length() > 160) {
                routeStatus.setText("Введите адрес или название места до 160 символов"); return;
            }
            Uri search = Uri.parse("yandexnavi://map_search").buildUpon()
                    .appendQueryParameter("text", query).build();
            openYandex(search, "Поиск открыт в Яндекс Навигаторе; выберите место и маршрут там.");
        } });
        latitude = input(navigationCard, "Широта", false); longitude = input(navigationCard, "Долгота", false);
        latitude.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        longitude.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        button(navigationCard, "Построить маршрут в Яндекс Навигаторе", new View.OnClickListener() { public void onClick(View v) {
            try {
                double lat = Double.parseDouble(latitude.getText().toString());
                double lon = Double.parseDouble(longitude.getText().toString());
                openYandex(Uri.parse(YandexNavigatorRoute.to(lat, lon)),
                        "Яндекс Навигатор открыт. Выбор маршрута и начало ведения подтвердите в нём.");
            } catch (NumberFormatException invalid) {
                routeStatus.setText("Введите корректные широту и долготу");
            } catch (IllegalArgumentException invalid) {
                routeStatus.setText("Недопустимые координаты");
            }
        } });
        routeStatus = label(navigationCard, "Маршрут ещё не запрошен", 16);
        routeStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        label(navigationCard, "Без ключа интеграции Яндекс ограничивает переходы из других приложений: после пяти за сутки ссылка может открыться в браузере.", 14);
        navigate = button(navigationCard, "Отправить только точку на Glass · опытный режим", new View.OnClickListener() { public void onClick(View v) {
            try {
                double lat = Double.parseDouble(latitude.getText().toString());
                double lon = Double.parseDouble(longitude.getText().toString());
                if (send(Xe24Wire.navigationRequest(lat, lon)))
                    routeStatus.setText("Точка отправлена на Glass; показ на очках ещё не подтверждён. Это не маршрут.");
            } catch (RuntimeException invalid) { routeStatus.setText("Введите корректные широту и долготу"); }
        } });
        locationStatus = label(navigationCard, "Геолокация Pixel → Glass выключена", 16);
        button(navigationCard, "Разрешить геолокацию для Glass", new View.OnClickListener() { public void onClick(View v) {
            if (!connected || service == null) { status.setText("Сначала подключите Glass"); return; }
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 18);
            else enableLocation();
        } });
        Button stopLocation=button(navigationCard, "Остановить геолокацию Glass", new View.OnClickListener() { public void onClick(View v) {
            if (service != null) { service.disableLocation(); render(); }
        } });
        CompanionUi.secondary(stopLocation,this);
        CompanionUi.disclosure(this, root, "Границы подключения", "Показ экрана прекращается при выходе. Геолокация работает только пока включена связь и Glass запрашивают её. Сеть не сохраняется в приложении Pixel. Уведомления, SMS и звонки здесь не включаются.");
        setContentView(CompanionUi.withNavigation(this, scroll, "glass")); controls();
    }
    @Override protected void onResume() {
        super.onResume(); active = true;
        IntentFilter filter = new IntentFilter(NativeCompanionService.UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updates, filter);
        bindIfRunning();
    }
    @Override protected void onPause() {
        active = false;
        if (casting && service != null) service.screen(false);
        casting = false; cast.setText("Показать экран Glass"); screen.setImageDrawable(null);
        unregisterReceiver(updates);
        if (bound) { unbindService(connection); bound = false; }
        service = null; super.onPause();
    }
    private void bindIfRunning() {
        if (!bound) bound = bindService(new Intent(this, NativeCompanionService.class), connection, BIND_AUTO_CREATE);
    }
    private void choose() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.BLUETOOTH_CONNECT }, 17); return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) { status.setText("Включите Bluetooth на Pixel и Glass"); return; }
        final Set<BluetoothDevice> bonded;
        try { bonded = adapter.getBondedDevices(); }
        catch (SecurityException denied) { status.setText("Нет разрешения Bluetooth"); return; }
        if (bonded == null || bonded.isEmpty()) { status.setText("Нет сопряжённых устройств; сначала выполните системное сопряжение"); return; }
        final BluetoothDevice[] devices = bonded.toArray(new BluetoothDevice[0]);
        String[] choices = new String[devices.length];
        for (int i = 0; i < devices.length; i++) {
            String name = devices[i].getName();
            choices[i] = (name == null || name.isEmpty() ? "Без имени" : name) + " · " + devices[i].getAddress();
        }
        new AlertDialog.Builder(this).setTitle("Выберите именно ваши Glass")
                .setItems(choices, new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) {
                    status.setText("Подключаюсь к выбранным Glass…");
                    Intent start = new Intent(NativeCompanionActivity.this, NativeCompanionService.class)
                            .setAction(NativeCompanionService.CONNECT)
                            .putExtra(NativeCompanionService.DEVICE, devices[which].getAddress());
                    try {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(start); else startService(start);
                        bindIfRunning();
                    } catch (RuntimeException failure) { status.setText("Не удалось начать подключение"); }
                } }).show();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 17 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED && active) choose();
        else if (request == 17 && active) status.setText("Разрешение Bluetooth не дано");
        if (request == 18 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED && active) enableLocation();
        else if (request == 18 && active) status.setText("Геолокация не разрешена");
    }
    private void enableLocation() {
        if (service == null || !service.enableLocation()) status.setText("Не удалось включить геолокацию Glass");
        render();
    }
    private void render() {
        if (!active || service == null) return;
        connected = service.connected(); controls();
        if (!connected && casting) {
            casting = false; cast.setText("Показать экран Glass"); screen.setImageDrawable(null);
        }
        status.setText(service.state()); details.setText(service.details());
        locationStatus.setText(service.locationAllowed() ? "Геолокация Pixel → Glass включена" : "Геолокация Pixel → Glass выключена");
        if (casting) {
            byte[] bytes = service.takeScreen();
            if (bytes == null) return;
            if (!isImage(bytes)) { status.setText("Формат кадра Glass не распознан"); return; }
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 2048 || bounds.outHeight > 2048) {
                status.setText("Кадр Glass превысил допустимый размер"); return;
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap != null) screen.setImageBitmap(bitmap);
        }
    }
    private boolean isImage(byte[] data) {
        return data.length >= 8 && ((data[0] == (byte) 0xff && data[1] == (byte) 0xd8)
                || (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4e && data[3] == 0x47));
    }
    private void openYandex(Uri uri, String success) {
        Intent open = new Intent(Intent.ACTION_VIEW, uri);
        open.setPackage(YandexNavigatorRoute.PACKAGE);
        try { startActivity(open); routeStatus.setText(success); }
        catch (ActivityNotFoundException missing) { routeStatus.setText("Яндекс Навигатор не установлен на Pixel"); }
        catch (SecurityException denied) { routeStatus.setText("Pixel не разрешил открыть Яндекс Навигатор"); }
    }
    private boolean send(byte[] command) {
        if (!connected || service == null || !service.sendCommand(command)) { status.setText("Сначала подключите Glass; очередь недоступна"); return false; }
        return true;
    }
    private void disconnect() {
        if (service != null) service.stopCompanion();
        else startService(new Intent(this, NativeCompanionService.class).setAction(NativeCompanionService.DISCONNECT));
        connected = false; casting = false; if (cast != null) cast.setText("Показать экран Glass");
        if (screen != null) screen.setImageDrawable(null);
        controls();
    }
    private void controls() {
        if (disconnectButton != null) disconnectButton.setEnabled(connected);
        if (refresh != null) refresh.setEnabled(connected);
        if (cast != null) cast.setEnabled(connected);
        if (wifi != null) wifi.setEnabled(connected);
        if (navigate != null) navigate.setEnabled(connected);
    }
    private TextView label(LinearLayout root, String text, int size) {
        return CompanionUi.text(this,root,text,size,size>=18?CompanionUi.INK:CompanionUi.MUTED);
    }
    private EditText input(LinearLayout root, String hint, boolean secret) {
        EditText view = new EditText(this); view.setHint(hint);
        CompanionUi.fieldLabel(this, root, view, hint);
        if (secret) view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CompanionUi.input(this,view);
        root.addView(view); return view;
    }
    private Button button(LinearLayout root, String title, View.OnClickListener click) {
        return CompanionUi.button(this,root,title,click);
    }
}
