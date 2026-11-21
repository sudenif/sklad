package ru.parts.ledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.parts.ledger.domain.*
import ru.parts.ledger.domain.Target
import java.time.YearMonth

@Composable fun OperationsScreen(state:LedgerState,month:YearMonth,vm:LedgerViewModel,onOperation:(OperationRow)->Unit) {
    var kind by rememberSaveable {mutableStateOf("Все")}
    var allDates by rememberSaveable {mutableStateOf(false)}
    val rows=operations(state).filter {(allDates || it.date.take(7)==month.toString()) && (kind=="Все" || it.kind.name==kind)}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        item {Button(onClick={vm.open("expense")},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp).heightIn(min=48.dp)) {Text("Добавить расход")}}
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {TextButton(onClick={vm.changeMonth(-1)}) {Text("Раньше")};Text(monthName(month),Modifier.padding(top=12.dp),style=MaterialTheme.typography.titleSmall);TextButton(onClick={vm.changeMonth(1)}) {Text("Позже")}}}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {FilterChip(selected=!allDates,onClick={allDates=false},label={Text("За месяц")});FilterChip(selected=allDates,onClick={allDates=true},label={Text("За всё время")})}}
        item {PickField("Тип операции",kind,listOf("Все" to "Все",Record.PURCHASE.name to "Покупки",Record.SALE.name to "Продажи",Record.EXPENSE.name to "Расходы"),{kind=it})}
        if(rows.isEmpty())item {EmptyState("Записей нет","Измените период или добавьте операцию")}
        items(rows,key={"${it.kind}:${it.id}"}) {row->LedgerRow(row.title,"${row.date} · ${if(row.active)row.subtitle else "Отменено"}",row.amount?.let(Money::format)?:"") {onOperation(row)}}
        item {Spacer(Modifier.height(96.dp))}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun OperationDetail(row:OperationRow,state:LedgerState,vm:LedgerViewModel,onDismiss:()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(row.title,style=MaterialTheme.typography.titleLarge)
            Text("${row.date} · ${if(row.active)row.subtitle else "Отменено"}",style=MaterialTheme.typography.bodyMedium,color=Muted)
            row.amount?.let {Text(Money.format(it),style=MaterialTheme.typography.headlineMedium)}
            when(row.kind) {
                Record.PURCHASE->{val p=state.purchases.first {it.id==row.id};if(p.extra>0)MoneyRow("Дополнительные закупочные расходы",p.extra);if(p.note.isNotBlank())Text(p.note)}
                Record.SALE->{val sale=state.sales.first {it.id==row.id};sale.lines.forEach {MoneyRow(it.name,it.cost)};sale.costs.forEach {MoneyRow(it.label,it.amount)};MoneyRow("Себестоимость",sale.cost);MoneyRow("Прибыль",sale.amount-sale.cost)}
                Record.EXPENSE->{val e=state.expenses.first {it.id==row.id};e.parts.forEach {p->MoneyRow(when(p.target) {Target.GENERAL->"Общие расходы";Target.ITEM->state.items.first {it.id==p.targetId}.name;Target.BUILD->state.builds.first {it.id==p.targetId}.name},p.amount)};if(e.note.isNotBlank())Text(e.note)}
                else->Unit
            }
            if(row.active) {
                InfoCard("Исправление суммы, даты или назначения пересчитает связанные сделки и прошлые месяцы. Прежние значения останутся в истории")
                Button(onClick={onDismiss();vm.correct(row)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Исправить запись")}
                TextButton(onClick={onDismiss();vm.cancellation.value=row}) {Text("Отменить операцию",color=Negative)}
            }
            SectionTitle("История записи")
            state.audit.filter {it.recordId==row.id}.reversed().forEach {a->
                if(a.action=="Disassemble") {
                    val parts=runCatching {LedgerJson.decodeFromString<List<Item>>(a.after)}.getOrDefault(emptyList())
                    Text("${parts.firstOrNull()?.availableFrom?:a.at.take(10)} · Разбор на детали",style=MaterialTheme.typography.bodySmall,color=Muted)
                    parts.forEach {MoneyRow(it.name,it.cost)}
                } else {
                    Text("${a.at.take(10)} · ${when(a.action) {"Correct"->"Исправление";"Cancel"->"Отмена";else->"Создание"}}",style=MaterialTheme.typography.bodySmall,color=Muted)
                }
                if(a.action!="Disassemble" && a.before.isNotEmpty()) {
                    val previous=runCatching {when(row.kind) {
                        Record.PURCHASE->LedgerJson.decodeFromString<PurchaseRecord>(a.before).let {"Было: ${it.date}, ${Money.format(it.total)} + ${Money.format(it.extra)}"}
                        Record.SALE->LedgerJson.decodeFromString<Sale>(a.before).let {"Было: ${it.date}, ${Money.format(it.amount)}"}
                        Record.EXPENSE->LedgerJson.decodeFromString<Expense>(a.before).let {"Было: ${it.date}, ${Money.format(it.total)}"}
                        Record.BUILD->""
                    }}.getOrDefault("")
                    if(previous.isNotBlank())Text(previous,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
