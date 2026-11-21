package ru.parts.ledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.parts.ledger.domain.*
import ru.parts.ledger.backup.BackupStatus
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

fun monthName(month:YearMonth)=month.month.getDisplayName(TextStyle.FULL_STANDALONE,Locale.forLanguageTag("ru")).replaceFirstChar {it.uppercase()}+" ${month.year}"
@Composable fun OverviewScreen(state:LedgerState,month:YearMonth,backup:BackupStatus,vm:LedgerViewModel,onOperation:(OperationRow)->Unit) {
    val computed by vm.overview.collectAsState()
    val data=computed?.takeIf {it.month==month}
    val report=data?.report?:MonthlyReport()
    val previous=data?.previous?:MonthlyReport()
    var picker by remember {mutableStateOf(false)}
    var monthText by remember(month){mutableStateOf(month.toString())}
    var invalidMonth by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={vm.changeMonth(-1)}) {Icon(Icons.Outlined.KeyboardArrowLeft,"Предыдущий месяц",Modifier.size(20.dp))}
            TextButton(onClick={picker=true},modifier=Modifier.weight(1f)) {Text(monthName(month),style=MaterialTheme.typography.titleLarge,color=Ink)}
            IconButton(onClick={vm.changeMonth(1)}) {Icon(Icons.Outlined.KeyboardArrowRight,"Следующий месяц",Modifier.size(20.dp))}
        }
        if(backup.folder.isBlank())OutlinedCard(onClick={vm.navigate("Копии")}) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("Настройте резервную копию",style=MaterialTheme.typography.titleSmall)
                Text("Выберите папку, чтобы сохранить учёт вне приложения",style=MaterialTheme.typography.bodySmall,color=Muted)
            }
        } else if(backup.error.isNotBlank())InfoCard("Не удалось обновить копию: ${backup.error}",true)
        AttentionSummary(vm)
        Spacer(Modifier.height(8.dp))
        Text("Итоговая прибыль",style=MaterialTheme.typography.bodyMedium,color=Muted)
        if(data==null)LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if(data==null)"Расчёт…" else Money.format(report.profit),style=MaterialTheme.typography.headlineLarge,color=if(report.profit<0)Negative else Ink)
        val difference=report.profit-previous.profit
        Text("${if(difference>0)"+" else ""}${Money.format(difference)} к предыдущему месяцу",style=MaterialTheme.typography.bodySmall,color=Muted)
        Text("После себестоимости и общих расходов · без расчёта налогов",style=MaterialTheme.typography.bodySmall,color=Muted)
        Spacer(Modifier.height(8.dp))
        Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                MoneyRow("Выручка · сделки: ${report.dealCount}",report.revenue)
                MoneyRow("Себестоимость продаж",report.cost)
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                MoneyRow("Прибыль сделок",report.dealProfit)
                MoneyRow("Общие расходы",report.overhead)
            }
        }
        SectionTitle("Деньги и остатки")
        MoneyRow("Покупки",report.purchases)
        MoneyRow("Выплаты по расходам",report.payouts)
        MoneyRow("Движение денег",report.cash,"Поступления минус оплаты за выбранный месяц")
        MoneyRow("В товарах на конец месяца",report.stock,"Стоимость непроданных деталей, сборок и прямых расходов")
        SectionTitle("Прибыль за полгода")
        val series=data?.months?:emptyList()
        val values=data?.profits?:emptyList()
        if(values.all {it==0L})Text("График появится после первых продаж или общих расходов",style=MaterialTheme.typography.bodyMedium,color=Muted)
        else {
            val max=values.maxOf {abs(it).toDouble()}.coerceAtLeast(1.0)
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                val baseline=if(values.any {it<0})size.height*0.5f else size.height-4f
                drawLine(Color(0xFFD4D4D4),Offset(0f,baseline),Offset(size.width,baseline),1.dp.toPx())
                val step=size.width/6
                values.forEachIndexed {i,v->
                    val h=(abs(v).toDouble()/max*(if(values.any {it<0})size.height*0.44 else size.height*0.88)).toFloat()
                    drawRect(if(v<0)Negative else if(i==5)Copper else Color(0xFFD4D4D4),Offset(step*i+step*0.25f,if(v>=0)baseline-h else baseline),Size(step*0.5f,h))
                }
            }
            Row(Modifier.fillMaxWidth()) {series.forEach {m->Text(m.month.getDisplayName(TextStyle.SHORT,Locale.forLanguageTag("ru")),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=Muted)}}
        }
        SectionTitle("Операции месяца","Все") {vm.navigate("Операции")}
        val rows=operations(state).filter {it.date.take(7)==month.toString()}
        if(rows.isEmpty())EmptyState("Записей пока нет","Добавьте покупку, продажу или расход")
        else rows.take(8).forEach {row->LedgerRow(row.title,"${row.date} · ${if(row.active)row.subtitle else "Отменено"}",row.amount?.let(Money::format)?:"",onClick={onOperation(row)})}
        Spacer(Modifier.height(96.dp))
    }
    if(picker)AlertDialog(onDismissRequest={picker=false},title={Text("Выбрать месяц")},text={Column {Input("Год и месяц: ГГГГ-ММ",monthText,{monthText=it;invalidMonth=false});if(invalidMonth)Text("Например, 2026-11",color=Negative)}},confirmButton={TextButton(onClick={runCatching {YearMonth.parse(monthText)}.onSuccess {vm.selectMonth(it);picker=false}.onFailure {invalidMonth=true}}){Text("Выбрать")}},dismissButton={TextButton(onClick={monthText=today().take(7);vm.selectMonth(YearMonth.parse(monthText));picker=false}){Text("Текущий")}})
}
