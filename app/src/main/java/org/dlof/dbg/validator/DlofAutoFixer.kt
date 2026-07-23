package org.dlof.dbg.validator

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * يطبّق فقط الإصلاحات "الآمنة" المذكورة في [IssueCode.autoFixable]:
 * - إضافة version="1.0" حين تكون مفقودة.
 * - توليد id جديد (UUID) حين يكون مفقوداً أو فارغاً.
 * - تصحيح <previous> في الملف الهدف ليطابق <next> في الملف المصدر عند عدم التطابق.
 *
 * ملاحظة: إعادة كتابة الملف عبر DOM/Transformer قد تُغيّر تنسيق المسافات
 * البادئة الأصلي قليلاً، لكنها تحافظ على كل البيانات والعناصر.
 */
object DlofAutoFixer {

    /** يصحّح كل ملفات الحزمة بناءً على نتائج الفحص، ويعيد خريطة (اسم الملف -> المحتوى المصحح). */
    fun autoFix(entries: Map<String, String>, report: ValidationReport): Map<String, String> {
        val resultsByName = report.fileResults.associateBy { it.fileName }
        val idToResult = report.fileResults.mapNotNull { r -> r.id?.let { it to r } }.toMap()

        // العلاقات الصحيحة المطلوب فرضها على previous بناءً على next الصحيح من كل ملف
        val requiredPrevious = mutableMapOf<String, String>() // fileName -> previousId المطلوب
        for (r in report.fileResults) {
            val id = r.id ?: continue
            val nextId = r.nextId?.takeIf { it.isNotEmpty() } ?: continue
            val target = idToResult[nextId] ?: continue
            if (target.previousId != id) {
                requiredPrevious[target.fileName] = id
            }
        }

        val fixed = linkedMapOf<String, String>()
        for ((name, content) in entries) {
            val result = resultsByName[name]
            val needsVersionFix = result?.version == null
            val needsIdFix = result?.id == null
            val requiredPrev = requiredPrevious[name]

            if (!needsVersionFix && !needsIdFix && requiredPrev == null) {
                fixed[name] = content
                continue
            }

            fixed[name] = try {
                fixSingleDocument(content, needsVersionFix, needsIdFix, requiredPrev)
            } catch (e: Exception) {
                // إن فشل إعادة الكتابة (مثلاً XML غير صالح أصلاً)، أبقِ المحتوى كما هو
                content
            }
        }
        return fixed
    }

    private fun fixSingleDocument(
        xmlContent: String,
        fixVersion: Boolean,
        fixId: Boolean,
        requiredPreviousId: String?
    ): String {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val builder = factory.newDocumentBuilder()
        val doc: Document = builder.parse(ByteArrayInputStream(xmlContent.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement ?: return xmlContent

        if (fixVersion && root.getAttribute(DlofRules.VERSION_ATTR).isBlank()) {
            root.setAttribute(DlofRules.VERSION_ATTR, DlofRules.DEFAULT_VERSION)
        }
        if (fixId && root.getAttribute(DlofRules.ID_ATTR).isBlank()) {
            root.setAttribute(DlofRules.ID_ATTR, "dlof-" + UUID.randomUUID().toString().take(8))
        }
        if (requiredPreviousId != null) {
            val loopLinks = findOrCreateChild(doc, root, DlofRules.LOOP_LINKS_TAG)
            val previous = findOrCreateChild(doc, loopLinks, DlofRules.PREVIOUS_TAG)
            // إفراغ أي نص سابق ثم وضع القيمة الصحيحة
            while (previous.firstChild != null) previous.removeChild(previous.firstChild)
            previous.appendChild(doc.createTextNode(requiredPreviousId))
        }

        return serialize(doc)
    }

    private fun findOrCreateChild(doc: Document, parent: Element, tagName: String): Element {
        val children = parent.getElementsByTagName(tagName)
        for (i in 0 until children.length) {
            val el = children.item(i)
            if (el is Element && el.parentNode === parent) return el
        }
        val created = doc.createElement(tagName)
        parent.appendChild(created)
        return created
    }

    private fun serialize(doc: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }
}
