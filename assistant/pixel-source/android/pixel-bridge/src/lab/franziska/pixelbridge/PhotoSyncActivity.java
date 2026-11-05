package lab.franziska.pixelbridge;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lab.franziska.protocol.MediaWire;

/** Offline, visual Glass library. Opening it neither starts a receiver nor copies files. */
public final class PhotoSyncActivity extends Activity {
    private static final int BG=0xff101218,INK=0xfff1f3f9,MUTED=0xffb4bdd0,ACCENT=0xffc4d5ff;
    private final Handler ui=new Handler();
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private SharedPreferences statePrefs;
    private boolean observing,loading,more=true,selecting;
    private int filter,loadVersion;
    private long displayedAt=-1,localAt=-1;
    private TextView status,title,empty,deletion;
    private ImageButton connect,select;
    private Button cancel;
    private final Button[] filterButtons=new Button[3];
    private LinearLayout actions;
    private GridView grid;
    private final ArrayList<GlassMediaCatalog.Item> items=new ArrayList<GlassMediaCatalog.Item>();
    private final LinkedHashMap<Long,GlassMediaCatalog.Item> selected=new LinkedHashMap<Long,GlassMediaCatalog.Item>();
    private Tiles adapter;
    private ServiceConnection runtimeConnection;
    private PhotoSyncService runtimeService;
    private Dialog viewer;
    private AlertDialog confirmation;
    private GlassMediaCatalog.Item viewing;
    private VideoView video;
    private GalleryPhotoView photo;
    private int viewerVersion;
    private final BroadcastReceiver runtimeChanges=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(observing)refreshRuntimeState();}};
    private final SharedPreferences.OnSharedPreferenceChangeListener changes=new SharedPreferences.OnSharedPreferenceChangeListener(){
        public void onSharedPreferenceChanged(SharedPreferences p,String k){if(observing)ui.post(new Runnable(){public void run(){if(observing)showState();}});}
    };
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);statePrefs=getSharedPreferences("photo-sync",MODE_PRIVATE);
        if(saved!=null)filter=saved.getInt("filter",0);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        LinearLayout root=column();root.setBackgroundColor(BG);safeInsets(root,12);
        LinearLayout bar=row();root.addView(bar);
        icon(bar,GalleryIcon.BACK,"Назад",new View.OnClickListener(){public void onClick(View v){onBackPressed();}});
        title=label("Галерея Glass",20,INK);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        select=icon(bar,GalleryIcon.SELECT,"Выбрать",new View.OnClickListener(){public void onClick(View v){selecting=!selecting;if(!selecting)selected.clear();selectionChanged();}});
        status=label("",13,MUTED);status.setPadding(dp(4),dp(6),0,dp(6));root.addView(status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        deletion=label("",13,MUTED);deletion.setPadding(dp(4),0,dp(4),dp(6));deletion.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(deletion);
        LinearLayout tools=row();root.addView(tools);
        connect=icon(bar,GalleryIcon.LINK,"Подключить",new View.OnClickListener(){public void onClick(View v){boolean on=running();Intent i=new Intent(PhotoSyncActivity.this,PhotoSyncService.class).setAction(on?PhotoSyncService.STOP:PhotoSyncService.START);if(!on&&Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}});
        icon(bar,GalleryIcon.REFRESH,"Обновить",new View.OnClickListener(){public void onClick(View v){statePrefs.edit().putBoolean("refresh",true).apply();toast("Обновим, когда Glass подключатся");}});
        cancel=button(tools,"Отменить операцию",new View.OnClickListener(){public void onClick(View v){startService(new Intent(PhotoSyncActivity.this,PhotoSyncService.class).setAction(PhotoSyncService.CANCEL_ALL));}});
        LinearLayout filters=row();root.addView(filters);
        String[] names={"Все","Фото","Видео"};for(int n=0;n<3;n++){final int kind=n;Button b=button(filters,names[n],new View.OnClickListener(){public void onClick(View v){filter=kind;updateFilters();reset();}});b.setLayoutParams(new LinearLayout.LayoutParams(0,dp(48),1));filterButtons[n]=b;}updateFilters();
        FrameLayout body=new FrameLayout(PhotoSyncActivity.this);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        grid=new GridView(PhotoSyncActivity.this);grid.setNumColumns(getResources().getConfiguration().screenWidthDp>=600?5:3);grid.setHorizontalSpacing(dp(3));grid.setVerticalSpacing(dp(3));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setClipToPadding(false);
        adapter=new Tiles();grid.setAdapter(adapter);body.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        empty=label("Здесь появятся фото и видео Glass",18,MUTED);empty.setGravity(Gravity.CENTER);body.addView(empty,new FrameLayout.LayoutParams(-1,-1));grid.setEmptyView(empty);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener(){public void onItemClick(AdapterView<?> p,View v,int pos,long id){GlassMediaCatalog.Item item=items.get(pos);if(selecting)toggle(item);else open(item);}});
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener(){public boolean onItemLongClick(AdapterView<?> p,View v,int pos,long id){selecting=true;toggle(items.get(pos));return true;}});
        grid.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int s){}public void onScroll(AbsListView v,int first,int count,int total){if(total>0&&first+count>=total-12)loadMore();}});
        HorizontalScrollView actionScroll=new HorizontalScrollView(PhotoSyncActivity.this);actionScroll.setHorizontalScrollBarEnabled(false);actions=row();actionScroll.addView(actions);root.addView(actionScroll);
        button(actions,"На Pixel",new View.OnClickListener(){public void onClick(View v){copy(new ArrayList<GlassMediaCatalog.Item>(selected.values()));}});
        button(actions,"Поделиться",new View.OnClickListener(){public void onClick(View v){share(new ArrayList<GlassMediaCatalog.Item>(selected.values()),Intent.ACTION_SEND);}});
        button(actions,"Удалить с Glass",new View.OnClickListener(){public void onClick(View v){deleteGlass(new ArrayList<GlassMediaCatalog.Item>(selected.values()));}});
        button(actions,"Выбрать показанные",new View.OnClickListener(){public void onClick(View v){for(GlassMediaCatalog.Item i:items)if(selected.size()<128)selected.put(i.id,i);selectionChanged();}});
        setContentView(root);root.requestApplyInsets();selectionChanged();showState();
    }
    private void safeInsets(final View root,final int horizontal){
        root.setPadding(dp(horizontal),0,dp(horizontal),0);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener(){public WindowInsets onApplyWindowInsets(View v,WindowInsets insets){
            int l,t,r,b;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());l=bars.left;t=bars.top;r=bars.right;b=bars.bottom;}
            else {l=insets.getSystemWindowInsetLeft();t=insets.getSystemWindowInsetTop();r=insets.getSystemWindowInsetRight();b=insets.getSystemWindowInsetBottom();}
            v.setPadding(dp(horizontal)+l,t,dp(horizontal)+r,b);return insets;
        }});
    }
    private android.graphics.drawable.Drawable surface(int color){android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(24));return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33c4d5ff),shape,null);}
    private ImageButton icon(LinearLayout parent,int kind,String description,View.OnClickListener click){ImageButton b=new ImageButton(PhotoSyncActivity.this);b.setImageDrawable(new GalleryIcon(kind));b.setContentDescription(description);b.setPadding(dp(12),dp(12),dp(12),dp(12));b.setBackground(surface(Color.TRANSPARENT));b.setOnClickListener(click);parent.addView(b,new LinearLayout.LayoutParams(dp(48),dp(48)));return b;}
    private void updateFilters(){for(int n=0;n<filterButtons.length;n++){Button b=filterButtons[n];if(b!=null){b.setSelected(filter==n);b.setTextColor(filter==n?INK:MUTED);b.setBackground(surface(filter==n?0xff33445e:Color.TRANSPARENT));}}}
    private LinearLayout column(){LinearLayout v=new LinearLayout(PhotoSyncActivity.this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(PhotoSyncActivity.this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView label(String value,int size,int color){TextView v=new TextView(PhotoSyncActivity.this);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private Button button(LinearLayout p,String text,View.OnClickListener click){Button b=new Button(PhotoSyncActivity.this);b.setText(text);b.setTextColor(ACCENT);b.setAllCaps(false);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setBackground(surface(Color.TRANSPARENT));b.setMinWidth(0);b.setMinimumWidth(0);b.setOnClickListener(click);p.addView(b,new LinearLayout.LayoutParams(-2,-2));return b;}
    private void toast(String text){Toast.makeText(PhotoSyncActivity.this,text,Toast.LENGTH_LONG).show();}
    private void toggle(GlassMediaCatalog.Item i){if(selected.containsKey(i.id))selected.remove(i.id);else if(selected.size()<128)selected.put(i.id,i);else toast("Можно выбрать до 128 файлов за раз");selectionChanged();}
    private void selectionChanged(){title.setText(selecting?"Выбрано: "+selected.size():"Галерея Glass");select.setContentDescription(selecting?"Завершить выбор":"Выбрать");select.setBackground(surface(selecting?0xff33445e:Color.TRANSPARENT));actions.setVisibility(selecting?View.VISIBLE:View.GONE);adapter.notifyDataSetChanged();}
    private void reset(){++loadVersion;loading=false;more=true;items.clear();selected.clear();selectionChanged();loadMore();}
    private void loadMore(){if(loading||!more||isFinishing())return;loading=true;final int version=loadVersion,offset=items.size(),kind=filter;
        io.execute(new Runnable(){public void run(){ArrayList<GlassMediaCatalog.Item> page=new ArrayList<GlassMediaCatalog.Item>();boolean ok=true;GlassMediaCatalog db=new GlassMediaCatalog(PhotoSyncActivity.this);try{page=db.page(offset,200,kind);}catch(RuntimeException e){ok=false;}finally{db.close();}
            final ArrayList<GlassMediaCatalog.Item> result=page;final boolean success=ok;
            ui.post(new Runnable(){public void run(){if(isDestroyed()||version!=loadVersion)return;loading=false;more=success&&result.size()==200;items.addAll(result);adapter.notifyDataSetChanged();empty.setText(success?"Здесь пока нет "+(kind==1?"фото":kind==2?"видео":"фото и видео")+"\nПодключите Glass и обновите галерею":"Не удалось открыть галерею");}});}});}
    private void showState(){if(status==null)return;long at=statePrefs.getLong("catalog_at",0),local=statePrefs.getLong("gallery_local_at",0);boolean on=running();
        connect.setContentDescription(on?"Отключить приём Glass":"Подключить Glass");connect.setAlpha(on?1f:.5f);connect.setEnabled(on||Provisioning.isComplete(PhotoSyncActivity.this));
        cancel.setVisibility(on&&(runtimeService.hasSelection()||runtimeService.hasDeletion()||statePrefs.getBoolean("import_all",false))?View.VISIBLE:View.GONE);
        String deleteText=statePrefs.getString("delete_status","");deletion.setText(deleteText);deletion.setVisibility(deleteText.isEmpty()?View.GONE:View.VISIBLE);
        String age=at==0?"Каталог ещё не получен":"Снимок каталога · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(at));
        status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setText(on?(runtimeService.hasSelection()?statePrefs.getString("status","Ожидаем Glass"):"Приём включён · последний ответ "+lastSeen()):"Не подключено · "+(at==0?"каталога нет":"сохранённый каталог"));status.setContentDescription(age+". "+status.getText());
        if(at!=displayedAt){displayedAt=at;reset();}
        if(local!=localAt){localAt=local;adapter.notifyDataSetChanged();if(viewing!=null&&video==null)open(viewing);}
    }
    private String lastSeen(){long t=statePrefs.getLong("checked_at",0);return t==0?"ещё не получен":DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(t));}
    private void copy(final ArrayList<GlassMediaCatalog.Item> list){if(list.isEmpty()){toast("Выберите фото или видео");return;}if(!running()){toast("Включите подключение к Glass");return;}
        if(runtimeService.hasDeletion()){toast("Сначала отмените или завершите удаление с Glass");return;}
        for(GlassMediaCatalog.Item i:list)if(i.size>(i.kind==MediaWire.PHOTO?MediaWire.MAX_PHOTO:MediaWire.MAX_VIDEO)){toast("Лимит копии: 32 МБ для фото, 4 ГБ для видео");return;}
        confirm("Скопировать на Pixel?",list.size()+" файлов в исходном качестве. Оригиналы останутся на Glass.","Скопировать",new Runnable(){public void run(){if(runtimeService!=null&&runtimeService.requestCopies(list)){selected.clear();selecting=false;selectionChanged();showState();}else toast("Подключение недоступно");}});}
    private void deleteGlass(final ArrayList<GlassMediaCatalog.Item> list){
        if(list.isEmpty()){toast("Выберите фото или видео");return;}
        if(!running()){toast("Включите подключение к Glass");return;}
        if(runtimeService.hasDeletion()){toast("Удаление уже выполняется");return;}
        for(GlassMediaCatalog.Item item:list)if(GalleryLocalStore.local(PhotoSyncActivity.this,item)==null){toast("Сначала сохраните выбранные оригиналы на Pixel");return;}
        StringBuilder names=new StringBuilder();for(GlassMediaCatalog.Item item:list)names.append("\n• ").append(item.name);
        confirm("Удалить оригиналы с Glass?",list.size()+" файлов будут удалены с очков без возможности отмены. Копии на Pixel останутся. При обрыве связи возможно частичное удаление.\n"+names,"Удалить с Glass",new Runnable(){public void run(){
            if(runtimeService!=null&&runtimeService.requestGlassDeletion(list)){selected.clear();selecting=false;selectionChanged();showState();}
            else toast("Удаление не отправлено. Проверьте подключение и локальные оригиналы.");
        }});
    }
    private void deleteLocal(final GlassMediaCatalog.Item item,final String token){
        confirm("Удалить копию приложения?","Удалится только копия внутри приложения. Публичный альбом Pixel и файл на Glass не изменятся.","Удалить копию",new Runnable(){public void run(){
            if(runtimeConnection!=null&&runtimeService==null){toast("Проверяем текущую операцию · повторите через секунду");return;}
            Object lock=runtimeService==null?PhotoSyncActivity.this:runtimeService;
            synchronized(lock){if(runtimeService!=null&&runtimeService.hasDeletion()){toast("Дождитесь завершения удаления с Glass");return;}
                try{GalleryLocalStore.delete(PhotoSyncActivity.this,item,token);open(item);}catch(Exception e){toast("Копия изменилась или недоступна");}}
        }});
    }
    private void confirm(String title,String message,String yes,Runnable action){if(confirmation!=null)confirmation.dismiss();confirmation=new AlertDialog.Builder(PhotoSyncActivity.this).setTitle(title).setMessage(message).setNegativeButton("Отмена",null).setPositiveButton(yes,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){if(observing)action.run();}}).create();confirmation.show();final AlertDialog armed=confirmation;armed.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(new android.view.ViewTreeObserver.OnWindowFocusChangeListener(){public void onWindowFocusChanged(boolean focused){if(!focused&&armed.isShowing())armed.dismiss();}});}
    private ArrayList<GlassMediaCatalog.Item> one(GlassMediaCatalog.Item i){ArrayList<GlassMediaCatalog.Item> a=new ArrayList<GlassMediaCatalog.Item>();a.add(i);return a;}
    private void share(ArrayList<GlassMediaCatalog.Item> list,String action){if(list.isEmpty())return;try{
        ArrayList<android.net.Uri> uris=new ArrayList<android.net.Uri>();for(GlassMediaCatalog.Item i:list){OriginalShareFiles.Entry e=GalleryLocalStore.local(PhotoSyncActivity.this,i);if(e==null){toast("Сначала скопируйте выбранные оригиналы на Pixel");return;}OriginalShareStore.offered(PhotoSyncActivity.this,e.token);uris.add(OriginalShareStore.uri(e.token));}
        Intent send=new Intent(list.size()>1?Intent.ACTION_SEND_MULTIPLE:action);boolean sending=action.equals(Intent.ACTION_SEND);
        send.setType(sending?OriginalShareStore.MIME:GalleryMediaTypes.mime(list.get(0).name,list.get(0).kind));
        if(sending){if(list.size()>1)send.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);else send.putExtra(Intent.EXTRA_STREAM,uris.get(0));}
        else {send.setDataAndType(uris.get(0),GalleryMediaTypes.mime(list.get(0).name,list.get(0).kind));if(Intent.ACTION_EDIT.equals(action))send.putExtra("return-data",false);}
        ClipData clip=ClipData.newRawUri("Оригиналы Glass",uris.get(0));for(int n=1;n<uris.size();n++)clip.addItem(new ClipData.Item(uris.get(n)));send.setClipData(clip);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send,Intent.ACTION_EDIT.equals(action)?"Изменить копию":sending?"Поделиться оригиналом":"Открыть с помощью"));
    }catch(Exception unavailable){toast("Нет доступного приложения или оригинал недоступен");}}
    private void closeViewer(){++viewerVersion;stopVideo();if(photo!=null){photo.release();photo=null;}if(viewer!=null){Dialog old=viewer;viewer=null;old.dismiss();}viewing=null;}
    private void stopVideo(){if(video!=null){video.stopPlayback();video=null;}}
    private void open(final GlassMediaCatalog.Item item){closeViewer();viewing=item;final int version=viewerVersion;
        final OriginalShareFiles.Entry local=GalleryLocalStore.local(PhotoSyncActivity.this,item);
        viewer=new Dialog(PhotoSyncActivity.this,android.R.style.Theme_Material_NoActionBar);LinearLayout root=column();root.setBackgroundColor(Color.BLACK);safeInsets(root,0);
        LinearLayout bar=row();icon(bar,GalleryIcon.BACK,"Закрыть просмотр",new View.OnClickListener(){public void onClick(View v){closeViewer();}});TextView name=label(item.name,16,INK);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);bar.addView(name,new LinearLayout.LayoutParams(0,-2,1));icon(bar,GalleryIcon.INFO,"Информация о файле",new View.OnClickListener(){public void onClick(View v){info(item);}});root.addView(bar);
        final FrameLayout content=new FrameLayout(PhotoSyncActivity.this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        photo=new GalleryPhotoView(PhotoSyncActivity.this);content.addView(photo,new FrameLayout.LayoutParams(-1,-1));final GalleryPhotoView target=photo;
        if(item.kind==MediaWire.VIDEO){Button play=new Button(PhotoSyncActivity.this);play.setText(local==null?"Скопировать видео для просмотра":"▶ Воспроизвести");FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,dp(56),Gravity.CENTER);content.addView(play,lp);play.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(local==null){copy(one(item));return;}target.release();content.removeAllViews();video=new VideoView(PhotoSyncActivity.this);content.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));MediaController controller=new MediaController(PhotoSyncActivity.this);controller.setAnchorView(video);video.setMediaController(controller);video.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener(){public boolean onError(android.media.MediaPlayer mp,int w,int e){toast("Не удалось воспроизвести. Попробуйте «Открыть».");stopVideo();return true;}});video.setVideoPath(local.file.getAbsolutePath());video.start();}});}
        TextView quality=label(local==null?"Предпросмотр · оригинал на Glass":"Оригинал на Pixel",13,MUTED);quality.setGravity(Gravity.CENTER);root.addView(quality);
        LinearLayout nav=row();button(nav,"‹",new View.OnClickListener(){public void onClick(View v){neighbour(item,-1);}});button(nav,"›",new View.OnClickListener(){public void onClick(View v){neighbour(item,1);}});root.addView(nav);
        HorizontalScrollView scroll=new HorizontalScrollView(PhotoSyncActivity.this);LinearLayout buttons=row();scroll.addView(buttons);root.addView(scroll);
        button(buttons,"На Pixel",new View.OnClickListener(){public void onClick(View v){copy(one(item));}});
        button(buttons,"Поделиться",new View.OnClickListener(){public void onClick(View v){share(one(item),Intent.ACTION_SEND);}});
        button(buttons,"Изменить",new View.OnClickListener(){public void onClick(View v){share(one(item),Intent.ACTION_EDIT);}});
        button(buttons,"Ещё",new View.OnClickListener(){public void onClick(View v){PopupMenu menu=new PopupMenu(PhotoSyncActivity.this,v);menu.getMenu().add(0,1,0,"Открыть в приложении");menu.getMenu().add(0,2,1,"Удалить с Glass");if(local!=null)menu.getMenu().add(0,3,2,"Удалить копию приложения");menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener(){public boolean onMenuItemClick(MenuItem option){if(option.getItemId()==1)share(one(item),Intent.ACTION_VIEW);else if(option.getItemId()==2)deleteGlass(one(item));else if(option.getItemId()==3)deleteLocal(item,local.token);return true;}});menu.show();}});
        viewer.setContentView(root);viewer.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){if(viewing==item){stopVideo();if(photo!=null){photo.release();photo=null;}viewing=null;++viewerVersion;}}});viewer.show();viewer.getWindow().setLayout(-1,-1);root.requestApplyInsets();viewer.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(new android.view.ViewTreeObserver.OnWindowFocusChangeListener(){public void onWindowFocusChanged(boolean focused){if(!focused&&video!=null)video.pause();}});
        io.execute(new Runnable(){public void run(){Bitmap bitmap=decode(item,local);ui.post(new Runnable(){public void run(){if(isDestroyed()||viewing!=item||version!=viewerVersion){if(bitmap!=null)bitmap.recycle();return;}target.setBitmap(bitmap);}});}});
    }
    private void neighbour(GlassMediaCatalog.Item item,int delta){int n=items.indexOf(item)+delta;if(n>=0&&n<items.size())open(items.get(n));else if(delta>0&&more){loadMore();toast("Загружаем следующие файлы");}}
    private Bitmap decode(GlassMediaCatalog.Item item,OriginalShareFiles.Entry local){try{if(local!=null&&item.kind==MediaWire.PHOTO){BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(local.file.getPath(),o);o.inSampleSize=1;while(o.outWidth/o.inSampleSize>2048||o.outHeight/o.inSampleSize>2048)o.inSampleSize*=2;o.inJustDecodeBounds=false;Bitmap b=BitmapFactory.decodeFile(local.file.getPath(),o);if(b!=null){try {android.media.ExifInterface exif=new android.media.ExifInterface(local.file.getPath());int orientation=exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);android.graphics.Matrix m=new android.graphics.Matrix();switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(-90);m.postScale(-1,1);break;case 8:m.setRotate(-90);break;}if(!m.isIdentity()){Bitmap rotated=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);if(rotated!=b)b.recycle();b=rotated;}}catch(java.io.IOException unsupportedExif){/* Keep the decoded original. */}}return b;}
        return thumbnail(item);}catch(Exception|OutOfMemoryError invalid){return thumbnail(item);}}
    private Bitmap thumbnail(GlassMediaCatalog.Item item){if(item.preview==null)return null;BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(item.preview,0,item.preview.length,o);if(o.outWidth<1||o.outHeight<1||o.outWidth>512||o.outHeight>512)return null;return BitmapFactory.decodeByteArray(item.preview,0,item.preview.length);}
    private void info(GlassMediaCatalog.Item i){OriginalShareFiles.Entry e=GalleryLocalStore.local(PhotoSyncActivity.this,i);new AlertDialog.Builder(PhotoSyncActivity.this).setTitle(i.name).setMessage((i.kind==MediaWire.VIDEO?"Видео":"Фото")+" · "+android.text.format.Formatter.formatFileSize(PhotoSyncActivity.this,i.size)+"\n"+DateFormat.getDateTimeInstance().format(new Date(i.modified*1000))+"\n"+(e==null?"Оригинал на Glass · показан сохранённый каталог":"Оригинал сохранён на Pixel\nSHA-256: "+PhotoAlbum.hex(e.hash))).setPositiveButton("Готово",null).show();}
    private final class Tiles extends BaseAdapter {
        public int getCount(){return items.size();}public Object getItem(int n){return items.get(n);}public long getItemId(int n){return items.get(n).id;}public boolean hasStableIds(){return true;}
        public View getView(int n,View recycled,android.view.ViewGroup parent){FrameLayout tile=recycled instanceof FrameLayout?(FrameLayout)recycled:new FrameLayout(PhotoSyncActivity.this);
            if(tile.getChildCount()>0){ImageView old=(ImageView)tile.getChildAt(0);if(old.getDrawable() instanceof android.graphics.drawable.BitmapDrawable){Bitmap b=((android.graphics.drawable.BitmapDrawable)old.getDrawable()).getBitmap();old.setImageDrawable(null);if(b!=null)b.recycle();}}tile.removeAllViews();GlassMediaCatalog.Item i=items.get(n);int size=Math.max(dp(96),(grid.getWidth()-dp(6))/grid.getNumColumns());tile.setLayoutParams(new AbsListView.LayoutParams(-1,size));tile.setBackgroundColor(0xff252b36);
            ImageView image=new ImageView(PhotoSyncActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(thumbnail(i));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tile.addView(image,new FrameLayout.LayoutParams(-1,-1));
            TextView badge=label((selected.containsKey(i.id)?"✓  ":"")+(i.kind==MediaWire.VIDEO?"▶  ":"")+(GalleryLocalStore.local(PhotoSyncActivity.this,i)!=null?"↓":""),18,Color.WHITE);badge.setBackgroundColor(0x66000000);badge.setPadding(dp(5),dp(3),dp(5),dp(3));tile.addView(badge,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
            TextView date=label(DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(i.modified*1000)),11,Color.WHITE);date.setBackgroundColor(0x66000000);tile.addView(date,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));tile.setAlpha(selecting&&!selected.containsKey(i.id)?.65f:1);tile.setContentDescription((i.kind==MediaWire.VIDEO?"Видео ":"Фото ")+i.name+(selected.containsKey(i.id)?", выбрано":""));return tile;}
    }
    @Override protected void onResume(){super.onResume();observing=true;statePrefs.registerOnSharedPreferenceChangeListener(changes);IntentFilter f=new IntentFilter(PhotoSyncService.RUNTIME_CHANGED);if(Build.VERSION.SDK_INT>=33)registerReceiver(runtimeChanges,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(runtimeChanges,f);refreshRuntimeState();}
    @Override protected void onPause(){observing=false;statePrefs.unregisterOnSharedPreferenceChangeListener(changes);unregisterReceiver(runtimeChanges);releaseRuntimeState();if(confirmation!=null){confirmation.dismiss();confirmation=null;}closeViewer();super.onPause();}
    @Override protected void onDestroy(){++loadVersion;io.shutdownNow();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle b){b.putInt("filter",filter);super.onSaveInstanceState(b);}
    @Override public void onBackPressed(){if(viewer!=null){closeViewer();return;}if(selecting){selecting=false;selected.clear();selectionChanged();return;}super.onBackPressed();}
    private boolean running(){return runtimeService!=null&&runtimeService.isRunning();}
    private void refreshRuntimeState(){releaseRuntimeState();ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);boolean present=false;java.util.List<ActivityManager.RunningServiceInfo> services=manager.getRunningServices(100);if(services!=null)for(ActivityManager.RunningServiceInfo s:services)if(PhotoSyncService.class.getName().equals(s.service.getClassName()))present=true;if(!present){showState();return;}runtimeConnection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder binder){if(!observing||runtimeConnection!=this)return;runtimeService=((PhotoSyncService.StateBinder)binder).service();showState();}public void onServiceDisconnected(ComponentName n){if(runtimeConnection==this){releaseRuntimeState();showState();}}};if(!bindService(new Intent(PhotoSyncActivity.this,PhotoSyncService.class),runtimeConnection,0))runtimeConnection=null;showState();}
    private void releaseRuntimeState(){ServiceConnection c=runtimeConnection;runtimeConnection=null;runtimeService=null;if(c!=null)unbindService(c);}
}
