package ru.parts.ledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.parts.ledger.domain.Attention
import ru.parts.ledger.domain.AttentionRow
import ru.parts.ledger.domain.Money
import ru.parts.ledger.domain.Target

@Composable
fun AttentionSummary(vm: LedgerViewModel) {
    val rows by vm.attention.collectAsState()
    OutlinedCard(onClick = { vm.navigate("Внимание") }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Требуют внимания", style = MaterialTheme.typography.titleMedium)
            if (rows.isEmpty()) {
                Text("Пока нет залежавшихся товаров", style = MaterialTheme.typography.bodySmall, color = Muted)
            } else {
                Text(Money.format(rows.sumOf { it.cost }), style = MaterialTheme.typography.headlineSmall, color = Copper)
                Text("Позиций: ${rows.size} · с покупки 30 дней и больше", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
fun AttentionScreen(vm: LedgerViewModel, busy: Boolean) {
    val rows by vm.attention.collectAsState()
    var expensiveFirst by rememberSaveable { mutableStateOf(false) }
    val sorted = remember(rows, expensiveFirst) { if (expensiveFirst) rows.sortedByDescending { it.cost } else rows }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) item {
            EmptyState("Сейчас всё в порядке", "Здесь появятся непроданные детали и сборки, в которых деньги вложены 30 дней назад или раньше")
        } else {
            item {
                MoneyRow("В этих товарах", rows.sumOf { it.cost })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!expensiveFirst, { expensiveFirst = false }, label = { Text("По сроку") })
                    FilterChip(expensiveFirst, { expensiveFirst = true }, label = { Text("По сумме") })
                }
            }
            items(sorted, key = { it.id }) { row -> AttentionCard(row, busy, vm) }
        }
    }
}

@Composable
private fun AttentionCard(row: AttentionRow, busy: Boolean, vm: LedgerViewModel) {
    val timing = if (row.target == Target.BUILD) "С закупки старшей детали" else "С покупки"
    OutlinedCard(onClick = { vm.openAttention(row) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text("$timing · ${row.days} дн.", style = MaterialTheme.typography.bodyMedium,
                color = if (row.days >= Attention.LONG_STAY) Copper else Muted)
            MoneyRow("Вложено", row.cost)
            TextButton(onClick = { vm.defer(row) }, enabled = !busy) { Text("Отложить на 7 дней") }
        }
    }
}
