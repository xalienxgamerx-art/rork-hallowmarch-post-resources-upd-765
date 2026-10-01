package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.ChronicleEvent
import com.rork.hollowmarch.world.World
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The physical side of magic: scrolls, tomes, and the historical mages whose
 * work made them. A scroll is a portable formula — used, never learned. A tome
 * is a studyable record — studied, and what it holds becomes knowledge. Both
 * are ordinary [Item]s whose `uid` names their entry in the [MagicLedger].
 */

// ---------------------------------------------------------------------------
// Scrolls
// ---------------------------------------------------------------------------

/** How well a scroll was made. The make alone decides how many castings survive. */
enum class ScrollQuality(val label: String, val uses: Int, val valueMult: Float) {
    CRUDE("crude", 1, 0.6f),
    COMMON("common", 2, 1.0f),
    FINE("fine", 3, 1.7f),
    SUPERIOR("superior", 5, 2.6f),
    MASTERWORK("masterwork", 8, 4.2f);
}

/**
 * The scroll's tunable numbers, in one door: how many uses each make allows,
 * what a scroll costs, and how quality is rolled. Change these values and no
 * scroll logic anywhere needs to know.
 */
object ScrollRules {
    /** Baseline uses by make; the single place to retune scroll endurance. */
    val USES_BY_QUALITY: Map<ScrollQuality, Int> =
        ScrollQuality.entries.associateWith { it.uses }

    fun maxUses(quality: ScrollQuality): Int = USES_BY_QUALITY.getValue(quality)

    /**
     * A scribe of greater Spellcrafting tends finer work: crude quills at the
     * bottom of the road, masterwork at its top. Deterministic under [rng].
     */
    fun roll(rng: Random, spellcraft: Int): ScrollQuality {
        val weights = listOf(
            ScrollQuality.CRUDE to (30 - spellcraft).coerceAtLeast(2),
            ScrollQuality.COMMON to 26,
            ScrollQuality.FINE to (8 + spellcraft / 2).coerceAtLeast(4),
            ScrollQuality.SUPERIOR to (2 + spellcraft / 3).coerceAtLeast(1),
            ScrollQuality.MASTERWORK to (spellcraft / 5).coerceAtLeast(0)
        )
        var pick = rng.nextInt(weights.sumOf { it.second }.coerceAtLeast(1))
        weights.forEach { (quality, weight) ->
            pick -= weight
            if (pick < 0) return quality
        }
        return ScrollQuality.COMMON
    }

    /** What a steady buyer pays: the formula's worth, carried by the make. */
    fun value(spell: Spell, quality: ScrollQuality): Int =
        (spell.value * quality.valueMult * 0.5f).roundToInt().coerceAtLeast(5)
}

/**
 * One physical scroll: a formula shut in vellum. It is a tool, not a teacher —
 * nothing here ever joins the user's [KnownMagic].
 */
