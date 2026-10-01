package com.rork.hollowmarch.world

/** A generated people: language, homeland, values, taboos and the craft they are known for. */
data class Culture(
    val id: Int,
    val name: String,
    val adjective: String,
    val epithet: String,
    val craft: String,
    val homeland: String,
    val values: List<String> = emptyList(),
    val taboo: String = ""
)

/**
 * Anything the province can judge the delver by: a sovereign realm, an
 * organization, or an outlaw group. All three share one id space, so the
 * ledgers of standing, pressure and employment speak of actors by number
 * without caring which kind it names.
 */
sealed interface Actor {
    val id: Int
    val name: String
    /** Outlaws and hostile creeds take against outsiders on sight. */
    val hostileByNature: Boolean
    /** Grudges and pacts, by actor id, in -100..100. */
    val relations: Map<Int, Int>
    /** What the actor is currently pressing for, in the chronicle's words. */
    val goals: List<String>
    /** The doctrine the actor holds, for the journal's flavor lines. */
    val creed: String
    /** True when history has ended this actor. */
    val extinct: Boolean
}

/** The political shape of a realm: how it is named, and what its ruler is called. */
enum class RealmType(val label: String, val rulerTitle: String) {
    KINGDOM("kingdom", "king"),
    REPUBLIC("republic", "first citizen"),
    CITY_STATE("free city", "lord-protector"),
    THEOCRACY("hierocracy", "hierophant"),
    CONFEDERATION("confederation", "speaker"),
    CHIEFDOM("chiefdom", "chief"),
    TRIBAL_REALM("tribal realm", "thegn"),
    EMPIRE("empire", "emperor")
}

/** How a realm is actually governed — independent of what it is. */
enum class GovernmentForm(val label: String) {
    HEREDITARY_CROWN("hereditary crown"),
    IMPERIAL_THRONE("imperial throne"),
    ELECTED_COUNCIL("elected council"),
    MERCHANT_COUNCIL("merchant council"),
    THEOCRATIC_SEAT("theocratic seat"),
    WARLORD_HOLD("warlord's hold"),
    ELDERS_ASSEMBLY("elders' assembly"),
    HIGH_ASSEMBLY("high assembly")
}

/**
 * The institution that presently governs a realm: its form, its ruler, the
 * houses that sit in council, and the year this government took the seat.
 * A realm outlives its governments; this is the current one.
 */
data class Government(
    val id: Int,
    val realmId: Int,
    val form: GovernmentForm,
    val rulerFigureId: Int?,
    val councilHouseIds: List<Int> = emptyList(),
    val seatedYear: Int
)

/** How a realm came into the world; the forge reads it when it names the state. */
enum class FoundingOrigin { ANCIENT_SEAT, CHARTER, REBELLION, SUCCESSION_SPLINTER, TEMPLE_SEAT }

/**
 * A sovereign political entity: the only kind of actor that owns ground.
 * A realm holds settlements, keeps a capital and a ruler, wages wars, and
 * outlives the governments that administer it.
 */
data class Realm(
    override val id: Int,
    override val name: String,
    val type: RealmType,
    val cultureId: Int,
    val foundedYear: Int,
    val founding: FoundingOrigin = FoundingOrigin.ANCIENT_SEAT,
    val splinterFromId: Int? = null,
    override val creed: String = "",
    val deityId: Int? = null,
    val capitalSiteId: Int? = null,
    val rulerFigureId: Int? = null,
    val governmentId: Int? = null,
    val legitimacy: Int = 60,
    val population: Int = 0,
    val militaryStrength: Int = 0,
    val wealth: Int = 0,
    override val relations: Map<Int, Int> = emptyMap(),
    override val goals: List<String> = emptyList(),
    override val hostileByNature: Boolean = false,
    override val extinct: Boolean = false
) : Actor

/** What an organization is for, and what its head is called. */
enum class OrganizationKind(val label: String, val leaderTitle: String) {
    GUILD("guild", "guildmaster"),
    ORDER("order", "grandmaster"),
    CULT("cult", "hierophant")
}

/**
 * A body that operates within society rather than governing it: guilds,
 * orders, cults. It keeps a headquarters, a leader, members and a treasury,
 * and it may hold sway in a settlement — but it is never sovereign.
 */
