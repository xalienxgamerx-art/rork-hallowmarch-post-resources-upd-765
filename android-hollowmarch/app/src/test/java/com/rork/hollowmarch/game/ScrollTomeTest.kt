package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.EventKind
import com.rork.hollowmarch.world.WorldGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The physical side of magic: scrolls are spent, never learned; tomes are
 * studied, and what they hold becomes knowledge; the whole ledger of mages,
 * works and chronicle is the seed's own, always the same.
 */
class ScrollTomeTest {

    private val world = WorldGenerator.generate(20260923L)
    private val ledger = MagicHistory.forge(world)

    // ---------------------------------------------------------------- scrolls

    @Test
    fun qualityAloneSetsHowManyCastingsAScrollSurvives() {
        assertEquals(1, ScrollRules.maxUses(ScrollQuality.CRUDE))
        assertEquals(2, ScrollRules.maxUses(ScrollQuality.COMMON))
        assertEquals(3, ScrollRules.maxUses(ScrollQuality.FINE))
        assertEquals(5, ScrollRules.maxUses(ScrollQuality.SUPERIOR))
        assertEquals(8, ScrollRules.maxUses(ScrollQuality.MASTERWORK))
    }

    @Test
    fun aScrollCarriesItsFormulaAndItsUses() {
        assertTrue("history scribes scrolls", ledger.scrolls.isNotEmpty())
        ledger.scrolls.forEach { scroll ->
            assertEquals(scroll.maxUses, ScrollRules.maxUses(scroll.quality))
            assertEquals("a fresh scroll is whole", scroll.maxUses, scroll.remainingUses)
            assertTrue(scroll.provenance.isNotEmpty())
            assertNotNull("its spell must exist", ledger.chronSpells.firstOrNull { it.id == scroll.spellId }
                ?: SpellGrimoire.get(scroll.spellId))
        }
    }

    @Test
    fun aScrollIsAnItemHeldLikeAnArm() {
        val scroll = ledger.scrolls.first()
        val item = ledger.itemFor(scroll)
        assertEquals(ItemArchetype.SCROLL, item.archetype)
        assertEquals(scroll.id, item.uid)
        assertEquals(WearSlot.RIGHT_HAND, item.archetype.wear)
        // its uid names the ledger, so the same item reads the same scroll
        assertEquals(scroll, ledger.scrollFor(item))
    }

    @Test
    fun aScrollUsedIsNeverKnowledgeGained() {
        val engine = GameEngine(world, null, null)
        val scroll = engine.magicLedger.scrolls.first()
        val item = engine.magicLedger.itemFor(scroll)
        engine.inventory.add(item)
        // the scroll takes the striking hand, whatever else was there
        engine.equipFromSatchel(item, Hand.RIGHT)
        val spellsBefore = engine.knownMagic.knownSpells.toSet()
        val componentsBefore = engine.knownMagic.knownComponents.toSet()
        val magickaBefore = engine.magicka

        engine.strike()

        assertEquals("a use burns away", scroll.maxUses - 1, scroll.remainingUses)
        assertEquals("no will spent on a scroll's magic", magickaBefore, engine.magicka)
        assertEquals("no spell learned", spellsBefore, engine.knownMagic.knownSpells.toSet())
        assertEquals("no component learned", componentsBefore, engine.knownMagic.knownComponents.toSet())
        assertTrue(
            "the log tells of the scroll",
            engine.log.any { it.text.contains("scroll", ignoreCase = true) }
        )
    }

    @Test
    fun theLastUseUnmakesTheScroll() {
        val engine = GameEngine(world, null, null)
        val scroll = engine.magicLedger.scrolls.first()
        val item = engine.magicLedger.itemFor(scroll)
        engine.inventory.add(item)
        engine.equipFromSatchel(item, Hand.RIGHT)
        repeat(scroll.maxUses) {
            engine.strikeCooldown = 0f
            engine.strike()
        }
        assertTrue("the scroll is spent", scroll.spent)
        assertNull("it leaves the hand", engine.equipment.weaponIn(Hand.RIGHT)
            ?.takeIf { it.archetype == ItemArchetype.SCROLL })
        assertFalse("it leaves the satchel", engine.inventory.all.any { it.uid == scroll.id })
        assertTrue(
            "the log tells of its ending",
            engine.log.any { it.text.contains("crumbles", ignoreCase = true) }
        )
    }

