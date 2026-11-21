package ru.parts.ledger.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val LedgerJson=Json { encodeDefaults=true; ignoreUnknownKeys=false; isLenient=false }

@Serializable enum class Target { GENERAL, ITEM, BUILD }
@Serializable enum class Record { PURCHASE, SALE, EXPENSE, BUILD }
@Serializable data class PurchaseRecord(val id:String,val date:String,val name:String,val category:String,val total:Long,val quantity:Int=1,val extra:Long=0,val opening:Boolean=false,val note:String="",val active:Boolean=true)
@Serializable data class Item(
    val id:String,val purchaseId:String,val name:String,val category:String,val cost:Long,
    val sourceId:String?=null,val availableFrom:String?=null,val splitWeight:Long?=null,val attentionUntil:String?=null
)
@Serializable data class Build(val id:String,val date:String,val name:String,val note:String="",val planPrice:Long?=null,val active:Boolean=true,val attentionUntil:String?=null)
@Serializable data class Assignment(val id:String,val date:String,val itemId:String,val buildId:String,val endDate:String?=null,val active:Boolean=true)
@Serializable data class CostPart(val target:Target,val targetId:String?,val amount:Long)
@Serializable data class Expense(val id:String,val date:String,val category:String,val parts:List<CostPart>,val note:String="",val active:Boolean=true) { val total:Long get()=Money.sum(parts.map {it.amount}) }
@Serializable data class SaleLine(val itemId:String,val name:String,val cost:Long)
@Serializable data class CostSnapshot(val allocationId:String,val label:String,val amount:Long)
@Serializable data class Sale(val id:String,val date:String,val target:Target,val targetId:String,val itemIds:List<String>,val amount:Long,val cost:Long=0,val lines:List<SaleLine> = emptyList(),val costs:List<CostSnapshot> = emptyList(),val active:Boolean=true)
@Serializable data class Audit(val id:String,val action:String,val recordId:String,val at:String,val before:String="",val after:String="",val affectedBefore:List<Sale> = emptyList(),val affectedAfter:List<Sale> = emptyList())
@Serializable data class LedgerState(val revision:Long=0,val purchases:List<PurchaseRecord> = emptyList(),val items:List<Item> = emptyList(),val builds:List<Build> = emptyList(),val assignments:List<Assignment> = emptyList(),val expenses:List<Expense> = emptyList(),val sales:List<Sale> = emptyList(),val audit:List<Audit> = emptyList(),val drafts:Map<String,String> = emptyMap(),val photos:List<PhotoRef> = emptyList())

sealed interface Command {
    val id:String
    data class Purchase(override val id:String,val date:String,val name:String,val category:String,val total:Long,val quantity:Int=1,val extra:Long=0,val opening:Boolean=false,val buildId:String?=null,val note:String=""):Command
    data class NewBuild(override val id:String,val date:String,val name:String,val note:String="",val planPrice:Long?=null):Command
    data class Reserve(override val id:String,val date:String,val itemId:String,val buildId:String):Command
    data class Release(override val id:String,val date:String,val assignmentId:String):Command
    data class Spend(override val id:String,val date:String,val category:String,val parts:List<CostPart>,val note:String=""):Command
    data class Sell(override val id:String,val date:String,val target:Target,val targetId:String,val amount:Long):Command
    data class Disassemble(override val id:String,val date:String,val itemId:String,val parts:List<SplitPart>):Command
    data class DeferAttention(override val id:String,val target:Target,val targetId:String,val until:String):Command
    data class Correct(override val id:String,val record:Record,val recordId:String,val date:String,val amount:Long,val name:String?=null,val extra:Long?=null,val parts:List<CostPart>?=null):Command
    data class Cancel(override val id:String,val record:Record,val recordId:String):Command
}

data class SplitPart(val name:String,val category:String,val amount:Long)

val itemCategories=listOf("Процессор","Видеокарта","Материнская плата","Память","Накопитель","Блок питания","Корпус","Охлаждение","Готовый компьютер","Другое")
val expenseCategories=listOf("Расходники","Публикации","Продвижение","Доставка","Ремонт","Прочее")