data class Organization(
    override val id: Int,
    override val name: String,
    val kind: OrganizationKind,
    val cultureId: Int,
    val foundedYear: Int,
    val splinterFromId: Int? = null,
    override val creed: String = "",
    val deityId: Int? = null,
    val headquartersSiteId: Int? = null,
    /** Dead or holy places the organization keeps, without owning them. */
    val presenceSiteIds: List<Int> = emptyList(),
    val leaderFigureId: Int? = null,
    val membershipCount: Int = 0,
    val treasury: Int = 0,
    override val relations: Map<Int, Int> = emptyMap(),
    override val goals: List<String> = emptyList(),
    override val hostileByNature: Boolean = false,
    override val extinct: Boolean = false
) : Actor

/** What a group outside the law is, and what its head is called. */
enum class GroupKind(val label: String, val leaderTitle: String) {
    WARBAND("warband", "war-chief"),
    REBELS("rebels", "free-lord")
}

/**
 * A group that lives outside political authority: warbands, rebel movements.
 * It keeps a base and a leader and it raids the living — but a camp is a
 * base, not a nation, and no group is ever a settlement's sovereign.
 */
data class NonStateGroup(
    override val id: Int,
    override val name: String,
    val kind: GroupKind,
    val cultureId: Int,
    val foundedYear: Int,
    val splinterFromId: Int? = null,
    override val creed: String = "",
    val deityId: Int? = null,
    val baseSiteId: Int? = null,
    val leaderFigureId: Int? = null,
    val membershipCount: Int = 0,
    val wealth: Int = 0,
    override val relations: Map<Int, Int> = emptyMap(),
    override val goals: List<String> = emptyList(),
    override val hostileByNature: Boolean = true,
    override val extinct: Boolean = false
) : Actor

enum class SiteKind(val label: String) {
    CAPITAL("capital"),
    CITY("city"),
    TOWN("town"),
    VILLAGE("village"),
    HOLDFAST("holdfast"),
    CAMP("camp"),
    RUIN("ruin"),
    BARROW("barrow"),
    VAULT("vault"),
    SHRINE("shrine")
}

data class Site(
    val id: Int,
    val name: String,
    val kind: SiteKind,
    val x: Float,
    val y: Float,
    /**
     * The sovereign realm — the only kind of actor that can hold ground.
     * Camps, vaults and barrows keep no sovereign: their keepers and riders
     * are found through the world's own steward lookups instead.
     */
    val sovereignRealmId: Int?,
    val note: String,
    val foundedYear: Int = 0,
    val population: Int = 0,
    val sackedCount: Int = 0,
    val ruined: Boolean = false,
    val structures: List<Structure> = emptyList(),
    val stability: Int = 100,
    val loyalty: Int = 100,
    val garrison: Int = 0,
    val influences: List<Influence> = emptyList()
)

enum class StructureKind(val label: String) {
    TEMPLE("temple"),
    TAVERN("tavern"),
    MARKET("market"),
    GUILD("guild hall"),
    CATACOMB("catacomb")
}

/** A named building inside a settlement, with the person history remembers keeping it. */
data class Structure(
    val id: Int,
    val siteId: Int,
    val kind: StructureKind,
    val name: String,
    val foundedYear: Int,
    val keeperFigureId: Int? = null,
    /** The organization this hall belongs to — the guild behind the guild hall. */
    val organizationId: Int? = null,
    val ruined: Boolean = false
)

/** A place people actually live, as opposed to dungeons and standing stones. */
val Site.isSettlement: Boolean
    get() = kind in setOf(
        SiteKind.CAPITAL, SiteKind.CITY, SiteKind.TOWN,
        SiteKind.VILLAGE, SiteKind.HOLDFAST
    )

enum class EventKind {
    FOUNDING, GROWTH, WAR, BATTLE, TREATY, SCHISM, PLAGUE, FAMINE, FLOOD, SEALING,
    PROPHECY, DEATH, SUCCESSION, MIGRATION, RUIN, DESTRUCTION, ARTIFACT, BEAST, PACT,
    TAVERN, CHARTER, GUILDHALL, CONSECRATION, CATACOMB,
    CLAIM, MARRIAGE, REBELLION, PLOT, RAID,
    COMET, ECLIPSE, MOONWONDER,
    // the magical chronicle: mages, their works, and what became of both
    MAGE, TOME, SCROLL, THEFT, RECOVERY, REDISCOVERY, MAGIC_CONFLICT
}

data class ChronicleEvent(
    val year: Int,
    val kind: EventKind,
    val text: String,
    /** The event's named subject — a comet's coined name, when it has one. */
    val subject: String = ""
)

