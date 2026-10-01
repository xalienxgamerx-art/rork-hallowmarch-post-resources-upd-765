package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.Biome
import com.rork.hollowmarch.world.Site
import com.rork.hollowmarch.world.World
import com.rork.hollowmarch.world.isSettlement
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The economy layer beneath the deep history. It is a consumer of the world,
 * not a second world-maker: the land each settlement stands on is read from
 * the province's own terrain map — the same heights, wetness, rivers and
 * biomes worldgen drew — never re-invented from private noise.
 *
 * Four layers are kept distinct throughout:
 *
 *   potential   what the authoritative land could give
 *   production  what hands actually took this year
 *   stock       what the place holds in yard, granary and forge
 *   trade       what actually moves along a road between two places
 *
 * A high potential with no folk produces nothing; a discovered deposit no one
 * works yields no metal; a depleted seam cannot keep a quarry alive. Aggregate
 * throughout — no individual farms, pits, wagons or merchants — and the seed's
 * own: same seed, same world, same years, same ledgers forever.
 *
 * The mutable residue — stocks, crops, seams, deposits, roads, the last
 * economic identity — rides the history save's fourth section. Everything
 * derived from the world is recomputed, never saved.
 */

// ------------------------------------------------------------------ terrain

/**
 * One settlement's stretch of land, read off the province's own terrain: the
 * elevation, wetness and biome worldgen stored, with river distance, local
 * relief and the derived earth measured against that same ground.
 */
data class LandProfile(
    val elevation: Float,
    val roughness: Float,
    val moisture: Float,
    val forest: Float,
    val water: Float,
    val soil: Float,
    val warmth: Float,
    val saltBand: Float,
    val biome: Biome = Biome.DOWNS
)

/** What the land around a place is like, in the words the chronicle uses. */
enum class LandCharacter(val label: String) {
    VALLEY("river valley"),
    PLAIN("open plain"),
    FOREST("deep forest"),
    WETLAND("wetland"),
    HILLS("hill country"),
    MOUNTAIN("high mountains")
}

/** The broad resources the land may offer, and whether they come back. */
enum class ResourceKind(val label: String, val renewable: Boolean) {
    GRAIN("grain land", true),
    GRAZING("pasture", true),
    GAME("game", true),
    FISH("fisheries", true),
    TIMBER("timber", true),
    CLAY("clay beds", true),
    STONE("quarry stone", false),
    SALT("salt", false),
    GATHERED("wild gleanings", true),
    ORE("ore", false)
}

/** A 0..1 answer per resource, read from the authoritative land. */
data class ResourcePotential(
    val grain: Float,
    val grazing: Float,
    val game: Float,
    val fish: Float,
    val timber: Float,
    val clay: Float,
    val stone: Float,
    val salt: Float,
    val gathered: Float
) {
    operator fun get(kind: ResourceKind): Float = when (kind) {
        ResourceKind.GRAIN -> grain
        ResourceKind.GRAZING -> grazing
        ResourceKind.GAME -> game
        ResourceKind.FISH -> fish
        ResourceKind.TIMBER -> timber
        ResourceKind.CLAY -> clay
        ResourceKind.STONE -> stone
        ResourceKind.SALT -> salt
        ResourceKind.GATHERED -> gathered
        ResourceKind.ORE -> 0f // ore is a matter of deposits, not of open land
    }
}

/**
 * The province's ecology: every settlement's land, read from the terrain map
 * the world itself was made with. Pure — the same world always reads the same
 * land, and no economic terrain exists apart from the world's.
 */
object Ecology {

    /** How near a river must run to water a place's fields, in province steps. */
    private const val RIVER_REACH = 0.02f

    /** How far from the shore sea-salt still rides the wind, in province steps. */
    private const val SHORE_REACH = 0.03f

    /** The land one settlement stands on. */
    fun profileOf(world: World, site: Site): LandProfile {
        val terrain = world.terrain
        val elevation = terrain.heightAt(site.x, site.y)
        val moisture = terrain.moistureAt(site.x, site.y)
        val biome = terrain.biomeAt(site.x, site.y)
        // local relief: how fast the authoritative ground rises around the place
        val step = 1.5f / terrain.size
        val around = listOf(
            terrain.heightAt(site.x + step, site.y),
            terrain.heightAt(site.x - step, site.y),
            terrain.heightAt(site.x, site.y + step),
            terrain.heightAt(site.x, site.y - step)
        )
        val roughness = (around.sumOf { abs(it - elevation).toDouble() } / around.size / 0.07f)
            .toFloat().coerceIn(0f, 1f)
        // the province's own waters
        val river = terrain.rivers.maxOfOrNull { r ->
            val nearest = r.points.minOf { p ->
                val dx = p.x - site.x
                val dy = p.y - site.y
                sqrt(dx * dx + dy * dy)
            }
            (RIVER_REACH - nearest) / RIVER_REACH
        } ?: 0f
        // shore and marsh: the map's own salt and standing water
        var shore = 0
        for (i in 0 until 8) {
            val ang = i * 0.7853982f
            val b = terrain.biomeAt(
                site.x + cosOf(ang) * SHORE_REACH,
                site.y + sinOf(ang) * SHORE_REACH
            )
            if (b == Biome.OCEAN || b == Biome.MARSH) shore++
        }
        val shoreShare = shore / 8f
        val water = maxOf(
            river,
            if (biome == Biome.MARSH) 0.85f else 0f,
            if (biome == Biome.OCEAN) 1f else 0f,
            if (biome == Biome.FOREST) moisture * 0.30f else moisture * 0.20f
        ).coerceIn(0f, 1f)
        // woodland density follows the authoritative classification and wetness;
        // only the map's own forests stand dense
        val forest = when (biome) {
            Biome.FOREST -> 0.55f + 0.45f * moisture
            Biome.MARSH -> 0.22f + 0.20f * moisture
            Biome.HILLS -> 0.20f + 0.25f * moisture
            Biome.MOOR -> 0.15f + 0.15f * moisture
            Biome.DOWNS -> 0.08f + 0.10f * moisture
            Biome.PEAK -> 0.05f
            Biome.OCEAN -> 0f
        }.coerceIn(0f, 1f)
        // the earth: a derivation, but from the authoritative ground alone
        val soil = if (biome == Biome.OCEAN) {
            0f
        } else {
            ((1f - elevation * 0.7f) * (0.45f + moisture * 0.55f) * (0.72f + river.coerceIn(0f, 1f) * 0.4f))
                .coerceIn(0f, 1f)
        }
        val warmth = (0.22f + 0.55f * site.y + 0.10f * (1f - elevation)).coerceIn(0f, 1f)
        val saltBand = maxOf(if (biome == Biome.MARSH) 0.75f else 0f, shoreShare * shoreShare)
            .coerceIn(0f, 1f)
        return LandProfile(elevation, roughness, moisture, forest, water, soil, warmth, saltBand, biome)
    }

    /** What the land is like, in plain words. */
    fun characterOf(p: LandProfile): LandCharacter = when {
        p.biome == Biome.OCEAN || p.biome == Biome.MARSH -> LandCharacter.WETLAND
        p.elevation > 0.68f && p.roughness > 0.55f -> LandCharacter.MOUNTAIN
        p.biome == Biome.PEAK -> LandCharacter.MOUNTAIN
        p.elevation > 0.55f || p.roughness > 0.45f || p.biome == Biome.HILLS -> LandCharacter.HILLS
        p.water > 0.55f && p.elevation < 0.40f -> LandCharacter.WETLAND
        p.forest > 0.62f || p.biome == Biome.FOREST -> LandCharacter.FOREST
        p.water > 0.30f && p.soil > 0.50f -> LandCharacter.VALLEY
        else -> LandCharacter.PLAIN
    }

    /** What the land could yield, if hands worked it. Potential, never production. */
    fun potentialOf(p: LandProfile): ResourcePotential {
        val grain = (p.soil * (1f - p.roughness * 0.55f) * band(p.warmth, 0.30f, 0.85f))
            .coerceIn(0f, 1f)
        val grazing = ((1f - p.forest * 0.8f) * (1f - p.roughness * 0.7f) *
            band(p.warmth, 0.20f, 0.95f) * (0.4f + p.moisture * 0.6f)).coerceIn(0f, 1f)
        val game = (p.forest * 0.75f + p.roughness * 0.25f + p.water * 0.1f).coerceIn(0f, 1f)
        val fish = p.water
        val timber = p.forest
        val clay = ((p.water * 0.6f + p.soil * 0.4f) * (1f - p.roughness * 0.5f)).coerceIn(0f, 1f)
        val stone = (p.elevation * 0.65f + p.roughness * 0.55f).coerceIn(0f, 1f)
        val salt = p.saltBand
        val gathered = (p.forest * 0.5f + p.water * 0.25f + p.soil * 0.25f).coerceIn(0f, 1f)
        return ResourcePotential(grain, grazing, game, fish, timber, clay, stone, salt, gathered)
    }

    /** 1 inside a band, falling away softly outside it. */
    private fun band(v: Float, lo: Float, hi: Float): Float = when {
        v in lo..hi -> 1f
        v < lo -> (1f - (lo - v) / 0.15f).coerceIn(0f, 1f)
        else -> (1f - (v - hi) / 0.15f).coerceIn(0f, 1f)
    }

    private fun cosOf(a: Float): Float = kotlin.math.cos(a)
    private fun sinOf(a: Float): Float = kotlin.math.sin(a)
}

