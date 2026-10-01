package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.Biome
import com.rork.hollowmarch.world.Site
import com.rork.hollowmarch.world.World
import com.rork.hollowmarch.world.WorldGenerator
import com.rork.hollowmarch.world.isSettlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The economy layer: the world's own ground decides what each place could
 * produce, hands and capability decide what it does produce, production
 * becomes stock, stock feeds folk and roads, and roads move real cargo
 * between a real surplus and a real want. All of it the seed's own — same
 * seed, same world, same years, same ledgers forever.
 */
class EconomyTest {

    private val world: World = WorldGenerator.generate(20260925L)
    private val sites = world.sites.filter { it.isSettlement && !it.ruined }
    private val start = world.currentYear + 1

    private val reader = EconomySimulation.fresh(world)

    private fun freshRun(): Pair<SettlementLedger, HistoricalSimulation> =
        Pair(SettlementLedger.fresh(world), HistoricalSimulation.fresh(world))

    private fun stepOne(ledger: SettlementLedger, sim: HistoricalSimulation, year: Int): List<AgeEvent> {
        val turns = ledger.stepYears(year, world.sites)
        return sim.stepYears(year, world.sites, ledger, turns)
    }

    /** Steps [n] years of economy only, standing [folk] everywhere, and returns the events. */
    private fun runYears(
        economy: EconomySimulation,
        n: Int,
        folk: Int,
        ledger: SettlementLedger = SettlementLedger.fresh(world)
    ): List<AgeEvent> = runYearsHunger(economy, n, folk, ledger).first

    /** As [runYears], but also returns the hunger seen across all the years. */
    private fun runYearsHunger(
        economy: EconomySimulation,
        n: Int,
        folk: Int,
        ledger: SettlementLedger = SettlementLedger.fresh(world)
    ): Pair<List<AgeEvent>, Map<Int, Int>> {
        val events = mutableListOf<AgeEvent>()
        val hunger = mutableMapOf<Int, Int>()
        val override = sites.associate { it.id to folk }
        (start until start + n).forEach { year ->
            economy.stepYear(year, world.sites, ledger, events, override).forEach { (id, count) ->
                hunger[id] = maxOf(hunger[id] ?: 0, count)
            }
        }
        return Pair(events, hunger)
    }

    /** What a place could gather per soul in a year, from the land alone. */
    private fun foodPerSoul(site: Site): Float {
        val pot = reader.potentialOf(site)
        val bestSuit = CropKind.entries.maxOf { it.suitability(reader.profileOf(site)) }
        return bestSuit * EconomySimulation.CROP_RATE +
            pot.grazing * EconomySimulation.MEAT_RATE +
            pot.game * EconomySimulation.GAME_RATE +
            pot.fish * EconomySimulation.FISH_RATE +
            pot.gathered * EconomySimulation.GATHER_RATE
    }

    private fun bestLand(): Site = sites.maxBy { foodPerSoul(it) }
    private fun worstLand(): Site = sites.minBy { foodPerSoul(it) }
    private fun woodiestLand(): Site = sites.maxBy { reader.potentialOf(it).timber }

    /** The most arable ground in the province, by the crops' own judgment. */
    private fun bestArable(): Site = sites.maxBy { site ->
        CropKind.entries.maxOf { it.suitability(reader.profileOf(site)) }
    }

    private fun siteWithDeposits(): Site? =
        sites.firstOrNull { reader.depositsOf(it).isNotEmpty() }

