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
 * محرك ذكي محلي بالكامل (لا يتصل بأي خادم أو نموذج خارجي - كل المنطق قواعد
 * حتمية تعمل على الجهاز). له مهمتان:
 *
 * 1) [explainIssue] يحوّل كل مشكلة مكتشفة إلى شرح أوسع بلغة طبيعية: ما سببها،
 *    ولماذا تهم، وما الذي سيفعله الإصلاح الذكي حيالها إن أمكن.
 *
 * 2) [smartFix] لا يكتفي بإصلاحات آمنة بسيطة (كما يفعل [DlofAutoFixer])، بل
 *    يعيد بناء ترتيب "الحلقة" (documentLoop) بالكامل: يجمع كل قرائن الربط
 *    الصالحة من next/previous عبر كل ملفات الحزمة، يحل التعارضات بينها، يَخيط
 *    السلاسل الجزئية المتقطعة (بسبب روابط مكسورة) في سلسلة واحدة متماسكة،
 *    ويستخدم ترتيب أسماء الملفات (page01, page02, ...) كدليل احتياطي للملفات
 *    التي لا يوجد لها أي دليل ربط إطلاقاً - ثم يعيد كتابة next/previous لكل
 *    ملف بحيث تُغلق الحلقة بشكل متّسق تماماً.
 */
object DlofSmartEngine {

    /** شرح واحد ناتج عن قرار اتخذه المحرك أثناء إعادة البناء. */
    data class SmartExplanation(val fileName: String?, val message: String)

    /** نتيجة الإصلاح الذكي: محتوى الملفات بعد التعديل + شرح كل قرار + الترتيب النهائي (بأسماء الملفات). */
    data class SmartFixResult(
        val fixedEntries: Map<String, String>,
        val explanations: List<SmartExplanation>,
        val finalFileOrder: List<String>
    )

    // ------------------------------------------------------------------
    // 1) شرح المشاكل بلغة طبيعية أوضح
    // ------------------------------------------------------------------

