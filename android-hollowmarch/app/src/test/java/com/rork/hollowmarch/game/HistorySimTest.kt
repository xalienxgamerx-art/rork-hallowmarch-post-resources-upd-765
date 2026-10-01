package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.EventKind
import com.rork.hollowmarch.world.World
import com.rork.hollowmarch.world.WorldGenerator
import com.rork.hollowmarch.world.isSettlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deep historical layer: famine sends walkers, walkers change the places
 * they reach, growth names its builders, and thin places are left. All of it
 * the seed's own — same seed, same years, same history forever — and its
 * residue rides the save whole.
 */
class HistorySimTest {

    private val world: World = WorldGenerator.generate(20260925L)
    private val sites = world.sites.filter { it.isSettlement && !it.ruined }

    private fun freshRun(): Pair<SettlementLedger, HistoricalSimulation> =
        Pair(SettlementLedger.fresh(world), HistoricalSimulation.fresh(world))

    private fun stepOne(
        ledger: SettlementLedger,
        sim: HistoricalSimulation,
        year: Int
    ): List<AgeEvent> {
        val turns = ledger.stepYears(year, world.sites)
        return sim.stepYears(year, world.sites, ledger, turns)
    }

    /** The two living settlements nearest each other, for a walker's road. */
    private fun nearestPair(): Pair<com.rork.hollowmarch.world.Site, com.rork.hollowmarch.world.Site> {
        val ordered = sites.sortedBy { it.id }
        var best = Pair(ordered[0], ordered[1])
        var bestD = Float.MAX_VALUE
        for (a in ordered) for (b in ordered) {
            if (a.id >= b.id) continue
            val d = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)
            if (d < bestD) {
                bestD = d
                best = Pair(a, b)
            }
        }
        return best
    }

    private fun famineTurn(site: com.rork.hollowmarch.world.Site, died: Int): YearTurn =
        YearTurn(
            site = site,
            events = emptyList(),
            roseTo = null,
            step = FolkYear(site.population, 0, died, FolkYearKind.FAMINE, null)
        )

    // ------------------------------------------------------------------ determinism

    @Test
    fun theSameSeedProducesTheSameHistoryTwice() {
        val (ledgerA, simA) = freshRun()
        val (ledgerB, simB) = freshRun()
        val end = world.currentYear + 60
        (world.currentYear + 1..end).forEach { year ->
            stepOne(ledgerA, simA, year)
            stepOne(ledgerB, simB, year)
        }
        assertEquals(ledgerA.encode(), ledgerB.encode())
        assertEquals(simA.encode(), simB.encode())
    }

    @Test
    fun aHistorySavedMidwayResumesAsIfItNeverStopped() {
        val end = world.currentYear + 80
        // one uninterrupted run
        val (ledgerA, simA) = freshRun()
        (world.currentYear + 1..end).forEach { year -> stepOne(ledgerA, simA, year) }
        // a run interrupted at the halfway save
        val (ledgerB, simB) = freshRun()
        val mid = world.currentYear + 40
        (world.currentYear + 1..mid).forEach { year -> stepOne(ledgerB, simB, year) }
        val ledgerReloaded = SettlementLedger.fromEncoded(ledgerB.encode(), world)
        val simReloaded = HistoricalSimulation.fromSave(world, simB.encode())
        (mid + 1..end).forEach { year ->
            val turns = ledgerReloaded.stepYears(year, world.sites)
            simReloaded.stepYears(year, world.sites, ledgerReloaded, turns)
        }
        assertEquals(ledgerA.encode(), ledgerReloaded.encode())
        assertEquals(simA.encode(), simReloaded.encode())
    }

    // ------------------------------------------------------------------ migration

    @Test
    fun aFamineSendsWalkersAndTheReceivingPlaceGrows() {
        val (origin, _) = nearestPair()
        val (ledger, sim) = freshRun()
        val beforeOrigin = ledger.folkOf(origin)
        assertTrue("the origin lives", beforeOrigin > 0)
        val before = world.sites.filter { it.isSettlement }.associate { it.id to ledger.folkOf(it) }
        val died = 100
        val events = mutableListOf<AgeEvent>()
        sim.applySiteYear(origin, beforeOrigin, famineTurn(origin, died), world.sites, ledger, world.currentYear + 1, events)
        val walkers = died * HistoricalSimulation.MIGRANT_SHARE_NUM / HistoricalSimulation.MIGRANT_SHARE_DEN
        assertTrue("some of the lost walked, the rest died", walkers in 1..died)
        assertEquals("the walkers live: the origin takes them back onto the road", beforeOrigin + walkers, ledger.folkOf(origin))
        val migration = events.filter { it.kind == AgeEventKind.MIGRATION }
        assertEquals(1, migration.size)
        val received = world.sites.first { it.id == migration[0].otherSiteId }
        val firstShare = walkers * HistoricalSimulation.DESTINATION_SPLIT[0] /
            HistoricalSimulation.DESTINATION_SPLIT.sum()
        assertEquals(
            "the first road takes its share",
            before.getValue(received.id) + firstShare,
            ledger.folkOf(received)
        )
        val totalArrived = world.sites
            .filter { it.isSettlement && it.id != origin.id }
            .sumOf { ledger.folkOf(it) - before.getValue(it.id) }
        assertEquals("no soul was lost on the road", walkers, totalArrived)
        assertTrue(migration[0].text.contains("walked out of ${origin.name}"))
        assertTrue(migration[0].text.contains(received.name))
    }

    @Test
    fun walkersNeverOutnumberTheLost() {
        val (origin, _) = nearestPair()
        val (ledger, sim) = freshRun()
        val before = ledger.folkOf(origin)
        val events = mutableListOf<AgeEvent>()
        sim.applySiteYear(origin, before, famineTurn(origin, 3), world.sites, ledger, world.currentYear + 1, events)
        val gained = ledger.folkOf(origin) - before
        assertTrue("two in five at most walked", gained <= 3 * HistoricalSimulation.MIGRANT_SHARE_NUM / HistoricalSimulation.MIGRANT_SHARE_DEN)
    }

    @Test
    fun aKindYearMovesNobody() {
        val (origin, dest) = nearestPair()
        val (ledger, sim) = freshRun()
        val quiet = YearTurn(
            site = origin, events = emptyList(), roseTo = null,
            step = FolkYear(origin.population, 10, 0, FolkYearKind.GOOD, null)
        )
        val beforeOrigin = ledger.folkOf(origin)
        val beforeDest = ledger.folkOf(dest)
        val events = mutableListOf<AgeEvent>()
        sim.applySiteYear(origin, beforeOrigin, quiet, world.sites, ledger, world.currentYear + 1, events)
        assertEquals(beforeOrigin, ledger.folkOf(origin))
        assertEquals(beforeDest, ledger.folkOf(dest))
        assertTrue(events.none { it.kind == AgeEventKind.MIGRATION })
    }

    @Test
    fun walkersSettleInOneOfTheNearestLivingPlaces() {
        val (origin, _) = nearestPair()
        val (ledger, sim) = freshRun()
        val near = world.sites
            .filter { it.id != origin.id && it.isSettlement && !it.ruined && ledger.folkOf(it) > 0 }
            .sortedBy { (it.x - origin.x) * (it.x - origin.x) + (it.y - origin.y) * (it.y - origin.y) }
            .take(HistoricalSimulation.DESTINATION_POOL)
            .map { it.id }
            .toSet()
        val events = mutableListOf<AgeEvent>()
        sim.applySiteYear(origin, ledger.folkOf(origin), famineTurn(origin, 100), world.sites, ledger, world.currentYear + 1, events)
        val migration = events.first { it.kind == AgeEventKind.MIGRATION }
        assertTrue("the road was short", migration.otherSiteId in near)
    }

    @Test
    fun anAbandonedPlaceReceivesNoWalkers() {
        val (origin, dest) = nearestPair()
        val (ledger, sim) = freshRun()
        // leave the nearest neighbor to the crows
        repeat(HistoricalSimulation.FADING_YEARS) {
            sim.applySiteYear(dest, 3, null, world.sites, ledger, world.currentYear + 1, mutableListOf())
        }
        assertTrue(sim.isAbandoned(dest.id))
        val events = mutableListOf<AgeEvent>()
        sim.applySiteYear(origin, ledger.folkOf(origin), famineTurn(origin, 100), world.sites, ledger, world.currentYear + 1, events)
        val migration = events.first { it.kind == AgeEventKind.MIGRATION }
        assertNotEquals(dest.id, migration.otherSiteId)
    }

    // ------------------------------------------------------------------ census

    @Test
    fun aCensusAddsUpToThePlaceItCounts() {
        val (origin, dest) = nearestPair()
        val (ledger, sim) = freshRun()
        val census = sim.censusOf(origin, ledger)
        assertEquals(ledger.folkOf(origin), census.total)
        assertEquals(census.total, census.children + census.adults + census.elders)
        assertEquals(census.total, census.occupations.sumOf { it.second })
        assertEquals("nothing has walked yet", 0, census.arrived)
        // after a famine sends walkers, the receiving place counts come-here folk
        sim.applySiteYear(origin, ledger.folkOf(origin), famineTurn(origin, 100), world.sites, ledger, world.currentYear + 1, mutableListOf())
        val received = sim.censusOf(dest, ledger)
        assertTrue(received.arrived > 0)
        assertEquals(received.total, received.natives + received.arrived)
        assertTrue(received.culturesNote.contains("come-here"))
    }

    // ------------------------------------------------------------------ growth & ruin

    @Test
    fun aRoseYearNamesItsBuilder() {
        val (ledger, sim) = freshRun()
        val site = sites.first()
        val turn = YearTurn(
            site = site, events = emptyList(), roseTo = SettlementStage.TOWN,
            step = FolkYear(400, 20, 5, FolkYearKind.GOOD, SettlementStage.TOWN)
        )
        val events = mutableListOf<AgeEvent>()
        sim.applySiteYear(site, 400, turn, world.sites, ledger, world.currentYear + 1, events)
        val rose = events.first { it.kind == AgeEventKind.SETTLEMENT_ROSE }
        assertTrue(rose.text.contains("led the raising of ${site.name}"))
        assertEquals(EventKind.GROWTH, rose.toChronicle().kind)
    }

    @Test
    fun aThinPlaceThinsThenIsLeft() {
        val site = sites.last()
        val (ledger, sim) = freshRun()
        val events = mutableListOf<AgeEvent>()
        var year = world.currentYear + 1
        repeat(HistoricalSimulation.FADING_YEARS) {
            sim.applySiteYear(site, 3, null, world.sites, ledger, year, events)
            year++
        }
        assertTrue(sim.isAbandoned(site.id))
        assertTrue(events.any { it.kind == AgeEventKind.SETTLEMENT_THINNED })
        val abandoned = events.last { it.kind == AgeEventKind.ABANDONED }
        assertEquals(EventKind.RUIN, abandoned.toChronicle().kind)
        assertTrue(abandoned.text.contains(site.name))
        // a plentiful year afterwards does not raise the dead place
        assertFalse(sim.isAbandoned(sites.first().id))
    }

    // ------------------------------------------------------------------ the long run

    @Test
    fun aCenturyOfYearsStaysBoundedAndReadable() {
        val (ledger, sim) = freshRun()
        val end = world.currentYear + 120
        (world.currentYear + 1..end).forEach { year -> stepOne(ledger, sim, year) }
        // the log's room scales with the province — one remembered deed for every
        // living place, never fewer than the old fixed cap (the same room the
        // simulation trims to)
        val logRoom = maxOf(
            HistoricalSimulation.EVENT_LOG_CAP,
            world.sites.count { it.isSettlement && !it.ruined }
        )
        assertTrue("the world remembers what matters, not everything", sim.eventLog().size <= logRoom)
        sim.eventLog().forEach { event ->
            assertTrue(event.text.isNotBlank())
            assertEquals(event.year, event.toChronicle().year)
        }
        // famine years walked, and the walking is in the chronicle kinds the journal reads
        val kinds = sim.eventLog().map { it.toChronicle().kind }.toSet()
        assertTrue(kinds.all { it in EventKind.entries })
    }
}