// -------------------------------------------------------------------- crops

/**
 * The five crops the province knows, and what each asks of the land. A crop
 * sown on land that cannot feed it never takes root.
 */
enum class CropKind(
    val label: String,
    val minWarmth: Float,
    val maxWarmth: Float,
    val minMoisture: Float,
    val maxMoisture: Float,
    val minSoil: Float
) {
    GRAIN("grain", 0.30f, 0.80f, 0.25f, 0.75f, 0.35f),
    BARLEY("hardy barley", 0.15f, 0.70f, 0.15f, 0.65f, 0.22f),
    ROOTS("roots", 0.10f, 0.75f, 0.20f, 0.80f, 0.20f),
    FRUIT("orchard fruit", 0.40f, 0.95f, 0.30f, 0.70f, 0.40f),
    GREENS("greens and fiber", 0.30f, 0.85f, 0.35f, 0.85f, 0.30f);

    /** How well this crop would do on [p], 0..1 — pure from the land. */
    fun suitability(p: LandProfile): Float {
        val warm = softBand(p.warmth, minWarmth, maxWarmth)
        val wet = softBand(p.moisture, minMoisture, maxMoisture)
        val dirt = if (p.soil >= minSoil) {
            0.6f + 0.4f * ((p.soil - minSoil) / (1f - minSoil).coerceAtLeast(0.01f))
        } else {
            p.soil / minSoil.coerceAtLeast(0.01f) * 0.5f
        }
        return (warm * wet * dirt).coerceIn(0f, 1f)
    }

    private fun softBand(v: Float, lo: Float, hi: Float): Float = when {
        v in lo..hi -> 1f
        v < lo -> (1f - (lo - v) / 0.15f).coerceIn(0f, 1f)
        else -> (1f - (v - hi) / 0.15f).coerceIn(0f, 1f)
    }
}

/** Where a crop stands in a place's fields. */
enum class CropStage { UNKNOWN, CULTIVATED, ESTABLISHED }

/**
 * The broad stores a kitchen draws from. Crops keep their own names until the
 * threshing floor; here they become the food the folk actually eat.
 */
enum class FoodKind(val label: String) {
    GRAIN("grain"),
    MEAT("meat"),
    FISH("fish"),
    GATHERED("gleanings"),
    PRODUCE("garden produce")
}

// ------------------------------------------------------------------ deposits

/** A pocket of metal in the ground: how much it held, how much is left, and whether anyone ever worked it. */
data class DepositState(
    val material: Material,
    val richness: Int,
    val remaining: Int,
    val discovered: Boolean,
    val worked: Boolean = false
)

/** A trade road between two places, carrying one cargo — food, bulk goods, or a material. */
data class TradeRoute(
    val fromId: Int,
    val toId: Int,
    val cargo: String,
    val since: Int = 0,
    val strength: Int = 0,
    val lastQty: Int = 0
) {
    val key: String get() = "${minOf(fromId, toId)}>${maxOf(fromId, toId)}:$cargo"
    /** The cargo as a bulk resource, when it is one. */
    val resource: ResourceKind? get() = ResourceKind.entries.firstOrNull { it.name == cargo }
    /** The cargo as a worked material, when it is one. */
    val material: Material? get() = Material.entries.firstOrNull { it.name == cargo }
}

/** Where a settlement's material comes from, in one word. */
enum class MaterialSource { LOCAL, IMPORTED, SCARCE }

/** The answer to "can this place obtain this material, and from where?". */
data class MaterialProvenance(
    val material: Material,
    val source: MaterialSource,
    val fromSiteId: Int = -1,
    val fromSiteName: String = ""
)

/** What the roads actually carry for one place, and which way the wagons run. */
data class TradeFlow(val cargo: String, val partnerId: Int, val incoming: Boolean)

/** What remains in the ground at one place: seams of stone and salt, the bed of clay. */
data class Seams(val stone: Int, val salt: Int, val clay: Int)

/** The granary's own ledger: stores by kind, the year's work, the folk's need. */
data class FoodLedger(
    val stores: Map<FoodKind, Int>,
    val production: Map<FoodKind, Int>,
    val consumption: Int
)

// ------------------------------------------------------------- the simulation

/**
 * The living economy of every settlement, turned year by year beneath the
 * folk ledger. Pure in its rolls: every chance is drawn from the seed, the
 * place, and the year — never from luck. Potential is read from the world;
 * everything after that, the folk must actually do.
 */
class EconomySimulation private constructor(private val world: World) {

    private val geography = MaterialGeography(world)

    // derived from the authoritative terrain, never saved
    private val profiles = mutableMapOf<Int, LandProfile>()
    private val potentials = mutableMapOf<Int, ResourcePotential>()

    // mutable residue, saved
    private val states = mutableMapOf<Int, EcoState>()

    /**
     * Trade partners are a pure function of the immutable site list — positions
     * never change — so every settlement's two nearest steads in reach are
     * coined once, in the very order the old per-year whole-province scan
     * produced. Same walk, same ledgers, one pass instead of one per year.
     */
    private var tradeIndex: Pair<Map<Int, List<Site>>, Map<Int, Site>>? = null
    private var tradeIndexSource: List<Site>? = null

    /** One place's economic residue: fields, woods, yards, granary, roads. */
    private class EcoState(val siteId: Int) {
        // forestry: the standing woods, and the memory of what they were
        var timber: Int = 0
        var timberInit: Int = 0
        var timberGone: Boolean = false
        var timberCut: Int = 0
        // the waters and the game feel the hand that works them
        var fishPressure: Int = 0 // thousandths
        var gamePressure: Int = 0 // thousandths
        // seams and beds in the ground: what is left to take
        var stoneSeam: Int = 0
        var saltSeam: Int = 0
        var clayBed: Int = 0
        var clayInit: Int = 0
        // stocks: the yard, the granary, the seed barn, the forge store
        val stock = LinkedHashMap<ResourceKind, Int>() // TIMBER, CLAY, STONE, SALT on hand
        val cropStock = LinkedHashMap<CropKind, Int>() // harvested, awaiting the threshing floor
        val food = LinkedHashMap<FoodKind, Int>() // the broad stores
        val oreStock = LinkedHashMap<Material, Int>() // dug ore, awaiting the forge
        val materialStock = LinkedHashMap<Material, Int>() // worked materials on hand
        // last year's actual production — what hands really took
        val production = LinkedHashMap<ResourceKind, Int>()
        val foodProduction = LinkedHashMap<FoodKind, Int>()
        val materialProduced = LinkedHashMap<Material, Int>()
        var oreExtracted: Int = 0
        // industry memory
        val industries = mutableSetOf<Material>()
        val declined = mutableSetOf<Material>()
        // crops: crop -> years cultivated
        val crops = LinkedHashMap<CropKind, Int>()
        // diggings
        val deposits = mutableListOf<MutableDeposit>()
        // roads: key -> the road's own memory
        val routes = LinkedHashMap<String, RouteState>()
        // identity and hard years
        var labels: List<String>? = null
        var richYear: Boolean = false
        var famineStreak: Int = 0
        var quarryOpened: Boolean = false
        var quarryClosed: Boolean = false
        var saltOpened: Boolean = false
        var saltClosed: Boolean = false
    }

    /** A trade road's own memory: when beaten, how long it has run, what it last carried. */
    private class RouteState(
        val cargo: String,
        var since: Int,
        var strength: Int = 0,
        var lastQty: Int = 0,
        var dry: Int = 0
    )

    /** The working form of a deposit while the economy runs. */
    private class MutableDeposit(
        val material: Material,
        val richness: Int,
        var remaining: Int,
        var discovered: Boolean,
        var announced: Boolean = false
    )

    // ------------------------------------------------------------- land reads

    fun profileOf(site: Site): LandProfile =
        profiles.getOrPut(site.id) { Ecology.profileOf(world, site) }

    fun potentialOf(site: Site): ResourcePotential =
        potentials.getOrPut(site.id) { Ecology.potentialOf(profileOf(site)) }

    fun characterOf(site: Site): LandCharacter = Ecology.characterOf(profileOf(site))

    /** The culture whose region law governs a place's ground. */
    internal fun cultureOf(site: Site): Int =
        world.cultureIdOf(site.id)

    // ------------------------------------------------------------ state reads

    private fun stateOf(site: Site): EcoState = states.getOrPut(site.id) { initState(site) }

    private fun initState(site: Site): EcoState {
        val st = EcoState(site.id)
        val pot = potentialOf(site)
        st.timber = (pot.timber * 1200f).toInt().coerceAtLeast(0)
        st.timberInit = st.timber
        st.clayBed = (pot.clay * 600f).toInt().coerceAtLeast(0)
        st.clayInit = st.clayBed
        st.stoneSeam = (pot.stone * 500f).toInt().coerceAtLeast(0)
        st.saltSeam = if (pot.salt > SALT_SEAM_FLOOR) (pot.salt * 300f).toInt() else 0
        val rng = Random(world.seed * 7919L + site.id * 104729L + 17L)
        val available = geography.available(cultureOf(site)).toSet()
        val common = listOf(
            Material.BOG_IRON, Material.COPPER, Material.IRON,
            Material.LEAD, Material.SILVER, Material.GOLD
        ).filter { it in available }
        val rare = listOf(Material.STAR_IRON, Material.SPIRIT_SILVER, Material.EARTH_BONE)
            .filter { it in available }
        val count = when (characterOf(site)) {
            LandCharacter.MOUNTAIN -> 1 + rng.nextInt(2)
            LandCharacter.HILLS -> rng.nextInt(2)
            else -> if (rng.nextInt(100) < 35) 1 else 0
        }
        if (common.isNotEmpty()) {
            repeat(count) {
                val material = if (rare.isNotEmpty() && rng.nextInt(100) < 22) {
                    rare[rng.nextInt(rare.size)]
                } else {
                    common[rng.nextInt(common.size)]
                }
                val richness = 400 + rng.nextInt(1400)
                st.deposits += MutableDeposit(material, richness, richness, discovered = false)
            }
        }
        return st
    }

