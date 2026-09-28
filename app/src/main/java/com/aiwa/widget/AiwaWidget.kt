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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.*
import com.aiwa.bridge.LocalClaudeBridge
import com.aiwa.bridge.SessionInfo
// Reported live: "mon widget n'a aucun style... je veux id session
// (cliquable pour l'ouvrir dans Claude Code), le bouton micro, et le
// champs de sa réponse (avec scroll)" — then, once tested: "je veux la
// liste de mes véritables sessions Claude Code ou bien en créer une
// nouvelle" (a single id wasn't enough — a real list, brought back).
class AiwaWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){
val state=AiwaRepository.state.value
val sessions=try{LocalClaudeBridge().listSessions()}catch(err:Exception){emptyList()}
provideContent{Content(state,sessions)}}
// Glance's ColorProvider(Int) overload takes a @ColorRes RESOURCE id,
// not a raw packed color — passing android.graphics.Color.rgb(...)/
// .WHITE/.LTGRAY straight into it compiles fine, Int being Int, but
// crashes at real widget-render time on device. Wrapping every raw
// android.graphics.Color int in Compose's own Color(Int) first (same
// 0xAARRGGBB packing, just the real value type ColorProvider wants)
// is the real fix — confirmed live earlier in this project.
private fun rgb(colorInt:Int)=ColorProvider(androidx.compose.ui.graphics.Color(colorInt))
@Composable private fun Content(state:AiwaState,sessions:List<SessionInfo>){
val accent=rgb(android.graphics.Color.rgb(122,162,255))
val dim=rgb(android.graphics.Color.rgb(150,150,160))
val fg=rgb(android.graphics.Color.rgb(228,228,235))
Column(
modifier=GlanceModifier.fillMaxSize()
.background(rgb(android.graphics.Color.rgb(22,22,28)))
.cornerRadius(24.dp)
.padding(14.dp),
horizontalAlignment=Alignment.Start,
){
Text("Sessions",style=TextStyle(color=dim,fontSize=11.sp,fontWeight=FontWeight.Medium))
Spacer(GlanceModifier.height(4.dp))
// The real session list — tapping a row opens it directly in the
// real Claude Code CLI via Termux (OpenSessionInTermuxAction), not
// Aiwa's own app screen, per explicit request. "+ Nouvelle session"
// has nothing to resume, so it only selects a fresh conversation.
LazyColumn(modifier=GlanceModifier.fillMaxWidth().height(72.dp)){
item{
Text(
"+ Nouvelle session",
style=TextStyle(color=accent,fontSize=13.sp,fontWeight=FontWeight.Medium),
modifier=GlanceModifier.fillMaxWidth().padding(vertical=3.dp)
.clickable(actionRunCallback<OpenSessionInTermuxAction>(actionParametersOf(SessionPreviewKey to "nouvelle"))),
)
}
items(sessions.take(4)){s->
val isActive=s.id==state.sessionId
Text(
(if(isActive)"●  " else "")+s.preview.take(34),
style=TextStyle(color=if(isActive)accent else fg,fontSize=13.sp),
modifier=GlanceModifier.fillMaxWidth().padding(vertical=3.dp)
.clickable(actionRunCallback<OpenSessionInTermuxAction>(actionParametersOf(SessionIdKey to s.id,SessionPreviewKey to s.preview))),
)
}
}
Spacer(GlanceModifier.height(10.dp))
// The response area — a real scrollable region. Glance has no
// plain "scrollable Text" for widgets (a RemoteViews constraint);
// a single-item LazyColumn is the real, working way to get
// scrolling here. defaultWeight() lets it fill whatever vertical
// space the session list and mic row above/below don't use.
LazyColumn(modifier=GlanceModifier.fillMaxWidth().defaultWeight()){
item{
Text(
state.output.ifBlank{"Touche le micro pour parler à Claude."},
style=TextStyle(color=if(state.output.isBlank())dim else fg,fontSize=13.sp),
)
}
}
Spacer(GlanceModifier.height(10.dp))
Row(modifier=GlanceModifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
Text(
"🎙️",
style=TextStyle(fontSize=22.sp),
modifier=GlanceModifier
.background(accent)
.cornerRadius(28.dp)
.padding(horizontal=22.dp,vertical=10.dp)
.clickable(actionStartActivity<DictateActivity>()),
)
}
}
}}
