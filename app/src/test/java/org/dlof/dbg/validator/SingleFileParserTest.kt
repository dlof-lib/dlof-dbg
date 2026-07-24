package org.dlof.dbg.validator

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * يفحص محتوى ملف .dlof واحد (نص XML) ويعيد [SingleFileResult] يضم كل المشاكل
 * المكتشفة داخل هذا الملف بمعزل عن بقية ملفات الحزمة. فحوصات الروابط المتقاطعة
 * بين الملفات (next/previous) تتم لاحقاً في [PackageValidator].
 */
object SingleFileParser {

    fun parse(fileName: String, xmlContent: String): SingleFileResult {
        val handler = Handler(fileName)
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val parser = factory.newSAXParser()
            parser.parse(InputSource(StringReader(xmlContent)), handler)
        } catch (e: SAXException) {
            handler.issues.add(
                DlofIssue(
                    severity = Severity.ERROR,
                    code = IssueCode.XML_SYNTAX_ERROR,
                    message = "خطأ في بنية XML: ${e.message ?: "تنسيق غير صالح"}",
                    fileName = fileName,
                    line = handler.lastKnownLine
                )
            )
        } catch (e: Exception) {
            handler.issues.add(
                DlofIssue(
                    severity = Severity.ERROR,
                    code = IssueCode.XML_SYNTAX_ERROR,
                    message = "تعذّرت قراءة الملف كـ XML: ${e.message ?: e.javaClass.simpleName}",
                    fileName = fileName,
                    line = -1
                )
            )
        }
        return handler.buildResult()
    }

    private class Handler(private val fileName: String) : DefaultHandler() {
        val issues = mutableListOf<DlofIssue>()
        private var locator: Locator? = null
        var lastKnownLine: Int = -1
            private set

        private var depth = 0
        private var sawRoot = false
        private var sawMetadata = false
        private var sawContent = false
        private var sawLoopLinks = false
        private var rootId: String? = null
        private var rootVersion: String? = null
        private var domain: String? = null
        private var contentType: String? = null
        private var nextId: String? = null
        private var previousId: String? = null

        // مسار العناصر الحالي لمعرفة أين نحن (metadata/domain, loopLinks/next, ...)
        private val stack = ArrayDeque<String>()
        private val textBuffer = StringBuilder()

        override fun setDocumentLocator(l: Locator) {
            locator = l
        }

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            lastKnownLine = locator?.lineNumber ?: -1
            depth++
            textBuffer.setLength(0)

            if (depth == 1) {
                sawRoot = true
                if (qName != DlofRules.ROOT_TAG) {
                    issues.add(
                        DlofIssue(
                            Severity.ERROR, IssueCode.MISSING_ROOT_ELEMENT,
                            "العنصر الجذر يجب أن يكون <${DlofRules.ROOT_TAG}> وليس <$qName>.",
                            fileName, lastKnownLine
                        )
                    )
                } else {
                    rootId = attributes.getValue(DlofRules.ID_ATTR)?.trim()?.ifEmpty { null }
                    rootVersion = attributes.getValue(DlofRules.VERSION_ATTR)?.trim()?.ifEmpty { null }
                    if (rootId == null) {
                        issues.add(
                            DlofIssue(
                                Severity.ERROR, IssueCode.MISSING_ID,
                                "خاصية id مفقودة أو فارغة على العنصر الجذر <${DlofRules.ROOT_TAG}>.",
                                fileName, lastKnownLine
                            )
                        )
                    }
                    // ✅ هذا الجزء موجود بالفعل ويجب أن يعمل
                    if (rootVersion == null) {
                        issues.add(
                            DlofIssue(
                                Severity.WARNING, IssueCode.MISSING_VERSION,
                                "خاصية version مفقودة على العنصر الجذر. سيُستخدم الافتراضي ${DlofRules.DEFAULT_VERSION} عند التصحيح التلقائي.",
                                fileName, lastKnownLine, rootId
                            )
                        )
                    }
                }
            }

            when (qName) {
                DlofRules.METADATA_TAG -> if (depth == 2) sawMetadata = true
                DlofRules.LOOP_LINKS_TAG -> if (depth == 2) sawLoopLinks = true
                DlofRules.CONTENT_TAG -> if (depth == 2) {
                    sawContent = true
                    contentType = attributes.getValue(DlofRules.CONTENT_TYPE_ATTR)?.trim()?.ifEmpty { null }
                    if (contentType == null) {
                        issues.add(
                            DlofIssue(
                                Severity.ERROR, IssueCode.MISSING_CONTENT_TYPE,
                                "خاصية type مفقودة على <${DlofRules.CONTENT_TAG}>.",
                                fileName, lastKnownLine, rootId
                            )
                        )
                    } else if (contentType !in DlofRules.ALLOWED_CONTENT_TYPES) {
                        issues.add(
                            DlofIssue(
                                Severity.ERROR, IssueCode.UNKNOWN_CONTENT_TYPE,
                                "نوع محتوى غير معروف: \"$contentType\". الأنواع المعتمدة: ${DlofRules.ALLOWED_CONTENT_TYPES.joinToString()}.",
                                fileName, lastKnownLine, rootId
                            )
                        )
                    }
                }
            }

            stack.addLast(qName)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            textBuffer.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val text = textBuffer.toString().trim()
            val parent = if (stack.size >= 2) stack.elementAt(stack.size - 2) else null

            if (qName == DlofRules.DOMAIN_TAG && parent == DlofRules.METADATA_TAG) {
                domain = text.ifEmpty { null }
            }
            if (qName == DlofRules.NEXT_TAG && parent == DlofRules.LOOP_LINKS_TAG) {
                nextId = text.ifEmpty { null }
            }
            if (qName == DlofRules.PREVIOUS_TAG && parent == DlofRules.LOOP_LINKS_TAG) {
                previousId = text.ifEmpty { null }
            }

            if (stack.isNotEmpty()) stack.removeLast()
            textBuffer.setLength(0)
            depth--
        }

        override fun endDocument() {
            if (!sawRoot) {
                issues.add(
                    DlofIssue(
                        Severity.ERROR, IssueCode.MISSING_ROOT_ELEMENT,
                        "الملف فارغ أو لا يحوي أي عنصر جذر.", fileName, -1
                    )
                )
                return
            }
            if (!sawMetadata) {
                issues.add(
                    DlofIssue(
                        Severity.ERROR, IssueCode.MISSING_METADATA,
                        "العنصر <${DlofRules.METADATA_TAG}> مفقود.", fileName, -1, rootId
                    )
                )
            } else if (domain == null) {
                issues.add(
                    DlofIssue(
                        Severity.WARNING, IssueCode.UNKNOWN_DOMAIN,
                        "لم يُحدَّد <${DlofRules.DOMAIN_TAG}> داخل metadata.", fileName, -1, rootId
                    )
                )
            } else if (domain !in DlofRules.ALLOWED_DOMAINS) {
                issues.add(
                    DlofIssue(
                        Severity.WARNING, IssueCode.UNKNOWN_DOMAIN,
                        "قيمة domain غير معروفة: \"$domain\". القيم المعتمدة: ${DlofRules.ALLOWED_DOMAINS.joinToString()}.",
                        fileName, -1, rootId
                    )
                )
            }
            if (!sawContent) {
                issues.add(
                    DlofIssue(
                        Severity.ERROR, IssueCode.MISSING_CONTENT,
                        "العنصر <${DlofRules.CONTENT_TAG}> مفقود.", fileName, -1, rootId
                    )
                )
            }
        }

        fun buildResult(): SingleFileResult = SingleFileResult(
            fileName = fileName,
            issues = issues,
            id = rootId,
            version = rootVersion,
            domain = domain,
            contentType = contentType,
            nextId = nextId,
            previousId = previousId,
            hasLoopLinks = sawLoopLinks
        )
    }
}
