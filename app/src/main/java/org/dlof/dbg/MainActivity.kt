package org.dlof.dbg

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import org.dlof.dbg.databinding.ActivityMainBinding
import org.dlof.dbg.ui.IssueAdapter
import org.dlof.dbg.validator.DlofAutoFixer
import org.dlof.dbg.validator.DlofRules
import org.dlof.dbg.validator.PackageValidator
import org.dlof.dbg.validator.ValidationReport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = IssueAdapter()
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // حالة الملف المفتوح حالياً، مطلوبة للتصحيح التلقائي وإعادة التصدير
    private var currentFileName: String? = null
    private var currentBytes: ByteArray? = null
    private var currentReport: ValidationReport? = null
    private var currentIsPackage: Boolean = false

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handlePickedUri(it) }
    }

    private val createFixedDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let { writeFixedFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.rvIssues.layoutManager = LinearLayoutManager(this)
        binding.rvIssues.adapter = adapter

        binding.btnOpenFile.setOnClickListener {
            openDocument.launch(arrayOf("*/*"))
        }

        binding.btnSampleValid.setOnClickListener {
            loadFromAssets("samples/valid_sample.dlof")
        }
        binding.btnSampleBroken.setOnClickListener {
            loadFromAssets("samples/broken_sample.dlofpkg")
        }

        binding.btnAutoFix.setOnClickListener {
            val defaultName = "fixed_" + (currentFileName ?: "output.dlof")
            createFixedDocument.launch(defaultName)
        }
    }

    private fun handlePickedUri(uri: Uri) {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "file"
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("empty stream")
            runValidation(name, bytes)
        } catch (e: Exception) {
            binding.tvStatus.text = getString(R.string.status_open_failed)
            Toast.makeText(this, e.message ?: getString(R.string.status_open_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun loadFromAssets(assetPath: String) {
        try {
            val bytes = assets.open(assetPath).use { it.readBytes() }
            val name = assetPath.substringAfterLast('/')
            runValidation(name, bytes)
        } catch (e: Exception) {
            binding.tvStatus.text = getString(R.string.status_open_failed)
        }
    }

    private fun runValidation(fileName: String, bytes: ByteArray) {
        currentFileName = fileName
        currentBytes = bytes
        currentIsPackage = fileName.substringAfterLast('.', "").lowercase() in DlofRules.PACKAGE_EXTENSIONS

        binding.tvStatus.text = getString(R.string.status_scanning)
        binding.btnAutoFix.visibility = android.view.View.GONE
        adapter.submitList(emptyList())

        bgExecutor.execute {
            val report = PackageValidator.validate(fileName, ByteArrayInputStream(bytes))
            mainHandler.post {
                currentReport = report
                showReport(report)
            }
        }
    }

    private fun showReport(report: ValidationReport) {
        val issues = report.allIssues
        adapter.submitList(issues)

        binding.tvStatus.text = if (issues.isEmpty()) {
            getString(R.string.status_result_ok)
        } else {
            getString(R.string.status_result_issues, issues.size, report.errorCount, report.warningCount)
        }

        binding.btnAutoFix.visibility = if (report.hasAutoFixableIssues) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun writeFixedFile(uri: Uri) {
        val fileName = currentFileName ?: return
        val bytes = currentBytes ?: return
        val report = currentReport ?: return

        bgExecutor.execute {
            try {
                if (currentIsPackage) {
                    writeFixedPackage(uri, fileName, bytes, report)
                } else {
                    writeFixedSingleFile(uri, fileName, bytes, report)
                }
                mainHandler.post {
                    binding.tvStatus.text = getString(R.string.status_autofix_done)
                    Toast.makeText(this, R.string.status_autofix_done, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    binding.tvStatus.text = getString(R.string.status_autofix_failed)
                    Toast.makeText(this, e.message ?: getString(R.string.status_autofix_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun writeFixedSingleFile(uri: Uri, fileName: String, bytes: ByteArray, report: ValidationReport) {
        val original = bytes.toString(Charsets.UTF_8)
        val fixedMap = DlofAutoFixer.autoFix(mapOf(fileName to original), report)
        val fixedText = fixedMap[fileName] ?: original
        contentResolver.openOutputStream(uri)?.use { out ->
            out.write(fixedText.toByteArray(Charsets.UTF_8))
        }
    }

    private fun writeFixedPackage(uri: Uri, fileName: String, bytes: ByteArray, report: ValidationReport) {
        // 1) استخراج كل ملفات .dlof النصية لتطبيق التصحيح عليها
        val dlofEntries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('.', "").lowercase() in DlofRules.DLOF_EXTENSIONS) {
                    dlofEntries[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val fixedDlofEntries = DlofAutoFixer.autoFix(dlofEntries, report)

        // 2) إعادة بناء الحزمة كاملة: نسخ كل شيء كما هو، واستبدال محتوى ملفات .dlof فقط
        val outputBuffer = ByteArrayOutputStream()
        ZipOutputStream(outputBuffer).use { zos ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val isDlof = !entry.isDirectory && name.substringAfterLast('.', "").lowercase() in DlofRules.DLOF_EXTENSIONS
                    zos.putNextEntry(ZipEntry(name))
                    if (isDlof && fixedDlofEntries.containsKey(name)) {
                        zos.write(fixedDlofEntries.getValue(name).toByteArray(Charsets.UTF_8))
                    } else if (!entry.isDirectory) {
                        zis.copyTo(zos)
                    }
                    zos.closeEntry()
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        contentResolver.openOutputStream(uri)?.use { out ->
            out.write(outputBuffer.toByteArray())
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bgExecutor.shutdown()
    }
}
