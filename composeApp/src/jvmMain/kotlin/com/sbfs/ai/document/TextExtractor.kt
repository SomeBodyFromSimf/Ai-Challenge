package com.sbfs.ai.document

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.w3c.dom.NodeList
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

object TextExtractor {

    private val SUPPORTED_EXTENSIONS = setOf("txt", "md", "kt", "kts", "java", "json", "csv", "xml", "pdf", "docx", "doc")

    fun supports(file: File): Boolean = file.extension.lowercase() in SUPPORTED_EXTENSIONS

    /**
     * Извлекает plain-text содержимое файла.
     * Поддерживаемые форматы: txt, md, kt/kts/java, json, csv, xml, pdf, docx, doc.
     */
    fun extract(file: File): String = when (file.extension.lowercase()) {
        "pdf"  -> extractPdf(file)
        "docx" -> extractDocx(file)
        "doc"  -> extractDoc(file)
        else   -> file.readText(Charsets.UTF_8)
    }

    // ── PDF ────────────────────────────────────────────────────────────────────

    private fun extractPdf(file: File): String {
        PDDocument.load(file).use { doc ->
            if (doc.isEncrypted) return ""
            return PDFTextStripper().getText(doc)
        }
    }

    // ── DOCX ───────────────────────────────────────────────────────────────────
    // DOCX — это ZIP-архив; текст находится в word/document.xml в элементах <w:t>.

    private fun extractDocx(file: File): String {
        val xmlContent = ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return ""
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
        }
        return parseWordXml(xmlContent)
    }

    private fun parseWordXml(xml: String): String {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val xmlDoc = factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))

        val ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val nodes: NodeList = xmlDoc.getElementsByTagNameNS(ns, "t")

        val sb = StringBuilder()
        for (i in 0 until nodes.length) {
            sb.append(nodes.item(i).textContent)
            sb.append(' ')
        }
        return sb.toString().trim()
    }

    // ── DOC ────────────────────────────────────────────────────────────────────
    // Бинарный формат Word 97-2003; используем Apache POI HWPFDocument.

    private fun extractDoc(file: File): String {
        file.inputStream().use { stream ->
            HWPFDocument(stream).use { doc ->
                return WordExtractor(doc).use { it.text }
            }
        }
    }
}
