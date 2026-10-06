package com.mamre.billing.data.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

// Getting the export out of the phone (Doc 2 s5.3, s6): the files are written to cacheDir/exports and handed to the Android share
// sheet as content:// addresses from androidx.core's FileProvider. There is no storage permission, no INTERNET and no new
// dependency; the Owner chooses where the files go.

/** What the Owner reads before sharing (Doc 2 s6): the files are not protected. */
const val EXPORT_WARNING =
    "These files are plain text. They hold your customer names, phone numbers and addresses, your prices, bills and payments, " +
        "and anyone who gets them can read all of it. Share them only with people you trust. The PIN is never included."

/** Writes an export into cacheDir/exports. Old files are deleted first, so the folder only ever holds the last export. */
class ExportWriter(private val cacheDir: File) {
    fun write(bundle: ExportBundle): List<File> {
        val dir = File(cacheDir, DIR)
        dir.deleteRecursively()
        check(dir.mkdirs() || dir.isDirectory) { "cannot create the export folder" }
        return bundle.files.map { file ->
            File(dir, file.name).also { it.writeBytes(file.text.toByteArray(Charsets.UTF_8)) }
        }
    }

    companion object {
        /** The folder under the cache directory; file_paths.xml shares exactly this folder. */
        const val DIR = "exports"
    }
}

object ExportShare {
    /** The provider authority, declared in the manifest as ${applicationId}.exports. */
    fun authority(context: Context): String = context.packageName + ".exports"

    /** One share of all the files: ACTION_SEND_MULTIPLE, read access granted for each address. */
    fun intent(
        context: Context,
        files: List<File>,
        uriFor: (File) -> Uri = { FileProvider.getUriForFile(context, authority(context), it) },
    ): Intent {
        require(files.isNotEmpty()) { "nothing to share" }
        val uris = ArrayList(files.map(uriFor))
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "Billing data export")
            // The clip carries the grant to every address, not only the first.
            clipData = uris.drop(1).fold(ClipData.newRawUri("export", uris.first())) { clip, uri -> clip.apply { addItem(ClipData.Item(uri)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
