package com.eden.savetool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val GAME_PKG = "jp.co.yoozoo.projectedenjp"
        private const val REQ_PERM = 1002

        private val SAVE_DIR: File by lazy {
            File(
                Environment.getExternalStorageDirectory(),
                "Android/data/$GAME_PKG/files/eden_save"
            )
        }
        private val EXPORT_DIR: File by lazy {
            File(Environment.getExternalStorageDirectory(), "Download")
        }
    }

    private lateinit var tvLog: TextView
    private lateinit var btnGrant: Button
    private lateinit var btnExport: Button
    private lateinit var btnImport: Button

    private var busy = false
    private var lastPermState: Boolean? = null

    private val pickZipLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { doImport(it) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)
        btnGrant = findViewById(R.id.btnGrant)
        btnExport = findViewById(R.id.btnExport)
        btnImport = findViewById(R.id.btnImport)

        findViewById<TextView>(R.id.tvPath).text =
            "存档目录：\n${SAVE_DIR.absolutePath}\n导出目录：\n${EXPORT_DIR.absolutePath}"
        appendLog("就绪。导出文件会保存到 Download 目录。")

        btnGrant.setOnClickListener { requestStoragePermission() }
        btnExport.setOnClickListener { doExport() }
        btnImport.setOnClickListener { pickZip() }

        refreshPermState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermState()
    }

    // ---------------- 权限 ----------------

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }

    private fun refreshPermState() {
        val ok = hasStoragePermission()
        if (lastPermState != ok) {
            appendLog(if (ok) "✔ 已获得「所有文件访问」权限" else "✘ 尚未授权，请点击按钮①")
            lastPermState = ok
        }
        btnGrant.isEnabled = !ok
        btnExport.isEnabled = ok && !busy
        btnImport.isEnabled = ok && !busy
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                REQ_PERM
            )
        }
    }

    // ---------------- 导出 ----------------

    private fun doExport() {
        if (busy) return
        if (!SAVE_DIR.exists()) {
            appendLog("✘ 存档目录不存在：${SAVE_DIR.absolutePath}")
            toast("找不到存档目录，请先运行一次游戏")
            return
        }

        busy = true
        refreshPermState()
        appendLog("开始导出…")

        thread {
            try {
                EXPORT_DIR.mkdirs()
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val zipFile = File(EXPORT_DIR, "eden_save_$ts.zip")

                var count = 0
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    count = addDirToZip(zos, SAVE_DIR, SAVE_DIR)
                }

                runOnUiThread {
                    appendLog("✔ 导出成功（$count 个文件）")
                    appendLog("  ${zipFile.absolutePath}")
                    toast("导出成功")
                    busy = false
                    refreshPermState()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("✘ 导出失败：${e.message}")
                    busy = false
                    refreshPermState()
                }
            }
        }
    }

    private fun addDirToZip(zos: ZipOutputStream, dir: File, base: File): Int {
        var count = 0
        val files = dir.listFiles() ?: return 0
        for (f in files) {
            if (f.isDirectory) {
                count += addDirToZip(zos, f, base)
            } else {
                val entryName = f.absolutePath.substring(base.absolutePath.length + 1)
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
                count++
            }
        }
        return count
    }

    // ---------------- 导入 ----------------

    private fun pickZip() {
        if (busy) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream"
                )
            )
        }
        pickZipLauncher.launch(intent)
    }

    private fun doImport(uri: Uri) {
        busy = true
        refreshPermState()
        appendLog("开始导入…")

        thread {
            try {
                // 1. 自动备份现有存档
                if (SAVE_DIR.exists() && !SAVE_DIR.listFiles().isNullOrEmpty()) {
                    EXPORT_DIR.mkdirs()
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val backup = File(EXPORT_DIR, "eden_save_backup_$ts.zip")
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(backup))).use { zos ->
                        addDirToZip(zos, SAVE_DIR, SAVE_DIR)
                    }
                    runOnUiThread { appendLog("  已自动备份原存档：${backup.name}") }
                }

                // 2. 清空现有存档
                SAVE_DIR.mkdirs()
                SAVE_DIR.listFiles()?.forEach { it.deleteRecursively() }

                // 3. 解压覆盖
                var count = 0
                contentResolver.openInputStream(uri)?.use { input ->
                    count = unzip(input, SAVE_DIR)
                } ?: throw IOException("无法打开所选文件")

                runOnUiThread {
                    appendLog("✔ 导入成功（$count 个文件）")
                    toast("导入成功，请启动游戏检查")
                    busy = false
                    refreshPermState()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("✘ 导入失败：${e.message}")
                    busy = false
                    refreshPermState()
                }
            }
        }
    }

    private fun unzip(input: InputStream, targetDir: File): Int {
        var count = 0
        val targetPath = targetDir.canonicalPath
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                val outPath = outFile.canonicalPath
                if (outPath != targetPath && !outPath.startsWith(targetPath + File.separator)) {
                    throw IOException("压缩包包含非法路径：${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                    count++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return count
    }

    // ---------------- 工具 ----------------

    private fun appendLog(msg: String) {
        runOnUiThread { tvLog.append("\n$msg") }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}