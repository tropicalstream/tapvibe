package com.tropicalstream.tapvibe.radio

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** A tunable internet-radio station (Icecast / SHOUTcast / HTTP stream). */
data class Station(val name: String, val url: String, val meta: String = "")

/** How a directory result set is ordered. Descending for every rank; A–Z ascends. */
enum class RadioOrder(val api: String, val reverse: Boolean, val label: String) {
    VOTES("votes", true, "Top voted"),
    TREND("clicktrend", true, "Trending"),
    PLAYS("clickcount", true, "Most played"),
    QUALITY("bitrate", true, "Quality"),
    NAME("name", false, "A–Z"),
}

/** A facet the directory can be sliced by. */
sealed class RadioQuery {
    /** The whole directory, unfiltered — just ranked. */
    object All : RadioQuery()
    data class Tag(val tag: String) : RadioQuery()
    data class Country(val code: String) : RadioQuery()
}

data class RadioCountry(val name: String, val code: String, val count: Int)

/**
 * Internet radio: favorite stations in prefs, plus a full browsable front-end to
 * the community radio-browser.info directory (free, open API — the de-facto index
 * of ~50k Icecast/SHOUTcast streams, the same catalogue VLC's Icecast discovery
 * draws on). Browse by genre or country, or pull the trending / top-voted /
 * most-played charts, each ranked and paged. MediaPlayer streams the resolved
 * URLs directly, and TapVibe's session-visualizer reacts to them exactly like an
 * uploaded track.
 *
 * Uses HttpURLConnection so IP Radio adds no third-party dependency. All network
 * calls MUST run off the UI thread (see RadioService).
 */
class RadioStore(context: Context) {
    companion object {
        private const val TAG = "TapVibeRadio"
        private const val BASE = "https://all.api.radio-browser.info/json"
        private const val UA = "TapVibe/1.0"

        /** Curated genre tiles — the tags people actually browse by, in the
         *  spellings radio-browser indexes. Display name to query tag. */
        val GENRES: List<Pair<String, String>> = listOf(
            "Pop" to "pop", "Rock" to "rock", "Jazz" to "jazz", "Classical" to "classical",
            "News" to "news", "Talk" to "talk", "Oldies" to "oldies", "Dance" to "dance",
            "Electronic" to "electronic", "House" to "house", "Techno" to "techno",
            "Hip-Hop" to "hiphop", "Country" to "country", "Folk" to "folk", "Metal" to "metal",
            "Blues" to "blues", "Reggae" to "reggae", "Latin" to "latin", "Soul" to "soul",
            "Funk" to "funk", "Ambient" to "ambient", "Chillout" to "chillout", "Lounge" to "lounge",
            "80s" to "80s", "90s" to "90s", "Indie" to "indie", "Punk" to "punk",
            "Gospel" to "gospel", "World" to "world", "Top 40" to "top40", "Sports" to "sports",
        )
    }

    private val prefs = context.getSharedPreferences("tapvibe_radio", Context.MODE_PRIVATE)

    // ---- Favorites ----------------------------------------------------------

    fun favorites(): List<Station> {
        val raw = prefs.getString("favorites", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Station(o.optString("name"), o.getString("url"), o.optString("meta"))
            }.filter { it.url.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun isFavorite(url: String): Boolean = favorites().any { it.url == url }

    /** Toggle a station in favorites; returns true if it is now a favorite. */
    fun toggleFavorite(st: Station): Boolean {
        val cur = favorites()
        return if (cur.any { it.url == st.url }) {
            persist(cur.filterNot { it.url == st.url }); false
        } else {
            persist(cur + st); true
        }
    }

    private fun persist(list: List<Station>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url).put("meta", it.meta)) }
        prefs.edit().putString("favorites", arr.toString()).apply()
    }

    // ---- Directory ----------------------------------------------------------

    /** One ranked, paged slice of the directory. Call off the UI thread. */
    fun fetch(q: RadioQuery, order: RadioOrder, limit: Int, offset: Int): List<Station> {
        val params = StringBuilder(
            "?limit=$limit&offset=$offset&hidebroken=true" +
                "&order=${order.api}&reverse=${order.reverse}")
        when (q) {
            is RadioQuery.All -> {}
            is RadioQuery.Tag -> params.append("&tag=").append(enc(q.tag))
            is RadioQuery.Country -> params.append("&countrycode=").append(enc(q.code))
        }
        return getStations("$BASE/stations/search$params")
    }

    /** Countries that actually have stations, busiest first. Call off the UI thread. */
    fun countries(): List<RadioCountry> = try {
        val arr = JSONArray(getBody("$BASE/countries"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            val code = o.optString("iso_3166_1").trim()
            val count = o.optInt("stationcount")
            if (name.isBlank() || code.isBlank() || count <= 0) null
            else RadioCountry(name, code, count)
        }.sortedByDescending { it.count }
    } catch (e: Exception) {
        Log.w(TAG, "countries fetch failed: ${e.message}"); emptyList()
    }

    // ---- Internals ----------------------------------------------------------

    private fun getStations(url: String): List<Station> = try {
        val arr = JSONArray(getBody(url))
        (0 until arr.length()).mapNotNull { i -> parseStation(arr.optJSONObject(i)) }
    } catch (e: Exception) {
        Log.w(TAG, "directory fetch failed: ${e.message}"); emptyList()
    }

    private fun getBody(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 25_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseStation(o: JSONObject?): Station? {
        if (o == null) return null
        val streamUrl = o.optString("url_resolved").ifBlank { o.optString("url") }
        if (streamUrl.isBlank()) return null
        // A compact, professional meta line: genre · bitrate · country · popularity.
        val genre = o.optString("tags").split(',').firstOrNull { it.isNotBlank() }
            ?.trim()?.replaceFirstChar { it.uppercase() }
        val bitrate = o.optInt("bitrate").takeIf { it > 0 }?.let { "${it}k" }
        val country = o.optString("country").takeIf { it.isNotBlank() }
        val votes = o.optInt("votes").takeIf { it > 0 }?.let { "▲" + compact(it) }
        val meta = listOfNotNull(genre, bitrate, country, votes).joinToString(" · ")
        return Station(o.optString("name", "Unnamed station").trim(), streamUrl, meta)
    }

    private fun compact(n: Int): String = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
}
