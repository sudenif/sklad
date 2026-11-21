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

fun itemStatus(s:LedgerState,item:Item):String = when {
    Ledger.isDisassembled(s,item.id)->"Разобран"
    Ledger.itemSale(s,item.id)!=null->"Продан"
    Ledger.assignment(s,item.id)!=null->"В сборке"
    else->"В наличии"
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun InventoryScreen(state:LedgerState,vm:LedgerViewModel,photoPicker:PhotoPicker) {
    var query by rememberSaveable {mutableStateOf("")}
    var category by rememberSaveable {mutableStateOf("Все")}
    var status by rememberSaveable {mutableStateOf("Все")}
    var withoutPhoto by rememberSaveable {mutableStateOf(false)}
    val selected by vm.selectedItem.collectAsState()
    val photos=remember(state.photos) {state.photos.filter {it.itemId!=null}.groupBy {it.ownerId}}
    val filtered=Ledger.activeItems(state).filter {
        it.name.contains(query,true) && (category=="Все" || it.category==category) &&
            (status=="Все" || itemStatus(state,it)==status) && (!withoutPhoto || (it.id !in photos && itemStatus(state,it)!="Разобран"))
    }.reversed()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        item {Button(onClick={vm.open("purchase")},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp).heightIn(min=48.dp)) {Text("Добавить покупку")}}
        item {Input("Поиск",query,{query=it})}
        item {Row(Modifier.padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {PickField("Категория",category,(listOf("Все")+itemCategories).map {it to it},{category=it})}
            Box(Modifier.weight(1f)) {PickField("Состояние",status,listOf("Все","В наличии","В сборке","Продан","Разобран").map {it to it},{status=it})}
        }}
        item {FilterChip(withoutPhoto,{withoutPhoto=!withoutPhoto},label={Text("Без фото")})}
        if(filtered.isEmpty())item {
            val searching=query.isNotEmpty() || category!="Все" || status!="Все" || withoutPhoto
            EmptyState(if(searching)"Ничего не найдено" else "Склад пока пуст",if(searching)"Попробуйте изменить фильтры" else "Запишите комплектующие или готовый компьютер с ценой покупки")
        }
        items(filtered,key={it.id}) {item->
            val cover=photos[item.id]?.firstOrNull()
            LedgerRow(item.name,itemStatus(state,item),Money.format(Ledger.itemCost(state,item.id)),item.category,
                leading=cover?.let {{PhotoPreview(it,vm,Modifier.size(48.dp))}},onClick={vm.selectItem(item.id)})
        }
        item {Spacer(Modifier.height(96.dp))}
    }
    val item=state.items.firstOrNull {it.id==selected}
    if(item!=null)ModalBottomSheet(onDismissRequest={vm.selectItem(null)}) {
        val p=Ledger.purchase(state,item)
        val sold=Ledger.itemSale(state,item.id)
        val splitParts=Ledger.partsFrom(state,item.id)
        val source=state.items.firstOrNull {it.id==item.sourceId}
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(item.category,style=MaterialTheme.typography.bodySmall,color=Muted)
            Text(item.name,style=MaterialTheme.typography.titleLarge)
            Text("${itemStatus(state,item)} · ${p.date}",style=MaterialTheme.typography.bodyMedium,color=Muted)
            if(source!=null)TextButton(onClick={vm.selectItem(source.id)}) {Text("Из товара «${source.name}»")}
            CardPhotos(photos[item.id].orEmpty(),Target.ITEM,item.id,vm,photoPicker)
            MoneyRow(if(source==null)"Закупочная стоимость" else "Доля себестоимости исходного товара",item.cost)
            state.expenses.filter {it.active}.forEach {e->val total=Money.sum(e.parts.filter {it.target==Target.ITEM && it.targetId==item.id}.map {it.amount});if(total>0)MoneyRow("${e.category} · ${e.date}",total)}
            MoneyRow("Себестоимость",Ledger.itemCost(state,item.id))
            if(p.opening)Text("Начальный остаток — без платежа в месяце ввода",style=MaterialTheme.typography.bodySmall,color=Muted)
            if(p.note.isNotBlank())Text(p.note,style=MaterialTheme.typography.bodyMedium)
            if(splitParts.isNotEmpty()) {
                Text("Детали после разбора · ${splitParts.first().availableFrom}",style=MaterialTheme.typography.titleMedium)
                splitParts.forEach {part->
                    LedgerRow(part.name,itemStatus(state,part),Money.format(Ledger.itemCost(state,part.id)),onClick={vm.selectItem(part.id)})
                }
            }
            if(sold!=null) {
                Text("Продано ${sold.date}${if(sold.target==Target.BUILD)" в составе компьютера" else ""}",style=MaterialTheme.typography.bodyMedium)
                if(sold.target==Target.ITEM) {MoneyRow("Выручка",sold.amount);MoneyRow("Прибыль сделки",sold.amount-sold.cost)}
            }
            if(splitParts.isEmpty() && sold==null && Ledger.assignment(state,item.id)==null) {
                Button(onClick={vm.selectItem(null);vm.open("sale",defaults=mapOf("target" to Target.ITEM.name,"targetId" to item.id))},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Продать")}
                if(source==null)OutlinedButton(onClick={vm.selectItem(null);vm.open("breakdown",item.id)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Разобрать на детали")}
            }
            val expenseDate=splitParts.firstOrNull()?.availableFrom?.let {minOf(it,today())}?:today()
            OutlinedButton(onClick={vm.selectItem(null);vm.open("expense",defaults=mapOf("target" to Target.ITEM.name,"targetId" to item.id,"date" to expenseDate))},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text(if(splitParts.isEmpty())"Добавить расход на деталь" else "Добавить расход до разбора")
            }
            TextButton(onClick={vm.selectItem(null);vm.correct(OperationRow(p.id,Record.PURCHASE,p.date,p.name,"",p.total,p.active))}) {Text("Исправить покупку")}
        }
    }
}