data class Age(val name: String, val startYear: Int, val endYear: Int)

/** A god of one people's pantheon. */
data class Deity(
    val id: Int,
    val name: String,
    val cultureId: Int,
    val domain: String,
    val epithet: String
)

/** A historical figure: lineage, titles, and the deeds the chroniclers kept. */
data class Figure(
    val name: String,
    val cultureId: Int,
    val bornYear: Int,
    val diedYear: Int?,
    val title: String,
    /** The realm this figure serves or rules, when one has a hold on them. */
    val realmId: Int? = null,
    /** The organization this figure leads or belongs to, when one has them. */
    val orgId: Int? = null,
    val parentIds: List<Int> = emptyList(),
    val feats: List<String> = emptyList(),
    val houseId: Int? = null,
    val spouseId: Int? = null,
    val friends: List<Int> = emptyList(),
    val rivals: List<Int> = emptyList(),
    val deathCause: String? = null
) {
    val lifespan: Int? get() = diedYear?.let { it - bornYear }
}

/** A noble house: a named bloodline whose members press claims and contest seats. */
data class House(
    val id: Int,
    val name: String,
    val cultureId: Int,
    val foundedYear: Int,
    val headFigureId: Int?,
    /** The realm this house keeps its seats under, when it has a patron. */
    val patronRealmId: Int? = null,
    /** The bloodlines this house feuds with, by the house id. */
    val rivalHouseIds: List<Int> = emptyList(),
    val extinct: Boolean = false
)

/** A pressed right to a settlement: who claims it, how strongly, and why. */
data class Claim(
    val id: Int,
    val siteId: Int,
    /** The actor pressing the right — nearly always a realm, once a rising. */
    val claimantId: Int?,
    val claimantFigureId: Int?,
    val strength: Int,
    val origin: String,
    val madeYear: Int
)

/** The kind of social weight an influence carries in a settlement. */
enum class InfluenceKind(val label: String) {
    REALM("sovereign"),
    HOUSE("house"),
    GUILD("guild"),
    TEMPLE("temple"),
    GARRISON("garrison"),
    TOWNSFOLK("townsfolk")
}

/**
 * One actor's slice of a settlement's politics, in shares of a hundred.
 * Influence is the ability to sway a place — never the owning of it.
 */
data class Influence(
    val kind: InfluenceKind,
    val actorId: Int?,
    val label: String,
    val share: Int
)

/** What kind of conflict this is; each kind keeps its own rules and ends. */
enum class ConflictKind(val label: String) {
    REALM_WAR("realm war"),
    REBELLION("rebellion"),
    RAID("raid"),
    GUILD_CONFLICT("guild conflict"),
    HOUSE_FEUD("house feud")
}

/** A conflict between actors, with its battles and how it ended. */
data class Conflict(
    val id: Int,
    val kind: ConflictKind,
    val attackerId: Int,
    val defenderId: Int,
    val cause: String,
    val startYear: Int,
    val endYear: Int?,
    val battles: List<Battle>,
    val outcome: String
) {
    val totalDead: Int get() = battles.sumOf { it.dead }
}

data class Battle(
    val year: Int,
    val siteId: Int,
    val attackerId: Int,
    val defenderId: Int,
    val attackerGeneralId: Int?,
    val defenderGeneralId: Int?,
    val dead: Int,
    val attackerWon: Boolean
)

/** An artifact made by a named hand, and where history lost it. */
data class Artifact(
    val id: Int,
    val name: String,
    val kind: String,
    val makerId: Int,
    val madeYear: Int,
    val keeperSiteId: Int?,
    val whereabouts: String
)

/** A megabeast or night-creature that woke during worldgen. */
data class Beast(
    val id: Int,
    val name: String,
    val kind: String,
    val lairSiteId: Int,
    val wokeYear: Int,
    val slainYear: Int?,
    val slayerId: Int?,
    val raids: Int
) {
    val alive: Boolean get() = slainYear == null
}

data class Rumor(
    val text: String,
    val source: String,
    val daysOld: Int,
    val aboutPlayer: Boolean,
    /** The place the rumor names, if it names one: the seed of a pin on the Bearings sheet. */
    val siteId: Int = -1
)

// ---------------------------------------------------------------------------
// Terrain
// ---------------------------------------------------------------------------

enum class Biome { OCEAN, MARSH, MOOR, DOWNS, FOREST, HILLS, PEAK }

