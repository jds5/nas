package org.rokano.nasremote.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.rokano.nasremote.RemoteState
import org.rokano.nasremote.RemoteViewModel

/** Native question form. The terminal adapter remains outside the presentation layer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionSheet(state: RemoteState, model: RemoteViewModel) {
    if (!state.questionsOpen) return
    val enabled = state.connected && !state.busy && !state.uncertain && state.screenToken != null && state.chatError == null
    ModalBottomSheet(onDismissRequest = model::closeQuestions, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Codex 的问题", style = MaterialTheme.typography.headlineSmall)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            AnimatedContent(targetState = state.question, contentKey = { it?.id }, label = "question") { question ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (question == null) {
                        Text(when {
                            state.busy -> "正在打开问题…"
                            state.uncertain -> "上次操作结果需要核对。"
                            state.questionClosed -> "点击打开待回答的问题。"
                            state.questionHint -> "暂时无法完整显示这个问题，请使用控制面板查看。"
                            else -> "当前没有待回答的问题。"
                        })
                        if (state.questionClosed) FilledTonalButton(onClick = { model.openQuestions(state.screenToken) }, enabled = enabled) { Text("打开问题") }
                    } else {
                        if (question.count > 1) Text("第 ${question.index} / ${question.count} 题", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(question.title, style = MaterialTheme.typography.titleLarge)
                        val current = question.id == state.question?.id
                        val choice = if (question.freeText) 0 else state.questionChoice
                        if (!question.freeText) question.options.forEachIndexed { index, label ->
                            val selected = current && state.questionChoice == index
                            Surface(shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled && current, role = Role.RadioButton,
                                    onClick = { model.chooseAnswer(index) }).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selected, onClick = null)
                                    Text(if (index == question.options.lastIndex) "自定义回答" else label, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        val other = current && choice == question.options.lastIndex
                        if (other) OutlinedTextField(state.answerDraft, model::answerDraft, modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy, label = { Text("你的回答") }, placeholder = { Text("输入你自己的想法") }, singleLine = true,
                            supportingText = { Text("提交后直接发送给 Codex") })
                        Button(onClick = {
                            choice?.let { model.submitAnswer(question, it, state.answerDraft, state.screenToken) }
                        }, enabled = enabled && current && choice != null && (!other || state.answerDraft.isNotBlank()), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(if (state.busy) "正在提交…" else "提交回答")
                        }
                        if (question.count > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { model.questionPage(false, state.screenToken) }, enabled = enabled && current && question.previous) { Text("上一题") }
                            TextButton(onClick = { model.questionPage(true, state.screenToken) }, enabled = enabled && current && question.next) { Text("下一题") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { model.deferQuestions(state.screenToken) }, enabled = !state.busy && (state.question == null || enabled)) { Text("稍后回答") }
                TextButton(onClick = { model.panel(true) }, enabled = !state.busy) { Text(if (state.uncertain) "核对操作结果" else "控制面板") }
            }
        }
    }
}
