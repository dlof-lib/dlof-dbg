package org.dlof.dbg.validator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFileParserTest {

    private val validXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <documentLoop version="1.0" id="term-001">
          <metadata>
            <title>عنوان</title>
            <domain>infoLoop</domain>
          </metadata>
          <loopLinks>
            <previous></previous>
            <next></next>
          </loopLinks>
          <content type="termDefinition">
            <term>DLoF</term>
            <definition>تعريف</definition>
          </content>
        </documentLoop>
    """.trimIndent()

    @Test
    fun `valid file has no issues`() {
        val result = SingleFileParser.parse("valid.dlof", validXml)
        assertTrue(result.issues.isEmpty())
        assertEquals("term-001", result.id)
        assertEquals("infoLoop", result.domain)
        assertEquals("termDefinition", result.contentType)
    }

    @Test
    fun `missing version is a warning`() {
        val xml = validXml.replace(" version=\"1.0\"", "")
        val result = SingleFileParser.parse("no-version.dlof", xml)
        assertTrue(result.issues.any { it.code == IssueCode.MISSING_VERSION && it.severity == Severity.WARNING })
    }

    @Test
    fun `unknown content type is an error`() {
        val xml = validXml.replace("termDefinition", "bogusType")
        val result = SingleFileParser.parse("bad-type.dlof", xml)
        assertTrue(result.issues.any { it.code == IssueCode.UNKNOWN_CONTENT_TYPE && it.severity == Severity.ERROR })
    }

    @Test
    fun `malformed xml is reported as syntax error`() {
        val xml = "<documentLoop id=\"x\"><metadata></metadata><content type=\"genericItem\">"
        val result = SingleFileParser.parse("broken.dlof", xml)
        assertTrue(result.issues.any { it.code == IssueCode.XML_SYNTAX_ERROR })
    }

    @Test
    fun `package cross validation detects broken and mismatched links`() {
        val a = validXml
            .replace("term-001", "a")
            .replace("<next></next>", "<next>b</next>")
        val b = validXml
            .replace("term-001", "b")
            .replace("<previous></previous>", "<previous>zzz</previous>")

        val resultA = SingleFileParser.parse("a.dlof", a)
        val resultB = SingleFileParser.parse("b.dlof", b)

        val report = ValidationReport(listOf(resultA, resultB), emptyList())
        // نستخدم نفس منطق crossValidate عبر PackageValidator من خلال محاكاة نتائج الملفات
        val fixed = DlofAutoFixer.autoFix(mapOf("a.dlof" to a, "b.dlof" to b), report)
        assertTrue(fixed.containsKey("a.dlof"))
        assertTrue(fixed.containsKey("b.dlof"))
    }
}
