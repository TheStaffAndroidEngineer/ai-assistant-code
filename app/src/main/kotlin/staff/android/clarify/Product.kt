package staff.android.clarify
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
const val ABOUT="Clarify retrieves original local handbook passages using word overlap and reveals them over time. Generation is deterministic, not a real language model. Saving a cited passage requires your separate confirmation. No external AI provider or API key is used; requests use the local HTTP fixture."
fun validateView(d:JSONObject){
    require(d.get("generation") is Int)
    d.getJSONArray("notes")
    d.getJSONArray("documents")
    require(d.has("run"))
    if (d.isNull("run")) return
    val r = d.getJSONObject("run")
    require(r.getInt("generation") == d.getInt("generation"))
    require(r.getString("status") in setOf("RUNNING", "COMPLETE", "CANCELLED"))
    require(r.getString("visible").length <= 1000 && r.get("saved") is Boolean)
    r.getString("question")
}
fun grant(r:JSONObject)=JSONObject().put("runId",r.getString("id")).put("generation",r.getInt("generation"))
@Composable fun Product(state: CaseState, model: CaseModel, question: String, changeQuestion: (String) -> Unit) {
    val run = state.data?.optJSONObject("run")
    var sourceOpen by remember(run?.optString("id"), run?.optInt("generation")) { mutableStateOf(false) }
    var saveOpen by remember(run?.optString("id"), run?.optInt("generation")) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("STUDIO HANDBOOK", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("Local retrieval demo · no language model", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ClarifyRecovery(state, model)
            if (run == null) {
                Text("Find the detail you need.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Ask about studio access, equipment or shared spaces. Open the cited handbook passage before relying on an answer.", style = MaterialTheme.typography.bodyLarge)
                if (state.data == null) Text(if (state.busy) "Opening the local handbook…" else "The handbook is unavailable. Connect to the local service and refresh.")
            } else {
                Text(run.getString("question"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(when (run.getString("status")) { "RUNNING" -> "Response arriving"; "CANCELLED" -> "Response stopped"; else -> "Retrieved answer" }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(run.getString("visible").ifBlank { "Looking in the local handbook…" }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                run.optJSONObject("citation")?.let { citation -> OutlinedButton(onClick = { sourceOpen = true }, modifier = Modifier.fillMaxWidth().testTag("source")) { Text("Read source: ${citation.getString("title")}") } }
                if (run.getString("status") == "RUNNING") OutlinedButton(onClick = { model.send("cancel", grant(run)) }, enabled = ready(state), modifier = Modifier.testTag("cancel")) { Text("Stop response") }
                if (run.getString("status") == "COMPLETE" && !run.isNull("proposalId")) {
                    if (run.getBoolean("saved")) Text("Passage saved · find it in Saved notes", color = MaterialTheme.colorScheme.primary)
                    else Button(onClick = { saveOpen = true }, enabled = ready(state), modifier = Modifier.testTag("review-save")) { Text("Review save to notes") }
                }
                TextButton(onClick = { model.send("new") }, enabled = ready(state), modifier = Modifier.testTag("new")) { Text("New conversation") }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = question, onValueChange = { if (it.length <= 300) changeQuestion(it) }, label = { Text("Ask about the studio") }, modifier = Modifier.fillMaxWidth().testTag("question"), minLines = 1, maxLines = 3, shape = RoundedCornerShape(16.dp))
                Button(onClick = { model.send("ask", JSONObject().put("question", question)) }, enabled = ready(state) && question.isNotBlank() && state.data != null, modifier = Modifier.align(Alignment.End).testTag("ask")) { Text("Ask handbook") }
            }
        }
    }
    val citation = run?.optJSONObject("citation")
    if (sourceOpen && citation != null) AlertDialog(onDismissRequest = { sourceOpen = false }, title = { Text(citation.getString("title")) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Original local handbook passage", style = MaterialTheme.typography.labelLarge); Text(citation.getString("text")) } }, confirmButton = { TextButton(onClick = { sourceOpen = false }) { Text("Back to answer") } })
    if (saveOpen && run != null && citation != null) AlertDialog(onDismissRequest = { saveOpen = false }, title = { Text("Save this passage?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(citation.getString("title"), fontWeight = FontWeight.Bold)
            Text(citation.getString("text"))
            Text("This saves the exact cited passage to notes on the local service. It does not send a message or change studio policy.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { saveOpen = false; model.send("confirm", grant(run).put("proposalId", run.getString("proposalId"))) }, enabled = ready(state) && run.getString("status") == "COMPLETE" && !run.getBoolean("saved"), modifier = Modifier.testTag("confirm")) { Text("Confirm save to notes") } }, dismissButton = { TextButton(onClick = { saveOpen = false }) { Text("Keep reading") } })
}

@Composable fun ClarifyRecovery(state: CaseState, model: CaseModel) {
    state.problem?.let { InfoCard("Connection and recovery", it) }
    state.pending?.let {
        InfoCard("Waiting for confirmation", "Your request is saved. Retry the same request before making another change.")
        Button(onClick = model::retry, enabled = !state.busy && !state.storageBlocked, modifier = Modifier.testTag("retry")) { Text("Retry saved request") }
    }
    state.outcome?.takeIf { it.has("reason") }?.let { InfoCard("Request result", it.getString("reason")) }
}

@Composable fun ClarifyNotes(state: CaseState) {
    Text("Saved notes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    val notes = state.data?.optJSONArray("notes")
    if (notes == null || notes.length() == 0) InfoCard("Keep a useful passage", "Ask the handbook, read its source and confirm a save. Your selected passages will appear here.")
    else for (i in 0 until notes.length()) {
        val note = notes.getJSONObject(i)
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(note.getString("source"), fontWeight = FontWeight.Bold)
                Text(note.getString("text"), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
