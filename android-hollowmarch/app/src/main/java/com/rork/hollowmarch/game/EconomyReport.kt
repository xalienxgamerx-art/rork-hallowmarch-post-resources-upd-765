package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.Site
import com.rork.hollowmarch.world.World

/**
 * The player-facing voice of the economy. Everything here is a read: the
 * simulation stays the one authority, and this file only translates its
 * state into sentences a traveler could hear in a settlement's street.
 * No arithmetic of production, hunger, trade, or provenance lives here —
 * where a number is needed, an accessor on the simulation provides it.
 *
 * Every claim in a summary traces to saved simulation state: stores,
 * production records, seams, deposits, roads, industries, and the deep
 * history's own event log. Nothing is inferred from potential alone.
 */

/** One kind of food, and how far it would go. */
data class FoodLine(val label: String, val plenty: String)

/** One crop, its stage, and what lies in the barn. */
data class CropLine(val name: String, val state: String, val barn: String?)

/** One bulk good: where it stands, and how it got there. */
data class ResourceLine(val name: String, val state: String)

/** One material, its provenance in a word, and the place it comes from. */
data class MaterialLine(val name: String, val word: String, val from: String?)

/** One trade the place lives by, and how it stands this year. */
data class IndustryLine(val name: String, val status: String)

/** One road's cargo and the place at its other end. */
data class TradeLine(val cargo: String, val partner: String)

/** One workable place in a settlement's keeping: mine, quarry, pit, woods. */
data class WorkLine(val name: String, val state: String, val detail: String?)

/** One entry of the economic timeline, straight from the annals. */
data class Milestone(val year: Int, val text: String)

/** The whole economic census of one settlement, built only from simulation reads. */
data class SettlementEconomy(
    val siteId: Int,
    val identity: String,
    val condition: String,
    val conditionCause: String?,
    val censusLine: String,
    val food: List<FoodLine>,
    val foodNote: String,
    val seeds: List<String>,
    val crops: List<CropLine>,
    val resources: List<ResourceLine>,
    val materials: List<MaterialLine>,
    val industries: List<IndustryLine>,
    val exports: List<TradeLine>,
    val imports: List<TradeLine>,
    val roads: List<String>,
    val works: List<WorkLine>,
    val history: List<String>,
    val milestones: List<Milestone>
)

/**
 * Build the census of [site] from the authoritative layers alone: the
 * economy's own state, the deep history's event log, and the folk ledger.
 * Nothing is recomputed; nothing is invented.
 */
fun settlementEconomyOf(
    site: Site,
    world: World,
    economy: EconomySimulation,
    history: HistoricalSimulation,
    ledger: SettlementLedger
): SettlementEconomy {
    val folk = ledger.folkOf(site).coerceAtLeast(0)
    val year = world.currentYear
    val events = history.eventLog()
    val siteEvents = events.filter { it.siteId == site.id }
    val foodLedger = economy.foodLedgerOf(site, folk)
    val production = economy.productionOf(site)
    val flows = economy.tradeFlowsOf(site, ledger, world.sites)
    val labels = economy.specializationOf(site, folk)
    val condition = conditionOf(site, economy, foodLedger, folk, flows)

    return SettlementEconomy(
        siteId = site.id,
        identity = identityOf(labels, site),
        condition = condition.first,
        conditionCause = condition.second,
        censusLine = censusLine(history.censusOf(site, ledger)),
        food = foodLines(foodLedger),
        foodNote = foodNote(foodLedger, folk),
        seeds = seedLines(economy, site),
        crops = cropLines(economy, site),
        resources = resourceLines(economy, site, production, flows),
        materials = materialLines(economy, site, ledger, world.sites),
        industries = industryLines(economy, site, production, foodLedger, flows),
        exports = flows.filter { !it.incoming }.map { TradeLine(cap(cargoWord(it.cargo)), nameOf(world, it.partnerId)) },
        imports = flows.filter { it.incoming }.map { TradeLine(cap(cargoWord(it.cargo)), nameOf(world, it.partnerId)) },
        roads = roadLines(economy, site, siteEvents, world),
        works = workLines(economy, site, production, foodLedger, siteEvents),
        history = historySummary(site, labels, siteEvents, year),
        milestones = siteEvents
            .filter { it.kind in ECONOMIC_ANNALS }
            .sortedBy { it.year }
            .takeLast(30)
            .map { Milestone(it.year, it.text) }
    )
}

