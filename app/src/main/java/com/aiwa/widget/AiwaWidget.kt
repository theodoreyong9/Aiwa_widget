package com.aiwa.widget
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
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
// Then again: "il faut juste que à côté de session dans le widget
// s'affiche la session en cours. Et que la liste des sessions
// existantes soit en déroulé" — the header now shows the CURRENT
// session inline, and the full list is collapsed by default, only
// taking up space once tapped open, via SESSIONS_EXPANDED_KEY below
// (a real per-widget-instance flag, so it survives updateAll calls).
private val SESSIONS_EXPANDED_KEY = booleanPreferencesKey("sessionsExpanded")

class ToggleSessionsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[SESSIONS_EXPANDED_KEY] = !(prefs[SESSIONS_EXPANDED_KEY] ?: false)
        }
        AiwaWidget().update(context, glanceId)
    }
}

// Reported live: "des fois la liste des sessions dans le widget
// disparaît" — provideGlance can run from a plain background refresh,
// not just a tap, and used to silently replace the list with an empty
// one on ANY transient listSessions() failure (backend momentarily
// busy, etc.), even though the real list hadn't actually changed.
// Caching the last successfully-fetched list here (a real, working
// list is always better than a fetch hiccup wiping the screen) means a
// blip no longer looks like every session vanished.
private var cachedSessions: List<SessionInfo> = emptyList()

class AiwaWidget:GlanceAppWidget(){override suspend fun provideGlance(context:Context,id:GlanceId){
val bridge=LocalClaudeBridge()
val sessions=try{bridge.listSessions()}catch(err:Exception){cachedSessions}
if(sessions.isNotEmpty())cachedSessions=sessions
// Reported live: "la synchronisation n'est pas top" — a previous
// version of this resync WROTE the result back into AiwaRepository, a
// single process-wide mutable singleton ALSO read by the app's own
// screen. That let two overlapping provideGlance calls (e.g. one still
// in flight from just before a session switch, one fired right after
// it) race to overwrite each other, and whichever happened to FINISH
// last won — not necessarily whichever was actually more current,
// which is exactly why the header kept showing a stale "aucune
// session" until some LATER, unrelated action forced yet another
// provideGlance to run and finally land correctly. Resolving this
// purely into a LOCAL copy of state, never written back, means this
// specific render can only ever reflect what THIS specific
// currentSessionId() call just returned — nothing else can clobber it
// afterward, and no other concurrent render can clobber IT either.
val realSessionId=try{bridge.currentSessionId()}catch(err:Exception){AiwaRepository.state.value.sessionId}
val displaySession=sessions.find{it.id==realSessionId}?.preview?.take(30)
?:realSessionId?.take(8)
?:"aucune session"
val state=AiwaRepository.state.value.copy(session=displaySession,sessionId=realSessionId)
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
val expanded=currentState<Preferences>()[SESSIONS_EXPANDED_KEY]?:false
Column(
modifier=GlanceModifier.fillMaxSize()
.background(rgb(android.graphics.Color.rgb(22,22,28)))
.cornerRadius(24.dp)
.padding(14.dp),
horizontalAlignment=Alignment.Start,
){
// Reported live: "à côté de session s'affiche la session en cours"
// — the header itself now carries the real current session (or
// "aucune session"), tappable to expand/collapse the full list
// below instead of that list always being visible and eating space
// above the response area.
Row(
modifier=GlanceModifier.fillMaxWidth().clickable(actionRunCallback<ToggleSessionsAction>()),
verticalAlignment=Alignment.CenterVertically,
){
Text(
"Session : ${state.session}",
style=TextStyle(color=dim,fontSize=11.sp,fontWeight=FontWeight.Medium),
modifier=GlanceModifier.defaultWeight(),
)
Text(if(expanded)"▾" else "▸",style=TextStyle(color=dim,fontSize=11.sp))
}
// The real session list — tapping a row opens it directly in the
// real Claude Code CLI via Termux (OpenSessionInTermuxAction), not
// Aiwa's own app screen, per explicit request. "+ Nouvelle session"
// has nothing to resume, so it only selects a fresh conversation.
// Only rendered at all once the header above is tapped open — see
// "déroulé" comment above.
if(expanded){
Spacer(GlanceModifier.height(4.dp))
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
