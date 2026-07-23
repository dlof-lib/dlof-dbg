package org.dlof.dbg.validator

/**
 * القواعد المعتمدة لصيغة DLoF كما هي موثّقة علناً (بنية documentLoop / metadata /
 * loopLinks / content، والمجالات وأنواع المحتوى السبعة). لا يعتمد هذا الملف على
 * أي مخطط XSD خارجي - القواعد مكتوبة يدوياً هنا. إن تغيّر المخطط الرسمي مستقبلاً
 * يكفي تحديث القيم أدناه.
 */
object DlofRules {

    const val ROOT_TAG = "documentLoop"
    const val METADATA_TAG = "metadata"
    const val DOMAIN_TAG = "domain"
    const val LOOP_LINKS_TAG = "loopLinks"
    const val NEXT_TAG = "next"
    const val PREVIOUS_TAG = "previous"
    const val CONTENT_TAG = "content"
    const val CONTENT_TYPE_ATTR = "type"
    const val ID_ATTR = "id"
    const val VERSION_ATTR = "version"
    const val REF_ATTR = "ref"
    const val LOOP_ROOT_ATTR = "loopRoot"

    val ALLOWED_DOMAINS: Set<String> = setOf(
        "education", "book", "infoApp", "infoLoop", "custom", "series", "comic", "characters"
    )

    val ALLOWED_CONTENT_TYPES: Set<String> = setOf(
        "genericItem", "qaItem", "bookChapter", "termDefinition",
        "infoExplain", "episodeItem", "comicPanel"
    )

    const val DEFAULT_VERSION = "1.0"

    val DLOF_EXTENSIONS = setOf("dlof")
    val PACKAGE_EXTENSIONS = setOf("dlofpkg")
}