    /** The standing wood about a place, in logs — the forest, not the yard. */
    fun timberOf(site: Site): Int = stateOf(site).timber

    /** The cut logs in a place's yard — stock, not standing wood. */
    fun logsOf(site: Site): Int = stockOf(site, ResourceKind.TIMBER)

    /** What the yard holds of a bulk good. */
    fun stockOf(site: Site, kind: ResourceKind): Int = stateOf(site).stock[kind] ?: 0

    /** What stands in a place's granary: all the broad stores together. */
    fun reserveOf(site: Site): Int = stateOf(site).food.values.sum()

    /** A place's broad food stores, by kind. */
    fun foodOf(site: Site): Map<FoodKind, Int> = stateOf(site).food.toMap()

    /** What waits in the seed barn. */
    fun cropStockOf(site: Site): Map<CropKind, Int> = stateOf(site).cropStock.toMap()

    /** Ore dug and waiting for the forge. */
    fun oreStockOf(site: Site): Map<Material, Int> = stateOf(site).oreStock.toMap()

    /** Worked materials on hand. */
    fun materialStockOf(site: Site): Map<Material, Int> = stateOf(site).materialStock.toMap()

    /** What hands actually took last year, per resource — not what the land could give. */
    fun productionOf(site: Site): Map<ResourceKind, Int> = stateOf(site).production.toMap()

    /** How a crop stands in a place's fields. */
    fun cropStageOf(site: Site, crop: CropKind): CropStage {
        val years = stateOf(site).crops[crop] ?: return CropStage.UNKNOWN
        return if (years >= ESTABLISH_YEARS) CropStage.ESTABLISHED else CropStage.CULTIVATED
    }

    /** Every crop's stage at a place. */
    fun cropsOf(site: Site): Map<CropKind, CropStage> =
        CropKind.entries.associateWith { cropStageOf(site, it) }
            .filterValues { it != CropStage.UNKNOWN }

    /** A place's diggings, frozen for reading. */
    fun depositsOf(site: Site): List<DepositState> =
        stateOf(site).deposits.map { DepositState(it.material, it.richness, it.remaining, it.discovered, it.announced) }

    /** The roads a place keeps, parsed for reading, in a settled order. */
    fun routesOf(site: Site): List<TradeRoute> =
        stateOf(site).routes.entries.mapNotNull { (key, r) ->
            parseRoute(key)?.let { TradeRoute(it.fromId, it.toId, r.cargo, r.since, r.strength, r.lastQty) }
        }.sortedBy { it.key }

    /** Last year's food work, by kind. */
    fun foodProductionOf(site: Site): Map<FoodKind, Int> = stateOf(site).foodProduction.toMap()

    /** The granary's ledger: stores by kind, the year's work, the folk's need. */
    fun foodLedgerOf(site: Site, folk: Int): FoodLedger {
        val st = stateOf(site)
        return FoodLedger(st.food.toMap(), st.foodProduction.toMap(), folk * FOOD_NEED)
    }

    /** What a count of folk eats in a year: the granary's own arithmetic. */
    fun foodNeedOf(folk: Int): Int = folk * FOOD_NEED

    /** How many lean years have run against the granary lately. */
    fun famineStreakOf(site: Site): Int = stateOf(site).famineStreak

    /** Whether the last harvest still counts as a fat one. */
    fun richYearOf(site: Site): Boolean = stateOf(site).richYear

    /** The worked trades the place remembers, and those that have failed. */
    fun industriesOf(site: Site): Set<Material> = stateOf(site).industries.toSet()

    /** The trades the place remembers that no longer feed anyone. */
    fun declinedOf(site: Site): Set<Material> = stateOf(site).declined.toSet()

    /** What remains in the ground: seams of stone and salt, the bed of clay. */
    fun seamsOf(site: Site): Seams =
        stateOf(site).let { Seams(it.stoneSeam, it.saltSeam, it.clayBed) }

    /** Whether the high ground above a place was ever quarried, and the salt pans dug. */
    fun quarryOpenedOf(site: Site): Boolean = stateOf(site).quarryOpened

    /** Whether the salt pans were ever scraped. */
    fun saltOpenedOf(site: Site): Boolean = stateOf(site).saltOpened

    /**
     * What the roads actually carry for a place this year, and which way the
     * wagons run — read from the same surplus and want the roads themselves
     * obey, never guessed from potential.
     */
    fun tradeFlowsOf(site: Site, ledger: SettlementLedger, sites: List<Site>): List<TradeFlow> {
        val st = stateOf(site)
        val folk = ledger.folkOf(site).coerceAtLeast(0)
        val byId = sites.associateBy { it.id }
        val flows = mutableListOf<TradeFlow>()
        for (key in st.routes.keys) {
            val route = parseRoute(key) ?: continue
            val otherId = if (route.fromId == site.id) route.toId else route.fromId
            val partner = byId[otherId] ?: continue
            val partnerState = stateOf(partner)
            val folkPartner = ledger.folkOf(partner).coerceAtLeast(0)
            val capacity = (folk + folkPartner) / 4 + 20
            val outgoing = minOf(
                surplusOf(st, route.cargo, folk),
                demandOf(partnerState, route.cargo, folkPartner),
                capacity
            )
            val incoming = minOf(
                surplusOf(partnerState, route.cargo, folkPartner),
                demandOf(st, route.cargo, folk),
                capacity
            )
            if (outgoing >= TRADE_MIN_CARGO && outgoing >= incoming) {
                flows += TradeFlow(route.cargo, partner.id, incoming = false)
            } else if (incoming >= TRADE_MIN_CARGO) {
                flows += TradeFlow(route.cargo, partner.id, incoming = true)
            }
        }
        return flows.sortedWith(compareBy({ it.partnerId }, { it.cargo }))
    }

    /** A place's economic identity, derived from what it actually produces. */
    fun specializationOf(site: Site, folk: Int): List<String> {
        val st = stateOf(site)
        return st.labels ?: labelSet(site, folk, st)
    }

    // ------------------------------------------------------------- the years

    /**
     * Turn the economy forward to [year] for every living settlement. Writes
     * its events into [into] and returns the places whose shortfall sends
     * hungry walkers onto the roads — the count that walks, keyed by site.
     * [folkOverride] lets a caller stand folk where the ledger has none yet;
     * the engine never uses it.
     */
    fun stepYear(
        year: Int,
        sites: List<Site>,
        ledger: SettlementLedger,
        into: MutableList<AgeEvent>,
        folkOverride: Map<Int, Int> = emptyMap()
    ): Map<Int, Int> {
        val hunger = mutableMapOf<Int, Int>()
        val living = sites.filter { it.isSettlement && !it.ruined }.sortedBy { it.id }
        if (tradeIndexSource !== sites) {
            tradeIndex = buildTradeIndex(living)
            tradeIndexSource = sites
        }
        val (partnersBySite, livingById) = tradeIndex!!
        for (site in living) {
            val st = stateOf(site)
            val folk = (folkOverride[site.id] ?: ledger.folkOf(site)).coerceAtLeast(0)
            val pot = potentialOf(site)
            stepCrops(year, site, folk, st, into)
            produce(year, site, folk, st, pot, into)
            stepPressure(folk, st, pot)
            processMaterials(year, site, folk, st, into)
            stepTrade(year, site, st, ledger, into, partnersBySite[site.id].orEmpty(), livingById)
            stepFood(year, site, folk, st, into, hunger)
            stepSpecialization(year, site, folk, st, into)
        }
        return hunger
    }

    /** Crops arrive by seed and trade; abandoned fields go back to brush. */
    private fun stepCrops(year: Int, site: Site, folk: Int, st: EcoState, into: MutableList<AgeEvent>) {
        if (folk < MIN_FIELD_FOLK && st.crops.isNotEmpty()) {
            st.crops.clear()
            st.cropStock.clear()
            into += AgeEvent(
                year, AgeEventKind.CROP_LOST, site.id,
                text = "The fields of ${site.name} went back to brush — too few hands remain to work them."
            )
        }
        val prof = profileOf(site)
        for (crop in CropKind.entries) {
            val suit = crop.suitability(prof)
            val years = st.crops[crop]
            if (years == null) {
                if (suit < ADOPT_FLOOR) continue
                val chance = suit * ADOPT_CHANCE + if (st.routes.isNotEmpty()) 0.05f else 0f
                if (rng(site.id, year, 31 + crop.ordinal).nextFloat() < chance) {
                    st.crops[crop] = 0
                }
            } else {
                st.crops[crop] = years + 1
                if (years + 1 == ESTABLISH_YEARS) {
                    into += AgeEvent(
                        year, AgeEventKind.CROP_ESTABLISHED, site.id,
                        text = "The ${crop.label} fields of ${site.name} have taken root; the place will be known for them."
                    )
                }
            }
        }
    }

