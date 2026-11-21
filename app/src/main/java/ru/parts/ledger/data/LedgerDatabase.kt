package ru.parts.ledger.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import ru.parts.ledger.domain.PhotoRef
import ru.parts.ledger.domain.LedgerState

@Entity(tableName="purchases") data class PurchaseRow(@PrimaryKey val id:String,val ordinal:Int,val payload:String)
@Entity(tableName="items",foreignKeys=[ForeignKey(entity=PurchaseRow::class,parentColumns=["id"],childColumns=["purchaseId"],onDelete=ForeignKey.RESTRICT)],indices=[Index("purchaseId")])
data class ItemRow(@PrimaryKey val id:String,val purchaseId:String,val ordinal:Int,val payload:String)
@Entity(tableName="builds") data class BuildRow(@PrimaryKey val id:String,val ordinal:Int,val payload:String)
@Entity(tableName="assignments",foreignKeys=[ForeignKey(entity=ItemRow::class,parentColumns=["id"],childColumns=["itemId"],onDelete=ForeignKey.RESTRICT),ForeignKey(entity=BuildRow::class,parentColumns=["id"],childColumns=["buildId"],onDelete=ForeignKey.RESTRICT)],indices=[Index("itemId"),Index("buildId")])
data class AssignmentRow(@PrimaryKey val id:String,val itemId:String,val buildId:String,val ordinal:Int,val payload:String)
@Entity(tableName="expenses") data class ExpenseRow(@PrimaryKey val id:String,val ordinal:Int,val payload:String)
@Entity(tableName="allocations",foreignKeys=[ForeignKey(entity=ExpenseRow::class,parentColumns=["id"],childColumns=["expenseId"],onDelete=ForeignKey.RESTRICT),ForeignKey(entity=ItemRow::class,parentColumns=["id"],childColumns=["itemId"],onDelete=ForeignKey.RESTRICT),ForeignKey(entity=BuildRow::class,parentColumns=["id"],childColumns=["buildId"],onDelete=ForeignKey.RESTRICT)],indices=[Index("expenseId"),Index("itemId"),Index("buildId")])
data class AllocationRow(@PrimaryKey val id:String,val expenseId:String,val itemId:String?,val buildId:String?,val amount:Long)
@Entity(tableName="sales",foreignKeys=[ForeignKey(entity=ItemRow::class,parentColumns=["id"],childColumns=["itemId"],onDelete=ForeignKey.RESTRICT),ForeignKey(entity=BuildRow::class,parentColumns=["id"],childColumns=["buildId"],onDelete=ForeignKey.RESTRICT)],indices=[Index("itemId"),Index("buildId")])
data class SaleRow(@PrimaryKey val id:String,val itemId:String?,val buildId:String?,val ordinal:Int,val payload:String)
@Entity(tableName="sale_lines",foreignKeys=[ForeignKey(entity=SaleRow::class,parentColumns=["id"],childColumns=["saleId"],onDelete=ForeignKey.RESTRICT),ForeignKey(entity=ItemRow::class,parentColumns=["id"],childColumns=["itemId"],onDelete=ForeignKey.RESTRICT)],indices=[Index("saleId"),Index("itemId")])
data class SaleLineRow(@PrimaryKey val id:String,val saleId:String,val itemId:String)
@Entity(tableName="audit") data class AuditRow(@PrimaryKey val id:String,val ordinal:Int,val payload:String)
@Entity(tableName="metadata") data class MetaRow(@PrimaryKey val id:Int=1,val revision:Long)
@Entity(tableName="drafts") data class DraftRow(@PrimaryKey val id:Int=1,val payload:String)

@Entity(tableName="photos", foreignKeys=[
    ForeignKey(entity=ItemRow::class,parentColumns=["id"],childColumns=["itemId"],onDelete=ForeignKey.RESTRICT),
    ForeignKey(entity=BuildRow::class,parentColumns=["id"],childColumns=["buildId"],onDelete=ForeignKey.RESTRICT)
], indices=[Index("itemId"),Index("buildId")])
data class PhotoRow(@PrimaryKey val id:String,val itemId:String?,val buildId:String?,val ordinal:Int,val size:Int,val checksum:String,val bytes:ByteArray)

