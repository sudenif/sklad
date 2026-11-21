package ru.parts.ledger.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class AttentionRow(val target: Target, val id: String, val name: String, val days: Long, val cost: Long)

object Attention {
    const val THRESHOLD = 30L
    const val LONG_STAY = 60L

    fun rows(state: LedgerState, today: LocalDate): List<AttentionRow> {
        val todayText = today.toString()
        val s = state.copy(expenses = state.expenses.filter { it.date <= todayText })
        val index = Ledger.index(s)
        fun days(date: String) = ChronoUnit.DAYS.between(LocalDate.parse(date), today)
        val groups = Ledger.stockAt(s, todayText).groupBy { item ->
            index.assignmentsByItem[item.id].orEmpty().firstOrNull { assignment ->
                assignment.active && assignment.date <= todayText && (assignment.endDate == null || assignment.endDate > todayText)
            }?.buildId
        }
        val items = groups[null].orEmpty().mapNotNull { item ->
            val age = days(Ledger.purchase(s, item).date)
            if (age < THRESHOLD || item.attentionUntil.orEmpty() > todayText) null
            else AttentionRow(Target.ITEM, item.id, item.name, age, Ledger.itemCost(s, item.id))
        }
        val builds = groups.entries.mapNotNull { (id, parts) ->
            val build = index.builds[id] ?: return@mapNotNull null
            if (build.attentionUntil.orEmpty() > todayText) return@mapNotNull null
            val age = parts.maxOf { days(Ledger.purchase(s, it).date) }
            if (age < THRESHOLD) null else AttentionRow(Target.BUILD, build.id, build.name, age, Ledger.buildCost(s, build.id, parts))
        }
        return (items + builds).sortedByDescending { it.days }
    }
}