    /**
     * The year's work: what hands actually take from the land. Potential sets
     * the ceiling, folk and capability set the take, and the take becomes
     * stock — never a number that vanishes into the year.
     */
    private fun produce(year: Int, site: Site, folk: Int, st: EcoState, pot: ResourcePotential, into: MutableList<AgeEvent>) {
        val hands = folk.coerceAtMost(LAND_FOLK)
        st.production.clear()
        st.foodProduction.clear()
        st.materialProduced.clear()
        st.timberCut = 0
        st.oreExtracted = 0

        // the harvest: each adopted crop yields by its own suitability, the land shared among them
        val grown = st.crops.size
        if (grown > 0 && hands > 0) {
            val prof = profileOf(site)
            for ((crop, years) in st.crops) {
                val stage = if (years >= ESTABLISH_YEARS) 1f else 0.55f
                val yield = (crop.suitability(prof) * stage * hands * CROP_RATE / grown).toInt()
                if (yield > 0) st.cropStock[crop] = (st.cropStock[crop] ?: 0) + yield
            }
        }
        // the threshing floor: harvest becomes stores, a tenth kept back for seed
        for ((crop, qty) in st.cropStock.entries.toList()) {
            val threshed = qty - qty / SEED_SHARE
            if (threshed > 0) {
                val kind = if (crop == CropKind.GRAIN || crop == CropKind.BARLEY) FoodKind.GRAIN else FoodKind.PRODUCE
                st.food[kind] = (st.food[kind] ?: 0) + threshed
                st.foodProduction[kind] = (st.foodProduction[kind] ?: 0) + threshed
            }
            st.cropStock[crop] = qty / SEED_SHARE
        }
        // herds, hunt, waters, gleanings
        val meat = (pot.grazing * hands * MEAT_RATE).toInt()
        val game = (pot.game * (1f - st.gamePressure / 1000f) * hands * GAME_RATE).toInt()
        val fish = (pot.fish * (1f - st.fishPressure / 1000f) * hands * FISH_RATE).toInt()
        val gathered = (pot.gathered * hands * GATHER_RATE).toInt()
        if (meat > 0) {
            st.food[FoodKind.MEAT] = (st.food[FoodKind.MEAT] ?: 0) + meat
            st.foodProduction[FoodKind.MEAT] = (st.foodProduction[FoodKind.MEAT] ?: 0) + meat
        }
        if (game > 0) {
            st.food[FoodKind.MEAT] = (st.food[FoodKind.MEAT] ?: 0) + game
            st.foodProduction[FoodKind.MEAT] = (st.foodProduction[FoodKind.MEAT] ?: 0) + game
            val bone = game / 10
            if (bone > 0) {
                st.materialStock[Material.BONE] = (st.materialStock[Material.BONE] ?: 0) + bone
                st.materialProduced[Material.BONE] = (st.materialProduced[Material.BONE] ?: 0) + bone
            }
        }
        if (fish > 0) {
            st.food[FoodKind.FISH] = (st.food[FoodKind.FISH] ?: 0) + fish
            st.foodProduction[FoodKind.FISH] = (st.foodProduction[FoodKind.FISH] ?: 0) + fish
        }
        if (gathered > 0) {
            st.food[FoodKind.GATHERED] = (st.food[FoodKind.GATHERED] ?: 0) + gathered
            st.foodProduction[FoodKind.GATHERED] = (st.foodProduction[FoodKind.GATHERED] ?: 0) + gathered
        }
        // forestry: the cut, the regrowth, the yard
        if (st.timberInit > 0) {
            val cut = minOf(st.timber, folk / TIMBER_FOLK_PER_LOG)
            st.timber -= cut
            st.timber = minOf(st.timberInit, st.timber + (pot.timber * 60f).toInt().coerceAtLeast(2))
            if (cut > 0) {
                st.stock[ResourceKind.TIMBER] = (st.stock[ResourceKind.TIMBER] ?: 0) + cut
                st.production[ResourceKind.TIMBER] = cut
                st.timberCut = cut
            }
            if (!st.timberGone && st.timberInit >= 300 && st.timber < st.timberInit / 10) {
                st.timberGone = true
                into += AgeEvent(
                    year, AgeEventKind.RESOURCE_DEPLETED, site.id,
                    text = "The woods about ${site.name} are cut out; its folk must look abroad for timber."
                )
            }
        }
        // clay: a renewable bed, dug and refilled by the slow earth
        if (st.clayInit > 0) {
            val take = minOf(st.clayBed, hands / CLAY_FOLK_PER_LOAD)
            st.clayBed = minOf(
                st.clayInit,
                st.clayBed - take + (pot.clay * 30f).toInt().coerceAtLeast(1)
            ).coerceAtLeast(0)
            if (take > 0) {
                st.stock[ResourceKind.CLAY] = (st.stock[ResourceKind.CLAY] ?: 0) + take
                st.production[ResourceKind.CLAY] = take
            }
        }
        // stone: a finite seam, opened by a real crew and closed when it runs out
        if (st.stoneSeam > 0 && folk >= QUARRY_FOLK) {
            val take = minOf(st.stoneSeam, hands / STONE_FOLK_PER_BLOCK)
            if (take > 0) {
                st.stoneSeam -= take
                st.stock[ResourceKind.STONE] = (st.stock[ResourceKind.STONE] ?: 0) + take
                st.production[ResourceKind.STONE] = take
                if (!st.quarryOpened) {
                    st.quarryOpened = true
                    into += AgeEvent(
                        year, AgeEventKind.QUARRY_OPENED, site.id,
                        text = "A quarry was opened in the high ground above ${site.name}, and the first blocks came down."
                    )
                }
                if (st.stoneSeam == 0 && !st.quarryClosed) {
                    st.quarryClosed = true
                    into += AgeEvent(
                        year, AgeEventKind.QUARRY_CLOSED, site.id,
                        text = "The quarry above ${site.name} is worked out; the last blocks came down this year."
                    )
                }
            }
        }
        // salt: scraped where the shore and marsh give it, until they give no more
        if (st.saltSeam > 0 && folk >= QUARRY_FOLK / 2) {
            val take = minOf(st.saltSeam, folk / SALT_FOLK_PER_PAN + 1)
            if (take > 0) {
                st.saltSeam -= take
                st.stock[ResourceKind.SALT] = (st.stock[ResourceKind.SALT] ?: 0) + take
                st.production[ResourceKind.SALT] = take
                if (!st.saltOpened) {
                    st.saltOpened = true
                    into += AgeEvent(
                        year, AgeEventKind.MINE_OPENED, site.id,
                        text = "The salt pans of ${site.name} were opened, and the first white harvest scraped."
                    )
                }
                if (st.saltSeam == 0 && !st.saltClosed) {
                    st.saltClosed = true
                    into += AgeEvent(
                        year, AgeEventKind.RESOURCE_DEPLETED, site.id,
                        text = "The salt pans of ${site.name} yield no more; the ground has given all it had."
                    )
                }
            }
        }
        // ore: deposits are found by growing folk, opened, worked, and worked out
        for (dep in st.deposits) {
            if (!dep.discovered) {
                val character = characterOf(site)
                val chance = 0.02f + folk / 4000f +
                    if (character == LandCharacter.MOUNTAIN || character == LandCharacter.HILLS) 0.08f else 0.02f
                if (rng(site.id, year, 61 + dep.material.ordinal).nextFloat() < chance) {
                    dep.discovered = true
                    into += AgeEvent(
                        year, AgeEventKind.RESOURCE_DISCOVERED, site.id,
                        text = "Veins of ${dep.material.label} were found in the ground about ${site.name}."
                    )
                }
            } else if (dep.remaining > 0 && folk > 0) {
                val take = (folk / ORE_FOLK_PER_LOAD).coerceAtMost(ORE_MAX_LOAD)
                    .coerceAtLeast(2).coerceAtMost(dep.remaining)
                dep.remaining -= take
                st.oreStock[dep.material] = (st.oreStock[dep.material] ?: 0) + take
                st.oreExtracted += take
                if (!dep.announced) {
                    dep.announced = true
                    into += AgeEvent(
                        year, AgeEventKind.MINE_OPENED, site.id,
                        text = "The ${dep.material.label} diggings at ${site.name} were opened, and the first loads brought up."
                    )
                }
                if (dep.remaining == 0) {
                    into += AgeEvent(
                        year, AgeEventKind.RESOURCE_DEPLETED, site.id,
                        text = "The ${dep.material.label} diggings at ${site.name} are worked out; the pits stand empty."
                    )
                }
            }
        }
        if (st.oreExtracted > 0) st.production[ResourceKind.ORE] = st.oreExtracted
        // the year's burning and building draws the yards down
        if (folk > 0) {
            takeFrom(st.stock, ResourceKind.TIMBER, maxOf(1, folk / TIMBER_USE_FOLK))
            takeFrom(st.stock, ResourceKind.CLAY, maxOf(1, folk / CLAY_USE_FOLK))
            takeFrom(st.stock, ResourceKind.STONE, maxOf(1, folk / STONE_USE_FOLK))
            takeFrom(st.stock, ResourceKind.SALT, maxOf(1, folk / SALT_USE_FOLK))
        }
    }

    /** The waters and woods feel the hand that works them, and heal when left. */
    private fun stepPressure(folk: Int, st: EcoState, pot: ResourcePotential) {
        val fishStrain = (folk * 1000f / (pot.fish * 20000f + 500f)).toInt()
        st.fishPressure = (st.fishPressure + fishStrain - FISH_REGEN).coerceIn(0, PRESSURE_CAP)
        val gameStrain = (folk * 1000f / (pot.game * 40000f + 1000f)).toInt()
        st.gamePressure = (st.gamePressure + gameStrain - GAME_REGEN).coerceIn(0, PRESSURE_CAP)
    }

