package com.rork.hollowmarch.world

import com.rork.hollowmarch.game.SkyGen
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Dwarf-Fortress-flavoured world generation. A seed produces peoples with their
 * own phonetics, sovereign realms, the guilds, orders and cults that operate
 * within them, the warbands and rebel movements that live outside the law,
 * centuries of simulated history and the ruins that history left behind.
 * Same seed always rebuilds the same province.
 */
object WorldGenerator {

    /** What the forge is coining while history runs, before the final order is derived. */
    private enum class SeedKind(val title: String) {
        REALM("thegn"),
        GUILD("guildmaster"),
        ORDER("grandmaster"),
        CULT("hierophant"),
        WARBAND("war-chief"),
        REBELS("free-lord")
    }

    /**
     * The forge's own ledger of politics: what was coined, by whom, and where
     * it sits. The final Realm, Organization and NonStateGroup values are read
     * out of these at the end of history. What a seed is was decided the moment
     * it was coined — a realm is never a guild by accident.
     */
    private data class Seed(
        val id: Int,
        val name: String,
        val seedKind: SeedKind,
        val cultureId: Int,
        val foundedYear: Int,
        val founding: FoundingOrigin = FoundingOrigin.ANCIENT_SEAT,
        val splinterFromId: Int? = null,
        val creed: String = "",
        val deityId: Int? = null,
        /** A realm's seat, an organization's headquarters, or a group's base. */
        val seatSiteId: Int? = null,
        val realmType: RealmType? = null
    )

    /** A conflict the forge is still fighting; ended drafts become the chronicle's conflicts. */
    private class ConflictDraft(
        val id: Int,
        val kind: ConflictKind,
        val attackerId: Int,
        val defenderId: Int,
        val cause: String,
        val startYear: Int,
        val targetSiteId: Int
    ) {
        val battles = mutableListOf<Battle>()
        var attackerWins = 0
        var endYear: Int? = null
        var outcome: String = ""
    }

    /** Tally of small hostilities that never became a war: raids, rivalries, feuds. */
    private class Skirmish {
        var count = 0
        var firstYear = 0
        var lastYear = 0

        fun mark(year: Int) {
            count++
            if (firstYear == 0) firstYear = year
            lastYear = year
        }
    }

    /** What a seed's head is called, from what the seed is. */
    private fun titleOf(seed: Seed): String =
        if (seed.seedKind == SeedKind.REALM) seed.realmType?.rulerTitle ?: "thegn"
        else seed.seedKind.title

