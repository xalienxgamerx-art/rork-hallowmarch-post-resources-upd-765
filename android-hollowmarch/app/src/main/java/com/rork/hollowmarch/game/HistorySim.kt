package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.ChronicleEvent
import com.rork.hollowmarch.world.EventKind
import com.rork.hollowmarch.world.Site
import com.rork.hollowmarch.world.World
import com.rork.hollowmarch.world.isSettlement
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The societal layer beneath the years: who lives where, what walks when a
 * place starves, and when a place is left to the crows. Built under the folk
 * ledger — never beside it — so every count has one home and the whole layer
 * is the seed's own, always the same.
 *
 * Every roll here is drawn from the seed, the place, and the year; nothing
 * reads the clock, the iteration order, or luck. The mutable residue (fading
 * counts, arrivals, the event log) rides the save; everything else is derived.
 */

/** The kinds of year the deep history records. */
enum class AgeEventKind(val label: String) {
    FAMINE("famine"),
    PLAGUE("plague"),
    MIGRATION("migration"),
    SETTLEMENT_ROSE("a place rose"),
    SETTLEMENT_THINNED("a place thinned"),
    ABANDONED("a place was left"),
    RESOURCE_DISCOVERED("a resource was found"),
    RESOURCE_DEPLETED("a resource ran out"),
    HARVEST_FAILURE("the harvest failed"),
    HARVEST_SURPLUS("a fat year"),
    TRADE_ROUTE_OPENED("a trade road opened"),
    TRADE_ROUTE_CLOSED("a trade road closed"),
    TRADE_ROUTE_STRENGTHENED("a trade road grew strong"),
    SPECIALIZED("a people found their trade"),
    ECONOMIC_DECLINE("a trade withered"),
    CROP_ESTABLISHED("a crop took hold"),
    CROP_LOST("a crop was lost"),
    MINE_OPENED("a mine was opened"),
    QUARRY_OPENED("a quarry was opened"),
    QUARRY_CLOSED("a quarry was closed"),
    INDUSTRY_ESTABLISHED("an industry was born"),
    INDUSTRY_DECLINED("an industry failed")
}

/**
 * One causal historical fact: what happened, where, in which year, and how
 * many souls it touched. Recorded once; consumed by the chronicle, the log,
 * and any later system that asks what the world remembers.
 */
data class AgeEvent(
    val year: Int,
    val kind: AgeEventKind,
    val siteId: Int,
    val otherSiteId: Int = -1,
    val count: Int = 0,
    val text: String
) {
    /** The chronicle's line for it, under the kinds the journal already knows. */
    fun toChronicle(): ChronicleEvent = when (kind) {
        AgeEventKind.FAMINE -> ChronicleEvent(year, EventKind.FAMINE, text)
        AgeEventKind.PLAGUE -> ChronicleEvent(year, EventKind.PLAGUE, text)
        AgeEventKind.MIGRATION -> ChronicleEvent(year, EventKind.MIGRATION, text)
        AgeEventKind.SETTLEMENT_ROSE -> ChronicleEvent(year, EventKind.GROWTH, text)
        AgeEventKind.SETTLEMENT_THINNED -> ChronicleEvent(year, EventKind.RUIN, text)
        AgeEventKind.ABANDONED -> ChronicleEvent(year, EventKind.RUIN, text)
        AgeEventKind.RESOURCE_DISCOVERED -> ChronicleEvent(year, EventKind.CLAIM, text)
        AgeEventKind.RESOURCE_DEPLETED -> ChronicleEvent(year, EventKind.RUIN, text)
        AgeEventKind.HARVEST_FAILURE -> ChronicleEvent(year, EventKind.FAMINE, text)
        AgeEventKind.HARVEST_SURPLUS -> ChronicleEvent(year, EventKind.GROWTH, text)
        AgeEventKind.TRADE_ROUTE_OPENED -> ChronicleEvent(year, EventKind.TAVERN, text)
        AgeEventKind.TRADE_ROUTE_CLOSED -> ChronicleEvent(year, EventKind.RUIN, text)
        AgeEventKind.TRADE_ROUTE_STRENGTHENED -> ChronicleEvent(year, EventKind.TAVERN, text)
        AgeEventKind.SPECIALIZED -> ChronicleEvent(year, EventKind.GROWTH, text)
        AgeEventKind.ECONOMIC_DECLINE -> ChronicleEvent(year, EventKind.RUIN, text)
        AgeEventKind.CROP_ESTABLISHED -> ChronicleEvent(year, EventKind.GROWTH, text)
        AgeEventKind.CROP_LOST -> ChronicleEvent(year, EventKind.RUIN, text)
        AgeEventKind.MINE_OPENED -> ChronicleEvent(year, EventKind.CLAIM, text)
        AgeEventKind.QUARRY_OPENED -> ChronicleEvent(year, EventKind.CLAIM, text)
        AgeEventKind.QUARRY_CLOSED -> ChronicleEvent(year, EventKind.RUIN, text)
        AgeEventKind.INDUSTRY_ESTABLISHED -> ChronicleEvent(year, EventKind.GUILDHALL, text)
        AgeEventKind.INDUSTRY_DECLINED -> ChronicleEvent(year, EventKind.RUIN, text)
    }
}

