package com.aiwa.widget
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.*
class AiwaWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){provideContent{Content()}}
@Composable private fun Content(){Column(modifier=GlanceModifier.fillMaxSize().background(ColorProvider(android.graphics.Color.rgb(18,18,18))).padding(12.dp),horizontalAlignment=Alignment.Start){Row(modifier=GlanceModifier.fillMaxWidth()){Text("AIWA",style=TextStyle(color=ColorProvider(android.graphics.Color.WHITE),fontSize=18.sp));Spacer(GlanceModifier.width(8.dp));Text("• Aiwa",style=TextStyle(color=ColorProvider(android.graphics.Color.LTGRAY),fontSize=13.sp))};Spacer(GlanceModifier.height(8.dp));Text("Prêt — toucher pour ouvrir Aiwa",style=TextStyle(color=ColorProvider(android.graphics.Color.LTGRAY),fontSize=12.sp),modifier=GlanceModifier.clickable(actionStartActivity<MainActivity>()));Spacer(GlanceModifier.height(8.dp));Row{Text("🎙️",modifier=GlanceModifier.padding(6.dp).clickable(actionStartActivity<MainActivity>()));Text("⌨️",modifier=GlanceModifier.padding(6.dp).clickable(actionStartActivity<MainActivity>()));Text("➤",modifier=GlanceModifier.padding(6.dp).clickable(actionStartActivity<MainActivity>()))}}}
