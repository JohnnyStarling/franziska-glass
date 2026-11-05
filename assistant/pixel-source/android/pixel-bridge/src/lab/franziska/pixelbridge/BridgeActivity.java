package lab.franziska.pixelbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Everyday task destinations; live authority remains owned by LinkService. */
public final class BridgeActivity extends Activity {
    private TextView routeCheck, status, connection, readiness, attempt, attemptSummary, deviceStatus;
    private Button start, setup, compactStop;
    private static final int HOTSPOT_SETTINGS=419;
    private String hotspotNonce;
    private boolean hotspotReturned;
    private LinearLayout home, advanced, route, settings;
    private View navigation;
    private CompanionBack back;
    private String section = CompanionDestination.TALK, lastTask = CompanionDestination.TALK;
    private EditText routeQuery;
    private TextView routeStatus;
    private ScrollView page;
    private EditText title, steps;
    private SharedPreferences draft;
    private DisplayCalibrationPanel displayPanel;

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if(displayPanel!=null)displayPanel.update(intent);
            connection.setText(or(intent.getStringExtra("connection"), "Состояние Glass не получено"));
            readiness.setText(or(intent.getStringExtra("readiness"), "Состояние разговора не получено"));
            showAttempt(intent.getStringExtra("last_attempt"), intent.getStringExtra("previous_attempt"));
            boolean pending = intent.getBooleanExtra("start_pending", false);
            boolean checking=intent.getBooleanExtra("route_check_pending",false);
            start.setEnabled(!pending&&!checking);
            start.setText(pending||checking?"Проверяем подключение…":"Подготовить и проверить");
            routeCheck.setText(or(intent.getStringExtra("route_check"),"OpenClaw: проверка ещё не выполнена"));
            setup.setVisibility(intent.getBooleanExtra("setup_needed", false) ? View.VISIBLE : View.GONE);
            String nonce = intent.getStringExtra("test_nonce");
            String detail = or(intent.getStringExtra("status"), "Состояние не получено");
            deviceStatus.setText(detail);
            status.setText(detail
                    + "\n" + or(intent.getStringExtra("title"), "Чек-лист") + " · " + intent.getIntExtra("currentstep", 1)
                    + "/" + intent.getIntExtra("total", 0) + "\n" + or(intent.getStringExtra("currenttext"), "")
                    + "\nВыполнено: " + intent.getIntExtra("completedcount", 0)
                    + "\n" + (intent.getBooleanExtra("received", false)
                        ? "Карточка получена приложением Glass"
                        : intent.getBooleanExtra("queued", false) ? "Карточка в очереди" : "Карточка не подтверждена")
                    + "\nВидимость владельцу не проверена"
                    + "\nДиагностика: проверенных событий " + intent.getIntExtra("events", 0)
                    + (nonce != null && nonce.length() > 0 ? "\nДемо nonce: " + nonce : ""));
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        back = new CompanionBack(this, new Runnable() {
            @Override public void run() {
                String target = CompanionDestination.bridgeBack(section, lastTask);
                if (target != null) openSection(target);
            }
        });
        draft = getSharedPreferences("checklist-draft", MODE_PRIVATE);
        LinearLayout[] holder = new LinearLayout[1];
        page = CompanionUi.screen(this, holder);
        LinearLayout root = holder[0];
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        buildToolbar(root);
        root.addView(home);
        buildHome();

        advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        root.addView(advanced);
        buildTools();
        route = new LinearLayout(this);
        route.setOrientation(LinearLayout.VERTICAL);
        root.addView(route);
        buildRoute();
        settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        root.addView(settings);
        buildSettings();
        navigation = CompanionUi.withNavigation(this, page, CompanionDestination.TALK);
        setContentView(navigation);
        if (state != null) {
            lastTask = CompanionDestination.bridge(state.getString("last_task"));
            hotspotNonce=state.getString("hotspot_nonce");
            hotspotReturned=state.getBoolean("hotspot_returned",false);
            title.setText(state.getString("draft_title", title.getText().toString()));
            steps.setText(state.getString("draft_steps", steps.getText().toString()));
            routeQuery.setText(state.getString("route_query", routeQuery.getText().toString()));
        }
        openSection(state == null ? getIntent().getStringExtra(CompanionDestination.EXTRA_SECTION)
                : state.getString("section"));
    }

    private void buildToolbar(LinearLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout wordmark = new LinearLayout(this);
        wordmark.setOrientation(LinearLayout.VERTICAL);
        bar.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView name = CompanionUi.text(this, wordmark, "Франциска", 24, CompanionUi.INK);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        name.setLetterSpacing(-0.02f);
        name.setPadding(0, 0, 0, CompanionUi.dp(this, 2));
        TextView signature = CompanionUi.text(this, wordmark, "GLASS / PIXEL", 13, CompanionUi.FAINT);
        signature.setLetterSpacing(0.04f); signature.setPadding(0, 0, 0, 0);
        boolean largeText = getResources().getConfiguration().fontScale >= 1.3f;
        LinearLayout actions = bar;
        if (largeText) {
            bar.setOrientation(LinearLayout.VERTICAL);
            wordmark.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            actions = new LinearLayout(this);
            actions.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
            bar.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        }
        compactStop = addButton(actions, "Стоп", new View.OnClickListener() {
            public void onClick(View view) { send(LinkService.STOP); }
        });
        compactStop.setTextSize(14);
        compactStop.setContentDescription("Стоп — остановить разговор и его связь; сопряжение сохранится. Bluetooth и приём медиа не выключаются");
        compactStop.setLayoutParams(new LinearLayout.LayoutParams(largeText ? -2 : CompanionUi.dp(this, 80), -2));
        CompanionUi.stop(compactStop, this);
        compactStop.setVisibility(View.GONE);
        Button menu = addButton(actions, "⋮", new View.OnClickListener() {
            public void onClick(View view) { openSection(CompanionDestination.SETTINGS); }
        });
        menu.setContentDescription("Настройки и устройство");
        menu.setTextSize(24);
        menu.setMinHeight(CompanionUi.dp(this, 48));
        menu.setMinimumWidth(CompanionUi.dp(this, 48));
        menu.setLayoutParams(new LinearLayout.LayoutParams(CompanionUi.dp(this, 48), -2));
        CompanionUi.quiet(menu, this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = CompanionUi.dp(this, 16);
        root.addView(bar, lp);
    }

    private void buildConnection(LinearLayout link) {
        CompanionUi.eyebrow(this, link, "СВЯЗЬ С GLASS");
        connection = CompanionUi.text(this, link, "Состояние ещё не получено", 18, CompanionUi.INK);
        connection.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        readiness = CompanionUi.text(this, link, "Доступ к разговору пока не проверен", 14, CompanionUi.MUTED);
        readiness.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        attemptSummary = CompanionUi.text(this, link, "", 14, CompanionUi.MUTED);
        attemptSummary.setVisibility(View.GONE);
    }

    private void buildConnectionControls(LinearLayout link) {
        LinearLayout controls = new LinearLayout(this);
        boolean largeText = getResources().getConfiguration().fontScale >= 1.3f;
        controls.setOrientation(largeText ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        link.addView(controls);
        Button stopButton = addButton(controls, "Стоп", new View.OnClickListener() {
            public void onClick(View view) { send(LinkService.STOP); }
        });
        stopButton.setContentDescription("Стоп — остановить разговор и его связь; сопряжение сохранится. Bluetooth и приём медиа не выключаются");
        CompanionUi.stop(stopButton, this);
        Button refresh = addButton(controls, "Обновить", new View.OnClickListener() {
            public void onClick(View view) { send(LinkService.REFRESH); }
        });
        refresh.setContentDescription("Обновить состояние без запуска разговора");
        CompanionUi.quiet(refresh, this);
        if (!largeText) {
            LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, -2, 1f);
            stopLp.rightMargin = CompanionUi.dp(this, 8);
            stopLp.topMargin = CompanionUi.dp(this, 8);
            stopButton.setLayoutParams(stopLp);
            LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(0, -2, 1f);
            refreshLp.topMargin = CompanionUi.dp(this, 8);
            refresh.setLayoutParams(refreshLp);
        }
        setup = addButton(link, "Доступ к OpenClaw", new View.OnClickListener() {
            public void onClick(View view) { openSetup(); }
        });
        setup.setContentDescription("Открыть настройки доступа к OpenClaw; сопряжение Glass настраивается отдельно");
        CompanionUi.secondary(setup, this);
        setup.setVisibility(View.GONE);
    }

    private void buildHome() {
        CompanionUi.eyebrow(this, home, "РАЗГОВОР · БЕЗ ЛИШНЕГО ЭКРАНА");
        LinearLayout hero = CompanionUi.hero(this, home);
        CompanionUi.prismHeadline(this, hero, "Спросите\nФранциску.");
        buildConnection(hero);
        CompanionUi.text(this, hero, "Запись — только по вашему действию на Glass.", 14, CompanionUi.MUTED);
        routeCheck=CompanionUi.text(this,hero,"OpenClaw: проверка ещё не выполнена",14,CompanionUi.MUTED);
        routeCheck.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        start = addButton(hero, "Подготовить и проверить", new View.OnClickListener() {
            public void onClick(View view) { send(LinkService.PREPARE_CHECK); }
        });
        Button hotspot=addButton(hero,"Точка доступа Pixel…",new View.OnClickListener(){
            public void onClick(View view){openHotspot();}
        });
        CompanionUi.secondary(hotspot,this);
        CompanionUi.text(this,hero,"На улице: включите точку Pixel и мобильные данные. Адреса обновятся автоматически. Ваш совместимый OpenClaw Gateway должен быть доступен Pixel через выбранную вами защищённую сеть.",14,CompanionUi.MUTED);
        start.setMinHeight(CompanionUi.dp(this, 60));
        buildConnectionControls(hero);
        CompanionUi.disclosure(this, hero, "Как начать разговор",
                "Подготовьте связь, затем задайте вопрос касанием Glass. Подготовка не включает микрофон или камеру; кнопка проверяет доступ к OpenClaw без разговора. Включение точки — в системных настройках Pixel; после возврата проверка продолжится. "
                + "Состояние выше сообщает о связи и доступе, а не о записи. Стоп завершает разговор и его связь, сохраняя сопряжение. Bluetooth и приём медиа остаются отдельно.");
        CompanionUi.section(this, home, "Продолжить на Pixel");
        CompanionUi.navigationRow(this, home, "media", "Медиа с Glass",
                "Архив Glass · оригиналы и передача", new View.OnClickListener() {
            public void onClick(View view) { startActivity(new Intent(BridgeActivity.this, PhotoSyncActivity.class)); }
        });
        CompanionUi.navigationRow(this, home, "cards", "Мои карточки",
                "Заметки и события для вашего взгляда", new View.OnClickListener() {
            public void onClick(View view) { startActivity(new Intent(BridgeActivity.this, LocalMirrorActivity.class)); }
        });
        CompanionUi.disclosure(this, home, "Ваше пространство",
                "Оригиналы фото и видео остаются на Glass. Копирование архива — только по отдельному запросу; новые фото — по настройке синхронизации. Уведомления Pixel на Glass не пересылаются.");
    }

    private void buildRoute() {
        CompanionUi.eyebrow(this, route, "НОВОЕ НАПРАВЛЕНИЕ");
        heading(route, "Маршрут");
        LinearLayout card = CompanionUi.card(this, route);
        CompanionUi.badge(this, card, "НА PIXEL");
        section(card, "Куда отправимся?");
        label(card, "Найдите место в Яндекс Навигаторе. Начало ведения — в самом Навигаторе.");
        routeQuery = new EditText(this);
        CompanionUi.fieldLabel(this, card, routeQuery, "Адрес или название места");
        routeQuery.setHint("Например, парк или кафе");
        routeQuery.setSingleLine(true);
        routeQuery.setText(draft.getString("route_query", ""));
        CompanionUi.input(this, routeQuery);
        card.addView(routeQuery);
        CompanionUi.text(this, card, "На Glass пока нет поворотов и ETA.", 14, CompanionUi.MUTED);
        addButton(card, "Найти в Яндекс Навигаторе", new View.OnClickListener() {
            public void onClick(View view) { openNavigator(); }
        });
        routeStatus = CompanionUi.text(this, card, "Маршрут ещё не запрашивался", 14, CompanionUi.MUTED);
        routeStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout gate = CompanionUi.card(this, route);
        section(gate, "На Glass пока нет ведения");
        label(gate, CompanionDestination.ROUTE_LIMIT);
        CompanionUi.disclosure(this, gate, "Что доступно сейчас",
                "Открытие Навигатора на Pixel не означает, что маршрут появился на очках. Нет фонового захвата экрана или геолокации при входе в этот раздел.");
        CompanionUi.navigationRow(this, route, "glass", "Точка и функции устройства",
                "Координаты · отдельный опытный режим, не пошаговый маршрут", new View.OnClickListener() {
            public void onClick(View view) { startActivity(new Intent(BridgeActivity.this, NativeCompanionActivity.class)); }
        });
        CompanionUi.navigationRow(this, route, "cards", "Сохранённые места и лента",
                "Открыть раздел брокера · не настроен", new View.OnClickListener() {
            public void onClick(View view) { startActivity(new Intent(BridgeActivity.this, MyGlassSetupActivity.class)); }
        });
    }

    private void buildSettings() {
        CompanionUi.eyebrow(this, settings, "ВАША СВЯЗЬ С МИРОМ");
        heading(settings, "Устройство и связь");
        CompanionUi.navigationRow(this, settings, "glass", "Изображение Glass",
            "Яркость · контраст · гамма · цвет · сохранённые профили", new View.OnClickListener(){
                public void onClick(View v){if(displayPanel!=null)displayPanel.close();displayPanel=new DisplayCalibrationPanel(BridgeActivity.this);}
            });
        CompanionUi.navigationRow(this, settings, "glass", "Glass и подключение",
                "Батарея · Bluetooth · Wi‑Fi · экран", new View.OnClickListener() {
            public void onClick(View view) { startActivity(new Intent(BridgeActivity.this, NativeCompanionActivity.class)); }
        });
        CompanionUi.navigationRow(this, settings, "link", "Доступ к Франциске",
                "Сохранённый доступ · проверка на главной", new View.OnClickListener() {
            public void onClick(View view) { openSetup(); }
        });
        CompanionUi.navigationRow(this, settings, "cards", "Лента Glass · брокер отключён",
                "Частные карточки и места · доступ Pixel", new View.OnClickListener() {
            public void onClick(View view) { startActivity(new Intent(BridgeActivity.this, MyGlassSetupActivity.class)); }
        });
        CompanionUi.navigationRow(this, settings, "more", "Инструменты и диагностика",
                "Чек-лист · причины сбоев · управление парой", new View.OnClickListener() {
            public void onClick(View view) { showTools(); }
        });
        Button back = addButton(settings, "Вернуться к задаче", new View.OnClickListener() {
            public void onClick(View view) { openSection(lastTask); }
        });
        CompanionUi.quiet(back, this);
    }

    private void openNavigator() {
        String query = routeQuery.getText().toString().trim();
        if (query.length() == 0 || query.length() > 160) {
            routeStatus.setText("Введите адрес или название места до 160 символов"); return;
        }
        Uri search = Uri.parse("yandexnavi://map_search").buildUpon()
                .appendQueryParameter("text", query).build();
        Intent intent = new Intent(Intent.ACTION_VIEW, search).setPackage(YandexNavigatorRoute.PACKAGE);
        try {
            startActivity(intent);
            routeStatus.setText("Поиск открыт на Pixel. Выберите маршрут в Навигаторе; на Glass ведение не началось.");
        } catch (ActivityNotFoundException unavailable) {
            routeStatus.setText("Яндекс Навигатор не установлен или не принимает этот переход на Pixel.");
        } catch (RuntimeException unavailable) {
            routeStatus.setText("Не удалось открыть Навигатор. Маршрут на Glass не запущен.");
        }
    }

    private void buildTools() {
        LinearLayout layout = advanced;
        Button back = addButton(layout, "← К настройкам", new View.OnClickListener() {
            public void onClick(View view) { openSection(CompanionDestination.SETTINGS); }
        });
        CompanionUi.quiet(back, this);
        heading(layout, "Инструменты");
        label(layout, "Чек-лист и диагностика не запускают разговор автоматически.");

        LinearLayout diagnostic = CompanionUi.card(this, layout);
        section(diagnostic, "Состояние связи");
        deviceStatus = CompanionUi.text(this, diagnostic, "Состояние не проверено", 14, CompanionUi.MUTED);
        attempt = CompanionUi.text(this, diagnostic, "", 14, CompanionUi.MUTED);
        label(diagnostic, "Стоп доступен вверху экрана. Обновить состояние можно в разделе «Разговор». Эти данные не подтверждают видимость экрана владельцу.");
        SharedPreferences history = getSharedPreferences("assistant-status", MODE_PRIVATE);
        AssistantProduct restored = new AssistantProduct(history.getString("last", "NONE"), history.getString("previous", "NONE"));
        showAttempt(restored.last(), restored.previous());

        LinearLayout checklistCard = CompanionUi.card(this, layout);
        section(checklistCard, "Чек-лист · отдельный прежний режим");
        label(checklistCard, "Заголовок: 1–40 символов. Шаги: 1–20 строк, по 1–120 символов. Без обрезки.");
        title = new EditText(this);
        CompanionUi.fieldLabel(this, checklistCard, title, "Заголовок чек-листа");
        title.setHint("Заголовок");
        title.setSingleLine(true);
        title.setText(draft.getString("title", Checklist.DEFAULT_TITLE));
        CompanionUi.input(this, title);
        checklistCard.addView(title);
        steps = new EditText(this);
        CompanionUi.fieldLabel(this, checklistCard, steps, "Шаги чек-листа");
        steps.setHint("Каждый шаг с новой строки");
        steps.setMinLines(5);
        steps.setGravity(android.view.Gravity.TOP);
        steps.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        steps.setText(draft.getString("steps", Checklist.DEFAULT_STEPS));
        CompanionUi.input(this, steps);
        checklistCard.addView(steps);
        status = CompanionUi.text(this, checklistCard, "Состояние чек-листа появится после обновления", 14, CompanionUi.MUTED);
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable value) { saveDraft(false); }
        };
        title.addTextChangedListener(watcher);
        steps.addTextChangedListener(watcher);
        button(checklistCard, "Отправить (Send)", LinkService.SEND);
        button(checklistCard, "Начать (Start)", LinkService.START);
        button(checklistCard, "Назад", LinkService.PREVIOUS);
        button(checklistCard, "Далее", LinkService.NEXT);
        button(checklistCard, "Отметить и далее", LinkService.MARK);
        button(checklistCard, "Сбросить прогресс", LinkService.CLEAR);
        button(checklistCard, "STOP — сохранить сопряжение", LinkService.STOP);

        LinearLayout connectionTools = CompanionUi.card(this, layout);
        section(connectionTools, "Подключение и пара");
        Button settings = addButton(connectionTools, "Подключение к OpenClaw…", new View.OnClickListener() {
            public void onClick(View view) { openSetup(); }
        });
        CompanionUi.secondary(settings, this);
        label(connectionTools, "Сопряжение Glass настраивается отдельно. Для обычного запуска не отзывайте рабочую пару.");
        Button revoke = addButton(connectionTools, "Отозвать сопряжение…", new View.OnClickListener() {
            public void onClick(View view) { confirmRevoke(); }
        });
        CompanionUi.stop(revoke, this);

        LinearLayout lab = CompanionUi.card(this, layout);
        section(lab, "Лабораторные функции");
        label(lab, "Демо заменяет текущую публикацию; отдельный HTTPS-адаптер для разговора не нужен.");
        button(lab, "Демо карточки / кириллица / nonce", LinkService.CARD);
        button(lab, "Включить лабораторный HTTPS-адаптер", LinkService.AGENT_ENABLE);
        button(lab, "Выключить лабораторный HTTPS-адаптер", LinkService.AGENT_DISABLE);
    }

    private void showTools() { openSection(CompanionDestination.TOOLS); }

    private void showHome() { openSection(CompanionDestination.TALK); }

    void openSection(String requested) {
        section = CompanionDestination.bridge(requested);
        if (CompanionDestination.TALK.equals(section) || CompanionDestination.ROUTE.equals(section)) lastTask = section;
        home.setVisibility(CompanionDestination.TALK.equals(section) ? View.VISIBLE : View.GONE);
        compactStop.setVisibility(CompanionDestination.TALK.equals(section) ? View.GONE : View.VISIBLE);
        route.setVisibility(CompanionDestination.ROUTE.equals(section) ? View.VISIBLE : View.GONE);
        settings.setVisibility(CompanionDestination.SETTINGS.equals(section) ? View.VISIBLE : View.GONE);
        advanced.setVisibility(CompanionDestination.TOOLS.equals(section) ? View.VISIBLE : View.GONE);
        CompanionUi.selectNavigation(navigation, CompanionDestination.task(section));
        back.setEnabled(CompanionDestination.bridgeBack(section, lastTask) != null);
        page.post(new Runnable() { public void run() { page.scrollTo(0, 0); } });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openSection(intent.getStringExtra(CompanionDestination.EXTRA_SECTION));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("section", section);
        state.putString("hotspot_nonce",hotspotNonce);
        state.putBoolean("hotspot_returned",hotspotReturned);
        state.putString("last_task", lastTask);
        state.putString("draft_title", title.getText().toString());
        state.putString("draft_steps", steps.getText().toString());
        state.putString("route_query", routeQuery.getText().toString());
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (!back.handleBack()) super.onBackPressed();
    }

    @Override protected void onDestroy() {
        back.destroy();
        super.onDestroy();
    }

    private boolean saveDraft(boolean explain) {
        try {
            Checklist.validate(title.getText().toString(), steps.getText().toString());
            draft.edit().putString("title", title.getText().toString())
                    .putString("steps", steps.getText().toString()).apply();
            return true;
        } catch (IllegalArgumentException invalid) {
            if (explain) status.setText(invalid.getMessage() + "\nЧерновик не заменён; текст не обрезан.");
            return false;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(LinkService.STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updates, filter);
        send(LinkService.REFRESH);
        if(hotspotReturned&&hotspotNonce!=null){
            String nonce=hotspotNonce;hotspotNonce=null;hotspotReturned=false;
            startService(new Intent(this,LinkService.class).setAction(LinkService.HOTSPOT_RETURN).putExtra("route_nonce",nonce));
        }
    }

    @Override protected void onPause() {
        if(displayPanel!=null){displayPanel.close();displayPanel=null;}
        saveDraft(false);
        draft.edit().putString("route_query", routeQuery.getText().toString()).apply();
        unregisterReceiver(updates);
        super.onPause();
    }

    private void confirmRevoke() {
        new AlertDialog.Builder(this).setTitle("Отозвать сопряжение Glass?")
                .setMessage("Будут удалены закрытая идентичность, доверие к паре и endpoint. Потребуется повторное сопряжение. Черновик останется.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отозвать", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) { send(LinkService.REVOKE); }
                }).show();
    }

    private void send(String action) {
        if(LinkService.STOP.equals(action)||LinkService.REVOKE.equals(action)){
            hotspotNonce=null;hotspotReturned=false;
        }
        Intent intent = new Intent(this, LinkService.class).setAction(action);
        if (LinkService.START.equals(action) || LinkService.SEND.equals(action)) {
            if (!saveDraft(true)) return;
            intent.putExtra("title", title.getText().toString());
            intent.putExtra("steps", steps.getText().toString());
        }
        if (Build.VERSION.SDK_INT >= 26 && (LinkService.START.equals(action) || LinkService.ASSIST_START.equals(action)||LinkService.PREPARE_CHECK.equals(action))) startForegroundService(intent);
        else startService(intent);
    }

    private void openHotspot(){
        String nonce=java.util.UUID.randomUUID().toString();
        Intent settings=new Intent(android.provider.Settings.ACTION_SETTINGS);
        // Public Settings entry; no hidden tethering permission or shell dependency.
        settings.setClassName("com.android.settings","com.android.settings.Settings$WifiTetherSettingsActivity");
        if(settings.resolveActivity(getPackageManager())==null)settings=new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS);
        try {
            hotspotNonce=nonce;hotspotReturned=false;
            startService(new Intent(this,LinkService.class).setAction(LinkService.HOTSPOT_ARM).putExtra("route_nonce",nonce));
            startActivityForResult(settings,HOTSPOT_SETTINGS);
        }catch(RuntimeException unavailable){
            hotspotNonce=null;hotspotReturned=false;
            routeCheck.setText("Откройте настройки Pixel → Сеть и интернет → Точка доступа, затем нажмите «Подготовить и проверить».");
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==HOTSPOT_SETTINGS&&hotspotNonce!=null)hotspotReturned=true;
    }

    private void openSetup() { startActivity(new Intent(this, AssistSetupActivity.class)); }

    private void showAttempt(String last, String previous) {
        String current = last == null ? "NONE" : last;
        String earlier = previous == null ? "NONE" : previous;
        attempt.setText("Последняя попытка\n" + AssistantProduct.describe(current)
                + ("NONE".equals(earlier) ? "" : "\nПредыдущее событие: " + AssistantProduct.describe(earlier)));
        String brief = AssistantProduct.brief(current);
        attemptSummary.setText(brief);
        attemptSummary.setVisibility(brief.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private static String or(String value, String fallback) {
        return value == null || value.length() == 0 ? fallback : value;
    }
    private void label(LinearLayout layout, String text) {
        CompanionUi.text(this, layout, text, 14, CompanionUi.MUTED);
    }
    private void heading(LinearLayout layout, String text) { CompanionUi.heading(this, layout, text); }
    private void section(LinearLayout layout, String text) { CompanionUi.section(this, layout, text); }
    private void button(LinearLayout layout, String text, final String action) {
        Button control = addButton(layout, text, new View.OnClickListener() {
            public void onClick(View view) { send(action); }
        });
        CompanionUi.secondary(control, this);
    }
    private Button addButton(LinearLayout layout, String text, View.OnClickListener listener) {
        return CompanionUi.button(this, layout, text, listener);
    }
}
