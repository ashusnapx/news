package com.ashusnapx.news

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Share cards: a 1080x1350 image (Instagram portrait) drawn on the spot, handed to any app through [ShareProvider]. */
object Share {
    fun story(a: Activity, item: Item) = send(
        a, card(a, item.title, item.excerpt), "${item.title}\n\n${item.link}\n\nvia UPSC Today",
    )

    fun score(a: Activity, marks: String, outOf: Int, right: Int, total: Int) = send(
        a, card(a, a.getString(R.string.share_score_title, marks, outOf), a.getString(R.string.share_score_body, right, total)),
        a.getString(R.string.share_score_text, marks, outOf),
    )

    private fun card(a: Activity, title: String, body: String): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF5B4CF5.toInt(), 0xFF8B5CF6.toInt(), 0xFFDB2777.toInt()), null, Shader.TileMode.CLAMP)
        })
        val pad = 96f
        val kicker = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt(); textSize = 34f; letterSpacing = 0.14f; typeface = a.resources.getFont(R.font.jakarta_bold)
        }
        canvas.drawText("UPSC TODAY  ·  ${DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(LocalDate.now()).uppercase()}", pad, pad + 34f, kicker)
        val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 104f; typeface = a.resources.getFont(R.font.instrument_serif) }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt(); textSize = 44f; typeface = a.resources.getFont(R.font.jakarta_medium) }
        val width = (w - pad * 2).toInt()
        val headLayout = layout(title, head, width, 6)
        val bodyLayout = layout(body, text, width, 6)
        canvas.save()
        canvas.translate(pad, pad + 140f)
        headLayout.draw(canvas)
        canvas.translate(0f, headLayout.height + 56f)
        bodyLayout.draw(canvas)
        canvas.restore()
        canvas.drawText(a.getString(R.string.share_footer), pad, h - pad, kicker)
        return bmp
    }

    private fun layout(s: String, p: TextPaint, width: Int, lines: Int) = StaticLayout.Builder.obtain(s, 0, s.length, p, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.08f).setMaxLines(lines)
        .setEllipsize(android.text.TextUtils.TruncateAt.END).build()

    private fun send(a: Activity, bmp: Bitmap, text: String) {
        val dir = File(a.cacheDir, "share").apply { mkdirs() }
        File(dir, "card.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val uri = Uri.parse("content://${a.packageName}.share/card.png")
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri) // lets the chooser preview the card
        a.startActivity(Intent.createChooser(send, null))
    }
}

/**
 * Serves only the share card, read-only, and only to apps it was granted to (not exported, grantUriPermissions).
 * A provider of our own instead of AndroidX FileProvider, which would be the app's first dependency.
 */
class ShareProvider : ContentProvider() {
    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (!Regex("[a-z]+\\.png").matches(name)) return null // no paths, no traversal
        return File(File(context!!.cacheDir, "share"), name).takeIf { it.exists() }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(file(uri) ?: return null, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = file(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun getType(uri: Uri) = "image/png"
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
