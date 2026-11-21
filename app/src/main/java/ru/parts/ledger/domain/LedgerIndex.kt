package ru.parts.ledger.domain

/** Immutable lookup tables shared by calculations for the same state instance. */
internal class LedgerIndex(val state:LedgerState) {
    val purchases=state.purchases.associateBy {it.id}
    val items=state.items.associateBy {it.id}
    val builds=state.builds.associateBy {it.id}
    val audit=state.audit.associateBy {it.id}
    val itemsByPurchase=state.items.groupBy {it.purchaseId}
    val partsBySource=state.items.filter {it.sourceId!=null}.groupBy {requireNotNull(it.sourceId)}
    val activeItems=state.items.filter {purchases[it.purchaseId]?.active==true}
    val assignmentsByItem=state.assignments.groupBy {it.itemId}
    val assignmentsByBuild=state.assignments.groupBy {it.buildId}
    val currentAssignments=state.assignments.filter {it.active && it.endDate==null}.associateBy {it.itemId}
    val currentByBuild=state.assignments.filter {it.active && it.endDate==null}.groupBy {it.buildId}
    val itemSales=buildMap {state.sales.filter {it.active}.forEach {sale->sale.itemIds.forEach {put(it,sale)}}}
    val buildSales=state.sales.filter {it.active && it.target==Target.BUILD}.associateBy {it.targetId}
    private val allocated=state.expenses.filter {it.active}.flatMap {e->e.parts.mapIndexed {i,p->p to CostSnapshot("${e.id}:$i",e.category,p.amount)}}
    val itemCosts=allocated.filter {it.first.target==Target.ITEM}.groupBy({it.first.targetId},{it.second})
    val buildCosts=allocated.filter {it.first.target==Target.BUILD}.groupBy({it.first.targetId},{it.second})
}
