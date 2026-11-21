package ru.parts.ledger.domain

import java.time.YearMonth

data class MonthlyReport(val revenue:Long=0,val cost:Long=0,val overhead:Long=0,val purchases:Long=0,val payouts:Long=0,val stock:Long=0,val dealCount:Int=0) {
    val dealProfit:Long get()=Math.subtractExact(revenue,cost)
    val profit:Long get()=Math.subtractExact(dealProfit,overhead)
    val cash:Long get()=Math.subtractExact(Math.subtractExact(revenue,purchases),payouts)
}
object Reports {
    fun month(s:LedgerState,month:YearMonth):MonthlyReport {
        val idx=Ledger.index(s)
        val start=month.atDay(1).toString(); val end=month.atEndOfMonth().toString()
        fun within(date:String)=date>=start && date<=end
        val sales=s.sales.filter {it.active && within(it.date)}
        val stockItems=Ledger.stockAt(s,end)
        val stockIds=stockItems.map {it.id}.toSet()
        val inventoryCosts=s.expenses.filter {it.active && it.date<=end}.flatMap {it.parts}.filter {p->when(p.target) {
            Target.ITEM->p.targetId in stockIds
            Target.BUILD->{val b=idx.builds[p.targetId]?.takeIf {it.active}; b!=null && b.date<=end && (Ledger.buildSale(s,b.id)?.date?.let {it>end}?:true)}
            Target.GENERAL->false
        }}
        return MonthlyReport(
            revenue=Money.sum(sales.map {it.amount}), cost=Money.sum(sales.map {it.cost}),
            overhead=Money.sum(s.expenses.filter {it.active && within(it.date)}.flatMap {it.parts}.filter {it.target==Target.GENERAL}.map {it.amount}),
            purchases=Money.sum(s.purchases.filter {it.active && !it.opening && within(it.date)}.flatMap {listOf(it.total,it.extra)}),
            payouts=Money.sum(s.expenses.filter {it.active && within(it.date)}.map {it.total}),
            stock=Money.sum(stockItems.map {it.cost}+inventoryCosts.map {it.amount}),dealCount=sales.size
        )
    }
}
