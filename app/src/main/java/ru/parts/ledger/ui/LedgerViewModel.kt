package ru.parts.ledger.ui

import android.app.Application
import android.net.Uri
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import ru.parts.ledger.LedgerApplication
import ru.parts.ledger.backup.BackupStatus
import ru.parts.ledger.backup.SnapshotInfo
import ru.parts.ledger.backup.VerifiedBackup
import ru.parts.ledger.data.PhotoImages
import ru.parts.ledger.domain.*
import ru.parts.ledger.domain.Target
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

fun today():String=LocalDate.now(ZoneId.of("Europe/Moscow")).toString()
fun newId():String=UUID.randomUUID().toString()
@Serializable data class AllocationDraft(val target:Target=Target.GENERAL,val targetId:String="",val amount:String="")
@Serializable data class BreakdownDraft(val name:String="",val category:String=itemCategories.first(),val amount:String="")
@Serializable data class EditorDraft(val id:String,val kind:String,val contextId:String="",val fields:Map<String,String> = emptyMap(),val parts:List<AllocationDraft> = emptyList(),val breakdown:List<BreakdownDraft> = emptyList()) {
    operator fun get(key:String)=fields[key]?:""
}
data class OperationRow(val id:String,val kind:Record,val date:String,val title:String,val subtitle:String,val amount:Long?,val active:Boolean)
data class OverviewData(val month:YearMonth,val report:MonthlyReport,val previous:MonthlyReport,val months:List<YearMonth>,val profits:List<Long>)

