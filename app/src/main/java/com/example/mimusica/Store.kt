package com.example.mimusica

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

const val FAV = "__fav__"

object Store {
    private var sp: SharedPreferences? = null
    var favorites by mutableStateOf<Set<Long>>(emptySet())
    var playlists by mutableStateOf<Map<String, List<Long>>>(emptyMap())

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.getSharedPreferences("store", Context.MODE_PRIVATE)
        sp = s
        favorites = (s.getString("fav", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.toSet()
        runCatching {
            val js = JSONObject(s.getString("pl", "{}") ?: "{}")
            val m = LinkedHashMap<String, List<Long>>()
            for (k in js.keys()) {
                val a = js.getJSONArray(k)
                m[k] = (0 until a.length()).map { a.getLong(it) }
            }
            playlists = m
        }
    }

    private fun save() {
        val o = JSONObject()
        playlists.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        sp?.edit()?.putString("fav", favorites.joinToString(","))?.putString("pl", o.toString())?.apply()
    }

    fun toggleFav(id: Long) { favorites = if (id in favorites) favorites - id else favorites + id; save() }
    fun create(name: String) { if (name !in playlists) { playlists = playlists + (name to emptyList()); save() } }
    fun delete(name: String) { playlists = playlists - name; save() }
    fun addTo(name: String, id: Long) {
        val l = playlists[name] ?: return
        if (id !in l) { playlists = playlists + (name to (l + id)); save() }
    }
    fun addMany(name: String, ids: List<Long>) {
        val l = playlists[name] ?: return
        playlists = playlists + (name to (l + ids.filter { it !in l })); save()
    }
    fun addFavs(ids: List<Long>) { favorites = favorites + ids; save() }
    fun removeFrom(name: String, id: Long) {
        val l = playlists[name] ?: return
        playlists = playlists + (name to (l - id)); save()
    }
}
