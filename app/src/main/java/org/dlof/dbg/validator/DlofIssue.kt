package org.dlof.dbg.validator

enum class Severity { ERROR, WARNING, INFO }

/**
 * أنواع (أكواد) المشاكل التي يكشفها الفاحص. تُستخدم داخلياً لتحديد أي المشاكل
 * قابلة للتصحيح التلقائي في [DlofAutoFixer], وتُعرض كنص مقروء للمستخدم.
 */
enum class IssueCode(val autoFixable: Boolean) {
    XML_SYNTAX_ERROR(false),
    MISSING_ROOT_ELEMENT(false),
    MISSING_ID(true),
    MISSING_VERSION(true),
    MISSING_METADATA(false),
    MISSING_CONTENT(false),
    UNKNOWN_DOMAIN(false),
    MISSING_CONTENT_TYPE(false),
    UNKNOWN_CONTENT_TYPE(false),
    DUPLICATE_ID(false),
    BROKEN_LINK_TARGET(false),
    MISMATCHED_LINK(true),
    ORPHAN_FILE_IN_PACKAGE(false),
    EMPTY_PACKAGE(false)
}

/**
 * مشكلة واحدة تم رصدها أثناء الفحص.
 *
 * @param fileName اسم الملف داخل الحزمة (أو اسم الملف نفسه لملف .dlof منفرد)
 * @param line رقم السطر في الملف الأصلي إن كان متوفراً (من SAX Locator)، وإلا -1
 * @param elementId قيمة id للعنصر الجذر إن كانت معروفة وقت رصد المشكلة
 */
data class DlofIssue(
    val severity: Severity,
    val code: IssueCode,
    val message: String,
    val fileName: String,
    val line: Int = -1,
    val elementId: String? = null
) {
    val autoFixable: Boolean get() = code.autoFixable
}

/** نتيجة فحص ملف .dlof واحد. */
data class SingleFileResult(
    val fileName: String,
    val issues: List<DlofIssue>,
    val id: String?,
    val version: String?,
    val domain: String?,
    val contentType: String?,
    val nextId: String?,
    val previousId: String?,
    val hasLoopLinks: Boolean
)

/** نتيجة فحص كاملة (ملف منفرد أو حزمة .dlofpkg بعدة ملفات). */
data class ValidationReport(
    val fileResults: List<SingleFileResult>,
    val crossFileIssues: List<DlofIssue>
) {
    val allIssues: List<DlofIssue>
        get() = (fileResults.flatMap { it.issues } + crossFileIssues)
            .sortedWith(compareByDescending<DlofIssue> { it.severity == Severity.ERROR }
                .thenByDescending { it.severity == Severity.WARNING })

    val errorCount: Int get() = allIssues.count { it.severity == Severity.ERROR }
    val warningCount: Int get() = allIssues.count { it.severity == Severity.WARNING }
    val hasAutoFixableIssues: Boolean get() = allIssues.any { it.autoFixable }
}