class LedgerViewModel(application:Application,private val saved:SavedStateHandle):AndroidViewModel(application) {
    private val app=application as LedgerApplication
    private val repo=app.repository
    val ledger=MutableStateFlow<LedgerState?>(null)
    val failure=MutableStateFlow("")
    val message=MutableStateFlow("")
    val backupStatus=MutableStateFlow(BackupStatus())
    val busy=MutableStateFlow(false)
    val editor=MutableStateFlow<EditorDraft?>(null)
    val savedDraft=MutableStateFlow<String?>(null)
    val editorError=MutableStateFlow("")
    val screen=MutableStateFlow(saved.get<String>("screen")?:"Обзор")
    val month=MutableStateFlow(YearMonth.parse(saved.get<String>("month")?:today().take(7)))
    val preview=MutableStateFlow<VerifiedBackup?>(null)
    val internalCopies=MutableStateFlow<List<SnapshotInfo>?>(null)
    var exportInternalId:String?=null
    val cancellation=MutableStateFlow<OperationRow?>(null)
    val selectedItem=MutableStateFlow(saved.get<String>("selectedItem"))
    val selectedBuild=MutableStateFlow(saved.get<String>("selectedBuild"))
    private val currentDay=MutableStateFlow(LocalDate.parse(today()))
    @OptIn(ExperimentalCoroutinesApi::class)
    val attention=combine(ledger,currentDay) {state,day->state to day}.mapLatest { (state,day)->
        withContext(Dispatchers.Default) {state?.let {Attention.rows(it,day)}.orEmpty()}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val drafts=DraftCoordinator(viewModelScope) {json->
        try {repo.saveDraft(json)}catch(e:Exception) {if(e is CancellationException)throw e;message.value="Не удалось сохранить черновик: ${e.message}"}
    }
    private var initialized=false
    @OptIn(ExperimentalCoroutinesApi::class)
    val overview:StateFlow<OverviewData?> = combine(ledger,month) {s,m->s to m}.mapLatest {(s,m)->
        if(s==null)null else withContext(Dispatchers.Default) {
            val months=(5L downTo 0L).map {m.minusMonths(it)}
            OverviewData(m,Reports.month(s,m),Reports.month(s,m.minusMonths(1)),months,months.map {Reports.month(s,it).profit})
        }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.states.collect {state->
                    ledger.value=state
                    if(!initialized) {
                        initialized=true
                        val raw=saved.get<String>("editor")?:state.drafts["editor"]
                        savedDraft.value=raw
                        editor.value=raw?.let {runCatching {LedgerJson.decodeFromString<EditorDraft>(it)}.getOrNull()}
                        app.backup.afterMutation()
                    }
                }
            } catch(e:Exception) {if(e is CancellationException)throw e;failure.value="Не удалось открыть данные. Исходная база сохранена. ${e.message.orEmpty()}"}
        }
        viewModelScope.launch {app.backup.status.catch {message.value="Ошибка настроек копий: ${it.message}"}.collect {backupStatus.value=it}}
        viewModelScope.launch {while(isActive){currentDay.value=LocalDate.parse(today());delay(30_000)}}
    }
    fun navigate(value:String) {screen.value=value;saved["screen"]=value}
    fun selectItem(id:String?) {selectedItem.value=id;saved["selectedItem"]=id}
    fun selectBuild(id:String?) {selectedBuild.value=id;saved["selectedBuild"]=id}
    fun openAttention(row:AttentionRow) {
        if(row.target==Target.ITEM) {selectItem(row.id);navigate("Склад")} else {selectBuild(row.id);navigate("Сборки")}
    }
    fun defer(row:AttentionRow)=action {
        repo.execute(Command.DeferAttention(newId(),row.target,row.id,LocalDate.parse(today()).plusDays(7).toString()))
        message.value="Отложено на 7 дней"
        app.backup.afterMutation()
    }
    fun changeMonth(delta:Long) {month.value=month.value.plusMonths(delta);saved["month"]=month.value.toString()}
    fun selectMonth(value:YearMonth) {month.value=value;saved["month"]=value.toString()}
    private fun persist(draft:EditorDraft?) {
        editor.value=draft;saved["editor"]=draft?.let {LedgerJson.encodeToString(it)}
        val json=draft?.let {LedgerJson.encodeToString(it)}
        savedDraft.value=json
        drafts.enqueue(json)
    }
    fun open(kind:String,contextId:String="",defaults:Map<String,String> = emptyMap()) {
        if(busy.value)return
        editorError.value=""
        val fields=mapOf("date" to today(),"category" to if(kind=="expense")expenseCategories.first() else itemCategories.first(),"quantity" to "1","target" to Target.GENERAL.name,"opening" to "false")+defaults
        val parts=if(kind=="expense")listOf(AllocationDraft(runCatching {Target.valueOf(fields["target"]?:"GENERAL")}.getOrDefault(Target.GENERAL),fields["targetId"]?:"")) else emptyList()
        val breakdown=if(kind=="breakdown")listOf(BreakdownDraft(),BreakdownDraft(category="Видеокарта")) else emptyList()
        persist(EditorDraft(newId(),kind,contextId,fields,parts,breakdown))
    }
    fun update(key:String,value:String) {
        if(busy.value)return
        val max=when(key) {"note"->5000;"name"->300;"date"->10;"amount","extra","quantity"->50;else->300}
        if(value.length>max)editorError.value="Поле ограничено $max символами"
        editor.value?.let {persist(it.copy(fields=it.fields+(key to value.take(max))))}
    }
    fun updatePart(index:Int,value:AllocationDraft) {if(busy.value)return;editor.value?.let {d->persist(d.copy(parts=d.parts.mapIndexed {i,p->if(i==index)value.copy(amount=value.amount.take(50)) else p}))}}
    fun addPart() {if(busy.value)return;editor.value?.let {if(it.parts.size<100)persist(it.copy(parts=it.parts+AllocationDraft()))}}
    fun removePart(index:Int) {if(busy.value)return;editor.value?.let {if(it.parts.size>1)persist(it.copy(parts=it.parts.filterIndexed {i,_->i!=index}))}}
    fun updateBreakdown(index:Int,value:BreakdownDraft) {
        if(busy.value)return
        editor.value?.let {draft->persist(draft.copy(breakdown=draft.breakdown.mapIndexed {i,part->
            if(i==index)value.copy(name=value.name.take(300),amount=value.amount.take(50)) else part
        }))}
    }
    fun addBreakdownPart() {
        if(!busy.value)editor.value?.let {if(it.breakdown.size<100)persist(it.copy(breakdown=it.breakdown+BreakdownDraft(category="Другое")))}
    }
    fun removeBreakdownPart(index:Int) {
        if(!busy.value)editor.value?.let {if(it.breakdown.size>2)persist(it.copy(breakdown=it.breakdown.filterIndexed {i,_->i!=index}))}
    }
    fun dismissEditor() {if(busy.value)return;editorError.value="";persist(null)}
    fun hideEditor() {if(!busy.value){editor.value=null;saved["editor"]=null;editorError.value=""}}
    fun resumeDraft() {if(busy.value)return;savedDraft.value?.let {raw->runCatching {LedgerJson.decodeFromString<EditorDraft>(raw)}.onSuccess {editor.value=it;saved["editor"]=raw}.onFailure {message.value="Черновик недоступен; финансовые записи сохранены"}}}
    fun correct(row:OperationRow) {
        if(busy.value)return
        val state=ledger.value?:return
        when(row.kind) {
            Record.PURCHASE->{val p=state.purchases.first {it.id==row.id};open("correctPurchase",p.id,mapOf("date" to p.date,"name" to p.name,"amount" to Money.input(p.total),"extra" to Money.input(p.extra)))}
            Record.SALE->{val s=state.sales.first {it.id==row.id};open("correctSale",s.id,mapOf("date" to s.date,"amount" to Money.input(s.amount)))}
            Record.EXPENSE->{val e=state.expenses.first {it.id==row.id};open("correctExpense",e.id,mapOf("date" to e.date,"amount" to Money.input(e.total),"category" to e.category));persist(editor.value!!.copy(parts=e.parts.map {AllocationDraft(it.target,it.targetId?:"",Money.input(it.amount))}))}
            Record.BUILD->{val b=state.builds.first {it.id==row.id};open("correctBuild",b.id,mapOf("date" to b.date,"name" to b.name,"amount" to (b.planPrice?.let(Money::input)?:"")))}
        }
    }
    private fun amount(value:String)=Money.parse(value)
    private fun parts(d:EditorDraft):List<CostPart> {
        return d.expenseParts()
    }
    fun submit() {
        val d=editor.value?:return
        if(busy.value)return
        busy.value=true;editorError.value=""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val command:Command=when(d.kind) {
                    "purchase"->Command.Purchase(d.id,d["date"],d["name"],d["category"],amount(d["amount"]),d["quantity"].toIntOrNull()?:throw IllegalArgumentException("Введите количество"),if(d["extra"].isBlank())0 else amount(d["extra"]),d["opening"]=="true",d.contextId.ifBlank {null},d["note"])
                    "build"->Command.NewBuild(d.id,d["date"],d["name"],d["note"],d["amount"].ifBlank {null}?.let(::amount))
                    "reserve"->Command.Reserve(d.id,d["date"],d["itemId"],d.contextId)
                    "release"->Command.Release(d.id,d["date"],d.contextId)
                    "expense"->Command.Spend(d.id,d["date"],d["category"],parts(d),d["note"])
                    "sale"->Command.Sell(d.id,d["date"],Target.valueOf(d["target"]),d["targetId"],amount(d["amount"]))
                    "breakdown"->Command.Disassemble(d.id,d["date"],d.contextId,d.breakdown.map {SplitPart(it.name,it.category,amount(it.amount))})
                    "correctPurchase"->Command.Correct(d.id,Record.PURCHASE,d.contextId,d["date"],amount(d["amount"]),d["name"],if(d["extra"].isBlank())0 else amount(d["extra"]))
                    "correctSale"->Command.Correct(d.id,Record.SALE,d.contextId,d["date"],amount(d["amount"]))
                    "correctExpense"->Command.Correct(d.id,Record.EXPENSE,d.contextId,d["date"],amount(d["amount"]),parts=parts(d))
                    "correctBuild"->Command.Correct(d.id,Record.BUILD,d.contextId,d["date"],d["amount"].ifBlank {"0"}.let(::amount),d["name"])
                    else->throw IllegalArgumentException("Неизвестная операция")
                }
                drafts.exclusive {
                    repo.execute(command,clearDraft=true)
                    withContext(Dispatchers.Main) {editor.value=null;saved["editor"]=null;savedDraft.value=null;message.value=if(command is Command.Disassemble)"Детали добавлены на склад" else "Сохранено на телефоне"}
                }
                app.backup.afterMutation()
            } catch(e:Exception) {if(e is CancellationException)throw e;editorError.value=e.message?:"Не удалось сохранить запись"}
            finally {busy.value=false}
        }
    }
    fun cancel(row:OperationRow) {
        if(busy.value)return
        busy.value=true
        viewModelScope.launch(Dispatchers.IO) {
            try {repo.execute(Command.Cancel(newId(),row.kind,row.id));cancellation.value=null;message.value="Операция отменена, запись в истории сохранена";app.backup.afterMutation()}
            catch(e:Exception) {if(e is CancellationException)throw e;message.value=e.message?:"Не удалось отменить"}
            finally {busy.value=false}
        }
    }
    private fun action(block:suspend ()->Unit) {
        if(busy.value)return;busy.value=true
        viewModelScope.launch(Dispatchers.IO) {try {block()}catch(e:Exception) {if(e is CancellationException)throw e;message.value=e.message?:"Не удалось выполнить действие"}finally {busy.value=false}}
    }
    fun selectFolder(uri:Uri)=action {app.backup.selectFolder(uri);message.value="Папка выбрана"}
    fun automaticBackup(enabled:Boolean)=action {app.backup.setAutomatic(enabled)}
    fun saveBackup()=action {app.backup.save();message.value=if(backupStatus.value.folder.isBlank())"Внутренняя копия проверена. Выберите внешнюю папку" else "Внешняя копия записана и проверена"}
    fun export(uri:Uri)=action {val id=exportInternalId;if(id!=null)app.backup.exportInternal(id,uri) else app.backup.export(uri);exportInternalId=null;message.value="Файл записан и проверен"}
    fun previewRestore(uri:Uri)=action {replacePreview(app.backup.inspect(uri))}
    fun showInternalCopies()=action {internalCopies.value=app.backup.internalCopies()}
    fun previewInternal(id:String)=action {replacePreview(app.backup.internalCopy(id));internalCopies.value=null}
    private fun replacePreview(copy:VerifiedBackup?) {app.backup.discard(preview.value);preview.value=copy}
    fun closePreview() {if(!busy.value)replacePreview(null)}
    fun restore() {
        val copy=preview.value?:return
        action {
            var statusUpdated=true
            drafts.exclusive {
                statusUpdated=app.backup.restore(copy)
                replacePreview(null)
                withContext(Dispatchers.Main) {
                    editor.value=null;saved["editor"]=null;savedDraft.value=copy.state.drafts["editor"]
                    selectItem(null);selectBuild(null)
                }
            }
            message.value=if(statusUpdated)"Данные восстановлены" else "Данные восстановлены. Не удалось обновить статус резервных копий"
        }
    }
    fun addPhoto(target:Target,id:String,uri:Uri,onFinished:()->Unit={}) {
        if(busy.value){onFinished();return}
        action {
            try {repo.addPhoto(target,id,PhotoImages.import(app.contentResolver,uri));app.backup.afterMutation()}
            finally {onFinished()}
        }
    }
    suspend fun photo(id:String,thumbnail:Boolean):Bitmap?=withContext(Dispatchers.IO) {
        repo.photoBytes(id)?.let {PhotoImages.decode(it,thumbnail)}
    }
    fun makeCover(id:String)=action {repo.makeCover(id);app.backup.afterMutation();message.value="Обложка изменена"}
    fun deletePhoto(id:String)=action {repo.deletePhoto(id);app.backup.afterMutation();message.value="Фото удалено"}
}

fun operations(s:LedgerState):List<OperationRow> = (
    s.purchases.map {OperationRow(it.id,Record.PURCHASE,it.date,it.name,if(it.opening)"Начальный остаток" else "Покупка · ${it.quantity} шт.",Money.sum(listOf(it.total,it.extra)),it.active)}+
    s.sales.map {sale->OperationRow(sale.id,Record.SALE,sale.date,if(sale.target==Target.BUILD)s.builds.first {it.id==sale.targetId}.name else s.items.first {it.id==sale.targetId}.name,"Продажа · прибыль ${Money.format(sale.amount-sale.cost)}",sale.amount,sale.active)}+
    s.expenses.map {OperationRow(it.id,Record.EXPENSE,it.date,it.category,if(it.parts.all {it.target==Target.GENERAL})"Общий расход" else "Прямой / распределённый расход",it.total,it.active)}
    ).sortedWith(compareByDescending<OperationRow> {it.date}.thenByDescending {it.id})
