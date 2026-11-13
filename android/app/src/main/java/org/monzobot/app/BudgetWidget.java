package org.monzobot.app;
import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.widget.RemoteViews;
import org.json.JSONObject;
import java.util.concurrent.*;

/** Persistent home-screen entry point, with financial details always hidden. */
public final class BudgetWidget extends AppWidgetProvider {
 private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
 @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids){PendingResult pending=goAsync();WORK.execute(()->{try{render(context);}finally{pending.finish();}});}
 static void refresh(Context context){WORK.execute(()->render(context));}
 private static void render(Context context){
  AppWidgetManager manager=AppWidgetManager.getInstance(context);int[] ids=manager.getAppWidgetIds(new ComponentName(context,BudgetWidget.class));if(ids.length==0)return;
  String message="Tap to open your private dashboard";
  try{JSONObject data=new SecureStore(context).load();if(!data.optString("lastSync").isEmpty())message="Saved data available · export coverage unverified";}catch(Exception ignored){message="Open the app to review your private workspace";}
  for(int id:ids){RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.budget_widget);views.setTextViewText(R.id.widget_status,message);PendingIntent pi=PendingIntent.getActivity(context,0,new Intent(context,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);views.setOnClickPendingIntent(R.id.widget_root,pi);manager.updateAppWidget(id,views);}
 }
}
