package lab.franziska.pixelbridge;
import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import lab.franziska.protocol.*;
/** Native modal hosted by BridgeActivity; dismissal/background always requests rollback. */
final class DisplayCalibrationPanel {
    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout root;
    private final String owner=UUID.randomUUID().toString();
    private final TextView status,applied;
    private final Button begin,save,reset;
    private final CheckBox auto,original;
    private final Spinner slot,pattern;
    private final SeekBar[] sliders=new SeekBar[7];
    private final TextView[] labels=new TextView[7];
    private final String[] names={"Яркость окна (%)","Контраст (%)","Гамма (×100)","Насыщенность (%)","Красный (%)","Зелёный (%)","Синий (%)"};
    private final int[] minimum={10,50,50,0,25,25,25}, maximum={100,150,200,150,100,100,100};
    private boolean syncing,active,hydrated,closed,updateScheduled;
    private final android.os.Handler updates=new android.os.Handler();
    private final Runnable sendLatest=new Runnable(){public void run(){
        updateScheduled=false;
        if(active&&hydrated&&!closed)start(send("update").putExtra("profile",profile().encode())
            .putExtra("pattern",pattern.getSelectedItemPosition()).putExtra("original",original.isChecked()));
    }};
    DisplayCalibrationPanel(Activity activity){
        this.activity=activity;dialog=new Dialog(activity);
        dialog.setTitle("Изображение Glass");
        ScrollView scroll=new ScrollView(activity);root=new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,16,24,24);scroll.addView(root);
        text("Изображение Glass",24);
        text("Только интерфейс Франциски: разговор, текст и карточки. Не системная калибровка; фото, видео и камера без цветового фильтра. Чёрный RGB 0 остаётся чёрным.",16);
        text("Подготовьте связь на главной Pixel. Откройте «Франциска» в меню Glass вручную, не голосовой командой. Начало настройки не включает камеру или микрофон. Завершите разговор, фото и чтение.",16);
        status=text("Проверяем доступность…",17);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        slot=spinner(new String[]{"Помещение","Улица"});
        begin=button("Начать настройку",new Runnable(){public void run(){
            hydrated=false;syncing=true;original.setChecked(false);syncing=false;
            start(send("begin").putExtra("slot",slot.getSelectedItemPosition()==0?"indoor":"outdoor"));
        }});
        pattern=spinner(new String[]{"Чёрный и тени","Белый и серый","Цвета и текст"});
        auto=new CheckBox(activity);auto.setText("Яркость: авто (штатная политика Glass)");auto.setChecked(true);root.addView(auto);
        for(int i=0;i<sliders.length;i++){
            final int index=i;labels[i]=text(names[i],16);sliders[i]=new SeekBar(activity);
            sliders[i].setMax(maximum[i]-minimum[i]);root.addView(sliders[i],new LinearLayout.LayoutParams(-1,-2));
            sliders[i].setContentDescription(names[i]);
            sliders[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar bar,int progress,boolean user){label(index);if(user)changed();}
                public void onStartTrackingTouch(SeekBar bar){}
                public void onStopTrackingTouch(SeekBar bar){}
            });
        }
        text("Гамма 100 = 1,00; больше — темнее. Контраст — кривая без поднятия чёрного. RGB уменьшает каналы, без усиления выше 100%. Яркость — запрос окну, не измеренная светимость.",15);
        original=new CheckBox(activity);original.setText("A/B: оригинал (временно авто и без коррекции)");root.addView(original);
        original.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(CompoundButton b,boolean c){changed();}});
        auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(CompoundButton b,boolean c){
            sliders[0].setEnabled(active&&!c);changed();
        }});
        pattern.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){changed();}
        });
        applied=text("Применённые значения ещё не подтверждены Glass.",15);
        reset=button("Сбросить к исходным (предпросмотр)",new Runnable(){public void run(){hydrate(DisplayCalibration.Profile.identity());changed();}});
        save=button("Сохранить на Glass",new Runnable(){public void run(){if(updateScheduled){updates.removeCallbacks(sendLatest);sendLatest.run();}start(send("save"));}});
        button("Отменить / закрыть",new Runnable(){public void run(){close();}});
        text("Сохранить — только после ACK. Назад, уход с экрана, потеря связи или 2 минуты без изменений отменяют несохранённое. Максимум сеанса — 15 минут. Чёрный оттенок оптики RGB-регуляторами может не исправиться.",15);
        hydrate(DisplayCalibration.Profile.identity());enable(false);
        dialog.setContentView(scroll);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){if(!closed){closed=true;updates.removeCallbacks(sendLatest);start(send("cancel"));}}});
        dialog.show();dialog.getWindow().setLayout(-1,-1);
        start(send("status"));
    }
    private Intent send(String op){return new Intent(activity,LinkService.class).setAction(LinkService.DISPLAY).putExtra("owner",owner).putExtra("op",op);}
    private void start(Intent i){activity.startService(i);}
    private TextView text(String s,int size){TextView v=new TextView(activity);v.setText(s);v.setTextSize(size);v.setPadding(0,8,0,8);root.addView(v);return v;}
    private Button button(String name,final Runnable action){Button b=new Button(activity);b.setText(name);root.addView(b);b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();}});return b;}
    private Spinner spinner(String[] items){Spinner s=new Spinner(activity);s.setAdapter(new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,items));root.addView(s);return s;}
    private void label(int i){labels[i].setText(names[i]+": "+(minimum[i]+sliders[i].getProgress()));}
    private DisplayCalibration.Profile profile(){int[] v=new int[7];for(int i=0;i<7;i++)v[i]=minimum[i]+sliders[i].getProgress();
        return new DisplayCalibration.Profile(auto.isChecked()?-1:v[0],v[1],v[2],v[3],v[4],v[5],v[6]);}
    private void hydrate(DisplayCalibration.Profile p){
        syncing=true;int[] values={p.brightness<0?50:p.brightness,p.contrast,p.gamma,p.saturation,p.red,p.green,p.blue};
        for(int i=0;i<7;i++){sliders[i].setProgress(values[i]-minimum[i]);label(i);}
        auto.setChecked(p.brightness<0);original.setChecked(false);syncing=false;
    }
    private void changed(){
        if(syncing||!active||!hydrated)return;
        if(!updateScheduled){updateScheduled=true;updates.postDelayed(sendLatest,120);}
    }
    private void enable(boolean value){
        active=value;slot.setEnabled(!value);begin.setEnabled(!value);
        pattern.setEnabled(value);auto.setEnabled(value);original.setEnabled(value);reset.setEnabled(value);save.setEnabled(value);
        for(SeekBar s:sliders)s.setEnabled(value);sliders[0].setEnabled(value&&!auto.isChecked());
    }
    @SuppressWarnings("unchecked") void update(Intent intent){
        if(closed)return;
        String incoming=intent.getStringExtra("display_owner");
        if(incoming!=null&&!incoming.isEmpty()&&!owner.equals(incoming)){
            status.setText(intent.getStringExtra("display_status"));enable(false);return;
        }
        status.setText(intent.getStringExtra("display_status"));
        String json=intent.getStringExtra("display_json");
        boolean remoteActive=intent.getBooleanExtra("display_active",false);
        if(json!=null&&!json.isEmpty())try{
            Map<String,Object> state=(Map<String,Object>)Json.parse(json);
            if(owner.equals(state.get("id"))){
                DisplayCalibration.Profile p=DisplayCalibration.Profile.parse(state.get("profile"));
                if(!hydrated&&"applied".equals(state.get("status"))){hydrate(p);hydrated=true;}
                applied.setText("ACK Glass: "+state.get("status")+" · rev "+state.get("revision")
                    +"\nЯркость "+(p.brightness<0?"авто":p.brightness+"%")+" · контраст "+p.contrast
                    +" · гамма "+p.gamma+" · насыщенность "+p.saturation+"\nRGB "+p.red+" / "+p.green+" / "+p.blue
                    +(Boolean.TRUE.equals(state.get("original"))?" · ОРИГИНАЛ":""));
            }
        }catch(Exception invalid){status.setText("Некорректное подтверждение Glass");}
        enable(remoteActive&&hydrated);
        begin.setEnabled(!remoteActive);slot.setEnabled(!remoteActive);
    }
    void close(){if(!closed)dialog.dismiss();}
}
