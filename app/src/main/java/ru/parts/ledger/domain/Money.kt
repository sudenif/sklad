package ru.parts.ledger.domain

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Money {
    const val MAX = 9_000_000_000_000_000L
    fun parse(text:String):Long = try {
        val normalized=text.trim().replace(" ","").replace("\u00a0","").replace("\u202f","").replace(',','.')
        require(normalized.matches(Regex("[0-9]+(\\.[0-9]{1,2})?"))) { "Введите сумму, например 1250,50" }
        BigDecimal(normalized).movePointRight(2).longValueExact().also { check(it) }
    } catch (e:ArithmeticException) { throw IllegalArgumentException("Слишком большая сумма",e) }
    fun check(value:Long) { require(value in 0..MAX) { "Сумма должна быть от 0 до 90 трлн ₽" } }
    fun sum(values:Iterable<Long>):Long = try { values.fold(0L, Math::addExact) }
        catch (e:ArithmeticException) { throw IllegalArgumentException("Сумма превышает допустимый предел",e) }
    fun split(total:Long,count:Int):List<Long> {
        check(total); require(count in 1..1000) { "Количество: от 1 до 1000" }
        return List(count) { total/count + if(it<total%count) 1 else 0 }
    }
    fun format(value:Long):String {
        val symbols=DecimalFormatSymbols(Locale.forLanguageTag("ru-RU")).apply { groupingSeparator=' '; decimalSeparator=',' }
        return DecimalFormat(if(value%100==0L) "#,##0" else "#,##0.00",symbols).format(BigDecimal.valueOf(value,2))+" ₽"
    }
    fun input(value:Long)=BigDecimal.valueOf(value,2).stripTrailingZeros().toPlainString().replace('.',',')
}
