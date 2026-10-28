package com.ashusnapx.news

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors

/** Image loader: memory LRU on top of the platform HTTP disk cache, decoded no larger than the screen needs. */
object Img {
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String?, into: ImageView) {
        into.tag = url
        val hit = url?.let(memory::get)
        into.setImageBitmap(hit)
        into.alpha = 1f
        if (url == null || hit != null) return
        into.alpha = 0f
        val width = into.resources.displayMetrics.widthPixels
        pool.execute {
            val bitmap = runCatching { decode(url, width) }.getOrNull() ?: return@execute
            memory.put(url, bitmap)
            main.post {
                if (into.tag != url) return@post // view was recycled for another story meanwhile
                into.setImageBitmap(bitmap)
                into.animate().alpha(1f).setDuration(220)
            }
        }
    }

    private fun decode(url: String, width: Int): Bitmap? {
        val bytes = URL(url).openConnection().run {
            connectTimeout = 10_000
            readTimeout = 20_000
            getInputStream().use { it.readBytes() }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        // Downsample to screen width, and cap height at 3x so a giant infographic can't exhaust memory.
        o.inSampleSize = generateSequence(1) { it * 2 }.first { o.outWidth / it <= width * 2 && o.outHeight / it <= width * 3 }
        o.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }
}