    /**
     * The forge, the kiln and the sawpit: raw stock becomes worked material,
     * and only where the hands and the town can support it. Every processed
     * material has an economic cause — an input consumed.
     */
    private fun processMaterials(year: Int, site: Site, folk: Int, st: EcoState, into: MutableList<AgeEvent>) {
        // the sawpit: half the yard's logs squared into lumber each year
        if (folk >= LUMBER_FOLK) {
            val yard = st.stock[ResourceKind.TIMBER] ?: 0
            val convert = minOf(yard / 2, folk / 6)
            if (convert > 0) {
                takeFrom(st.stock, ResourceKind.TIMBER, convert)
                st.materialStock[Material.ASHWOOD] = (st.materialStock[Material.ASHWOOD] ?: 0) + convert
                st.materialProduced[Material.ASHWOOD] = (st.materialProduced[Material.ASHWOOD] ?: 0) + convert
            }
        }
        if (folk >= TOWN_FOLK) {
            // the forge: ore smelted into metal — walked in key order, so a save
            // and a running world smelt the same ores the same way, and the
            // industries they found keep one chronology
            for ((material, qty) in st.oreStock.entries.sortedBy { it.key.name }) {
                val smelt = minOf(qty, folk / SMELT_FOLK_PER_LOAD)
                if (smelt > 0) {
                    st.oreStock[material] = qty - smelt
                    st.materialStock[material] = (st.materialStock[material] ?: 0) + smelt
                    st.materialProduced[material] = (st.materialProduced[material] ?: 0) + smelt
                    if (material == Material.COPPER) {
                        val green = smelt / 4
                        if (green > 0) {
                            st.materialStock[Material.VERDIGRIS] =
                                (st.materialStock[Material.VERDIGRIS] ?: 0) + green
                            st.materialProduced[Material.VERDIGRIS] =
                                (st.materialProduced[Material.VERDIGRIS] ?: 0) + green
                        }
                    }
                }
            }
            val region = geography.available(cultureOf(site)).toSet()
            // temper brass: copper alloyed where the region's smiths know the secret
            if (Material.BRASS in region) {
                val alloy = minOf(st.materialStock[Material.COPPER] ?: 0, folk / 60)
                if (alloy > 0) {
                    st.materialStock[Material.COPPER] = (st.materialStock[Material.COPPER] ?: 0) - alloy
                    st.materialStock[Material.BRASS] = (st.materialStock[Material.BRASS] ?: 0) + alloy
                    st.materialProduced[Material.BRASS] = (st.materialProduced[Material.BRASS] ?: 0) + alloy
                }
            }
            // grave-slate: quarried stone dressed for the masons
            val dressed = minOf(st.stock[ResourceKind.STONE] ?: 0, folk / 60)
            if (dressed > 0) {
                takeFrom(st.stock, ResourceKind.STONE, dressed)
                st.materialStock[Material.GRAVE_SLATE] = (st.materialStock[Material.GRAVE_SLATE] ?: 0) + dressed
                st.materialProduced[Material.GRAVE_SLATE] = (st.materialProduced[Material.GRAVE_SLATE] ?: 0) + dressed
            }
            // salt-glass: salt burned into the strange clear stuff
            val glass = minOf(st.stock[ResourceKind.SALT] ?: 0, folk / 80)
            if (glass > 0) {
                takeFrom(st.stock, ResourceKind.SALT, glass)
                st.materialStock[Material.SALT_GLASS] = (st.materialStock[Material.SALT_GLASS] ?: 0) + glass
                st.materialProduced[Material.SALT_GLASS] = (st.materialProduced[Material.SALT_GLASS] ?: 0) + glass
            }
        }
        // steel: the great forges only, where iron is worked long and hot
        if (folk >= FORGE_FOLK) {
            val hardened = minOf(st.materialStock[Material.IRON] ?: 0, folk / 40)
            if (hardened > 0) {
                st.materialStock[Material.IRON] = (st.materialStock[Material.IRON] ?: 0) - hardened
                st.materialStock[Material.STEEL] = (st.materialStock[Material.STEEL] ?: 0) + hardened
                st.materialProduced[Material.STEEL] = (st.materialProduced[Material.STEEL] ?: 0) + hardened
            }
        }
        // the chronicle of industry: the first fire, and the cold forge
        for ((material, made) in st.materialProduced) {
            if (made > 0 && material !in st.industries) {
                st.industries += material
                into += AgeEvent(
                    year, AgeEventKind.INDUSTRY_ESTABLISHED, site.id,
                    text = "${material.adjective} work has taken hold at ${site.name}; the place is known for it now."
                )
            }
        }
        for (material in st.industries.toList()) {
            if (material in st.declined || (st.materialProduced[material] ?: 0) > 0) continue
            val oreGone = (st.oreStock[material] ?: 0) == 0 &&
                st.deposits.none { it.material == material && it.remaining > 0 }
            if (material in ORE_FED && oreGone) {
                st.declined += material
                into += AgeEvent(
                    year, AgeEventKind.INDUSTRY_DECLINED, site.id,
                    text = "The ${material.label} work at ${site.name} has failed — the ground that fed it is empty."
                )
            }
        }
    }

    /**
     * Coin every settlement's trade partners: the [TRADE_PARTNERS] nearest
     * steads in reach, nearest first, ties broken by id — the exact order the
     * old per-year whole-province scan produced, walked once here. The folk
     * gate stays in [stepTrade], where the yearly ledger lives.
     */
    private fun buildTradeIndex(
        living: List<Site>
    ): Pair<Map<Int, List<Site>>, Map<Int, Site>> {
        val partnersById = HashMap<Int, List<Site>>(living.size)
        for (site in living) {
            val nearest = arrayOfNulls<Site>(TRADE_PARTNERS)
            val nearestDist = FloatArray(TRADE_PARTNERS) { Float.MAX_VALUE }
            for (other in living) {
                if (other.id == site.id) continue
                val d = distSq(site, other)
                if (d > TRADE_MAX_DIST_SQ) continue
                // where this stead sits in the (distance, id) order, nearest first
                var slot = TRADE_PARTNERS - 1
                while (slot >= 0 && (nearest[slot] == null || d < nearestDist[slot] ||
                            (d == nearestDist[slot] && other.id < nearest[slot]!!.id))
                ) slot--
                if (slot + 1 >= TRADE_PARTNERS) continue
                for (j in TRADE_PARTNERS - 1 downTo slot + 2) {
                    nearest[j] = nearest[j - 1]
                    nearestDist[j] = nearestDist[j - 1]
                }
                nearest[slot + 1] = other
                nearestDist[slot + 1] = d
            }
            partnersById[site.id] = nearest.mapNotNull { it }
        }
        return partnersById to living.associateBy { it.id }
    }

    /** Roads are beaten between a real surplus and a real want, and cargo actually moves. */
    private fun stepTrade(
        year: Int,
        site: Site,
        st: EcoState,
        ledger: SettlementLedger,
        into: MutableList<AgeEvent>,
        partners: List<Site>,
        livingById: Map<Int, Site>
    ) {
        // the folk gate stays here: the ledger's counts move year to year
        val tradePartners = partners.filter { ledger.folkOf(it) > ROUTE_MIN_FOLK }

        for (partner in tradePartners) {
            if (site.id > partner.id) continue // each pair once, alphabet of ids
            val other = stateOf(partner)
            val pairPrefix = "${site.id}>${partner.id}:"
            // the roads already running: cargo moves, or the road dries —
            // walked in a settled order, so a save and a running world agree
            for ((key, route) in st.routes.entries.sortedBy { it.key }) {
                if (!key.startsWith(pairPrefix)) continue
                moveCargo(year, site, st, partner, other, key, route, ledger, into)
            }
            // new roads: actual surplus meeting actual demand, either way round
            val running = st.routes.keys.filter { it.startsWith(pairPrefix) }
                .mapNotNull { parseRoute(it)?.cargo }.toSet()
            if (!openRoad(year, site, st, partner, other, ledger, running, into)) {
                openRoad(year, partner, other, site, st, ledger, running, into)
            }
        }

        // a road is kept while its other end stands within reach and alive —
        // swept in key order, so the chronicle remembers closures the same way
        // a reloaded world does
        val dead = st.routes.keys.sorted().filter { key ->
            val route = parseRoute(key) ?: return@filter false
            val otherId = if (route.fromId == site.id) route.toId else route.fromId
            val other = livingById[otherId]
            !(other != null && distSq(site, other) <= TRADE_MAX_DIST_SQ && ledger.folkOf(other) > ROUTE_MIN_FOLK)
        }
        for (key in dead) {
            val route = parseRoute(key) ?: continue
            val otherId = if (route.fromId == site.id) route.toId else route.fromId
            st.routes.remove(key)
            states[otherId]?.routes?.remove(key)
            val other = livingById[otherId]
            into += AgeEvent(
                year, AgeEventKind.TRADE_ROUTE_CLOSED, site.id, otherId,
                text = "The trade road between ${site.name} and ${other?.name ?: "a dead place"} fell out of use."
            )
        }
    }

