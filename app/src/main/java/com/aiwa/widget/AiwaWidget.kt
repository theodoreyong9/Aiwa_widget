package com.aiwa.widget
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.*
class AiwaWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){
// Glance re-renders only when told to (updateAll()), not reactively —
// a plain synchronous snapshot of the shared AiwaRepository here is
// exactly the intended usage: whoever mutated the repository is
// responsible for calling AiwaWidget().updateAll(context) afterward
// (see MainActivity).
val state=AiwaRepository.state.value
provideContent{Content(state)}}
@Composable private fun Content(state:AiwaState){
val statusLabel=when(state.status){AiwaState.Status.READY->"Prêt";AiwaState.Status.WORKING->"En cours…";AiwaState.Status.WAITING->"Question en attente";AiwaState.Status.DONE->"Terminé";AiwaState.Status.ERROR->"Erreur"}
val statusColor=when(state.status){AiwaState.Status.READY->android.graphics.Color.LTGRAY;AiwaState.Status.WORKING->android.graphics.Color.rgb(90,170,255);AiwaState.Status.WAITING->android.graphics.Color.rgb(255,180,60);AiwaState.Status.DONE->android.graphics.Color.rgb(110,220,110);AiwaState.Status.ERROR->android.graphics.Color.rgb(255,90,90)}
val preview=if(state.status==AiwaState.Status.WAITING&&!state.question.isNullOrBlank())state.question!! else if(state.output.isNotBlank())state.output else "Toucher pour ouvrir Aiwa"
Column(modifier=GlanceModifier.fillMaxSize().background(ColorProvider(android.graphics.Color.rgb(18,18,18))).padding(12.dp),horizontalAlignment=Alignment.Start){Row(modifier=GlanceModifier.fillMaxWidth()){Text("AIWA",style=TextStyle(color=ColorProvider(android.graphics.Color.WHITE),fontSize=18.sp));Spacer(GlanceModifier.width(8.dp));Text("• ${state.session}",style=TextStyle(color=ColorProvider(android.graphics.Color.LTGRAY),fontSize=13.sp))};Spacer(GlanceModifier.height(6.dp));Text(statusLabel,style=TextStyle(color=ColorProvider(statusColor),fontSize=12.sp));Spacer(GlanceModifier.height(4.dp));Text(preview.take(80),style=TextStyle(color=ColorProvider(android.graphics.Color.LTGRAY),fontSize=12.sp),modifier=GlanceModifier.clickable(actionStartActivity<MainActivity>()));Spacer(GlanceModifier.height(8.dp));Row{Text("🎙️",modifier=GlanceModifier.padding(6.dp).clickable(actionStartActivity<MainActivity>()));Text("⌨️",modifier=GlanceModifier.padding(6.dp).clickable(actionStartActivity<MainActivity>()));Text("➤",modifier=GlanceModifier.padding(6.dp).clickable(actionStartActivity<MainActivity>()))}}}}