    /**
     * Forge a province. [historyYears] sets how long the simulation runs, [maxEvents]
     * caps the chronicle — once full, history keeps living but stops being written;
     * pass 0 or less for an uncapped chronicle — and [cultureCount] fixes the number
     * of peoples (0 rolls a random three to five). No setting has a hard ceiling:
     * the forge builds whatever the caller dares to ask for. The same seed with the
     * same settings always produces the same world.
     */
    fun generate(
        seed: Long,
        historyYears: Int = 400,
        maxEvents: Int = 600,
        cultureCount: Int = 0
    ): World {
        val rng = Random(seed)
        val cultures = generateCultures(rng, cultureCount)
        val phonetics = cultures.associate { it.id to Phonetics.forCulture(rng, it.id) }

        // The land itself, from a rng derived only from the seed, so the history
        // simulation below can be re-ordered without changing the province.
        val riverNames = (0 until 5).map {
            "the ${phonetics.getValue(cultures[rng.nextInt(cultures.size)].id).word(rng, 1)}"
        }
        val terrain = generateTerrain(Random(seed * 31 + 7L), riverNames)

        // Every people keeps its own small pantheon.
        val deities = mutableListOf<Deity>()
        var nextDeityId = 0
        cultures.forEach { culture ->
            repeat(2 + rng.nextInt(3)) {
                val domain = DOMAINS.random(rng)
                deities += Deity(
                    id = nextDeityId++,
                    name = phonetics.getValue(culture.id).word(rng, 2),
                    cultureId = culture.id,
                    domain = domain,
                    epithet = "${DEITY_TITLES.random(rng)} $domain"
                )
            }
        }
        val patronDeity = cultures.associate { culture ->
            culture.id to deities.filter { it.cultureId == culture.id }.random(rng).id
        }

        fun deity(id: Int?): Deity? = id?.let { did -> deities.firstOrNull { it.id == did } }

        val province = "Marches of ${cultures[0].name}"

        val totalYears = historyYears.coerceAtLeast(60)
        val ages = generateAges(rng, totalYears)

        val sites = mutableListOf<Site>()
        val seeds = mutableListOf<Seed>()
        // Seeds by id: the yearly passes look coins up thousands of times, and
        // a province of thousands of camps cannot be scanned for each.
        val seedById = HashMap<Int, Seed>()
        val figures = mutableListOf<Figure>()
        val events = EventLedger(if (maxEvents > 0) maxEvents else Int.MAX_VALUE)
        val ruinedSiteIds = mutableSetOf<Int>()
        var nextSiteId = 0
        var nextActorId = 0

        // --- Scale infrastructure: a continent of places must stay cheap to fill. ---
        // A neighborhood grid over the normalized map: hunting open ground asks only
        // the cells around a candidate, never the whole province.
        val placeCell = 0.05f
        val placeGrid = HashMap<Long, MutableList<Site>>()
        fun placeKey(cx: Int, cy: Int): Long = cx.toLong() * 65536L + cy.toLong()
        val siteIdx = mutableMapOf<Int, Int>()
        val siteById = mutableMapOf<Int, Site>()
        fun registerSite(site: Site, idx: Int) {
            siteIdx[site.id] = idx
            siteById[site.id] = site
            val key = placeKey((site.x / placeCell).toInt(), (site.y / placeCell).toInt())
            placeGrid.getOrPut(key) { mutableListOf() }.add(site)
        }
        fun replaceSite(updated: Site) {
            val idx = siteIdx[updated.id] ?: return
            sites[idx] = updated
            siteById[updated.id] = updated
        }
        // Where rivers wet the ground, on the terrain's own grid, so founding rolls
        // need not walk every river point on every attempt.
        val nearRiver = BooleanArray(terrain.size * terrain.size).also { wet ->
            terrain.rivers.forEach { river ->
                river.points.forEach { p ->
                    val cx = (p.x * terrain.size).toInt()
                    val cy = (p.y * terrain.size).toInt()
                    for (dy2 in -6..6) for (dx2 in -6..6) {
                        // 0.06 normalized is the old river-reach, squared per cell
                        if (dx2 * dx2 + dy2 * dy2 > 33) continue
                        val nx2 = cx + dx2
                        val ny2 = cy + dy2
                        if (nx2 in 0 until terrain.size && ny2 in 0 until terrain.size) {
                            wet[ny2 * terrain.size + nx2] = true
                        }
                    }
                }
            }
        }
        fun riverAt(x: Float, y: Float): Boolean {
            val cx = (x * terrain.size).toInt().coerceIn(0, terrain.size - 1)
            val cy = (y * terrain.size).toInt().coerceIn(0, terrain.size - 1)
            return nearRiver[cy * terrain.size + cx]
        }

        // --- Living politics: houses, claims, agendas and the mood of each settlement. ---
        val houses = mutableListOf<House>()
        var nextHouseId = 0
        val claims = mutableListOf<Claim>()
        var nextClaimId = 0
        val loyaltyNow = mutableMapOf<Int, Int>()
        val stabilityNow = mutableMapOf<Int, Int>()
        val garrisonNow = mutableMapOf<Int, Int>()
        val agendasNow = mutableMapOf<Int, List<String>>()
        val childOf = mutableMapOf<Int, MutableList<Int>>()
        // Successions whose passed-over blood still has its claim to press.
        val pendingContests = mutableListOf<Triple<Int, Int, Int>>()

        /** Finds open, livable ground: no sea, no peaks, no crowding the neighbours. */
        fun placeFor(rng: Random, spacing: Float): Pair<Float, Float> {
            var bestX = 0.5f
            var bestY = 0.5f
            var best = -Float.MAX_VALUE
            var found = false
            repeat(14) {
                val x = 0.10f + rng.nextFloat() * 0.80f
                val y = 0.10f + rng.nextFloat() * 0.80f
                val h = terrain.heightAt(x, y)
                if (h < 0.42f || h > 0.74f) return@repeat
                found = true
                // the nearest neighbour, from the grid's own neighborhood: five cells
                // out clears the widest spacing any kind of place asks for
                val cx = (x / placeCell).toInt()
                val cy = (y / placeCell).toInt()
                var nearest = spacing
                for (gx in cx - 2..cx + 2) {
                    for (gy in cy - 2..cy + 2) {
                        val bucket = placeGrid[placeKey(gx, gy)] ?: continue
                        for (s in bucket) {
                            val dx = x - s.x
                            val dy = y - s.y
                            val d = sqrt(dx * dx + dy * dy)
                            if (d < nearest) nearest = d
                        }
                    }
                }
                val score = minOf(nearest / spacing, 1f) * 10f +
                    (if (riverAt(x, y)) 1.5f else 0f) +
                    rng.nextFloat()
                if (score > best) {
                    best = score
                    bestX = x
                    bestY = y
                }
            }
            if (!found) {
                return 0.10f + rng.nextFloat() * 0.80f to 0.10f + rng.nextFloat() * 0.80f
            }
            return bestX to bestY
        }

        fun coinSite(
            kind: SiteKind,
            cultureId: Int,
            sovereign: Int?,
            note: String,
            foundedYear: Int = 0,
            forcedName: String? = null
        ): Site {
            val ph = phonetics.getValue(cultureId)
            val name = forcedName ?: when (kind) {
                SiteKind.BARROW -> "${ADJECTIVES.random(rng)} Barrow"
                SiteKind.SHRINE -> "Shrine of ${ph.word(rng, 2)}"
                SiteKind.CAMP -> "${ph.word(rng, 1)}${SITE_SUFFIX.random(rng)} Camp"
                else -> "${ph.word(rng, 1)}${SITE_SUFFIX.random(rng)}"
            }
            val (px, py) = placeFor(
                rng,
                // spacing in true leagues across the province's league-wide measure:
                // cities keep eight leagues between them, villages scarce more than one
                when (kind) {
                    SiteKind.CAPITAL, SiteKind.CITY -> 8f / WORLD_LEAGUES
                    SiteKind.TOWN -> 4f / WORLD_LEAGUES
                    SiteKind.VILLAGE -> 1.6f / WORLD_LEAGUES
                    else -> 1.0f / WORLD_LEAGUES
                }
            )
            val site = Site(
                id = nextSiteId++,
                name = name,
                kind = kind,
                x = px,
                y = py,
                sovereignRealmId = sovereign,
                note = note,
                foundedYear = foundedYear,
                population = when (kind) {
                    SiteKind.CAPITAL -> 6000 + rng.nextInt(10000)
                    SiteKind.CITY -> 3000 + rng.nextInt(6000)
                    SiteKind.TOWN -> 500 + rng.nextInt(2600)
                    SiteKind.VILLAGE -> 30 + rng.nextInt(370)
                    SiteKind.HOLDFAST -> 150 + rng.nextInt(650)
                    SiteKind.CAMP -> 20 + rng.nextInt(80)
                    else -> 0
                }
            )
            sites += site
            registerSite(site, sites.lastIndex)
            loyaltyNow[site.id] = 62 + rng.nextInt(24)
            stabilityNow[site.id] = 60 + rng.nextInt(28)
            garrisonNow[site.id] =
                if (kind == SiteKind.CAPITAL || kind == SiteKind.CITY) 18 + rng.nextInt(20) else rng.nextInt(12)
            return site
        }

        // Lineage state: a figure's id is its index in the list.
        val rulers = mutableMapOf<Int, Int>()
        val rulerDeathAge = mutableMapOf<Int, Int>()
        val strengthNow = mutableMapOf<Int, Int>()
        val battlesWon = mutableMapOf<Int, Int>()
        val wealthNow = mutableMapOf<Int, Int>()
        // The year a realm's present government took the seat.
        val crownedYear = mutableMapOf<Int, Int>()
        // Each culture's chartered guild, and the halls that keep it.
        val guildOfCulture = mutableMapOf<Int, Int>()
        // Living structures, kept by site: a province of thousands of halls
        // cannot be filtered whole on every question.
        val structuresBySite = HashMap<Int, MutableList<Structure>>()
        var nextStructureId = 0

        fun replaceStructure(updated: Structure) {
            val list = structuresBySite[updated.siteId] ?: return
            val idx = list.indexOfFirst { it.id == updated.id }
            if (idx >= 0) list[idx] = updated
        }
        val conflictDrafts = mutableListOf<ConflictDraft>()
        var nextConflictId = 0
        val raidTallies = HashMap<Pair<Int, Int>, Skirmish>()
        val rebellionTallies = HashMap<Pair<Int, Int>, Skirmish>()
        val rivalryTallies = HashMap<Pair<Int, Int>, Skirmish>()
        val feudTallies = HashMap<Pair<Int, Int>, Skirmish>()
        val grudge = mutableMapOf<Pair<Int, Int>, Int>()
        val extinctActors = mutableSetOf<Int>()
        val artifacts = mutableListOf<Artifact>()
        val beasts = mutableListOf<Beast>()
        var nextArtifactId = 0
        var nextBeastId = 0

        fun coinFigure(
            cultureId: Int,
            bornYear: Int,
            title: String,
            realmId: Int? = null,
            orgId: Int? = null,
            parentIdx: Int? = null,
            houseId: Int? = null
        ): Int {
            figures += Figure(
                name = phonetics.getValue(cultureId).word(rng, 2),
                cultureId = cultureId,
                bornYear = bornYear,
                diedYear = null,
                title = title,
                realmId = realmId,
                orgId = orgId,
                parentIds = parentIdx?.let { listOf(it) } ?: emptyList(),
                houseId = houseId
            )
            return figures.lastIndex
        }

        /** Two figures remember each other: friends and rivals, in small ledgers. */
        fun seedBond(a: Int, b: Int, rival: Boolean) {
            if (a == b || a < 0 || b < 0 || a >= figures.size || b >= figures.size) return
            if (rival) {
                figures[a] = figures[a].copy(rivals = (figures[a].rivals + b).distinct().takeLast(4))
                figures[b] = figures[b].copy(rivals = (figures[b].rivals + a).distinct().takeLast(4))
            } else {
                figures[a] = figures[a].copy(friends = (figures[a].friends + b).distinct().takeLast(4))
                figures[b] = figures[b].copy(friends = (figures[b].friends + a).distinct().takeLast(4))
            }
        }

        // --- Living structures: temples, taverns, markets, guild halls, catacombs. ---
        fun structuresAt(siteId: Int, kind: StructureKind? = null): List<Structure> =
            (structuresBySite[siteId] ?: emptyList())
                .filter { (kind == null || it.kind == kind) && !it.ruined }

        fun livingKeeper(structure: Structure): Boolean =
            structure.keeperFigureId?.let { figures[it].diedYear == null } ?: false

        fun coinKeeper(site: Site, kind: StructureKind, year: Int, cultureId: Int): Int {
            val title = when (kind) {
                StructureKind.TEMPLE -> "priest of ${site.name}"
                StructureKind.TAVERN -> "innkeeper of ${site.name}"
                StructureKind.MARKET -> "merchant of ${site.name}"
                StructureKind.GUILD -> "guildmaster of ${site.name}"
                StructureKind.CATACOMB -> "gravedigger of ${site.name}"
            }
            return coinFigure(cultureId, year - (19 + rng.nextInt(24)), title)
        }

        fun coinStructure(
            site: Site,
            kind: StructureKind,
            year: Int,
            cultureId: Int,
            organizationId: Int? = null
        ): Structure {
            val ph = phonetics.getValue(cultureId)
            val name = when (kind) {
                StructureKind.TEMPLE -> "Temple of ${deity(patronDeity[cultureId])?.name ?: ph.word(rng, 2)}"
                StructureKind.TAVERN -> "The ${ADJECTIVES.random(rng)} ${NOUNS.random(rng)}"
                StructureKind.MARKET -> "${site.name} Market"
                StructureKind.GUILD -> "The ${ADJECTIVES.random(rng)} Hall"
                StructureKind.CATACOMB -> "The ${ADJECTIVES.random(rng)} Catacombs"
            }
            val keeperIdx = coinKeeper(site, kind, year, cultureId)
            val structure = Structure(
                id = nextStructureId++,
                siteId = site.id,
                kind = kind,
                name = name,
                foundedYear = year,
                keeperFigureId = keeperIdx,
                organizationId = organizationId
            )
            structuresBySite.getOrPut(site.id) { mutableListOf() }.add(structure)
            val keeper = figures[keeperIdx]
            events += ChronicleEvent(
                year,
                when (kind) {
                    StructureKind.TEMPLE -> EventKind.CONSECRATION
                    StructureKind.TAVERN -> EventKind.TAVERN
                    StructureKind.MARKET -> EventKind.CHARTER
                    StructureKind.GUILD -> EventKind.GUILDHALL
                    StructureKind.CATACOMB -> EventKind.CATACOMB
                },
                when (kind) {
                    StructureKind.TEMPLE ->
                        "${keeper.name} consecrates $name at ${site.name}; the god keeps the hearth."
                    StructureKind.TAVERN ->
                        "$name opens its doors at ${site.name}; ${keeper.name} pours the first cup."
                    StructureKind.MARKET ->
                        "${site.name} is granted a market charter; ${keeper.name} keeps the scales."
                    StructureKind.GUILD ->
                        "$name is raised at ${site.name}; ${keeper.name} keeps the rolls and the grudges."
                    StructureKind.CATACOMB ->
                        "The dead of ${site.name} go under the hill; ${keeper.name} seals $name."
                }
            )
            return structure
        }

        fun coinRuler(actor: Seed, year: Int, houseId: Int? = null): Int {
            val idx = coinFigure(
                actor.cultureId, year - (22 + rng.nextInt(26)),
                titleOf(actor),
                realmId = if (actor.seedKind == SeedKind.REALM) actor.id else null,
                orgId = if (actor.seedKind == SeedKind.REALM) null else actor.id
            )
            rulers[actor.id] = idx
            rulerDeathAge[idx] = 46 + rng.nextInt(36)
            if (houseId != null) {
                val hIdx = houses.indexOfFirst { it.id == houseId }
                if (hIdx >= 0) houses[hIdx] = houses[hIdx].copy(headFigureId = idx)
            }
            return idx
        }

        /** A noble house takes its name; its blood will press its rights in time. */
        fun coinHouse(cultureId: Int, year: Int, patronRealm: Int?): House {
            val house = House(
                id = nextHouseId++,
                name = "House ${phonetics.getValue(cultureId).word(rng, 1)}",
                cultureId = cultureId,
                foundedYear = year,
                headFigureId = null,
                patronRealmId = patronRealm
            )
            houses += house
            return house
        }

        /** Two bloodlines are now feuding: the rolls keep it, the chronicle whispers it. */
        fun linkFeud(a: Int, b: Int, year: Int) {
            if (a == b) return
            val key = if (a < b) a to b else b to a
            feudTallies.getOrPut(key) { Skirmish() }.mark(year)
            val aIdx = houses.indexOfFirst { it.id == key.first }
            val bIdx = houses.indexOfFirst { it.id == key.second }
            if (aIdx >= 0) {
                houses[aIdx] = houses[aIdx].copy(rivalHouseIds = (houses[aIdx].rivalHouseIds + key.second).distinct())
            }
            if (bIdx >= 0) {
                houses[bIdx] = houses[bIdx].copy(rivalHouseIds = (houses[bIdx].rivalHouseIds + key.first).distinct())
            }
        }

        /**
         * A ruler dies; an heir — often of their blood — takes the seat.
         * Organizations simply raise the next hand from among their own.
         */
        fun succeed(actorId: Int, year: Int, cause: String) {
            val oldIdx = rulers[actorId] ?: return
            val old = figures[oldIdx]
            if (old.diedYear == null) {
                figures[oldIdx] = old.copy(diedYear = year, deathCause = cause)
            }
            val actor = seedById[actorId] ?: return
            if (actor.seedKind != SeedKind.REALM) {
                val heirIdx = coinFigure(actor.cultureId, year - (20 + rng.nextInt(20)), titleOf(actor), orgId = actorId)
                rulers[actorId] = heirIdx
                rulerDeathAge[heirIdx] = 46 + rng.nextInt(36)
                events += ChronicleEvent(
                    year,
                    EventKind.SUCCESSION,
                    "${figures[heirIdx].name} takes the ${titleOf(actor)}'s seat of ${actor.name}."
                )
                return
            }
            // Temple legitimacy calms a succession; a rich guild can buy one instead.
            val seat = actor.seatSiteId?.let { sid -> siteById[sid]?.takeIf { !it.ruined } }
            val temple = seat?.let { s ->
                structuresAt(s.id, StructureKind.TEMPLE).firstOrNull { livingKeeper(it) }
            }
            val guildBacker = if (temple == null) {
                seeds.firstOrNull {
                    it.seedKind == SeedKind.GUILD && it.id !in extinctActors &&
                        it.cultureId == actor.cultureId && (wealthNow[it.id] ?: 0) >= 130
                }
            } else {
                null
            }
            val usurped = guildBacker != null && rng.nextInt(4) == 0
            if (usurped && guildBacker != null) {
                wealthNow[guildBacker.id] = (wealthNow[guildBacker.id] ?: 0) - 110
                // Bought seats are bitter seats.
                seat?.let { loyaltyNow[it.id] = ((loyaltyNow[it.id] ?: 70) - (8 + rng.nextInt(8))).coerceIn(0, 100) }
            }
            // Adult children of the dead ruler can contest the seat itself.
            val children = (childOf[oldIdx] ?: emptyList())
                .filter { figures[it].diedYear == null && year - figures[it].bornYear >= 15 }
            val contested = !usurped && children.size >= 2 && rng.nextInt(10) < 6
            val heirIdx = when {
                contested -> {
                    val heir = children.first()
                    figures[heir] = figures[heir].copy(title = titleOf(actor), realmId = actorId)
                    pendingContests += Triple(actorId, children[1], 40 + rng.nextInt(45))
                    heir
                }
                !usurped && rng.nextInt(10) < 6 ->
                    coinFigure(actor.cultureId, year - (16 + rng.nextInt(24)), titleOf(actor), realmId = actorId, parentIdx = oldIdx, houseId = old.houseId)
                else ->
                    coinFigure(actor.cultureId, year - (24 + rng.nextInt(18)), titleOf(actor), realmId = actorId, houseId = old.houseId)
            }
            val asHeir = contested || oldIdx in figures[heirIdx].parentIds
            rulers[actorId] = heirIdx
            rulerDeathAge[heirIdx] = 46 + rng.nextInt(36)
            crownedYear[actorId] = year
            figures[heirIdx].houseId?.let { hid ->
                val hIdx = houses.indexOfFirst { it.id == hid }
                if (hIdx >= 0) houses[hIdx] = houses[hIdx].copy(headFigureId = heirIdx)
            }
            events += ChronicleEvent(
                year,
                EventKind.SUCCESSION,
                "${figures[heirIdx].name} takes the ${titleOf(actor)}'s seat of ${actor.name}" +
                    when {
                        usurped && guildBacker != null ->
                            ", bought with ${guildBacker.name} gold; the old blood seethes."
                        temple != null ->
                            if (asHeir) ", heir of ${old.name}, anointed at the ${temple.name}."
                            else ", anointed at the ${temple.name}."
                        asHeir -> ", heir of ${old.name}." + if (contested) " The younger blood grumbles." else ""
                        else -> ", raised from among the sworn."
                    }
            )
        }

        /** Rulers die when their years run out; so do the rest, eventually. */
        fun mortalityStep(year: Int) {
            for (actor in seeds.toList()) {
                val rIdx = rulers[actor.id] ?: continue
                val fig = figures[rIdx]
                if (fig.diedYear != null) continue
                if (year - fig.bornYear >= (rulerDeathAge[rIdx] ?: 60)) {
                    succeed(actor.id, year, DEATHS.random(rng))
                }
            }
            val old = figures.withIndex().filter { it.value.diedYear == null && year - it.value.bornYear > 58 }
            if (old.size > 2 && rng.nextInt(3) == 0) {
                val (idx, fig) = old.random(rng)
                if (!rulers.containsValue(idx)) {
                    figures[idx] = fig.copy(diedYear = year, deathCause = DEATHS.random(rng))
                    events += ChronicleEvent(
                        year,
                        EventKind.DEATH,
                        "${fig.name} the ${fig.title} dies at ${sites.random(rng).name}; the debts pass to kin."
                    )
                    // Grief with a ledger behind it: the rivals do not mourn.
                    val watchers = fig.rivals.filter { figures.getOrNull(it)?.diedYear == null }
                    if (watchers.isNotEmpty() && rng.nextInt(2) == 0) {
                        val watcher = figures[watchers.random(rng)]
                        events += ChronicleEvent(
                            year,
                            EventKind.PLOT,
                            "${fig.name} the ${fig.title} is dead; ${watcher.name} the ${watcher.title} " +
                                "does not mourn, and counts the inheritance."
                        )
                    }
                }
            }
        }

        /**
         * Coin a new actor of the kind asked for. Foundings only ever ask for
         * realms; guilds, orders and cults are coined by their own passes and
         * never hold ground; warbands and rebel movements live outside the law.
         */
        fun coinSeed(
            kind: SeedKind,
            cultureId: Int,
            year: Int,
            parent: Int?,
            deityId: Int? = null,
            realmType: RealmType? = null
        ): Seed {
            val culture = cultures[cultureId]
            val name = when (kind) {
                SeedKind.REALM -> phonetics.getValue(cultureId).word(rng, 2)
                SeedKind.ORDER -> "Order of the ${ADJECTIVES.random(rng)} ${NOUNS.random(rng)}"
                SeedKind.CULT -> if (rng.nextBoolean()) {
                    "The ${ADJECTIVES.random(rng)} ${NOUNS.random(rng)}"
                } else {
                    "${ADJECTIVES.random(rng)}bound of ${culture.name}"
                }
                SeedKind.GUILD -> "The ${ADJECTIVES.random(rng)} Guild"
                SeedKind.WARBAND -> "${phonetics.getValue(cultureId).word(rng, 2)}'s Riders"
                SeedKind.REBELS -> "The ${ADJECTIVES.random(rng)} Rising"
            }
            val seed = Seed(
                id = nextActorId++,
                name = name,
                seedKind = kind,
                cultureId = cultureId,
                foundedYear = year,
                splinterFromId = parent,
                creed = CREEDS.random(rng),
                deityId = deityId,
                realmType = realmType
            )
            strengthNow[seed.id] = 30 + rng.nextInt(50)
            battlesWon[seed.id] = 0
            wealthNow[seed.id] = (strengthNow[seed.id] ?: 30) / 2
            agendasNow[seed.id] = AGENDAS.shuffled(rng).take(1 + rng.nextInt(3))
            seeds += seed
            seedById[seed.id] = seed
            return seed
        }

        /** Replace a coin's staging record in place, keeping the id index honest. */
        fun reseat(seed: Seed) {
            val idx = seeds.indexOfFirst { it.id == seed.id }
            if (idx >= 0) {
                seeds[idx] = seed
                seedById[seed.id] = seed
            }
        }

        /** The culture whose hands made this place: its sovereign's people, or its base's. */
        fun stewardCultureOf(site: Site): Int =
            site.sovereignRealmId?.let { rid -> seedById[rid]?.cultureId }
                ?: seeds.firstOrNull { it.seedKind == SeedKind.WARBAND && it.seatSiteId == site.id }?.cultureId
                ?: 0

        /** Markets and guild halls earn their keep each turn; dead keepers are replaced. */
        fun structureStep(year: Int) {
            for (st in structuresBySite.values.flatten()) {
                if (st.ruined) continue
                val site = siteById[st.siteId] ?: continue
                if (site.ruined) continue
                when (st.kind) {
                    // A guild hall's tithe is the guild's, not the crown's.
                    StructureKind.GUILD -> st.organizationId?.takeIf { it !in extinctActors }?.let { gid ->
                        wealthNow[gid] = (wealthNow[gid] ?: 0) + 5
                    }
                    StructureKind.MARKET, StructureKind.TAVERN, StructureKind.TEMPLE ->
                        site.sovereignRealmId?.takeIf { it !in extinctActors }?.let { rid ->
                            wealthNow[rid] = (wealthNow[rid] ?: 0) +
                                if (st.kind == StructureKind.MARKET) 3 else 1
                        }
                    StructureKind.CATACOMB -> {}
                }
                val kIdx = st.keeperFigureId
                if (kIdx != null && figures[kIdx].diedYear == null &&
                    year - figures[kIdx].bornYear > 55 && rng.nextInt(4) == 0
                ) {
                    figures[kIdx] = figures[kIdx].copy(diedYear = year, deathCause = DEATHS.random(rng))
                    // Some debts are settled with a cutthroat.
                    val cutthroats = figures[kIdx].rivals.filter { figures.getOrNull(it)?.diedYear == null }
                    if (cutthroats.isNotEmpty() && rng.nextInt(3) == 0) {
                        val rival = figures[cutthroats.random(rng)]
                        events += ChronicleEvent(
                            year,
                            EventKind.PLOT,
                            "${figures[kIdx].name} the ${figures[kIdx].title} of ${site.name} dies; " +
                                "${rival.name} the ${rival.title} paid the cutthroat, it is whispered."
                        )
                    }
                }
            }
            // Swelling towns pour and trade; the dead pile up under the hill.
            val living = sites.filter { !it.ruined && it.isSettlement && it.population > 0 }
            living.firstOrNull {
                it.population >= 500 && structuresAt(it.id, StructureKind.TAVERN).isEmpty() && rng.nextInt(6) == 0
            }?.let { site ->
                coinStructure(site, StructureKind.TAVERN, year, stewardCultureOf(site))
            }
            living.firstOrNull {
                it.population >= 80 && structuresAt(it.id, StructureKind.CATACOMB).isEmpty() && rng.nextInt(8) == 0
            }?.let { site ->
                coinStructure(site, StructureKind.CATACOMB, year, stewardCultureOf(site))
            }
        }

        /** A pressed right to a settlement; the rolls remember who pressed it. */
        fun pressClaim(siteId: Int, claimantId: Int?, figureId: Int?, strength: Int, origin: String, year: Int) {
            val pressable = strength.coerceIn(10, 95)
            if (claims.any { it.siteId == siteId && it.claimantId == claimantId }) return
            claims += Claim(
                id = nextClaimId++,
                siteId = siteId,
                claimantId = claimantId,
                claimantFigureId = figureId,
                strength = pressable,
                origin = origin,
                madeYear = year
            )
            val claimant = seedById[claimantId]
            val site = siteById[siteId]
            events += ChronicleEvent(
                year,
                EventKind.CLAIM,
                "${claimant?.name ?: "An old line"} press a claim on ${site?.name ?: "lost ground"} — " +
                    "$origin. The rolls give it strength $pressable."
            )
        }

        fun adjustGrudge(a: Int, b: Int, delta: Int) {
            val key = if (a < b) a to b else b to a
            grudge[key] = (grudge[key] ?: 0) + delta
        }

        fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx
            val dy = ay - by
            return sqrt(dx * dx + dy * dy)
        }