    /** يعيد فقرة شرح أوضح وأشمل من [DlofIssue.message] المختصر، بلغة طبيعية. */
    fun explainIssue(issue: DlofIssue): String {
        val base = issue.message
        val why = when (issue.code) {
            IssueCode.XML_SYNTAX_ERROR ->
                "الملف ليس XML صالحاً (وسم غير مغلق، أو رمز غريب، أو تعشيش خاطئ)، لذلك تعذّر حتى قراءة بنيته. لا يمكن للمحرك الذكي إصلاح هذا تلقائياً لأنه قد يغيّر معنى المحتوى؛ يلزم تصحيح يدوي في محرر نصوص."
            IssueCode.MISSING_ROOT_ELEMENT ->
                "أي ملف dlof يجب أن يبدأ بعنصر جذر <documentLoop>. غيابه يعني أن الملف قد يكون تالفاً أو من صيغة مختلفة تماماً."
            IssueCode.MISSING_ID ->
                "كل ملف في الحلقة يحتاج معرّفاً فريداً (id) حتى تستطيع ملفات next/previous الأخرى الإشارة إليه. سيولّد الإصلاح التلقائي معرّفاً جديداً بأمان."
            IssueCode.MISSING_VERSION ->
                "سمة version تسمح للقارئ بمعرفة أي نسخة من صيغة dlof يجب استخدامها لتفسير الملف. سيضاف الإصدار الافتراضي 1.0 تلقائياً."
            IssueCode.MISSING_METADATA ->
                "عنصر <metadata> يحمل معلومات أساسية عن الملف (المجال، النوع...) يعتمد عليها القارئ والفاحص. غيابه يمنع التحقق من صحة بقية الحقول."
            IssueCode.MISSING_CONTENT ->
                "عنصر <content> هو المحتوى الفعلي الذي سيُعرض للمستخدم. ملف بلا محتوى هو حلقة فارغة عملياً."
            IssueCode.UNKNOWN_DOMAIN ->
                "قيمة <domain> يجب أن تكون واحدة من المجالات المعروفة (${DlofRules.ALLOWED_DOMAINS.joinToString()})، وإلا لن يعرف القارئ كيف يصنّف هذا المحتوى."
            IssueCode.MISSING_CONTENT_TYPE ->
                "سمة type على <content> تحدد شكل العرض المتوقع (سؤال وجواب، فصل كتاب...). بدونها لا يعرف القارئ كيف يرسم المحتوى."
            IssueCode.UNKNOWN_CONTENT_TYPE ->
                "قيمة type يجب أن تكون من الأنواع المعروفة (${DlofRules.ALLOWED_CONTENT_TYPES.joinToString()})."
            IssueCode.DUPLICATE_ID ->
                "معرّفان متطابقان في نفس الحزمة يجعلان أي إشارة next/previous إليهما غامضة: أي الملفين المقصود؟ الإصلاح الذكي يولّد معرّفاً جديداً لأحد الملفين المكررين تلقائياً، ثم يعيد ربطه بمكانه الصحيح في الحلقة."
            IssueCode.BROKEN_LINK_TARGET ->
                "يشير next أو previous إلى معرّف غير موجود في الحزمة، غالباً بسبب تعديل يدوي أو حذف ملف. الإصلاح البسيط لا يعرف بمن يستبدل الرابط، لكن الإصلاح الذكي يحاول استنتاج مكان هذا الملف الصحيح في الحلقة من بقية الأدلة (الروابط الأخرى الصالحة وأسماء الملفات) ثم يعيد كتابة الرابط."
            IssueCode.MISMATCHED_LINK ->
                "next في ملف يشير إلى ملف آخر، لكن previous في ذلك الملف الآخر لا يشير رجوعاً لنفس الملف. هذا يكسر التنقل ثنائي الاتجاه داخل الحلقة. سيُصحَّح previous ليطابق next."
            IssueCode.ORPHAN_FILE_IN_PACKAGE ->
                "هذا الملف موجود داخل الحزمة لكنه غير متصل بأي ملف آخر إطلاقاً (لا next ولا previous، ولا يشير إليه أحد)، فهو غير قابل للوصول أثناء التصفح. سيحاول الإصلاح الذكي إدراجه في مكانه المرجّح ضمن ترتيب الحلقة اعتماداً على اسم الملف."
            IssueCode.EMPTY_PACKAGE ->
                "لم يُعثر على أي ملف .dlof داخل الأرشيف، فلا يوجد ما يُفحص أو يُصحَّح."
        }
        return "$base\n$why"
    }

    // ------------------------------------------------------------------
    // 2) إعادة بناء ترتيب الحلقة بالكامل
    // ------------------------------------------------------------------

    private data class Edge(val from: String, val to: String, val confidence: Int)

    /** مقارنة "طبيعية" لأسماء الملفات تراعي الأرقام (page2 قبل page10). */
    private val naturalFileNameComparator = Comparator<String> { a, b ->
        val ax = splitNatural(a)
        val bx = splitNatural(b)
        val n = minOf(ax.size, bx.size)
        for (i in 0 until n) {
            val (aTok, aIsNum) = ax[i]
            val (bTok, bIsNum) = bx[i]
            val cmp = if (aIsNum && bIsNum) {
                (aTok.toLongOrNull() ?: 0L).compareTo(bTok.toLongOrNull() ?: 0L)
            } else {
                aTok.compareTo(bTok)
            }
            if (cmp != 0) return@Comparator cmp
        }
        ax.size.compareTo(bx.size)
    }

    private fun splitNatural(s: String): List<Pair<String, Boolean>> {
        val tokens = mutableListOf<Pair<String, Boolean>>()
        var i = 0
        while (i < s.length) {
            val isDigit = s[i].isDigit()
            val start = i
            while (i < s.length && s[i].isDigit() == isDigit) i++
            tokens.add(s.substring(start, i) to isDigit)
        }
        return tokens
    }

