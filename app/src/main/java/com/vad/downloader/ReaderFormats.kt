package com.vad.downloader

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Xml
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

// ---------------------------------------------------------------------------------------------
// Источники страниц (комиксы, PDF)
// ---------------------------------------------------------------------------------------------

/** Набор страниц-картинок. Любой формат (cbz, cbr, cb7, pdf, папка) приводится к этому виду. */
internal abstract class Pages {
    abstract val count: Int
    abstract fun bytes(i: Int): ByteArray
    open fun bitmap(i: Int, tw: Int): Bitmap? = ReaderFormats.decode(bytes(i), tw)
    open fun close() {}
}

internal class ZipPages(file: File) : Pages() {
    private val zip: ZipFile = try {
        ZipFile(file)
    } catch (e: Exception) {
        ZipFile(file, Charsets.ISO_8859_1) // имена не в UTF-8
    }
    private val names: List<String> = zip.entries().toList()
        .filter { !it.isDirectory && ReaderFormats.isImage(it.name) }
        .map { it.name }
        .sortedWith { a, b -> ReaderFormats.natural(a, b) }

    override val count: Int get() = names.size
    override fun bytes(i: Int): ByteArray =
        zip.getInputStream(zip.getEntry(names[i])).use { it.readBytes() }

    override fun close() {
        try { zip.close() } catch (_: Throwable) {}
    }
}

internal class RarPages(file: File) : Pages() {
    private val arc = com.github.junrar.Archive(file)
    private val heads: List<com.github.junrar.rarfile.FileHeader> = arc.fileHeaders
        .filter { !it.isDirectory && ReaderFormats.isImage(it.fileName) }
        .sortedWith { a, b -> ReaderFormats.natural(a.fileName, b.fileName) }

    override val count: Int get() = heads.size
    override fun bytes(i: Int): ByteArray {
        val out = ByteArrayOutputStream()
        synchronized(arc) { arc.extractFile(heads[i], out) }
        return out.toByteArray()
    }

    override fun close() {
        try { arc.close() } catch (_: Throwable) {}
    }
}

internal class SevenPages(file: File) : Pages() {
    private val sz = org.apache.commons.compress.archivers.sevenz.SevenZFile(file)
    private val entries = sz.entries
        .filter { !it.isDirectory && ReaderFormats.isImage(it.name) }
        .sortedWith { a, b -> ReaderFormats.natural(a.name, b.name) }

    override val count: Int get() = entries.size
    override fun bytes(i: Int): ByteArray =
        synchronized(sz) { sz.getInputStream(entries[i]).use { it.readBytes() } }

    override fun close() {
        try { sz.close() } catch (_: Throwable) {}
    }
}

internal class FilePages(private val files: List<File>) : Pages() {
    override val count: Int get() = files.size
    override fun bytes(i: Int): ByteArray = files[i].readBytes()
}

internal class UriPages(private val resolver: ContentResolver, private val uris: List<Uri>) : Pages() {
    override val count: Int get() = uris.size
    override fun bytes(i: Int): ByteArray =
        resolver.openInputStream(uris[i])!!.use { it.readBytes() }
}

/** PDF показывается постранично как картинки (PdfRenderer умеет отдавать только растр). */
internal class PdfPages(file: File) : Pages() {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val r = PdfRenderer(pfd)
    private val lock = Any()

    override val count: Int get() = r.pageCount
    override fun bytes(i: Int): ByteArray = ByteArray(0)

    override fun bitmap(i: Int, tw: Int): Bitmap? = synchronized(lock) {
        val p = r.openPage(i)
        try {
            val sc = tw.toFloat() / p.width
            var w = (p.width * sc).toInt().coerceAtLeast(1)
            var h = (p.height * sc).toInt().coerceAtLeast(1)
            while (w.toLong() * h > 12_000_000L) { w = w * 9 / 10; h = h * 9 / 10 }
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bm.eraseColor(Color.WHITE)
            p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bm
        } finally {
            p.close()
        }
    }

    override fun close() {
        try { r.close() } catch (_: Throwable) {}
        try { pfd.close() } catch (_: Throwable) {}
    }
}