// ------------------------------------------------------------------ reading

/** The economic annals the census timeline keeps. */
internal val ECONOMIC_ANNALS = setOf(
    AgeEventKind.CROP_ESTABLISHED, AgeEventKind.CROP_LOST,
    AgeEventKind.RESOURCE_DISCOVERED, AgeEventKind.RESOURCE_DEPLETED,
    AgeEventKind.MINE_OPENED, AgeEventKind.QUARRY_OPENED, AgeEventKind.QUARRY_CLOSED,
    AgeEventKind.INDUSTRY_ESTABLISHED, AgeEventKind.INDUSTRY_DECLINED,
    AgeEventKind.SPECIALIZED, AgeEventKind.ECONOMIC_DECLINE,
    AgeEventKind.TRADE_ROUTE_OPENED, AgeEventKind.TRADE_ROUTE_CLOSED,
    AgeEventKind.TRADE_ROUTE_STRENGTHENED,
    AgeEventKind.HARVEST_FAILURE, AgeEventKind.HARVEST_SURPLUS
)

/** What the place is known by, from the work its folk actually did. */
private fun identityOf(labels: List<String>, site: Site): String =
    if (labels.isEmpty()) {
        "${site.name} lives on what the land gives."
    } else {
        val spoken = labels.joinToString(" and ") { if (it == "trade hub") "trade" else it }
        "${site.name} is known for $spoken."
    }

/** The broad state of the place, and its cause when the state is a hard one. */
private fun conditionOf(
    site: Site,
    economy: EconomySimulation,
    ledger: FoodLedger,
    folk: Int,
    flows: List<TradeFlow>
): Pair<String, String?> {
    val stores = ledger.stores.values.sum()
    val made = ledger.production.values.sum()
    val declined = economy.declinedOf(site)
    val eatsImportedFood = flows.any { flow ->
        flow.incoming && FoodKind.entries.any { it.name == flow.cargo }
    }
    return when {
        economy.famineStreakOf(site) > 0 ->
            "Hungry years" to "the fields have fallen short year upon year"
        folk > 0 && stores == 0 ->
            "Empty granaries" to "nothing is stored against the winter"
        declined.isNotEmpty() ->
            "Declining" to "the ${declined.first().label} work has failed"
        made + stores < ledger.consumption ->
            "Struggling" to "the year's yield will not fill the granary"
        eatsImportedFood && made < ledger.consumption ->
            "Trade dependent" to "the roads bring what the fields do not"
        made > ledger.consumption && stores > ledger.consumption * 2 ->
            "Prosperous" to null
        else -> "Stable" to null
    }
}

/** Population in the census's own words. */
private fun censusLine(census: PopulationCensus): String =
    "${groupCount(census.total)} souls — ${census.children} children, ${census.adults} in their years, " +
        "${census.elders} elders · ${census.natives} born here, ${census.arrived} come-here"

/** The granary, kind by kind, in words that say how far each would go. */
private fun foodLines(ledger: FoodLedger): List<FoodLine> =
    FoodKind.entries.mapNotNull { kind ->
        val stored = ledger.stores[kind] ?: 0
        val made = ledger.production[kind] ?: 0
        if (stored == 0 && made == 0) return@mapNotNull null
        val plenty = when {
            stored <= 0 -> "none stored"
            stored >= ledger.consumption -> "plentiful"
            stored * 3 >= ledger.consumption -> "moderate"
            else -> "scarce"
        }
        FoodLine(cap(kind.label), plenty)
    }

