package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.Site
import com.rork.hollowmarch.world.World
import com.rork.hollowmarch.world.WorldGenerator
import com.rork.hollowmarch.world.isSettlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The economic census: what the player is told about a settlement must be
 * what the simulation itself holds — no more, no less. Food lines agree
 * with the granary, crops with the fields, industries with the year's work,
 * provenance with the corrected law of local/imported/scarce, milestones
 * with the annals, and after a save and a reload the same place says the
 * same things.
 */
class EconomyReportTest {

    private val world: World = WorldGenerator.generate(20260925L)
    private val sites = world.sites.filter { it.isSettlement && !it.ruined }
    private val start = world.currentYear + 1

    private fun freshRun(): Pair<SettlementLedger, HistoricalSimulation> =
        Pair(SettlementLedger.fresh(world), HistoricalSimulation.fresh(world))

    private fun stepOne(ledger: SettlementLedger, sim: HistoricalSimulation, year: Int) {
        val turns = ledger.stepYears(year, world.sites)
        sim.stepYears(year, world.sites, ledger, turns)
    }

    /** Steps [n] years of economy only, standing [folk] everywhere. */
    private fun runYears(economy: EconomySimulation, n: Int, folk: Int): List<AgeEvent> {
        val events = mutableListOf<AgeEvent>()
        val override = sites.associate { it.id to folk }
        (start until start + n).forEach { year ->
            economy.stepYear(year, world.sites, SettlementLedger.fresh(world), events, override)
        }
        return events
    }

    private fun craftedEntry(siteId: Int, fields: Map<Int, String>): String {
        val parts = MutableList(24) { "" }
        parts[0] = "S$siteId"
        (1..6).forEach { parts[it] = "0" }
        parts[11] = "0"
        parts[22] = "0"
        parts[23] = "0000"
        fields.forEach { (index, value) -> parts[index] = value }
        return parts.joinToString("=")
    }

    private fun reportOf(
        site: Site,
        economy: EconomySimulation,
        sim: HistoricalSimulation,
        ledger: SettlementLedger
    ): SettlementEconomy = settlementEconomyOf(site, world, economy, sim, ledger)

    private fun cap(word: String): String = word.replaceFirstChar { it.uppercase() }

    // ------------------------------------------------------------------ food

    @Test
    fun theCensusSpeaksTheGranary() {
        val economy = EconomySimulation.fresh(world)
        val (ledger, sim) = freshRun()
        runYears(economy, 12, 1000)
        val site = sites.first { economy.foodOf(it).values.sum() > 0 }
        val summary = reportOf(site, economy, sim, ledger)

        val folk = ledger.folkOf(site).coerceAtLeast(0)
        val need = economy.foodNeedOf(folk)
        economy.foodOf(site).forEach { (kind, stored) ->
            val line = summary.food.firstOrNull { it.label == cap(kind.label) }
            if (stored > 0 || (economy.foodProductionOf(site)[kind] ?: 0) > 0) {
                assertTrue("the granary's ${kind.label} is spoken of", line != null)
                val plenty = when {
                    stored <= 0 -> "none stored"
                    stored >= need -> "plentiful"
                    stored * 3 >= need -> "moderate"
                    else -> "scarce"
                }
                assertEquals("the plenty word agrees with the store", plenty, line!!.plenty)
            }
        }
        // the note follows the ledger's own balance
        val made = economy.foodProductionOf(site).values.sum()
        val expectedNote = when {
            folk <= 0 -> "No one is left to feed."
            made >= need -> "The settlement produces more food than it eats."
            economy.foodOf(site).values.sum() > need ->
                "The folk eat more than the year yields, and live for now on the stores."
            else -> "The folk eat more than the year yields, and the stores run thin."
        }
        assertEquals(expectedNote, summary.foodNote)
        // the population line is the ledger's own count
        val folkSpoken = folk.toString().reversed().chunked(3).joinToString(",").reversed()
        assertTrue(summary.censusLine.contains(folkSpoken))
        assertTrue(
            "the condition is one of the census's own words",
            summary.condition in listOf(
                "Prosperous", "Stable", "Struggling", "Declining",
                "Trade dependent", "Empty granaries", "Hungry years"
            )
        )
    }

