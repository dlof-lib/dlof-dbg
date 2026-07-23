package org.dlof.dbg.validator

import java.io.InputStream
import java.util.zip.ZipInputStream

object PackageValidator {

    /**
     * يفحص محتوى ملف واحد بناءً على امتداده. إن كان .dlofpkg (أرشيف zip) يُستخرج
     * ويُفحص كل ملف .dlof بداخله بالإضافة لفحص تماسك روابط next/previous بين الملفات.
     */
    fun validate(fileName: String, input: InputStream): ValidationReport {
        val extension = fileName.substringAfterLast('.', "").lowercase()

        val entries: Map<String, String> = if (extension in DlofRules.PACKAGE_EXTENSIONS) {
            readZipDlofEntries(input)
        } else {
            mapOf(fileName to input.readBytes().toString(Charsets.UTF_8))
        }

        if (entries.isEmpty()) {
            return ValidationReport(
                fileResults = emptyList(),
                crossFileIssues = listOf(
                    DlofIssue(
                        Severity.ERROR, IssueCode.EMPTY_PACKAGE,
                        "لم يتم العثور على أي ملف .dlof داخل الحزمة.", fileName
                    )
                )
            )
        }

        val fileResults = entries.map { (name, content) -> SingleFileParser.parse(name, content) }
        val crossIssues = crossValidate(fileResults)

        return ValidationReport(fileResults, crossIssues)
    }

    private fun readZipDlofEntries(input: InputStream): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.substringAfterLast('.', "").lowercase() in DlofRules.DLOF_EXTENSIONS) {
                    result[name] = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return result
    }

    private fun crossValidate(results: List<SingleFileResult>): List<DlofIssue> {
        val issues = mutableListOf<DlofIssue>()
        if (results.size <= 1) return issues

        // فحص المعرّفات المكررة
        val byId = mutableMapOf<String, MutableList<String>>()
        for (r in results) {
            val id = r.id ?: continue
            byId.getOrPut(id) { mutableListOf() }.add(r.fileName)
        }
        for ((id, files) in byId) {
            if (files.size > 1) {
                issues.add(
                    DlofIssue(
                        Severity.ERROR, IssueCode.DUPLICATE_ID,
                        "المعرّف \"$id\" مكرر في ${files.size} ملفات: ${files.joinToString()}.",
                        fileName = files.first(), elementId = id
                    )
                )
            }
        }

        val idToFile = results.mapNotNull { r -> r.id?.let { it to r } }.toMap()
        val linkedFiles = mutableSetOf<String>()

        for (r in results) {
            val id = r.id ?: continue

            if (!r.nextId.isNullOrEmpty()) {
                linkedFiles.add(r.fileName)
                val target = idToFile[r.nextId]
                if (target == null) {
                    issues.add(
                        DlofIssue(
                            Severity.ERROR, IssueCode.BROKEN_LINK_TARGET,
                            "next يشير إلى \"${r.nextId}\" لكن لا يوجد ملف بهذا المعرّف في الحزمة.",
                            r.fileName, elementId = id
                        )
                    )
                } else {
                    linkedFiles.add(target.fileName)
                    if (target.previousId != id) {
                        issues.add(
                            DlofIssue(
                                Severity.WARNING, IssueCode.MISMATCHED_LINK,
                                "رابط غير متطابق: \"${r.fileName}\" (id=$id) يشير next إلى \"${r.nextId}\"، لكن previous في \"${target.fileName}\" يساوي \"${target.previousId ?: "فارغ"}\" وليس \"$id\".",
                                target.fileName, elementId = target.id
                            )
                        )
                    }
                }
            }

            if (!r.previousId.isNullOrEmpty()) {
                linkedFiles.add(r.fileName)
                val target = idToFile[r.previousId]
                if (target == null) {
                    issues.add(
                        DlofIssue(
                            Severity.ERROR, IssueCode.BROKEN_LINK_TARGET,
                            "previous يشير إلى \"${r.previousId}\" لكن لا يوجد ملف بهذا المعرّف في الحزمة.",
                            r.fileName, elementId = id
                        )
                    )
                } else {
                    linkedFiles.add(target.fileName)
                }
            }
        }

        // ملفات معزولة: لا next ولا previous ضمن حزمة متعددة الملفات، ولا يشير إليها أحد
        for (r in results) {
            val hasAnyLink = !r.nextId.isNullOrEmpty() || !r.previousId.isNullOrEmpty()
            if (!hasAnyLink && r.fileName !in linkedFiles) {
                issues.add(
                    DlofIssue(
                        Severity.WARNING, IssueCode.ORPHAN_FILE_IN_PACKAGE,
                        "الملف لا يرتبط بأي ملف آخر في الحزمة (لا next ولا previous، ولا يُشار إليه من ملف آخر).",
                        r.fileName, elementId = r.id
                    )
                )
            }
        }

        return issues
    }
}