/** One sentence about the year's food, from the ledger's own numbers. */
private fun foodNote(ledger: FoodLedger, folk: Int): String = when {
    folk <= 0 -> "No one is left to feed."
    ledger.production.values.sum() >= ledger.consumption ->
        "The settlement produces more food than it eats."
    ledger.stores.values.sum() > ledger.consumption ->
        "The folk eat more than the year yields, and live for now on the stores."
    else -> "The folk eat more than the year yields, and the stores run thin."
}

/** The seed barn, crop by crop — or the want of one. */
private fun seedLines(economy: EconomySimulation, site: Site): List<String> {
    val barn = economy.cropStockOf(site)
    val lines = barn.entries.filter { it.value > 0 }
        .sortedBy { it.key.name }
        .map { "${cap(it.key.label)} — ${it.value} in the seed barn" }
    if (lines.isNotEmpty()) return lines
    return if (economy.cropsOf(site).isNotEmpty()) {
        listOf("The seed barn stands empty — famine has taken its share.")
    } else {
        emptyList()
    }
}

/** The fields, crop by crop, in the stage the years have brought them to. */
private fun cropLines(economy: EconomySimulation, site: Site): List<CropLine> {
    val barn = economy.cropStockOf(site)
    return economy.cropsOf(site).entries.sortedBy { it.key.name }.map { (crop, stage) ->
        CropLine(
            name = cap(crop.label),
            state = when (stage) {
                CropStage.ESTABLISHED -> "Established"
                CropStage.CULTIVATED -> "Cultivated"
                CropStage.UNKNOWN -> "Not grown here"
            },
            barn = barn[crop]?.takeIf { it > 0 }?.let { "$it in the barn" }
        )
    }
}

/** The yard and the ground: what is cut, dug, stocked, or brought in by road. */
private fun resourceLines(
    economy: EconomySimulation,
    site: Site,
    production: Map<ResourceKind, Int>,
    flows: List<TradeFlow>
): List<ResourceLine> {
    val seams = economy.seamsOf(site)
    val lines = mutableListOf<ResourceLine>()
    val stock = { kind: ResourceKind -> economy.stockOf(site, kind) }
    val roadCarries = { kind: ResourceKind -> flows.any { it.incoming && it.cargo == kind.name } }
    // timber: the woods and the yard
    if ((production[ResourceKind.TIMBER] ?: 0) > 0) {
        lines += ResourceLine("Timber", "cut this year — ${stock(ResourceKind.TIMBER)} in the yard")
    } else if (stock(ResourceKind.TIMBER) > 0) {
        lines += ResourceLine("Timber", "${stock(ResourceKind.TIMBER)} in the yard")
    } else if (roadCarries(ResourceKind.TIMBER)) {
        lines += ResourceLine("Timber", "brought in by road")
    } else if (economy.timberOf(site) <= 0 && economy.potentialOf(site).timber > 0.3f) {
        lines += ResourceLine("Timber", "the woods are cut out")
    }
    // clay: a renewable bed
    if ((production[ResourceKind.CLAY] ?: 0) > 0) {
        lines += ResourceLine("Clay", "dug this year — ${stock(ResourceKind.CLAY)} stocked")
    } else if (stock(ResourceKind.CLAY) > 0) {
        lines += ResourceLine("Clay", "${stock(ResourceKind.CLAY)} stocked")
    } else if (roadCarries(ResourceKind.CLAY)) {
        lines += ResourceLine("Clay", "brought in by road")
    }
    // stone and salt: seams that run out
    val stoneLeft = seams.stone
    if ((production[ResourceKind.STONE] ?: 0) > 0) {
        lines += ResourceLine("Stone", "quarried this year — $stoneLeft still in the seam")
    } else if (stock(ResourceKind.STONE) > 0) {
        lines += ResourceLine("Stone", "${stock(ResourceKind.STONE)} stocked")
    } else if (economy.quarryOpenedOf(site)) {
        lines += ResourceLine("Stone", "the quarry is worked out")
    } else if (stoneLeft > 0) {
        lines += ResourceLine("Stone", "lies in the high ground, unquarried")
    } else if (roadCarries(ResourceKind.STONE)) {
        lines += ResourceLine("Stone", "brought in by road")
    }
    val saltLeft = seams.salt
    if ((production[ResourceKind.SALT] ?: 0) > 0) {
        lines += ResourceLine("Salt", "scraped this year — $saltLeft left in the pans")
    } else if (stock(ResourceKind.SALT) > 0) {
        lines += ResourceLine("Salt", "${stock(ResourceKind.SALT)} stocked")
    } else if (economy.saltOpenedOf(site)) {
        lines += ResourceLine("Salt", "the pans yield no more")
    } else if (saltLeft > 0) {
        lines += ResourceLine("Salt", "the coast salt is untouched")
    } else if (roadCarries(ResourceKind.SALT)) {
        lines += ResourceLine("Salt", "brought in by road")
    }
    // ore waiting at the forge-yard
    economy.oreStockOf(site).entries.filter { it.value > 0 }.sortedBy { it.key.name }.forEach {
        lines += ResourceLine("Ore (${cap(it.key.label)})", "${it.value} awaiting the forge")
    }
    return lines
}

