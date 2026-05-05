package com.pocketnode.app.inference

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object DocumentReader {
    data class ExtractionResult(
        val text: String,
        val wasTruncated: Boolean,
        val warning: String? = null
    )

    private var isInitialized = false

    fun init(context: Context) {
        if (!isInitialized) {
            PDFBoxResourceLoader.init(context)
            isInitialized = true
        }
    }

    suspend fun extractText(context: Context, uri: Uri): ExtractionResult = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri)
        val content = StringBuilder()
        var wasTruncated = false
        var warning: String? = null

        try {
            if (mimeType == "application/pdf" || uri.toString().endsWith(".pdf", true)) {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    PDDocument.load(inputStream).use { document ->
                        val stripper = PDFTextStripper()
                        // Optional: limit pages to avoid OutOfMemory on huge PDFs
                        stripper.startPage = 1
                        stripper.endPage = 20 // read up to 20 pages
                        content.append(stripper.getText(document))
                        if (document.numberOfPages > 20) {
                            wasTruncated = true
                            warning = "Document truncated to first 20 pages."
                        }
                    }
                }
            } else {
                // Treat as text file
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        var line = reader.readLine()
                        var linesRead = 0
                        while (line != null && linesRead < 1000) { // arbitrary limit for memory
                            content.append(line).append("\n")
                            line = reader.readLine()
                            linesRead++
                        }
                        if (line != null) {
                            wasTruncated = true
                            warning = "Document truncated to first 1000 lines."
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext ExtractionResult(
                text = "Error extracting document text: ${e.message}",
                wasTruncated = false
            )
        }

        ExtractionResult(
            text = content.toString().trim(),
            wasTruncated = wasTruncated,
            warning = warning
        )
    }
}