    // ----------------------------------------------------------------- tomes

    @Test
    fun studyingATomeTeachesWhatItHolds() {
        val engine = GameEngine(world, null, null)
        val tome = engine.magicLedger.tomes.firstOrNull { !it.destroyed }
        if (tome == null) {
            assertTrue("this seed keeps no tome; the study test rests", engine.magicLedger.tomes.isEmpty())
            return
        }
        val work = engine.magicLedger.work(tome.workId)!!
        val item = engine.magicLedger.itemFor(tome)
        engine.inventory.add(item)

        assertTrue(engine.studyTome(item))
        work.spellIds.forEach { id ->
            val known = engine.knownMagic.knownSpells.contains(id)
            val registrable = engine.spellRegistry.get(id) != null
            assertTrue("the formula $id is learnable", !registrable || known)
        }
        work.componentIds.forEach { id ->
            assertTrue("the component $id joins the book", engine.knownMagic.knownComponents.contains(id))
        }
        // a work once drawn dry teaches no more
        assertFalse(engine.studyTome(item))
    }

    @Test
    fun anUnstudiedTomeTeachesNothing() {
        val engine = GameEngine(world, null, null)
        val tome = engine.magicLedger.tomes.firstOrNull { !it.destroyed } ?: return
        val item = engine.magicLedger.itemFor(tome)
        val spellsBefore = engine.knownMagic.knownSpells.toSet()
        // possession alone: the book sits in the satchel, unopened
        engine.inventory.add(item)
        assertEquals(spellsBefore, engine.knownMagic.knownSpells.toSet())
        assertFalse(engine.magicLedger.playerStudied.contains(tome.id))
    }

    // ------------------------------------------------------------- the ledger

    @Test
    fun theSameSeedForgesTheSameHistory() {
        val again = MagicHistory.forge(world)
        assertEquals(
            ledger.mages.joinToString("\u001E") { it.encode() },
            again.mages.joinToString("\u001E") { it.encode() }
        )
        assertEquals(
            ledger.chronSpells.joinToString("\u001E") { it.encode() },
            again.chronSpells.joinToString("\u001E") { it.encode() }
        )
        assertEquals(ledger.chronicle, again.chronicle)
        assertEquals(ledger.scrolls.size, again.scrolls.size)
        assertEquals(ledger.tomes.size, again.tomes.size)
    }

    @Test
    fun historySpellsAreOrdinarySpells() {
        assertTrue("mages create new spells", ledger.chronSpells.isNotEmpty())
        val mageNames = ledger.mages.map { it.name }.toSet()
        val ids = mutableSetOf<String>()
        ledger.chronSpells.forEach { spell ->
            assertTrue(spell.effects.isNotEmpty())
            assertTrue("ids are unique", ids.add(spell.id))
            assertTrue("a mage made it: ${spell.creator}", spell.creator in mageNames)
            spell.effects.forEach { effect ->
                assertNotNull(
                    "its components are the world's own",
                    runCatching { ComponentRegistry.get(effect.componentId) }.getOrNull()
                )
            }
        }
    }

    @Test
    fun lineagesRunTeacherToStudent() {
        assertTrue(
            "some mage was taught by another",
            ledger.mages.any { it.teacherId >= 0 && ledger.mage(it.teacherId) != null }
        )
        // every formula a mage made, that mage keeps in the book
        ledger.mages.forEach { mage ->
            mage.createdSpells.forEach { id -> assertTrue(mage.knownSpells.contains(id)) }
        }
    }