    /**
     * يحلل الحزمة كاملة ويصلح مشاكل معقدة: يعيد بناء ترتيب الحلقة كلها من next/previous
     * (بما فيها المعرّفات المكررة والروابط المكسورة والملفات المعزولة)، وليس فقط الإصلاحات
     * البسيطة الآمنة التي يطبّقها [DlofAutoFixer].
     */
    fun smartFix(entries: Map<String, String>, report: ValidationReport): SmartFixResult {
        val explanations = mutableListOf<SmartExplanation>()

        if (report.fileResults.size <= 1) {
            // ملف واحد: لا يوجد "ترتيب حلقة" لإعادة بنائه بين ملفات متعددة.
            // نكتفي بالإصلاحات الآمنة (id/version) عبر DlofAutoFixer.
            val fixed = DlofAutoFixer.autoFix(entries, report)
            explanations.add(
                SmartExplanation(
                    null,
                    "الحزمة تحتوي على ملف واحد فقط، فلا يوجد ترتيب حلقة متعدد الملفات لإعادة بنائه. تم تطبيق الإصلاحات الآمنة الأساسية فقط (المعرّف والإصدار إن كانا ناقصين)."
                )
            )
            return SmartFixResult(fixed, explanations, entries.keys.toList())
        }

        // المرحلة 1: إصلاحات آمنة أولاً (توليد id/version الناقصين) حتى يكون لكل
        // ملف معرّف صالح تُبنى عليه إعادة الهيكلة.
        val stage1Entries = DlofAutoFixer.autoFix(entries, report)
        var results = stage1Entries.map { (name, content) -> SingleFileParser.parse(name, content) }

        // المرحلة 2: حل تعارض المعرّفات المكررة - أول ظهور للمعرّف "يملكه"،
        // وأي تكرار لاحق يحصل على معرّف جديد ويصبح "معزولاً" مؤقتاً حتى تتم
        // إعادة إدراجه في الحلقة بالاعتماد على اسم ملفه.
        val seenIds = mutableSetOf<String>()
        results = results.map { r ->
            val id = r.id
            if (id == null || id !in seenIds) {
                if (id != null) seenIds.add(id)
                r
            } else {
                val newId = "dlof-" + UUID.randomUUID().toString().take(8)
                explanations.add(
                    SmartExplanation(
                        r.fileName,
                        "المعرّف \"$id\" كان مكرراً مع ملف آخر في الحزمة، ما يجعل أي رابط يشير إليه غامضاً. تم توليد معرّف جديد \"$newId\" لهذا الملف تحديداً، ثم أُعيد ربطه في مكانه المناسب ضمن ترتيب الحلقة."
                    )
                )
                seenIds.add(newId)
                r.copy(id = newId)
            }
        }

        val idToFileName = results.mapNotNull { r -> r.id?.let { it to r.fileName } }.toMap()
        val idSet = idToFileName.keys

        // المرحلة 3: جمع كل قرائن الربط الصالحة (next له وزن أعلى لأنه العلاقة
        // الأساسية في الصيغة؛ previous يُستخدم كدليل داعم عند غياب next).
        val candidateEdges = mutableListOf<Edge>()
        for (r in results) {
            val id = r.id ?: continue
            val next = r.nextId?.takeIf { it.isNotBlank() }
            if (next != null && next in idSet && next != id) {
                candidateEdges.add(Edge(id, next, confidence = 2))
            }
            val prev = r.previousId?.takeIf { it.isNotBlank() }
            if (prev != null && prev in idSet && prev != id) {
                candidateEdges.add(Edge(prev, id, confidence = 1))
            }
        }

        // المرحلة 4: تحويل القرائن إلى دالة رياضية (كل عقدة ≤ رابط صادر واحد و
        // ≤ رابط وارد واحد) عبر اختيار أعلى القرائن ثقة عند التعارض.
        val outEdge = mutableMapOf<String, String>()
        val outConfidence = mutableMapOf<String, Int>()
        for (e in candidateEdges) {
            val current = outConfidence[e.from]
            if (current == null || e.confidence > current) {
                if (current != null && outEdge[e.from] != e.to) {
                    val droppedTarget = outEdge[e.from]
                    val fromFile = idToFileName[e.from]
                    explanations.add(
                        SmartExplanation(
                            fromFile,
                            "وُجد تعارض في وجهة next لهذا الملف بين أكثر من دليل. تم اعتماد الدليل الأقوى (next الصريح) والاستغناء عن الدليل الأضعف الذي كان يشير إلى معرّف \"${droppedTarget ?: "غير معروف"}\"."
                        )
                    )
                }
                outEdge[e.from] = e.to
                outConfidence[e.from] = e.confidence
            }
        }

        // فرض قيد "رابط وارد واحد فقط" أيضاً: إن أشارت عقدتان مختلفتان لنفس
        // الهدف، يُبقى على الرابط الأقوى فقط.
        val inConfidence = mutableMapOf<String, Int>()
        val finalOut = mutableMapOf<String, String>()
        for ((from, to) in outEdge) {
            val conf = outConfidence[from] ?: 0
            val existingConf = inConfidence[to]
            if (existingConf == null || conf > existingConf) {
                // أزل أي رابط سابق كان يستهدف نفس العقدة بثقة أقل
                val previousFrom = finalOut.entries.firstOrNull { it.value == to }?.key
                if (previousFrom != null && previousFrom != from) {
                    finalOut.remove(previousFrom)
                    explanations.add(
                        SmartExplanation(
                            idToFileName[previousFrom],
                            "كان هذا الملف يشير next إلى نفس الملف الذي يشير إليه ملف آخر بدليل أقوى، لذا أُلغي هذا الرابط المتعارض وسيُعاد ترتيب الملف حسب اسمه بدلاً من ذلك."
                        )
                    )
                }
                finalOut[from] = to
                inConfidence[to] = conf
            }
        }

        // المرحلة 5: استخراج السلاسل (chains) من الرسم الناتج بالمشي فوق finalOut.
        val inEdgeOf = finalOut.entries.associate { (k, v) -> v to k }
        val visited = mutableSetOf<String>()
        val chains = mutableListOf<List<String>>()

        // ابدأ من العقد التي لا رابط وارد لها (رؤوس سلاسل خطية واضحة)
        val heads = idSet.filter { it !in inEdgeOf.keys }.sortedWith(
            compareBy(naturalFileNameComparator) { idToFileName[it] ?: it }
        )
        for (head in heads) {
            if (head in visited) continue
            val chain = mutableListOf(head)
            visited.add(head)
            var cur = head
            while (true) {
                val next = finalOut[cur] ?: break
                if (next in visited) break // يمنع الدوران اللانهائي إن وُجدت حلقة جزئية متصلة برأس
                chain.add(next)
                visited.add(next)
                cur = next
            }
            chains.add(chain)
        }

        // ما تبقى (حلقات فرعية مغلقة بالكامل بلا رأس واضح، أو عقد بلا أي رابط إطلاقاً)
        val remaining = idSet.filter { it !in visited }.sortedWith(
            compareBy(naturalFileNameComparator) { idToFileName[it] ?: it }
        )
        for (id in remaining) {
            if (id in visited) continue
            val chain = mutableListOf(id)
            visited.add(id)
            var cur = id
            while (true) {
                val next = finalOut[cur] ?: break
                if (next in visited) break
                chain.add(next)
                visited.add(next)
                cur = next
            }
            chains.add(chain)
        }

        // المرحلة 6: خيط كل السلاسل الجزئية في ترتيب حلقة واحد متماسك، باستخدام
        // اسم أول ملف بكل سلسلة كدليل احتياطي لترتيب السلاسل نفسها بين بعضها.
        val orderedChains = chains.sortedWith(
            compareBy(naturalFileNameComparator) { chain -> idToFileName[chain.first()] ?: chain.first() }
        )
        val finalIdOrder = orderedChains.flatten()

        if (orderedChains.size > 1) {
            val chainDescriptions = orderedChains.joinToString(" ← ثم ← ") { chain ->
                chain.joinToString(" → ") { idToFileName[it] ?: it }
            }
            explanations.add(
                SmartExplanation(
                    null,
                    "كانت الحزمة مقسّمة إلى ${orderedChains.size} سلسلة/سلاسل منفصلة بسبب روابط مكسورة أو غائبة (بدل حلقة واحدة متصلة). تم خَيطها معاً في حلقة واحدة، بترتيب استُنتج من الروابط الصالحة ومن ترتيب أسماء الملفات كدليل احتياطي عند غياب أي رابط: $chainDescriptions"
                )
            )
        }

        // المرحلة 7: إعادة كتابة next/previous لكل ملف بحيث تُغلق الحلقة (دائرية):
        // آخر ملف يشير next إلى أول ملف، وأول ملف يشير previous إلى آخر ملف.
        val n = finalIdOrder.size
        val fixedEntries = linkedMapOf<String, String>()
        for ((index, id) in finalIdOrder.withIndex()) {
            val fileName = idToFileName[id] ?: continue
            val content = stage1Entries[fileName] ?: entries[fileName] ?: continue
            val newNext = finalIdOrder[(index + 1) % n]
            val newPrevious = finalIdOrder[(index - 1 + n) % n]

            val original = results.first { it.fileName == fileName }
            val idChanged = original.id != id
            val nextChanged = original.nextId != newNext
            val prevChanged = original.previousId != newPrevious

            if (!idChanged && !nextChanged && !prevChanged) {
                fixedEntries[fileName] = content
                continue
            }

            fixedEntries[fileName] = try {
                rewriteLoopFields(content, id, newNext, newPrevious)
            } catch (e: Exception) {
                content
            }

            if (nextChanged || prevChanged) {
                explanations.add(
                    SmartExplanation(
                        fileName,
                        "أُعيد ترتيب هذا الملف ضمن الحلقة: previous الآن = \"${idToFileName[newPrevious] ?: newPrevious}\"، next الآن = \"${idToFileName[newNext] ?: newNext}\"."
                    )
                )
            }
        }
        // أي ملفات لم تُعالج (خارج finalIdOrder لسبب ما، احتياط دفاعي) تُعاد كما هي
        for ((name, content) in stage1Entries) {
            if (name !in fixedEntries) fixedEntries[name] = content
        }

        if (explanations.none { it.message.contains("أُعيد ترتيب") || it.message.contains("سلسلة") }) {
            explanations.add(SmartExplanation(null, "بنية الحلقة كانت متّسقة أصلاً؛ لم يلزم تغيير ترتيب next/previous."))
        }

        val finalFileNames = finalIdOrder.mapNotNull { idToFileName[it] }
        return SmartFixResult(fixedEntries, explanations, finalFileNames)
    }

    /** يعيد كتابة id و next و previous لعنصر documentLoop الجذر بقيم محددة صراحة. */
    private fun rewriteLoopFields(xmlContent: String, id: String, nextId: String, previousId: String): String {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val builder = factory.newDocumentBuilder()
        val doc: Document = builder.parse(ByteArrayInputStream(xmlContent.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement ?: return xmlContent

        root.setAttribute(DlofRules.ID_ATTR, id)

        val loopLinks = findOrCreateChild(doc, root, DlofRules.LOOP_LINKS_TAG)
        setChildText(doc, loopLinks, DlofRules.NEXT_TAG, nextId)
        setChildText(doc, loopLinks, DlofRules.PREVIOUS_TAG, previousId)

        return serialize(doc)
    }

    private fun setChildText(doc: Document, parent: Element, tagName: String, value: String) {
        val el = findOrCreateChild(doc, parent, tagName)
        while (el.firstChild != null) el.removeChild(el.firstChild)
        el.appendChild(doc.createTextNode(value))
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
