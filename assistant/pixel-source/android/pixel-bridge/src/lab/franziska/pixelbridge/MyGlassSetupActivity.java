package lab.franziska.pixelbridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/** Community edition: no broker enable, credential entry or import controls. */
public final class MyGlassSetupActivity extends Activity {
    private TextView status, routeText;
    private Button openRoute, dismissRoute;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout[] holder=new LinearLayout[1];
        ScrollView scroll=CompanionUi.screen(this,holder);
        LinearLayout root=holder[0];
        CompanionUi.heading(this,root,"Лента Glass · не настроена");
        status=CompanionUi.text(this,root,"Брокер отключён в community-версии. Для ассистента настройте собственный совместимый Gateway через «Доступ к OpenClaw».",15,CompanionUi.INK);
        LinearLayout route=CompanionUi.card(this,root);
        CompanionUi.section(this,route,"Маршрут на Pixel");
        routeText=CompanionUi.text(this,route,"Нет маршрута, ожидающего подтверждения",15,CompanionUi.INK);
        openRoute=CompanionUi.button(this,route,"Открыть в Яндекс Навигаторе",new View.OnClickListener() {
            public void onClick(View view) { openPendingRoute(); }
        });
        dismissRoute=CompanionUi.button(this,route,"Убрать предложенный маршрут",new View.OnClickListener() {
            public void onClick(View view) { dismissPendingRoute(); }
        });
        CompanionUi.quiet(dismissRoute,this);
        CompanionUi.text(this,route,"Маршрут не запускается сервером в фоне: сначала проверьте пункт назначения и нажмите кнопку на Pixel. Открытие приложения не подтверждает начало навигации на Glass.",13,CompanionUi.MUTED);
        setContentView(CompanionUi.withNavigation(this,scroll,"link"));
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        status.setText("Брокер не настроен: получение команд и импорт токенов отключены.");
        MyGlassRouteStore.Route route=MyGlassRouteStore.pending(this);
        routeText.setText(route==null?"Нет маршрута, ожидающего подтверждения"
                : route.label+"\n"+String.format(Locale.ROOT,"%.5f, %.5f",route.latitude,route.longitude));
        openRoute.setVisibility(route==null?View.GONE:View.VISIBLE);
        dismissRoute.setVisibility(route==null?View.GONE:View.VISIBLE);
    }

    private void openPendingRoute() {
        MyGlassRouteStore.Route route=MyGlassRouteStore.pending(this);
        if(route==null) { refresh(); return; }
        try {
            Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(YandexNavigatorRoute.to(route.latitude,route.longitude)));
            intent.setPackage(YandexNavigatorRoute.PACKAGE);
            startActivity(intent); // This is reached only from the owner's tap in this foreground Activity.
            status.setText("Яндекс Навигатор открыт по вашему действию. Проверьте маршрут в приложении.");
        } catch(Exception unavailable) {
            status.setText("Яндекс Навигатор не открылся. Маршрут сохранён для повторной попытки.");
        }
    }

    private void dismissPendingRoute() {
        MyGlassRouteStore.Route route=MyGlassRouteStore.pending(this);
        if(route!=null && MyGlassRouteStore.clear(this,route.commandId)) refresh();
        else status.setText("Маршрут не удалось убрать");
    }

}
