package ru.parts.ledger.domain

import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.LocalDate

object Ledger {
    @Volatile private var cached:LedgerIndex?=null
    internal fun index(s:LedgerState):LedgerIndex=cached?.takeIf {it.state===s}?:LedgerIndex(s).also {cached=it}
    fun purchase(s:LedgerState,item:Item)=index(s).purchases[item.purchaseId]?:throw IllegalArgumentException("Нет исходной покупки")
    fun activeItems(s:LedgerState)=index(s).activeItems
    fun itemSale(s:LedgerState,id:String)=index(s).itemSales[id]
    fun buildSale(s:LedgerState,id:String)=index(s).buildSales[id]
    fun assignment(s:LedgerState,id:String)=index(s).currentAssignments[id]
    fun partsFrom(s:LedgerState,id:String)=index(s).partsBySource[id].orEmpty()
    fun isDisassembled(s:LedgerState,id:String)=partsFrom(s,id).isNotEmpty()
    fun availableSince(s:LedgerState,item:Item)=item.availableFrom?:purchase(s,item).date
    internal fun stockAt(s:LedgerState,date:String)=activeItems(s).filter {item->
        availableSince(s,item)<=date &&
            (partsFrom(s,item.id).firstOrNull()?.availableFrom?.let {it>date}?:true) &&
            (itemSale(s,item.id)?.date?.let {it>date}?:true)
    }
    fun freeItems(s:LedgerState)=activeItems(s).filter {itemSale(s,it.id)==null && assignment(s,it.id)==null && !isDisassembled(s,it.id)}
    fun buildItems(s:LedgerState,id:String)=index(s).currentByBuild[id].orEmpty().map {a->index(s).items[a.itemId]?:throw IllegalArgumentException("Нет детали сборки")}
    private fun costs(s:LedgerState,ids:List<String>,buildId:String?):List<CostSnapshot> = index(s).let {idx->ids.flatMap {idx.itemCosts[it].orEmpty()}+(if(buildId!=null)idx.buildCosts[buildId].orEmpty() else emptyList())}
    fun buildCost(s:LedgerState,id:String):Long=buildCost(s,id,buildItems(s,id))
    internal fun buildCost(s:LedgerState,id:String,items:List<Item>):Long=
        Money.sum(items.map {it.cost}+costs(s,items.map {it.id},id).map {it.amount})
    fun itemCost(s:LedgerState,id:String)=Money.sum(listOf(index(s).items[id]?.cost?:throw IllegalArgumentException("Нет комплектующего"))+costs(s,listOf(id),null).map {it.amount})
    private fun recalculate(state:LedgerState):LedgerState {
        val s=Breakdown.recalculate(state)
        return s.copy(sales=s.sales.map {sale->
        val names=sale.lines.associate {it.itemId to it.name}
        val lines=sale.itemIds.map {id->index(s).items[id]?.let {SaleLine(it.id,names[id]?:it.name,it.cost)} ?: throw IllegalArgumentException("В продаже отсутствует комплектующее") }
        val charges=costs(s,sale.itemIds,if(sale.target==Target.BUILD) sale.targetId else null)
        sale.copy(lines=lines,costs=charges,cost=Money.sum(lines.map {it.cost}+charges.map {it.amount}))
        })
    }
    private fun date(value:String):LocalDate = try { LocalDate.parse(value) } catch(e:Exception) {throw IllegalArgumentException("Дата должна быть в формате ГГГГ-ММ-ДД",e)}
    private fun name(value:String) {require(value.isNotBlank() && value.length<=300) {"Название: от 1 до 300 символов"}}
    private fun build(s:LedgerState,id:String)=index(s).builds[id]?.takeIf {it.active} ?: throw IllegalArgumentException("Сборка не найдена")
    private fun item(s:LedgerState,id:String)=index(s).items[id]?.takeIf {purchase(s,it).active} ?: throw IllegalArgumentException("Комплектующее не найдено")
    private fun recordJson(s:LedgerState,kind:Record,id:String):String=when(kind) {
        Record.PURCHASE->LedgerJson.encodeToString(s.purchases.first {it.id==id})
        Record.SALE->LedgerJson.encodeToString(s.sales.first {it.id==id})
        Record.EXPENSE->LedgerJson.encodeToString(s.expenses.first {it.id==id})
        Record.BUILD->LedgerJson.encodeToString(s.builds.first {it.id==id})
    }
    fun apply(s:LedgerState,cmd:Command):LedgerState {
        require(cmd.id.isNotBlank() && cmd.id.length<=150) {"Некорректный идентификатор операции"}
        if(index(s).audit.containsKey(cmd.id)) return s
        var before=""
        var after=""
        val changed=when(cmd) {
            is Command.Purchase->{
                date(cmd.date); name(cmd.name); name(cmd.category); Money.check(cmd.total); Money.check(cmd.extra)
                val allocations=Money.split(Money.sum(listOf(cmd.total,cmd.extra)),cmd.quantity)
                require(s.purchases.none {it.id==cmd.id}) {"Покупка уже существует"}
                val p=PurchaseRecord(cmd.id,cmd.date,cmd.name.trim(),cmd.category,cmd.total,cmd.quantity,cmd.extra,cmd.opening,cmd.note)
                val items=allocations.mapIndexed {i,c->Item("${cmd.id}:item:$i",cmd.id,cmd.name.trim(),cmd.category,c)}
                val assignments=cmd.buildId?.let {id->
                    val b=build(s,id); require(buildSale(s,id)==null) {"Сборка уже продана"}; require(cmd.date>=b.date) {"Деталь нельзя включить раньше даты сборки"}
                    items.mapIndexed {i,it->Assignment("${cmd.id}:reserve:$i",cmd.date,it.id,id)}
                } ?: emptyList()
                after=LedgerJson.encodeToString(p)
                s.copy(purchases=s.purchases+p,items=s.items+items,assignments=s.assignments+assignments)
            }
            is Command.NewBuild->{
                date(cmd.date); name(cmd.name); cmd.planPrice?.let(Money::check)
                require(s.builds.none {it.id==cmd.id}) {"Сборка уже существует"}
                val b=Build(cmd.id,cmd.date,cmd.name.trim(),cmd.note,cmd.planPrice)
                after=LedgerJson.encodeToString(b); s.copy(builds=s.builds+b)
            }
            is Command.Reserve->{
                date(cmd.date); val it=item(s,cmd.itemId); val b=build(s,cmd.buildId)
                require(itemSale(s,it.id)==null && assignment(s,it.id)==null) {"Деталь уже продана или находится в сборке"}
                require(!isDisassembled(s,it.id)) {"Исходный товар уже разобран"}
                require(buildSale(s,b.id)==null) {"Сборка уже продана"}
                val a=Assignment(cmd.id,cmd.date,it.id,b.id)
                after=LedgerJson.encodeToString(a); s.copy(assignments=s.assignments+a)
            }
            is Command.Release->{
                date(cmd.date)
                val a=s.assignments.firstOrNull {it.id==cmd.assignmentId && it.active && it.endDate==null} ?: throw IllegalArgumentException("Деталь уже извлечена")
                require(itemSale(s,a.itemId)==null && buildSale(s,a.buildId)==null) {"Сначала отмените продажу"}
                before=LedgerJson.encodeToString(a); after=LedgerJson.encodeToString(a.copy(endDate=cmd.date))
                s.copy(assignments=s.assignments.map {if(it.id==a.id) it.copy(endDate=cmd.date) else it})
            }
            is Command.Spend->{
                date(cmd.date); name(cmd.category)
                val e=Expense(cmd.id,cmd.date,cmd.category,cmd.parts,cmd.note)
                after=LedgerJson.encodeToString(e); s.copy(expenses=s.expenses+e)
            }
            is Command.Sell->{
                date(cmd.date); Money.check(cmd.amount)
                val ids=when(cmd.target) {
                    Target.ITEM->{val it=item(s,cmd.targetId); require(!isDisassembled(s,it.id)) {"Исходный товар уже разобран"}; require(assignment(s,it.id)==null) {"Сначала извлеките деталь из сборки"}; listOf(it.id)}
                    Target.BUILD->{build(s,cmd.targetId); require(buildSale(s,cmd.targetId)==null) {"Сборка уже продана"}; buildItems(s,cmd.targetId).map {it.id}.also {require(it.isNotEmpty()) {"Добавьте хотя бы одну деталь"}}}
                    else->throw IllegalArgumentException("Выберите товар для продажи")
                }
                require(ids.none {itemSale(s,it)!=null}) {"Товар уже продан"}
                val sale=Sale(cmd.id,cmd.date,cmd.target,cmd.targetId,ids,cmd.amount)
                after=LedgerJson.encodeToString(sale); s.copy(sales=s.sales+sale)
            }
            is Command.Disassemble->{
                date(cmd.date)
                val value=Breakdown.apply(s,cmd)
                before=LedgerJson.encodeToString(item(s,cmd.itemId))
                after=LedgerJson.encodeToString(value.items.filter {it.sourceId==cmd.itemId})
                value
            }
            is Command.DeferAttention->{
                date(cmd.until)
                after=cmd.until
                when(cmd.target) {
                    Target.ITEM->{val it=item(s,cmd.targetId); s.copy(items=s.items.map {row->if(row.id==it.id)row.copy(attentionUntil=cmd.until) else row})}
                    Target.BUILD->{val b=build(s,cmd.targetId); s.copy(builds=s.builds.map {if(it.id==b.id)it.copy(attentionUntil=cmd.until) else it})}
                    Target.GENERAL->throw IllegalArgumentException("Выберите товар или сборку")
                }
            }
            is Command.Correct->{
                date(cmd.date); Money.check(cmd.amount); cmd.name?.let(::name)
                before=recordJson(s,cmd.record,cmd.recordId)
                val value=when(cmd.record) {
                    Record.PURCHASE->{
                        val p=s.purchases.first {it.id==cmd.recordId}; require(p.active) {"Покупка отменена"}
                        val extra=cmd.extra?:p.extra; Money.check(extra)
                        val p2=p.copy(date=cmd.date,total=cmd.amount,extra=extra,name=cmd.name?:p.name)
                        val allocated=Money.split(Money.sum(listOf(cmd.amount,extra)),p.quantity)
                        val indices=index(s).itemsByPurchase[p.id].orEmpty().filter {it.sourceId==null}.mapIndexed {i,it->it.id to i}.toMap()
                        s.copy(purchases=s.purchases.map {if(it.id==p.id) p2 else it},items=s.items.map {val i=indices[it.id]; if(i!=null) it.copy(cost=allocated[i],name=cmd.name?:it.name) else it})
                    }
                    Record.SALE->s.copy(sales=s.sales.map {if(it.id==cmd.recordId) {require(it.active) {"Продажа отменена"}; it.copy(date=cmd.date,amount=cmd.amount)} else it})
                    Record.EXPENSE->s.copy(expenses=s.expenses.map {if(it.id==cmd.recordId) {
                        require(it.active) {"Расход отменён"}
                        val parts=cmd.parts ?: run {require(it.parts.size==1) {"Для разделённого расхода отредактируйте распределение"}; it.parts.map {p->p.copy(amount=cmd.amount)} }
                        require(Money.sum(parts.map {p->p.amount})==cmd.amount) {"Сумма распределений не совпадает"}
                        it.copy(date=cmd.date,parts=parts)
                    } else it})
                    Record.BUILD->{build(s,cmd.recordId); s.copy(builds=s.builds.map {if(it.id==cmd.recordId) it.copy(date=cmd.date,name=cmd.name?:it.name,planPrice=cmd.amount) else it})}
                }
                after=recordJson(value,cmd.record,cmd.recordId); value
            }
            is Command.Cancel->{
                before=recordJson(s,cmd.record,cmd.recordId)
                val value=when(cmd.record) {
                    Record.PURCHASE->{
                        val ids=s.items.filter {it.purchaseId==cmd.recordId}.map {it.id}
                        require(ids.none {id->itemSale(s,id)!=null || s.assignments.any {it.itemId==id && it.active} || s.expenses.any {e->e.active && e.parts.any {it.target==Target.ITEM && it.targetId==id}}}) {"Покупка связана со сборкой, расходом или продажей; сначала отмените связанные операции"}
                        s.copy(purchases=s.purchases.map {if(it.id==cmd.recordId) it.copy(active=false) else it})
                    }
                    Record.SALE->s.copy(sales=s.sales.map {if(it.id==cmd.recordId) it.copy(active=false) else it})
                    Record.EXPENSE->s.copy(expenses=s.expenses.map {if(it.id==cmd.recordId) it.copy(active=false) else it})
                    Record.BUILD->{
                        require(buildSale(s,cmd.recordId)==null) {"Сначала отмените продажу сборки"}
                        require(s.expenses.none {e->e.active && e.parts.any {it.target==Target.BUILD && it.targetId==cmd.recordId}}) {"Переназначьте или отмените расходы сборки"}
                        s.copy(builds=s.builds.map {if(it.id==cmd.recordId) it.copy(active=false) else it},assignments=s.assignments.map {if(it.buildId==cmd.recordId) it.copy(active=false) else it})
                    }
                }
                after=recordJson(value,cmd.record,cmd.recordId); value
            }
        }
        val recalculated=recalculate(changed)
        if(cmd is Command.Sell)after=recordJson(recalculated,Record.SALE,cmd.id)
        val oldSales=s.sales.associateBy {it.id}
        val affectedAfter=recalculated.sales.filter {oldSales[it.id]?.let {previous->previous!=it}==true}
        val recordId=when(cmd) {
            is Command.Correct->cmd.recordId
            is Command.Cancel->cmd.recordId
            is Command.Disassemble->item(s,cmd.itemId).purchaseId
            is Command.DeferAttention->cmd.targetId
            else->cmd.id
        }
        val result=recalculated.copy(revision=Math.addExact(s.revision,1),audit=s.audit+Audit(cmd.id,cmd.javaClass.simpleName,recordId,Instant.now().toString(),before,after,affectedAfter.map {oldSales.getValue(it.id)},affectedAfter))
        validate(result)
        return result
    }
    fun validate(s:LedgerState) {
        require(s.revision>=0) {"Некорректная ревизия"}
        val idx=index(s)
        fun unique(ids:List<String>) {require(ids.size<=100_000 && ids.all {it.isNotBlank() && it.length<=200} && ids.distinct().size==ids.size) {"Повторяющиеся или некорректные идентификаторы"}}
        listOf(s.purchases.map {it.id},s.items.map {it.id},s.builds.map {it.id},s.assignments.map {it.id},s.expenses.map {it.id},s.sales.map {it.id},s.audit.map {it.id}).forEach(::unique)
        unique(s.purchases.map {it.id}+s.items.map {it.id}+s.builds.map {it.id}+s.assignments.map {it.id}+s.expenses.map {it.id}+s.sales.map {it.id})
        Photos.validate(s)
        fun creation(id:String,action:String) {val audit=idx.audit[id];require(audit!=null && audit.recordId==id && audit.action==action && audit.after.isNotBlank()) {"Отсутствует история создания записи"}}
        s.purchases.forEach {creation(it.id,"Purchase")};s.builds.forEach {creation(it.id,"NewBuild")};s.expenses.forEach {creation(it.id,"Spend")};s.sales.forEach {creation(it.id,"Sell")}
        s.purchases.forEach {p->
            date(p.date); name(p.name); name(p.category); Money.check(p.total); Money.check(p.extra)
            require(p.note.length<=5000) {"Заметка слишком длинная"}
            val parts=idx.itemsByPurchase[p.id].orEmpty().filter {it.sourceId==null}
            require(p.quantity in 1..1000 && parts.size==p.quantity && Money.sum(parts.map {it.cost})==Money.sum(listOf(p.total,p.extra))) {"Нарушено распределение покупки"}
        }
        s.items.forEach {it->
            Money.check(it.cost); name(it.name); require(idx.purchases.containsKey(it.purchaseId)) {"Нет исходной покупки"}
            it.attentionUntil?.let(::date)
            if(it.sourceId==null)require(it.availableFrom==null && it.splitWeight==null) {"Повреждена дата появления товара"}
            else {
                val source=idx.items[it.sourceId]
                require(source!=null && source.sourceId==null && source.purchaseId==it.purchaseId) {"Не найден источник деталей разбора"}
                val from=requireNotNull(it.availableFrom)
                date(from); Money.check(requireNotNull(it.splitWeight))
                require(from>=purchase(s,it).date) {"Разбор раньше покупки"}
            }
        }
        idx.partsBySource.forEach { (id,parts)->
            val splitDate=parts.first().availableFrom!!
            require(parts.size in 2..100 && parts.all {it.availableFrom==splitDate}) {"Повреждён состав разбора"}
            require(idx.assignmentsByItem[id].orEmpty().none {it.active && (it.endDate==null || it.endDate>splitDate)}) {"На дату разбора товар находится в сборке"}
        }
        s.builds.forEach {date(it.date);name(it.name);it.planPrice?.let(Money::check);it.attentionUntil?.let(::date); require(it.note.length<=5000)}
        s.assignments.forEach {a->
            date(a.date); a.endDate?.let {date(it);require(it>=a.date) {"Извлечение раньше установки"}}
            require(idx.items.containsKey(a.itemId) && idx.builds.containsKey(a.buildId)) {"Связь сборки повреждена"}
            if(a.active) {
                val it=item(s,a.itemId); val b=build(s,a.buildId)
                require(a.date>=availableSince(s,it) && a.date>=b.date) {"Установка раньше появления детали или создания сборки"}
            }
        }
        s.assignments.filter {it.active}.groupBy {it.itemId}.values.forEach {assignments->
            val sorted=assignments.sortedBy {it.date}
            sorted.zipWithNext().forEach {(a,b)->require(a.endDate!=null && a.endDate<=b.date) {"Деталь одновременно в двух сборках"}}
        }
        s.expenses.forEach {e->
            date(e.date); name(e.category); require(e.parts.isNotEmpty() && e.parts.size<=1000 && e.note.length<=5000) {"Проверьте расход"}
            Money.check(e.total)
            e.parts.forEach {p->
                Money.check(p.amount)
                when(p.target) {
                    Target.GENERAL->require(p.targetId==null) {"У общего расхода не должно быть товара"}
                    Target.ITEM->{
                        val it=idx.items[p.targetId] ?: throw IllegalArgumentException("Товар расхода не найден")
                        if(e.active) {
                            require(purchase(s,it).active && e.date>=availableSince(s,it)) {"Расход раньше появления товара или покупка отменена"}
                            partsFrom(s,it.id).firstOrNull()?.availableFrom?.let {splitDate->
                                require(e.date<=splitDate) {"После разбора отнесите расход на конкретную деталь"}
                            }
                        }
                    }
                    Target.BUILD->{val b=idx.builds[p.targetId] ?: throw IllegalArgumentException("Сборка расхода не найдена"); if(e.active) require(b.active && e.date>=b.date) {"Расход раньше сборки или сборка отменена"}}
                }
            }
        }
        // Every report is a signed combination of subsets of these nonnegative sums.
        // Check their combined bound before a transaction or import can reach the UI.
        Money.sum(s.purchases.filter {it.active}.flatMap {listOf(it.total,it.extra)}+
            s.expenses.filter {it.active}.map {it.total}+s.sales.filter {it.active}.map {it.amount})
        val sold=mutableSetOf<String>()
        s.sales.forEach {sale->
            date(sale.date); Money.check(sale.amount); Money.check(sale.cost)
            require(sale.itemIds.isNotEmpty() && sale.itemIds.distinct().size==sale.itemIds.size) {"Пустая или повторная комплектация продажи"}
            require(sale.target!=Target.GENERAL) {"Некорректная продажа"}
            if(sale.target==Target.ITEM) require(sale.itemIds==listOf(sale.targetId)) {"Состав продажи не совпадает с товаром"}
            if(sale.target==Target.BUILD) require(idx.builds.containsKey(sale.targetId)) {"Нет сборки продажи"}
            sale.itemIds.forEach {id->
                val it=idx.items[id] ?: throw IllegalArgumentException("Нет детали продажи")
                if(sale.active) {
                    require(purchase(s,it).active && sale.date>=availableSince(s,it)) {"Продажа раньше появления товара или покупка отменена"}
                    require(!isDisassembled(s,id)) {"Исходный товар уже разобран"}
                    require(sold.add(id)) {"Повторная продажа одной детали"}
                    val assignments=idx.assignmentsByItem[id].orEmpty()
                    val asOf=assignments.filter {it.active && it.date<=sale.date && (it.endDate==null || it.endDate>sale.date)}
                    if(sale.target==Target.ITEM) require(asOf.isEmpty()) {"Деталь на дату продажи находится в сборке"}
                    else require(asOf.size==1 && asOf.single().buildId==sale.targetId) {"Дата продажи не соответствует комплектации"}
                    require(assignments.none {it.active && (it.date>sale.date || (it.endDate!=null && it.endDate>sale.date))}) {"После продажи есть перемещения детали"}
                }
            }
            if(sale.active && sale.target==Target.BUILD) {
                val b=build(s,sale.targetId); require(sale.date>=b.date) {"Продажа раньше сборки"}
                val expected=idx.assignmentsByBuild[b.id].orEmpty().filter {it.active && it.date<=sale.date && (it.endDate==null || it.endDate>sale.date)}.map {it.itemId}.toSet()
                require(expected==sale.itemIds.toSet()) {"Состав продажи не совпадает со сборкой"}
            }
        }
        require(s.sales.filter {it.active && it.target==Target.BUILD}.groupBy {it.targetId}.values.all {it.size==1}) {"Повторная продажа сборки"}
        val calculated=recalculate(s)
        require(s.items==calculated.items) {"Себестоимость деталей разбора не соответствует данным"}
        require(s.sales==calculated.sales) {"Снимок себестоимости продажи не соответствует данным"}
        require(s.drafts.size<=20 && s.drafts.values.all {it.length<=200_000}) {"Черновики повреждены"}
        s.audit.forEach { Instant.parse(it.at); require(it.before.length<=1_000_000 && it.after.length<=1_000_000) {"Слишком большая запись истории"} }
    }
}