    @Test
    fun theMagicalChronicleIsOrderlyAndOfThisWorld() {
        val kinds = setOf(
            EventKind.MAGE, EventKind.TOME, EventKind.SCROLL, EventKind.THEFT,
            EventKind.RECOVERY, EventKind.REDISCOVERY, EventKind.MAGIC_CONFLICT
        )
        assertTrue("the chronicle speaks of magic", ledger.chronicle.isNotEmpty())
        var last = 0
        ledger.chronicle.forEach { event ->
            assertTrue(event.year >= last)
            last = event.year
            assertTrue(event.kind in kinds)
            assertTrue(event.year in 1..world.currentYear)
        }
    }

    @Test
    fun onlyTheDelversMarksRideTheSave() {
        val scroll = ledger.scrolls.first()
        scroll.remainingUses -= 1
        val tome = ledger.tomes.firstOrNull()
        tome?.let { ledger.playerStudied += it.id }
        ledger.markTaken(scroll.id)
        val raw = ledger.encodeDelta()

        val fresh = MagicHistory.forge(world)
        assertNotEquals(
            "a fresh ledger does not know the delver's marks yet",
            scroll.remainingUses, fresh.scroll(scroll.id)!!.remainingUses
        )
        fresh.applyDelta(raw)
        assertEquals(scroll.remainingUses, fresh.scroll(scroll.id)!!.remainingUses)
        assertEquals(ledger.playerStudied, fresh.playerStudied)
        assertEquals(ledger.takenFromWorld, fresh.takenFromWorld)
    }

    @Test
    fun whatTheDelverTookNeverRisesAgain() {
        val scroll = ledger.scrolls.first { it.siteId >= 0 && !it.spent }
        val siteId = scroll.siteId
        assertTrue(ledger.itemsForSite(siteId).any { it.uid == scroll.id })
        ledger.markTaken(scroll.id)
        assertTrue(ledger.itemsForSite(siteId).none { it.uid == scroll.id })
    }

    // ------------------------------------------------------------ significance

    @Test
    fun standingRisesByTheTunablesAlone() {
        assertEquals(TomeSignificance.COMMON, TomeStanding.classify(0))
        assertEquals(TomeSignificance.UNUSUAL, TomeStanding.classify(TomeStanding.UNUSUAL_AT))
        assertEquals(TomeSignificance.IMPORTANT, TomeStanding.classify(TomeStanding.IMPORTANT_AT))
        assertEquals(TomeSignificance.HISTORIC, TomeStanding.classify(TomeStanding.HISTORIC_AT))
        assertEquals(TomeSignificance.LEGENDARY, TomeStanding.classify(TomeStanding.LEGENDARY_AT))
    }

    @Test
    fun worksCarryASignificanceTheSeedAnswersFor() {
        assertTrue("history writes works", ledger.works.isNotEmpty())
        val ranks = ledger.works.map { it.significance.rank }
        assertTrue("the whole ladder may appear", ranks.all { it in 0..4 })
        // a legendary standing never comes from the contents alone
        ledger.works.filter { it.significance == TomeSignificance.LEGENDARY }.forEach { work ->
            val score = TomeStanding.score(
                work.spellIds.size, work.componentIds.size,
                mageGenerations(work), work.historyEvents,
                world.currentYear - work.year,
                ledger.mage(work.authorId)?.archetype == MageArchetype.MASTER,
                work.rediscovered
            )
            assertTrue(score >= TomeStanding.LEGENDARY_AT)
        }
    }

    private fun mageGenerations(work: TomeWork): Int {
        val author = ledger.mage(work.authorId) ?: return 0
        var generations = 0
        var frontier = listOf(author.id)
        val seen = mutableSetOf(author.id)
        while (frontier.isNotEmpty() && generations < 12) {
            val next = ledger.mages.filter { it.teacherId in frontier && it.id !in seen }.map { it.id }
            if (next.isEmpty()) break
            seen += next
            frontier = next
            generations++
        }
        return generations
    }
}
