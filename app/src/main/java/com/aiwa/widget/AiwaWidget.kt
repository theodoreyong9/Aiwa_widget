package com.aiwa.widget
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.*
import com.aiwa.bridge.LocalClaudeBridge
import com.aiwa.bridge.SessionInfo
class AiwaWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){
// Glance re-renders only when told to (updateAll()), not reactively —
// a plain synchronous snapshot of the shared AiwaRepository here is
// exactly the intended usage: whoever mutated the repository is
// responsible for calling AiwaWidget().updateAll(context) afterward
// (see MainActivity).
val state=AiwaRepository.state.value
// Reported live: "what I actually expected was picking an existing
// session directly [without opening the app]" — this fetches the real
// list the same way MainActivity's own picker does, so the widget can
// offer the same real choice instead of only ever opening the app.
val sessions=try{LocalClaudeBridge().listSessions()}catch(err:Exception){emptyList()}
provideContent{Content(state,sessions)}}
// Glance's ColorProvider(Int) overload takes a @ColorRes RESOURCE id,
// not a raw packed color — passing android.graphics.Color.rgb(...)/
// .WHITE/.LTGRAY straight into it (as the original file did, and this
// file copied for safety) compiles fine, Int being Int, but crashes at
// real widget-render time on device: Android tries to look up a color
// RESOURCE matching that huge, meaningless int and fails, which is
// exactly what surfaces as "impossible d'installer le widget" — a
// generic RemoteViews-inflation failure, not a Kotlin-catchable error.
// Wrapping every raw android.graphics.Color int in Compose's own
// Color(Int) first (same 0xAARRGGBB packing, just the real value type
// ColorProvider actually wants) is the real fix.
private fun rgb(colorInt:Int)=ColorProvider(androidx.compose.ui.graphics.Color(colorInt))
@Composable private fun Content(state:AiwaState,sessions:List<SessionInfo>){
val statusLabel=when(state.status){AiwaState.Status.READY->"Prêt";AiwaState.Status.WORKING->"En cours…";AiwaState.Status.WAITING->"Question en attente";AiwaState.Status.DONE->"Terminé";AiwaState.Status.ERROR->"Erreur"}
val statusColor=when(state.status){AiwaState.Status.READY->rgb(android.graphics.Color.LTGRAY);AiwaState.Status.WORKING->rgb(android.graphics.Color.rgb(90,170,255));AiwaState.Status.WAITING->rgb(android.graphics.Color.rgb(255,180,60));AiwaState.Status.DONE->rgb(android.graphics.Color.rgb(110,220,110));AiwaState.Status.ERROR->rgb(android.graphics.Color.rgb(255,90,90))}
val preview=if(state.status==AiwaState.Status.WAITING&&!state.question.isNullOrBlank())state.question!! else if(state.output.isNotBlank())state.output else "Toucher pour ouvrir Aiwa"
Column(modifier=GlanceModifier.fillMaxSize().background(rgb(android.graphics.Color.rgb(18,18,18))).padding(12.dp),horizontalAlignment=Alignment.Start){
Row(modifier=GlanceModifier.fillMaxWidth()){Text("AIWA",style=TextStyle(color=rgb(android.graphics.Color.WHITE),fontSize=18.sp));Spacer(GlanceModifier.width(8.dp));Text("• ${state.session}",style=TextStyle(color=rgb(android.graphics.Color.LTGRAY),fontSize=13.sp))}
Spacer(GlanceModifier.height(6.dp))
Text(statusLabel,style=TextStyle(color=statusColor,fontSize=12.sp))
Spacer(GlanceModifier.height(4.dp))
Text(preview.take(80),style=TextStyle(color=rgb(android.graphics.Color.LTGRAY),fontSize=12.sp),modifier=GlanceModifier.clickable(actionStartActivity<MainActivity>()))
// Reported live as "super moche" (fair): three separate icons
// (mic/keyboard/send) that all did the exact same single thing —
// open the app — with no real difference between them. Collapsing
// them all away was a mistake (reported live too) — the mic is its
// own real, distinct affordance and stays, now launching
// DictateActivity directly (a real, invisible trampoline — see that
// file — so the system recognizer appears immediately instead of
// opening Aiwa's own UI first). Typing still genuinely needs the app
// either way (no text input field can live directly in a widget — a
// hard Android platform limit), so keyboard/send collapse into one
// generic "Ouvrir".
if(sessions.isNotEmpty()){
Spacer(GlanceModifier.height(8.dp))
Text("Sessions :",style=TextStyle(color=rgb(android.graphics.Color.LTGRAY),fontSize=11.sp))
LazyColumn(modifier=GlanceModifier.fillMaxWidth().height(72.dp)){
items(sessions.take(4)){s->
Text(
s.preview.take(36),
style=TextStyle(color=rgb(android.graphics.Color.WHITE),fontSize=12.sp),
modifier=GlanceModifier.fillMaxWidth().padding(vertical=3.dp)
.clickable(actionRunCallback<SelectSessionAction>(actionParametersOf(SessionIdKey to s.id,SessionPreviewKey to s.preview))),
)
}
}
}
Spacer(GlanceModifier.height(8.dp))
Row{
Text("▶",modifier=GlanceModifier.padding(6.dp).clickable(actionRunCallback<StartBackendAction>()))
Text("🎙️",modifier=GlanceModifier.padding(6.dp).clickable(actionStartActivity<DictateActivity>()))
Text("💬 Ouvrir",modifier=GlanceModifier.padding(6.dp).clickable(actionStartActivity<MainActivity>()))
}
}
}}
