package org.monzobot.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Existing widget identity remains valid; financial details stay inside the private app. */
class BudgetWidget : AppWidgetProvider() {
  override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) {
    for(id in ids) {
      val views=RemoteViews(context.packageName,android.R.layout.simple_list_item_1)
      views.setTextViewText(android.R.id.text1,"Money companion · Open privately")
      views.setOnClickPendingIntent(android.R.id.text1,PendingIntent.getActivity(context,id,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
      manager.updateAppWidget(id,views)
    }
  }
}