/** The materials a place can put its hands on, by the corrected provenance. */
private fun materialLines(
    economy: EconomySimulation,
    site: Site,
    ledger: SettlementLedger,
    sites: List<Site>
): List<MaterialLine> {
    val declined = economy.declinedOf(site)
    val knownOnce = economy.depositsOf(site).filter { it.discovered }.map { it.material }.toSet()
    val local = mutableListOf<MaterialLine>()
    val imported = mutableListOf<MaterialLine>()
    val scarce = mutableListOf<MaterialLine>()
    economy.materialReport(site, ledger, sites).forEach { report ->
        when (report.source) {
            MaterialSource.LOCAL ->
                local += MaterialLine(cap(report.material.label), "local", null)
            MaterialSource.IMPORTED ->
                imported += MaterialLine(cap(report.material.label), "imported", report.fromSiteName.ifBlank { null })
            MaterialSource.SCARCE ->
                // a material nobody here has heard of is not listed at all;
                // one the place once worked and lost is named scarce
                if (report.material in declined || report.material in knownOnce) {
                    scarce += MaterialLine(cap(report.material.label), "scarce", null)
                }
        }
    }
    return (local.sortedBy { it.name } + imported.sortedBy { it.name } + scarce.sortedBy { it.name })
}

/** The trades the place lives by, and how each stands. */
private fun industryLines(
    economy: EconomySimulation,
    site: Site,
    production: Map<ResourceKind, Int>,
    foodLedger: FoodLedger,
    flows: List<TradeFlow>
): List<IndustryLine> {
    val lines = mutableListOf<IndustryLine>()
    val made = { kind: FoodKind -> (foodLedger.production[kind] ?: 0) > 0 }
    val cut = { kind: ResourceKind -> (production[kind] ?: 0) > 0 }
    if (made(FoodKind.GRAIN) || made(FoodKind.PRODUCE)) lines += IndustryLine("Farming", "Active")
    if (made(FoodKind.FISH)) lines += IndustryLine("Fishing", "Active")
    if (made(FoodKind.MEAT)) lines += IndustryLine("Herding and the hunt", "Active")
    if (cut(ResourceKind.TIMBER)) {
        lines += IndustryLine("Logging", "Active")
    } else if (Material.ASHWOOD in economy.declinedOf(site)) {
        lines += IndustryLine("Logging", "Declined")
    }
    if (cut(ResourceKind.CLAY)) lines += IndustryLine("Clay working", "Active")
    if (cut(ResourceKind.STONE)) {
        lines += IndustryLine("Quarrying", "Active")
    } else if (economy.quarryOpenedOf(site)) {
        lines += IndustryLine("Quarrying", "Exhausted")
    }
    if (cut(ResourceKind.SALT)) {
        lines += IndustryLine("Salting", "Active")
    } else if (economy.saltOpenedOf(site)) {
        lines += IndustryLine("Salting", "Exhausted")
    }
    if (cut(ResourceKind.ORE)) {
        lines += IndustryLine("Mining", "Active")
    } else if (economy.depositsOf(site).any { it.worked }) {
        val anyLeft = economy.depositsOf(site).any { it.worked && it.remaining > 0 }
        lines += IndustryLine("Mining", if (anyLeft) "Idle" else "Exhausted")
    }
    economy.industriesOf(site).filter { it !in economy.declinedOf(site) }.sortedBy { it.name }.take(4).forEach { material ->
        lines += IndustryLine("${material.label} work".replaceFirstChar { it.uppercase() }, "Established")
    }
    economy.declinedOf(site).sortedBy { it.name }.take(3).forEach { material ->
        lines += IndustryLine("${material.label} work".replaceFirstChar { it.uppercase() }, "Declined")
    }
    if (flows.size >= 2) lines += IndustryLine("Trade", "Active")
    return lines
}