        /** A chartered city raises a guild hall, and the hall belongs to a guild. */
        fun charterGuild(site: Site, year: Int, cultureId: Int) {
            val existing = guildOfCulture[cultureId]
            if (existing != null) {
                coinStructure(site, StructureKind.GUILD, year, cultureId, organizationId = existing)
                return
            }
            val hall = coinStructure(site, StructureKind.GUILD, year, cultureId)
            val guild = coinSeed(SeedKind.GUILD, cultureId, year, null)
            guildOfCulture[cultureId] = guild.id
            reseat(guild.copy(seatSiteId = site.id))
            coinRuler(guild, year)
            val list = structuresBySite[site.id] ?: return
            val idx = list.indexOfFirst { it.id == hall.id }
            if (idx >= 0) list[idx] = hall.copy(organizationId = guild.id)
        }

        fun generalFor(actor: Seed, year: Int): Int {
            val rIdx = rulers[actor.id]
            if (rIdx != null && figures[rIdx].diedYear == null) return rIdx
            return coinFigure(actor.cultureId, year - (26 + rng.nextInt(20)), "champion", realmId = actor.id)
        }

        /** Whatever was kept at a burned site is lost to history. */
        fun scatterArtifacts(site: Site, year: Int) {
            for (i in artifacts.indices) {
                val a = artifacts[i]
                if (a.keeperSiteId == site.id) {
                    artifacts[i] = a.copy(keeperSiteId = null, whereabouts = "lost when ${site.name} burned in yr $year")
                }
            }
        }

        /** A sacked site's relics are carried off to the victor's seat — or not at all. */
        fun moveArtifacts(site: Site, newHolder: Seed, year: Int) {
            val newSeat = sites.firstOrNull { it.sovereignRealmId == newHolder.id && !it.ruined }
            for (i in artifacts.indices) {
                val a = artifacts[i]
                if (a.keeperSiteId == site.id) {
                    artifacts[i] = if (newSeat != null) {
                        a.copy(keeperSiteId = newSeat.id, whereabouts = "carried off to ${newSeat.name} in yr $year")
                    } else {
                        a.copy(keeperSiteId = null, whereabouts = "lost on the ${site.name} road in yr $year")
                    }
                }
            }
        }

        fun captureSite(site: Site, attacker: Seed, defender: Seed, year: Int) {
            val old = siteById[site.id] ?: return
            val burned = rng.nextInt(4) == 0
            replaceSite(
                old.copy(
                    sovereignRealmId = attacker.id,
                    sackedCount = old.sackedCount + 1,
                    population = if (burned) 0 else (old.population * 2 / 3).coerceAtLeast(10),
                    ruined = burned
                )
            )
            // The conquered keep their own counsel: loyalty collapses under new banners.
            loyaltyNow[old.id] = 15 + rng.nextInt(25)
            stabilityNow[old.id] = (stabilityNow[old.id] ?: 70) / 2
            if (burned) {
                ruinedSiteIds += old.id
                var burnedHalls = 0
                structuresBySite[old.id]?.let { halls ->
                    for (j in halls.indices) {
                        val st = halls[j]
                        if (!st.ruined) {
                            halls[j] = st.copy(ruined = true)
                            burnedHalls++
                        }
                    }
                }
                scatterArtifacts(old, year + 1)
                events += if (old.isSettlement) {
                    ChronicleEvent(
                        year + 1,
                        EventKind.DESTRUCTION,
                        "${old.name} burns. ${formatCount(maxOf(old.population, 30))} souls die or scatter; " +
                            if (burnedHalls > 0) "the taverns burn first; ${attacker.name} march on."
                            else "${attacker.name} march on."
                    )
                } else {
                    ChronicleEvent(
                        year + 1,
                        EventKind.RUIN,
                        "${old.name} is left to the crows; its wells are fouled."
                    )
                }
            } else {
                moveArtifacts(old, attacker, year)
                events += ChronicleEvent(
                    year,
                    EventKind.WAR,
                    "${old.name} passes to ${attacker.name}; ${defender.name} fall back."
                )
                // The dispossessed remember: the beaten line presses its right.
                pressClaim(
                    old.id,
                    defender.id,
                    rulers[defender.id]?.takeIf { figures[it].diedYear == null },
                    55 + rng.nextInt(40),
                    "held by ${defender.name} until year $year",
                    year
                )
            }
            if (!sites.any { it.sovereignRealmId == defender.id && !it.ruined } && defender.id !in extinctActors) {
                extinctActors += defender.id
                events += ChronicleEvent(
                    year + 1,
                    EventKind.DEATH,
                    "${defender.name} is broken. Its banners burn; its debts pass to strangers."
                )
            }
        }

        fun endWar(draft: ConflictDraft, year: Int, outcome: String) {
            if (draft.endYear != null) return
            draft.endYear = year
            draft.outcome = outcome
            val attacker = seedById[draft.attackerId]
            val defender = seedById[draft.defenderId]
            if (attacker != null && defender != null) {
                adjustGrudge(attacker.id, defender.id, 15)
                events += ChronicleEvent(
                    year,
                    EventKind.TREATY,
                    if (outcome.contains("took")) {
                        "${defender.name} swear peace and pay ${attacker.name} a tithe of grain and brass."
                    } else {
                        "${attacker.name} and ${defender.name} swear peace; ${outcome}."
                    }
                )
            }
        }

