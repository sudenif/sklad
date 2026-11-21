package ru.parts.ledger

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.parts.ledger.ui.*
import ru.parts.ledger.domain.Target
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity:ComponentActivity() {
    private val vm:LedgerViewModel by viewModels()
    private data class PendingPhoto(val target:Target,val owner:String,val cameraFile:String?=null)
    private var pendingPhoto:PendingPhoto?=null
    private val captures get()=File(cacheDir,"photo-capture")
    private val folder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri->uri?.let(vm::selectFolder)}
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {uri->uri?.let(vm::export)}
    private val import=registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri->uri?.let(vm::previewRestore)}
    private val raw=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {uri->uri?.let(::exportRaw)}
    private val gallery=registerForActivityResult(ActivityResultContracts.GetContent()) {uri->
        val request=pendingPhoto?.takeIf {it.cameraFile==null}
        if(request!=null) {
            pendingPhoto=null
            if(uri!=null)vm.addPhoto(request.target,request.owner,uri)
        }
    }
    private val camera=registerForActivityResult(object:ActivityResultContracts.TakePicture() {
        override fun createIntent(context:Context,input:Uri):Intent=super.createIntent(context,input).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData=ClipData.newRawUri("Фото",input)
        }
    }) {saved->
        val request=pendingPhoto
        val name=request?.cameraFile
        if(request!=null && name!=null) {
            pendingPhoto=null
            val file=File(captures,name)
            if(saved && file.length()>0)vm.addPhoto(request.target,request.owner,Uri.fromFile(file)) {file.delete()}
            else {
                file.delete()
                if(saved)vm.message.value="Камера не передала снимок"
            }
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val target=savedInstanceState?.getString("photoTarget")
        val owner=savedInstanceState?.getString("photoOwner")
        if(target!=null && owner!=null)pendingPhoto=PendingPhoto(Target.valueOf(target),owner,savedInstanceState.getString("photoCameraFile"))
        enableEdgeToEdge()
        setContent {LedgerTheme {LedgerApp(vm,{folder.launch(null)},{export.launch("ledger-${today()}.zip")},
            {import.launch(arrayOf("application/zip","application/x-zip-compressed","application/json","text/plain","application/octet-stream"))},
            {raw.launch("ledger-recovery-${today()}.zip")},::pickPhoto)}}
    }
    override fun onSaveInstanceState(outState:Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhoto?.let {
            outState.putString("photoTarget",it.target.name)
            outState.putString("photoOwner",it.owner)
            outState.putString("photoCameraFile",it.cameraFile)
        }
    }
    private fun pickPhoto(target:Target,id:String,useCamera:Boolean) {
        if(pendingPhoto!=null || vm.busy.value)return
        var capture:File?=null
        try {
            if(useCamera) {
                require(captures.isDirectory || captures.mkdirs()) {"Не удалось подготовить файл для камеры"}
                captures.listFiles()?.filter {System.currentTimeMillis()-it.lastModified()>86_400_000}?.forEach {it.delete()}
                capture=File.createTempFile("capture-",".jpg",captures)
                pendingPhoto=PendingPhoto(target,id,capture.name)
                camera.launch(FileProvider.getUriForFile(this,"$packageName.camera",capture))
            } else {
                pendingPhoto=PendingPhoto(target,id)
                gallery.launch("image/*")
            }
        } catch(e:Exception) {
            capture?.delete()
            pendingPhoto=null
            vm.message.value=if(useCamera)"Не удалось открыть камеру. Можно добавить снимок из галереи" else "Не удалось открыть галерею"
        }
    }
    private fun exportRaw(uri:Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val database=getDatabasePath("parts-ledger.db")
                val files=listOf(database,java.io.File(database.path+"-wal"),java.io.File(database.path+"-shm")).filter {it.exists()}
                require(files.isNotEmpty()) {"Исходные файлы базы не найдены"}
                contentResolver.openOutputStream(uri)?.use {stream->ZipOutputStream(stream).use {zip->files.forEach {file->zip.putNextEntry(ZipEntry(file.name));file.inputStream().use {it.copyTo(zip)};zip.closeEntry()}}} ?: throw IllegalArgumentException("Не удалось записать архив")
                vm.message.value="Исходные файлы сохранены для восстановления"
            } catch(e:Exception) {vm.message.value=e.message?:"Не удалось сохранить исходные файлы"}
        }
    }
}
