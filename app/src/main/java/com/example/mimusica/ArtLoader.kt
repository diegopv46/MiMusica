package com.example.mimusica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/**
 * Carga la carátula para la notificación y la tarjeta del sistema.
 * Las direcciones "mimusica-art://" usan primero la portada descargada y, si no hay, la del archivo.
 */
class ArtBitmapLoader(private val ctx: Context) : BitmapLoader {
    private val inner = DataSourceBitmapLoader(ctx)
    private val io = Executors.newSingleThreadExecutor()

    override fun supportsMimeType(mimeType: String): Boolean = inner.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = inner.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != "mimusica-art") return inner.loadBitmap(uri)
        val result = SettableFuture.create<Bitmap>()
        io.execute {
            val id = uri.lastPathSegment?.toLongOrNull()
            val artist = uri.getQueryParameter("artist").orEmpty()
            val album = uri.getQueryParameter("album").orEmpty()
            val bmp = runCatching {
                Covers.fileForMeta(ctx, artist, album)?.let { BitmapFactory.decodeFile(it.path) }
                    ?: id?.let { ctx.contentResolver.loadThumbnail(uriOf(it), Size(512, 512), null) }
            }.getOrNull()
            if (bmp != null) result.set(bmp) else result.setException(IllegalStateException("sin carátula"))
        }
        return result
    }
}