        /** A dispossessed realm presses its right to a settlement; the rolls remember. */
        fun declareWar(year: Int): Boolean {
            // Only realms march on realms: a guild feud never spends a kingdom's war.
            val alive = seeds.filter { it.seedKind == SeedKind.REALM && it.id !in extinctActors }
            if (alive.size < 2) return false
            // Agendas choose the blade: expansionists march, the vengeful follow.
            val ambitious = alive.filter {
                (agendasNow[it.id] ?: emptyList()).any { g -> g == "expand the borders" || g == "press old claims" }
            }
            val vengeful = alive.filter { "avenge old grudges" in (agendasNow[it.id] ?: emptyList()) }
            val attacker = when {
                ambitious.isNotEmpty() && rng.nextInt(10) < 6 -> ambitious.random(rng)
                vengeful.isNotEmpty() && rng.nextInt(10) < 4 -> vengeful.random(rng)
                else -> alive.random(rng)
            }
            val enemyPool = alive.filter { it.id != attacker.id }
            // A pressed claim decides both foe and field when one can be warred over.
            val claimPair = claims
                .filter { it.claimantId == attacker.id }
                .mapNotNull { c ->
                    val targetSite = siteById[c.siteId]?.takeIf {
                        !it.ruined && it.kind != SiteKind.VAULT &&
                            it.sovereignRealmId != null && it.sovereignRealmId != attacker.id
                    }
                    if (targetSite == null) null else c to targetSite
                }
                .maxByOrNull { (c, _) -> c.strength }
            val claimantRealm = claimPair?.second?.sovereignRealmId
                ?.let { hid -> alive.firstOrNull { it.id == hid } }
            val defender = when {
                claimantRealm != null && rng.nextInt(10) < 7 -> claimantRealm
                // Grudges draw the blade: the bitterest rival is attacked first.
                rng.nextInt(10) < 6 -> enemyPool.minByOrNull {
                    grudge[if (it.id < attacker.id) it.id to attacker.id else attacker.id to it.id] ?: 0
                } ?: enemyPool.random(rng)
                else -> enemyPool.random(rng)
            }
            val claimWar = claimPair != null && claimantRealm?.id == defender.id
            val cause = when {
                claimWar ->
                    "their claim on ${claimPair!!.second.name} — ${claimPair.first.origin}, strength ${claimPair.first.strength}"
                attacker.creed != defender.creed && rng.nextBoolean() ->
                    "the doctrine that ${defender.creed}"
                else -> WAR_CAUSES.random(rng)
            }
            val target = if (claimWar) {
                claimPair!!.second
            } else {
                sites
                    .filter { it.sovereignRealmId == defender.id && !it.ruined && it.kind != SiteKind.VAULT }
                    .ifEmpty { sites.filter { it.kind != SiteKind.VAULT } }
                    .random(rng)
            }
            conflictDrafts += ConflictDraft(nextConflictId++, ConflictKind.REALM_WAR, attacker.id, defender.id, cause, year, target.id)
            adjustGrudge(attacker.id, defender.id, -70)
            events += ChronicleEvent(
                year,
                EventKind.WAR,
                "${attacker.name} march on ${defender.name} over $cause."
            )
            return true
        }

        fun warStep(year: Int) {
            // Wars that outlive two generations are recorded as they fade.
            conflictDrafts
                .filter {
                    it.kind == ConflictKind.REALM_WAR && it.endYear == null &&
                        it.battles.isNotEmpty() && year - it.startYear > 60
                }
                .forEach { endWar(it, year, "the war peters out; both sides keep their anger") }
            val active = conflictDrafts.filter { it.kind == ConflictKind.REALM_WAR && it.endYear == null }
            if (active.isNotEmpty() && rng.nextInt(3) > 0) {
                // The oldest grudge is fought first.
                val draft = active.minByOrNull { it.startYear } ?: return
                val attacker = seedById[draft.attackerId] ?: return
                val defender = seedById[draft.defenderId] ?: return
                if (attacker.id in extinctActors || defender.id in extinctActors) {
                    endWar(draft, year, "both halls had other griefs")
                    return
                }
                val site = siteById[draft.targetSiteId] ?: sites.first()
                val atkGen = generalFor(attacker, year)
                val defGen = generalFor(defender, year)
                val atkStr = strengthNow[attacker.id] ?: 40
                val defStr = strengthNow[defender.id] ?: 40
                // A rightful claim stiffens the attacker's spine.
                val claimStrength = claims
                    .firstOrNull { it.claimantId == attacker.id && it.siteId == site.id }?.strength ?: 0
                val attackerWon = rng.nextInt(atkStr + defStr) < atkStr + claimStrength / 3
                val dead = 90 + rng.nextInt(1500)
                draft.battles += Battle(
                    year = year,
                    siteId = site.id,
                    attackerId = attacker.id,
                    defenderId = defender.id,
                    attackerGeneralId = atkGen,
                    defenderGeneralId = defGen,
                    dead = dead,
                    attackerWon = attackerWon
                )
                events += ChronicleEvent(
                    year,
                    EventKind.BATTLE,
                    if (attackerWon) {
                        "At ${site.name}, ${attacker.name} break the line of ${defender.name}. ${formatCount(dead)} dead."
                    } else {
                        "At ${site.name}, ${defender.name} hold the field against ${attacker.name}. ${formatCount(dead)} dead."
                    }
                )
                // War sours the ground it stands on.
                loyaltyNow[site.id] = ((loyaltyNow[site.id] ?: 70) - (4 + rng.nextInt(8))).coerceIn(0, 100)
                // Generals who bleed each other remember it.
                if (atkGen != defGen && rng.nextInt(3) == 0) seedBond(atkGen, defGen, rival = true)
                // Generals sometimes fall; rulers are succeeded in the field.
                listOf(attacker to atkGen, defender to defGen).forEach { (side, gen) ->
                    if (rng.nextInt(8) == 0 && figures[gen].diedYear == null) {
                        if (rulers[side.id] == gen) {
                            succeed(side.id, year, "fell at ${site.name}")
                        } else {
                            val fig = figures[gen]
                            figures[gen] = fig.copy(diedYear = year, deathCause = "fell at ${site.name}")
                            events += ChronicleEvent(
                                year,
                                EventKind.DEATH,
                                "${fig.name} the ${fig.title} falls at ${site.name}."
                            )
                        }
                    }
                }
                if (attackerWon) {
                    draft.attackerWins++
                    strengthNow[attacker.id] = (strengthNow[attacker.id] ?: 40) + 6
                    battlesWon[attacker.id] = (battlesWon[attacker.id] ?: 0) + 1
                    rulers[attacker.id]?.let { rIdx ->
                        val r = figures[rIdx]
                        if (r.diedYear == null) {
                            figures[rIdx] = r.copy(feats = r.feats + "bled ${defender.name} at ${site.name} in yr $year")
                        }
                    }
                } else {
                    strengthNow[defender.id] = (strengthNow[defender.id] ?: 40) + 6
                    battlesWon[defender.id] = (battlesWon[defender.id] ?: 0) + 1
                }
                if (draft.attackerWins >= 2) {
                    captureSite(site, attacker, defender, year)
                    // A satisfied claim is struck from the rolls; the rightful lords are welcome.
                    if (claims.removeAll { it.claimantId == attacker.id && it.siteId == site.id }) {
                        loyaltyNow[site.id] = ((loyaltyNow[site.id] ?: 70) + 15).coerceIn(0, 100)
                    }
                    endWar(draft, year, "${attacker.name} took ${site.name}")
                } else if (draft.battles.size >= 2 + draft.id % 2) {
                    if (draft.attackerWins >= 1) {
                        // A war's worth of victories tells: the field changes hands.
                        captureSite(site, attacker, defender, year)
                        if (claims.removeAll { it.claimantId == attacker.id && it.siteId == site.id }) {
                            loyaltyNow[site.id] = ((loyaltyNow[site.id] ?: 70) + 15).coerceIn(0, 100)
                        }
                        endWar(draft, year, "${attacker.name} took ${site.name}")
                    } else {
                        endWar(draft, year, "white peace; both sides bury their dead and keep their anger")
                    }
                }
            } else {
                declareWar(year)
            }
        }

        fun schismStep(year: Int) {
            val parent = seeds.filter { it.id !in extinctActors }.randomOrNull(rng) ?: return
            val child = coinSeed(
                if (rng.nextBoolean()) SeedKind.CULT else SeedKind.ORDER,
                parent.cultureId,
                year,
                parent.id,
                parent.deityId
            )
            coinRuler(child, year)
            if (parent.seedKind != SeedKind.REALM) adjustGrudge(child.id, parent.id, -45)
            events += ChronicleEvent(
                year,
                EventKind.SCHISM,
                "${child.name} splinters from ${parent.name} over the doctrine ${child.creed}."
            )
        }

        /** Warbands raid the living; they pillage, they never conquer. */
        fun raidStep(year: Int) {
            val bands = seeds.filter { it.seedKind == SeedKind.WARBAND && it.id !in extinctActors }
            if (bands.isEmpty()) return
            val band = bands.random(rng)
            val base = band.seatSiteId?.let { siteById[it] } ?: return
            val prey = sites
                .filter { !it.ruined && it.isSettlement && it.sovereignRealmId != null && it.sovereignRealmId != band.id }
                .minByOrNull { dist(base.x, base.y, it.x, it.y) } ?: return
            val realmId = prey.sovereignRealmId ?: return
            val taken = 2 + rng.nextInt(18)
            replaceSite(prey.copy(population = (prey.population - taken).coerceAtLeast(0)))
            wealthNow[realmId] = (wealthNow[realmId] ?: 0) - (5 + rng.nextInt(10))
            loyaltyNow[prey.id] = ((loyaltyNow[prey.id] ?: 70) - (2 + rng.nextInt(6))).coerceIn(0, 100)
            raidTallies.getOrPut(band.id to realmId) { Skirmish() }.mark(year)
            events += ChronicleEvent(
                year,
                EventKind.RAID,
                "${band.name} ride out of the wilds: $taken souls dragged from ${prey.name}, " +
                    "and the watch rides out too late."
            )
        }

        /** Guilds war with ledgers and torches, never with armies. */
        fun rivalryStep(year: Int) {
            val guilds = seeds.filter { it.seedKind == SeedKind.GUILD && it.id !in extinctActors }
            if (guilds.size < 2) return
            val a = guilds.random(rng)
            val kin = guilds.filter { it.id != a.id && it.cultureId == a.cultureId }
            val b = (if (kin.isNotEmpty()) kin else guilds.filter { it.id != a.id }).random(rng)
            adjustGrudge(a.id, b.id, -20)
            rivalryTallies.getOrPut(if (a.id < b.id) a.id to b.id else b.id to a.id) { Skirmish() }.mark(year)
            events += ChronicleEvent(
                year,
                EventKind.PLOT,
                "${a.name} and ${b.name} are at odds: contracts bought out, apprentices poached, " +
                    "a warehouse burned by night."
            )
        }

        /** Houses feud over marriages, boundaries and old insults. */
        fun feudStep(year: Int) {
            val living = houses.filter { !it.extinct && it.headFigureId != null }
            if (living.size < 2) return
            val a = living.random(rng)
            val kin = living.filter { it.id != a.id && it.patronRealmId == a.patronRealmId }
            val b = (if (kin.isNotEmpty()) kin else living.filter { it.id != a.id }).random(rng)
            linkFeud(a.id, b.id, year)
            events += ChronicleEvent(
                year,
                EventKind.PLOT,
                "${a.name} and ${b.name} open a feud: a marriage refused, a boundary stone moved in the night."
            )
        }

