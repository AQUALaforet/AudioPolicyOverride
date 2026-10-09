package dev.aqua.audiopolicy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.aqua.audiopolicy.ForceUse
import dev.aqua.audiopolicy.data.AppChoice

@Composable
fun MainScreen(state: MainUiState, onPermission: () -> Unit, onReload: () -> Unit,
               onToggle: (Boolean) -> Unit, onRestore: () -> Unit,
               onAutomatic: (Boolean) -> Unit, onSelectPackages: (Set<String>) -> Unit,
               onLoadApps: () -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val connection = state.connection
    val snapshot = state.snapshot
    val canChange = connection.connected && !state.busy &&
        snapshot?.current?.let(ForceUse::supported) == true
    if (showPicker) {
        AppPicker(state.apps, state.automation.packages, state.appsLoading,
            onDismiss = { showPicker = false }, onSave = { onSelectPackages(it); showPicker = false })
    }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Audio Policy Override", style = MaterialTheme.typography.headlineMedium)
            Text("Shizuku を使ってシステムの音声ポリシーを切り替えます。")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Shizuku: " + when {
                        connection.running -> "起動中"
                        connection.installed -> "インストール済み・未起動"
                        else -> "未インストール"
                    })
                    Text("権限: " + when {
                        !connection.running || !connection.ready -> "確認できません"
                        connection.granted -> "許可済み"
                        else -> "未許可"
                    })
                    Text("UserService: " + when {
                        connection.connected -> "接続済み"
                        connection.connecting -> "接続中"
                        else -> "未接続"
                    })
                }
            }
            if (connection.running && connection.ready && !connection.granted) {
                Button(onClick = onPermission) { Text("Shizuku 権限を許可") }
            }
            if (!connection.running) {
                Text("Shizuku アプリをインストールし、ADB または root で起動してください。root は必須ではありません。")
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("FOR_SYSTEM = ${snapshot?.current ?: "未取得"}")
                    Text(ForceUse.label(snapshot?.current), style = MaterialTheme.typography.titleLarge)
                    Text(if (state.automaticActive) "自動無効化中" else if (state.manualEnabled) "手動無効化中" else "無効化していません")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(if (snapshot == null) "Override 状態を確認中"
                                else if (state.manualEnabled) "手動 Override enabled" else "手動 Override disabled")
                            Text("ON: FORCE_NONE に変更\nOFF: 保存した元の値へ復元", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = state.manualEnabled,
                            onCheckedChange = onToggle,
                            enabled = canChange && (!state.manualEnabled || snapshot?.canRestore == true),
                            modifier = Modifier.semantics { contentDescription = "システム音声ポリシーの Override" })
                    }
                    Text("保存した元の値: ${snapshot?.record?.originalForceUse ?: "なし"}")
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("アプリごとの自動切替", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(checked = state.automation.enabled, onCheckedChange = onAutomatic,
                            enabled = state.settingsReady && !state.busy && (state.automation.enabled ||
                                (canChange && state.automation.packages.isNotEmpty())),
                            modifier = Modifier.semantics { contentDescription = "アプリごとの自動切替" })
                    }
                    Text(when {
                        !state.automation.enabled -> "自動切替 OFF"
                        !state.monitorRunning -> "監視を開始しています"
                        !connection.connected -> "Shizuku 接続待ち"
                        state.automaticSuspended -> "一時停止中・再読み込みまたは復元してください"
                        state.manualEnabled -> "監視中・手動 ON を優先"
                        state.automaticActive -> "監視中・対象アプリを無効化中"
                        else -> "監視中・対象アプリの起動待ち"
                    })
                    val labels = state.automation.packages.sorted().map { pkg -> state.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg }
                    Text("対象アプリ: ${labels.size} 個")
                    if (labels.isNotEmpty()) Text(labels.joinToString("、"))
                    OutlinedButton(onClick = { onLoadApps(); showPicker = true }, enabled = state.settingsReady && !state.busy,
                        modifier = Modifier.fillMaxWidth()) { Text("対象アプリを選択（複数選択）") }
                    Text("選択したアプリが前面にある間だけ無効化します。離れて約0.8秒後に元へ戻します。",
                        style = MaterialTheme.typography.bodySmall)
                    if (!state.notificationsEnabled) Text("通知が許可されていません。監視の通知を表示するには、アプリの通知を許可してください。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = onReload, enabled = !state.busy && !connection.connecting,
                modifier = Modifier.fillMaxWidth()) { Text("再読み込み / 再接続") }
            Button(onClick = onRestore, enabled = canChange && snapshot?.canRestore == true,
                modifier = Modifier.fillMaxWidth()) { Text(if (state.automation.enabled) "復元して自動切替を停止" else "保存した元の値へ復元") }
            if (state.busy) CircularProgressIndicator(Modifier.semantics { contentDescription = "処理中" })
            snapshot?.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            (state.error ?: connection.error)?.let { Text("エラー: $it", color = MaterialTheme.colorScheme.error) }
            Text("効果は Android / メーカー実装に依存します。すべてのカメラ音が変わるとは限りません。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AppPicker(apps: List<AppChoice>, selected: Set<String>, loading: Boolean,
                      onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var checked by rememberSaveable(stateSaver = listSaver<Set<String>, String>(
        save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(selected.toSet()) }
    var query by rememberSaveable { mutableStateOf("") }
    val choices = remember(apps, selected) {
        (apps + selected.filter { pkg -> apps.none { it.packageName == pkg } }.map { AppChoice(it, "$it（一覧にないアプリ）") })
            .sortedWith(compareByDescending<AppChoice> { it.packageName in selected }.thenBy { it.label })
    }
    val filtered = remember(choices, query) { choices.filter { it.label.contains(query, true) || it.packageName.contains(query, true) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("対象アプリを選択") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("アプリ名・パッケージ名で検索") }, singleLine = true)
                Text("${checked.size} 個選択中")
                if (loading) Text("アプリ一覧を読み込み中…")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(Modifier.fillMaxWidth().toggleable(value = app.packageName in checked, role = Role.Checkbox,
                            onValueChange = { value -> checked = if (value) checked + app.packageName else checked - app.packageName })
                            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = app.packageName in checked, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                Text(app.label)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (filtered.isEmpty() && !loading) Text("該当するアプリがありません。")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(checked) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
}