/** The roads a place keeps, and what the annals remember of the ones it lost. */
private fun roadLines(
    economy: EconomySimulation,
    site: Site,
    siteEvents: List<AgeEvent>,
    world: World
): List<String> {
    val lines = mutableListOf<String>()
    economy.routesOf(site).filter { it.since > 0 }.forEach { route ->
        val partner = nameOf(world, if (route.fromId == site.id) route.toId else route.fromId)
        val cargo = cargoWord(route.cargo)
        lines += if (route.strength >= EconomySimulation.TRADE_STRENGTH_MILESTONE) {
            "The road to $partner is an old road — $cargo has crossed it since year ${route.since}."
        } else {
            "A road to $partner carries $cargo — opened in year ${route.since}."
        }
    }
    siteEvents.filter { it.kind == AgeEventKind.TRADE_ROUTE_CLOSED }
        .sortedBy { it.year }
        .takeLast(2)
        .forEach { event ->
            val partner = nameOf(world, event.otherSiteId)
            lines += "The road to $partner fell out of use in year ${event.year}."
        }
    return lines
}

/** The workable places in a settlement's keeping, with their true state. */
private fun workLines(
    economy: EconomySimulation,
    site: Site,
    production: Map<ResourceKind, Int>,
    foodLedger: FoodLedger,
    siteEvents: List<AgeEvent>
): List<WorkLine> {
    val lines = mutableListOf<WorkLine>()
    val cut = { kind: ResourceKind -> (production[kind] ?: 0) > 0 }
    // the mines: one line per known deposit
    economy.depositsOf(site).filter { it.discovered }.sortedBy { it.material.name }.forEach { deposit ->
        val material = deposit.material.label
        when {
            deposit.worked && deposit.remaining > 0 -> {
                val opened = eventYear(siteEvents, AgeEventKind.MINE_OPENED, material)
                lines += WorkLine(
                    "${material.replaceFirstChar { it.uppercase() }} mine", "Active",
                    opened?.let { "Opened in year $it" }
                )
            }
            deposit.worked -> {
                val closed = eventYear(siteEvents, AgeEventKind.RESOURCE_DEPLETED, material)
                lines += WorkLine(
                    "Old ${material} mine", "Worked out",
                    closed?.let { "Worked out in year $it" }
                )
            }
            else -> {
                val found = eventYear(siteEvents, AgeEventKind.RESOURCE_DISCOVERED, material)
                lines += WorkLine(
                    "${material.replaceFirstChar { it.uppercase() }} diggings", "Known, not yet opened",
                    found?.let { "Found in year $it" }
                )
            }
        }
    }
    // the quarry
    if (cut(ResourceKind.STONE)) {
        lines += WorkLine("Stone quarry", "Active", eventYear(siteEvents, AgeEventKind.QUARRY_OPENED, "quarry")?.let { "Opened in year $it" })
    } else if (economy.quarryOpenedOf(site)) {
        lines += WorkLine("Old quarry", "Abandoned", eventYear(siteEvents, AgeEventKind.QUARRY_CLOSED, "quarry")?.let { "Closed in year $it" })
    }
    // the salt pans
    if (cut(ResourceKind.SALT)) {
        lines += WorkLine("Salt pans", "Active", null)
    } else if (economy.saltOpenedOf(site)) {
        lines += WorkLine("Old salt pans", "Abandoned", null)
    }
    // the clay pits
    if (cut(ResourceKind.CLAY)) lines += WorkLine("Clay pits", "Active", null)
    // the woods
    if (cut(ResourceKind.TIMBER)) {
        lines += WorkLine("The woods", "Being cut", null)
    } else if (economy.timberOf(site) <= 0 && economy.potentialOf(site).timber > 0.3f) {
        lines += WorkLine("The woods", "Cut out", null)
    }
    // the fields
    val grown = economy.cropsOf(site).filterValues { it != CropStage.UNKNOWN }
    if (grown.isNotEmpty()) {
        lines += WorkLine("The fields", "Sown with " + grown.keys.sortedBy { it.name }.joinToString { it.label }, null)
    }
    // the waters
    if ((foodLedger.production[FoodKind.FISH] ?: 0) > 0) lines += WorkLine("The waters", "Fished", null)
    return lines
}

