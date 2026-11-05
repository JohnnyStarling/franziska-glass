package lab.franziska.pixelbridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Owner-operated draft shelf. Opening Telegram never means a file was sent. */
public final class OriginalShareActivity extends Activity {
    private LinearLayout shelf;
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout[] holder = new LinearLayout[1];
        ScrollView scroll = CompanionUi.screen(this, holder); // live native system insets
        LinearLayout root = holder[0];
        CompanionUi.eyebrow(this, root, "ПЕРЕДАЧА БЕЗ СЖАТИЯ");
        CompanionUi.heading(this, root, "Оригиналы");
        LinearLayout intro = CompanionUi.card(this, root);
        CompanionUi.badge(this, intro, "НА PIXEL · БЕЗ СЖАТИЯ");
        CompanionUi.text(this, intro,
                "Здесь — подготовленные на Pixel файлы. Выберите оригинал, затем адресата в Telegram. Отправку подтверждаете вы.",
                16, CompanionUi.MUTED);
        Button refresh = CompanionUi.button(this, intro, "Обновить список", new View.OnClickListener() {
            @Override public void onClick(View view) { render(); }
        });
        CompanionUi.secondary(refresh, this);
        status = CompanionUi.text(this, root, "", 14, CompanionUi.MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        shelf = new LinearLayout(this); shelf.setOrientation(LinearLayout.VERTICAL);
        root.addView(shelf);
        setContentView(scroll);
    }
    @Override protected void onResume() { super.onResume(); render(); }
    private void render() {
        status.setText("");
        shelf.removeAllViews();
        try {
            List<OriginalShareFiles.Entry> entries = OriginalShareStore.pending(this);
            if (entries.isEmpty()) {
                LinearLayout card = CompanionUi.card(this, shelf);
                CompanionUi.text(this, card, "Нет подготовленных оригиналов. Сначала перенесите выбранные файлы с Glass на Pixel.",
                        15, CompanionUi.MUTED);
                return;
            }
            for (final OriginalShareFiles.Entry entry : entries) {
                LinearLayout card = CompanionUi.card(this, shelf);
                CompanionUi.section(this, card, entry.name);
                String size = entry.size < 1048576
                        ? String.format(Locale.getDefault(), "%.0f КиБ", entry.size / 1024.0)
                        : String.format(Locale.getDefault(), "%.1f МиБ", entry.size / 1048576.0);
                String date = DateFormat.getDateTimeInstance().format(new Date(entry.created));
                CompanionUi.text(this, card, size + " · на Pixel с " + date,
                        14, CompanionUi.MUTED);
                Button open = CompanionUi.button(this, card, "Открыть в Telegram", new View.OnClickListener() {
                    @Override public void onClick(View view) { openTelegram(entry.token); }
                });
                open.setContentDescription("Открыть в Telegram оригинальный файл " + entry.name);
            }
        } catch (IOException failure) {
            status.setText("Не удалось прочитать подготовленные оригиналы: " + failure.getMessage());
        }
    }
    private void openTelegram(String token) {
        try {
            Intent send = OriginalShareStore.telegramIntent(this, token);
            if (send.resolveActivity(getPackageManager()) == null) {
                status.setText("Официальный Telegram не найден. Установите его, затем вернитесь к файлу.");
                return;
            }
            OriginalShareStore.offered(this, token); // retention lease before handing out the read grant
            startActivity(send); // foreground tap only; owner chooses recipient and confirms in Telegram
            status.setText("Telegram открыт. Отправка не подтверждена приложением Франциска.");
        } catch (IOException failure) {
            status.setText("Файл недоступен: " + failure.getMessage());
        } catch (ActivityNotFoundException unavailable) {
            status.setText("Telegram не открылся; файл остаётся в списке.");
        } catch (SecurityException denied) {
            status.setText("Telegram не получил доступ к файлу; файл остаётся в списке.");
        }
    }
}