/** One settlement's people, by their years and their work — never one soul apiece. */
data class PopulationCensus(
    val siteId: Int,
    val total: Int,
    val children: Int,
    val adults: Int,
    val elders: Int,
    /** Work the place lives by, largest share first. */
    val occupations: List<Pair<String, Int>>,
    /** Born here against come-here: what the roads have made of the place. */
    val natives: Int,
    val arrived: Int,
    val wealthTier: String
) {
    val culturesNote: String
        get() = when {
            arrived <= 0 -> "all born to it"
            arrived * 4 >= total -> "more come-here than born"
            else -> "some come-here among the born"
        }
}

/**
 * The province's deep history: famine sends walkers, walkers change the places
 * they reach, growth raises notables, and thin places are left. The folk
 * ledger keeps every count; this layer decides what the counts mean and where
 * the living go.
 */
class HistoricalSimulation private constructor(
    private val world: World,
    private val state: MutableMap<Int, SiteHistory>
) {

    private val seed: Long get() = world.seed

    /** The economy layer beneath this one: what the land gives, what is eaten. */
    internal val economy = EconomySimulation.fresh(world)

    /** One place's residue: its fading streak, its leavers, whether it was left. */
    data class SiteHistory(
        var fadingYears: Int = 0,
        var abandoned: Boolean = false,
        var arrived: Int = 0
    )

    private val events = mutableListOf<AgeEvent>()
    private var lastYear: Int = 0

    /**
     * The log's room: at least one remembered deed for every living place, so a
     * crowded province's census timelines keep their annals — never fewer than
     * the old fixed cap, never a size the world's own population doesn't set.
     */
    private val eventLogCap = maxOf(
        EVENT_LOG_CAP,
        world.sites.count { it.isSettlement && !it.ruined }
    )

    /** What the world remembers of itself, newest last. */
    fun eventLog(): List<AgeEvent> = events

    /** Whether a place has been left to the crows. */
    fun isAbandoned(siteId: Int): Boolean = state[siteId]?.abandoned == true

    // ------------------------------------------------------------- the years

    /**
     * Turn the deep layer forward to [year]. Called after the folk ledger has
     * stepped; the turns carry what kind of year each place had. Returns the
     * causal events the year produced, oldest first.
     */
    fun stepYears(
        year: Int,
        sites: List<Site>,
        ledger: SettlementLedger,
        turns: List<YearTurn>
    ): List<AgeEvent> {
        if (year <= lastYear) return emptyList()
        val produced = mutableListOf<AgeEvent>()
        val byTurn = turns.associateBy { it.site.id }
        // The economy turns first: its shortfalls decide who walks this year.
        val hunger = economy.stepYear(year, sites, ledger, produced)
        sites.filter { it.isSettlement && !it.ruined }.sortedBy { it.id }.forEach { site ->
            val turn = byTurn[site.id]
            val folk = ledger.folkOf(site)
            applySiteYear(site, folk, turn, sites, ledger, year, produced, hunger[site.id] ?: 0)
        }
        lastYear = year
        events += produced
        while (events.size > eventLogCap) events.removeAt(0)
        return produced
    }

    /**
     * One place, one year: the whole causal heart of the layer, pure in its
     * inputs and deterministic in its rolls.
     */
    internal fun applySiteYear(
        site: Site,
        folk: Int,
        turn: YearTurn?,
        sites: List<Site>,
        ledger: SettlementLedger,
        year: Int,
        into: MutableList<AgeEvent>,
        hungry: Int = 0
    ) {
        val hist = state.getOrPut(site.id) { SiteHistory() }
        val step = turn?.step
        val kindYear = step?.kind

        // Growth: the place rose, and someone's name went on the work.
        turn?.roseTo?.let { rose ->
            val builder = notableFor(seed, site.id, year)
            into += AgeEvent(
                year, AgeEventKind.SETTLEMENT_ROSE, site.id,
                text = "$builder led the raising of ${site.name} — its folk, ${folk} strong, made it a ${rose.label}."
            )
        }

        // Hardship: of those the year took, some died and some walked. Hunger
        // walks too: the economy's shortfall puts hungry souls onto the roads.
        val lost = step?.died ?: 0
        val famineWalkers = if (kindYear == FolkYearKind.FAMINE || kindYear == FolkYearKind.PLAGUE) {
            lost * MIGRANT_SHARE_NUM / MIGRANT_SHARE_DEN
        } else {
            0
        }
        val walkers = famineWalkers + hungry
        if (walkers > 0 && folk + walkers > ABANDON_FLOOR) {
            val destinations = chooseDestinations(site, sites, ledger, year)
            if (destinations.isNotEmpty()) {
                // The famine walkers live: they left, they did not die — the
                // ledger's count gave them to the grave, the deep layer takes
                // them back onto the road. The hungry simply leave the count.
                if (famineWalkers > 0) ledger.addFolk(site.id, famineWalkers)
                if (hungry > 0) ledger.addFolk(site.id, -hungry)
                var remaining = walkers
                destinations.forEachIndexed { index, dest ->
                    val share = if (index == destinations.lastIndex) remaining
                    else walkers * DESTINATION_SPLIT[index] / 100
                    val count = share.coerceAtMost(remaining)
                    if (count > 0) {
                        ledger.addFolk(dest.id, count)
                        state.getOrPut(dest.id) { SiteHistory() }.arrived += count
                        remaining -= count
                    }
                }
                val to = destinations.first()
                into += AgeEvent(
                    year, AgeEventKind.MIGRATION, site.id, to.id, walkers,
                    "${walkers} ${if (famineWalkers > 0) "souls" else "hungry souls"} walked out of ${site.name}, " +
                        (if (famineWalkers > 0) "the year having taken the rest" else "the land having given out") +
                        ", and came to ${to.name}."
                )
            }
        }

        // Thinning and abandonment: a place below the floor too many years running
        // is left, and its chronicle line is the last anyone writes of it.
        if (folk < ABANDON_FLOOR && !hist.abandoned) {
            hist.fadingYears++
            if (hist.fadingYears >= FADING_YEARS) {
                hist.abandoned = true
                into += AgeEvent(
                    year, AgeEventKind.ABANDONED, site.id,
                    text = "${site.name} was left — too few remained to keep it, and the last " +
                        "doors were barred from the outside."
                )
            } else if (hist.fadingYears >= FADING_WARN) {
                into += AgeEvent(
                    year, AgeEventKind.SETTLEMENT_THINNED, site.id,
                    text = "${site.name} thins; ${folk} souls keep it, and the fields go back to brush."
                )
            }
        } else if (folk >= ABANDON_FLOOR * 2) {
            hist.fadingYears = 0
        }
    }

    /**
     * Where the walkers go: the near and the living first, the larger places
     * stronger than the small. Deterministic from seed, place, and year.
     */
    private fun chooseDestinations(
        site: Site,
        sites: List<Site>,
        ledger: SettlementLedger,
        year: Int
    ): List<Site> {
        val candidates = sites
            .filter {
                it.id != site.id && it.isSettlement && !it.ruined &&
                    !isAbandoned(it.id) && ledger.folkOf(it) > 0
            }
            .sortedWith(
                compareBy<Site> {
                    val dx = it.x - site.x
                    val dy = it.y - site.y
                    (dx * dx + dy * dy)
                }.thenBy { it.id }
            )
        if (candidates.isEmpty()) return emptyList()
        // A roll among the nearest few, weighted by what each place can hold.
        val near = candidates.take(DESTINATION_POOL)
        val rng = Random(seed * 678679679L + site.id * 100003L + year * 7919L)
        val weights = near.map { stageOf(it, ledger.folkOf(it)).minFolk + 40 }
        val first = pickWeighted(near, weights, rng)
        val rest = near.filter { it.id != first.id }
        return listOfNotNull(first, rest.firstOrNull())
    }

    private fun pickWeighted(sites: List<Site>, weights: List<Int>, rng: Random): Site {
        var pick = rng.nextInt(weights.sum().coerceAtLeast(1))
        sites.forEachIndexed { index, site ->
            pick -= weights[index]
            if (pick < 0) return site
        }
        return sites.first()
    }

    // ---------------------------------------------------------------- census

    /**
     * What the place's people are, by their years, their work, and where they
     * came from — all of it derived, nothing stored per soul.
     */
    fun censusOf(site: Site, ledger: SettlementLedger): PopulationCensus {
        val folk = ledger.folkOf(site).coerceAtLeast(0)
        val hist = state[site.id] ?: SiteHistory()
        val rng = Random(seed * 15485863L + site.id * 31L)
        val stage = stageOf(site, folk)
        val children = (folk * (28 + rng.nextInt(6)) / 100)
        val elders = (folk * (10 + rng.nextInt(5) + hist.fadingYears * 2) / 100)
            .coerceAtMost(folk - children)
        val adults = (folk - children - elders).coerceAtLeast(0)
        val occupations = occupationShares(stage, folk)
        val arrived = hist.arrived.coerceAtMost(folk)
        return PopulationCensus(
            siteId = site.id,
            total = folk,
            children = children,
            adults = adults,
            elders = elders,
            occupations = occupations,
            natives = (folk - arrived).coerceAtLeast(0),
            arrived = arrived,
            wealthTier = when (stage) {
                SettlementStage.CAMP -> "scraping by"
                SettlementStage.VILLAGE -> "lean but whole"
                SettlementStage.TOWN -> "comfortable"
                SettlementStage.CITY -> "rich"
                SettlementStage.CAPITAL -> "opulent"
            }
        )
    }

    /** Work a place lives by: the land decides, the stage decides, the seed tilts. */
    private fun occupationShares(stage: SettlementStage, folk: Int): List<Pair<String, Int>> {
        val shares = when (stage) {
            SettlementStage.CAMP -> listOf("working the ground" to 50, "crafting" to 12, "trading" to 4, "carrying and hauling" to 22, "keeping the peace" to 4)
            SettlementStage.VILLAGE -> listOf("working the ground" to 55, "crafting" to 14, "trading" to 6, "carrying and hauling" to 15, "keeping the peace" to 4)
            SettlementStage.TOWN -> listOf("working the ground" to 40, "crafting" to 22, "trading" to 12, "carrying and hauling" to 14, "keeping the peace" to 7)
            else -> listOf("working the ground" to 28, "crafting" to 26, "trading" to 18, "carrying and hauling" to 14, "keeping the peace" to 9)
        }
        var used = 0
        val counts = shares.mapIndexed { index, (label, share) ->
            val count = if (index == shares.lastIndex) (folk - used).coerceAtLeast(0)
            else (folk * share / 100).also { used += it }
            label to count
        }
        return counts.filter { it.second > 0 }.sortedByDescending { it.second }
    }

    // ------------------------------------------------------- notable souls

    /**
     * The name a growing place remembers: a local notable raised from the
     * population the year the work was done — one of few, in a world where
     * most stay anonymous.
     */
    private fun notableFor(seed: Long, siteId: Int, year: Int): String {
        val rng = Random(seed * 8191L + siteId * 104729L + year * 31L)
        val name = NOTABLE_FIRST[rng.nextInt(NOTABLE_FIRST.size)]
        val epithet = NOTABLE_EPITHET[rng.nextInt(NOTABLE_EPITHET.size)]
        return "$name the $epithet"
    }

    // ------------------------------------------------------------------ save

    /** The layer's mutable residue: per-place history, events, and the economy. */
    fun encode(): String {
        val places = state.entries.joinToString(ENTRY) { (id, hist) ->
            "P$id=${hist.fadingYears}=${if (hist.abandoned) 1 else 0}=${hist.arrived}"
        }
        val log = events.joinToString(ENTRY) { event ->
            "E${event.year}=${event.kind.name}=${event.siteId}=${event.otherSiteId}=${event.count}=${event.text}"
        }
        return "$lastYear\u001F$places\u001F$log\u001F${economy.encode()}"
    }

    companion object {
        /** The deep layer's own arithmetic, tuned once, in one place. */
        const val ABANDON_FLOOR = 8
        const val FADING_WARN = 3
        const val FADING_YEARS = 6
        const val EVENT_LOG_CAP = 400
        const val MIGRANT_SHARE_NUM = 2
        const val MIGRANT_SHARE_DEN = 5
        const val DESTINATION_POOL = 3
        val DESTINATION_SPLIT = listOf(60, 40)

        private const val ENTRY = "\u001E"

        private val NOTABLE_FIRST = listOf(
            "Aldric", "Elira", "Vael", "Maren", "Oswic", "Thera", "Cadmon", "Isolde",
            "Bren", "Halli", "Ryn", "Sefa", "Ulmar", "Wenda", "Corvin", "Liss"
        )
        private val NOTABLE_EPITHET = listOf(
            "Builder", "Steady", "Younger", "Kind", "Stubborn", "Loud", "Quiet",
            "Wise", "Bold", "Patient", "Fair", "Long"
        )

        /** A fresh layer for a fresh world: nothing has happened yet. */
        fun fresh(world: World): HistoricalSimulation =
            HistoricalSimulation(world, mutableMapOf()).also { it.lastYear = world.currentYear }

        /** Wake from the save; a blank string wakes clean, as old saves always have. */
        fun fromSave(world: World, raw: String?): HistoricalSimulation {
            val sim = fresh(world)
            if (raw.isNullOrBlank()) return sim
            val sections = raw.split('\u001F')
            sections.getOrNull(0)?.toIntOrNull()?.let { sim.lastYear = it }
            sections.getOrNull(1)?.takeIf { it.isNotBlank() }?.split(ENTRY)?.forEach { piece ->
                val parts = piece.split("=")
                if (parts.size < 4 || !parts[0].startsWith("P")) return@forEach
                val id = parts[0].removePrefix("P").toIntOrNull() ?: return@forEach
                sim.state[id] = SiteHistory(
                    fadingYears = parts[1].toIntOrNull() ?: 0,
                    abandoned = parts[2] == "1",
                    arrived = parts[3].toIntOrNull() ?: 0
                )
            }
            sections.getOrNull(2)?.takeIf { it.isNotBlank() }?.split(ENTRY)?.forEach { piece ->
                val parts = piece.split("=", limit = 6)
                if (parts.size < 6 || !parts[0].startsWith("E")) return@forEach
                val year = parts[0].removePrefix("E").toIntOrNull() ?: return@forEach
                val kind = AgeEventKind.entries.firstOrNull { it.name == parts[1] } ?: return@forEach
                sim.events += AgeEvent(
                    year = year,
                    kind = kind,
                    siteId = parts[2].toIntOrNull() ?: return@forEach,
                    otherSiteId = parts[3].toIntOrNull() ?: -1,
                    count = parts[4].toIntOrNull() ?: 0,
                    text = parts[5]
                )
            }
            sections.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { sim.economy.applyEncoded(it) }
            return sim
        }
    }
}
