package org.dlof.dbg

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import org.dlof.dbg.databinding.ActivityMainBinding
import org.dlof.dbg.ui.IssueAdapter
import org.dlof.dbg.validator.DlofRules
import org.dlof.dbg.validator.DlofSmartEngine
import org.dlof.dbg.validator.PackageValidator
import org.dlof.dbg.validator.Severity
import org.dlof.dbg.validator.ValidationReport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * الشاشة الرئيسية. المحرك الذكي المحلي ([DlofSmartEngine]) مدمج مباشرة في مسار
 * "تحليل وإصلاح تلقائي" - وليس خياراً منفصلاً يحتاج تفعيلاً - وكل خطوة يقوم بها
 * الفاحص والمحرك الذكي تُعرض حياً في طرفية برمجية حقيقية أسفل الشاشة، مع إمكانية
 * تنزيل السجل كاملاً كملف نصي في أي وقت.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = IssueAdapter()
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // حالة الملف المفتوح حالياً
    private var currentFileName: String? = null
    private var currentBytes: ByteArray? = null
    private var currentReport: ValidationReport? = null
    private var currentIsPackage: Boolean = false

    // نتيجة آخر تشغيل للمحرك الذكي، مخزّنة لضمان أن ما ظهر في الطرفية هو
    // بالضبط ما سيُحفظ (بعض القرارات، كمعرّف بديل لمعرّف مكرر، عشوائية).
    private var smartResultCache: DlofSmartEngine.SmartFixResult? = null

    // سجل الطرفية الكامل (لتنزيله لاحقاً كملف log)
    private val terminalLog = StringBuilder()

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handlePickedUri(it) }
    }

    private val createFixedDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let { writeFixedFile(it) }
    }

    private val createLogDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { writeLogFile(it) }
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
            runAiFixAndSave()
        }

        binding.btnDownloadLog.setOnClickListener {
            downloadLog()
        }
    }

    // ------------------------------------------------------------------
    // طرفية برمجية حقيقية: كل سطر يُضاف هنا يظهر فوراً في tvTerminal ويُحفظ
    // في terminalLog لتنزيله لاحقاً كاملاً.
    // ------------------------------------------------------------------

    private fun timestamp(): String = DateFormat.format("HH:mm:ss", Date()).toString()

    /** يضيف سطراً للطرفية. آمن الاستدعاء من أي خيط (يُنفَّذ فعلياً على الخيط الرئيسي). */
    private fun termLog(line: String) {
        mainHandler.post {
            val stamped = if (line.isBlank()) "" else "[${timestamp()}] $line"
            terminalLog.append(stamped).append('\n')
            binding.tvTerminal.text = terminalLog.toString()
            binding.svTerminal.post { binding.svTerminal.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun termLogRaw(line: String) {
        mainHandler.post {
            terminalLog.append(line).append('\n')
            binding.tvTerminal.text = terminalLog.toString()
            binding.svTerminal.post { binding.svTerminal.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ------------------------------------------------------------------
    // فتح وفحص الملفات
    // ------------------------------------------------------------------

    private fun handlePickedUri(uri: Uri) {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "file"
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("empty stream")
            runValidation(name, bytes)
        } catch (e: Exception) {
            binding.tvStatus.text = getString(R.string.status_open_failed)
            termLog("[FAIL] تعذّر فتح الملف: ${e.message}")
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
            termLog("[FAIL] تعذّر تحميل النموذج: ${e.message}")
        }
    }

    private fun runValidation(fileName: String, bytes: ByteArray) {
        currentFileName = fileName
        currentBytes = bytes
        currentIsPackage = fileName.substringAfterLast('.', "").lowercase() in DlofRules.PACKAGE_EXTENSIONS
        smartResultCache = null

        binding.tvStatus.text = getString(R.string.status_scanning)
        binding.btnAutoFix.visibility = View.GONE
        adapter.submitList(emptyList())

        termLogRaw("")
        termLog("\$ dlof-dbg scan \"$fileName\" (${bytes.size} بايت)")
        termLog(if (currentIsPackage) "[..] حزمة .dlofpkg — استخراج ملفات .dlof منها..." else "[..] ملف .dlof مفرد — تحليل مباشر...")

        bgExecutor.execute {
            val report = PackageValidator.validate(fileName, ByteArrayInputStream(bytes))
            termLog("[OK] تم تحليل ${report.fileResults.size} ملف .dlof")
            termLog("[..] فحص روابط next/previous بين الملفات، والمعرّفات، والمجالات...")
            mainHandler.post {
                currentReport = report
                showReport(report)
            }
        }
    }

    private fun showReport(report: ValidationReport) {
        // نستخدم شرح المحرك الذكي الأوسع بدلاً من رسالة الفحص المختصرة، حتى في
        // القائمة العادية للمشاكل - بحيث يفهم المستخدم سبب كل مشكلة وأثرها.
        val issues = report.allIssues.map { it.copy(message = DlofSmartEngine.explainIssue(it)) }
        adapter.submitList(issues)

        binding.tvStatus.text = if (issues.isEmpty()) {
            getString(R.string.status_result_ok)
        } else {
            getString(R.string.status_result_issues, issues.size, report.errorCount, report.warningCount)
        }

        if (issues.isEmpty()) {
            termLog("[OK] لا توجد مشاكل. البنية سليمة بالكامل.")
        } else {
            termLog("[نتيجة] ${issues.size} مشكلة — ${report.errorCount} خطأ، ${report.warningCount} تحذير")
            for (issue in report.allIssues.take(20)) {
                val prefix = if (issue.severity == Severity.ERROR) "[ERROR]" else "[WARN]"
                termLog("  $prefix ${issue.fileName}: ${issue.message}")
            }
        }

        // زر "تحليل وإصلاح تلقائي" يظهر متى وُجدت أي مشكلة على الإطلاق؛ المحرك
        // الذكي مدمج داخله دائماً (لا يوجد مسار "بسيط" منفصل يتجاوزه).
        binding.btnAutoFix.visibility = if (issues.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // الإصلاح: AI مدمج بالكامل في المسار الوحيد للإصلاح (وليس خياراً إضافياً)
    // ------------------------------------------------------------------

    private fun runAiFixAndSave() {
        val fileName = currentFileName ?: return
        val bytes = currentBytes ?: return
        val report = currentReport ?: return

        binding.btnAutoFix.isEnabled = false
        binding.tvStatus.text = getString(R.string.status_ai_working)

        termLogRaw("")
        termLog("\$ dlof-ai fix --auto --package \"$fileName\"")
        termLog("[AI] تحميل المحرك الذكي المحلي (لا اتصال إنترنت، كل المعالجة على الجهاز)...")
        termLog("[AI] تطبيق الإصلاحات الآمنة (id/version الناقصين)...")
        termLog("[AI] تحليل قرائن next/previous وإعادة بناء ترتيب الحلقة بالكامل...")

        bgExecutor.execute {
            val entries = if (currentIsPackage) extractDlofEntries(bytes) else mapOf(fileName to bytes.toString(Charsets.UTF_8))
            val result = try {
                DlofSmartEngine.smartFix(entries, report)
            } catch (e: Exception) {
                termLog("[FAIL] تعذّر تشغيل المحرك الذكي: ${e.message}")
                null
            }

            if (result == null) {
                mainHandler.post {
                    binding.btnAutoFix.isEnabled = true
                    binding.tvStatus.text = getString(R.string.status_autofix_failed)
                    Toast.makeText(this, R.string.status_autofix_failed, Toast.LENGTH_LONG).show()
                }
                return@execute
            }

            smartResultCache = result
            mainHandler.post {
                // اعرض كل قرار اتخذه المحرك الذكي كسطر منفصل، بتتابع زمني بسيط
                // يحاكي طرفية حقيقية أثناء العمل، ثم افتح حوار الحفظ تلقائياً.
                revealExplanations(result.explanations.toList(), 0) {
                    if (result.finalFileOrder.size > 1) {
                        termLog("[AI] الترتيب النهائي للحلقة: ${result.finalFileOrder.joinToString(" → ")}")
                    }
                    termLog("[OK] اكتمل التحليل والإصلاح. اختر مكان حفظ النسخة المصححة...")
                    binding.btnAutoFix.isEnabled = true
                    val defaultName = "fixed_" + fileName
                    createFixedDocument.launch(defaultName)
                }
            }
        }
    }

    /** يكشف شروح المحرك الذكي سطراً سطراً بتأخير بسيط، بأسلوب طرفية حية، ثم ينفّذ onDone. */
    private fun revealExplanations(items: List<DlofSmartEngine.SmartExplanation>, index: Int, onDone: () -> Unit) {
        if (index >= items.size) {
            onDone()
            return
        }
        val exp = items[index]
        val line = if (exp.fileName != null) "[AI] (${exp.fileName}) ${exp.message}" else "[AI] ${exp.message}"
        termLog(line)
        mainHandler.postDelayed({ revealExplanations(items, index + 1, onDone) }, 120L)
    }

    private fun writeFixedFile(uri: Uri) {
        val fileName = currentFileName ?: return
        val bytes = currentBytes ?: return
        val report = currentReport ?: return

        bgExecutor.execute {
            try {
                if (currentIsPackage) {
                    writeFixedPackage(uri, bytes, report)
                } else {
                    writeFixedSingleFile(uri, fileName, bytes, report)
                }
                termLog("[OK] تم الحفظ بنجاح ✅")
                mainHandler.post {
                    binding.tvStatus.text = getString(R.string.status_autofix_done)
                    Toast.makeText(this, R.string.status_autofix_done, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                termLog("[FAIL] تعذّر الحفظ: ${e.message}")
                mainHandler.post {
                    binding.tvStatus.text = getString(R.string.status_autofix_failed)
                    Toast.makeText(this, e.message ?: getString(R.string.status_autofix_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** يحسم مصدر الملفات المصححة: نتيجة المحرك الذكي المخزّنة مسبقاً، أو يعيد حسابها احتياطياً. */
    private fun resolveFixedEntries(entries: Map<String, String>, report: ValidationReport): Map<String, String> {
        return smartResultCache?.fixedEntries ?: DlofSmartEngine.smartFix(entries, report).fixedEntries
    }

    private fun writeFixedSingleFile(uri: Uri, fileName: String, bytes: ByteArray, report: ValidationReport) {
        val original = bytes.toString(Charsets.UTF_8)
        val fixedMap = resolveFixedEntries(mapOf(fileName to original), report)
        val fixedText = fixedMap[fileName] ?: original
        contentResolver.openOutputStream(uri)?.use { out ->
            out.write(fixedText.toByteArray(Charsets.UTF_8))
        }
    }

    private fun extractDlofEntries(bytes: ByteArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('.', "").lowercase() in DlofRules.DLOF_EXTENSIONS) {
                    result[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return result
    }

    private fun writeFixedPackage(uri: Uri, bytes: ByteArray, report: ValidationReport) {
        // 1) استخراج كل ملفات .dlof النصية لتطبيق التصحيح عليها
        val dlofEntries = extractDlofEntries(bytes)
        val fixedDlofEntries = resolveFixedEntries(dlofEntries, report)

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

    // ------------------------------------------------------------------
    // تنزيل سجل الطرفية كملف نصي
    // ------------------------------------------------------------------

    private fun downloadLog() {
        if (terminalLog.isBlank()) {
            Toast.makeText(this, R.string.status_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val ts = DateFormat.format("yyyyMMdd_HHmmss", Date()).toString()
        createLogDocument.launch("dlof-dbg-log-$ts.txt")
    }

    private fun writeLogFile(uri: Uri) {
        bgExecutor.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(terminalLog.toString().toByteArray(Charsets.UTF_8))
                }
                mainHandler.post {
                    Toast.makeText(this, R.string.status_log_saved, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, e.message ?: getString(R.string.status_log_failed), Toast.LENGTH_LONG).show()
                }
            }
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