        fun hardshipStep(year: Int) {
            val target = sites.filter { !it.ruined && it.population > 40 }.randomOrNull(rng) ?: return
            // Misery deepens where the people have lost faith in their lords.
            val restless = (loyaltyNow[target.id] ?: 70) < 35
            val lost = (target.population * (30 + rng.nextInt(30)) / 100 * (if (restless) 5 else 4) / 4)
                .coerceAtLeast(20)
            val remaining = target.population - lost
            if (target.isSettlement && remaining < 25) {
                // The rot wins: the last families walk away and the place dies for good.
                // Only the catacombs keep; the dead stay when the living will not.
                structuresBySite[target.id]?.let { halls ->
                    for (j in halls.indices) {
                        val st = halls[j]
                        if (!st.ruined && st.kind != StructureKind.CATACOMB) {
                            halls[j] = st.copy(ruined = true)
                        }
                    }
                }
                replaceSite(target.copy(population = 0, ruined = true))
                ruinedSiteIds += target.id
                events += ChronicleEvent(
                    year,
                    EventKind.DESTRUCTION,
                    "The last families walk out of ${target.name}; " +
                        if (rng.nextBoolean()) "the grey rot has won." else "the empty granaries have won."
                )
            } else {
                replaceSite(target.copy(population = remaining))
                loyaltyNow[target.id] = ((loyaltyNow[target.id] ?: 70) - (10 + rng.nextInt(16))).coerceIn(0, 100)
                stabilityNow[target.id] = ((stabilityNow[target.id] ?: 70) - (8 + rng.nextInt(12))).coerceIn(0, 100)
                val catacombs = structuresAt(target.id, StructureKind.CATACOMB).isNotEmpty()
                val blame = if (restless) " The people blame their lords." else ""
                events += if (rng.nextBoolean()) {
                    ChronicleEvent(
                        year, EventKind.PLAGUE,
                        "Grey rot takes ${target.name}. ${formatCount(lost)} dead; the gates stay shut nine months." +
                            (if (catacombs) " The catacombs fill." else "") + blame
                    )
                } else {
                    ChronicleEvent(
                        year, EventKind.FAMINE,
                        "The granaries at ${target.name} fail. ${formatCount(lost)} dead before the river barges come." +
                            (if (catacombs) " The catacombs fill." else "") + blame
                    )
                }
            }
        }

        fun foundingStep(year: Int) {
            // The founding law: souls beget steads. A fuller province founds more,
            // and crowded ground begets new hamlets beside the old — the count of
            // places is whatever the centuries produce, never a number the forge
            // is told. Wild places accumulate the same way: camps, ruins, shrines.
            val souls = sites.sumOf { it.population }
            repeat(souls / 10000 + sites.size / 120 + 1) {
                val culture = cultures.random(rng)
                // Only a realm can receive new ground: organizations and bands
                // operate within the land, they are never its sovereign.
                val realm = seeds
                    .filter { it.seedKind == SeedKind.REALM && it.cultureId == culture.id && it.id !in extinctActors }
                    .randomOrNull(rng)
                val kind = when (rng.nextInt(100)) {
                    in 0..54 -> SiteKind.VILLAGE
                    in 55..74 -> SiteKind.CAMP
                    in 75..84 -> SiteKind.HOLDFAST
                    in 85..92 -> SiteKind.TOWN
                    in 93..96 -> SiteKind.RUIN
                    else -> SiteKind.SHRINE
                }
                val newSite = coinSite(
                    kind, culture.id,
                    if (kind == SiteKind.CAMP || kind == SiteKind.RUIN || kind == SiteKind.SHRINE) null else realm?.id,
                    "founded in year $year", year
                )
                if (kind == SiteKind.CAMP) {
                    // A camp is a base, not a nation: the riders who keep it owe no crown.
                    val band = coinSeed(SeedKind.WARBAND, culture.id, year, null, realm?.deityId)
                    reseat(band.copy(seatSiteId = newSite.id))
                    val chiefIdx = coinFigure(culture.id, year - (24 + rng.nextInt(16)), "war-chief", orgId = band.id)
                    rulers[band.id] = chiefIdx
                    rulerDeathAge[chiefIdx] = 40 + rng.nextInt(30)
                }
                if (kind == SiteKind.TOWN) {
                    coinStructure(newSite, StructureKind.TAVERN, year, culture.id)
                    coinStructure(newSite, StructureKind.MARKET, year, culture.id)
                }
                // The chronicle keeps the halls and holy ground; hamlets and camps
                // live in the tithe rolls without an entry apiece.
                if (kind == SiteKind.TOWN || kind == SiteKind.HOLDFAST ||
                    kind == SiteKind.SHRINE || kind == SiteKind.RUIN
                ) {
                    events += ChronicleEvent(
                        year,
                        if (kind == SiteKind.RUIN) EventKind.RUIN else EventKind.FOUNDING,
                        if (kind == SiteKind.RUIN) {
                            "${newSite.name} stands empty; no living people claims its stones."
                        } else {
                            "${realm?.name ?: "The ${culture.epithet}"} found ${newSite.name}, " +
                                "a ${kind.label}, in ${culture.homeland}."
                        }
                    )
                }
            }
        }

        /** How thickly folk stand around a place: neighbours within a few leagues. */
        fun crowdOf(site: Site): Int {
            val cx = (site.x / placeCell).toInt()
            val cy = (site.y / placeCell).toInt()
            var n = 0
            for (gx in cx - 1..cx + 1) {
                for (gy in cy - 1..cy + 1) {
                    val bucket = placeGrid[placeKey(gx, gy)] ?: continue
                    for (s in bucket) {
                        if (s.id == site.id || s.ruined) continue
                        val dx = site.x - s.x
                        val dy = site.y - s.y
                        // within 0.03 normalized — about two and a half leagues
                        if (dx * dx + dy * dy < 0.0009f) n++
                    }
                }
            }
            return n
        }

        /** The richness of the ground under a place: kind land and rivers feed crowds. */
        fun groundOf(site: Site): Float {
            val base = when (terrain.biomeAt(site.x, site.y)) {
                Biome.DOWNS, Biome.FOREST -> 1.0f
                Biome.HILLS -> 0.75f
                Biome.MOOR -> 0.6f
                Biome.MARSH -> 0.45f
                else -> 0.25f
            }
            return if (riverAt(site.x, site.y)) base + 0.15f else base
        }

        /** Villages swell into towns, towns into cities — but the ground decides where. */
        fun growthStep(year: Int) {
            for (i in sites.indices) {
                val target = sites[i]
                if (target.ruined || !target.isSettlement || target.population <= 0) continue
                // Restless towns grow half as fast: unrest strangles the market carts.
                val restless = (loyaltyNow[target.id] ?: 70) < 35
                // Crowded ground starves growth; kind, rivered ground feeds it.
                val rate = (1 + rng.nextInt(6)) / 100f *
                    groundOf(target) * (7f / (7f + crowdOf(target))) *
                    (if (restless) 0.5f else 1f)
                if (rate <= 0f) continue
                val grown = (target.population * rate).toInt().coerceAtLeast(1)
                val newPop = target.population + grown
                val newKind = when {
                    target.kind == SiteKind.VILLAGE && newPop >= 320 -> SiteKind.TOWN
                    target.kind == SiteKind.TOWN && newPop >= 2400 -> SiteKind.CITY
                    else -> target.kind
                }
                val updated = target.copy(population = newPop, kind = newKind)
                sites[i] = updated
                siteById[target.id] = updated
                val holderCulture = stewardCultureOf(target)
                if (newKind != target.kind) {
                    if (newKind == SiteKind.TOWN) {
                        if (structuresAt(target.id, StructureKind.MARKET).isEmpty()) {
                            coinStructure(updated, StructureKind.MARKET, year, holderCulture)
                        }
                        if (structuresAt(target.id, StructureKind.TEMPLE).isEmpty()) {
                            coinStructure(updated, StructureKind.TEMPLE, year, holderCulture)
                        }
                    } else if (newKind == SiteKind.CITY && structuresAt(target.id, StructureKind.GUILD).isEmpty()) {
                        charterGuild(updated, year, holderCulture)
                    }
                    events += ChronicleEvent(
                        year,
                        EventKind.GROWTH,
                        (if (newKind == SiteKind.TOWN) {
                            "${target.name} outgrows its palisade and is counted a town."
                        } else {
                            "${target.name} is chartered a city; its tolls are reckoned in ingots now."
                        }) + if (restless) " Unrest strangles the market." else ""
                    )
                } else if (
                    target.kind == SiteKind.VILLAGE && newPop >= 60 &&
                    structuresAt(target.id, StructureKind.TAVERN).isEmpty() && rng.nextInt(40) == 0
                ) {
                    coinStructure(updated, StructureKind.TAVERN, year, holderCulture)
                }
            }
        }

        /** A realm names its largest living settlement its high seat. */
        fun capitalStep(year: Int) {
            val realm = seeds
                .filter { it.seedKind == SeedKind.REALM && it.id !in extinctActors }
                .randomOrNull(rng) ?: return
            val seat = sites
                .filter { it.sovereignRealmId == realm.id && !it.ruined && it.isSettlement }
                .maxByOrNull { it.population } ?: return
            if (seat.kind == SiteKind.CAPITAL) return
            val exalted = seat.copy(kind = SiteKind.CAPITAL)
            replaceSite(exalted)
            reseat(realm.copy(seatSiteId = seat.id))
            if (structuresAt(seat.id, StructureKind.TEMPLE).isEmpty()) {
                coinStructure(exalted, StructureKind.TEMPLE, year, realm.cultureId)
            }
            events += ChronicleEvent(
                year,
                EventKind.GROWTH,
                "${seat.name} is named the high seat of ${realm.name}; the road tolls come here now."
            )
        }

        fun cultureStep(year: Int) {
            if (rng.nextBoolean()) {
                val culture = cultures.random(rng)
                events += ChronicleEvent(
                    year,
                    EventKind.MIGRATION,
                    "The ${culture.epithet} abandon ${culture.homeland} and carry their ${culture.craft} east."
                )
            } else {
                val culture = cultures.random(rng)
                val prophetIdx = coinFigure(culture.id, year - 30 - rng.nextInt(20), "prophet")
                val actor = seeds.random(rng)
                events += ChronicleEvent(
                    year,
                    EventKind.PROPHECY,
                    "${figures[prophetIdx].name} of ${culture.name} declares ${actor.creed}; ${1 + rng.nextInt(4)} shrines burned."
                )
            }
        }

        fun minorStep(year: Int) {
            val culture = cultures.random(rng)
            val shrine = coinSite(SiteKind.SHRINE, culture.id, null, "raised in year $year", year)
            events += ChronicleEvent(
                year,
                EventKind.FOUNDING,
                "${shrine.name} is raised over a ${CRAFTS.random(rng)} kiln."
            )
        }

        fun artifactStep(year: Int) {
            val patron = seeds
                .filter { it.id !in extinctActors && it.seatSiteId != null }
                .randomOrNull(rng) ?: return
            val seat = siteById[patron.seatSiteId ?: return] ?: return
            val culture = cultures[patron.cultureId]
            val makerIdx = coinFigure(patron.cultureId, year - (30 + rng.nextInt(25)), "smith of ${culture.craft}")
            val name = "The ${ADJECTIVES.random(rng)} ${NOUNS.random(rng)}"
            artifacts += Artifact(
                id = nextArtifactId++,
                name = name,
                kind = ARTIFACT_KINDS.random(rng),
                makerId = makerIdx,
                madeYear = year,
                keeperSiteId = seat.id,
                whereabouts = "kept at ${seat.name}"
            )
            events += ChronicleEvent(
                year,
                EventKind.ARTIFACT,
                "${figures[makerIdx].name} forges $name for ${patron.name}; it is kept at ${seat.name}."
            )
        }

