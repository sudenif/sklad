package ru.parts.ledger.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import ru.parts.ledger.domain.*
import ru.parts.ledger.domain.Target
import java.util.UUID

class LedgerRepository(private val db:LedgerDatabase) {
    private val dao=db.ledger()
    val states:Flow<LedgerState> = dao.observeMeta().map {snapshot()}
    private fun read():LedgerState {
        val meta=dao.meta()
        // A disposable editor draft must never make financial data unreadable.
        val rawDrafts=dao.draft()?.payload
        val drafts=readDrafts(rawDrafts)
        val cleaned=LedgerJson.encodeToString(drafts)
        if(rawDrafts!=null && rawDrafts!=cleaned)dao.draft(DraftRow(payload=cleaned))
        return LedgerState(meta?.revision?:0,
            dao.purchases().map {LedgerJson.decodeFromString<PurchaseRecord>(it.payload)},
            dao.items().map {LedgerJson.decodeFromString<Item>(it.payload)},
            dao.builds().map {LedgerJson.decodeFromString<Build>(it.payload)},
            dao.assignments().map {LedgerJson.decodeFromString<Assignment>(it.payload)},
            dao.expenses().map {LedgerJson.decodeFromString<Expense>(it.payload)},
            dao.sales().map {LedgerJson.decodeFromString<Sale>(it.payload)},
            dao.audit().map {LedgerJson.decodeFromString<Audit>(it.payload)},
            drafts,dao.photos())
    }
    private fun write(s:LedgerState) {
        dao.purchases(s.purchases.mapIndexed {i,it->PurchaseRow(it.id,i,LedgerJson.encodeToString(it))})
        dao.items(s.items.mapIndexed {i,it->ItemRow(it.id,it.purchaseId,i,LedgerJson.encodeToString(it))})
        dao.builds(s.builds.mapIndexed {i,it->BuildRow(it.id,i,LedgerJson.encodeToString(it))})
        dao.assignments(s.assignments.mapIndexed {i,it->AssignmentRow(it.id,it.itemId,it.buildId,i,LedgerJson.encodeToString(it))})
        dao.expenses(s.expenses.mapIndexed {i,it->ExpenseRow(it.id,i,LedgerJson.encodeToString(it))})
        // Allocations may be reassigned on correction: replace their rows, not their parent expenses.
        dao.clearAllocations()
        dao.allocations(s.expenses.flatMap {e->e.parts.mapIndexed {i,p->AllocationRow("${e.id}:$i",e.id,if(p.target==Target.ITEM)p.targetId else null,if(p.target==Target.BUILD)p.targetId else null,p.amount)}})
        dao.sales(s.sales.mapIndexed {i,it->SaleRow(it.id,if(it.target==Target.ITEM)it.targetId else null,if(it.target==Target.BUILD)it.targetId else null,i,LedgerJson.encodeToString(it))})
        dao.saleLines(s.sales.flatMap {sale->sale.itemIds.mapIndexed {i,id->SaleLineRow("${sale.id}:$i",sale.id,id)}})
        dao.audit(s.audit.mapIndexed {i,it->AuditRow(it.id,i,LedgerJson.encodeToString(it))})
        dao.draft(DraftRow(payload=LedgerJson.encodeToString(s.drafts)))
        dao.meta(MetaRow(revision=s.revision))
    }
    suspend fun snapshot():LedgerState=db.withTransaction {read().also(Ledger::validate)}
    suspend fun execute(command:Command,clearDraft:Boolean=false):LedgerState=db.withTransaction {
        val before=read(); Ledger.apply(before,command).let {if(clearDraft)it.copy(drafts=emptyMap()) else it}.also {if(it!=before)write(it)}
    }
    suspend fun saveDraft(value:String?) {
        require(value==null || value.length<=200_000) {"Черновик слишком большой: сократите заметку"}
        val drafts=value?.let {mapOf("editor" to it)}?:emptyMap()
        db.withTransaction {dao.draft(DraftRow(payload=LedgerJson.encodeToString(drafts)))}
    }
    private fun photoSource(photo:PhotoRef):ByteArray = dao.photoBytes(photo.id) ?: error("Фото отсутствует в базе")

    suspend fun <T> readArchive(read:(LedgerState,(PhotoRef)->ByteArray)->T):T=db.withTransaction {
        read(read(),::photoSource)
    }

    suspend fun photoBytes(id:String):ByteArray?=db.withTransaction {dao.photoBytes(id)}

    suspend fun addPhoto(target:Target,id:String,bytes:ByteArray):Unit=db.withTransaction {
        require(Photos.isJpeg(bytes)) {"Не удалось обработать снимок"}
        val state=read()
        require(when(target) {
            Target.ITEM->Ledger.activeItems(state).any {it.id==id}
            Target.BUILD->state.builds.any {it.id==id && it.active}
            Target.GENERAL->false
        }) {"Карточка для фото недоступна"}
        val count=state.photos.count {it.ownerId==id}
        require(count<Photos.MAX_PER_CARD) {"На карточку можно добавить до ${Photos.MAX_PER_CARD} снимков"}
        require(state.photos.size<Photos.MAX_COUNT) {"В учёте достигнут лимит снимков"}
        dao.photo(PhotoRow(UUID.randomUUID().toString(),id.takeIf {target==Target.ITEM},id.takeIf {target==Target.BUILD},count,bytes.size,Photos.checksum(bytes),bytes))
        advanceRevision()
    }

    suspend fun makeCover(id:String):Unit=db.withTransaction {
        val rows=dao.photos()
        val selected=rows.firstOrNull {it.id==id} ?: error("Фото не найдено")
        if(selected.ordinal!=0) {
            val others=rows.filter {it.ownerId==selected.ownerId && it.id!=id}
            (listOf(selected)+others).forEachIndexed {index,photo->dao.photoOrder(photo.id,index)}
            advanceRevision()
        }
    }

    suspend fun deletePhoto(id:String):Unit=db.withTransaction {
        val rows=dao.photos()
        val selected=rows.firstOrNull {it.id==id} ?: return@withTransaction
        dao.deletePhoto(id)
        rows.filter {it.ownerId==selected.ownerId && it.id!=id}.forEachIndexed {index,photo->dao.photoOrder(photo.id,index)}
        advanceRevision()
    }

    private fun advanceRevision() {dao.meta(MetaRow(revision=Math.addExact(dao.meta()?.revision?:0,1)))}

    suspend fun restore(state:LedgerState,loadPhoto:(PhotoRef)->ByteArray={error("В файле нет снимка")},protect:(LedgerState,(PhotoRef)->ByteArray)->Unit={_,_->}):Unit=db.withTransaction {
        Ledger.validate(state)
        protect(read(),::photoSource)
        dao.clearPhotos()
        dao.clearSaleLines();dao.clearSales();dao.clearAllocations();dao.clearExpenses();dao.clearAssignments();dao.clearItems();dao.clearPurchases();dao.clearBuilds();dao.clearAudit()
        write(state)
        state.photos.forEach {photo->
            val bytes=Photos.verify(photo,loadPhoto(photo))
            dao.photo(PhotoRow(photo.id,photo.itemId,photo.buildId,photo.ordinal,photo.size,photo.checksum,bytes))
        }
    }
}
