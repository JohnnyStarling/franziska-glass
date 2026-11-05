package lab.franziska.pixelbridge;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local, owner-operated entry points. No notification is forwarded or call placed automatically. */
public final class PixelCapabilitiesActivity extends Activity {
    public static final String EXTRA_DIAL_NUMBER = "lab.franziska.pixelbridge.DIAL_NUMBER";
    private static final int CALL_PERMISSION_REQUEST = 19;
    private static final int CONTACTS_PERMISSION_REQUEST = 20;

    private TextView status;
    private LinearLayout messages, callTools;
    private ScrollView page;
    private View navigation;
    private CompanionBack back;
    private String section = CompanionDestination.MESSAGES;
    private EditText phone;
    private EditText callLabel, callNumber;
    private TextView callStatus, permissionStatus;
    private EditText contactQuery;
    private TextView contactPermissionStatus, contactStatus;
    private LinearLayout contactResults;
    // One active search and at most one queued replacement after cancellation.
    private final ThreadPoolExecutor contactExecutor = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private CancellationSignal contactSignal;
    private int contactGeneration;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (callStatus != null) callStatus.setText(intent.getStringExtra("call_state"));
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        back = new CompanionBack(this, new Runnable() {
            @Override public void run() {
                if (CompanionDestination.CALLS.equals(section)) openSection(CompanionDestination.MESSAGES);
            }
        });
        LinearLayout[] holder = new LinearLayout[1];
        ScrollView scroll = CompanionUi.screen(this, holder);
        page = scroll;
        LinearLayout root = holder[0];
        CompanionUi.eyebrow(this, root, "ФРАНЦИСКА / PIXEL");
        status = CompanionUi.text(this, root, "Ничего не отправлено и не запрашивалось", 14, CompanionUi.MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        root.addView(messages);
        CompanionUi.heading(this, messages, "Сообщения");
        LinearLayout telegram = CompanionUi.card(this, messages);
        CompanionUi.badge(this, telegram, "НА PIXEL");
        CompanionUi.section(this, telegram, "Продолжить в Telegram");
        CompanionUi.text(this, telegram, CompanionDestination.MESSAGE_LIMIT, 14, CompanionUi.MUTED);
        CompanionUi.button(this, telegram, "Открыть Telegram на Pixel", new View.OnClickListener() {
            @Override public void onClick(View view) { openTelegram(); }
        });
        LinearLayout original = CompanionUi.card(this, messages);
        CompanionUi.section(this, original, "Оригинал. Без сжатия.");
        CompanionUi.text(this, original,
                "Чат и отправку выбираете вы в Telegram. Открытие файла не означает отправку получателю.",
                14, CompanionUi.MUTED);
        CompanionUi.navigationRow(this, original, "media", "Передать оригинал",
                "Фото или видео · уже подготовленные на Pixel", new View.OnClickListener() {
            @Override public void onClick(View view) {
                startActivity(new Intent(PixelCapabilitiesActivity.this, OriginalShareActivity.class));
            }
        });
        CompanionUi.disclosure(this, original, "Оригинал ещё на Glass?",
                "Откройте Медиа и включите приём. Выберите файл в галерее Glass. "
                + "Когда оригинал появится на Pixel, откройте подготовленные файлы здесь. "
                + "Передача в Telegram требует вашего отдельного действия.");
        CompanionUi.section(this, messages, "Другие действия");
        CompanionUi.navigationRow(this, messages, "more", "Звонки и видеочерновик",
                "Отдельные действия владельца · без автоматической записи или звонка", new View.OnClickListener() {
            @Override public void onClick(View view) { openSection(CompanionDestination.CALLS); }
        });
        CompanionUi.navigationRow(this, messages, "more", "Настройки и диагностика",
                "Связь, Glass и доступ к Франциске", new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent intent = new Intent(PixelCapabilitiesActivity.this, BridgeActivity.class);
                intent.putExtra(CompanionDestination.EXTRA_SECTION, CompanionDestination.SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            }
        });
        callTools = new LinearLayout(this);
        callTools.setOrientation(LinearLayout.VERTICAL);
        root.addView(callTools);
        secondaryButton(this, callTools, "← К сообщениям", new View.OnClickListener() {
            @Override public void onClick(View view) { openSection(CompanionDestination.MESSAGES); }
        });
        CompanionUi.eyebrow(this, callTools, "ИНСТРУМЕНТЫ / ПО ВАШЕМУ ДЕЙСТВИЮ");
        CompanionUi.heading(this, callTools, "Звонки\nи черновики");

