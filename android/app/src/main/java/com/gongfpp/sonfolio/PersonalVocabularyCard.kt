package com.gongfpp.sonfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gongfpp.sonfolio.data.local.PersonalVocabularyEntity
import kotlinx.coroutines.*

/**
 * 个人词汇管理：候选词来自用户对转写的修正，只有确认过的词才作为本地 Qwen3-ASR 的热词。
 * 这里也是唯一能手动增删个人词汇的地方，避免“悄悄改识别行为”。
 */
@Composable internal fun PersonalVocabularyCard() {
    val app = LocalContext.current.applicationContext as SonfolioApplication
    val vocabulary by remember { app.vocabularyRepository.observeAll() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var manual by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val accepted = vocabulary.filter { it.status == PersonalVocabularyRepository.STATUS_ACCEPTED }
    val candidates = vocabulary.filter { it.status == PersonalVocabularyRepository.STATUS_CANDIDATE }

    Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("个人词汇", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "候选词来自你对转写的修正，只有确认过的词才会成为本地识别提示（热词）。目前仅对本地 Qwen3-ASR 生效，不上传、不改写已有文字。",
                fontSize = 12.sp,
            )

            if (accepted.isEmpty()) {
                Text("还没有个人词汇。修正转写时出现的专有名词会先进入下方候选。", fontSize = 12.sp)
            } else {
                Text("已加入（${accepted.size}）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                accepted.forEach { entry ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.term, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        Text("命中 ${entry.seenCount}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        TextButton(enabled = !busy, onClick = {
                            scope.launch { runCatching { app.vocabularyRepository.remove(entry.term) } }
                        }) { Text("删除") }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    manual, { manual = it }, Modifier.weight(1f), singleLine = true, enabled = !busy,
                    label = { Text("手动添加词汇") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                TextButton(enabled = !busy && manual.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val added = withContext(Dispatchers.IO) { app.vocabularyRepository.addManual(manual) }
                            manual = ""; message = "已加入「$added」"
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { message = error.message ?: "添加失败" }
                        finally { busy = false }
                    }
                }) { Text("添加") }
            }

            if (candidates.isNotEmpty()) {
                Text("候选词（${candidates.size}）", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                candidates.forEach { term: PersonalVocabularyEntity ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(term.term, fontSize = 13.sp)
                            Text(candidateDetail(term), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        TextButton(enabled = !busy, onClick = {
                            scope.launch { runCatching { app.vocabularyRepository.accept(term.term) } }
                        }) { Text("加入") }
                        TextButton(enabled = !busy, onClick = {
                            scope.launch { runCatching { app.vocabularyRepository.ignore(term.term) } }
                        }) { Text("忽略") }
                    }
                }
                Text(
                    "命中 ${PersonalVocabularyRepository.AUTO_ACCEPT_SUPPORT} 次后会直接加入，不再逐个确认。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                )
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, fontSize = 12.sp) }
        }
    }
}

private fun candidateDetail(term: PersonalVocabularyEntity): String {
    val original = term.sourceOriginal?.takeIf { it.isNotBlank() }
    val corrected = term.sourceCorrected?.takeIf { it.isNotBlank() }
    return if (original != null && corrected != null) "命中 ${term.seenCount} 次 · 「$original」→「$corrected」"
    else "命中 ${term.seenCount} 次"
}
