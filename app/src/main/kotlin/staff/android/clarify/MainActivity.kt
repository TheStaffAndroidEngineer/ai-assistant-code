package staff.android.clarify

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity:ComponentActivity(){
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted ->
        if(granted)ViewModelProvider(this)[CaseModel::class.java].refresh()
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        if(Build.VERSION.SDK_INT>=37&&checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)!=PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val model=ViewModelProvider(this)[CaseModel::class.java]
        setContent{ProductTheme{val state by model.state.collectAsStateWithLifecycle();CaseScreen(state,model)}}
    }
}
val BodyFont=FontFamily(Font(R.font.google_sans_regular),Font(R.font.google_sans_bold,FontWeight.Bold))
val HeadingFont=FontFamily(Font(R.font.sora_bold,FontWeight.Bold))
@Composable fun ProductTheme(content:@Composable ()->Unit){
    val scheme=if(isSystemInDarkTheme())darkColorScheme(
        primary=Color(0xFF9ED9E8),onPrimary=Color(0xFF124452),background=Color(0xFF15252B),onBackground=Color(0xFFF0F3EE),
        surface=Color(0xFF24373F),onSurface=Color(0xFFF0F3EE),surfaceVariant=Color(0xFF354F5A),onSurfaceVariant=Color(0xFFCBE2EA),
        secondaryContainer=Color(0xFF285C6C),onSecondaryContainer=Color(0xFFD4F0F8),outline=Color(0xFF99ADA0),outlineVariant=Color(0xFF526659))
    else lightColorScheme(
        primary=Color(0xFF246878),onPrimary=Color(0xFFFFFFFF),background=Color(0xFFF0F6F8),onBackground=Color(0xFF263C43),
        surface=Color(0xFFFFFFFF),onSurface=Color(0xFF263C43),surfaceVariant=Color(0xFFDFEBEF),onSurfaceVariant=Color(0xFF55737C),
        secondaryContainer=Color(0xFFD5ECF2),onSecondaryContainer=Color(0xFF1A5261),outline=Color(0xFF73929D),outlineVariant=Color(0xFFC0D8E0))
    val defaults=Typography()
    MaterialTheme(scheme,typography=defaults.copy(bodyLarge=defaults.bodyLarge.copy(fontFamily=BodyFont),
        bodyMedium=defaults.bodyMedium.copy(fontFamily=BodyFont),titleMedium=defaults.titleMedium.copy(fontFamily=BodyFont),
        labelLarge=defaults.labelLarge.copy(fontFamily=BodyFont)),content=content)
}
@Composable fun CaseScreen(state: CaseState, model: CaseModel) {
    var route by rememberSaveable { mutableStateOf("ask") }
    var question by rememberSaveable { mutableStateOf("How do I access the studio?") }
    var previousRoute by rememberSaveable { mutableStateOf("ask") }
    BackHandler(enabled = route != "ask") { route = if (route == "lab") previousRoute else "ask" }
    val run = state.data?.optJSONObject("run")
    LaunchedEffect(run?.optString("id"), run?.optString("status"), state.problem, state.pending != null) {
        if (run?.optString("status") == "RUNNING" && state.problem == null && state.pending == null) while (true) { delay(1000); model.refresh() }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.statusBarsPadding().fillMaxWidth()) {
                AppChrome("Clarify", statusInsets = false) {
                    TextButton(onClick = model::refresh, enabled = !state.busy && !state.storageBlocked, modifier = Modifier.testTag("refresh")) { Text("Refresh") }
                    TextButton(onClick = { if (route == "lab") route = previousRoute else { previousRoute = route; route = "lab" } }, modifier = Modifier.testTag("lab-tab")) { Text(if (route == "lab") "Back" else "Lab") }
                }
                if (route != "lab") Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    TextButton(onClick = { route = "ask" }, modifier = Modifier.weight(1f).testTag("ask-tab").semantics { selected = route == "ask"; role = Role.Tab }) { Text("Handbook") }
                    TextButton(onClick = { route = "notes" }, modifier = Modifier.weight(1f).testTag("notes-tab").semantics { selected = route == "notes"; role = Role.Tab }) { Text("Saved notes") }
                }
            }
        }
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 800.dp).fillMaxSize()) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("busy"))
                when (route) {
                    "ask" -> Product(state, model, question, { question = it })
                    "notes" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { ClarifyNotes(state); ClarifyRecovery(state, model) }
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionTitle("Handbook laboratory")
                        Text(ABOUT)
                        run?.let { Text("Run ${it.getString("id")}\nGeneration ${it.getInt("generation")}\nStatus ${it.getString("status")}\nProposal ${it.opt("proposalId")}", style = MaterialTheme.typography.bodySmall) }
                        ClarifyRecovery(state, model)
                    }
                }
            }
        }
    }
}
@Composable fun InfoCard(title:String,body:String){
    Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(title,fontWeight=FontWeight.Bold);Text(body,style=MaterialTheme.typography.bodyLarge)
        }
    }
}
@Composable fun SectionTitle(title:String){Text(title,fontFamily=HeadingFont,fontSize=27.sp,lineHeight=34.sp)}
fun ready(state:CaseState)=!state.busy&&!state.storageBlocked&&state.pending==null

@Composable
private fun AppChrome(title: String, statusInsets: Boolean = true, actions: @Composable () -> Unit) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    BoxWithConstraints((if (statusInsets) Modifier.statusBarsPadding() else Modifier).fillMaxWidth().padding(horizontal = 16.dp)) {
        val needed = (title.length * 13 + if (title == "Fieldnotes") 290 else 190).dp * fontScale
        if (maxWidth < needed) {
            Column(Modifier.fillMaxWidth()) {
                Text(title, fontFamily = HeadingFont, fontSize = 22.sp, lineHeight = 28.sp, modifier = Modifier.padding(top = 10.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontFamily = HeadingFont, fontSize = 22.sp, lineHeight = 28.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                actions()
            }
        }
    }
}
