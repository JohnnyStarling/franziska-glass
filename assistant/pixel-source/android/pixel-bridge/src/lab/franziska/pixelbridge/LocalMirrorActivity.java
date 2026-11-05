package lab.franziska.pixelbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Build;
import android.provider.CalendarContract;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Owner-reviewed local card shelf. Android shares only fill a draft, never send to Glass. */
public final class LocalMirrorActivity extends Activity {
    private LocalMirrorCatalog catalog;
    private EditText title, body;
    private TextView status;
    private LinearLayout shelf;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout[] holder = new LinearLayout[1];
        ScrollView scroll = CompanionUi.screen(this, holder);
        LinearLayout root = holder[0];
        CompanionUi.eyebrow(this, root, "СОХРАНЕНО НА PIXEL");
        CompanionUi.heading(this, root, "Мои карточки");
        CompanionUi.text(this, root, "Небольшие заметки для Glass. Сохранение на Pixel и показ на очках — два разных действия.",16,CompanionUi.MUTED);
        status = CompanionUi.text(this, root, "", 15, CompanionUi.INK);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Button create = CompanionUi.button(this, root, "Создать карточку", null);
        LinearLayout editor = CompanionUi.card(this, root);
        editor.setVisibility(View.GONE);
        create.setOnClickListener(new View.OnClickListener() { public void onClick(View view) {
            boolean open = editor.getVisibility() != View.VISIBLE;
            editor.setVisibility(open ? View.VISIBLE : View.GONE);
            create.setText(open ? "Скрыть создание" : "Создать карточку");
            if (open) title.requestFocus();
        } });
        CompanionUi.section(this, editor, "Новая карточка");
        CompanionUi.text(this, editor, "Текст остаётся на Pixel, пока вы отдельно не нажмёте «Показать на Glass».", 14, CompanionUi.MUTED);
        title = new EditText(this); CompanionUi.fieldLabel(this,editor,title,"Заголовок"); title.setHint("Заголовок · до 80 символов"); title.setSingleLine(true); CompanionUi.input(this,title); editor.addView(title);
        body = new EditText(this); CompanionUi.fieldLabel(this,editor,body,"Текст карточки"); body.setHint("Текст карточки · до 240 символов");
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); body.setMinLines(3); CompanionUi.input(this,body); editor.addView(body);
        Button save = CompanionUi.button(this, editor, "Сохранить карточку на Pixel", null);
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View view) { saveCard(); } });
        Button calendar = CompanionUi.button(this, editor, "Взять событие из календаря Pixel…", null);
        CompanionUi.secondary(calendar, this);
        calendar.setOnClickListener(new View.OnClickListener() { public void onClick(View view) { chooseCalendar(); } });
        CompanionUi.section(this, root, "Сохранённые карточки");
        shelf = new LinearLayout(this); shelf.setOrientation(LinearLayout.VERTICAL); root.addView(shelf);
        setContentView(CompanionUi.withNavigation(this, scroll, "cards"));
        try { catalog = LocalMirrorCatalog.decode(getSharedPreferences("local-mirror", MODE_PRIVATE).getString("cards", "")); }
        catch (IllegalArgumentException invalid) { status.setText("Хранилище карточек повреждено; запись заблокирована, данные не перезаписаны"); return; }
        Intent source = getIntent();
        if (Intent.ACTION_SEND.equals(source.getAction()) && "text/plain".equals(source.getType())) {
            CharSequence shared = source.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared != null && shared.length() <= 240) {
                title.setText("Из Pixel"); body.setText(shared);
                editor.setVisibility(View.VISIBLE);
                create.setText("Скрыть создание");
                status.setText("Проверьте полученный текст, затем сохраните карточку");
            } else status.setText("Полученный текст длиннее 240 символов; он не сохранён и не отправлен");
        }
        render();
    }
    private void chooseCalendar() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.READ_CALENDAR }, 19); return;
        }
        final ArrayList<String[]> events = new ArrayList<String[]>();
        long begin = System.currentTimeMillis();
        long end = begin + 7L * 24 * 60 * 60 * 1000;
        Cursor cursor = null;
        try {
            cursor = CalendarContract.Instances.query(getContentResolver(),
                    new String[] { CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY }, begin, end);
            if (cursor != null) while (cursor.moveToNext() && events.size() < 40) {
                String name = cursor.getString(0);
                long when = cursor.getLong(1);
                String place = cursor.getString(2);
                boolean allDay = cursor.getInt(3) != 0;
                if (name == null || name.trim().isEmpty() || name.length() > 80
                        || place != null && place.length() > 150) continue;
                SimpleDateFormat format = new SimpleDateFormat(allDay ? "dd.MM.yyyy · весь день" : "dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru-RU"));
                if (allDay) format.setTimeZone(TimeZone.getTimeZone("UTC"));
                String date = format.format(new Date(when));
                String detail = date + (place == null || place.trim().isEmpty() ? "" : "\nМесто: " + place.trim());
                if (detail.length() <= 240) events.add(new String[] {name, detail});
            }
        } catch (SecurityException denied) { status.setText("Доступ к календарю не разрешён"); return; }
        catch (RuntimeException failure) { status.setText("Не удалось прочитать календарь Pixel"); return; }
        finally { if (cursor != null) cursor.close(); }
        if (events.isEmpty()) { status.setText("Подходящих событий на ближайшие 7 дней нет"); return; }
        String[] choices = new String[events.size()];
        for (int i = 0; i < choices.length; i++) choices[i] = events.get(i)[0] + " · " + events.get(i)[1].replace('\n', ' ');
        new AlertDialog.Builder(this).setTitle("Выберите одно событие")
                .setItems(choices, new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) {
                    title.setText(events.get(which)[0]); body.setText(events.get(which)[1]);
                    status.setText("Событие только в черновике; проверьте и сохраните вручную");
                } }).show();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 19 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) chooseCalendar();
        else if (request == 19) status.setText("Доступ к календарю не разрешён");
    }
    private void saveCard() {
        if (catalog == null) return;
        try {
            catalog.add(title.getText().toString(), body.getText().toString());
            if (!getSharedPreferences("local-mirror", MODE_PRIVATE).edit().putString("cards", catalog.encode()).commit())
                throw new IllegalStateException("storage");
            title.setText(""); body.setText(""); status.setText("Карточка сохранена на Pixel; на Glass пока не отправлена"); render();
        } catch (IllegalArgumentException invalid) { status.setText(invalid.getMessage()); }
        catch (IllegalStateException failed) {
            catalog = LocalMirrorCatalog.decode(getSharedPreferences("local-mirror", MODE_PRIVATE).getString("cards", ""));
            status.setText("Не удалось сохранить карточку"); render();
        }
    }
    private void render() {
        if (catalog == null) return;
        shelf.removeAllViews();
        if (catalog.size() == 0) {
            LinearLayout empty = CompanionUi.card(this, shelf);
            CompanionUi.text(this, empty, "Карточек пока нет. Создайте первую, затем решите, когда показать её на Glass.", 16, CompanionUi.MUTED);
        }
        for (int i = 0; i < catalog.size(); i++) {
            final int index = i;
            LocalMirrorCatalog.Card card = catalog.get(i);
            LinearLayout cardSurface = CompanionUi.card(this, shelf);
            TextView row = CompanionUi.text(this, cardSurface, card.title + "\n" + card.body, 17, CompanionUi.INK);
            Button show = CompanionUi.button(this, cardSurface, "Показать на Glass", null);
            show.setOnClickListener(new View.OnClickListener() { public void onClick(View view) {
                startService(new Intent(LocalMirrorActivity.this, LinkService.class)
                        .setAction(LinkService.MIRROR_SHOW).putExtra("index", index)
                        .putExtra("title", card.title).putExtra("body", card.body));
                status.setText("Запрос передан службе Pixel. Показ на Glass не подтверждён. Если связь выключена, подготовьте разговор на главной.");
            } });
            Button delete = CompanionUi.button(this, cardSurface, "Удалить с Pixel", null);
            CompanionUi.secondary(delete, this);
            delete.setOnClickListener(new View.OnClickListener() { public void onClick(View view) {
                new AlertDialog.Builder(LocalMirrorActivity.this).setTitle("Удалить локальную карточку?")
                        .setNegativeButton("Отмена", null).setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                catalog.remove(index);
                                if (getSharedPreferences("local-mirror", MODE_PRIVATE).edit().putString("cards", catalog.encode()).commit()) {
                                    status.setText("Карточка удалена с Pixel"); render();
                                } else {
                                    catalog = LocalMirrorCatalog.decode(getSharedPreferences("local-mirror", MODE_PRIVATE).getString("cards", ""));
                                    status.setText("Удаление не сохранено"); render();
                                }
                            }
                        }).show();
            } });
        }
    }
}
