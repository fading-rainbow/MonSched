package cn.qingke.schedule.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.qingke.schedule.data.SyncConnection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun SyncSettings(connection: SyncConnection?, onLogin: () -> Unit, onEnabled: (Boolean) -> Unit,
    onNow: () -> Unit, onForget: () -> Unit, onReview: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("每日同步官方课表", fontWeight = FontWeight.SemiBold)
                Text(connection?.schoolName?.takeIf { it.isNotBlank() } ?: "绑定当前课表的学校与学期", style = MaterialTheme.typography.bodySmall)
            }
            if (connection != null) Switch(connection.enabled, onEnabled)
        }
        if (connection == null) {
            Text("首次登录时选择记住账号密码。确认导入后加密保存在本机，记住所选学期和课表页面。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("登录并设置同步") }
        } else {
            Text(connection.message, style = MaterialTheme.typography.bodySmall,
                color = if (connection.status in setOf("error", "needs_login", "review")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            val formatted = if (connection.lastSuccess == 0L) "尚未完成" else Instant.ofEpochMilli(connection.lastSuccess).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            Text("上次成功检查：$formatted", style = MaterialTheme.typography.labelSmall)
            if (connection.status == "review") Button(onClick = onReview, enabled = connection.enabled, modifier = Modifier.fillMaxWidth()) { Text("查看并确认官方变化") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNow, enabled = connection.enabled && connection.status !in setOf("syncing", "needs_login", "review"), modifier = Modifier.weight(1f)) { Text("立即同步") }
                OutlinedButton(onClick = onLogin, modifier = Modifier.weight(1f)) { Text("重新登录") }
            }
            TextButton(onClick = onForget) { Text("清除记住的登录信息", color = MaterialTheme.colorScheme.error) }
        }
        Text("每天本地时间 0 点后检查，系统省电或断网可能延迟，打开 App 会补查。验证码或统一认证需要手动处理。同步仅更新已绑定的官方课程，自建课程保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