        fun beastStep(year: Int) {
            val living = beasts.filter { it.alive }
            if (living.isEmpty() || (rng.nextInt(4) == 0 && beasts.size < 5)) {
                val lairs = sites.filter { it.kind == SiteKind.RUIN || it.kind == SiteKind.BARROW }
                val lair = lairs.randomOrNull(rng) ?: return
                val name = phonetics.getValue(cultures.random(rng).id).word(rng, 2)
                val kind = BEAST_KINDS.random(rng)
                beasts += Beast(
                    id = nextBeastId++,
                    name = name,
                    kind = kind,
                    lairSiteId = lair.id,
                    wokeYear = year,
                    slainYear = null,
                    slayerId = null,
                    raids = 0
                )
                events += ChronicleEvent(
                    year,
                    EventKind.BEAST,
                    "$name the $kind wakes beneath ${lair.name}. The first tithe-taker to see it does not come back."
                )
            } else {
                val beast = living.random(rng)
                val lair = siteById[beast.lairSiteId] ?: return
                val near = sites.filter { it.id != lair.id && it.population > 0 }
                    .minByOrNull { dist(lair.x, lair.y, it.x, it.y) } ?: return
                val taken = 3 + rng.nextInt(30)
                val idx = beasts.indexOfFirst { it.id == beast.id }
                beasts[idx] = beast.copy(raids = beast.raids + 1)
                events += ChronicleEvent(
                    year,
                    EventKind.BEAST,
                    "${beast.name} carries off $taken souls from ${near.name}. ${beast.raids + 1} raids in all."
                )
                // Fear walks with the beast.
                loyaltyNow[near.id] = ((loyaltyNow[near.id] ?: 70) - (3 + rng.nextInt(7))).coerceIn(0, 100)
                if (beast.raids + 1 >= 2 && rng.nextInt(10) < 4) {
                    val culture = cultures.random(rng)
                    val heroIdx = coinFigure(culture.id, year - (24 + rng.nextInt(16)), "monster-slayer")
                    beasts[idx] = beasts[idx].copy(slainYear = year, slayerId = heroIdx)
                    figures[heroIdx] = figures[heroIdx].copy(feats = listOf("slew ${beast.name} in ${lair.name} in yr $year"))
                    events += ChronicleEvent(
                        year + 1,
                        EventKind.BEAST,
                        "${figures[heroIdx].name} the monster-slayer cuts ${beast.name} down above ${lair.name}. The province breathes."
                    )
                }
            }
        }

        /** The slow currents of mood: courts wed, rebels rise, garrisons steady, feuds smolder. */
        fun politicsStep(year: Int) {
            // The ledgers of the covetous never quite close.
            for (actor in seeds) {
                if (actor.id in extinctActors) continue
                if ("grow rich" in (agendasNow[actor.id] ?: emptyList())) {
                    wealthNow[actor.id] = (wealthNow[actor.id] ?: 0) + 2
                }
            }
            // Courts wed and beget: dynasties grow their rivals at home. Guilds
            // and bands keep no dynasties — the houses belong to the realms.
            for (realm in seeds.toList()) {
                if (realm.seedKind != SeedKind.REALM || realm.id in extinctActors) continue
                val rIdx = rulers[realm.id] ?: continue
                val ruler = figures[rIdx]
                if (ruler.diedYear != null) continue
                val age = year - ruler.bornYear
                if (ruler.spouseId == null && age in 20..55 && rng.nextInt(12) == 0) {
                    val spouseIdx = coinFigure(realm.cultureId, year - (18 + rng.nextInt(10)), "consort", realmId = realm.id)
                    figures[spouseIdx] = figures[spouseIdx].copy(spouseId = rIdx)
                    figures[rIdx] = ruler.copy(spouseId = spouseIdx)
                    seedBond(rIdx, spouseIdx, rival = false)
                    // A wedding promises a bloodline; the first child follows within the year.
                    val kidIdx = coinFigure(
                        realm.cultureId, year, "child of ${realm.name}", realmId = realm.id, parentIdx = rIdx, houseId = ruler.houseId
                    )
                    childOf.getOrPut(rIdx) { mutableListOf() }.add(kidIdx)
                    events += ChronicleEvent(
                        year,
                        EventKind.MARRIAGE,
                        "${ruler.name} the ${ruler.title} weds ${figures[spouseIdx].name}; " +
                            "the halls exchange gifts and hostages."
                    )
                }
                val wed = figures[rIdx].spouseId?.let { sid -> figures.getOrNull(sid)?.diedYear == null } == true
                if (wed && (childOf[rIdx]?.size ?: 0) < 4 && age < 55 && rng.nextInt(8) == 0) {
                    val kidIdx = coinFigure(
                        realm.cultureId, year, "child of ${realm.name}", realmId = realm.id, parentIdx = rIdx, houseId = ruler.houseId
                    )
                    childOf.getOrPut(rIdx) { mutableListOf() }.add(kidIdx)
                }
            }
            // Restless steads breed rebels long before any gate falls: the movement
            // raids, loyalty drains, and only a collapsed town actually rises.
            for (site in sites) {
                if (site.ruined || !site.isSettlement) continue
                val holderId = site.sovereignRealmId ?: continue
                if (holderId in extinctActors) continue
                val loyalty = loyaltyNow[site.id] ?: 70
                if (loyalty !in 25..49 || rng.nextInt(12) != 0) continue
                val holder = seedById[holderId] ?: continue
                val cultureId = stewardCultureOf(site)
                val rebels = seeds.firstOrNull {
                    it.seedKind == SeedKind.REBELS && it.id !in extinctActors && it.cultureId == cultureId
                } ?: coinSeed(SeedKind.REBELS, cultureId, year, holderId, holder.deityId)
                rebellionTallies.getOrPut(rebels.id to holderId) { Skirmish() }.mark(year)
                loyaltyNow[site.id] = ((loyaltyNow[site.id] ?: 70) - (2 + rng.nextInt(9))).coerceIn(0, 100)
                stabilityNow[site.id] = ((stabilityNow[site.id] ?: 70) - (1 + rng.nextInt(4))).coerceIn(0, 100)
                events += ChronicleEvent(
                    year,
                    EventKind.REBELLION,
                    "Rebels of ${rebels.name} burn the tithe barns at ${site.name}; ${holder.name}'s writ thins."
                )
                break
            }
            // When loyalty and order collapse, a town raises its own banner — and
            // the rising that takes the gates crowns its lord the same year.
            for (site in sites) {
                if (site.ruined || !site.isSettlement) continue
                val holderId = site.sovereignRealmId ?: continue
                if (holderId in extinctActors) continue
                if ((loyaltyNow[site.id] ?: 100) >= 20 || (stabilityNow[site.id] ?: 100) >= 35) continue
                if (rng.nextInt(6) != 0) continue
                val holder = seedById[holderId] ?: continue
                val cultureId = stewardCultureOf(site)
                val rebels = coinSeed(SeedKind.REBELS, cultureId, year, holderId, holder.deityId)
                val leaderIdx = coinFigure(cultureId, year - (30 + rng.nextInt(20)), "free-lord", orgId = rebels.id)
                rulers[rebels.id] = leaderIdx
                rulerDeathAge[leaderIdx] = 46 + rng.nextInt(36)
                // The rising dissolves into the crown it raised.
                extinctActors += rebels.id
                val realmType = if (rng.nextInt(3) == 0) RealmType.THEOCRACY else RealmType.CITY_STATE
                val freeHold = coinSeed(SeedKind.REALM, cultureId, year, holderId, holder.deityId, realmType)
                    .copy(founding = FoundingOrigin.REBELLION, seatSiteId = site.id)
                reseat(freeHold)
                crownedYear[freeHold.id] = year
                figures[leaderIdx] = figures[leaderIdx].copy(title = titleOf(freeHold), realmId = freeHold.id, orgId = null)
                rulers[freeHold.id] = leaderIdx
                replaceSite(site.copy(sovereignRealmId = freeHold.id, sackedCount = site.sackedCount + 1))
                loyaltyNow[site.id] = 55 + rng.nextInt(20)
                garrisonNow[site.id] = 10
                conflictDrafts += ConflictDraft(
                    nextConflictId++, ConflictKind.REBELLION, rebels.id, holderId,
                    "the levies and the grain tithes", year, site.id
                ).apply {
                    endYear = year
                    outcome = "${site.name} crowned its own lord"
                }
                events += ChronicleEvent(
                    year,
                    EventKind.REBELLION,
                    "${site.name} rises against ${holder.name}; " +
                        "${figures[leaderIdx].name} the ${titleOf(freeHold)} keeps its gates now."
                )
                // Neighbours of the same people may follow the banner.
                for (near in sites) {
                    if (near.id == site.id || near.ruined || !near.isSettlement) continue
                    if (near.sovereignRealmId != holderId || stewardCultureOf(near) != freeHold.cultureId) continue
                    if (rng.nextInt(3) != 0) continue
                    replaceSite(near.copy(sovereignRealmId = freeHold.id))
                    loyaltyNow[near.id] = 55 + rng.nextInt(20)
                    events += ChronicleEvent(
                        year,
                        EventKind.REBELLION,
                        "${near.name} declares for the rising; ${holder.name}'s wardens walk out by night."
                    )
                }
                // The broken holder remembers the theft.
                pressClaim(
                    site.id, holderId, rulers[holderId]?.takeIf { figures[it].diedYear == null },
                    50 + rng.nextInt(40), "seized by rebels in year $year", year
                )
                break
            }
            // Settled successions leave grudges: the passed-over press their rights.
            while (pendingContests.isNotEmpty()) {
                val (contestedPower, rival, strength) = pendingContests.removeAt(0)
                if (figures[rival].diedYear != null) continue
                val power = seedById[contestedPower] ?: continue
                val seatId = power.seatSiteId
                    ?: sites.firstOrNull { it.sovereignRealmId == contestedPower && !it.ruined }?.id
                    ?: continue
                var splinterId: Int? = null
                if (strength >= 60 && rng.nextInt(3) == 0) {
                    val defected = sites
                        .filter {
                            it.sovereignRealmId == contestedPower && !it.ruined &&
                                it.id != seatId && it.isSettlement
                        }
                        .maxByOrNull { it.population }
                    if (defected != null) {
                        // Rival blood with a settlement behind it founds a realm of its own.
                        val splinter = coinSeed(
                            SeedKind.REALM, power.cultureId, year, contestedPower, power.deityId,
                            power.realmType
                        ).copy(founding = FoundingOrigin.SUCCESSION_SPLINTER, seatSiteId = defected.id)
                        reseat(splinter)
                        replaceSite(defected.copy(sovereignRealmId = splinter.id))
                        crownedYear[splinter.id] = year
                        figures[rival] = figures[rival].copy(title = titleOf(splinter), realmId = splinter.id)
                        rulers[splinter.id] = rival
                        rulerDeathAge[rival] = 46 + rng.nextInt(36)
                        conflictDrafts += ConflictDraft(
                            nextConflictId++, ConflictKind.REALM_WAR, splinter.id, contestedPower,
                            "the succession of ${power.name}", year, seatId
                        )
                        adjustGrudge(splinter.id, contestedPower, -70)
                        val blood = figures[rival].houseId
                            ?.let { hid -> houses.firstOrNull { it.id == hid }?.name }
                        events += ChronicleEvent(
                            year,
                            EventKind.WAR,
                            "${figures[rival].name}${blood?.let { " of $it" } ?: ""} raises ${splinter.name} " +
                                "against ${power.name} over the succession; ${defected.name} declares for the rival blood."
                        )
                        splinterId = splinter.id
                        // The rival blood and the seated line are now feuding houses.
                        val rivalHouseId = figures[rival].houseId
                        val seatedHouseId = rulers[contestedPower]?.let { figures.getOrNull(it)?.houseId }
                        if (rivalHouseId != null && seatedHouseId != null && rivalHouseId != seatedHouseId) {
                            linkFeud(rivalHouseId, seatedHouseId, year)
                        }
                    }
                }
                pressClaim(seatId, splinterId, rival, strength, "passed over in the succession", year)
            }
            // Agendas are paid for: protective realms garrison their most restive stead.
            for (realm in seeds) {
                if (realm.seedKind != SeedKind.REALM || realm.id in extinctActors) continue
                val goals = agendasNow[realm.id] ?: continue
                if ("suppress unrest" !in goals && "protect the capital" !in goals) continue
                val restive = sites
                    .filter { it.sovereignRealmId == realm.id && !it.ruined && it.isSettlement }
                    .minByOrNull { loyaltyNow[it.id] ?: 100 } ?: continue
                if ((wealthNow[realm.id] ?: 0) >= 120) {
                    wealthNow[realm.id] = (wealthNow[realm.id] ?: 0) - 30
                    garrisonNow[restive.id] = ((garrisonNow[restive.id] ?: 0) + 6).coerceAtMost(60)
                    loyaltyNow[restive.id] = ((loyaltyNow[restive.id] ?: 70) + 3).coerceIn(0, 100)
                }
            }
            // Garrisons steady the streets; stability drifts toward loyalty.
            for (site in sites) {
                if (site.ruined) continue
                if ((garrisonNow[site.id] ?: 0) >= 15) {
                    loyaltyNow[site.id] = ((loyaltyNow[site.id] ?: 70) + 1).coerceIn(0, 100)
                }
                val l = loyaltyNow[site.id] ?: 70
                val s = stabilityNow[site.id] ?: 70
                stabilityNow[site.id] = if (s < l) s + 1 else if (s > l) s - 1 else s
            }
            // Agendas are re-read when the wind changes.
            if (rng.nextInt(10) == 0) {
                val actor = seeds.filter { it.id !in extinctActors }.randomOrNull(rng) ?: return
                agendasNow[actor.id] = AGENDAS.shuffled(rng).take(1 + rng.nextInt(3))
            }
        }

