package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.ChronicleEvent
import com.rork.hollowmarch.world.EventKind
import com.rork.hollowmarch.world.Site
import com.rork.hollowmarch.world.World
import com.rork.hollowmarch.world.isSettlement
import kotlin.random.Random

/**
 * The province's magical history, forged once from the seed: mages who lived
 * and taught, spells they created through the ordinary [SpellCalc] forge, the
 * tomes and scrolls that carried their work, and the chronicle of what became
 * of it all — sold, stolen, lost in war, and found again.
 *
 * Nothing here is saved: the same world seed forges the same history, always.
 * The spells history creates are ordinary [Spell] objects — the same magic the
 * player forges — registered into the living registry at wake.
 */
object MagicHistory {

    /** Forge the whole magical ledger of a province. Same seed, same history. */
    fun forge(world: World): MagicLedger {
        val rng = Random(world.seed * 104729L + 7717L)
        val settlements = world.sites.filter { it.isSettlement }
        val deadPlaces = world.sites.filter { !it.isSettlement }
        val livingPlaces = settlements.ifEmpty { world.sites }
        val hidingPlaces = deadPlaces.ifEmpty { livingPlaces }

        fun personName(): String = coinPersonName(rng)
        fun aYear(after: Int, before: Int): Int =
            rng.nextInt((before - after).coerceAtLeast(1)) + after

        // ---------------------------------------------------------------- mages
        val mages = mutableListOf<Mage>()
        val count = (world.currentYear / 40).coerceIn(6, 24)
        repeat(count) {
            val culture = world.cultures[rng.nextInt(world.cultures.size)]
            val home = livingPlaces[rng.nextInt(livingPlaces.size)]
            val archetype = rollArchetype(rng)
            val craft = archetype.craftMin + rng.nextInt(archetype.craftSpan + 1)
            val born = 1 + rng.nextInt((world.currentYear - 25).coerceAtLeast(10))
            val lifespan = 40 + rng.nextInt(55)
            val died = if (born + lifespan < world.currentYear) born + lifespan else null
            mages += Mage(
                id = mages.size,
                name = personName(),
                cultureId = culture.id,
                powerId = home.sovereignRealmId ?: -1,
                homeSiteId = home.id,
                archetype = archetype,
                bornYear = born,
                diedYear = died,
                spellcraft = craft
            )
        }

        // The craft each soul wakes with: components of the working library.
        val allComponentIds = ComponentRegistry.all().map { it.id }
        mages.forEach { mage ->
            val knows = (2 + mage.spellcraft / 2).coerceAtMost(allComponentIds.size)
            mage.knownComponents += STARTER_MAGIC
            mage.knownComponents += allComponentIds.shuffled(rng).take(knows)
            // and the lore formulas their road reaches
            val craft = mage.spellcraft
            val pool = SpellGrimoire.byTier(SpellGrimoire.tierFor(craft)) +
                SpellGrimoire.byTier(SpellGrimoire.tierFor(craft / 2))
            mage.knownSpells += pool.shuffled(rng).take(1 + rng.nextInt(3)).map { it.id }
            mage.biography += "A ${mage.archetype.label} of " +
                "${world.site(mage.homeSiteId).name}, born in the year ${mage.bornYear}."
        }

        // ------------------------------------------------------- apprenticeships
        mages.sortedBy { it.bornYear }.forEach { student ->
            val elders = mages.filter { other ->
                val alive = other.diedYear?.let { it > student.bornYear + 3 } ?: true
                other.id != student.id && other.bornYear + 15 <= student.bornYear && alive
            }
            if (elders.isNotEmpty() && rng.nextInt(100) < 55) {
                val teacher = elders[rng.nextInt(elders.size)]
                student.teacherId = teacher.id
                teacher.studentIds += student.id
                student.knownComponents += teacher.knownComponents
                student.knownSpells += teacher.knownSpells
                student.biography += "Taught by ${teacher.name}."
            }
        }

        // ------------------------------------------------------- new spells
        val chronSpells = mutableListOf<Spell>()
        val usedSpellNames = mutableSetOf<String>()
        val chronicle = mutableListOf<ChronicleEvent>()

        mages.forEach { mage ->
            val deathYear = mage.diedYear ?: world.currentYear
            if (mage.spellcraft < 8) return@forEach
            val creations = if (mage.archetype == MageArchetype.MASTER) 1 + rng.nextInt(3)
            else if (rng.nextInt(100) < 55) 1 + rng.nextInt(2) else 0
            repeat(creations) {
                val year = aYear(mage.bornYear + 12, deathYear.coerceAtLeast(mage.bornYear + 13))
                val effects = buildEffects(rng, mage)
                if (effects.isEmpty()) return@repeat
                val name = coinSpellName(rng, usedSpellNames)
                val spell = SpellCalc.forge(
                    id = "chron-${chronSpells.size}",
                    name = name,
                    creator = mage.name,
                    effects = effects
                )
                chronSpells += spell
                mage.createdSpells += spell.id
                mage.knownSpells += spell.id
                mage.biography += "Bound the spell $name in the year $year."
                chronicle += ChronicleEvent(
                    year, EventKind.MAGE,
                    "${mage.name} binds the spell $name.", name
                )
            }
        }

        fun spellNameOf(spellId: String): String =
            chronSpells.firstOrNull { it.id == spellId }?.name
                ?: SpellGrimoire.get(spellId)?.name
                ?: "a formula"

        fun spellValueOf(spellId: String, quality: ScrollQuality): Int {
            val spell = chronSpells.firstOrNull { it.id == spellId } ?: SpellGrimoire.get(spellId)
                ?: return 5
            return ScrollRules.value(spell, quality)
        }

        // ------------------------------------------------------------- works
        val works = mutableListOf<TomeWork>()
        val tomes = mutableListOf<Tome>()
        val scrolls = mutableListOf<Scroll>()
        var nextScrollId = 1

        mages.forEach { mage ->
            val deathYear = mage.diedYear ?: world.currentYear
            val writes = when {
                mage.createdSpells.isNotEmpty() -> 1 + rng.nextInt(2)
                mage.spellcraft >= 12 && rng.nextInt(100) < 45 -> 1
                else -> 0
            }
            repeat(writes) {
                val recordedSpells = mage.createdSpells.ifEmpty {
                    mage.knownSpells.shuffled(rng).take(1 + rng.nextInt(2))
                }
                val recordedComponents = mage.knownComponents
                    .intersect(
                        recordedSpells.flatMap { id ->
                            chronSpells.firstOrNull { it.id == id }?.effects
                                ?.map { it.componentId } ?: emptyList()
                        }.toSet()
                    )
                    .plus(mage.knownComponents.shuffled(rng).take(rng.nextInt(3)))
                if (recordedSpells.isEmpty() && recordedComponents.isEmpty()) return@repeat
                val year = aYear(mage.bornYear + 15, deathYear.coerceAtLeast(mage.bornYear + 16))
                val title = coinTomeTitle(rng, mage.name, recordedSpells.mapNotNull { id ->
                    chronSpells.firstOrNull { it.id == id }?.name
                })
                val work = TomeWork(
                    id = works.size,
                    title = title,
                    author = mage.name,
                    authorId = mage.id,
                    year = year,
                    spellIds = recordedSpells,
                    componentIds = recordedComponents.toList(),
                    schools = recordedSpells.mapNotNull { id ->
                        chronSpells.firstOrNull { it.id == id }?.schools
                    }.flatten().toSet(),
                    quality = when (mage.spellcraft) {
                        in 16..Int.MAX_VALUE -> Quality.LEGENDARY
                        in 13..15 -> Quality.SUPERB
                        in 9..12 -> Quality.FINE
                        else -> Quality.HONEST
                    },
                    copies = 1 + rng.nextInt(3),
                    significance = TomeSignificance.COMMON
                )
                works += work
                mage.createdWorks += work.id
                chronicle += ChronicleEvent(
                    year, EventKind.TOME,
                    "${mage.name} completes \"$title\", recording " +
                        "${recordedSpells.size} spell" +
                        (if (recordedSpells.size == 1) "" else "s") + ".",
                    title
                )
                // --- the physical copies, and what became of them
                var copyId = tomes.size
                repeat(work.copies) { copyIndex ->
                    val copy = Tome(
                        id = copyId++,
                        workId = work.id,
                        copyIndex = copyIndex,
                        owner = if (copyIndex == 0) mage.name else personName(),
                        siteId = if (copyIndex == 0) mage.homeSiteId
                        else livingPlaces[rng.nextInt(livingPlaces.size)].id,
                        destroyed = false,
                        provenance = mutableListOf(
                            if (copyIndex == 0) "Written in ${mage.name}'s own hand, year $year."
                            else "Copied from ${mage.name}'s work, year $year."
                        )
                    )
                    simulateCopyLife(
                        copy, work, world, mages, livingPlaces, hidingPlaces,
                        ::spellNameOf, rng, chronicle
                    )
                    tomes += copy
                }
            }

            // --- scrolls: portable formulas, spent and kept
            val scrollable = mage.knownSpells.toList()
            if (scrollable.isNotEmpty() && rng.nextInt(100) < 65) {
                repeat(1 + rng.nextInt(3)) {
                    val spellId = scrollable[rng.nextInt(scrollable.size)]
                    val quality = ScrollRules.roll(rng, mage.spellcraft)
                    val uses = ScrollRules.maxUses(quality)
                    val year = aYear(mage.bornYear + 10, deathYear.coerceAtLeast(mage.bornYear + 11))
                    val spellName = spellNameOf(spellId)
                    val resting = if (rng.nextInt(100) < 35) {
                        hidingPlaces[rng.nextInt(hidingPlaces.size)].id
                    } else {
                        livingPlaces[rng.nextInt(livingPlaces.size)].id
                    }
                    scrolls += Scroll(
                        id = nextScrollId++,
                        spellId = spellId,
                        creator = mage.name,
                        year = year,
                        quality = quality,
                        maxUses = uses,
                        remainingUses = uses,
                        value = spellValueOf(spellId, quality),
                        provenance = listOf("Scribed by ${mage.name}, year $year."),
                        siteId = resting
                    )
                    if (quality.ordinal >= ScrollQuality.SUPERIOR.ordinal) {
                        chronicle += ChronicleEvent(
                            year, EventKind.SCROLL,
                            "${mage.name} scribes a ${quality.label} scroll of " +
                                "$spellName, its power good for $uses workings.",
                            spellName
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------- significance
        works.forEach { work ->
            val generations = mageGenerations(work, mages)
            work.significance = TomeStanding.classify(
                TomeStanding.score(
                    spells = work.spellIds.size,
                    rareComponents = work.componentIds.size,
                    generations = generations,
                    events = work.historyEvents,
                    age = world.currentYear - work.year,
                    masterAuthor = mages.firstOrNull { it.id == work.authorId }
                        ?.archetype == MageArchetype.MASTER,
                    rediscovered = work.rediscovered
                )
            )
        }

        // ------------------------------------------------- conflicts over works
        works.filter { it.significance.ordinal >= TomeSignificance.IMPORTANT.ordinal }
            .forEach { work ->
                if (rng.nextInt(100) >= 45) return@forEach
                val year = aYear(work.year + 5, world.currentYear)
                val actors = (world.realms + world.organizations).filter { !it.extinct }
                work.historyEvents++
                val text = if (actors.size >= 2) {
                    val a = actors[rng.nextInt(actors.size)]
                    val b = actors[rng.nextInt(actors.size)]
                    "${a.name} and ${b.name} contest \"${work.title}\"; " +
                        "swords are drawn over a book."
                } else {
                    "The library that holds \"${work.title}\" is fought over."
                }
                chronicle += ChronicleEvent(year, EventKind.MAGIC_CONFLICT, text, work.title)
            }

        chronicle.sortBy { it.year }
        return MagicLedger(
            world = world,
            mages = mages,
            works = works,
            tomes = tomes,
            scrolls = scrolls,
            chronSpells = chronSpells,
            chronicle = chronicle
        )
    }

    // ------------------------------------------------------------- helpers

    private val NAME_HEAD = listOf(
        "Vor", "Mael", "Oth", "Ser", "Thal", "Erev", "Kal", "Mor",
        "Ish", "Ael", "Dun", "Fer", "Bran", "Cael", "Lys", "Rhia"
    )
    private val NAME_MIDDLE = listOf("an", "eth", "ir", "oth", "ar", "ien", "us", "ael", "or", "is")
    private val NAME_TAIL = listOf(
        "dric", "vane", "morn", "wyn", "gar", "shold", "reth", "line", "velle", "dain"
    )

    /** A name of the province, coined from its own syllables under the rng. */
    private fun coinPersonName(rng: Random): String =
        NAME_HEAD[rng.nextInt(NAME_HEAD.size)] +
            NAME_MIDDLE[rng.nextInt(NAME_MIDDLE.size)] +
            (if (rng.nextInt(3) == 0) NAME_TAIL[rng.nextInt(NAME_TAIL.size)] else "")

    private fun rollArchetype(rng: Random): MageArchetype {
        val weights = listOf(
            MageArchetype.HEDGE to 5, MageArchetype.COURT to 4, MageArchetype.SCHOLAR to 4,
            MageArchetype.BATTLE to 3, MageArchetype.HERMIT to 3, MageArchetype.MASTER to 1
        )
        var pick = rng.nextInt(weights.sumOf { it.second })
        weights.forEach { (archetype, weight) ->
            pick -= weight
            if (pick < 0) return archetype
        }
        return MageArchetype.HEDGE
    }

    /** Shape a new formula from a mage's own knowledge, within their reach. */
    private fun buildEffects(rng: Random, mage: Mage): List<SpellEffect> {
        val stats = StatBlock.balanced()
        val craft = mage.spellcraft
        val knownIds = mage.knownComponents.toSet()
        val pool = ComponentRegistry.all().filter { it.id in knownIds }
        if (pool.isEmpty()) return emptyList()
        val howMany = (1 + rng.nextInt(2)).coerceAtMost(SpellForge.maxEffects(craft, stats))
        val budget = SpellForge.complexityBudget(craft, stats)
        val effects = mutableListOf<SpellEffect>()
        var complexity = 0f
        pool.shuffled(rng).forEach { component ->
            if (effects.size >= howMany || complexity >= budget) return@forEach
            val magnitude = (component.magnitude.first..SpellForge.magnitudeCap(component, craft, stats))
                .random(rng)
            val duration = if (component.duration.last > 0) {
                (component.duration.first..SpellForge.durationCap(component, craft, stats)).random(rng)
            } else 0
            val delivery = component.deliveries.firstOrNull { it != Delivery.SELF || effects.isEmpty() }
                ?: component.deliveries.first()
            val area = if (component.area.endInclusive > 0f && delivery == Delivery.AREA) {
                val cap = SpellForge.areaCap(component, craft, stats)
                component.area.start + rng.nextFloat() * (cap - component.area.start)
            } else 0f
            val range = if (delivery == Delivery.TARGET || delivery == Delivery.AREA) {
                (component.range.first..SpellForge.rangeCap(component, craft)).random(rng)
            } else 1
            val target = when (component.targetKind) {
                MagicTargetKind.ATTRIBUTE -> MAGIC_TARGET_ATTRIBUTES.random(rng).name
                MagicTargetKind.SKILL -> Skill.entries.random(rng).name
                MagicTargetKind.NONE -> ""
            }
            val effect = SpellEffect(
                componentId = component.id,
                magnitude = magnitude,
                duration = duration,
                area = area,
                delivery = delivery,
                range = range,
                target = target
            )
            complexity += SpellCalc.complexity(listOf(effect))
            effects += effect
        }
        return effects
    }

    private val SPELL_FIRST = listOf(
        "King's", "Winter's", "Ashen", "Pale", "Black", "Seven", "Iron",
        "Silent", "Sallow", "Grave", "Hollow", "Last", "Sundered", "Bitter"
    )
    private val SPELL_SECOND = listOf(
        "Pyre", "Crown", "Veil", "Choir", "Marrow", "Lantern", "Verdict",
        "Lament", "Gambit", "Threnody", "Vigil", "Covenant", "Ward", "Brand"
    )

    private fun coinSpellName(rng: Random, used: MutableSet<String>): String {
        repeat(24) {
            val name = "${SPELL_FIRST[rng.nextInt(SPELL_FIRST.size)]} " +
                SPELL_SECOND[rng.nextInt(SPELL_SECOND.size)]
            if (used.add(name)) return name
        }
        var n = 2
        while (true) {
            val name = "${SPELL_FIRST[rng.nextInt(SPELL_FIRST.size)]} " +
                SPELL_SECOND[rng.nextInt(SPELL_SECOND.size)] + " $n"
            if (used.add(name)) return name
            n++
        }
    }

    private val TOME_FIRST = listOf(
        "Treatise", "Principles", "Codex", "Apocryphon", "Litany", "Testament", "Primer"
    )
    private val TOME_ADJ = listOf(
        "Black", "Silver", "Sevenfold", "Pale", "Hidden", "Ashen", "Deep", "Elder"
    )

    private fun coinTomeTitle(rng: Random, author: String, spells: List<String>): String {
        val named = spells.firstOrNull()
        return when (rng.nextInt(4)) {
            0 -> "The ${TOME_ADJ[rng.nextInt(TOME_ADJ.size)]} ${TOME_FIRST[rng.nextInt(TOME_FIRST.size)]}"
            1 -> "The ${TOME_FIRST[rng.nextInt(TOME_FIRST.size)]} of the " +
                SPELL_SECOND[rng.nextInt(SPELL_SECOND.size)]
            2 -> named?.let { "The Study of $it" }
                ?: "The ${TOME_FIRST[rng.nextInt(TOME_FIRST.size)]} of ${author.split(" ").first()}"
            else -> "${author.split(" ").first()}'s ${TOME_FIRST[rng.nextInt(TOME_FIRST.size)]}"
        }
    }

    /** How many teacher-to-student generations a work passed down. */
    private fun mageGenerations(work: TomeWork, mages: List<Mage>): Int {
        val author = mages.firstOrNull { it.id == work.authorId } ?: return 0
        var generations = 0
        var frontier = listOf(author.id)
        val seen = mutableSetOf(author.id)
        while (frontier.isNotEmpty() && generations < 12) {
            val next = mages.filter { it.teacherId in frontier && it.id !in seen }.map { it.id }
            if (next.isEmpty()) break
            seen += next
            frontier = next
            generations++
        }
        return generations
    }

    /**
     * The years after a copy leaves its author's hands: sold, inherited,
     * stolen, lost to war or fire, and — if any copy endures — found again.
     */
    private fun simulateCopyLife(
        copy: Tome,
        work: TomeWork,
        world: World,
        mages: List<Mage>,
        livingPlaces: List<Site>,
        hidingPlaces: List<Site>,
        spellNameOf: (String) -> String,
        rng: Random,
        chronicle: MutableList<ChronicleEvent>
    ) {
        var year = work.year + rng.nextInt(5) + 1
        var lost = false
        fun name(): String = coinPersonName(rng)

        repeat(1 + rng.nextInt(3)) {
            if (copy.destroyed) return
            year += 5 + rng.nextInt(30)
            if (year >= world.currentYear) return
            when (rng.nextInt(10)) {
                0, 1 -> {
                    val buyer = name()
                    copy.provenance += "Sold to $buyer, year $year."
                    copy.owner = buyer
                    chronicle += ChronicleEvent(
                        year, EventKind.TOME,
                        "\"${work.title}\" is sold to $buyer.", work.title
                    )
                }
                2 -> {
                    copy.provenance += "Stolen by a rival, year $year."
                    chronicle += ChronicleEvent(
                        year, EventKind.THEFT,
                        "\"${work.title}\" is stolen from ${copy.owner}.",
                        work.title
                    )
                    copy.owner = name()
                    work.historyEvents++
                }
                3, 4 -> {
                    if (lost) return
                    val resting = hidingPlaces[rng.nextInt(hidingPlaces.size)]
                    copy.siteId = resting.id
                    copy.owner = "lost"
                    lost = true
                    copy.provenance += "Lost at ${resting.name}, year $year."
                    chronicle += ChronicleEvent(
                        year, EventKind.TOME,
                        "\"${work.title}\" is lost at ${resting.name}.", work.title
                    )
                }
                5 -> {
                    copy.destroyed = true
                    copy.siteId = MagicLedger.UNPLACED
                    copy.provenance += "Destroyed, year $year."
                    chronicle += ChronicleEvent(
                        year, EventKind.TOME,
                        "A copy of \"${work.title}\" is destroyed.", work.title
                    )
                }
                else -> {
                    // the work passes quietly down: inheritance or quiet keeping
                    copy.provenance += "Inherited by ${copy.owner}'s student, year $year."
                }
            }
        }

        // A lost copy may yet be found, and its art return to the world.
        if (lost && !copy.destroyed && rng.nextInt(100) < 55) {
            val findYear = (year + 20 + rng.nextInt(120)).coerceAtMost(world.currentYear - 2)
            val finder = mages.filter { it.bornYear + 10 <= findYear && it.teacherId == -1 }
                .randomOrNull(rng)
            copy.siteId = livingPlaces[rng.nextInt(livingPlaces.size)].id
            copy.owner = finder?.name ?: "a scholar"
            copy.provenance += "Found again at year $findYear by ${copy.owner}."
            work.rediscovered = true
            work.historyEvents++
            val returned = work.spellIds.map { spellNameOf(it) }
            chronicle += ChronicleEvent(
                findYear, EventKind.REDISCOVERY,
                "\"${work.title}\" is found again, and " +
                    (returned.take(2).ifEmpty { listOf("a forgotten art") }.joinToString(" and ")) +
                    " return" + (if (returned.size == 1) "s" else "") + " to the world.",
                work.title
            )
            finder?.let { mage ->
                mage.knownSpells += work.spellIds
                mage.knownComponents += work.componentIds
                mage.biography += "Found \"${work.title}\" in the year $findYear."
            }
        }
    }
}
