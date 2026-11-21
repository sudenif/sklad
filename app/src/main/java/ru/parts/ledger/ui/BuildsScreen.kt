package ru.parts.ledger.ui

import androidx.activity.compose.BackHandler
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

@Composable fun BuildsScreen(state:LedgerState,vm:LedgerViewModel,photoPicker:PhotoPicker) {
    val selected by vm.selectedBuild.collectAsState()
    var showSold by rememberSaveable {mutableStateOf(false)}
    var withoutPhoto by rememberSaveable {mutableStateOf(false)}
    val photos=remember(state.photos) {state.photos.filter {it.buildId!=null}.groupBy {it.ownerId}}
    val build=state.builds.firstOrNull {it.id==selected && it.active}
    if(build==null) {
        val builds=state.builds.filter {it.active && (Ledger.buildSale(state,it.id)!=null)==showSold && (!withoutPhoto || it.id !in photos)}.reversed()
        LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
            item {Button(onClick={vm.open("build")},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp).heightIn(min=48.dp)) {Text("Создать сборку")}}
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {FilterChip(selected=!showSold,onClick={showSold=false},label={Text("В работе")});FilterChip(selected=showSold,onClick={showSold=true},label={Text("Проданные")})}}
            item {FilterChip(withoutPhoto,{withoutPhoto=!withoutPhoto},label={Text("Без фото")})}
            if(builds.isEmpty())item {EmptyState(if(withoutPhoto)"Сборок без фото нет" else if(showSold)"Проданных сборок пока нет" else "Первая сборка",if(withoutPhoto)"Снимите фильтр, чтобы увидеть все сборки" else "Создайте компьютер и добавьте детали")}
            items(builds,key={it.id}) {b->
                val sale=Ledger.buildSale(state,b.id)
                val cover=photos[b.id]?.firstOrNull()
                LedgerRow(b.name,if(sale!=null)"Продано ${sale.date}" else "${Ledger.buildItems(state,b.id).size} деталей",Money.format(sale?.cost?:Ledger.buildCost(state,b.id)),
                    leading=cover?.let {{PhotoPreview(it,vm,Modifier.size(48.dp))}},onClick={vm.selectBuild(b.id)})
            }
            item {Spacer(Modifier.height(96.dp))}
        }
    } else {
        BackHandler {vm.selectBuild(null)}
        val sale=Ledger.buildSale(state,build.id)
        val items=Ledger.buildItems(state,build.id)
        val cost=Ledger.buildCost(state,build.id)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            TextButton(onClick={vm.selectBuild(null)}) {Text("Все сборки")}
            Text(build.name,style=MaterialTheme.typography.headlineMedium)
            Text(if(sale!=null)"Продано ${sale.date}" else "В работе · ${build.date}",style=MaterialTheme.typography.bodySmall,color=Muted)
            if(build.note.isNotBlank())Text(build.note,style=MaterialTheme.typography.bodyMedium)
            CardPhotos(photos[build.id].orEmpty(),Target.BUILD,build.id,vm,photoPicker)
            SectionTitle("Комплектующие")
            if(items.isEmpty())Text("Добавьте первую деталь",color=Muted)
            items.forEach {it->
                LedgerRow(it.name,"Закупка ${Money.format(it.cost)}",Money.format(Ledger.itemCost(state,it.id)),it.category)
                if(sale==null)TextButton(onClick={Ledger.assignment(state,it.id)?.let {vm.open("release",it.id)}}) {Text("Вернуть на склад")}
            }
            if(sale==null) {
                OutlinedButton(onClick={vm.open("reserve",build.id)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Добавить со склада")}
                OutlinedButton(onClick={vm.open("purchase",build.id)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Ввести деталь и цену вручную")}
            }
            SectionTitle("Себестоимость")
            MoneyRow("Закупка деталей",Money.sum(items.map {it.cost}))
            val repairs=Money.sum(items.map {Ledger.itemCost(state,it.id)-it.cost})
            if(repairs!=0L)MoneyRow("Расходы на детали",repairs)
            state.expenses.filter {it.active}.forEach {e->val total=Money.sum(e.parts.filter {it.target==Target.BUILD && it.targetId==build.id}.map {it.amount});if(total>0)MoneyRow("${e.category} · ${e.date}",total)}
            HorizontalDivider()
            MoneyRow("Итого",cost)
            if(sale==null)build.planPrice?.let {price->MoneyRow("Плановая цена",price);MoneyRow("Ожидаемая прибыль",price-cost,"План не входит в выручку",if(price>=cost)Positive else Negative)}
            else {MoneyRow("Выручка",sale.amount);MoneyRow("Прибыль сделки",sale.amount-sale.cost,color=if(sale.amount>=sale.cost)Positive else Negative)}
            OutlinedButton(onClick={vm.open("expense",defaults=mapOf("target" to Target.BUILD.name,"targetId" to build.id))},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Добавить расход на сборку")}
            if(sale==null)Button(onClick={vm.open("sale",defaults=mapOf("target" to Target.BUILD.name,"targetId" to build.id,"amount" to (build.planPrice?.let(Money::input)?:"")))},enabled=items.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Продать компьютер")}
            TextButton(onClick={vm.correct(OperationRow(build.id,Record.BUILD,build.date,build.name,"",build.planPrice,true))}) {Text("Изменить сборку")}
            if(sale==null)TextButton(onClick={vm.cancellation.value=OperationRow(build.id,Record.BUILD,build.date,build.name,"",null,true)}) {Text("Отменить сборку",color=Negative)}
            Spacer(Modifier.height(96.dp))
        }
    }
}