internal class EpubBook(val zip: ZipFile, val chapters: List<String>, val title: String)

// ---------------------------------------------------------------------------------------------
// Преобразование документов в HTML
// ---------------------------------------------------------------------------------------------

internal object ReaderFormats {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")

    fun isImage(name: String): Boolean {
        val n = name.replace('\\', '/')
        val base = n.substringAfterLast('/')
        if (base.startsWith(".") || n.contains("__MACOSX/")) return false
        return base.substringAfterLast('.', "").lowercase() in imageExt
    }

    /** Естественная сортировка: page2 раньше page10. */
    fun natural(a0: String, b0: String): Int {
        val a = a0.lowercase()
        val b = b0.lowercase()
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isDigit()) ie++
                var je = j
                while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0')
                val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun decode(b: ByteArray, tw: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= tw) s *= 2
        while ((o.outWidth / s).toLong() * (o.outHeight / s) > 16_000_000L) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        return BitmapFactory.decodeByteArray(b, 0, b.size, o2)
    }

    /** UTF-8 (с BOM или без), UTF-16, а если не получилось - windows-1251 (старые русские тексты). */
    fun decodeText(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte())
            return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte())
            return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (_: CharacterCodingException) {
            String(b, Charset.forName("windows-1251"))
        }
    }

    /** Для HTML-файлов: если в <meta> указана кодировка, берём её. */
    fun decodeHtml(b: ByteArray): String {
        val head = String(b, 0, minOf(b.size, 2048), Charsets.ISO_8859_1)
        val m = Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)
        val name = m?.groupValues?.get(1)
        if (name != null && !name.equals("utf-8", true) && !name.equals("utf8", true)) {
            try {
                return String(b, Charset.forName(name))
            } catch (_: Throwable) {
            }
        }
        return decodeText(b)
    }

    fun txtToHtml(s: String): String = "<pre class=\"txt\">" + esc(s) + "</pre>"

    fun mdToHtml(s: String): String {
        val ext = listOf(TablesExtension.create())
        val parser = Parser.builder().extensions(ext).build()
        return HtmlRenderer.builder().extensions(ext).build().render(parser.parse(s))
    }

    private fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        else -> null // emf, wmf и прочее браузер не покажет
    }

    private fun dataImg(zip: ZipFile, path: String): String {
        val mime = mimeOf(path) ?: return ""
        val e = zip.getEntry(path) ?: return ""
        if (e.size > 12L * 1024 * 1024) return ""
        val b = zip.getInputStream(e).use { it.readBytes() }
        return "<img src=\"data:$mime;base64," + Base64.encodeToString(b, Base64.NO_WRAP) + "\">"
    }

    /** Склеивает относительный путь внутри архива: ("word/", "../media/a.png") -> "media/a.png". */
    fun resolve(baseDir: String, target: String): String {
        val t = target.substringBefore('#')
        if (t.startsWith("/")) return t.removePrefix("/")
        val parts = ArrayList<String>()
        (baseDir.trim('/') + "/" + t).split('/').forEach { s ->
            when (s) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(s)
            }
        }
        return parts.joinToString("/")
    }

    private fun readRels(zip: ZipFile, relsPath: String, baseDir: String): Map<String, String> {
        val e = zip.getEntry(relsPath) ?: return emptyMap()
        val res = HashMap<String, String>()
        val p = Xml.newPullParser()
        p.setInput(zip.getInputStream(e), null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.getAttributeValue(null, "Id")
                val target = p.getAttributeValue(null, "Target")
                val mode = p.getAttributeValue(null, "TargetMode")
                if (id != null && target != null && mode != "External") res[id] = resolve(baseDir, target)
            }
            ev = p.next()
        }
        return res
    }

    private fun flagOn(p: XmlPullParser, attr: String): Boolean {
        val v = p.getAttributeValue(null, attr) ?: return true
        return v != "0" && v != "false" && v != "none"
    }

    // ---- DOCX ----

    fun docxToHtml(file: File): String {
        ZipFile(file).use { zip ->
            val rels = readRels(zip, "word/_rels/document.xml.rels", "word/")
            val entry = zip.getEntry("word/document.xml") ?: throw IOException("word/document.xml")
            val out = StringBuilder()
            val para = StringBuilder()
            var style = ""
            var isList = false
            var inRun = false
            var bold = false
            var ital = false
            var under = false
            var strike = false
            var fallback = 0
            val p = Xml.newPullParser()
            p.setInput(zip.getInputStream(entry), null)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                val name = if (ev == XmlPullParser.START_TAG || ev == XmlPullParser.END_TAG) p.name else ""
                if (name == "mc:Fallback") {
                    if (ev == XmlPullParser.START_TAG) fallback++ else fallback--
                } else if (fallback > 0) {
                    // дубликат содержимого (текстовые рамки, картинки) - пропускаем
                } else if (ev == XmlPullParser.START_TAG) {
                    when (name) {
                        "w:p" -> { para.setLength(0); style = ""; isList = false }
                        "w:pStyle" -> style = p.getAttributeValue(null, "w:val") ?: ""
                        "w:numPr" -> isList = true
                        "w:r" -> { inRun = true; bold = false; ital = false; under = false; strike = false }
                        "w:b" -> if (inRun) bold = flagOn(p, "w:val")
                        "w:i" -> if (inRun) ital = flagOn(p, "w:val")
                        "w:u" -> if (inRun) under = flagOn(p, "w:val")
                        "w:strike" -> if (inRun) strike = flagOn(p, "w:val")
                        "w:t" -> {
                            var h = esc(p.nextText())
                            if (bold) h = "<b>$h</b>"
                            if (ital) h = "<i>$h</i>"
                            if (under) h = "<u>$h</u>"
                            if (strike) h = "<s>$h</s>"
                            para.append(h)
                        }
                        "w:tab" -> para.append("&emsp;")
                        "w:br", "w:cr" ->
                            para.append(if (p.getAttributeValue(null, "w:type") == "page") "<hr>" else "<br>")
                        "w:tbl" -> out.append("<table>")
                        "w:tr" -> out.append("<tr>")
                        "w:tc" -> out.append("<td>")
                        "a:blip" -> {
                            val id = p.getAttributeValue(null, "r:embed")
                            rels[id]?.let { para.append(dataImg(zip, it)) }
                        }
                        "v:imagedata" -> {
                            val id = p.getAttributeValue(null, "r:id")
                            rels[id]?.let { para.append(dataImg(zip, it)) }
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (name) {
                        "w:r" -> inRun = false
                        "w:p" -> {
                            val inner = para.toString()
                            if (inner.isNotBlank()) {
                                val s = style.lowercase()
                                val hm = Regex("^heading\\s*(\\d)").find(s)
                                when {
                                    hm != null -> {
                                        val n = hm.groupValues[1].toInt().coerceIn(1, 6)
                                        out.append("<h$n>").append(inner).append("</h$n>")
                                    }
                                    s == "title" -> out.append("<h1>").append(inner).append("</h1>")
                                    s == "subtitle" -> out.append("<h2>").append(inner).append("</h2>")
                                    isList || s.contains("list") ->
                                        out.append("<p style=\"margin-left:1.4em\">&bull;&nbsp;").append(inner).append("</p>")
                                    else -> out.append("<p>").append(inner).append("</p>")
                                }
                            }
                            para.setLength(0)
                        }
                        "w:tc" -> out.append("</td>")
                        "w:tr" -> out.append("</tr>")
                        "w:tbl" -> out.append("</table>")
                    }
                }
                ev = p.next()
            }
            return out.toString()
        }
    }

    // ---- PPTX ----

    fun pptxToHtml(file: File): String {
        ZipFile(file).use { zip ->
            val re = Regex("ppt/slides/slide(\\d+)\\.xml")
            val slides = zip.entries().toList().map { it.name }
                .filter { re.matches(it) }
                .sortedBy { re.matchEntire(it)!!.groupValues[1].toInt() }
            val out = StringBuilder()
            for ((n, name) in slides.withIndex()) {
                val num = re.matchEntire(name)!!.groupValues[1]
                val rels = readRels(zip, "ppt/slides/_rels/slide$num.xml.rels", "ppt/slides/")
                out.append("<section class=\"slide\"><div class=\"sn\">").append(n + 1).append(" / ").append(slides.size).append("</div>")
                val para = StringBuilder()
                var isTitle = false
                var bold = false
                var ital = false
                var bullet = false
                val p = Xml.newPullParser()
                p.setInput(zip.getInputStream(zip.getEntry(name)), null)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (p.name) {
                            "p:sp" -> isTitle = false
                            "p:ph" -> {
                                val t = p.getAttributeValue(null, "type")
                                if (t == "title" || t == "ctrTitle") isTitle = true
                            }
                            "a:p" -> { para.setLength(0); bullet = false }
                            "a:buChar", "a:buAutoNum" -> bullet = true
                            "a:r" -> { bold = false; ital = false }
                            "a:rPr" -> {
                                bold = p.getAttributeValue(null, "b") == "1"
                                ital = p.getAttributeValue(null, "i") == "1"
                            }
                            "a:t" -> {
                                var h = esc(p.nextText())
                                if (bold) h = "<b>$h</b>"
                                if (ital) h = "<i>$h</i>"
                                para.append(h)
                            }
                            "a:br" -> para.append("<br>")
                            "a:blip" -> {
                                val id = p.getAttributeValue(null, "r:embed")
                                rels[id]?.let { out.append(dataImg(zip, it)) }
                            }
                        }
                    } else if (ev == XmlPullParser.END_TAG && p.name == "a:p") {
                        val inner = para.toString()
                        if (inner.isNotBlank()) {
                            when {
                                isTitle -> out.append("<h2>").append(inner).append("</h2>")
                                bullet -> out.append("<p style=\"margin-left:1.2em\">&bull;&nbsp;").append(inner).append("</p>")
                                else -> out.append("<p>").append(inner).append("</p>")
                            }
                        }
                        para.setLength(0)
                    }
                    ev = p.next()
                }
                out.append("</section>")
            }
            return out.toString()
        }
    }

    // ---- FB2 ----

    private fun hrefOf(p: XmlPullParser): String? {
        for (k in 0 until p.attributeCount) {
            if (p.getAttributeName(k).endsWith("href")) return p.getAttributeValue(k).removePrefix("#")
        }
        return null
    }

    private val fb2Tags = mapOf(
        "body" to ("<div class=\"body\">" to "</div>"),
        "section" to ("<section>" to "</section>"),
        "title" to ("<h2>" to "</h2>"),
        "subtitle" to ("<h4>" to "</h4>"),
        "epigraph" to ("<blockquote>" to "</blockquote>"),
        "cite" to ("<blockquote>" to "</blockquote>"),
        "poem" to ("<div class=\"poem\">" to "</div>"),
        "stanza" to ("<div class=\"st\">" to "</div>"),
        "v" to ("" to "<br>"),
        "text-author" to ("<p class=\"ta\">" to "</p>"),
        "empty-line" to ("<br>" to ""),
        "p" to ("<p>" to "</p>"),
        "emphasis" to ("<em>" to "</em>"),
        "strong" to ("<strong>" to "</strong>"),
        "strikethrough" to ("<s>" to "</s>"),
        "sub" to ("<sub>" to "</sub>"),
        "sup" to ("<sup>" to "</sup>"),
        "code" to ("<code>" to "</code>"),
        "table" to ("<table>" to "</table>"),
        "tr" to ("<tr>" to "</tr>"),
        "td" to ("<td>" to "</td>"),
        "th" to ("<th>" to "</th>"),
        "a" to ("<span class=\"lnk\">" to "</span>")
    )

    fun fb2ToHtml(input: InputStream): String {
        val p = Xml.newPullParser()
        p.setInput(input, null) // кодировку берёт из <?xml ... encoding="...">
        val stack = ArrayList<String>()
        val body = StringBuilder()
        var bookTitle = ""
        val authors = ArrayList<String>()
        val nameParts = ArrayList<String>()
        var cover: String? = null
        val bin = HashMap<String, String>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = p.name
                    stack.add(name)
                    val inInfo = stack.contains("title-info")
                    val inBody = stack.contains("body")
                    when {
                        name == "binary" -> {
                            val id = p.getAttributeValue(null, "id") ?: ""
                            val ct = p.getAttributeValue(null, "content-type") ?: "image/jpeg"
                            val t = p.nextText().filter { !it.isWhitespace() }
                            if (id.isNotEmpty()) bin[id] = "data:$ct;base64,$t"
                            stack.removeAt(stack.size - 1)
                        }
                        inInfo && name == "book-title" -> {
                            bookTitle = p.nextText().trim()
                            stack.removeAt(stack.size - 1)
                        }
                        inInfo && (name == "first-name" || name == "middle-name" || name == "last-name") -> {
                            val t = p.nextText().trim()
                            if (t.isNotEmpty()) nameParts.add(t)
                            stack.removeAt(stack.size - 1)
                        }
                        inInfo && name == "image" && stack.contains("coverpage") -> cover = hrefOf(p)
                        inBody && name == "image" -> hrefOf(p)?.let { body.append("<img src=\"@@").append(it).append("@@\">") }
                        inBody -> {
                            val inTitle = stack.contains("title")
                            if (inTitle && name == "p") body.append("") else fb2Tags[name]?.let { body.append(it.first) }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = p.name
                    if (name == "author" && stack.contains("title-info") && nameParts.isNotEmpty()) {
                        authors.add(nameParts.joinToString(" "))
                        nameParts.clear()
                    }
                    if (stack.contains("body") && name != "image") {
                        val inTitle = stack.contains("title") && name == "p"
                        if (inTitle) body.append("<br>") else fb2Tags[name]?.let { body.append(it.second) }
                    }
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
                XmlPullParser.TEXT -> if (stack.contains("body")) body.append(esc(p.text))
            }
            ev = p.next()
        }
        val sb = StringBuilder()
        cover?.let { sb.append("<p style=\"text-align:center\"><img src=\"@@").append(it).append("@@\"></p>") }
        if (bookTitle.isNotEmpty()) sb.append("<h1>").append(esc(bookTitle)).append("</h1>")
        if (authors.isNotEmpty()) sb.append("<p class=\"ta\">").append(esc(authors.joinToString(", "))).append("</p>")
        sb.append(body)
        var html = sb.toString()
        for ((id, uri) in bin) {
            val key = "@@$id@@"
            if (html.contains(key)) html = html.replace(key, uri)
        }
        return html.replace(Regex("<img src=\"@@[^\"]*@@\">"), "")
    }

    fun fb2FromZip(file: File): String {
        ZipFile(file).use { z ->
            val e = z.entries().toList().firstOrNull { it.name.lowercase().endsWith(".fb2") }
                ?: throw IOException("no .fb2 inside")
            return z.getInputStream(e).use { fb2ToHtml(it) }
        }
    }

    // ---- EPUB ----

    fun openEpub(file: File): EpubBook {
        val zip = ZipFile(file)
        try {
            val cont = zip.getEntry("META-INF/container.xml") ?: throw IOException("container.xml")
            var opfPath = ""
            run {
                val p = Xml.newPullParser()
                p.setInput(zip.getInputStream(cont), null)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT && opfPath.isEmpty()) {
                    if (ev == XmlPullParser.START_TAG && p.name == "rootfile")
                        opfPath = p.getAttributeValue(null, "full-path") ?: ""
                    ev = p.next()
                }
            }
            val opf = zip.getEntry(opfPath) ?: throw IOException("opf")
            val manifest = HashMap<String, String>()
            val spine = ArrayList<String>()
            var title = ""
            val p = Xml.newPullParser()
            p.setInput(zip.getInputStream(opf), null)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "item" -> {
                            val id = p.getAttributeValue(null, "id")
                            val href = p.getAttributeValue(null, "href")
                            if (id != null && href != null) manifest[id] = href
                        }
                        "itemref" -> p.getAttributeValue(null, "idref")?.let { spine.add(it) }
                        "dc:title" -> if (title.isEmpty()) title = p.nextText().trim()
                    }
                }
                ev = p.next()
            }
            val dir = opfPath.substringBeforeLast('/', "")
            val chapters = spine.mapNotNull { manifest[it] }
                .map { resolve(dir, Uri.decode(it)) }
                .filter { zip.getEntry(it) != null }
            if (chapters.isEmpty()) throw IOException("empty spine")
            return EpubBook(zip, chapters, title)
        } catch (e: Throwable) {
            try { zip.close() } catch (_: Throwable) {}
            throw e
        }
    }

    // ---- определение формата ----

    fun detect(f: File, name: String): String {
        val lower = name.lowercase()
        val ext = if (lower.endsWith(".fb2.zip")) "fb2.zip" else lower.substringAfterLast('.', "")
        val byExt = when (ext) {
            "pdf" -> "pdf"
            "epub" -> "epub"
            "fb2" -> "fb2"
            "fb2.zip" -> "fb2zip"
            "docx" -> "docx"
            "pptx" -> "pptx"
            "html", "htm", "xhtml" -> "html"
            "txt", "text", "log" -> "txt"
            "md", "markdown" -> "md"
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif" -> "img"
            "doc", "ppt", "xls", "xlsx", "odt", "odp", "rtf", "mobi", "azw", "azw3", "djvu", "djv", "chm", "lit" -> "unsupported"
            else -> null
        }
        return byExt ?: sniff(f)
    }

    /** Тип архива/файла по первым байтам: многие .cbr на деле zip, а у файлов бывает чужое расширение. */
    fun sniff(f: File): String {
        val head = ByteArray(600)
        val n = f.inputStream().use { it.read(head) }.coerceAtLeast(0)
        fun starts(vararg b: Int): Boolean = n >= b.size && b.indices.all { head[it] == b[it].toByte() }
        return when {
            starts(0x25, 0x50, 0x44, 0x46) -> "pdf"
            starts(0x50, 0x4B, 0x03, 0x04) -> refineZip(f)
            starts(0x52, 0x61, 0x72, 0x21) -> "rar"
            starts(0x37, 0x7A, 0xBC, 0xAF) -> "7z"
            n > 262 && String(head, 257, 5, Charsets.ISO_8859_1) == "ustar" -> "tar"
            starts(0xFF, 0xD8, 0xFF) || starts(0x89, 0x50, 0x4E, 0x47) || starts(0x47, 0x49, 0x46, 0x38) -> "img"
            starts(0x52, 0x49, 0x46, 0x46) && n >= 12 && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "img"
            (0 until n).none { head[it] == 0.toByte() } -> "txt"
            else -> "unsupported"
        }
    }

    private fun refineZip(f: File): String {
        try {
            ZipFile(f).use { z ->
                if (z.getEntry("word/document.xml") != null) return "docx"
                if (z.getEntry("ppt/presentation.xml") != null) return "pptx"
                if (z.getEntry("META-INF/container.xml") != null) return "epub"
                if (z.entries().toList().any { it.name.lowercase().endsWith(".fb2") }) return "fb2zip"
            }
        } catch (_: Throwable) {
        }
        return "zip"
    }

    /** Распаковывает картинки из tar (cbt) во временную папку. */
    fun extractTar(file: File, dir: File): List<File> {
        val res = ArrayList<Pair<String, File>>()
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            BufferedInputStream(FileInputStream(file))
        ).use { t ->
            var n = 0
            while (true) {
                val e = t.nextTarEntry ?: break
                if (e.isDirectory || !isImage(e.name)) continue
                val ext = e.name.substringAfterLast('.', "img").lowercase().filter { it.isLetterOrDigit() }.take(5)
                val f = File(dir, "t${n++}.$ext")
                f.outputStream().use { o -> t.copyTo(o) }
                res.add(e.name to f)
            }
        }
        return res.sortedWith { a, b -> natural(a.first, b.first) }.map { it.second }
    }
}
