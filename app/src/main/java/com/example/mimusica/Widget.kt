package com.example.mimusica

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Size
import android.widget.RemoteViews
import androidx.media3.common.Player
import java.util.concurrent.Executors

class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUi.refresh(context, null)
    }
}

/** Dibuja el widget de la pantalla de inicio y le manda sus botones al servicio de reproducción. */
object WidgetUi {
    const val ACT_PLAY = "com.example.mimusica.W_PLAY"
    const val ACT_NEXT = "com.example.mimusica.W_NEXT"
    const val ACT_PREV = "com.example.mimusica.W_PREV"

    @Volatile var live: Player? = null
    private val io = Executors.newSingleThreadExecutor()
    private var artId = 0L
    private var artBmp: Bitmap? = null

    fun refresh(ctx: Context, player: Player?) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val ids = mgr.getAppWidgetIds(ComponentName(app, PlayerWidget::class.java))
        if (ids.isEmpty()) return
        val sp = app.getSharedPreferences("widget", Context.MODE_PRIVATE)
        val p = player ?: live
        val item = p?.currentMediaItem
        val title = item?.mediaMetadata?.title?.toString() ?: sp.getString("title", "") ?: ""
        val artist = item?.mediaMetadata?.artist?.toString() ?: sp.getString("artist", "") ?: ""
        val album = item?.mediaMetadata?.albumTitle?.toString() ?: sp.getString("album", "") ?: ""
        val id = item?.mediaId?.toLongOrNull() ?: sp.getLong("id", 0L)
        val playing = p?.isPlaying ?: false
        sp.edit().putString("title", title).putString("artist", artist).putString("album", album).putLong("id", id).apply()
        io.execute {
            val art = runCatching { art(app, id, artist, album) }.getOrNull()
            runCatching { mgr.updateAppWidget(ids, build(app, title, artist, playing, art)) }
        }
    }

    private fun art(app: Context, id: Long, artist: String, album: String): Bitmap? {
        if (id == 0L) return null
        val custom = if (album.isNotEmpty()) Covers.fileForMeta(app, artist, album) else null
        val cacheKey = if (custom != null) -id else id
        if (cacheKey == artId && artBmp != null) return artBmp
        val raw = custom?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
            ?: runCatching { app.contentResolver.loadThumbnail(uriOf(id), Size(220, 220), null) }.getOrNull()
        // El widget viaja por binder: hay que mantener la imagen pequeña
        val small = raw?.let { if (it.width > 220) Bitmap.createScaledBitmap(it, 220, 220, true) else it }
        val out = small?.let { rounded(it, 28f) }
        artId = cacheKey
        artBmp = out
        return out
    }

    private fun rounded(src: Bitmap, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(out).drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), radius, radius, paint)
        return out
    }

    private fun svc(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            ctx, code, Intent(ctx, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun build(ctx: Context, title: String, artist: String, playing: Boolean, art: Bitmap?): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_player)
        v.setTextViewText(R.id.w_title, title.ifEmpty { "Mi Música" })
        v.setTextViewText(R.id.w_artist, artist)
        if (art != null) v.setImageViewBitmap(R.id.w_art, art) else v.setImageViewResource(R.id.w_art, R.drawable.ic_launcher_foreground)
        v.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        v.setOnClickPendingIntent(R.id.w_prev, svc(ctx, ACT_PREV, 1))
        v.setOnClickPendingIntent(R.id.w_play, svc(ctx, ACT_PLAY, 2))
        v.setOnClickPendingIntent(R.id.w_next, svc(ctx, ACT_NEXT, 3))
        val open = PendingIntent.getActivity(
            ctx, 4, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        v.setOnClickPendingIntent(R.id.w_art, open)
        v.setOnClickPendingIntent(R.id.w_title, open)
        v.setOnClickPendingIntent(R.id.w_artist, open)
        return v
    }
}