@Dao interface LedgerDao {
    @Query("SELECT * FROM purchases ORDER BY ordinal") fun purchases():List<PurchaseRow>
    @Query("SELECT * FROM items ORDER BY ordinal") fun items():List<ItemRow>
    @Query("SELECT * FROM builds ORDER BY ordinal") fun builds():List<BuildRow>
    @Query("SELECT * FROM assignments ORDER BY ordinal") fun assignments():List<AssignmentRow>
    @Query("SELECT * FROM expenses ORDER BY ordinal") fun expenses():List<ExpenseRow>
    @Query("SELECT * FROM sales ORDER BY ordinal") fun sales():List<SaleRow>
    @Query("SELECT * FROM audit ORDER BY ordinal") fun audit():List<AuditRow>
    @Query("SELECT * FROM metadata WHERE id=1") fun meta():MetaRow?
    @Query("SELECT * FROM drafts WHERE id=1") fun draft():DraftRow?
    @Query("SELECT id,itemId,buildId,ordinal,size,checksum FROM photos ORDER BY ordinal,id") fun photos():List<PhotoRef>
    @Query("SELECT bytes FROM photos WHERE id=:id") fun photoBytes(id:String):ByteArray?
    @Query("SELECT * FROM metadata WHERE id=1") fun observeMeta():Flow<MetaRow?>
    @Upsert fun purchases(rows:List<PurchaseRow>)
    @Upsert fun items(rows:List<ItemRow>)
    @Upsert fun builds(rows:List<BuildRow>)
    @Upsert fun assignments(rows:List<AssignmentRow>)
    @Upsert fun expenses(rows:List<ExpenseRow>)
    @Upsert fun allocations(rows:List<AllocationRow>)
    @Upsert fun sales(rows:List<SaleRow>)
    @Upsert fun saleLines(rows:List<SaleLineRow>)
    @Upsert fun audit(rows:List<AuditRow>)
    @Upsert fun meta(row:MetaRow)
    @Upsert fun draft(row:DraftRow)
    @Insert fun photo(row:PhotoRow)
    @Query("UPDATE photos SET ordinal=:ordinal WHERE id=:id") fun photoOrder(id:String,ordinal:Int)
    @Query("DELETE FROM photos WHERE id=:id") fun deletePhoto(id:String)
    @Query("DELETE FROM photos") fun clearPhotos()
    @Query("DELETE FROM sale_lines") fun clearSaleLines()
    @Query("DELETE FROM sales") fun clearSales()
    @Query("DELETE FROM allocations") fun clearAllocations()
    @Query("DELETE FROM expenses") fun clearExpenses()
    @Query("DELETE FROM assignments") fun clearAssignments()
    @Query("DELETE FROM items") fun clearItems()
    @Query("DELETE FROM purchases") fun clearPurchases()
    @Query("DELETE FROM builds") fun clearBuilds()
    @Query("DELETE FROM audit") fun clearAudit()
}

@Database(entities=[PurchaseRow::class,ItemRow::class,BuildRow::class,AssignmentRow::class,ExpenseRow::class,AllocationRow::class,SaleRow::class,SaleLineRow::class,AuditRow::class,MetaRow::class,DraftRow::class,PhotoRow::class],version=2,exportSchema=true)
abstract class LedgerDatabase:RoomDatabase() { abstract fun ledger():LedgerDao }

fun migration1To2(protect:(LedgerState)->Unit) = object : Migration(1,2) {
    override fun migrate(db:SupportSQLiteDatabase) {
        protect(readLegacyLedger(db))
        db.execSQL("""CREATE TABLE `photos` (`id` TEXT NOT NULL PRIMARY KEY, `itemId` TEXT, `buildId` TEXT,
            `ordinal` INTEGER NOT NULL, `size` INTEGER NOT NULL, `checksum` TEXT NOT NULL, `bytes` BLOB NOT NULL,
            FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`buildId`) REFERENCES `builds`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX `index_photos_itemId` ON `photos` (`itemId`)")
        db.execSQL("CREATE INDEX `index_photos_buildId` ON `photos` (`buildId`)")
    }
}
