package ru.parts.ledger.ui

import ru.parts.ledger.domain.CostPart
import ru.parts.ledger.domain.Money
import ru.parts.ledger.domain.Target

fun EditorDraft.expenseParts():List<CostPart> {
    val total=Money.parse(this["amount"])
    val allocations=parts.map {p->CostPart(p.target,if(p.target==Target.GENERAL)null else p.targetId,
        if(parts.size==1)total else Money.parse(p.amount))}
    require(Money.sum(allocations.map {it.amount})==total) {"Сумма распределений должна совпадать с суммой чека"}
    return allocations
}