    /** The two living settlements nearest each other, for a road. */
    private fun nearestPair(): Pair<Site, Site> {
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

    /** A crafted economy save entry for one site, given per-field overrides. */
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

    // ------------------------------------------------------------------ terrain

    @Test
    fun terrainReadsArePureAndSeeded() {
        val other = EconomySimulation.fresh(world)
        for (site in sites) {
            assertEquals(reader.profileOf(site), other.profileOf(site))
            assertEquals(reader.potentialOf(site), other.potentialOf(site))
            assertEquals(Ecology.potentialOf(Ecology.profileOf(world, site)), reader.potentialOf(site))
        }
        assertTrue(
            "the province is not one flat field",
            sites.map { reader.profileOf(it) }.distinct().size > 1
        )
    }

    @Test
    fun theEconomyReadsTheWorldsOwnGround() {
        val terrain = world.terrain
        for (site in sites) {
            val profile = reader.profileOf(site)
            assertEquals(
                "elevation comes from the province's own heights",
                terrain.heightAt(site.x, site.y),
                profile.elevation,
                0.0001f
            )
            assertEquals(
                "wetness comes from the province's own moisture",
                terrain.moistureAt(site.x, site.y),
                profile.moisture,
                0.0001f
            )
            assertEquals(
                "the classification is the map's own",
                terrain.biomeAt(site.x, site.y),
                profile.biome
            )
            assertEquals(
                "dense woodland stands exactly where the map says forest",
                profile.biome == Biome.FOREST,
                profile.forest > 0.5f
            )
        }
        // a forested ground gives timber potential a plain cannot
        val forested = sites.filter { reader.profileOf(it).biome == Biome.FOREST }
        val bare = sites.filter { reader.profileOf(it).biome != Biome.FOREST }
        if (forested.isNotEmpty() && bare.isNotEmpty()) {
            assertTrue(
                "timber potential follows the map's woods",
                reader.potentialOf(forested.first()).timber > reader.potentialOf(bare.first()).timber
            )
        }
    }

    @Test
    fun valleyLandFeedsAndFishes() {
        val valley = LandProfile(
            elevation = 0.20f, roughness = 0.15f, moisture = 0.55f, forest = 0.30f,
            water = 0.42f, soil = 0.70f, warmth = 0.60f, saltBand = 0f
        )
        assertEquals(LandCharacter.VALLEY, Ecology.characterOf(valley))
        val pot = Ecology.potentialOf(valley)
        assertTrue("a valley grows grain", pot.grain >= 0.45f)
        assertTrue("a valley fishes", pot.fish >= 0.30f)
        assertTrue("a valley digs clay", pot.clay >= 0.25f)
    }

    @Test
    fun mountainLandQuarriesAndStarves() {
        val mountain = LandProfile(
            elevation = 0.80f, roughness = 0.70f, moisture = 0.30f, forest = 0.10f,
            water = 0.05f, soil = 0.15f, warmth = 0.40f, saltBand = 0f
        )
        assertEquals(LandCharacter.MOUNTAIN, Ecology.characterOf(mountain))
        val pot = Ecology.potentialOf(mountain)
        assertTrue("a mountain quarries", pot.stone >= 0.60f)
        assertTrue("a mountain grows little grain", pot.grain <= 0.20f)
        assertTrue("a mountain holds little timber", pot.timber <= 0.30f)
    }

    @Test
    fun forestLandGivesTimberAndGame() {
        val forest = LandProfile(
            elevation = 0.35f, roughness = 0.30f, moisture = 0.60f, forest = 0.75f,
            water = 0.15f, soil = 0.35f, warmth = 0.50f, saltBand = 0f
        )
        assertEquals(LandCharacter.FOREST, Ecology.characterOf(forest))
        val pot = Ecology.potentialOf(forest)
        assertTrue("a forest gives timber", pot.timber >= 0.60f)
        assertTrue("a forest gives game", pot.game >= 0.50f)
    }

    // -------------------------------------------------------------------- crops

    @Test
    fun cropSuitabilityIsDeterministicAndDifferentiated() {
        val coldThin = LandProfile(
            elevation = 0.30f, roughness = 0.20f, moisture = 0.40f, forest = 0.20f,
            water = 0.10f, soil = 0.25f, warmth = 0.20f, saltBand = 0f
        )
        val hotRich = LandProfile(
            elevation = 0.15f, roughness = 0.10f, moisture = 0.50f, forest = 0.10f,
            water = 0.20f, soil = 0.60f, warmth = 0.85f, saltBand = 0f
        )
        CropKind.entries.forEach { crop ->
            assertEquals(crop.suitability(coldThin), crop.suitability(coldThin))
            assertEquals(crop.suitability(hotRich), crop.suitability(hotRich))
        }
        assertTrue("hardy barley beats grain in the cold thin soil",
            CropKind.BARLEY.suitability(coldThin) > CropKind.GRAIN.suitability(coldThin))
        assertTrue("roots beat grain in the cold thin soil",
            CropKind.ROOTS.suitability(coldThin) > CropKind.GRAIN.suitability(coldThin))
        assertTrue("orchard fruit beats grain where the summers burn",
            CropKind.FRUIT.suitability(hotRich) > CropKind.GRAIN.suitability(hotRich))
    }

    @Test
    fun onlySuitableCropsTakeRoot() {
        val economy = EconomySimulation.fresh(world)
        val site = worstLand()
        runYears(economy, 60, 200)
        val prof = economy.profileOf(site)
        economy.cropsOf(site).forEach { (crop, _) ->
            assertTrue(
                "${crop.label} took root on land that cannot feed it",
                crop.suitability(prof) >= EconomySimulation.ADOPT_FLOOR
            )
        }
    }

    @Test
    fun cropsArriveGetEstablishedAndFeed() {
        val economy = EconomySimulation.fresh(world)
        val site = bestArable()
        val ledger = SettlementLedger.fresh(world)
        val events = runYears(economy, 20, 120, ledger)
        val stages = economy.cropsOf(site)
        assertTrue("something was sown at ${site.name}", stages.isNotEmpty())
        assertTrue(
            "a sown field grows sure with the years",
            stages.values.any { it == CropStage.ESTABLISHED }
        )
        assertTrue(
            "the chronicle remembers the crop that took hold",
            events.any { it.kind == AgeEventKind.CROP_ESTABLISHED && it.siteId == site.id }
        )
        assertTrue("a fed place keeps a granary", economy.reserveOf(site) > 0)
    }

    @Test
    fun cropsKeepTheirOwnStocksBeforeBecomingFood() {
        val economy = EconomySimulation.fresh(world)
        val site = bestArable()
        runYears(economy, 20, 200)
        val barn = economy.cropStockOf(site)
        assertTrue("the seed barn is not empty on sown land", barn.isNotEmpty())
        barn.forEach { (crop, qty) ->
            assertTrue("$crop keeps only its seed corn", qty <= 400)
            assertTrue("no crop carries a debt", qty >= 0)
        }
        // the threshed harvest stands in the granary under its own kind
        val stores = economy.foodOf(site)
        assertTrue(
            "threshed grain and produce keep their names",
            stores.containsKey(FoodKind.GRAIN) || stores.containsKey(FoodKind.PRODUCE)
        )
    }

    @Test
    fun abandonedFieldsGoBackToBrush() {
        val economy = EconomySimulation.fresh(world)
        val events = runYears(economy, 30, 5)
        assertTrue(
            "too few hands lose their fields",
            events.any { it.kind == AgeEventKind.CROP_LOST }
        )
    }

    @Test
    fun yieldAnswersToFolk() {
        val site = bestLand()
        val few = EconomySimulation.fresh(world)
        val many = EconomySimulation.fresh(world)
        runYears(few, 30, 60)
        runYears(many, 30, 240)
        assertTrue(
            "more hands, more in the granary",
            many.reserveOf(site) > few.reserveOf(site)
        )
    }

    // --------------------------------------------------------------------- food

    @Test
    fun foodCategoriesStayDistinctAndSumToTheGranary() {
        val economy = EconomySimulation.fresh(world)
        val site = bestLand()
        runYears(economy, 25, 200)
        val stores = economy.foodOf(site)
        assertTrue("the granary is not empty on fed land", stores.isNotEmpty())
        assertTrue("only real kinds of food are kept", stores.keys.all { it in FoodKind.entries.toSet() })
        assertEquals(
            "the broad stores are the granary",
            stores.values.sum(),
            economy.reserveOf(site)
        )
    }

    @Test
    fun reservesPersistThroughTheSave() {
        val economy = EconomySimulation.fresh(world)
        val site = bestLand()
        runYears(economy, 30, 120)
        val restored = EconomySimulation.fresh(world)
        restored.applyEncoded(economy.encode())
        assertEquals(economy.reserveOf(site), restored.reserveOf(site))
        assertEquals(economy.foodOf(site), restored.foodOf(site))
        assertEquals(economy.cropStockOf(site), restored.cropStockOf(site))
        assertEquals(economy.cropsOf(site), restored.cropsOf(site))
        assertEquals(economy.routesOf(site), restored.routesOf(site))
        assertEquals(economy.timberOf(site), restored.timberOf(site))
        assertEquals(economy.logsOf(site), restored.logsOf(site))
        assertEquals(economy.materialStockOf(site), restored.materialStockOf(site))
    }

    @Test
    fun hungerSendsWalkers() {
        val economy = EconomySimulation.fresh(world)
        val site = worstLand()
        val (events, hunger) = runYearsHunger(economy, 20, 30000)
        assertTrue(
            "a place too great for its land goes hungry",
            (hunger[site.id] ?: 0) > 0
        )
        assertTrue(
            "the chronicle remembers a failed harvest",
            events.any { it.kind == AgeEventKind.HARVEST_FAILURE && it.siteId == site.id }
        )
    }

    @Test
    fun hungryWalkersTakeTheMigrationRoad() {
        val (ledger, sim) = freshRun()
        val origin = sites.maxBy { ledger.folkOf(it) }
        val before = world.sites.filter { it.isSettlement }.associate { it.id to ledger.folkOf(it) }
        val beforeOrigin = before.getValue(origin.id)
        val events = mutableListOf<AgeEvent>()
        sim.applySiteYear(origin, beforeOrigin, null, world.sites, ledger, start, events, hungry = 7)
        val migration = events.first { it.kind == AgeEventKind.MIGRATION }
        assertTrue(migration.text.contains("hungry souls"))
        assertTrue(migration.text.contains(origin.name))
        val received = world.sites.first { it.id == migration.otherSiteId }
        assertNotEquals(origin.id, received.id)
        assertTrue(migration.text.contains(received.name))
        assertEquals("the hungry leave the living count", beforeOrigin - 7, ledger.folkOf(origin))
        val arrived = world.sites
            .filter { it.isSettlement && it.id != origin.id }
            .sumOf { ledger.folkOf(it) - before.getValue(it.id) }
        assertEquals("no soul was lost on the road", 7, arrived)
    }

    @Test
    fun aFatYearFillsTheGranary() {
        val economy = EconomySimulation.fresh(world)
        val site = bestLand()
        val events = runYears(economy, 30, 80)
        val fat = events.filter { it.kind == AgeEventKind.HARVEST_SURPLUS }
        assertTrue(
            "a fat year on fat land enters the chronicle",
            fat.isNotEmpty() || economy.reserveOf(site) > 80 * EconomySimulation.RESERVE_RICH
        )
    }

    // --------------------------------------------------- timber, stone and salt

    @Test
    fun timberRegrowsUnderAFairHand() {
        val economy = EconomySimulation.fresh(world)
        val site = woodiestLand()
        val init = economy.timberOf(site)
        assertTrue("the province has woods", init >= 300)
        runYears(economy, 10, 60)
        assertTrue(
            "a fair hand takes no more than the forest gives back",
            economy.timberOf(site) >= init * 9 / 10
        )
    }

    @Test
    fun overloggingCutsTheWoodsOut() {
        val economy = EconomySimulation.fresh(world)
        val site = woodiestLand()
        val init = economy.timberOf(site)
        val events = runYears(economy, 12, 5000)
        assertTrue("the woods do not stand before ten thousand axes", economy.timberOf(site) < init / 5)
        assertTrue(
            "the chronicle remembers the cut-out woods",
            events.any {
                it.kind == AgeEventKind.RESOURCE_DEPLETED &&
                    it.siteId == site.id && it.text.contains("woods")
            }
        )
    }

    @Test
    fun theForestBecomesYardStockAndLumber() {
        val economy = EconomySimulation.fresh(world)
        val site = woodiestLand()
        runYears(economy, 15, 300)
        assertTrue(
            "the axes actually bite",
            (economy.productionOf(site)[ResourceKind.TIMBER] ?: 0) > 0
        )
        assertTrue("the cut wood stands in the yard as stock", economy.logsOf(site) > 0)
        assertTrue(
            "a working settlement squares logs into lumber",
            (economy.materialStockOf(site)[Material.ASHWOOD] ?: 0) > 0
        )
    }

    @Test
    fun quarriesOpenCloseAndLeaveHistory() {
        val economy = EconomySimulation.fresh(world)
        val site = sites.first()
        // a crafted save: a fresh seam of stone in the ground
        economy.applyEncoded(craftedEntry(site.id, mapOf(15 to "500:0:0:0")))
        val first = runYears(economy, 3, 200)
        assertTrue("the quarry is worked", (economy.productionOf(site)[ResourceKind.STONE] ?: 0) > 0)
        assertTrue("quarried stone stands in the yard", economy.stockOf(site, ResourceKind.STONE) > 0)
        assertTrue(
            "the chronicle remembers the quarry opening",
            first.any { it.kind == AgeEventKind.QUARRY_OPENED && it.siteId == site.id }
        )
        runYears(economy, 60, 2000)
        assertTrue("no seam is bottomless", economy.productionOf(site)[ResourceKind.STONE] == null)
        assertEquals(
            "the seam itself is gone",
            0,
            readerStoneSeamLeft(economy, site)
        )
    }

    /** Reads a place's remaining stone seam back out of its own save. */
    private fun readerStoneSeamLeft(economy: EconomySimulation, site: Site): Int {
        val entry = economy.encode().split("\u001E").firstOrNull { it.startsWith("S${site.id}=") } ?: return -1
        val seams = entry.split("=").getOrNull(15) ?: return -1
        return seams.split(":").firstOrNull()?.toIntOrNull() ?: -1
    }

    @Test
    fun saltPansFeedPreservationAndLeaveStock() {
        val economy = EconomySimulation.fresh(world)
        val site = sites.first()
        // a crafted save: salt in the ground by the shore
        economy.applyEncoded(craftedEntry(site.id, mapOf(15 to "0:500:0:0")))
        val events = runYears(economy, 5, 100)
        assertTrue("the pans are scraped", (economy.productionOf(site)[ResourceKind.SALT] ?: 0) > 0)
        assertTrue("salt keeps in the store", economy.stockOf(site, ResourceKind.SALT) > 0)
        assertTrue(
            "the chronicle remembers the salt works",
            events.any { it.kind == AgeEventKind.MINE_OPENED && it.siteId == site.id }
        )
    }

    // ------------------------------------------------------- the diggings

    @Test
    fun depositsAreFoundWorkedAndWorkedOut() {
        val economy = EconomySimulation.fresh(world)
        val site = siteWithDeposits()
        assertTrue("the province holds metal", site != null)
        val theSite = site!!
        val richness = reader.depositsOf(theSite).associate { it.material to it.richness }
        runYears(economy, 40, 300)
        val found = economy.depositsOf(theSite)
        assertTrue("growing folk find what the ground holds", found.all { it.discovered })
        runYears(economy, 60, 20000)
        val workedOut = economy.depositsOf(theSite)
        assertTrue("no pit is bottomless", workedOut.all { it.remaining == 0 })
        assertTrue("richness was once there", richness.all { it.value >= 400 })
    }

    @Test
    fun aMineIsOpenedWhenOreFirstFlows() {
        val economy = EconomySimulation.fresh(world)
        val site = siteWithDeposits()!!
        val events = runYears(economy, 40, 2000)
        assertTrue(
            "the chronicle remembers the mine opening",
            events.any { it.kind == AgeEventKind.MINE_OPENED && it.siteId == site.id }
        )
        assertTrue("dug ore waits in the store", economy.oreStockOf(site).isNotEmpty())
    }

    @Test
    fun oreBecomesMetalOnlyWhereTheForgeStands() {
        val economy = EconomySimulation.fresh(world)
        val site = siteWithDeposits()!!
        val material = reader.depositsOf(site).first().material
        // a hamlet digs ore but cannot smelt it
        runYears(economy, 30, 100)
        assertTrue(
            "no forge, no metal: ore waits",
            economy.materialStockOf(site)[material] == null
        )
        // a town smelts what its pits bring up
        runYears(economy, 10, 600)
        assertTrue(
            "a town's forge turns ore into metal",
            (economy.materialStockOf(site)[material] ?: 0) > 0
        )
    }

    @Test
    fun steelNeedsTheGreatForge() {
        val economy = EconomySimulation.fresh(world)
        val site = sites.first()
        // a crafted save: a store of iron ore and a full granary
        economy.applyEncoded(
            craftedEntry(site.id, mapOf(12 to "GRAIN:20000", 16 to "IRON:500"))
        )
        runYears(economy, 3, 1000)
        val stock = economy.materialStockOf(site)
        assertTrue("the forge makes iron of the ore", (stock[Material.IRON] ?: 0) > 0)
        assertTrue(
            "only the great forge makes steel",
            (stock[Material.STEEL] ?: 0) > 0
        )
    }

    @Test
    fun depositsStayWithinTheRegionsMaterialLaw() {
        val economy = EconomySimulation.fresh(world)
        val geography = MaterialGeography(world)
        for (site in sites) {
            val allowed = geography.available(economy.cultureOf(site)).toSet()
            economy.depositsOf(site).forEach { dep ->
                assertTrue(
                    "${dep.material.label} was drawn where the region law says it cannot be",
                    dep.material in allowed
                )
            }
        }
    }

    // ---------------------------------------------------------------- materials

    @Test
    fun anUndiscoveredDepositIsNotLocal() {
        val economy = EconomySimulation.fresh(world)
        val site = siteWithDeposits()!!
        val material = reader.depositsOf(site).first().material
        val ledger = SettlementLedger.fresh(world)
        assertEquals(
            "veins no one has found put iron in no one's hands",
            MaterialSource.SCARCE,
            economy.provenanceOf(site, ledger, world.sites, material).source
        )
    }

    @Test
    fun anImportRequiresAnActualRoad() {
        val (a, b) = nearestPair()
        val others = sites.filter { it.id != a.id && it.id != b.id }
        assertTrue("the province has a third place", others.isNotEmpty())
        val c = others.first()
        val economy = EconomySimulation.fresh(world)
        // a crafted save: A holds worked iron; a road carries iron to B; C has neither
        val roadKey = "${minOf(a.id, b.id)}>${maxOf(a.id, b.id)}:IRON@0@0@0@0"
        economy.applyEncoded(
            listOf(
                craftedEntry(a.id, mapOf(17 to "IRON:100")),
                craftedEntry(b.id, mapOf(9 to roadKey))
            ).joinToString("\u001E")
        )
        val ledger = SettlementLedger.fresh(world)
        val imported = economy.provenanceOf(b, ledger, world.sites, Material.IRON)
        assertEquals("the road makes the material", MaterialSource.IMPORTED, imported.source)
        assertEquals("the import names its road's other end", a.id, imported.fromSiteId)
        assertEquals(
            "no road, no iron: the place goes without",
            MaterialSource.SCARCE,
            economy.provenanceOf(c, ledger, world.sites, Material.IRON).source
        )
    }

    @Test
    fun materialsComeFromTheLandTheRoadOrNowhere() {
        val economy = EconomySimulation.fresh(world)
        val ledger = SettlementLedger.fresh(world)
        runYears(economy, 40, 600, ledger)
        var sawLocal = false
        var sawScarce = false
        var sawImported = false
        for (site in sites) {
            val report = economy.materialReport(site, ledger, world.sites)
            assertEquals("every material gets an answer", Material.entries.size, report.size)
            report.forEach { provenance ->
                when (provenance.source) {
                    MaterialSource.LOCAL -> sawLocal = true
                    MaterialSource.SCARCE -> sawScarce = true
                    MaterialSource.IMPORTED -> {
                        sawImported = true
                        assertTrue("an import names its road", provenance.fromSiteId > 0)
                        assertTrue(provenance.fromSiteName.isNotBlank())
                        assertEquals(
                            "the road's other end truly has it",
                            MaterialSource.LOCAL,
                            economy.provenanceOf(
                                world.sites.first { it.id == provenance.fromSiteId },
                                ledger, world.sites, provenance.material
                            ).source
                        )
                    }
                }
            }
        }
        assertTrue("someplace works something with its own hands", sawLocal)
        assertTrue("someplace cannot reach something", sawScarce)
        val materialRoads = sites.any { s -> economy.routesOf(s).any { it.material != null } }
        if (materialRoads) {
            assertTrue("roads bring what the ground does not", sawImported)
        }
    }

    // -------------------------------------------------------------------- trade

    @Test
    fun tradeRoadsFollowSurplusAndDemand() {
        val economy = EconomySimulation.fresh(world)
        val ledger = SettlementLedger.fresh(world)
        runYears(economy, 30, 400, ledger)
        val allRoutes = sites.flatMap { economy.routesOf(it) }.distinctBy { it.key }
        assertTrue("a real surplus and a real want find a road", allRoutes.isNotEmpty())
        allRoutes.forEach { route ->
            val a = world.sites.first { it.id == route.fromId }
            val b = world.sites.first { it.id == route.toId }
            val dx = a.x - b.x
            val dy = a.y - b.y
            assertTrue("roads are short", dx * dx + dy * dy <= EconomySimulation.TRADE_MAX_DIST_SQ)
        }
        assertTrue(
            "roads carry actual cargo, not good intentions",
            allRoutes.any { it.lastQty > 0 }
        )
        // and the roads ride the save
        val withRoads = sites.first { economy.routesOf(it).isNotEmpty() }
        val restored = EconomySimulation.fresh(world)
        restored.applyEncoded(economy.encode())
        assertEquals(economy.routesOf(withRoads), restored.routesOf(withRoads))
    }

    @Test
    fun aRoadToARuinedPlaceFallsOutOfUse() {
        val economy = EconomySimulation.fresh(world)
        val livingIds = sites.map { it.id }.toSet()
        val dead = world.sites.first { it.id !in livingIds }
        val a = sites.first()
        // a crafted save: one road to a place that no longer answers
        val crafted = "S${a.id}=500=0=0=0=0=0===${a.id}>${dead.id}:GRAIN="
        economy.applyEncoded(crafted)
        assertTrue(economy.routesOf(a).isNotEmpty())
        val events = runYears(economy, 1, 100)
        // In a crowded land the place may beat new roads to living neighbors the
        // same year — the contract is that the road to the dead place is gone.
        assertTrue(
            "the road is gone",
            economy.routesOf(a).none { it.toId == dead.id || it.fromId == dead.id }
        )
        assertTrue(
            "the chronicle remembers the road that fell",
            events.any {
                it.kind == AgeEventKind.TRADE_ROUTE_CLOSED &&
                    it.text.contains(a.name)
            }
        )
    }

    @Test
    fun aRoadDriesUpWhenNeitherEndHasCause() {
        val (a, b) = nearestPair()
        val economy = EconomySimulation.fresh(world)
        // a crafted save: a road built for salt, though no place here has any
        val roadKey = "${minOf(a.id, b.id)}>${maxOf(a.id, b.id)}:SALT@0@0@0@0"
        economy.applyEncoded(
            craftedEntry(a.id, mapOf(9 to roadKey)) + "\u001E" +
                craftedEntry(b.id, mapOf(9 to roadKey))
        )
        val events = runYears(economy, 5, 100)
        assertTrue(
            "a road with no cargo is forgotten",
            economy.routesOf(a).none { it.cargo == "SALT" }
        )
        assertTrue(
            "the chronicle remembers the road that dried",
            events.any { it.kind == AgeEventKind.TRADE_ROUTE_CLOSED && it.text.contains(a.name) }
        )
    }

    // ------------------------------------------------------------ specialization

    @Test
    fun specializationTakesHoldAndEntersTheChronicle() {
        val economy = EconomySimulation.fresh(world)
        val events = runYears(economy, 80, 300)
        assertTrue(
            "places come to be known for their work",
            events.any { it.kind == AgeEventKind.SPECIALIZED }
        )
        val site = bestLand()
        val labels = economy.specializationOf(site, 300)
        assertTrue(labels.isNotEmpty())
        assertEquals("an identity is derived, not rolled", labels, economy.specializationOf(site, 300))
    }

    @Test
    fun specializationFollowsTheWorkNotTheGround() {
        val economy = EconomySimulation.fresh(world)
        val site = siteWithDeposits() ?: sites.first()
        // no folk, no work, no identity — whatever the ground could give
        assertTrue(
            "potential without people is no identity at all",
            economy.specializationOf(site, 0).isEmpty()
        )
        // a mining ground with no mine yet is not a mining town
        val labels = economy.specializationOf(site, 300)
        assertFalse(
            "ore still in the ground makes no miner",
            labels.contains("mining")
        )
    }

    @Test
    fun anExhaustedMineEndsTheMiningTown() {
        val economy = EconomySimulation.fresh(world)
        val site = siteWithDeposits() ?: return
        val events = runYears(economy, 80, 2000)
        assertTrue(
            "the pits were worked dry",
            events.any {
                it.kind == AgeEventKind.RESOURCE_DEPLETED &&
                    it.siteId == site.id && it.text.contains("diggings")
            }
        )
        assertFalse(
            "no ore, no miners: the identity goes with the mine",
            economy.specializationOf(site, 2000).contains("mining")
        )
    }

    // -------------------------------------------------------------- the long run

    @Test
    fun theSameSeedProducesTheSameEconomyTwice() {
        val a = EconomySimulation.fresh(world)
        val b = EconomySimulation.fresh(world)
        runYears(a, 40, 150)
        runYears(b, 40, 150)
        assertEquals(a.encode(), b.encode())
    }

    @Test
    fun anEconomySavedMidwayResumesAsIfItNeverStopped() {
        val end = start + 80
        val (ledgerA, simA) = freshRun()
        (start until end).forEach { year -> stepOne(ledgerA, simA, year) }

        val (ledgerB, simB) = freshRun()
        val mid = start + 40
        (start until mid).forEach { year -> stepOne(ledgerB, simB, year) }
        val ledgerReloaded = SettlementLedger.fromEncoded(ledgerB.encode(), world)
        val simReloaded = HistoricalSimulation.fromSave(world, simB.encode())
        (mid until end).forEach { year -> stepOne(ledgerReloaded, simReloaded, year) }

        assertEquals(ledgerA.encode(), ledgerReloaded.encode())
        assertEquals("the whole layer rides the save", simA.encode(), simReloaded.encode())
        assertEquals(simA.eventLog(), simReloaded.eventLog())
        // the economy's own arithmetic agrees, item by item
        val midRun = simA.economy
        val reloaded = simReloaded.economy
        sites.forEach { site ->
            assertEquals(midRun.cropsOf(site), reloaded.cropsOf(site))
            assertEquals(midRun.cropStockOf(site), reloaded.cropStockOf(site))
            assertEquals(midRun.foodOf(site), reloaded.foodOf(site))
            assertEquals(midRun.depositsOf(site), reloaded.depositsOf(site))
            assertEquals(midRun.oreStockOf(site), reloaded.oreStockOf(site))
            assertEquals(midRun.materialStockOf(site), reloaded.materialStockOf(site))
            assertEquals(midRun.routesOf(site), reloaded.routesOf(site))
            assertEquals(midRun.reserveOf(site), reloaded.reserveOf(site))
            assertEquals(midRun.timberOf(site), reloaded.timberOf(site))
            assertEquals(midRun.logsOf(site), reloaded.logsOf(site))
        }
    }

    @Test
    fun aCenturyOfYearsStaysBoundedAndReadable() {
        val (ledger, sim) = freshRun()
        val end = start + 120
        (start until end).forEach { year -> stepOne(ledger, sim, year) }
        // the log's room scales with the province — one remembered deed for every
        // living place, never fewer than the old fixed cap (the same room the
        // simulation trims to)
        val logRoom = maxOf(
            HistoricalSimulation.EVENT_LOG_CAP,
            world.sites.count { it.isSettlement && !it.ruined }
        )
        assertTrue("the world remembers what matters", sim.eventLog().size <= logRoom)
        sim.eventLog().forEach { event ->
            assertTrue(event.text.isNotBlank())
            assertEquals(event.year, event.toChronicle().year)
        }
        val economyKinds = setOf(
            AgeEventKind.RESOURCE_DISCOVERED, AgeEventKind.RESOURCE_DEPLETED,
            AgeEventKind.MINE_OPENED, AgeEventKind.QUARRY_OPENED, AgeEventKind.QUARRY_CLOSED,
            AgeEventKind.INDUSTRY_ESTABLISHED, AgeEventKind.INDUSTRY_DECLINED,
            AgeEventKind.CROP_ESTABLISHED, AgeEventKind.CROP_LOST,
            AgeEventKind.HARVEST_FAILURE, AgeEventKind.HARVEST_SURPLUS,
            AgeEventKind.TRADE_ROUTE_OPENED, AgeEventKind.TRADE_ROUTE_CLOSED,
            AgeEventKind.TRADE_ROUTE_STRENGTHENED, AgeEventKind.SPECIALIZED,
            AgeEventKind.ECONOMIC_DECLINE
        )
        sim.eventLog().filter { it.kind in economyKinds }.forEach { event ->
            val chronicle = event.toChronicle()
            assertTrue("economy news lands in kinds the journal knows", chronicle.kind in com.rork.hollowmarch.world.EventKind.entries)
            assertFalse("economy news names its place", chronicle.text.contains("null"))
        }
    }
}