    /** Move a road's cargo: whichever end has the surplus ships to the end with the want. */
    private fun moveCargo(
        year: Int,
        a: Site,
        stateA: EcoState,
        b: Site,
        stateB: EcoState,
        key: String,
        route: RouteState,
        ledger: SettlementLedger,
        into: MutableList<AgeEvent>
    ) {
        val folkA = ledger.folkOf(a).coerceAtLeast(0)
        val folkB = ledger.folkOf(b).coerceAtLeast(0)
        val capacity = (folkA + folkB) / 4 + 20
        val qtyAtoB = minOf(
            surplusOf(stateA, route.cargo, folkA),
            demandOf(stateB, route.cargo, folkB),
            capacity
        ).coerceAtLeast(0)
        if (qtyAtoB >= TRADE_MIN_CARGO) {
            takeCargo(stateA, route.cargo, qtyAtoB)
            giveCargo(stateB, route.cargo, qtyAtoB)
            route.lastQty = qtyAtoB
            route.strength++
            route.dry = 0
            stateB.routes[key]?.let {
                it.lastQty = route.lastQty; it.strength = route.strength; it.dry = 0
            }
            if (route.strength == TRADE_STRENGTH_MILESTONE) {
                into += AgeEvent(
                    year, AgeEventKind.TRADE_ROUTE_STRENGTHENED, a.id, b.id,
                    text = "The road between ${a.name} and ${b.name} has grown strong — ${route.cargo.lowercase()} " +
                        "has crossed it year upon year, and the traffic is old news now."
                )
            }
            return
        }
        val qtyBtoA = minOf(
            surplusOf(stateB, route.cargo, folkB),
            demandOf(stateA, route.cargo, folkA),
            capacity
        ).coerceAtLeast(0)
        if (qtyBtoA >= TRADE_MIN_CARGO) {
            takeCargo(stateB, route.cargo, qtyBtoA)
            giveCargo(stateA, route.cargo, qtyBtoA)
            route.lastQty = qtyBtoA
            route.strength++
            route.dry = 0
            stateB.routes[key]?.let {
                it.lastQty = route.lastQty; it.strength = route.strength; it.dry = 0
            }
            if (route.strength == TRADE_STRENGTH_MILESTONE) {
                into += AgeEvent(
                    year, AgeEventKind.TRADE_ROUTE_STRENGTHENED, a.id, b.id,
                    text = "The road between ${a.name} and ${b.name} has grown strong — ${route.cargo.lowercase()} " +
                        "has crossed it year upon year, and the traffic is old news now."
                )
            }
            return
        }
        // nothing to carry: the road dries, and in time is forgotten
        route.lastQty = 0
        route.dry++
        if (route.dry >= TRADE_DRY_YEARS) {
            stateA.routes.remove(key)
            stateB.routes.remove(key)
            into += AgeEvent(
                year, AgeEventKind.TRADE_ROUTE_CLOSED, a.id, b.id,
                text = "The trade road between ${a.name} and ${b.name} fell quiet — neither end has cause to send wagons anymore."
            )
        }
    }

    /** Open a road from [from] to [to] when a real surplus meets a real want. */
    private fun openRoad(
        year: Int,
        from: Site,
        fromState: EcoState,
        to: Site,
        toState: EcoState,
        ledger: SettlementLedger,
        running: Set<String>,
        into: MutableList<AgeEvent>
    ): Boolean {
        val folkFrom = ledger.folkOf(from).coerceAtLeast(0)
        val folkTo = ledger.folkOf(to).coerceAtLeast(0)
        for (cargo in candidateCargos(fromState)) {
            if (cargo in running) continue
            val surplus = surplusOf(fromState, cargo, folkFrom)
            if (surplus < TRADE_MIN_CARGO) continue
            val demand = demandOf(toState, cargo, folkTo)
            if (demand < TRADE_MIN_CARGO) continue
            val key = TradeRoute(from.id, to.id, cargo).key
            fromState.routes[key] = RouteState(cargo, year)
            toState.routes[key] = RouteState(cargo, year)
            into += AgeEvent(
                year, AgeEventKind.TRADE_ROUTE_OPENED, from.id, to.id,
                text = "A trade road was opened between ${from.name} and ${to.name} — ${from.name} has " +
                    "${cargoLabel(cargo)} to spare and ${to.name} wants it."
            )
            return true
        }
        return false
    }

    /** The granary: what the year grew, what the roads brought, what was eaten. */
    private fun stepFood(
        year: Int,
        site: Site,
        folk: Int,
        st: EcoState,
        into: MutableList<AgeEvent>,
        hunger: MutableMap<Int, Int>
    ) {
        val opening = st.food.values.sum()
        val production = st.foodProduction.values.sum()
        val need = folk * FOOD_NEED
        // the folk eat what the year and the roads brought
        if (folk > 0 && opening > 0) {
            val share = minOf(1f, need.toFloat() / opening)
            for ((kind, qty) in st.food.entries.toList()) {
                st.food[kind] = (qty - (qty * share).toInt()).coerceAtLeast(0)
            }
        }
        // and a little spoils
        for ((kind, qty) in st.food.entries.toList()) {
            st.food[kind] = (qty - qty / 25).coerceAtLeast(0)
        }
        val deficit = (need - opening).coerceAtLeast(0)
        // Granaries buffer the first lean years; only want that outlasts them
        // puts hungry souls onto the roads.
        if (deficit > 0 && production < need * 0.75f) {
            st.famineStreak++
            if (st.famineStreak >= FAMINE_STREAK_YEARS) {
                val hungry = (deficit / FOOD_NEED).coerceAtMost(folk / 3)
                if (hungry > 0) {
                    hunger[site.id] = hungry
                    st.cropStock.clear() // famine eats the seed corn
                    if (hungry >= folk / 5) {
                        into += AgeEvent(
                            year, AgeEventKind.HARVEST_FAILURE, site.id, count = hungry,
                            text = "The harvest failed at ${site.name}; the granary stands empty and ${hungry} souls went hungry."
                        )
                    }
                }
                st.famineStreak = 0
            }
        } else {
            st.famineStreak = 0
        }
        if (!st.richYear && production > need * SURPLUS_GAIN && st.food.values.sum() > folk * RESERVE_RICH) {
            st.richYear = true
            into += AgeEvent(
                year, AgeEventKind.HARVEST_SURPLUS, site.id,
                text = "Granaries at ${site.name} stand full — a fat year the folk will remember."
            )
        }
        if (st.richYear && st.food.values.sum() <= folk * RESERVE_RICH / 3) st.richYear = false
    }

    /** What a place is known for, and the year it became known for it. */
    private fun stepSpecialization(
        year: Int,
        site: Site,
        folk: Int,
        st: EcoState,
        into: MutableList<AgeEvent>
    ) {
        val newLabels = labelSet(site, folk, st)
        val prev = st.labels
        if (prev != null && newLabels != prev && folk >= SPECIALIZE_MIN_FOLK) {
            val lost = prev.filter { it !in newLabels }
            if (lost.isEmpty() || newLabels.size > prev.size) {
                into += AgeEvent(
                    year, AgeEventKind.SPECIALIZED, site.id,
                    text = "The folk of ${site.name} are now known for " +
                        newLabels.joinToString(" and ") + "; the old days of " +
                        prev.joinToString(" and ") + " are past."
                )
            } else {
                into += AgeEvent(
                    year, AgeEventKind.ECONOMIC_DECLINE, site.id,
                    text = if (newLabels.isEmpty()) {
                        "The work of ${site.name} — ${lost.joinToString(" and ")} — is gone, and nothing has risen in its place."
                    } else {
                        "The ${lost.joinToString(" and ")} of ${site.name} are no more; its folk are known now for " +
                            newLabels.joinToString(" and ") + "."
                    }
                )
            }
        }
        st.labels = newLabels
    }

    // ---------------------------------------------------------- the trade law

    /** The cargos a place could plausibly ship, in a deterministic order. */
    private fun candidateCargos(st: EcoState): List<String> {
        val list = mutableListOf<String>()
        FoodKind.entries.forEach { list += it.name }
        for (kind in SHIPPED_RESOURCES) list += kind.name
        st.materialStock.keys.sortedBy { it.name }.forEach { list += it.name }
        st.oreStock.keys.sortedBy { it.name }.forEach { list += it.name }
        return list.distinct()
    }

    /** What a place could spare of a cargo this year — from stock, not potential. */
    private fun surplusOf(st: EcoState, cargo: String, folk: Int): Int {
        foodCargo(cargo)?.let {
            val total = st.food.values.sum()
            return (total - folk * FOOD_NEED / 2).coerceAtLeast(0)
        }
        bulkCargo(cargo)?.let { kind ->
            val onHand = st.stock[kind] ?: 0
            val keep = when (kind) {
                ResourceKind.TIMBER -> folk / 4
                ResourceKind.CLAY -> folk / 3
                ResourceKind.STONE -> folk / 3
                ResourceKind.SALT -> folk / 4
                else -> 0
            }
            return (onHand - keep).coerceAtLeast(0)
        }
        matCargo(cargo)?.let { material ->
            val onHand = st.materialStock[material] ?: 0
            val oreOnHand = st.oreStock[material] ?: 0
            val worksIt = oreOnHand > 0 || st.deposits.any { it.material == material && it.remaining > 0 }
            val keep = if (worksIt) folk / 10 else 0
            return (onHand + oreOnHand / 2 - keep).coerceAtLeast(0)
        }
        return 0
    }