/** The settlement's economic story, every sentence an event of the annals. */
private fun historySummary(
    site: Site,
    labels: List<String>,
    siteEvents: List<AgeEvent>,
    year: Int
): List<String> {
    val intro = if (labels.isEmpty()) {
        "${site.name} lives on what the land gives."
    } else {
        val spoken = labels.joinToString(" and ") { if (it == "trade hub") "trade" else it }
        "${site.name} is known for $spoken."
    }
    val annals = siteEvents.filter { it.kind in ECONOMIC_ANNALS }.sortedBy { it.year }
    if (annals.isEmpty()) return listOf(intro)
    val lines = mutableListOf(intro)
    val first = annals.first()
    lines += when {
        first.year == year -> "This year, ${first.text}"
        else -> "In year ${first.year}, ${first.text}"
    }
    annals.takeLast(4).filter { it !== first }.forEach { event ->
        lines += when {
            event.year == year -> "This year, ${event.text}"
            else -> "In year ${event.year}, ${event.text}"
        }
    }
    return lines
}

// ------------------------------------------------------------------ helpers

/** The year an event of a kind mentioning [needle] last happened at the site. */
private fun eventYear(events: List<AgeEvent>, kind: AgeEventKind, needle: String): Int? =
    events.lastOrNull { it.kind == kind && it.text.contains(needle, ignoreCase = true) }?.year

/** The name of a site by id, or "afar" when the place is gone. */
private fun nameOf(world: World, siteId: Int): String =
    world.siteOrNull(siteId)?.name ?: "afar"

/** A cargo's name in the player's tongue, from whatever registry it belongs to. */
internal fun cargoWord(cargo: String): String = when {
    FoodKind.entries.any { it.name == cargo } -> FoodKind.entries.first { it.name == cargo }.label
    ResourceKind.entries.any { it.name == cargo } -> ResourceKind.entries.first { it.name == cargo }.label
    Material.entries.any { it.name == cargo } -> Material.entries.first { it.name == cargo }.label
    else -> cargo.lowercase()
}

/** One word, sat upright at the start of its sentence. */
private fun cap(word: String): String = word.replaceFirstChar { it.uppercase() }

/** A count with its thousands marked. */
private fun groupCount(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