    @Test
    fun hardTimesAreNamedWithTheirCause() {
        val economy = EconomySimulation.fresh(world)
        val (ledger, sim) = freshRun()
        economy.applyEncoded(craftedEntry(sites.first().id, mapOf(11 to "2", 12 to "GRAIN:5")))
        val summary = reportOf(sites.first(), economy, sim, ledger)
        assertEquals("Hungry years", summary.condition)
        assertEquals("the fields have fallen short year upon year", summary.conditionCause)
        assertTrue(summary.food.any { it.label == "Grain" && it.plenty == "scarce" })
    }

    // ----------------------------------------------------------------- crops

    @Test
    fun cropsAndSeedSpeakByName() {
        val economy = EconomySimulation.fresh(world)
        val (ledger, sim) = freshRun()
        val site = sites.first()
        economy.applyEncoded(craftedEntry(site.id, mapOf(7 to "GRAIN:6", 14 to "GRAIN:42")))
        val summary = reportOf(site, economy, sim, ledger)

        val crop = summary.crops.first { it.name == "Grain" }
        assertEquals("Established", crop.state)
        assertEquals("42 in the barn", crop.barn)
        assertTrue(summary.seeds.any { it.contains("Grain") && it.contains("42") })
        // the fields are named as a work of the place
        assertTrue(summary.works.any { it.name == "The fields" && it.state.contains("grain") })
    }

    // ------------------------------------------------------------ industries

    @Test
    fun theWorkIsNamedFromTheYearsProduction() {
        val economy = EconomySimulation.fresh(world)
        val (ledger, sim) = freshRun()
        val site = sites.first()
        economy.applyEncoded(craftedEntry(site.id, mapOf(20 to "ORE:50,TIMBER:60")))
        val summary = reportOf(site, economy, sim, ledger)

        assertTrue("mining is named", summary.industries.any { it.name == "Mining" && it.status == "Active" })
        assertTrue("logging is named", summary.industries.any { it.name == "Logging" && it.status == "Active" })
        assertTrue(
            "identity follows the work, not the ground",
            summary.identity.contains("mining") && summary.identity.contains("logging")
        )
        assertTrue(summary.works.any { it.name == "The woods" && it.state == "Being cut" })
    }

    @Test
    fun exhaustionIsRememberedAndExplainsTheDecline() {
        val economy = EconomySimulation.fresh(world)
        val (ledger, sim) = freshRun()
        // the save can only rework a deposit the ground actually holds
        val site = sites.first { economy.depositsOf(it).isNotEmpty() }
        val ore = economy.depositsOf(site).first().material
        economy.applyEncoded(
            craftedEntry(
                site.id,
                mapOf(
                    8 to "${ore.name}:0:1:1", // dug out, known, once announced
                    12 to "GRAIN:500",        // a working granary, so hunger is not the story
                    18 to ore.name,           // the industry was known
                    19 to ore.name            // and has failed
                )
            )
        )
        val summary = reportOf(site, economy, sim, ledger)

        assertTrue(summary.industries.any { it.name == "Mining" && it.status == "Exhausted" })
        assertTrue(
            summary.industries.any { it.name == "${cap(ore.label)} work" && it.status == "Declined" }
        )
        assertEquals("Declining", summary.condition)
        assertEquals("the ${ore.label} work has failed", summary.conditionCause)
        assertTrue(summary.works.any { it.name == "Old ${ore.label} mine" && it.state == "Worked out" })
        assertTrue(summary.materials.any { it.name == cap(ore.label) && it.word == "scarce" })
    }

    // ----------------------------------------------------------------- trade