/**
 * The province's edge-to-edge measure in leagues: half of Daggerfall's recorded
 * land — about 15,500 square miles (40,200 km²) — is a square of 42 leagues to
 * a side. Every bearing, journey and hour of travel is measured against it.
 */
const val WORLD_LEAGUES = 42f

data class RiverPoint(val x: Float, val y: Float)

data class River(val name: String, val points: List<RiverPoint>)

/** The province's land itself: height, wetness, and the rivers cut into it. */
class TerrainMap(
    val size: Int,
    val heights: FloatArray,
    val moisture: FloatArray,
    val rivers: List<River>
) {
    fun heightAt(nx: Float, ny: Float): Float {
        val cx = (nx.coerceIn(0f, 0.999f) * size).toInt()
        val cy = (ny.coerceIn(0f, 0.999f) * size).toInt()
        return heights[cy * size + cx]
    }

    fun moistureAt(nx: Float, ny: Float): Float {
        val cx = (nx.coerceIn(0f, 0.999f) * size).toInt()
        val cy = (ny.coerceIn(0f, 0.999f) * size).toInt()
        return moisture[cy * size + cx]
    }

    fun biomeAt(nx: Float, ny: Float): Biome {
        val h = heightAt(nx, ny)
        val m = moistureAt(nx, ny)
        return classify(h, m)
    }

    companion object {
        fun classify(h: Float, m: Float): Biome = when {
            h < 0.34f -> Biome.OCEAN
            h < 0.42f -> if (m > 0.55f) Biome.MARSH else Biome.MOOR
            h < 0.60f -> if (m > 0.48f) Biome.FOREST else Biome.DOWNS
            h < 0.76f -> Biome.HILLS
            else -> Biome.PEAK
        }
    }
}

// ---------------------------------------------------------------------------
// The world
// ---------------------------------------------------------------------------

