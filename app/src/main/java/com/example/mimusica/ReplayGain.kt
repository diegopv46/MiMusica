package com.example.mimusica

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.flac.VorbisComment
import androidx.media3.extractor.metadata.id3.TextInformationFrame

/** Lee las etiquetas REPLAYGAIN_* (FLAC/Vorbis e ID3 TXXX) de la pista actual y las deja en Dsp. */
object ReplayGain {
    private fun num(s: String): Float? =
        Regex("[-+]?\\d+(?:[.,]\\d+)?").find(s)?.value?.replace(',', '.')?.toFloatOrNull()

    fun attach(p: Player) {
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                var tg = Float.NaN
                var ag = Float.NaN
                var tp = Float.NaN
                var ap = Float.NaN
                for (g in tracks.groups) {
                    if (g.type != C.TRACK_TYPE_AUDIO || !g.isSelected) continue
                    for (i in 0 until g.length) {
                        val md = g.getTrackFormat(i).metadata ?: continue
                        for (j in 0 until md.length()) {
                            val e = md.get(j)
                            val kv: Pair<String, String> = when (e) {
                                is VorbisComment -> e.key to e.value
                                is TextInformationFrame ->
                                    if (e.id == "TXXX") (e.description ?: "") to (e.values.firstOrNull() ?: "") else continue
                                else -> continue
                            }
                            val v = num(kv.second) ?: continue
                            when (kv.first.uppercase()) {
                                "REPLAYGAIN_TRACK_GAIN" -> tg = v
                                "REPLAYGAIN_ALBUM_GAIN" -> ag = v
                                "REPLAYGAIN_TRACK_PEAK" -> tp = v
                                "REPLAYGAIN_ALBUM_PEAK" -> ap = v
                            }
                        }
                    }
                }
                Dsp.rgTrack = tg; Dsp.rgAlbum = ag; Dsp.peakTrack = tp; Dsp.peakAlbum = ap
                Dsp.version++
            }
        })
    }
}
