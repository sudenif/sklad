package ru.parts.ledger.domain

import java.math.BigInteger

internal object Breakdown {
    fun apply(s: LedgerState, command: Command.Disassemble): LedgerState {
        val source = Ledger.freeItems(s).firstOrNull { it.id == command.itemId && it.sourceId == null }
            ?: throw IllegalArgumentException("Для разбора выберите свободный купленный товар")
        require(command.parts.size in 2..100) { "Добавьте от 2 до 100 деталей" }
        require(command.parts.all { it.category in itemCategories }) { "Выберите категории деталей" }
        command.parts.forEach { Money.check(it.amount) }
        require(Money.sum(command.parts.map { it.amount }) == Ledger.itemCost(s, source.id)) {
            "Распределите всю себестоимость исходного товара"
        }
        val newItems = command.parts.mapIndexed { index, part ->
            Item("${command.id}:part:$index", source.purchaseId, part.name.trim(), part.category,
                part.amount, source.id, command.date, part.amount)
        }
        return s.copy(items = s.items + newItems)
    }

    fun recalculate(s: LedgerState): LedgerState {
        val index = Ledger.index(s)
        if (index.partsBySource.isEmpty()) return s
        val portions = index.partsBySource.flatMap { (source, parts) ->
            val total = Ledger.itemCost(s, source)
            val weights = parts.map { requireNotNull(it.splitWeight) }
            val denominator = Money.sum(weights)
            val amounts = if (denominator == 0L) Money.split(total, parts.size) else {
                var cumulative = 0L
                var distributed = 0L
                weights.map { weight ->
                    cumulative = Math.addExact(cumulative, weight)
                    // Round cumulative shares so all copies preserve the last kopeck.
                    val next = BigInteger.valueOf(total).multiply(BigInteger.valueOf(cumulative))
                        .divide(BigInteger.valueOf(denominator)).toLong() // The quotient is between zero and total.
                    (next - distributed).also { distributed = next }
                }
            }
            parts.mapIndexed { i, part -> part.id to amounts[i] }
        }.toMap()
        return s.copy(items = s.items.map { item -> portions[item.id]?.let { item.copy(cost = it) } ?: item })
    }
}