data class Scroll(
    /** The scroll's uid: the same number its [Item] carries. */
    val id: Int,
    val spellId: String,
    val creator: String,
    val year: Int,
    val quality: ScrollQuality,
    val maxUses: Int,
    var remainingUses: Int,
    val value: Int,
    val provenance: List<String>,
    /** Where it rests in the world: a site id, or [MagicLedger.UNPLACED]. */
    var siteId: Int
) {
    val spent: Boolean get() = remainingUses <= 0

    fun encode(): String = listOf(
        "$id", spellId, creator, "$year", quality.name, "$maxUses", "$remainingUses",
        "$value", provenance.joinToString(PROV), "$siteId"
    ).joinToString(FIELD)

    companion object {
        private const val FIELD = "\u001B"
        private const val PROV = "|"

        fun fromEncoded(raw: String): Scroll? {
            val f = raw.split(FIELD)
            if (f.size < 10) return null
            val quality = ScrollQuality.entries.firstOrNull { it.name == f[4] } ?: return null
            return Scroll(
                id = f[0].toIntOrNull() ?: return null,
                spellId = f[1],
                creator = f[2],
                year = f[3].toIntOrNull() ?: 0,
                quality = quality,
                maxUses = f[5].toIntOrNull() ?: quality.uses,
                remainingUses = f[6].toIntOrNull() ?: quality.uses,
                value = f[7].toIntOrNull() ?: 5,
                provenance = f[8].split(PROV).filter { it.isNotBlank() },
                siteId = f[9].toIntOrNull() ?: MagicLedger.UNPLACED
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Tomes
// ---------------------------------------------------------------------------

/** How heavily the chronicle weighs a magical work; emergent from its history. */
enum class TomeSignificance(val label: String, val rank: Int) {
    COMMON("common", 0),
    UNUSUAL("unusual", 1),
    IMPORTANT("important", 2),
    HISTORIC("historic", 3),
    LEGENDARY("legendary", 4);
}

/**
 * The standing a work earns, from its contents and its own history — age,
 * owners, thefts, rediscoveries. Every threshold here is tunable; no tome is
 * legendary merely for being powerful.
 */
object TomeStanding {
    const val UNUSUAL_AT = 8
    const val IMPORTANT_AT = 14
    const val HISTORIC_AT = 22
    const val LEGENDARY_AT = 30

    fun score(
        spells: Int,
        rareComponents: Int,
        generations: Int,
        events: Int,
        age: Int,
        masterAuthor: Boolean,
        rediscovered: Boolean
    ): Int = spells * 4 + rareComponents * 2 + generations * 2 + events * 3 +
        age / 50 + (if (masterAuthor) 6 else 0) + (if (rediscovered) 5 else 0)

    fun classify(score: Int): TomeSignificance = when {
        score >= LEGENDARY_AT -> TomeSignificance.LEGENDARY
        score >= HISTORIC_AT -> TomeSignificance.HISTORIC
        score >= IMPORTANT_AT -> TomeSignificance.IMPORTANT
        score >= UNUSUAL_AT -> TomeSignificance.UNUSUAL
        else -> TomeSignificance.COMMON
    }
}

/**
 * A magical work: the original writing, of which copies may be made. The work
 * holds the knowledge; every physical [Tome] below references it by id.
 */
data class TomeWork(
    val id: Int,
    val title: String,
    val author: String,
    val authorId: Int,
    val year: Int,
    val spellIds: List<String>,
    val componentIds: List<String>,
    val schools: Set<MagicalSchool>,
    val quality: Quality,
    val copies: Int,
    var significance: TomeSignificance,
    /** How many chronicle turns the work took part in — thefts, wars, finds. */
    var historyEvents: Int = 0,
    /** True when a copy of this work was lost and later found again. */
    var rediscovered: Boolean = false
) {
    fun encode(): String = listOf(
        "$id", title, author, "$authorId", "$year",
        spellIds.joinToString(LIST), componentIds.joinToString(LIST),
        schools.joinToString(LIST) { it.name }, quality.name, "$copies",
        significance.name, "$historyEvents", "$rediscovered"
    ).joinToString(FIELD)

    companion object {
        private const val FIELD = "\u001B"
        private const val LIST = ","

        fun fromEncoded(raw: String): TomeWork? {
            val f = raw.split(FIELD)
            if (f.size < 13) return null
            val quality = Quality.entries.firstOrNull { it.name == f[8] } ?: return null
            val significance =
                TomeSignificance.entries.firstOrNull { it.name == f[10] } ?: return null
            return TomeWork(
                id = f[0].toIntOrNull() ?: return null,
                title = f[1],
                author = f[2],
                authorId = f[3].toIntOrNull() ?: -1,
                year = f[4].toIntOrNull() ?: 0,
                spellIds = f[5].split(LIST).filter { it.isNotBlank() },
                componentIds = f[6].split(LIST).filter { it.isNotBlank() },
                schools = f[7].split(LIST).mapNotNull { n ->
                    MagicalSchool.entries.firstOrNull { it.name == n }
                }.toSet(),
                quality = quality,
                copies = f[9].toIntOrNull() ?: 1,
                significance = significance,
                historyEvents = f[11].toIntOrNull() ?: 0,
                rediscovered = f[12] == "true"
            )
        }
    }
}

/** One physical copy of a [TomeWork]: its own owner, place, and provenance. */
data class Tome(
    val id: Int,
    val workId: Int,
    val copyIndex: Int,
    var owner: String,
    /** Where it rests: a site id, or [MagicLedger.UNPLACED]. */
    var siteId: Int,
    var destroyed: Boolean,
    val provenance: MutableList<String>
) {
    fun encode(): String = listOf(
        "$id", "$workId", "$copyIndex", owner, "$siteId", "$destroyed",
        provenance.joinToString(PROV)
    ).joinToString(FIELD)

    companion object {
        private const val FIELD = "\u001B"
        private const val PROV = "|"

        fun fromEncoded(raw: String): Tome? {
            val f = raw.split(FIELD)
            if (f.size < 7) return null
            return Tome(
                id = f[0].toIntOrNull() ?: return null,
                workId = f[1].toIntOrNull() ?: return null,
                copyIndex = f[2].toIntOrNull() ?: 0,
                owner = f[3],
                siteId = f[4].toIntOrNull() ?: MagicLedger.UNPLACED,
                destroyed = f[5] == "true",
                provenance = f[6].split(PROV).filter { it.isNotBlank() }.toMutableList()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Historical mages
// ---------------------------------------------------------------------------

/** The road a historical mage walked: an identity, never a rigid class. */
enum class MageArchetype(val label: String, val craftMin: Int, val craftSpan: Int) {
    HEDGE("hedge mage", 5, 6),
    COURT("court mage", 8, 7),
    SCHOLAR("scholar", 11, 7),
    BATTLE("battle mage", 10, 7),
    HERMIT("hermit", 9, 7),
    MASTER("master mage", 15, 6);
}

/** A mage of the province's history: a real historical actor, and a knowledge ledger. */
data class Mage(
    val id: Int,
    val name: String,
    val cultureId: Int,
    val powerId: Int,
    val homeSiteId: Int,
    val archetype: MageArchetype,
    val bornYear: Int,
    var diedYear: Int?,
    var teacherId: Int = -1,
    val studentIds: MutableList<Int> = mutableListOf(),
    val spellcraft: Int,
    val knownComponents: MutableSet<String> = mutableSetOf(),
    val knownSpells: MutableSet<String> = mutableSetOf(),
    val createdSpells: MutableList<String> = mutableListOf(),
    val createdWorks: MutableList<Int> = mutableListOf(),
    val biography: MutableList<String> = mutableListOf()
) {
    fun encode(): String = listOf(
        "$id", name, "$cultureId", "$powerId", "$homeSiteId", archetype.name,
        "$bornYear", diedYear?.toString() ?: "", "$teacherId",
        studentIds.joinToString(LIST), "$spellcraft",
        knownComponents.joinToString(LIST), knownSpells.joinToString(LIST),
        createdSpells.joinToString(LIST), createdWorks.joinToString(LIST),
        biography.joinToString(PROV)
    ).joinToString(FIELD)

    companion object {
        private const val FIELD = "\u001B"
        private const val LIST = ","
        private const val PROV = "|"

        fun fromEncoded(raw: String): Mage? {
            val f = raw.split(FIELD)
            if (f.size < 16) return null
            val archetype = MageArchetype.entries.firstOrNull { it.name == f[5] } ?: return null
            return Mage(
                id = f[0].toIntOrNull() ?: return null,
                name = f[1],
                cultureId = f[2].toIntOrNull() ?: -1,
                powerId = f[3].toIntOrNull() ?: -1,
                homeSiteId = f[4].toIntOrNull() ?: -1,
                archetype = archetype,
                bornYear = f[6].toIntOrNull() ?: 0,
                diedYear = f[7].toIntOrNull(),
                teacherId = f[8].toIntOrNull() ?: -1,
                studentIds = f[9].split(LIST).mapNotNull { it.toIntOrNull() }.toMutableList(),
                spellcraft = f[10].toIntOrNull() ?: 1,
                knownComponents = f[11].split(LIST).filter { it.isNotBlank() }.toMutableSet(),
                knownSpells = f[12].split(LIST).filter { it.isNotBlank() }.toMutableSet(),
                createdSpells = f[13].split(LIST).filter { it.isNotBlank() }.toMutableList(),
                createdWorks = f[14].split(LIST).mapNotNull { it.toIntOrNull() }.toMutableList(),
                biography = f[15].split(PROV).filter { it.isNotBlank() }.toMutableList()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The ledger
// ---------------------------------------------------------------------------

/**
 * The whole magical ledger of one province: the mages of its history, the works
 * they wrote, the copies that survive, and the scrolls that passed from hand to
 * hand. Deterministic: the same world seed forges the same ledger, so nothing
 * here needs saving — only the deltas the delver causes ride the save
 * ([encodeDelta]).
 */
class MagicLedger(
    val world: World,
    val mages: List<Mage>,
    val works: List<TomeWork>,
    val tomes: List<Tome>,
    val scrolls: List<Scroll>,
    /** The spells history created — ordinary [Spell] objects, registered at wake. */
    val chronSpells: List<Spell>,
    val chronicle: List<ChronicleEvent>
) {
    /** A thing of the ledger nobody has placed anywhere: a working mage's pocket. */
    companion object {
        const val UNPLACED = -1

        /** A scroll or tome the delver lifted from its resting place. */
        const val CARRIED = -2
    }

    /** Works the delver has studied dry, by tome id. Persisted. */
    val playerStudied = mutableSetOf<Int>()

    /** Scrolls and tomes lifted from their resting places, by id. Persisted. */
    val takenFromWorld = mutableSetOf<Int>()

    fun mage(id: Int): Mage? = mages.firstOrNull { it.id == id }
    fun work(id: Int): TomeWork? = works.firstOrNull { it.id == id }
    fun tome(id: Int): Tome? = tomes.firstOrNull { it.id == id }
    fun scroll(id: Int): Scroll? = scrolls.firstOrNull { it.id == id }

    /** The spells history created join the living registry once, by stable id. */
    fun registerSpells(registry: SpellRegistry) {
        chronSpells.forEach { registry.register(it) }
    }

    fun scrollFor(item: Item): Scroll? =
        if (item.archetype == ItemArchetype.SCROLL) scroll(item.uid) else null

    fun tomeFor(item: Item): Tome? =
        if (item.archetype == ItemArchetype.TOME) tome(item.uid) else null

    /** Every surviving copy of a work, destroyed copies excepted. */
    fun copiesOf(workId: Int): List<Tome> = tomes.filter { it.workId == workId && !it.destroyed }

    /** Every historical mage who knows a formula and still lives — or lived past [year]. */
    fun knowersOf(spellId: String, year: Int): List<Mage> = mages.filter { mage ->
        val died = mage.diedYear
        spellId in mage.knownSpells && (died == null || died >= year)
    }

    /** A spell is lost when no living knower and no surviving record holds it. */
    fun spellLostAt(spellId: String, year: Int): Boolean =
        knowersOf(spellId, year).isEmpty() &&
            tomes.none { !it.destroyed && work(it.workId)?.spellIds?.contains(spellId) == true }

    // ------------------------------------------------------------- as items

    /** The [Item] form of a scroll: ashwood vellum, its uid naming the ledger. */
    fun itemFor(scroll: Scroll): Item = Item(
        archetype = ItemArchetype.SCROLL,
        material = Material.ASHWOOD,
        quality = when (scroll.quality) {
            ScrollQuality.CRUDE -> Quality.WORN
            ScrollQuality.COMMON -> Quality.HONEST
            ScrollQuality.FINE -> Quality.FINE
            ScrollQuality.SUPERIOR -> Quality.SUPERB
            ScrollQuality.MASTERWORK -> Quality.LEGENDARY
        },
        uid = scroll.id
    )

    /** The [Item] form of a tome copy: the work's own make, its uid naming the ledger. */
    fun itemFor(tome: Tome): Item = Item(
        archetype = ItemArchetype.TOME,
        material = Material.ASHWOOD,
        quality = work(tome.workId)?.quality ?: Quality.HONEST,
        uid = tome.id
    )

    /** What the delver would pay, beyond the shape's base worth. */
    fun valueFor(item: Item): Int? = when {
        item.archetype == ItemArchetype.SCROLL -> scrollFor(item)?.value
        item.archetype == ItemArchetype.TOME ->
            tomeFor(item)?.let { t -> work(t.workId)?.let { w ->
                (w.spellIds.size * 60 * w.quality.valueMult + w.componentIds.size * 10)
                    .roundToInt().coerceAtLeast(20)
            } }
        else -> null
    }

    /** A reader's name for a scroll or tome; null for ordinary things. */
    fun nameFor(item: Item, spells: SpellRegistry): String? = when {
        item.archetype == ItemArchetype.SCROLL -> scrollFor(item)?.let { s ->
            val spellName = spells.get(s.spellId)?.name ?: "a lost art"
            "a scroll of $spellName (${s.remainingUses} use" +
                (if (s.remainingUses == 1) "" else "s") + " left)"
        }
        item.archetype == ItemArchetype.TOME -> tomeFor(item)?.let { t ->
            work(t.workId)?.title?.let { "a copy of $it" }
        }
        else -> null
    }

    /** The scroll and tome items resting at a site, for containers there to hold. */
    fun itemsForSite(siteId: Int): List<Item> =
        scrolls.filter { it.siteId == siteId && it.id !in takenFromWorld && !it.spent }
            .map { itemFor(it) } +
            tomes.filter { it.siteId == siteId && !it.destroyed && it.id !in takenFromWorld }
                .map { itemFor(it) }

    /** The delver takes a thing from the world: it will not regenerate there. */
    fun markTaken(id: Int) {
        takenFromWorld += id
    }

    // -------------------------------------------------------------- the save

    /**
     * Only what the delver changed rides the save: spent scrolls, studied
     * tomes, things lifted from their places. Everything else the seed answers.
     */
    fun encodeDelta(): String {
        val uses = scrolls.filter { it.remainingUses != it.maxUses }
            .joinToString(",") { "${it.id}=${it.remainingUses}" }
        return listOf(
            "U$uses",
            "P${playerStudied.sorted().joinToString(",")}",
            "T${takenFromWorld.sorted().joinToString(",")}"
        ).joinToString(";")
    }

    fun applyDelta(raw: String?) {
        if (raw.isNullOrBlank()) return
        raw.split(";").forEach { section ->
            if (section.length < 2) return@forEach
            val body = section.substring(1)
            when (section[0]) {
                'U' -> body.split(",").forEach { piece ->
                    val parts = piece.split("=")
                    val scroll = parts.getOrNull(0)?.toIntOrNull()?.let { scroll(it) }
                        ?: return@forEach
                    scroll.remainingUses = parts.getOrNull(1)?.toIntOrNull()
                        ?.coerceIn(0, scroll.maxUses) ?: return@forEach
                }
                'P' -> body.split(",").forEach { playerStudied += it.toIntOrNull() ?: return@forEach }
                'T' -> body.split(",").forEach { takenFromWorld += it.toIntOrNull() ?: return@forEach }
            }
        }
    }
}