/** Everything worldgen produced for one seed. Deterministic: same seed, same province. */
data class World(
    val seed: Long,
    val seedCode: String,
    val provinceName: String,
    val ages: List<Age>,
    val cultures: List<Culture>,
    val realms: List<Realm>,
    val organizations: List<Organization>,
    val groups: List<NonStateGroup>,
    val governments: List<Government>,
    val figures: List<Figure>,
    val deities: List<Deity>,
    val events: List<ChronicleEvent>,
    val sites: List<Site>,
    val conflicts: List<Conflict>,
    val artifacts: List<Artifact>,
    val beasts: List<Beast>,
    val houses: List<House> = emptyList(),
    val claims: List<Claim> = emptyList(),
    val terrain: TerrainMap,
    val rumors: List<Rumor>,
    val currentYear: Int,
    val vaultSiteId: Int,
    val barrowSiteId: Int,
    val ruinCount: Int
) {
    val currentAge: Age get() = ages.last()

    /** Every place by its id, indexed once: the province is far too large to scan. */
    val siteById: Map<Int, Site> by lazy { sites.associateBy { it.id } }

    /** Every actor of every kind, indexed once by id. */
    val actorById: Map<Int, Actor> by lazy {
        buildMap {
            realms.forEach { put(it.id, it) }
            organizations.forEach { put(it.id, it) }
            groups.forEach { put(it.id, it) }
        }
    }

    /** Where each organization keeps or tends ground, indexed by site. */
    private val orgBySite: Map<Int, Organization> by lazy {
        buildMap {
            organizations.forEach { org ->
                org.headquartersSiteId?.let { put(it, org) }
                org.presenceSiteIds.forEach { putIfAbsent(it, org) }
            }
        }
    }

    /** Which group bases itself where, indexed by site. */
    private val groupBySite: Map<Int, NonStateGroup> by lazy {
        buildMap { groups.forEach { it.baseSiteId?.let { site -> put(site, it) } } }
    }

    val livingBeasts: List<Beast> get() = beasts.filter { it.alive }
    val lostArtifacts: List<Artifact> get() = artifacts.filter { it.keeperSiteId == null }

    fun realm(id: Int?): Realm? = id?.let { realms.firstOrNull { r -> r.id == it } }

    fun organization(id: Int?): Organization? = id?.let { organizations.firstOrNull { o -> o.id == it } }

    fun group(id: Int?): NonStateGroup? = id?.let { groups.firstOrNull { g -> g.id == it } }

    fun government(id: Int?): Government? = id?.let { governments.firstOrNull { g -> g.id == it } }

    fun actor(id: Int?): Actor? = id?.let { actorById[it] }

    /** The organization that keeps its hall or holy ground at this site. */
    fun organizationAt(siteId: Int?): Organization? = siteId?.let { orgBySite[it] }

    /** The group that bases itself at this site. */
    fun groupAt(siteId: Int?): NonStateGroup? = siteId?.let { groupBySite[it] }

    /** The realm that rules a site's ground, when the site has a sovereign. */
    fun realmOf(site: Site?): Realm? = site?.let { realm(it.sovereignRealmId) }

    /**
     * The actor whose ground or keeping this site stands under: the sovereign
     * realm of a settlement, the cult behind a vault, the warband behind a camp.
     */
    fun stewardOf(site: Site?): Actor? = site?.let {
        realm(it.sovereignRealmId) ?: organizationAt(it.id) ?: groupAt(it.id)
    }

    /** The culture whose hands made this place: its steward's people, or none. */
    fun cultureIdOf(siteId: Int?): Int = when (val steward = stewardOf(siteById[siteId ?: -1])) {
        is Realm -> steward.cultureId
        is Organization -> steward.cultureId
        is NonStateGroup -> steward.cultureId
        else -> -1
    }

    /** Where an actor keeps its seat: a capital, a headquarters, or a camp. */
    fun seatSiteIdOf(actor: Actor): Int? = when (actor) {
        is Realm -> actor.capitalSiteId
        is Organization -> actor.headquartersSiteId
        is NonStateGroup -> actor.baseSiteId
    }

    fun site(id: Int): Site = siteById.getValue(id)

    fun siteOrNull(id: Int): Site? = siteById[id]

    fun culture(id: Int): Culture = cultures.first { it.id == id }

    fun figure(id: Int?): Figure? = id?.let { figures.getOrNull(it) }

    fun deity(id: Int?): Deity? = deities.firstOrNull { it.id == id }

    fun beast(id: Int?): Beast? = beasts.firstOrNull { it.id == id }

    fun house(id: Int?): House? = id?.let { hid -> houses.firstOrNull { it.id == hid } }

    /** Every claim pressed on a settlement, strongest first. */
    fun claimsOn(siteId: Int): List<Claim> =
        claims.filter { it.siteId == siteId }.sortedByDescending { it.strength }

    /** Formal wars between realms; raids, feuds and rivalries are their own ledgers. */
    fun warCount(): Int = conflicts.count { it.kind == ConflictKind.REALM_WAR }

    fun conflictsOf(kind: ConflictKind): List<Conflict> = conflicts.filter { it.kind == kind }

    /** A short line about whom an actor resents or trusts most, for the journal. */
    fun relationLine(actor: Actor): String? {
        if (actor.relations.isEmpty()) return null
        val (otherId, value) = actor.relations.entries.minByOrNull { kotlin.math.abs(it.value) } ?: return null
        val other = actor(otherId) ?: return null
        return when {
            value <= -55 -> "sworn enemy of ${other.name}"
            value <= -20 -> "bitter rivals of ${other.name}"
            value >= 55 -> "sworn ally of ${other.name}"
            value >= 20 -> "bound by pact to ${other.name}"
            else -> null
        }
    }

    /** Three most recent weighty events — the generation log shown on the title plate. */
    fun highlightEvents(count: Int): List<ChronicleEvent> {
        val weighty = events.filter {
            it.kind == EventKind.SCHISM || it.kind == EventKind.FLOOD ||
                it.kind == EventKind.SEALING || it.kind == EventKind.FOUNDING ||
                it.kind == EventKind.WAR || it.kind == EventKind.BEAST ||
                it.kind == EventKind.ARTIFACT || it.kind == EventKind.DESTRUCTION ||
                it.kind == EventKind.COMET || it.kind == EventKind.ECLIPSE ||
                it.kind == EventKind.MOONWONDER
        }
        val pool = if (weighty.size >= count) weighty else events
        return pool.takeLast(count)
    }

    fun eventsInAge(age: Age): List<ChronicleEvent> =
        events.filter { it.year in age.startYear..age.endYear }
}

/** Player standing with an actor, shown as a tick-marked bar. */
fun standingLabel(value: Int): String = when {
    value <= -60 -> "Blood-owed"
    value <= -25 -> "Hostile"
    value < 10 -> "Watchful"
    value < 45 -> "Known"
    value < 75 -> "Welcomed"
    else -> "Sworn"
}
