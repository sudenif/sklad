package ru.parts.ledger.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.parts.ledger.domain.Money
import java.time.LocalDate

@Composable fun SectionTitle(text:String,action:String?=null,onAction:()->Unit={}) {
    Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(text,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
        if(action!=null)TextButton(onClick=onAction) {Text(action)}
    }
}
@Composable fun EmptyState(title:String,body:String) {
    Column(Modifier.fillMaxWidth().padding(vertical=32.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge)
        Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
    }
}
@Composable fun InfoCard(text:String,error:Boolean=false) {
    Surface(color=if(error)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium) {
        Text(text,Modifier.fillMaxWidth().padding(16.dp),style=MaterialTheme.typography.bodyMedium,color=if(error)MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun MoneyRow(label:String,amount:Long,caption:String?=null,color:Color=Ink) {
    Column(Modifier.fillMaxWidth().padding(vertical=10.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Money.format(amount),style=MaterialTheme.typography.titleSmall,color=color)
        }
        if(caption!=null)Text(caption,style=MaterialTheme.typography.bodySmall,color=Muted,modifier=Modifier.padding(top=4.dp))
    }
}
@Composable fun LedgerRow(title:String,subtitle:String,amount:String="",category:String?=null,leading:(@Composable ()->Unit)?=null,onClick:(()->Unit)?=null) {
    Column(Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(onClick=onClick) else Modifier).padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(category!=null)Text(category,style=MaterialTheme.typography.bodySmall,color=Muted)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
            leading?.invoke()
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                Text(subtitle,style=MaterialTheme.typography.bodySmall,color=Muted)
            }
            if(amount.isNotEmpty())Text(amount,style=MaterialTheme.typography.titleSmall)
        }
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
}
@Composable fun Input(label:String,value:String,onChange:(String)->Unit,money:Boolean=false,number:Boolean=false,multiline:Boolean=false) {
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=!multiline,
        minLines=if(multiline)2 else 1,keyboardOptions=KeyboardOptions(keyboardType=when {money->KeyboardType.Decimal;number->KeyboardType.Number;else->KeyboardType.Text}),shape=MaterialTheme.shapes.small)
}
@Composable fun DateInput(value:String,onChange:(String)->Unit) {
    val context=LocalContext.current
    OutlinedTextField(value=value,onValueChange=onChange,label={Text("Дата")},supportingText={Text("ГГГГ-ММ-ДД")},singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={
        IconButton(onClick={val date=runCatching {LocalDate.parse(value)}.getOrDefault(LocalDate.parse(today()));DatePickerDialog(context,{_,y,m,d->onChange(LocalDate.of(y,m+1,d).toString())},date.year,date.monthValue-1,date.dayOfMonth).show()}) {
            Icon(Icons.Outlined.DateRange,"Выбрать дату",Modifier.size(20.dp))
        }
    })
}
@Composable fun PickField(label:String,value:String,options:List<Pair<String,String>>,onChange:(String)->Unit) {
    var showing by remember {mutableStateOf(false)}
    var query by remember {mutableStateOf("")}
    OutlinedButton(onClick={showing=true;query=""},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(label,style=MaterialTheme.typography.bodySmall,color=Muted)
            Text(options.firstOrNull {it.first==value}?.second?:"Выбрать",style=MaterialTheme.typography.bodyLarge,color=Ink)
        }
    }
    if(showing)AlertDialog(onDismissRequest={showing=false},title={Text(label)},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(options.size>8)Input("Поиск",query,{query=it})
            LazyColumn(Modifier.heightIn(max=400.dp)) {
                val filtered=options.filter {it.second.contains(query,ignoreCase=true)}
                if(filtered.isEmpty())item {Text("Нет подходящих записей",Modifier.padding(vertical=16.dp))}
                items(filtered,key={it.first}) {option->
                    Row(Modifier.fillMaxWidth().clickable {onChange(option.first);showing=false}.padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(option.second,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                        if(option.first==value)Icon(Icons.Outlined.Check,null,Modifier.size(20.dp),tint=Copper)
                    }
                }
            }
        }
    },confirmButton={TextButton(onClick={showing=false}){Text("Закрыть")}})
}
