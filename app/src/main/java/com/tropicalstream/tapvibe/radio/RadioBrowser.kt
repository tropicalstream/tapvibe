package com.tropicalstream.tapvibe.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * The IP Radio navigation model + async orchestration, kept out of the render
 * layer so StageView only draws and forwards gestures.
 *
 * Screens form a shallow, intuitive tree:
 *   HOME → { Favorites, Trending, Top Voted, Most Played, Genres, Countries }
 *   GENRES    → STATIONS (by tag)
 *   COUNTRIES → STATIONS (by country)
 *   the charts → STATIONS directly
 *
 * STATIONS is the payoff: swipe ↑↓ scrolls (and lazily pages in the next 30 as
 * you near the end — so the whole ~50k directory is reachable), swipe ←→ cycles
 * the ranking, tap streams, long-press stars a favorite.
 */
class RadioBrowser(context: Context) {

    enum class Screen { HOME, GENRES, COUNTRIES, STATIONS }
    enum class Confirm { STAY, PLAY }

    private val store = RadioStore(context)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    var onChanged: (() -> Unit)? = null
    var onPlay: ((Station) -> Unit)? = null

    var screen = Screen.HOME; private set
    var index = 0; private set
    var loading = false; private set
    var status: String? = null; private set
    var title = ""; private set

    val homeItems = listOf(
        "★  Favorites", "🔥  Trending", "▲  Top Voted",
        "♫  Most Played", "🎧  Genres", "🌍  Countries"
    )
    val genres = RadioStore.GENRES

    private var countries: List<RadioCountry> = emptyList()
    private val stations = ArrayList<Station>()
    private var favUrls: Set<String> = store.favorites().mapTo(HashSet()) { it.url }
    private var query: RadioQuery = RadioQuery.All
    private var order = RadioOrder.VOTES
    private var favoritesMode = false
    private var parent = Screen.HOME
    private var moreAvailable = true
    private var reqId = 0

    val orderLabel: String get() = order.label
    fun isFavoritesScreen() = favoritesMode
    fun stationList(): List<Station> = stations
    fun countryList(): List<RadioCountry> = countries
    /** Cached favorite membership — cheap enough to call per row per frame. */
    fun isFav(url: String) = url in favUrls

    /** Fresh entry from the main menu. */
    fun enter() {
        screen = Screen.HOME; index = 0; title = ""; status = null
        favUrls = store.favorites().mapTo(HashSet()) { it.url }
        notifyChanged()
    }

    private fun listSize(): Int = when (screen) {
        Screen.HOME -> homeItems.size
        Screen.GENRES -> genres.size
        Screen.COUNTRIES -> countries.size
        Screen.STATIONS -> stations.size
    }

    fun onSelect(dir: Int) {
        val n = listSize()
        if (n == 0) return
        val step = if (dir >= 0) 1 else -1
        index = (index + step + n) % n
        // Near the tail of a live directory list → pull the next page.
        if (screen == Screen.STATIONS && !favoritesMode && index >= stations.size - 6) loadNextPage()
        notifyChanged()
    }

    fun onCycle(dir: Int) {
        if (screen != Screen.STATIONS || favoritesMode) return
        val vals = RadioOrder.values()
        order = vals[(order.ordinal + (if (dir >= 0) 1 else -1) + vals.size) % vals.size]
        refetch()
    }

    fun onConfirm(): Confirm {
        when (screen) {
            Screen.HOME -> when (index) {
                0 -> openFavorites()
                1 -> openChart(RadioOrder.TREND, "Trending")
                2 -> openChart(RadioOrder.VOTES, "Top Voted")
                3 -> openChart(RadioOrder.PLAYS, "Most Played")
                4 -> { screen = Screen.GENRES; index = 0; notifyChanged() }
                5 -> openCountries()
            }
            Screen.GENRES -> {
                val (name, tag) = genres[index]
                openFacet(RadioQuery.Tag(tag), name, Screen.GENRES)
            }
            Screen.COUNTRIES -> {
                val c = countries.getOrNull(index) ?: return Confirm.STAY
                openFacet(RadioQuery.Country(c.code), c.name, Screen.COUNTRIES)
            }
            Screen.STATIONS -> {
                val st = stations.getOrNull(index) ?: return Confirm.STAY
                onPlay?.invoke(st)
                return Confirm.PLAY
            }
        }
        return Confirm.STAY
    }

    /** @return true if the back was consumed inside radio; false = leave radio. */
    fun onBack(): Boolean = when (screen) {
        Screen.HOME -> false
        Screen.GENRES, Screen.COUNTRIES -> { screen = Screen.HOME; index = 0; notifyChanged(); true }
        Screen.STATIONS -> { screen = parent; index = 0; notifyChanged(); true }
    }

    fun onLongPress() {
        if (screen != Screen.STATIONS) return
        val st = stations.getOrNull(index) ?: return
        val nowFav = store.toggleFavorite(st)
        favUrls = store.favorites().mapTo(HashSet()) { it.url }
        flash(if (nowFav) "★ saved to favorites" else "☆ removed")
        // Reflect a removal immediately if we're looking at the favorites list.
        if (favoritesMode && !nowFav) {
            stations.removeAt(index)
            if (index >= stations.size) index = (stations.size - 1).coerceAtLeast(0)
        }
        notifyChanged()
    }

    // ---- openers ----

    private fun openFavorites() {
        favoritesMode = true; parent = Screen.HOME; title = "Favorites"
        query = RadioQuery.All; screen = Screen.STATIONS; index = 0
        stations.clear(); stations.addAll(store.favorites())
        moreAvailable = false; loading = false; reqId++
        notifyChanged()
    }

    private fun openChart(order: RadioOrder, title: String) =
        openFacet(RadioQuery.All, title, Screen.HOME, order)

    private fun openFacet(q: RadioQuery, title: String, parent: Screen, order: RadioOrder = RadioOrder.VOTES) {
        this.query = q; this.order = order; this.title = title; this.parent = parent
        favoritesMode = false; screen = Screen.STATIONS; index = 0
        stations.clear(); moreAvailable = true
        refetch()
    }

    private fun openCountries() {
        screen = Screen.COUNTRIES; index = 0
        if (countries.isEmpty() && !loading) {
            loading = true
            io.execute {
                val res = store.countries()
                main.post { countries = res; loading = false; notifyChanged() }
            }
        }
        notifyChanged()
    }

    // ---- async fetch ----

    private fun refetch() {
        val id = ++reqId
        stations.clear(); index = 0; moreAvailable = true; loading = true
        notifyChanged()
        io.execute {
            val res = store.fetch(query, order, PAGE, 0)
            main.post {
                if (id != reqId) return@post
                stations.clear(); stations.addAll(res)
                moreAvailable = res.size == PAGE
                loading = false; notifyChanged()
            }
        }
    }

    private fun loadNextPage() {
        if (loading || !moreAvailable || favoritesMode) return
        val id = reqId
        val offset = stations.size
        loading = true; notifyChanged()
        io.execute {
            val res = store.fetch(query, order, PAGE, offset)
            main.post {
                if (id != reqId) return@post
                stations.addAll(res)
                moreAvailable = res.size == PAGE
                loading = false; notifyChanged()
            }
        }
    }

    private fun flash(msg: String) {
        status = msg
        main.postDelayed({ status = null; notifyChanged() }, 1400L)
    }

    private fun notifyChanged() { onChanged?.invoke() }

    fun release() { io.shutdownNow() }

    companion object { private const val PAGE = 30 }
}
