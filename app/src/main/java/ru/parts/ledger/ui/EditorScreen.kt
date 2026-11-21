package ru.parts.ledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import ru.parts.ledger.domain.*
import ru.parts.ledger.domain.Target

private fun objectOptions(state:LedgerState,target:Target,date:String):List<Pair<String,String>> = when(target) {
    Target.GENERAL->emptyList()
    Target.ITEM->Ledger.activeItems(state).filter {item->
        Ledger.availableSince(state,item)<=date && (Ledger.partsFrom(state,item.id).firstOrNull()?.availableFrom?.let {date<=it}?:true)
    }
        .map {it.id to "${it.name} · ${Money.format(it.cost)} · ${Ledger.purchase(state,it).date}"}
    Target.BUILD->state.builds.filter {it.active}.map {it.id to it.name}
}
@Composable fun EditorScreen(draft:EditorDraft,state:LedgerState,busy:Boolean,error:String,vm:LedgerViewModel) {
    val title=when(draft.kind) {"purchase"->if(draft.contextId.isBlank())"Покупка" else "Деталь для сборки";"build"->"Новая сборка";"reserve"->"Деталь со склада";"release"->"Вернуть на склад";"expense"->"Расход";"sale"->"Продажа";"breakdown"->"Разобрать на детали";else->"Исправление записи"}
    Dialog(onDismissRequest={vm.hideEditor()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),color=MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={vm.hideEditor()},enabled=!busy) {Icon(Icons.Outlined.Close,"Закрыть и оставить черновик",Modifier.size(20.dp))}
                    Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick={vm.submit()},enabled=!busy) {if(busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else Text("Сохранить")}
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    if(error.isNotEmpty())InfoCard(error,true)
                    if(draft.kind.startsWith("correct"))InfoCard("Исправление пересчитает связанные сделки и статистику прошлых месяцев. Прежние значения сохранятся в истории")
                    when(draft.kind) {
                        "purchase"->{
                            if(draft.contextId.isNotBlank())InfoCard("Деталь сразу попадёт в сборку. Для старой детали включите «Было в наличии», чтобы не создавать новый платёж")
                            Input("Название",draft["name"],{vm.update("name",it)})
                            PickField("Категория",draft["category"],itemCategories.map {it to it},{vm.update("category",it)})
                            Input("Сумма покупки, ₽",draft["amount"],{vm.update("amount",it)},money=true)
                            Input("Количество одинаковых товаров",draft["quantity"],{vm.update("quantity",it)},number=true)
                            Input("Дополнительные расходы покупки, ₽",draft["extra"],{vm.update("extra",it)},money=true)
                            Text("Общая стоимость распределится поровну. Остаточные копейки сохранятся в первых экземплярах",style=MaterialTheme.typography.bodySmall,color=Muted)
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text("Было в наличии");Text("Себестоимость есть, нового платежа нет",style=MaterialTheme.typography.bodySmall,color=Muted)};Switch(checked=draft["opening"]=="true",onCheckedChange={vm.update("opening",it.toString())})}
                            DateInput(draft["date"],{vm.update("date",it)})
                            Input("Заметка",draft["note"],{vm.update("note",it)},multiline=true)
                        }
                        "build","correctBuild"->{
                            Input("Название сборки",draft["name"],{vm.update("name",it)})
                            Input("Плановая цена продажи, ₽ · необязательно",draft["amount"],{vm.update("amount",it)},money=true)
                            DateInput(draft["date"],{vm.update("date",it)})
                            if(draft.kind=="build")Input("Заметка",draft["note"],{vm.update("note",it)},multiline=true)
                        }
                        "reserve"->{
                            val options=Ledger.freeItems(state).map {it.id to "${it.name} · ${it.category} · ${Money.format(Ledger.itemCost(state,it.id))}"}
                            if(options.isEmpty())InfoCard("Свободных деталей нет. Добавьте покупку или введите деталь вручную в сборке")
                            PickField("Комплектующее",draft["itemId"],options,{vm.update("itemId",it)})
                            DateInput(draft["date"],{vm.update("date",it)})
                            Text("Закупочная стоимость и прямые расходы сохранятся. Нового платежа не будет",style=MaterialTheme.typography.bodyMedium,color=Muted)
                        }
                        "release"->{
                            val assignment=state.assignments.firstOrNull {it.id==draft.contextId}
                            val item=state.items.firstOrNull {it.id==assignment?.itemId}
                            Text(item?.name?:"Комплектующее",style=MaterialTheme.typography.titleMedium)
                            InfoCard("Деталь вернётся на склад вместе со своей стоимостью и расходами на ремонт. Расходы самой сборки останутся у сборки")
                            DateInput(draft["date"],{vm.update("date",it)})
                        }
                        "sale"->{
                            val options=Ledger.freeItems(state).map {"ITEM:${it.id}" to "${it.name} · ${Money.format(Ledger.itemCost(state,it.id))}"}+
                                state.builds.filter {it.active && Ledger.buildSale(state,it.id)==null && Ledger.buildItems(state,it.id).isNotEmpty()}.map {"BUILD:${it.id}" to "${it.name} · сборка · ${Money.format(Ledger.buildCost(state,it.id))}"}
                            PickField("Что продано",draft["target"]+":"+draft["targetId"],options,{vm.update("target",it.substringBefore(':'));vm.update("targetId",it.substringAfter(':'))})
                            val selected=runCatching {when(Target.valueOf(draft["target"])) {Target.ITEM->Ledger.itemCost(state,draft["targetId"]);Target.BUILD->Ledger.buildCost(state,draft["targetId"]);else->null}}.getOrNull()
                            if(selected!=null)MoneyRow("Себестоимость",selected)
                            Input("Получено от покупателя, ₽",draft["amount"],{vm.update("amount",it)},money=true)
                            if(selected!=null)runCatching {Money.parse(draft["amount"])}.getOrNull()?.let {MoneyRow("Прибыль сделки",it-selected,color=if(it>=selected)Positive else Negative)}
                            DateInput(draft["date"],{vm.update("date",it)})
                            InfoCard("Сумма считается полностью полученной. После сохранения товар будет отмечен как проданный")
                        }
                        "breakdown"->BreakdownFields(draft,state,vm)
                        "expense","correctExpense"->{
                            if(draft.kind=="expense")PickField("Категория",draft["category"],expenseCategories.map {it to it},{vm.update("category",it)})
                            else Text(draft["category"],style=MaterialTheme.typography.titleMedium)
                            Input("Сумма расхода / чека, ₽",draft["amount"],{vm.update("amount",it)},money=true)
                            DateInput(draft["date"],{vm.update("date",it)})
                            Text("Куда отнести расход",style=MaterialTheme.typography.titleMedium)
                            draft.parts.forEachIndexed {i,part->
                                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceVariant) {
                                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                        PickField("Назначение${if(draft.parts.size>1)" ${i+1}" else ""}",part.target.name,listOf("GENERAL" to "Общий расход месяца","ITEM" to "На комплектующее","BUILD" to "На сборку"),{vm.updatePart(i,part.copy(target=Target.valueOf(it),targetId=""))})
                                        if(part.target!=Target.GENERAL)PickField(if(part.target==Target.ITEM)"Комплектующее" else "Сборка",part.targetId,objectOptions(state,part.target,draft["date"]),{vm.updatePart(i,part.copy(targetId=it))})
                                        if(draft.parts.size>1)Input("Сумма этой части, ₽",part.amount,{vm.updatePart(i,part.copy(amount=it))},money=true)
                                        if(draft.parts.size>1)TextButton(onClick={vm.removePart(i)}) {Text("Убрать часть")}
                                    }
                                }
                            }
                            TextButton(onClick={vm.addPart()}) {Text("Разделить чек / добавить часть")}
                            InfoCard("Часть на товар войдёт в себестоимость его продажи. Общая часть уменьшит прибыль месяца оплаты. Для уже учтённого расходника исправьте исходную запись — повторный расход создаст двойной учёт")
                            if(draft.parts.any {p->(p.target==Target.ITEM && Ledger.itemSale(state,p.targetId)!=null)||(p.target==Target.BUILD && Ledger.buildSale(state,p.targetId)!=null)})InfoCard("Расход связан с уже проданным товаром. Его прибыль и месяц продажи будут пересчитаны; платёж останется на выбранной дате")
                            if(draft.kind=="expense")Input("Заметка",draft["note"],{vm.update("note",it)},multiline=true)
                        }
                        "correctPurchase"->{
                            Input("Название",draft["name"],{vm.update("name",it)})
                            Input("Сумма покупки, ₽",draft["amount"],{vm.update("amount",it)},money=true)
                            Input("Дополнительные расходы покупки, ₽",draft["extra"],{vm.update("extra",it)},money=true)
                            DateInput(draft["date"],{vm.update("date",it)})
                        }
                        "correctSale"->{Input("Получено от покупателя, ₽",draft["amount"],{vm.update("amount",it)},money=true);DateInput(draft["date"],{vm.update("date",it)})}
                    }
                    if(error.isNotEmpty())InfoCard(error,true)
                    Button(onClick={vm.submit()},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(busy)"Сохранение…" else "Сохранить запись")}
                    Text("Черновик сохраняется на телефоне. Операция попадёт в учёт после нажатия «Сохранить»",style=MaterialTheme.typography.bodySmall,color=Muted)
                    TextButton(onClick={vm.dismissEditor()},enabled=!busy) {Text("Удалить черновик",color=Negative)}
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun BreakdownFields(draft:EditorDraft,state:LedgerState,vm:LedgerViewModel) {
    val source = state.items.firstOrNull { it.id == draft.contextId }
    if (source == null) {
        InfoCard("Исходный товар не найден", true)
        return
    }
    Text(source.name, style = MaterialTheme.typography.titleMedium)
    DateInput(draft["date"]) { vm.update("date", it) }
    val cost = Ledger.itemCost(state, source.id)
    val entered = draft.breakdown.map { part -> runCatching { Money.parse(part.amount) }.getOrDefault(0) }
    val remaining = cost - Money.sum(entered)
    MoneyRow("Распределить", cost)
    MoneyRow("Остаток", remaining, color = if (remaining < 0) Negative else Ink)
    Text("Детали появятся на складе. Исходная покупка останется в журнале", style = MaterialTheme.typography.bodyMedium, color = Muted)
    draft.breakdown.forEachIndexed { index, part ->
        OutlinedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Input("Название детали ${index + 1}", part.name, { vm.updateBreakdown(index, part.copy(name = it)) })
                PickField("Категория", part.category, itemCategories.map { it to it }) { vm.updateBreakdown(index, part.copy(category = it)) }
                Input("Себестоимость, ₽", part.amount, { vm.updateBreakdown(index, part.copy(amount = it)) }, money = true)
                val available = remaining + entered[index]
                if (part.amount.isBlank() && index == draft.breakdown.lastIndex && available >= 0) {
                    TextButton(onClick = { vm.updateBreakdown(index, part.copy(amount = Money.input(available))) }) { Text("Вписать остаток") }
                }
                if (draft.breakdown.size > 2) TextButton(onClick = { vm.removeBreakdownPart(index) }) { Text("Убрать деталь") }
            }
        }
    }
    OutlinedButton(onClick = vm::addBreakdownPart, enabled = draft.breakdown.size < 100) { Text("Добавить деталь") }
}