        LinearLayout calls = CompanionUi.card(this, callTools);
        CompanionUi.section(this, calls, "Номер на Pixel");
        CompanionUi.text(this, calls,
                "Откроется приложение телефона. Звонок — только после вашего действия в нём.",
                14, CompanionUi.MUTED);
        phone = new EditText(this);
        CompanionUi.fieldLabel(this, calls, phone, "Номер телефона");
        phone.setHint("Номер телефона");
        phone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        phone.setSingleLine(true);
        CompanionUi.input(this, phone);
        calls.addView(phone);
        secondaryButton(this, calls, "Открыть набор номера", new View.OnClickListener() {
            @Override public void onClick(View view) { openDialer(); }
        });

        LinearLayout glassCall = CompanionUi.card(this, callTools);
        CompanionUi.section(this, glassCall, "Запрос звонка на Glass");
        CompanionUi.text(this, glassCall,
                "Кнопка запросит показ имени и номера. Только физическое касание Glass отправит Pixel запрос сотового звонка. Дозвон не подтверждается.",
                14, CompanionUi.MUTED);
        callStatus = CompanionUi.text(this, glassCall, "Звонок не подготовлен", 14, CompanionUi.MUTED);
        callStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        permissionStatus = CompanionUi.text(this, glassCall, "Проверяем разрешение…", 14, CompanionUi.MUTED);
        secondaryButton(this, glassCall, "Разрешить сотовые звонки", new View.OnClickListener() {
            @Override public void onClick(View view) { requestCallPermission(); }
        });
        LinearLayout contacts = new LinearLayout(this);
        contacts.setOrientation(LinearLayout.VERTICAL);
        glassCall.addView(contacts);
        CompanionUi.section(this, contacts, "Контакт на Pixel");
        CompanionUi.text(this, contacts,
                "Поиск — только по кнопке. Выбор заполнит поля; звонок не начнётся.",
                14, CompanionUi.MUTED);
        CompanionUi.disclosure(this, contacts, "Границы поиска контактов",
                "Pixel проверит не более 24 совпадений и покажет до 8 сохранённых международных номеров. "
                + "Произнесённое на Glass имя пока не ищется. Номера не отправляются брокеру.");
        contactPermissionStatus = CompanionUi.text(this, contacts, "Проверяем доступ к контактам…", 14, CompanionUi.MUTED);
        contactStatus = CompanionUi.text(this, contacts, "Контакты не запрашивались", 14, CompanionUi.MUTED);
        contactStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        secondaryButton(this, contacts, "Разрешить поиск контактов", new View.OnClickListener() {
            @Override public void onClick(View view) { requestContactsPermission(); }
        });
        contactQuery = new EditText(this);
        CompanionUi.fieldLabel(this, contacts, contactQuery, "Имя контакта · минимум 3 буквы");
        contactQuery.setHint("Имя контакта · минимум 3 буквы");
        contactQuery.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        contactQuery.setSingleLine(true);
        CompanionUi.input(this, contactQuery);
        contacts.addView(contactQuery);
        contactQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable edited) {
                cancelContactSearch();
                contactStatus.setText("Нажмите «Найти», чтобы искать только это имя");
            }
        });
        secondaryButton(this, contacts, "Найти номера на Pixel", new View.OnClickListener() {
            @Override public void onClick(View view) { searchContacts(); }
        });
        contactResults = new LinearLayout(this);
        contactResults.setOrientation(LinearLayout.VERTICAL);
        contacts.addView(contactResults);
        if (!contactsDeclared()) {
            contacts.setVisibility(View.GONE);
            CompanionUi.text(this, glassCall,
                    "Поиск контактов не включён в этот выпуск. Введите имя и номер вручную; доступ к адресной книге не запрашивается.",
                    14, CompanionUi.MUTED);
        }
        callLabel = new EditText(this);
        CompanionUi.fieldLabel(this, glassCall, callLabel, "Имя на Glass · до 40 знаков");
        callLabel.setHint("Кому звонить · имя на Glass");
        callLabel.setSingleLine(true);
        CompanionUi.input(this, callLabel);
        glassCall.addView(callLabel);
        callNumber = new EditText(this);
        CompanionUi.fieldLabel(this, glassCall, callNumber, "Международный номер · + и 7–15 цифр");
        callNumber.setHint("+79991234567");
        callNumber.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        callNumber.setSingleLine(true);
        CompanionUi.input(this, callNumber);
        glassCall.addView(callNumber);
        secondaryButton(this, glassCall, "Показать запрос на Glass", new View.OnClickListener() {
            @Override public void onClick(View view) { prepareGlassCall(); }
        });
        LinearLayout video = CompanionUi.card(this, callTools);
        CompanionUi.section(this, video, "Видеочерновик");
        CompanionUi.text(this, video,
                "Запись до минуты со звуком — по отдельному касанию на следующем экране. Просмотр и удаление доступны; отправка в Telegram пока не подключена.",
                14, CompanionUi.MUTED);
        secondaryButton(this, video, "Записать или просмотреть черновик", new View.OnClickListener() {
            @Override public void onClick(View view) {
                startActivity(new Intent(PixelCapabilitiesActivity.this, CircleDraftActivity.class));
            }
        });

        navigation = CompanionUi.withNavigation(this, scroll, CompanionDestination.MESSAGES);
        setContentView(navigation);
        if (state == null) acceptSelection(getIntent());
        else {
            phone.setText(state.getString("phone_draft", ""));
            callLabel.setText(state.getString("call_label_draft", ""));
            callNumber.setText(state.getString("call_number_draft", ""));
            openSection(state.getString("section"));
        }
    }

    void openSection(String requested) {
        section = CompanionDestination.capabilities(requested, false);
        messages.setVisibility(CompanionDestination.MESSAGES.equals(section) ? View.VISIBLE : View.GONE);
        callTools.setVisibility(CompanionDestination.CALLS.equals(section) ? View.VISIBLE : View.GONE);
        CompanionUi.selectNavigation(navigation, CompanionDestination.MESSAGES);
        back.setEnabled(CompanionDestination.CALLS.equals(section));
        page.post(new Runnable() { public void run() { page.scrollTo(0, 0); } });
    }

    private void acceptSelection(Intent intent) {
        String requestedNumber = intent.getStringExtra(EXTRA_DIAL_NUMBER);
        if (requestedNumber != null) phone.setText(requestedNumber);
        openSection(CompanionDestination.capabilities(
                intent.getStringExtra(CompanionDestination.EXTRA_SECTION), requestedNumber != null));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptSelection(intent);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("section", section);
        state.putString("phone_draft", phone.getText().toString());
        state.putString("call_label_draft", callLabel.getText().toString());
        state.putString("call_number_draft", callNumber.getText().toString());
        // Search queries/results remain transient and are cleared on Pause as before.
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (!back.handleBack()) super.onBackPressed();
    }

    private android.widget.Button secondaryButton(Activity activity, LinearLayout parent,
            String text, View.OnClickListener action) {
        android.widget.Button button = CompanionUi.button(activity, parent, text, action);
        CompanionUi.secondary(button, activity);
        return button;
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(LinkService.STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updates, filter);
        refreshCallPermission();
        refreshContactsPermission();
    }

    @Override protected void onPause() {
        cancelContactSearch();
        contactQuery.setText("");
        contactStatus.setText("Контакты не запрашивались");
        unregisterReceiver(updates);
        super.onPause();
    }

    @Override protected void onDestroy() {
        back.destroy();
        contactExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refreshCallPermission() {
        permissionStatus.setText(checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                ? "Разрешение на сотовые звонки дано Pixel"
                : "Разрешение не дано; запрос на Glass не будет отправлен");
    }

    private void requestCallPermission() {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            refreshCallPermission(); return;
        }
        requestPermissions(new String[] { Manifest.permission.CALL_PHONE }, CALL_PERMISSION_REQUEST);
    }

    private void refreshContactsPermission() {
        contactPermissionStatus.setText(checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                ? "Доступ к контактам разрешён Pixel; поиск только по нажатию"
                : "Доступ к контактам не дан; поиск не выполняется");
    }

    private boolean contactsDeclared() {
        try {
            String[] permissions = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (permissions != null) for (String permission : permissions) {
                if (Manifest.permission.READ_CONTACTS.equals(permission)) return true;
            }
        } catch (PackageManager.NameNotFoundException unavailable) {
            return false;
        }
        return false;
    }

    private void requestContactsPermission() {
        if (!contactsDeclared()) return;
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            refreshContactsPermission(); return;
        }
        requestPermissions(new String[] { Manifest.permission.READ_CONTACTS }, CONTACTS_PERMISSION_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CALL_PERMISSION_REQUEST) refreshCallPermission();
        if (requestCode == CONTACTS_PERMISSION_REQUEST) refreshContactsPermission();
    }

    private void cancelContactSearch() {
        ++contactGeneration;
        if (contactSignal != null) contactSignal.cancel();
        contactSignal = null;
        if (contactResults != null) contactResults.removeAllViews();
    }

    private void searchContacts() {
        cancelContactSearch();
        if (!contactsDeclared()) return;
        final String term;
        try {
            term = PhoneContactChoices.query(contactQuery.getText().toString());
        } catch (IllegalArgumentException invalid) {
            contactStatus.setText("Введите имя: минимум 3 буквы, до 40 знаков, без спецсимволов"); return;
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            contactStatus.setText("Сначала разрешите доступ к контактам на Pixel"); return;
        }
        final int generation = contactGeneration;
        final CancellationSignal signal = new CancellationSignal();
        contactSignal = signal;
        contactStatus.setText("Ищем только «" + term + "» на Pixel…");
        contactExecutor.execute(new Runnable() {
            @Override public void run() {
                final List<PhoneContactChoices.Choice> choices;
                try {
                    choices = PhoneContactLookup.search(PixelCapabilitiesActivity.this, term, signal);
                } catch (SecurityException denied) {
                    postContactFailure(generation, "Доступ к контактам отозван; поиск не выполнен"); return;
                } catch (RuntimeException unavailable) {
                    postContactFailure(generation, "Поиск контактов недоступен на Pixel"); return;
                }
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (generation != contactGeneration || signal.isCanceled()) return;
                    contactSignal = null;
                    showContactChoices(choices);
                }});
            }
        });
    }

    private void postContactFailure(final int generation, final String message) {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (generation != contactGeneration) return;
            contactSignal = null;
            contactStatus.setText(message);
        }});
    }

    private void showContactChoices(List<PhoneContactChoices.Choice> choices) {
        contactResults.removeAllViews();
        if (choices.isEmpty()) {
            contactStatus.setText("Среди первых 24 совпадений нет номеров +E.164. Уточните имя или введите номер вручную."); return;
        }
        contactStatus.setText("Показано " + choices.size() + " из первых 24 совпадений. Выберите точное имя и номер.");
        for (final PhoneContactChoices.Choice choice : choices) {
            CompanionUi.button(this, contactResults, choice.label + " · " + choice.e164, new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                        cancelContactSearch();
                        contactStatus.setText("Доступ к контактам отозван; выберите номер вручную");
                        return;
                    }
                    callLabel.setText(choice.label);
                    callNumber.setText(choice.e164);
                    contactResults.removeAllViews();
                    contactStatus.setText("Выбрано: " + choice.label + " · " + choice.e164
                            + ". Проверьте поля и отдельно нажмите «Показать запрос на Glass».");
                }
            });
        }
    }

    private void prepareGlassCall() {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            callStatus.setText("Сначала разрешите звонки на Pixel; вызов не подготовлен"); return;
        }
        String label = callLabel.getText().toString().trim();
        String number = callNumber.getText().toString().trim();
        try {
            lab.franziska.protocol.CallWire.validate("call.prepare",
                    lab.franziska.protocol.CallWire.prepare("00000000-0000-4000-8000-000000000000", label, number));
        } catch (lab.franziska.protocol.ProtocolException invalid) {
            callStatus.setText("Введите имя до 40 знаков и E.164: + и 7–15 цифр"); return;
        }
        Intent request = new Intent(this, LinkService.class);
        request.setAction(LinkService.CALL_PREPARE);
        request.putExtra(LinkService.CALL_LABEL, label);
        request.putExtra(LinkService.CALL_E164, number);
        startService(request);
        callStatus.setText("Запрос передан локальному сервису; показ на Glass ещё не подтверждён");
    }

    private void openDialer() {
        String raw = phone.getText().toString().trim();
        String number = raw.replaceAll("[ ()-]", "");
        if (!number.matches("\\+?[0-9]{2,20}")) {
            status.setText("Введите номер: от 2 до 20 цифр, при необходимости с +.");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)));
            status.setText("Номер передан в системный набор; звонок ещё не совершён.");
        } catch (ActivityNotFoundException unavailable) {
            status.setText("Приложение телефона не найдено.");
        }
    }

    private void openTelegram() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("org.telegram.messenger");
        if (launch == null) { status.setText("Официальный Telegram не установлен на Pixel."); return; }
        try {
            startActivity(launch);
            status.setText("Telegram открыт на Pixel. Сообщение, оригинал и кружок не отправлены приложением Франциски.");
        } catch (RuntimeException unavailable) {
            status.setText("Не удалось открыть Telegram.");
        }
    }
}
