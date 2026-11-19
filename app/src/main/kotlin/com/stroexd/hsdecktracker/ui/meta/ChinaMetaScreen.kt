@file:OptIn(ExperimentalMaterial3Api::class)

package com.stroexd.hsdecktracker.ui.meta

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.core.meta.ChinaMeta
import com.stroexd.hsdecktracker.core.meta.ChinaMetaKind
import com.stroexd.hsdecktracker.core.meta.ChinaMetaRow
import com.stroexd.hsdecktracker.core.meta.ChinaMetaSnapshot
import com.stroexd.hsdecktracker.core.meta.ChinaMetaStats
import com.stroexd.hsdecktracker.ui.copyToClipboard
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

private val ranks = linkedMapOf(
    "all" to "全部段位",
    "legend" to "传说",
    "legend_top_1000" to "传说前 1000",
    "diamond1" to "钻石 4–1",
    "diamond2" to "钻石 10–5",
    "other" to "其余段位",
)

private val periods = listOf(1, 3, 7, 30)
private val classNames = mapOf(
    "01" to "战士", "warrior" to "战士", "02" to "萨满祭司", "shaman" to "萨满祭司",
    "03" to "潜行者", "rogue" to "潜行者", "04" to "圣骑士", "paladin" to "圣骑士",
    "05" to "猎人", "hunter" to "猎人", "06" to "德鲁伊", "druid" to "德鲁伊",
    "07" to "术士", "warlock" to "术士", "08" to "法师", "mage" to "法师",
    "09" to "牧师", "priest" to "牧师", "10" to "恶魔猎手", "demonhunter" to "恶魔猎手",
    "11" to "死亡骑士", "deathknight" to "死亡骑士",
)

private val shanghaiTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.of("Asia/Shanghai"))

private enum class StatSort(val label: String, val score: (ChinaMetaStats) -> Double?) {
    WIN_RATE("按胜率", { it.winRate }),
    PICK_RATE("按出场率", { it.pickRate }),
    EFFICIENCY("按上分效率", { it.netWinsPerHour }),
}

@Composable
fun ChinaMetaScreen(navController: NavHostController) {
    var wild by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(ChinaMetaKind.DECKS) }
    var rank by rememberSaveable { mutableStateOf("all") }
    var days by rememberSaveable { mutableStateOf(7) }
    var sort by rememberSaveable { mutableStateOf(StatSort.PICK_RATE) }
    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    val snapshots = remember { mutableStateMapOf<ChinaMetaKind, ChinaMetaSnapshot>() }
    val errors = remember { mutableStateMapOf<ChinaMetaKind, String>() }
    val activeKind = kind

    LaunchedEffect(activeKind, refresh) {
        loading = true
        errors.remove(activeKind)
        try {
            snapshots[activeKind] = ChinaMeta.fetch(activeKind)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            errors[activeKind] = "加载失败，请稍后重试。"
        } finally {
            loading = false
        }
    }

    val snapshot = snapshots[kind]
    val format = if (wild) "FT_WILD" else "FT_STANDARD"
    val rows = remember(snapshot, format, rank, days, sort) {
        snapshot?.rows.orEmpty().mapNotNull { entry ->
            if (entry.format != format || entry.rank != rank) return@mapNotNull null
            val stats = entry.periods[days]?.takeIf { it.games > 0 } ?: return@mapNotNull null
            entry to stats
        }.sortedByDescending { sort.score(it.second) ?: Double.NEGATIVE_INFINITY }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("国服环境") },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = { refresh++ }, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新数据")
                }
            },
        )
    }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !wild, onClick = { wild = false }, label = { Text("标准") })
                FilterChip(selected = wild, onClick = { wild = true }, label = { Text("狂野") })
                FilterChip(selected = kind == ChinaMetaKind.DECKS, onClick = { kind = ChinaMetaKind.DECKS }, label = { Text("卡组") })
                FilterChip(selected = kind == ChinaMetaKind.CLASSES, onClick = { kind = ChinaMetaKind.CLASSES }, label = { Text("职业") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatFilter(ranks.getValue(rank), ranks.keys.toList(), { ranks.getValue(it) }, Modifier.weight(1f)) { rank = it }
                StatFilter("近 $days 天", periods, { "近 $it 天" }, Modifier.weight(1f)) { days = it }
                StatFilter(sort.label, StatSort.entries, StatSort::label, Modifier.weight(1f)) { sort = it }
            }
            val updatedAt = snapshot?.modifiedAt?.let { shanghaiTime.format(it) }
            val source = if (updatedAt == null) "数据来源 · 网易炉石盒子" else "网易炉石盒子 · $updatedAt 北京时间"
            Text(source, style = MaterialTheme.typography.bodySmall)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            errors[kind]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (snapshot != null && rows.isEmpty()) Text("这个筛选条件暂无对局样本。")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(rows) { (row, stats) -> StatCard(kind, row, stats) }
            }
        }
    }
}

@Composable
private fun <T> StatFilter(title: String, options: List<T>, label: (T) -> String, modifier: Modifier, select: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = {
                    open = false
                    select(option)
                })
            }
        }
    }
}

@Composable
private fun StatCard(kind: ChinaMetaKind, row: ChinaMetaRow, stats: ChinaMetaStats) {
    val context = LocalContext.current
    val classLabel = classNames[row.classId]
    val name = when (kind) {
        ChinaMetaKind.DECKS -> row.title
        ChinaMetaKind.CLASSES -> classLabel ?: row.title
    }
    val sample = NumberFormat.getIntegerInstance(Locale.SIMPLIFIED_CHINESE).format(stats.games)
    val winRate = percent(stats.winRate)
    val pickRate = percent(stats.pickRate)
    val efficiency = stats.netWinsPerHour?.let { String.format(Locale.ROOT, "%+.2f", it) } ?: "—"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            val classText = if (kind == ChinaMetaKind.DECKS && classLabel != null) "$classLabel · " else ""
            Text("${classText}$sample 局", style = MaterialTheme.typography.bodySmall)
            Text("胜率 $winRate · 出场率 $pickRate", style = MaterialTheme.typography.bodyMedium)
            Text("上分效率 $efficiency 净胜局/小时", style = MaterialTheme.typography.bodySmall)
            if (kind == ChinaMetaKind.DECKS) row.code?.let { code ->
                TextButton(onClick = { context.copyToClipboard("卡组码", code, "已复制卡组码") }) {
                    Text("复制卡组码")
                }
            }
        }
    }
}

private fun percent(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "—"