    @Test
    fun importsAndExportsNameTheirRoads() {
        val economy = EconomySimulation.fresh(world)
        val (ledger, sim) = freshRun()
        val (a, b) = sites.filter { it.population >= 400 }.take(2)
        val key = "${minOf(a.id, b.id)}>${maxOf(a.id, b.id)}:IRON"
        economy.applyEncoded(
            craftedEntry(a.id, mapOf(17 to "IRON:5000", 9 to "$key@$start@3@40@0")) +
                "\u001E" +
                craftedEntry(b.id, mapOf(9 to "$key@$start@3@40@0"))
        )
        val summaryA = reportOf(a, economy, sim, ledger)
        val summaryB = reportOf(b, economy, sim, ledger)

        assertTrue("the seller names its buyer", summaryA.exports.any { it.cargo == "Iron" && it.partner == b.name })
        assertTrue("the buyer names its seller", summaryB.imports.any { it.cargo == "Iron" && it.partner == a.name })
        assertTrue(summaryA.materials.any { it.name == "Iron" && it.word == "local" })
        val imported = summaryB.materials.first { it.name == "Iron" }
        assertEquals("imported", imported.word)
        assertEquals(a.name, imported.from)
        // and the road itself is remembered with its age
        assertTrue(summaryB.roads.any { it.contains(a.name) && it.contains("year $start") })
    }

    @Test
    fun undiscoveredGroundIsNeverSpokenOfAsAvailable() {
        val economy = EconomySimulation.fresh(world)
        val (ledger, sim) = freshRun()
        val site = sites.first { economy.depositsOf(it).isNotEmpty() }
        val hidden = economy.depositsOf(site).first()
        assertFalse("the deposit starts unknown", hidden.discovered)

        assertEquals(
            MaterialSource.SCARCE,
            economy.provenanceOf(site, ledger, world.sites, hidden.material).source
        )
        val summary = reportOf(site, economy, sim, ledger)
        assertFalse(
            "the census does not list what no one has found",
            summary.materials.any { it.name.equals(cap(hidden.material.label), ignoreCase = true) }
        )
        assertFalse(
            "no mine is named for ground undug",
            summary.works.any { it.name.contains(hidden.material.label, ignoreCase = true) }
        )
    }

    // --------------------------------------------------------------- history

    @Test
    fun theTimelineIsTheAnnalsThemselves() {
        val (ledger, sim) = freshRun()
        (start until start + 30).forEach { year -> stepOne(ledger, sim, year) }
        val site = sites.maxBy { s -> sim.eventLog().count { it.siteId == s.id && it.kind in ECONOMIC_ANNALS } }
        val summary = reportOf(site, sim.economy, sim, ledger)

        val expected = sim.eventLog()
            .filter { it.siteId == site.id && it.kind in ECONOMIC_ANNALS }
            .sortedBy { it.year }
            .takeLast(30)
            .map { it.year to it.text }
        assertEquals(expected, summary.milestones.map { it.year to it.text })
        assertTrue("thirty years of folk leave some mark", summary.milestones.isNotEmpty())
        // the story's first sentence is the identity, and every later one is dated
        assertEquals(summary.identity, summary.history.first())
        summary.history.drop(1).forEach { line ->
            assertTrue("each remembered deed carries its year", line.startsWith("In year ") || line.startsWith("This year"))
        }
    }

    // ------------------------------------------------------------- save/load

    @Test
    fun theSameWorldSaysTheSameThingsAfterTheSave() {
        val end = start + 80
        val (ledgerA, simA) = freshRun()
        (start until end).forEach { year -> stepOne(ledgerA, simA, year) }

        val (ledgerB, simB) = freshRun()
        val mid = start + 40
        (start until mid).forEach { year -> stepOne(ledgerB, simB, year) }
        val ledgerReloaded = SettlementLedger.fromEncoded(ledgerB.encode(), world)
        val simReloaded = HistoricalSimulation.fromSave(world, simB.encode())
        (mid until end).forEach { year -> stepOne(ledgerReloaded, simReloaded, year) }

        sites.forEach { site ->
            assertEquals(
                "the census of ${site.name} survives the save",
                reportOf(site, simA.economy, simA, ledgerA),
                reportOf(site, simReloaded.economy, simReloaded, ledgerReloaded)
            )
            assertEquals(
                "the people of ${site.name} survive the save",
                simA.censusOf(site, ledgerA),
                simReloaded.censusOf(site, ledgerReloaded)
            )
        }
    }
}
