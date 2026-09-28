package com.aiwa.widget
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.*
// Reported live: "mon widget n'a aucun style... je veux id session
// (cliquable pour l'ouvrir dans Claude Code), le bouton micro, et le
// champs de sa réponse (avec scroll). Rien d'autre." — stripped down to
// exactly these three real elements instead of the earlier
// header/status/multi-session-list/multiple-redundant-icons design.
class AiwaWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){
val state=AiwaRepository.state.value
provideContent{Content(state)}}
// Glance's ColorProvider(Int) overload takes a @ColorRes RESOURCE id,
// not a raw packed color — passing android.graphics.Color.rgb(...)/
// .WHITE/.LTGRAY straight into it compiles fine, Int being Int, but
// crashes at real widget-render time on device. Wrapping every raw
// android.graphics.Color int in Compose's own Color(Int) first (same
// 0xAARRGGBB packing, just the real value type ColorProvider wants)
// is the real fix — confirmed live earlier in this project.
private fun rgb(colorInt:Int)=ColorProvider(androidx.compose.ui.graphics.Color(colorInt))
@Composable private fun Content(state:AiwaState){
val accent=rgb(android.graphics.Color.rgb(122,162,255))
val dim=rgb(android.graphics.Color.rgb(150,150,160))
val fg=rgb(android.graphics.Color.rgb(228,228,235))
Column(
modifier=GlanceModifier.fillMaxSize()
.background(rgb(android.graphics.Color.rgb(22,22,28)))
.cornerRadius(24.dp)
.padding(16.dp),
horizontalAlignment=Alignment.Start,
){
// Session id — tappable to open the REAL Claude Code CLI session
// directly in Termux (OpenSessionInTermuxAction), not Aiwa's own
// app screen, per explicit request.
Text(
if(state.sessionId!=null)"🗂  ${state.session}" else "🗂  aucune session",
style=TextStyle(color=accent,fontSize=14.sp,fontWeight=FontWeight.Medium),
modifier=GlanceModifier.clickable(actionRunCallback<OpenSessionInTermuxAction>()),
)
Spacer(GlanceModifier.height(12.dp))
// The response area — a real scrollable region. Glance has no
// plain "scrollable Text" for widgets (a RemoteViews constraint);
// a single-item LazyColumn is the real, working way to get
// scrolling here. defaultWeight() lets it fill whatever vertical
// space the session line and mic row above/below don't use.
LazyColumn(modifier=GlanceModifier.fillMaxWidth().defaultWeight()){
item{
Text(
state.output.ifBlank{"Touche le micro pour parler à Claude."},
style=TextStyle(color=if(state.output.isBlank())dim else fg,fontSize=13.sp),
)
}
}
Spacer(GlanceModifier.height(12.dp))
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