    /** What a place truly wants of a cargo — a shortfall or an empty yard. */
    private fun demandOf(st: EcoState, cargo: String, folk: Int): Int {
        foodCargo(cargo)?.let {
            val total = st.food.values.sum()
            return (folk * FOOD_NEED - total).coerceAtLeast(0)
        }
        bulkCargo(cargo)?.let { kind ->
            val onHand = st.stock[kind] ?: 0
            val wanted = when (kind) {
                ResourceKind.TIMBER -> folk / TIMBER_USE_FOLK * 3
                ResourceKind.CLAY -> folk / CLAY_USE_FOLK * 3
                ResourceKind.STONE -> folk / STONE_USE_FOLK * 3
                ResourceKind.SALT -> folk / SALT_USE_FOLK * 3
                else -> 0
            }
            return (wanted - onHand).coerceAtLeast(0)
        }
        matCargo(cargo)?.let { material ->
            val hasSupply = (st.materialStock[material] ?: 0) > 0 || (st.oreStock[material] ?: 0) > 0 ||
                st.deposits.any { it.material == material && it.discovered && it.remaining > 0 }
            if (hasSupply || folk < TRADE_DEMAND_FOLK) return 0
            return folk / 20
        }
        return 0
    }

    /** Take a shipment out of a place's stores. */
    private fun takeCargo(st: EcoState, cargo: String, qty: Int) {
        foodCargo(cargo)?.let {
            st.food[it] = ((st.food[it] ?: 0) - qty).coerceAtLeast(0)
            return
        }
        bulkCargo(cargo)?.let {
            takeFrom(st.stock, it, qty)
            return
        }
        matCargo(cargo)?.let { material ->
            val ore = st.oreStock[material] ?: 0
            val fromOre = minOf(ore, qty / 2)
            st.oreStock[material] = ore - fromOre
            st.materialStock[material] = ((st.materialStock[material] ?: 0) - (qty - fromOre)).coerceAtLeast(0)
        }
    }

    /** Set a shipment down in a place's stores. Raw metal arrives as ore for the forge. */
    private fun giveCargo(st: EcoState, cargo: String, qty: Int) {
        foodCargo(cargo)?.let {
            st.food[it] = (st.food[it] ?: 0) + qty
            return
        }
        bulkCargo(cargo)?.let {
            st.stock[it] = (st.stock[it] ?: 0) + qty
            return
        }
        matCargo(cargo)?.let {
            st.oreStock[it] = (st.oreStock[it] ?: 0) + qty
        }
    }

    private fun foodCargo(cargo: String): FoodKind? = FoodKind.entries.firstOrNull { it.name == cargo }
    private fun bulkCargo(cargo: String): ResourceKind? = SHIPPED_RESOURCES.firstOrNull { it.name == cargo }
    private fun matCargo(cargo: String): Material? = Material.entries.firstOrNull { it.name == cargo }

    private fun cargoLabel(cargo: String): String =
        foodCargo(cargo)?.label ?: bulkCargo(cargo)?.label ?: matCargo(cargo)?.label ?: cargo.lowercase()

    private fun parseRoute(key: String): TradeRoute? {
        val halves = key.split(">")
        if (halves.size != 2) return null
        val rest = halves[1].split(":")
        if (rest.size != 2) return null
        val fromId = halves[0].toIntOrNull() ?: return null
        val toId = rest[0].toIntOrNull() ?: return null
        return TradeRoute(fromId, toId, rest[1])
    }

    // -------------------------------------------------------- the material law

    /**
     * What a place can put its hands on with its own work: stock on hand, ore
     * being dug, woods being cut, herds being hunted. Potential alone is
     * nothing — an undiscovered deposit is not iron in anyone's hands.
     */
    private fun localMaterials(site: Site, folk: Int, st: EcoState): Set<Material> {
        val local = mutableSetOf<Material>()
        for (dep in st.deposits) {
            if ((dep.announced && dep.remaining > 0 && folk > 0) || (st.oreStock[dep.material] ?: 0) > 0) {
                local += dep.material
            }
        }
        for ((material, qty) in st.materialStock) {
            if (qty > 0) local += material
        }
        if (st.timberCut > 0 || (st.stock[ResourceKind.TIMBER] ?: 0) > 0) local += Material.ASHWOOD
        if ((st.foodProduction[FoodKind.MEAT] ?: 0) > 0) local += Material.BONE
        return local
    }

    /**
     * The full material report for a place: every material the world knows,
     * marked LOCAL (its own work can produce it), IMPORTED (a road that
     * actually carries it, from an end that truly holds it), or SCARCE
     * (neither production, stock, nor trade can supply it).
     */
    fun materialReport(site: Site, ledger: SettlementLedger, sites: List<Site>): List<MaterialProvenance> =
        Material.entries.map { provenanceOf(site, ledger, sites, it) }

    /** One material's answer: can this place obtain it, and from where? */
    fun provenanceOf(site: Site, ledger: SettlementLedger, sites: List<Site>, material: Material): MaterialProvenance {
        val st = stateOf(site)
        if (material in localMaterials(site, ledger.folkOf(site).coerceAtLeast(0), st)) {
            return MaterialProvenance(material, MaterialSource.LOCAL)
        }
        val byId = sites.associateBy { it.id }
        // one road's reach: a partner's own hands, not a partner's imports.
        // Walked in key order, so a save and a running world name the same source
        // even when two roads carry the same cargo.
        for (key in st.routes.keys.sorted()) {
            val route = parseRoute(key) ?: continue
            if (route.cargo != material.name) continue
            val otherId = if (route.fromId == site.id) route.toId else route.fromId
            val partner = byId[otherId] ?: continue
            if (material in localMaterials(partner, ledger.folkOf(partner).coerceAtLeast(0), stateOf(partner))) {
                return MaterialProvenance(material, MaterialSource.IMPORTED, partner.id, partner.name)
            }
        }
        return MaterialProvenance(material, MaterialSource.SCARCE)
    }

    // ------------------------------------------------------------ the identity

    /** The work a place is known by, from what it actually produced — not what it could. */
    private fun labelSet(site: Site, folk: Int, st: EcoState): List<String> {
        if (folk <= 0) return emptyList()
        val need = folk * FOOD_NEED
        val labels = mutableListOf<String>()
        val gardenFood = (st.foodProduction[FoodKind.GRAIN] ?: 0) + (st.foodProduction[FoodKind.PRODUCE] ?: 0)
        if (gardenFood > need * 0.7f) labels += "farming"
        if ((st.foodProduction[FoodKind.FISH] ?: 0) > need * 0.35f) labels += "fishing"
        if ((st.foodProduction[FoodKind.MEAT] ?: 0) > need * 0.35f) labels += "pastoral"
        if ((st.production[ResourceKind.TIMBER] ?: 0) > 40) labels += "logging"
        if ((st.production[ResourceKind.ORE] ?: 0) > 0) labels += "mining"
        if ((st.production[ResourceKind.STONE] ?: 0) > 0) labels += "quarrying"
        if ((st.production[ResourceKind.CLAY] ?: 0) > 0) labels += "brickworking"
        if ((st.production[ResourceKind.SALT] ?: 0) > 0) labels += "salting"
        if (st.routes.size >= 2) labels += "trade hub"
        if (labels.isEmpty()) labels += "gleaning"
        return labels
    }

    // ------------------------------------------------------------------- save