        // --- Year 1: the founding peoples take their ground. ---
        cultures.forEachIndexed { index, culture ->
            // A people founds a realm — never a guild, never by accident. The
            // first people raises the great seat; the rest found as their own
            // culture and country suggest.
            val realmType = if (index == 0) {
                RealmType.KINGDOM
            } else {
                when (rng.nextInt(10)) {
                    in 0..3 -> RealmType.KINGDOM
                    in 4..6 -> RealmType.CHIEFDOM
                    in 7..8 -> RealmType.TRIBAL_REALM
                    else -> RealmType.REPUBLIC
                }
            }
            val realm = coinSeed(
                SeedKind.REALM, culture.id, 1 + rng.nextInt(20), null,
                patronDeity[culture.id], realmType
            )
            val seat = coinSite(
                when (index) {
                    0 -> SiteKind.CAPITAL
                    // Each people keeps at least one market town; the rest are holds.
                    1 -> SiteKind.TOWN
                    else -> if (rng.nextInt(3) == 0) SiteKind.TOWN else SiteKind.HOLDFAST
                },
                culture.id,
                realm.id,
                "seat of the ${culture.epithet}"
            )
            reseat(realm.copy(seatSiteId = seat.id))
            coinRuler(realm, realm.foundedYear)
            crownedYear[realm.id] = realm.foundedYear
            val house = coinHouse(culture.id, realm.foundedYear, realm.id)
            val founderIdx = rulers[realm.id]
            if (founderIdx != null) {
                figures[founderIdx] = figures[founderIdx].copy(houseId = house.id)
                houses[houses.indexOfFirst { it.id == house.id }] = house.copy(headFigureId = founderIdx)
            }
            events += ChronicleEvent(
                house.foundedYear,
                EventKind.FOUNDING,
                "${house.name} takes its name in ${culture.homeland}; its blood will press its rights."
            )
            val god = deity(realm.deityId)
            events += ChronicleEvent(
                realm.foundedYear,
                EventKind.FOUNDING,
                "The ${culture.epithet} raise ${seat.name}" +
                    (if (index == 0) ", the great seat of the province" else "") +
                    " in ${culture.homeland}" +
                    (god?.let { ", and ${it.name} ${it.epithet} keeps the hearth." } ?: ".")
            )
            // Each people spreads into its homeland: the villages come first.
            val hamlets = (0 until 1 + rng.nextInt(2)).map {
                coinSite(
                    SiteKind.VILLAGE, culture.id, realm.id,
                    "tilled since the first years", 1 + rng.nextInt(30)
                )
            }
            if (hamlets.isNotEmpty()) {
                events += ChronicleEvent(
                    realm.foundedYear,
                    EventKind.FOUNDING,
                    "The ${culture.epithet} spread from ${seat.name} into ${hamlets.joinToString(" and ") { it.name }}."
                )
            }
            // The great seat keeps a temple, a market and a pour house from early on.
            if (index == 0) {
                coinStructure(seat, StructureKind.TEMPLE, realm.foundedYear, culture.id)
                coinStructure(seat, StructureKind.MARKET, realm.foundedYear, culture.id)
                coinStructure(seat, StructureKind.TAVERN, realm.foundedYear, culture.id)
            } else if (seat.kind == SiteKind.TOWN) {
                coinStructure(seat, StructureKind.TAVERN, realm.foundedYear, culture.id)
                coinStructure(seat, StructureKind.MARKET, realm.foundedYear, culture.id)
            }
        }

        // Old ruins no living people claims; beasts den in them later.
        repeat(2 + rng.nextInt(2)) {
            val c = cultures.random(rng)
            val ruin = coinSite(SiteKind.RUIN, c.id, null, "older than the tithe rolls", 1)
            events += ChronicleEvent(1, EventKind.RUIN, "${ruin.name} stands empty; no living people claims its stones.")
        }

        // --- The drowned figure whose vault the player will wake inside. ---
        val vaultCulture = cultures[rng.nextInt(cultures.size)]
        val floodYear = (totalYears * 0.55f).toInt() + rng.nextInt(30)
        val drownedIdx = coinFigure(vaultCulture.id, floodYear - 40 - rng.nextInt(20), "delver")
        val keeperCulture = cultures[(vaultCulture.id + 1) % cultures.size]
        // The keepers of the drowned are a cult — an organization that tends the
        // dead's ground, never a sovereign of it.
        val keepers = coinSeed(SeedKind.CULT, keeperCulture.id, floodYear - 60, null, patronDeity[keeperCulture.id])
        coinRuler(keepers, floodYear - 60)
        val barrow = coinSite(SiteKind.BARROW, keeperCulture.id, null, "sealed after the drowning")
        val vault = coinSite(
            SiteKind.VAULT,
            vaultCulture.id,
            null,
            "beneath ${barrow.name}",
            forcedName = "Third Vault of ${figures[drownedIdx].name}"
        )
        reseat(keepers.copy(seatSiteId = barrow.id))
        val keeperPresence = listOf(barrow.id, vault.id)

        // --- Simulate the centuries. ---
        var year = 20
        while (year < totalYears) {
            year += 2 + rng.nextInt(5)
            if (year >= totalYears) break

            if (year in (floodYear - 2)..(floodYear + 2)) continue

            structureStep(year)
            politicsStep(year)
            // The living tide: folk multiply and new steads are founded every pass.
            growthStep(year)
            foundingStep(year)

            when (rng.nextInt(100)) {
                in 0..19 -> warStep(year)
                in 20..21 -> raidStep(year)
                in 22..23 -> mortalityStep(year)
                in 24..29 -> schismStep(year)
                in 30..34 -> hardshipStep(year)
                in 35..38 -> artifactStep(year)
                in 39..43 -> beastStep(year)
                in 44..47 -> mortalityStep(year)
                in 48..49 -> cultureStep(year)
                in 50..53 -> capitalStep(year)
                in 54..55 -> rivalryStep(year)
                in 56..57 -> feudStep(year)
                else -> minorStep(year)
            }
        }

        // --- The drowning: the event that sealed the vault. ---
        val floodDead = 900 + rng.nextInt(2600)
        // The drowning takes a real settlement — the low-lying one, always.
        val floodVictim = sites
            .filter { it.isSettlement && !it.ruined && it.foundedYear < floodYear }
            .minByOrNull { terrain.heightAt(it.x, it.y) }
        if (floodVictim != null) {
            val victimDead = floodVictim.population
            // The flood takes the halls; the catacombs keep, drowned and sealed.
            structuresBySite[floodVictim.id]?.let { halls ->
                for (j in halls.indices) {
                    val st = halls[j]
                    if (!st.ruined && st.kind != StructureKind.CATACOMB) {
                        halls[j] = st.copy(ruined = true)
                    }
                }
            }
            replaceSite(
                floodVictim.copy(population = 0, ruined = true, note = "gone under in the drowning")
            )
            ruinedSiteIds += floodVictim.id
            events.list += ChronicleEvent(
                floodYear,
                EventKind.DESTRUCTION,
                "${floodVictim.name} goes under the flood. ${formatCount(maxOf(victimDead, 50))} souls; the bell tolls beneath the reeds."
            )
        }
        // The founding wound is written no matter how full the chronicle is.
        events.list += ChronicleEvent(
            floodYear,
            EventKind.FLOOD,
            "The Drowning of ${figures[drownedIdx].name}. ${formatCount(floodDead)} dead. ${keepers.name} seal the ${vault.name}."
        )
        events.list += ChronicleEvent(
            floodYear + 1,
            EventKind.SEALING,
            "${barrow.name} is closed with brass nails and left unnamed on the tithe rolls."
        )

        // The relic the player can actually find, and one the flood took for good.
        val smithIdx = coinFigure(keepers.cultureId, floodYear - 58, "keeper-smith", orgId = keepers.id)
        val drownedName = figures[drownedIdx].name
        artifacts += Artifact(
            id = nextArtifactId++,
            name = "The Grave-Nail of $drownedName",
            kind = "sealed relic",
            makerId = smithIdx,
            madeYear = floodYear - 28,
            keeperSiteId = vault.id,
            whereabouts = "sealed in the ${vault.name}"
        )
        events.list += ChronicleEvent(
            floodYear - 28,
            EventKind.ARTIFACT,
            "${figures[smithIdx].name} forges The Grave-Nail of $drownedName; ${keepers.name} swear it stays with the dead."
        )
        val founderIdx = coinFigure(cultures[0].id, floodYear - 80, "bell-founder")
        artifacts += Artifact(
            id = nextArtifactId++,
            name = "The ${ADJECTIVES.random(rng)} Bell",
            kind = "bell",
            makerId = founderIdx,
            madeYear = floodYear - 60,
            keeperSiteId = null,
            whereabouts = "sank with the drowned quarter in yr $floodYear"
        )

        figures[drownedIdx] = figures[drownedIdx].copy(diedYear = floodYear, deathCause = "the drowning")
        events.list.sortBy { it.year }

        // Every province keeps one great living seat; if history burned them all,
        // the last great settlement is named the capital.
        if (sites.none { it.kind == SiteKind.CAPITAL && !it.ruined && it.population > 0 }) {
            sites.filter { !it.ruined && it.isSettlement && it.population > 0 }
                .maxByOrNull { it.population }
                ?.let { seat -> replaceSite(seat.copy(kind = SiteKind.CAPITAL)) }
        }
        // And at least one market town: if every town burned, rotted or drowned,
        // the largest living stead is counted a town, with its market and pour house.
        fun ensureMarketTown() {
            if (sites.any { it.kind == SiteKind.TOWN && !it.ruined && it.population > 0 }) return
            sites.filter {
                !it.ruined && it.isSettlement && it.population > 0 &&
                    it.kind != SiteKind.CAPITAL && it.kind != SiteKind.CITY
            }.maxByOrNull { it.population }?.let { stead ->
                val promoted = stead.copy(kind = SiteKind.TOWN)
                replaceSite(promoted)
                if (structuresAt(stead.id, StructureKind.MARKET).isEmpty()) {
                    coinStructure(promoted, StructureKind.MARKET, totalYears, stewardCultureOf(promoted))
                }
                if (structuresAt(stead.id, StructureKind.TAVERN).isEmpty()) {
                    coinStructure(promoted, StructureKind.TAVERN, totalYears, stewardCultureOf(promoted))
                }
            }
        }
        ensureMarketTown()

        // And one chartered city: if growth never chartered one, the largest
        // town that grew past the city rolls is counted a city at the end,
        // with a guild hall and the guild that keeps the rolls.
        if (sites.none { it.kind == SiteKind.CITY && !it.ruined && it.population > 0 }) {
            val candidates = sites.filter { !it.ruined && it.kind == SiteKind.TOWN && it.population > 0 }
            (candidates.filter { it.population >= 2000 }.ifEmpty { candidates })
                .maxByOrNull { it.population }
                ?.let { town ->
                    val chartered = town.copy(kind = SiteKind.CITY)
                    replaceSite(chartered)
                    if (structuresAt(town.id, StructureKind.GUILD).isEmpty()) {
                        charterGuild(chartered, totalYears, stewardCultureOf(chartered))
                    }
                }
        }
        ensureMarketTown()

        // ------------------------------------------------------------------
        // The final order: what each actor ended history as, and what it is
        // called. Types and names are read from history, never rolled.
        // ------------------------------------------------------------------
        fun styledRealmName(type: RealmType, root: String, seat: Site?): String = when (type) {
            RealmType.KINGDOM -> "Kingdom of $root"
            RealmType.REPUBLIC -> "Republic of $root"
            RealmType.CITY_STATE -> "Free City of ${seat?.name ?: root}"
            RealmType.THEOCRACY -> "Hierocracy of $root"
            RealmType.CONFEDERATION -> "Concord of $root"
            RealmType.CHIEFDOM -> "Chiefdom of $root"
            RealmType.TRIBAL_REALM -> "Tribes of $root"
            RealmType.EMPIRE -> "Empire of $root"
        }

