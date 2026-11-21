package ru.parts.ledger.data

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.decodeFromString
import ru.parts.ledger.domain.LedgerJson
import ru.parts.ledger.domain.LedgerState

/** Read the old payload tables on the same SQLite connection as the migration. */
internal fun readLegacyLedger(db: SupportSQLiteDatabase): LedgerState {
    val revision = db.query("SELECT revision FROM metadata WHERE id=1").use { if (it.moveToFirst()) it.getLong(0) else 0L }
    val drafts = db.query("SELECT payload FROM drafts WHERE id=1").use { if (it.moveToFirst()) it.getString(0) else null }
    return LedgerState(
        revision = revision,
        purchases = db.payloads("purchases"),
        items = db.payloads("items"),
        builds = db.payloads("builds"),
        assignments = db.payloads("assignments"),
        expenses = db.payloads("expenses"),
        sales = db.payloads("sales"),
        audit = db.payloads("audit"),
        drafts = readDrafts(drafts)
    )
}

private inline fun <reified T> SupportSQLiteDatabase.payloads(table: String): List<T> =
    query("SELECT payload FROM $table ORDER BY ordinal").use { cursor ->
        buildList { while (cursor.moveToNext()) add(LedgerJson.decodeFromString<T>(cursor.getString(0))) }
    }

internal fun readDrafts(raw: String?): Map<String, String> = raw?.takeIf { it.length <= 4_000_000 }
    ?.let { runCatching { LedgerJson.decodeFromString<Map<String, String>>(it) }.getOrNull() }
    ?.takeIf { it.size <= 20 }?.filterValues { it.length <= 200_000 }.orEmpty()
