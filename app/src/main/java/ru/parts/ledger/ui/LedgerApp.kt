package ru.parts.ledger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ru.parts.ledger.domain.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LedgerApp(vm:LedgerViewModel,chooseFolder:()->Unit,export:()->Unit,import:()->Unit,exportRaw:()->Unit,photoPicker:PhotoPicker) {
    val state by vm.ledger.collectAsState()
    val failure by vm.failure.collectAsState()
    val message by vm.message.collectAsState()
    val page by vm.screen.collectAsState()
    val month by vm.month.collectAsState()
    val status by vm.backupStatus.collectAsState()
    val busy by vm.busy.collectAsState()
    val draft by vm.editor.collectAsState()
    val savedDraft by vm.savedDraft.collectAsState()
    val editorError by vm.editorError.collectAsState()
    val restore by vm.preview.collectAsState()
    val internalCopies by vm.internalCopies.collectAsState()
    val cancel by vm.cancellation.collectAsState()
    var addMenu by remember {mutableStateOf(false)}
    var detail by remember {mutableStateOf<OperationRow?>(null)}
    val snackbar=remember {SnackbarHostState()}
    val subsidiary=page=="Копии" || page=="Внимание"
    BackHandler(subsidiary && draft==null && restore==null) {vm.navigate("Обзор")}
    LaunchedEffect(message) {if(message.isNotEmpty()){snackbar.showSnackbar(message);vm.message.value=""}}
    val pages=listOf<Triple<String,String,ImageVector>>(Triple("Обзор","Обзор",Icons.Outlined.Home),Triple("Склад","Комплектующие",Icons.Outlined.ShoppingCart),Triple("Сборки","Компьютеры",Icons.Outlined.Settings),Triple("Операции","Журнал",Icons.Outlined.List))
    Scaffold(
        topBar={TopAppBar(title={Text(when(page) {"Копии"->"Резервные копии";"Внимание"->"Требуют внимания";else->pages.firstOrNull {it.first==page}?.second?:"Учёт"},style=MaterialTheme.typography.titleLarge)},
            navigationIcon={if(subsidiary)IconButton(onClick={vm.navigate("Обзор")}) {Icon(Icons.Outlined.ArrowBack,"Назад",Modifier.size(20.dp))}},
            actions={if(page!="Копии")IconButton(onClick={vm.navigate("Копии")}) {Icon(Icons.Outlined.Refresh,"Резервные копии",Modifier.size(20.dp))}})},
        bottomBar={if(failure.isEmpty())NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {pages.forEach {(key,_,icon)->NavigationBarItem(selected=page==key,onClick={vm.navigate(key)},icon={Icon(icon,null,Modifier.size(20.dp))},label={Text(key)})}}},
        snackbarHost={SnackbarHost(snackbar)},
        floatingActionButton={if(page=="Обзор" && state!=null && failure.isEmpty())ExtendedFloatingActionButton(onClick={addMenu=true},containerColor=Copper,contentColor=androidx.compose.ui.graphics.Color.White,icon={Icon(Icons.Outlined.Add,null,Modifier.size(20.dp))},text={Text("Добавить")})}
    ) {padding->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                failure.isNotEmpty()->Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {InfoCard(failure,true);Text("Не очищайте данные приложения. Сохраните исходные файлы перед восстановлением или исправлением");Button(onClick=exportRaw) {Text("Сохранить исходные файлы")}}
                state==null->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {CircularProgressIndicator()}
                else->{
                    val s=state!!
                    if(draft==null && savedDraft!=null)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {Text("Незавершённая запись",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=Muted);TextButton(onClick={vm.resumeDraft()},enabled=!busy) {Text("Продолжить")}}
                    Box(Modifier.weight(1f)) {
                        when(page) {
                            "Склад"->InventoryScreen(s,vm,photoPicker)
                            "Сборки"->BuildsScreen(s,vm,photoPicker)
                            "Внимание"->AttentionScreen(vm,busy)
                            "Операции"->OperationsScreen(s,month,vm,{detail=it})
                            "Копии"->BackupScreen(s.copy(drafts=savedDraft?.let {mapOf("editor" to it)}?:emptyMap()),status,busy,vm,chooseFolder,{vm.exportInternalId=null;export()},import)
                            else->OverviewScreen(s,month,status,vm,{detail=it})
                        }
                    }
                }
            }
        }
    }
    if(addMenu)AlertDialog(onDismissRequest={addMenu=false},title={Text("Добавить запись")},text={Column {
        listOf("purchase" to "Покупка","sale" to "Продажа","expense" to "Расход","build" to "Сборка").forEach {(key,label)->TextButton(onClick={addMenu=false;vm.open(key)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(label)}}
    }},confirmButton={TextButton(onClick={addMenu=false}){Text("Закрыть")}})
    if(state!=null && draft!=null)EditorScreen(draft!!,state!!,busy,editorError,vm)
    if(detail!=null && state!=null)OperationDetail(detail!!,state!!,vm,{detail=null})
    if(cancel!=null)AlertDialog(onDismissRequest={if(!busy)vm.cancellation.value=null},title={Text("Отменить операцию?")},text={Text("${cancel!!.title}\n\nСуммы и остатки будут пересчитаны, а запись останется в истории. Связанные операции могут потребовать предварительной отмены")},confirmButton={TextButton(onClick={vm.cancel(cancel!!)},enabled=!busy){Text("Отменить операцию",color=Negative)}},dismissButton={TextButton(onClick={vm.cancellation.value=null},enabled=!busy){Text("Оставить")}})
    restore?.let {copy->AlertDialog(onDismissRequest={vm.closePreview()},title={Text("Заменить текущий учёт?")},text={Text("Копия проверена\nСоздана ${displayTime(copy.createdAt)}\nПокупок: ${copy.state.purchases.size}\nДеталей: ${copy.state.items.size}\nПродаж: ${copy.state.sales.size}\nРасходов: ${copy.state.expenses.size}\nФото: ${copy.state.photos.size}\n\nПеред заменой сохранится отдельная защитная копия")},confirmButton={TextButton(onClick={vm.restore()},enabled=!busy){Text("Заменить данные")}},dismissButton={TextButton(onClick={vm.closePreview()},enabled=!busy){Text("Отмена")}})}
    if(internalCopies!=null)AlertDialog(onDismissRequest={vm.internalCopies.value=null},title={Text("Внутренние копии")},text={
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max=440.dp)) {
            if(internalCopies!!.isEmpty())item {Text("Внутренних копий пока нет")}
            items(internalCopies!!.size) {i->val copy=internalCopies!![i]
                Column(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(displayTime(copy.createdAt),style=MaterialTheme.typography.titleSmall)
                    val source=when {copy.id.startsWith("upgrade-")->"Перед обновлением · ";copy.isProtection->"Перед восстановлением · ";else->""}
                    Text("$source${copy.purchases} покупок · ${copy.sales} продаж",style=MaterialTheme.typography.bodySmall,color=Muted)
                    Row {TextButton(onClick={vm.previewInternal(copy.id)},enabled=!busy){Text("Восстановить")};TextButton(onClick={vm.exportInternalId=copy.id;export()},enabled=!busy){Text("В файл")}}
                }
            }
        }
    },confirmButton={TextButton(onClick={vm.internalCopies.value=null}) {Text("Закрыть")}})
}