    /** The economy's residue, packed: one entry per touched place. */
    fun encode(): String = states.entries.sortedBy { it.key }.joinToString(ENTRY) { (id, st) ->
        "S$id" +
            "=${st.timber}" +
            "=${if (st.timberGone) 1 else 0}" +
            "=${st.fishPressure}" +
            "=${st.gamePressure}" +
            "=${st.food.values.sum()}" + // the granary, as older saves knew it
            "=${if (st.richYear) 1 else 0}" +
            "=${st.crops.entries.joinToString(",") { "${it.key.name}:${it.value}" }}" +
            "=${st.deposits.joinToString(",") { "${it.material.name}:${it.remaining}:${if (it.discovered) 1 else 0}:${if (it.announced) 1 else 0}" }}" +
            "=${st.routes.entries.sortedBy { it.key }.joinToString(",") { (key, r) ->
                "$key@${r.since}@${r.strength}@${r.lastQty}@${r.dry}"
            }}" +
            "=${(st.labels ?: emptyList()).joinToString(",")}" +
            "=${st.famineStreak}" +
            "=${st.food.entries.joinToString(",") { "${it.key.name}:${it.value}" }}" +
            "=${st.stock.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}:${it.value}" }}" +
            "=${st.cropStock.entries.joinToString(",") { "${it.key.name}:${it.value}" }}" +
            "=${st.stoneSeam}:${st.saltSeam}:${st.clayBed}:${st.clayInit}" +
            "=${st.oreStock.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}:${it.value}" }}" +
            "=${st.materialStock.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}:${it.value}" }}" +
            // industries keeps its founding chronology: the decline check walks
            // this order, so a reloaded world must fail its works the same way
            "=${st.industries.joinToString(",")}" +
            // declined keeps its own chronology: the census names the first work
            // to fail, so the save must remember the order the failures came in
            "=${st.declined.joinToString(",")}" +
            "=${st.production.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}:${it.value}" }}" +
            "=${st.foodProduction.entries.joinToString(",") { "${it.key.name}:${it.value}" }}" +
            "=${st.timberCut}" +
            "=${listOf(st.quarryOpened, st.saltOpened, st.quarryClosed, st.saltClosed).joinToString("") { if (it) "1" else "0" }}"
    }

    /** Wake from the save; a blank string wakes clean, as old saves always have. */
    fun applyEncoded(raw: String) {
        if (raw.isBlank()) return
        raw.split(ENTRY).forEach { piece ->
            val parts = piece.split("=")
            if (parts.size < 10 || !parts[0].startsWith("S")) return@forEach
            val id = parts[0].removePrefix("S").toIntOrNull() ?: return@forEach
            val site = world.siteOrNull(id) ?: return@forEach
            val st = stateOf(site)
            st.timber = parts[1].toIntOrNull() ?: st.timber
            st.timberGone = parts[2] == "1"
            st.fishPressure = (parts[3].toIntOrNull() ?: 0).coerceIn(0, PRESSURE_CAP)
            st.gamePressure = (parts[4].toIntOrNull() ?: 0).coerceIn(0, PRESSURE_CAP)
            val legacyReserve = parts[5].toIntOrNull() ?: 0
            st.richYear = parts[6] == "1"
            parts[7].takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                val kv = entry.split(":")
                val crop = CropKind.entries.firstOrNull { it.name == kv[0] } ?: return@forEach
                st.crops[crop] = kv.getOrNull(1)?.toIntOrNull() ?: 0
            }
            parts[8].takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                val kv = entry.split(":")
                val material = Material.entries.firstOrNull { it.name == kv[0] } ?: return@forEach
                val dep = st.deposits.firstOrNull { it.material == material } ?: return@forEach
                dep.remaining = kv.getOrNull(1)?.toIntOrNull() ?: dep.remaining
                dep.discovered = kv.getOrNull(2) == "1"
                dep.announced = kv.getOrNull(3) == "1"
            }
            parts[9].takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                val halves = entry.split("@")
                val key = halves[0]
                if (!key.contains(">") || !key.contains(":")) return@forEach
                st.routes[key] = RouteState(
                    key.substringAfterLast(':'),
                    halves.getOrNull(1)?.toIntOrNull() ?: 0,
                    halves.getOrNull(2)?.toIntOrNull() ?: 0,
                    halves.getOrNull(3)?.toIntOrNull() ?: 0,
                    halves.getOrNull(4)?.toIntOrNull() ?: 0
                )
            }
            parts.getOrNull(10)?.takeIf { it.isNotBlank() }?.split(",")?.let { st.labels = it }
            st.famineStreak = parts.getOrNull(11)?.toIntOrNull() ?: 0
            // the broad stores: new saves carry the breakdown, old saves one granary number
            val foodField = parts.getOrNull(12)
            if (foodField != null) {
                foodField.takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                    val kv = entry.split(":")
                    val kind = FoodKind.entries.firstOrNull { it.name == kv[0] } ?: return@forEach
                    st.food[kind] = kv.getOrNull(1)?.toIntOrNull() ?: 0
                }
            } else if (legacyReserve > 0) {
                st.food[FoodKind.GRAIN] = legacyReserve
            }
            parts.getOrNull(13)?.takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                val kv = entry.split(":")
                val kind = ResourceKind.entries.firstOrNull { it.name == kv[0] } ?: return@forEach
                st.stock[kind] = kv.getOrNull(1)?.toIntOrNull() ?: 0
            }
            parts.getOrNull(14)?.takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                val kv = entry.split(":")
                val crop = CropKind.entries.firstOrNull { it.name == kv[0] } ?: return@forEach
                st.cropStock[crop] = kv.getOrNull(1)?.toIntOrNull() ?: 0
            }
            parts.getOrNull(15)?.takeIf { it.isNotBlank() }?.split(":")?.let { seams ->
                st.stoneSeam = seams.getOrNull(0)?.toIntOrNull() ?: st.stoneSeam
                st.saltSeam = seams.getOrNull(1)?.toIntOrNull() ?: st.saltSeam
                st.clayBed = seams.getOrNull(2)?.toIntOrNull() ?: st.clayBed
                st.clayInit = seams.getOrNull(3)?.toIntOrNull() ?: st.clayInit
            }
            parts.getOrNull(16)?.takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                val kv = entry.split(":")
                val material = Material.entries.firstOrNull { it.name == kv[0] } ?: return@forEach
                st.oreStock[material] = kv.getOrNull(1)?.toIntOrNull() ?: 0
            }
            parts.getOrNull(17)?.takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                val kv = entry.split(":")
                val material = Material.entries.firstOrNull { it.name == kv[0] } ?: return@forEach
                st.materialStock[material] = kv.getOrNull(1)?.toIntOrNull() ?: 0
            }
            parts.getOrNull(18)?.takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                Material.entries.firstOrNull { it.name == entry }?.let { st.industries += it }
            }
            parts.getOrNull(19)?.takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                Material.entries.firstOrNull { it.name == entry }?.let { st.declined += it }
            }
            parts.getOrNull(20)?.takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                val kv = entry.split(":")
                val kind = ResourceKind.entries.firstOrNull { it.name == kv[0] } ?: return@forEach
                st.production[kind] = kv.getOrNull(1)?.toIntOrNull() ?: 0
            }
            parts.getOrNull(21)?.takeIf { it.isNotBlank() }?.split(",")?.forEach { entry ->
                val kv = entry.split(":")
                val kind = FoodKind.entries.firstOrNull { it.name == kv[0] } ?: return@forEach
                st.foodProduction[kind] = kv.getOrNull(1)?.toIntOrNull() ?: 0
            }
            st.timberCut = parts.getOrNull(22)?.toIntOrNull() ?: 0
            parts.getOrNull(23)?.takeIf { it.length >= 4 }?.let { flags ->
                st.quarryOpened = flags[0] == '1'
                st.saltOpened = flags[1] == '1'
                st.quarryClosed = flags[2] == '1'
                st.saltClosed = flags[3] == '1'
            }
        }
    }

    // ----------------------------------------------------------------- engine

    private fun rng(siteId: Int, year: Int, salt: Int): Random =
        Random(world.seed * 1103515245L + siteId * 100003L + year * 7919L + salt)

    private fun distSq(a: Site, b: Site): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private fun takeFrom(stock: MutableMap<ResourceKind, Int>, kind: ResourceKind, qty: Int) {
        stock[kind] = ((stock[kind] ?: 0) - qty).coerceAtLeast(0)
    }

    companion object {
        /** The economy's own arithmetic, tuned once, in one place. */
        const val ESTABLISH_YEARS = 4
        const val FOOD_NEED = 10
        /** The hands' worth of ground one settlement's land can work at full yield. */
        const val LAND_FOLK = 10000
        /** Lean years a granary can absorb before the folk start walking. */
        const val FAMINE_STREAK_YEARS = 3
        const val CROP_RATE = 14f
        const val MEAT_RATE = 10f
        const val GAME_RATE = 4f
        const val FISH_RATE = 12f
        const val GATHER_RATE = 5f
        const val TIMBER_FOLK_PER_LOG = 8
        const val TIMBER_USE_FOLK = 12
        const val CLAY_FOLK_PER_LOAD = 10
        const val CLAY_USE_FOLK = 90
        const val STONE_FOLK_PER_BLOCK = 15
        const val STONE_USE_FOLK = 100
        const val SALT_FOLK_PER_PAN = 40
        const val SALT_USE_FOLK = 60
        const val ORE_FOLK_PER_LOAD = 12
        const val ORE_MAX_LOAD = 80
        const val SMELT_FOLK_PER_LOAD = 20
        const val FISH_REGEN = 40
        const val GAME_REGEN = 30
        const val PRESSURE_CAP = 850
        const val TRADE_PARTNERS = 2
        const val TRADE_MAX_DIST_SQ = 0.09f
        /** A road is not beaten for a basket: the least that counts as surplus or want. */
        const val TRADE_MIN_CARGO = 10
        const val TRADE_DRY_YEARS = 3
        const val TRADE_STRENGTH_MILESTONE = 10
        const val TRADE_DEMAND_FOLK = 100
        const val ROUTE_MIN_FOLK = 5
        const val ADOPT_FLOOR = 0.10f
        const val ADOPT_CHANCE = 0.28f
        /** Below this many souls the fields go back to brush. */
        const val MIN_FIELD_FOLK = 10
        const val SURPLUS_GAIN = 1.45f
        const val RESERVE_RICH = 8
        const val TOWN_FOLK = 320
        const val FORGE_FOLK = 800
        const val QUARRY_FOLK = 40
        const val LUMBER_FOLK = 40
        const val SPECIALIZE_MIN_FOLK = 40
        /** One part in this many of the harvest is kept for next year's sowing. */
        const val SEED_SHARE = 10
        /** A place needs this much salt in its band before pans are worth scraping. */
        const val SALT_SEAM_FLOOR = 0.6f

        private const val ENTRY = "\u001E"

        /** The bulk goods that ride the roads as themselves. */
        private val SHIPPED_RESOURCES = listOf(
            ResourceKind.TIMBER, ResourceKind.CLAY, ResourceKind.STONE, ResourceKind.SALT
        )

        /** Materials that come out of the ground as ore. */
        private val ORE_FED = setOf(
            Material.BOG_IRON, Material.COPPER, Material.IRON, Material.LEAD,
            Material.SILVER, Material.GOLD, Material.STAR_IRON, Material.SPIRIT_SILVER, Material.EARTH_BONE
        )

        /** A fresh economy for a fresh world: untouched ground everywhere. */
        fun fresh(world: World): EconomySimulation = EconomySimulation(world)
    }
}