        val finalRealms = mutableListOf<Realm>()
        val finalOrganizations = mutableListOf<Organization>()
        val finalGroups = mutableListOf<NonStateGroup>()
        val finalGovernments = mutableListOf<Government>()
        var nextGovernmentId = 0
        val finalRealmById = HashMap<Int, Realm>()

        fun relationsOf(actorId: Int): Map<Int, Int> = grudge.entries
            .filter { it.key.first == actorId || it.key.second == actorId }
            .associate { if (it.key.first == actorId) it.key.second to it.value else it.key.first to it.value }

        for (seed in seeds.filter { it.seedKind == SeedKind.REALM }) {
            val held = sites.filter { it.sovereignRealmId == seed.id && !it.ruined }
            val settlements = held.filter { it.isSettlement }
            val capital = settlements.maxByOrNull { it.population } ?: held.firstOrNull()
            val population = held.sumOf { it.population }
            // The realm's shape is not rolled: history decides. Great holdings
            // make empires, a single chartered city makes a free city, thin
            // holdings demote a young kingdom to a chiefdom.
            var type = seed.realmType ?: RealmType.KINGDOM
            if (settlements.size >= 20 && population >= 120_000) {
                type = RealmType.EMPIRE
            } else if (settlements.size <= 1 && capital != null &&
                (capital.kind == SiteKind.CITY || capital.kind == SiteKind.CAPITAL)
            ) {
                type = RealmType.CITY_STATE
            } else if (settlements.size <= 2 &&
                (type == RealmType.KINGDOM || type == RealmType.REPUBLIC)
            ) {
                type = RealmType.CHIEFDOM
            }
            val legitimacy = when (seed.founding) {
                FoundingOrigin.ANCIENT_SEAT, FoundingOrigin.CHARTER -> 62 + rng.nextInt(20)
                FoundingOrigin.TEMPLE_SEAT -> 72 + rng.nextInt(20)
                FoundingOrigin.SUCCESSION_SPLINTER -> 42 + rng.nextInt(20)
                FoundingOrigin.REBELLION -> 28 + rng.nextInt(20)
            } + (battlesWon[seed.id] ?: 0) * 2
            val rulerIdx = rulers[seed.id]
            val form = when (type) {
                RealmType.KINGDOM -> GovernmentForm.HEREDITARY_CROWN
                RealmType.EMPIRE -> GovernmentForm.IMPERIAL_THRONE
                RealmType.REPUBLIC -> GovernmentForm.ELECTED_COUNCIL
                RealmType.CITY_STATE ->
                    if (seed.founding == FoundingOrigin.REBELLION) GovernmentForm.WARLORD_HOLD
                    else GovernmentForm.MERCHANT_COUNCIL
                RealmType.THEOCRACY -> GovernmentForm.THEOCRATIC_SEAT
                RealmType.CONFEDERATION -> GovernmentForm.HIGH_ASSEMBLY
                RealmType.CHIEFDOM, RealmType.TRIBAL_REALM -> GovernmentForm.ELDERS_ASSEMBLY
            }
            val government = Government(
                id = nextGovernmentId++,
                realmId = seed.id,
                form = form,
                rulerFigureId = rulerIdx,
                councilHouseIds = houses.filter { it.patronRealmId == seed.id && !it.extinct }.take(3).map { it.id },
                seatedYear = crownedYear[seed.id] ?: seed.foundedYear
            )
            finalGovernments += government
            val realm = Realm(
                id = seed.id,
                name = styledRealmName(type, seed.name, capital),
                type = type,
                cultureId = seed.cultureId,
                foundedYear = seed.foundedYear,
                founding = seed.founding,
                splinterFromId = seed.splinterFromId,
                creed = seed.creed,
                deityId = seed.deityId,
                capitalSiteId = capital?.id,
                rulerFigureId = rulerIdx,
                governmentId = government.id,
                legitimacy = legitimacy.coerceIn(0, 100),
                population = population,
                militaryStrength = (strengthNow[seed.id] ?: 40) + (battlesWon[seed.id] ?: 0) * 8,
                wealth = (wealthNow[seed.id] ?: 0) + population / 3,
                relations = relationsOf(seed.id),
                goals = agendasNow[seed.id] ?: emptyList(),
                hostileByNature = false,
                extinct = held.isEmpty() || seed.id in extinctActors
            )
            finalRealms += realm
            finalRealmById[seed.id] = realm
        }

        for (seed in seeds.filter {
                it.seedKind == SeedKind.GUILD || it.seedKind == SeedKind.ORDER || it.seedKind == SeedKind.CULT
            }) {
            val kind = when (seed.seedKind) {
                SeedKind.GUILD -> OrganizationKind.GUILD
                SeedKind.ORDER -> OrganizationKind.ORDER
                else -> OrganizationKind.CULT
            }
            finalOrganizations += Organization(
                id = seed.id,
                name = seed.name,
                kind = kind,
                cultureId = seed.cultureId,
                foundedYear = seed.foundedYear,
                splinterFromId = seed.splinterFromId,
                creed = seed.creed,
                deityId = seed.deityId,
                headquartersSiteId = seed.seatSiteId,
                presenceSiteIds = if (seed.id == keepers.id) keeperPresence else emptyList(),
                leaderFigureId = rulers[seed.id],
                membershipCount = 20 + (wealthNow[seed.id] ?: 0) / 3,
                treasury = wealthNow[seed.id] ?: 0,
                relations = relationsOf(seed.id),
                goals = agendasNow[seed.id] ?: emptyList(),
                hostileByNature = kind == OrganizationKind.CULT,
                extinct = seed.id in extinctActors
            )
        }

        for (seed in seeds.filter { it.seedKind == SeedKind.WARBAND || it.seedKind == SeedKind.REBELS }) {
            val kind = if (seed.seedKind == SeedKind.WARBAND) GroupKind.WARBAND else GroupKind.REBELS
            finalGroups += NonStateGroup(
                id = seed.id,
                name = seed.name,
                kind = kind,
                cultureId = seed.cultureId,
                foundedYear = seed.foundedYear,
                splinterFromId = seed.splinterFromId,
                creed = seed.creed,
                deityId = seed.deityId,
                baseSiteId = seed.seatSiteId,
                leaderFigureId = rulers[seed.id],
                membershipCount = seed.seatSiteId?.let { siteById[it]?.population } ?: 0,
                wealth = wealthNow[seed.id] ?: 0,
                relations = relationsOf(seed.id),
                goals = agendasNow[seed.id] ?: emptyList(),
                hostileByNature = true,
                extinct = seed.id in extinctActors
            )
        }

        // Conflicts: wars and risings from their drafts, the small hostilities
        // from their tallies. Each kind keeps its own shape and its own end.
        val conflicts = conflictDrafts.filter { it.endYear != null }.map { draft ->
            Conflict(
                id = draft.id,
                kind = draft.kind,
                attackerId = draft.attackerId,
                defenderId = draft.defenderId,
                cause = draft.cause,
                startYear = draft.startYear,
                endYear = draft.endYear,
                battles = draft.battles.toList(),
                outcome = draft.outcome
            )
        } + rebellionTallies.entries.map { (pair, rising) ->
            Conflict(
                id = nextConflictId++,
                kind = ConflictKind.REBELLION,
                attackerId = pair.first,
                defenderId = pair.second,
                cause = "the levies and the grain tithes",
                startYear = rising.firstYear,
                endYear = rising.lastYear,
                battles = emptyList(),
                outcome = "${rising.count} years of rebellion in the barns and on the roads"
            )
        } + raidTallies.entries.map { (pair, raid) ->
            Conflict(
                id = nextConflictId++,
                kind = ConflictKind.RAID,
                attackerId = pair.first,
                defenderId = pair.second,
                cause = "the rich roads nearby",
                startYear = raid.firstYear,
                endYear = raid.lastYear,
                battles = emptyList(),
                outcome = "${raid.count} raids; the watch never caught them"
            )
        } + rivalryTallies.entries.map { (pair, spat) ->
            Conflict(
                id = nextConflictId++,
                kind = ConflictKind.GUILD_CONFLICT,
                attackerId = pair.first,
                defenderId = pair.second,
                cause = "trade and pride",
                startYear = spat.firstYear,
                endYear = spat.lastYear,
                battles = emptyList(),
                outcome = "${spat.count} seasons of open rivalry"
            )
        } + feudTallies.entries.map { (pair, feud) ->
            Conflict(
                id = nextConflictId++,
                kind = ConflictKind.HOUSE_FEUD,
                attackerId = pair.first,
                defenderId = pair.second,
                cause = "an old insult between the bloodlines",
                startYear = feud.firstYear,
                endYear = feud.lastYear,
                battles = emptyList(),
                outcome = "the feud still smolders"
            )
        }

        /** Who really holds sway in a settlement, in shares of a hundred. */
        fun influencesFor(site: Site): List<Influence> {
            val entries = mutableListOf<Influence>()
            finalRealmById[site.sovereignRealmId]?.takeIf { !it.extinct }?.let { realm ->
                entries += Influence(InfluenceKind.REALM, realm.id, realm.name, 30 + (garrisonNow[site.id] ?: 0) / 3)
            }
            structuresBySite[site.id]?.firstOrNull { it.kind == StructureKind.GUILD && !it.ruined }?.let { hall ->
                val guild = hall.organizationId?.let { gid -> seedById[gid] }
                entries += Influence(
                    InfluenceKind.GUILD,
                    guild?.id,
                    guild?.name ?: hall.name,
                    16 + (guild?.let { (wealthNow[it.id] ?: 0) / 30 } ?: 0)
                )
            }
            structuresBySite[site.id]?.firstOrNull { it.kind == StructureKind.TEMPLE && !it.ruined }?.let { temple ->
                entries += Influence(InfluenceKind.TEMPLE, null, temple.name, 12 + (if (livingKeeper(temple)) 8 else 0))
            }
            houses.firstOrNull { it.patronRealmId == site.sovereignRealmId && it.headFigureId != null }
                ?.let { house ->
                    entries += Influence(InfluenceKind.HOUSE, house.id, house.name, 10)
                }
            val garrison = garrisonNow[site.id] ?: 0
            if (garrison > 10) {
                entries += Influence(InfluenceKind.GARRISON, site.sovereignRealmId, "the garrison", garrison / 4)
            }
            entries += Influence(
                InfluenceKind.TOWNSFOLK, null, "the townsfolk",
                10 + (100 - (loyaltyNow[site.id] ?: 70)) / 5
            )
            val total = entries.sumOf { it.share }.coerceAtLeast(1)
            return entries.map { it.copy(share = it.share * 100 / total) }
                .sortedByDescending { it.share }
        }

        val ruinCount = ruinedSiteIds.size + 18 + rng.nextInt(60)

        // The sky writes history too: omens from the world's own heavens, on their own
        // rng so this pass can never disturb the rest of the province's rolls.
        val omens = generateOmens(
            rng = Random(seed * 6971L + 100003L),
            moons = SkyGen.moonsFor(seed),
            phonetics = phonetics.values.toList(),
            cultures = cultures,
            totalYears = totalYears
        )
        val allEvents = (events.list + omens).sortedBy { it.year }
        val rumors = generateRumors(
            rng, allEvents, sites,
            finalRealms + finalOrganizations + finalGroups,
            vault, beasts, artifacts, conflicts
        )

        return World(
            seed = seed,
            seedCode = seedCode(seed, cultures[0].name),
            provinceName = province,
            ages = ages,
            cultures = cultures,
            realms = finalRealms,
            organizations = finalOrganizations,
            groups = finalGroups,
            governments = finalGovernments,
            figures = figures,
            deities = deities,
            events = allEvents,
            sites = sites.map {
                it.copy(
                    structures = structuresBySite[it.id].orEmpty(),
                    stability = stabilityNow[it.id] ?: 70,
                    loyalty = loyaltyNow[it.id] ?: 70,
                    garrison = garrisonNow[it.id] ?: 0,
                    influences = influencesFor(it)
                )
            },
            conflicts = conflicts,
            artifacts = artifacts,
            beasts = beasts,
            houses = houses.toList(),
            claims = claims.toList(),
            terrain = terrain,
            rumors = rumors,
            currentYear = totalYears,
            vaultSiteId = vault.id,
            barrowSiteId = barrow.id,
            ruinCount = ruinCount
        )
    }
}
