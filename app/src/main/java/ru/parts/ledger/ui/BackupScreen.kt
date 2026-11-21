package ru.parts.ledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.parts.ledger.backup.*
import ru.parts.ledger.domain.LedgerState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun displayTime(value:String)=runCatching {DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(Instant.parse(value).atZone(ZoneId.of("Europe/Moscow")))}.getOrDefault("Ещё нет")
@Composable fun BackupScreen(state:LedgerState,status:BackupStatus,busy:Boolean,vm:LedgerViewModel,chooseFolder:()->Unit,export:()->Unit,import:()->Unit) {
    val current=remember(state,status){status.fingerprint.isNotEmpty() && status.fingerprint==BackupCodec.fingerprint(state)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Копии и восстановление",style=MaterialTheme.typography.headlineMedium)
        InfoCard(if(status.folder.isBlank())"Выберите папку вне приложения. Внутренняя копия не защитит данные при удалении приложения" else if(current)"Внешняя копия проверена и соответствует текущим данным" else "Есть изменения после последней внешней копии")
        if(status.error.isNotBlank())InfoCard(status.error,true)
        Text("Последняя внешняя копия\n${displayTime(status.lastAt)}",style=MaterialTheme.typography.bodyMedium)
        Text("Внутренняя защитная копия\n${displayTime(status.internalAt)}",style=MaterialTheme.typography.bodySmall,color=Muted)
        Text(if(status.folder.isBlank())"Папка не выбрана" else "Папка для автоматических копий выбрана",style=MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick=chooseFolder,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(status.folder.isBlank())"Выбрать папку копий" else "Изменить папку копий")}
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {Text("Автоматически сохранять в папку",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(checked=status.automatic,onCheckedChange={vm.automaticBackup(it)},enabled=!busy)}
        Button(onClick={vm.saveBackup()},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Сохранить копию")}
        OutlinedButton(onClick=export,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Сохранить отдельный файл")}
        HorizontalDivider()
        Text("Восстановление заменит текущие записи полностью. Перед заменой создаётся защитная копия",style=MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick=import,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Восстановить из файла")}
        OutlinedButton(onClick={vm.showInternalCopies()},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Внутренние и защитные копии")}
        Text("Копия перед восстановлением хранится отдельно и не вытесняется обычными сохранениями. Её можно восстановить или выгрузить в файл",style=MaterialTheme.typography.bodySmall,color=Muted)
        Text("Копия ZIP включает записи и фото. Для переноса на другой телефон сохраните весь файл",style=MaterialTheme.typography.bodySmall,color=Muted)
        SectionTitle("О приложении")
        Text("Учёт комплектующих · 1.1.0\nЛокальная база, без аккаунта и сервера",style=MaterialTheme.typography.bodyMedium)
        Text("Оформление по unslop\nDamir Sokolovsky (@heydamir) · CC BY 4.0\nheydamir.design/unslop\n\nAndroidX, Compose, Room, WorkManager, Material icons, Kotlin и kotlinx — Apache 2.0",style=MaterialTheme.typography.bodySmall,color=Muted)
        Spacer(Modifier.height(32.dp))
    }
}
