package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.Biome
import com.rork.hollowmarch.world.ChronicleEvent
import com.rork.hollowmarch.world.Actor
import com.rork.hollowmarch.world.Rumor
import com.rork.hollowmarch.world.Site
import com.rork.hollowmarch.world.SiteKind
import com.rork.hollowmarch.world.StructureKind
import com.rork.hollowmarch.world.WORLD_LEAGUES
import com.rork.hollowmarch.world.World
import com.rork.hollowmarch.world.isSettlement
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class LogLine(val text: String, var age: Float = 0f)

/** How many resolved exchanges the verification ledger keeps. */
private const val TRAIL_LENGTH = 24

/** What the nearest watcher has made of you, for the HUD's eye. */
enum class DetectionState { NONE, HIDDEN, SEARCHING, SEEN }

/** What the USE hand did: stole a pocket, took a thing up, opened spoils, or used the way. */
enum class Interact { NONE, PICKED, PICKPOCKET, LOOT, PORTAL, TRAVELER, STATION }

/** Word of a deed still on the road: it lands at a settlement on a later day. */
private data class Word(val arrivalDay: Int, val siteId: Int, val deed: Deed)

/** One entry of the Bearings sheet: a place, its bearing, and the walk it asks. */
data class Bearing(
    val site: Site,
    val compass: String,
    val leagues: Float,
    val hours: Float,
    val visited: Boolean,
    val source: String,
    /** The living folk of a settlement, or -1 for places that keep none. */
    val folk: Int = -1,
    /** The stage the place stands at — a camp, a town, a capital. */
    val stage: String = "",
    /** The trade roads a place you have walked keeps, and what crosses them. */
    val roads: String = ""
)

/** Rain bends the pace of a walk: how the speed folds, and what each stride costs. */
internal fun rainPacing(rain: Float): Pair<Float, Float> =
    if (rain <= 0.25f) 1f to 1f else (1f - 0.25f * rain) to (1f + rain)

/** What an hour of rest pays: thin under the rain, honest under cover. */
internal fun restRecovery(wet: Boolean): Pair<Int, Int> = if (wet) 4 to 5 else 6 to 7

/** The chronicler's word for a moon's phase. */
internal fun moonPhaseWord(illum: Float, waxing: Boolean): String {
    val shape = when {
        illum < 0.12f -> "new"
        illum < 0.45f -> "crescent"
        illum < 0.62f -> "half"
        illum < 0.9f -> "gibbous"
        else -> "full"
    }
    return when {
        shape == "new" || shape == "full" -> shape
        waxing -> "waxing $shape"
        else -> "waning $shape"
    }
}

/** How the road's news comes to you, graded by the regard of the ground you stand on. */
internal enum class TravelerTone { RICH, PLAIN, GRUDGING }

/** The tone travelers take, set by the regard of the place you stand in. */
internal fun travelerTone(regard: Int): TravelerTone = when {
    regard >= 45 -> TravelerTone.RICH
    regard <= -25 -> TravelerTone.GRUDGING
    else -> TravelerTone.PLAIN
}

/**
 * The simulation behind the viewport: movement, torchlight, tap-to-strike melee,
 * enemy behaviour, the passage of days and the province's memory of what you did.
 */
class GameEngine(val world: World, startSlot: SaveSlot?, creation: DelverCreation? = null) {

    var map: GameMap
        private set
    val camera: Camera

    /** The province itself, built once from the seed and kept for the session. */
    private val overlandLazy = lazy { OverlandGen.build(world) }
    val overland: GameMap get() = overlandLazy.value

    /** The floor you stand on: 0 is the surface, 1 and deeper are the buried floors. */
    var depth: Int = 0
        private set
    var outdoor: Boolean = false
        private set

    /** True while you walk the open province itself, between all places. */
    var onOverland: Boolean = false
        private set

    var maxVitality: Int = Derived.maxVitality(StatBlock.balanced())
        private set
    var maxFatigue: Int = Derived.maxFatigue(StatBlock.balanced())
        private set
    var maxMagicka: Int = Derived.maxMagicka(StatBlock.balanced())
        private set
    var vitality: Int = maxVitality
    var fatigue: Int = maxFatigue
    var magicka: Int = maxMagicka
    var torch: Float = 1f
    var kills: Int = 0
    var brass: Int = 0
    var minutes: Float = 0f
    var currentSiteId: Int
    var wardTimer: Float = 0f

    // ---- magic: the formula's own ledger, grown by casting and crafting
    var knownMagic: KnownMagic = KnownMagic()
    var schoolProficiencies: MagicProficiencies = MagicProficiencies.fresh()
    val spellRegistry: SpellRegistry = SpellRegistry()
    val playerEffects = mutableListOf<ActiveMagicEffect>()
    private var spellsForged: Int = 0
    var atSpellcraftingStation: Boolean = false
        private set
    var dead: Boolean = false
    var crouched: Boolean = false
        private set

    /** A raised guard behind the shield: transient combat state, entered by a tap, never saved. */
    var blocking: Boolean = false
        private set
    /** The raise and lower of the board in first person, 0..1, eased each frame. */
    var shieldRaise: Float = 0f
        private set

    val reputation: Reputation = Reputation.fromSave(world, startSlot?.reputation)

    /** The living folk of every settlement, turned year by year and kept in the save. */
    val settlements: SettlementLedger = SettlementLedger.fromEncoded(startSlot?.settlements, world)

    /** The years' turns as they pass: growth, hardship, and the graves you left. */
    val chronicle: MutableList<ChronicleEvent> = mutableListOf()
    val roster = ClassRoster(world)
    val styleRoster = StyleRoster(world)
    val geography = MaterialGeography(world)
    val stats: StatBlock
    val klass: ActorClass?
    val growth: Growth
    /** The delver's hand with each family of arm: a second ledger beside Melee. */
    val proficiencies: Proficiencies
    /** The particular pieces this hand has come to know, by their smith's marks. */
    val masteries: Masteries
    val inventory: Inventory
    val equipment: Equipment
    val groundItems: MutableList<GroundItem> = mutableListOf()
    val deeds: MutableList<String> = mutableListOf()

    /**
     * The province's memory: the authoritative record of what has happened to
     * the generated world. Woken from the save, or clean for a fresh
     * expedition. A save written before stable ids hands its name-keyed memory
     * in as legacy keys, which are migrated scene by scene as places are built.
     */
    val worldState: WorldState = WorldState.decode(
        startSlot?.worldState,
        world.seed,
        startSlot?.looted?.split('\u001E')?.filter { it.isNotBlank() } ?: emptyList()
    )

    /** Places you have stood — kept by the world, not by the engine. */
    val visitedSites: MutableSet<Int> get() = worldState.discovered
    val log: MutableList<LogLine> = mutableListOf()
    /** The last blows resolved, attacker's arithmetic to armor's verdict: for verification, not display. */
    val combatTrail = ArrayDeque<CombatResolution>()
    val rumors: MutableList<Rumor> = mutableListOf()

    // The held input lives behind the presentation boundary: the UI hands the
    // engine a whole immutable PlayerInput, and nothing outside writes here.
    var moveInput: Float = 0f
        private set
    var strafeInput: Float = 0f
        private set
    var turnInput: Float = 0f
        private set
    var lookInput: Float = 0f
        private set

    /** Take up the hands' command: every channel is replaced whole. */
    fun acceptInput(input: PlayerInput) {
        moveInput = input.move
        strafeInput = input.strafe
        turnInput = input.turn
        lookInput = input.look
    }

    var hurtFlash: Float = 0f
        private set
    var strikeArc: Float = 1f
        private set
    var swingPhase: Float = 0f
        private set
    private var swingTimer: Float = 0f
    var strikeCooldown: Float = 0f

    /** What the worn constants currently add to the self: exact reverts on unequip. */
    private val constantBonus = mutableMapOf<Attr, Int>()
    /** The low-resource weaves' clock and latches: crossing-based, never looping. */
    private var lowEnchantClock: Float = 0f
    private val lowLatched = mutableSetOf<String>()

    // ------------------------------------------------------------------ systems
    // The engine coordinates; the systems do the work. The marksmanship hand,
    // the shots in the air, the foes' wits, and the exchange of blows each
    // live in their own class now.
    val rangedPhase: RangedPhaseKind get() = ranged.phase
    val drawFraction: Float get() = ranged.drawFraction
    val muzzleFlash: Float get() = ranged.muzzleFlash
    val projectiles: MutableList<Projectile> get() = shots.projectiles
    val heldRanged: Item? get() = ranged.heldRanged()
    fun loadedShots(weapon: Item): Int = ranged.loadedShots(weapon)

    private var footstep: Float = 0f
    private val rng = Random(world.seed * 7919 + 17)

    // The four hands of the work, each with its own charge — all sharing this rng.
    val combat = CombatSystem(this)
    val ranged = RangedCombatSystem(this, rng)
    val shots = ProjectileSystem(this, rng)
    val npc = NpcSystem(this, rng)
    val settlement = SettlementSystem(this, rng)
    val interaction = InteractionSystem(this, rng)

    /** The province's magical ledger: its mages, works, tomes and scrolls, forged from the seed. */
    val magicLedger: MagicLedger = MagicHistory.forge(world)

    /**
     * The deep history layer beneath the years: famine sends walkers, walkers
     * change the places they reach, and thin places are left. Orchestration
     * only lives here; the layer's own arithmetic lives in [HistoricalSimulation].
     */
    val history: HistoricalSimulation = HistoricalSimulation.fromSave(world, startSlot?.history)

    /** The world's own heavens, rolled once from the seed. */
    val sky: Sky by lazy { SkyGen.build(world) }
    /** Animation clock in seconds: twinkling stars, drifting weather. */
    var animTime: Float = 0f
        private set

    // Declared before init: the first wake records a delve, and these must exist.
    private var lootedBurials = 0
    private val pendingWord = mutableListOf<Word>()
    private var ledgerYear: Int = 0

    /** The yard you left when you stepped through a door: restored when you step out. */
    private var surfaceMap: GameMap? = null

    /** The building you stand in, when you stand in one. */
    var inBuilding: BuildingFootprint? = null
        private set

    init {
        // Word you gathered rides the save; word from the forge rises again from the seed.
        rumors += startSlot?.rumors?.takeIf { it.isNotBlank() }
            ?.split('\u001E')?.mapNotNull { decodeRumor(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: world.rumors

        stats = StatBlock.fromEncoded(startSlot?.stats)
            ?: creation?.stats?.takeIf { it.isNotEmpty() }?.let { StatBlock(it) }
            ?: StatBlock.balanced()
        klass = roster.byKey(creation?.classKey?.takeIf { it.isNotBlank() } ?: startSlot?.classKey)
        growth = Growth.fromEncoded(startSlot?.growth, klass)
        // The smith's ledger continues where the save left it, before any piece is made.
        ItemUids.restore(startSlot?.nextUid ?: 1)
        proficiencies = Proficiencies.fromEncoded(startSlot?.proficiencies, klass)
        masteries = Masteries.fromEncoded(startSlot?.masteries)
        // The magical ledger rides the save; a new life wakes knowing the hedge-craft basics.
        if (startSlot?.magic?.isNotBlank() == true) {
            knownMagic = MagicSave.decodeKnown(startSlot.magic)
            schoolProficiencies = MagicSave.decodeProf(startSlot.magic)
            MagicSave.decodeSpells(startSlot.magic).forEach { spellRegistry.register(it) }
        } else {
            STARTER_MAGIC.forEach { knownMagic.learnComponent(it) }
        }
        // The province's assembled lore stands in every registry, ready to be
        // taken into a book; a grimoire spell casts like any other.
        SpellGrimoire.registerInto(spellRegistry)
        // The spells history created are ordinary formulas: registered like the lore.
        magicLedger.registerSpells(spellRegistry)
        // The delver's own marks ride the save: spent scrolls, studied tomes,
        // works lifted from their resting places.
        magicLedger.applyDelta(startSlot?.magicLedger)
        recomputePools()
        // A save from before items wakes with the starter kit; an itemed save keeps its satchel.
        inventory = startSlot?.inventory?.takeIf { it.isNotBlank() }
            ?.let { Inventory.fromEncoded(it) }
            ?: Inventory.starterKit()
        equipment = Equipment.fromEncoded(startSlot?.equipment)
        // An outfit that never rode the save (old expeditions, fresh wakes) takes up
        // its best blade once, then dresses as it pleases.
        if (equipment.isEmpty()) {
            inventory.all.filter { it.archetype.isWeapon }.maxByOrNull { it.damage() }?.let { weapon ->
                inventory.remove(weapon)
                equipment.equip(weapon)
                if (startSlot != null) {
                    pushLog("You take up the ${styleRoster.nameFor(weapon)} once more.")
                }
            }
        }
        // The worn constants are laid on the waking self, from the pieces it bears.
        refreshConstantEnchantments()

        // A testing choice on the forge sheet lets a new delver wake behind any
        // door in the province; otherwise the vault receives them as always.
        val askedSite = creation?.startSiteId?.takeIf { startSlot == null }?.let { id ->
            world.siteOrNull(id)
        }
        val startSite = world.site(
            startSlot?.siteId?.takeIf { it >= 0 } ?: askedSite?.id ?: world.vaultSiteId
        )
        currentSiteId = startSite.id
        onOverland = startSlot?.onOverland ?: true
        outdoor = if (onOverland) true else (startSlot?.outdoor ?: true)
        minutes = startSlot?.minutes ?: (40 * 1440f + 8 * 60f)
        // The simulation starts from the moment you wake: a fresh expedition owes
        // the world no absence, and a save resumes from the clock it stored.
        if (worldState.lastSimMinutes <= 0f) worldState.lastSimMinutes = minutes
        // An old save's depth was a dungeon level; it now names the floor you stood on.
        val savedDepth = startSlot?.depth ?: 1
        depth = if (onOverland || outdoor) {
            0
        } else if (savedDepth >= SiteGen.BUILDING_FLOOR_BASE) {
            savedDepth
        } else {
            savedDepth.coerceIn(1, SiteGen.floorCount(world, startSite).coerceAtLeast(1))
        }
        map = if (onOverland) {
            overland
        } else if (depth >= SiteGen.BUILDING_FLOOR_BASE) {
            // wake where you lay: the yard outside, then the room you rented
            val yard = sceneFor(startSite, 0)
            surfaceMap = yard
            val room = yard.buildings.getOrNull(depth - SiteGen.BUILDING_FLOOR_BASE)
            if (room != null) {
                inBuilding = room
                interiorFor(startSite, depth - SiteGen.BUILDING_FLOOR_BASE, room)
            } else {
                depth = 0
                outdoor = true
                yard
            }
        } else {
            sceneFor(startSite, depth)
        }
        camera = Camera(map.spawnX, map.spawnY, map.spawnAngle)

        // A fresh expedition wakes at the bottom of the sealed vault, deep in the
        // dark; every site after this one is entered by its door, at the entrance.
        if (startSlot == null) wakeDeepIn(startSite)

        startSlot?.let { slot ->
            // Old saves kept their discoveries in their own field; the world keeps them now.
            slot.visited.split('\u001F').mapNotNull { it.toIntOrNull() }
                .forEach { worldState.discover(it, day, minutes, world.siteOrNull(it)?.name ?: "") }
            worldState.discover(startSite.id, day, minutes, startSite.name)
            camera.x = slot.x.coerceIn(1.2f, map.width - 1.2f)
            camera.y = slot.y.coerceIn(1.2f, map.height - 1.2f)
            camera.angle = slot.angle
            vitality = slot.vitality.coerceIn(1, maxVitality)
            fatigue = slot.fatigue.coerceIn(0, maxFatigue)
            magicka = slot.magicka.coerceIn(0, maxMagicka)
            torch = slot.torch.coerceIn(0f, 1f)
            kills = slot.kills
            brass = slot.brass
            deeds += slot.deeds
            groundItems += slot.dropped.split('\u001E').mapNotNull { GroundItem.fromEncoded(it) }
                .map {
                    it.copy(
                        x = it.x.coerceIn(1.2f, map.width - 1.2f),
                        y = it.y.coerceIn(1.2f, map.height - 1.2f)
                    )
                }
            if (map.isWall(camera.x, camera.y)) {
                camera.x = map.spawnX
                camera.y = map.spawnY
            }
        }
        // A save written before stable ids hands in name-keyed memory; the scene
        // it wakes in is migrated to ids, then put back as the world remembers it.
        if (!onOverland) rebindCurrentScene()
        ledgerYear = settlements.currentYear

        if (startSlot == null) {
            pushLog("You wake at the bottom of ${siteName()}, deep in the dark.")
            pushLog("The brass nails that sealed this place lie far above. Only the climb out remains.")
            deeds += "Stood within ${siteName()}"
            recordDelving(startSite)
            SiteGen.bossFor(world, startSite).line?.let { pushLog(it) }
        } else if (onOverland) {
            pushLog("You take up the road again under the open sky.")
        } else {
            pushLog("You take up the torch again in ${map.title}.")
        }
    }

    /** A fresh expedition's waking: the lowest floor of the sealed vault, deep in the dark. */
    private fun wakeDeepIn(site: Site) {
        val floors = SiteGen.floorCount(world, site).coerceAtLeast(1)
        currentSiteId = site.id
        onOverland = false
        inBuilding = null
        depth = floors
        outdoor = false
        surfaceMap = null
        map = sceneFor(site, floors)
        camera.x = map.spawnX
        camera.y = map.spawnY
        camera.angle = map.spawnAngle
        torch = (torch + 0.25f).coerceAtMost(1f)
        worldState.discover(site.id, day, minutes, site.name)
    }

    // ------------------------------------------------- runtime reconstruction

    /**
     * One face of one place, reconstructed from base world + persistent state.
     *
     * Generation is deterministic in the seed, the site and the floor; the day
     * and folk it is also given are frozen on first visit by the world's own
     * stamp, so re-entering a place rebuilds the same place rather than rolling
     * a fresh, unrelated one. What the world remembers is then applied over it.
     */
    private fun sceneFor(site: Site, floor: Int): GameMap {
        val stamp = worldState.sceneStamp(
            EntityKey.scene(site.id, floor), site, day, folkOf(site.id)
        )
        val map = SiteGen.map(
            world, site, floor, roster, geography, stamp.day,
            worldState.slainBeastIds(), folk = stamp.folk
        )
        bindScene(map, site.id, floor)
        return map
    }

    /** A building's inside, on the same frozen stamp as the yard it stands in. */
    private fun interiorFor(site: Site, index: Int, room: BuildingFootprint): GameMap {
        val floor = SiteGen.BUILDING_FLOOR_BASE + index
        val stamp = worldState.sceneStamp(
            EntityKey.scene(site.id, floor), site, day, folkOf(site.id)
        )
        val map = SiteGen.buildingInterior(
            world, site, index, room, SiteGen.cultureId(world, site), stamp.day, geography
        )
        bindScene(map, site.id, floor)
        return map
    }

    /**
     * Bind a freshly built scene to the world: every persistent thing takes its
     * stable id, a pre-id save's memory is folded in, the souls are noted so
     * they outlive the scene, and the world's memory is applied over the top.
     */
    private fun bindScene(map: GameMap, siteId: Int, floor: Int) {
        SceneBinder.stamp(world.seed, siteId, floor, map.entities)
        if (worldState.hasLegacy()) worldState.migrateScene(siteId, floor, map.entities)
        SceneBinder.remember(worldState, world, siteId, floor, map)
        SceneBinder.restore(worldState, siteId, floor, map)
        placeMagicInScene(map, siteId, floor)
    }

    /**
     * History hides its works in the world: a scroll or tome whose resting
     * place is here slips into a container that has not been emptied. The same
     * seed sets the same things in the same chests; what the delver has taken
     * never rises again.
     */
    private fun placeMagicInScene(map: GameMap, siteId: Int, floor: Int) {
        val items = magicLedger.itemsForSite(siteId)
        if (items.isEmpty()) return
        val containers = map.entities.filter {
            it.container && !worldState.isEmptied(it.persistId) && it.loot.size < 3
        }
        if (containers.isEmpty()) return
        val rng = Random(world.seed * 3131L + 97L * siteId + 31L * floor)
        items.forEach { item ->
            val container = containers[rng.nextInt(containers.size)]
            container.loot += item
        }
    }

    /** Re-bind the scene already loaded: used when a save wakes standing in one. */
    private fun rebindCurrentScene() {
        bindScene(map, currentSiteId, depth)
    }

    fun siteName(): String = world.siteOrNull(currentSiteId)?.name ?: world.site(world.vaultSiteId).name

    /** The culture whose hands made this place's dead: its steward's people, or none. */
    private fun siteCultureId(siteId: Int): Int = world.cultureIdOf(siteId)

    /** Pools follow the attributes and the skills beneath them; called again when points are spent. */
    private fun recomputePools() {
        maxVitality = Derived.maxVitality(stats, growth)
        maxFatigue = Derived.maxFatigue(stats, growth)
        maxMagicka = Derived.maxMagicka(stats, growth)
    }

    /** Deeds feed the skill they exercise; a filled bar raises the skill, and the skill hardens its attribute. */
    internal fun learnSkill(skill: Skill, amount: Float) {
        val before = growth.level
        val rise = growth.feed(skill, stats, amount) ?: return
        recomputePools()
        pushLog("Your ${skill.label.lowercase()} improves — ${skill.label} ${growth.value(skill)}.")
        rise.attrRise?.let { attr -> pushLog(growthLine(attr)) }
        if (growth.level > before) {
            pushLog("You have grown — level ${growth.level}.")
        }
    }

    private fun growthLine(attr: Attr): String = when (attr) {
        Attr.MIGHT -> "Your blows land heavier. Might rises."
        Attr.VIGOR -> "The dark has made you harder to kill. Vigor rises."
        Attr.FINESSE -> "Your hands know the work. Finesse rises."
        Attr.SWIFTNESS -> "The road has quickened you. Swiftness rises."
        Attr.WISDOM -> "You see more in the same torchlight. Wisdom rises."
        Attr.INTELLECT -> "The old shapes come easier now. Intellect rises."
        Attr.PRESENCE -> "Doors open a little sooner for you. Presence rises."
        Attr.FORTUNE -> "Fate has begun to notice you. Fortune rises."
    }

    val day: Int get() = (minutes / 1440f).toInt() + 1

    /** The chronicle year you walk in: the world's present year, rolled forward by the days. */
    val gameYear: Int get() = world.currentYear + (day - 1) / 365
    val hour: Int get() = ((minutes % 1440f) / 60f).toInt()
    val minute: Int get() = (minutes % 60f).toInt()

    /** Fraction of the day passed, 0..1; 0.5 is noon. */
    val timeOfDay: Float get() = (minutes % 1440f) / 1440f

    /** What the sky is doing right now: fronts rolled from the seed and the calendar. */
    val weather: WeatherState
        get() {
            val inMarsh = onOverland && world.terrain.biomeAt(
                camera.x / OverlandGen.SIZE, camera.y / OverlandGen.SIZE
            ) == Biome.MARSH
            return Weather.at(world.seed, day, timeOfDay, inMarsh)
        }

    /** The light under the open sky once cloud has taken its share. */
    val skyLight: Float get() = outdoorLight * (1f - 0.30f * weather.cloud)

    /** Daylight from 0 (deep night) to 1 (noon); the wilds are lit by this alone. */
    val outdoorLight: Float
        get() {
            val t = (minutes % 1440f) / 1440f
            val curve = sin((t - 0.22f) * Math.PI.toFloat() * 2f)
            return ((curve + 0.55f) / 1.35f).coerceIn(0.08f, 1f)
        }

    val timeOfDayLabel: String
        get() = when (hour) {
            in 5..7 -> "dawn"
            in 8..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..19 -> "dusk"
            in 20..22 -> "night"
            else -> "small hours"
        }

    val heading: String
        get() {
            val deg = ((Math.toDegrees(camera.angle.toDouble()) + 450) % 360).toFloat()
            return when {
                deg < 22.5 || deg >= 337.5 -> "N"
                deg < 67.5 -> "NE"
                deg < 112.5 -> "E"
                deg < 157.5 -> "SE"
                deg < 202.5 -> "S"
                deg < 247.5 -> "SW"
                deg < 292.5 -> "W"
                else -> "NW"
            }
        }

    fun nearestTown(): Site =
        world.sites.filter { it.kind == SiteKind.TOWN }.minByOrNull { it.id } ?: world.sites.first()

    // ------------------------------------------------------------------ loop

    /** The half-second clock that stamps the lesser places as you come near them. */
    private var stampClock = 0f

    fun update(dt: Float) {
        if (dead) return
        val step = dt.coerceAtMost(0.05f)
        animTime += step
        // Twenty real minutes of daylight, fifteen of night — the same sun everywhere.
        minutes += clockStep(timeOfDay, step)
        strikeCooldown = (strikeCooldown - step).coerceAtLeast(0f)
        // The low-resource weaves are asked on the half-second, not every frame.
        lowEnchantClock += step
        if (lowEnchantClock >= EnchantRules.LOW_CHECK_INTERVAL) {
            lowEnchantClock = 0f
            evaluateLowEnchantments()
        }
        ranged.update(step)
        strikeArc = (strikeArc + step * 3.4f).coerceAtMost(1f)
        hurtFlash = (hurtFlash - step * 1.6f).coerceAtLeast(0f)
        wardTimer = (wardTimer - step).coerceAtLeast(0f)
        stepMagic(step)
        if (swingTimer > 0f) {
            swingTimer -= step
            swingPhase = (sin((1f - swingTimer / 0.28f) * Math.PI.toFloat())).coerceIn(0f, 1f)
        } else {
            swingPhase = 0f
        }

        updateMovement(step)
        // The wild keeps its own counsel: rolled by the league walked, from the seed.
        if (onOverland && walkedCells >= 20f) {
            walkedCells = 0f
            rollEncounter()
        }
        if (onOverland) {
            // the chronicler notes what the sky is doing, once a front has settled
            val w = weather
            if (timeOfDay in 0.25f..0.95f && w.kind != lastWeatherKind) {
                lastWeatherKind = w.kind
                pushLog(weatherLine(w.kind))
            }
            // the open country keeps the old clocks honest
            if (hour in 20..23 && nightWarnDay != day) {
                nightWarnDay = day
                pushLog("Night takes the open country. The road is no friend after dark.")
            }
            // the sky keeps its own annals, and you may be there when it writes
            if (day != skyEventDay && outdoorLight < 0.2f) {
                val cloud = weather.cloud
                if (SkyEvents.showerNight(world.seed, day) && cloud < 0.55f) {
                    skyEventDay = day
                    pushLog("The sky rains falling stars. You stand and watch until the cold drives you on.")
                } else if (auroraStrength() > 0.15f) {
                    skyEventDay = day
                    pushLog("Green light shivers over the northern sky, crowned in red.")
                }
            }
            // first sight of a landmark gets its own line, once
            map.portals.forEach { portal ->
                val siteId = portal.targetSiteId
                if (siteId >= 0 && siteId !in sighted &&
                    MapFactory.distance(camera.x, camera.y, portal.x, portal.y) < 26f
                ) {
                    sighted += siteId
                    pushLog("You catch sight of ${world.site(siteId).name} — ${portal.label}.")
                }
            }
            // the land fills in around you: lesser places are stamped as you come near
            stampClock += step
            if (stampClock >= 0.5f) {
                stampClock = 0f
                stampNearbyLandmarks()
            }
        }
        // The eye sinks when you crouch, and rises again when you stand.
        val targetEye = if (crouched) 0.30f else 0.5f
        camera.eye += (targetEye - camera.eye) * (step * 7f).coerceIn(0f, 1f)
        // The board rises when the guard is set, and lowers when it is not.
        shieldRaise += ((if (blocking) 1f else 0f) - shieldRaise) * (step * 9f).coerceIn(0f, 1f)
        updateTorch(step)
        // the chronicler's warnings: a torch guttering low, feet giving out
        if (holdsTorch && torch < 0.15f && torchWarnDay != day) {
            torchWarnDay = day
            pushLog("Your torch gutters low. It will not last the hour.")
        }
        if (fatigue <= 0 && fatigueWarnDay != day) {
            fatigueWarnDay = day
            pushLog("You are dead on your feet. Rest, or the road will have you.")
        }
        npc.update(step)
        shots.step(step)
        settlement.update(step)
        if (pendingWord.isNotEmpty()) deliverWord()
        if (gameYear != ledgerYear) stepFolkYears()
        log.forEach { it.age += step }
        if (log.size > 12) log.subList(0, log.size - 12).clear()
        val seenBefore = map.exploredFraction()
        map.markExplored(camera.x.toInt(), camera.y.toInt())
        val seenNow = map.exploredFraction()
        if (seenNow > seenBefore) {
            learnSkill(Skill.NAVIGATION, (seenNow - seenBefore) * 140f)
            learnSkill(Skill.AWARENESS, (seenNow - seenBefore) * 60f)
        }

        if (vitality <= 0) {
            vitality = 0
            dead = true
            pushLog("Your legs fold. The dark closes politely over you.")
        }
    }

    private fun updateMovement(dt: Float) {
        val tired = fatigue <= 0
        // The raised board rides the ordinary load, and a heavy guard slows the turn.
        val carriedShield = shield
        val burden = Derived.encumbranceFactor(
            inventory.weight() + (carriedShield?.weight() ?: 0f),
            Derived.carryCapacity(stats, growth)
        )
        val guardMove = if (carriedShield != null && blocking) Shields.moveFactor(carriedShield) else 1f
        val guardTurn = if (carriedShield != null && blocking) Shields.turnFactor(carriedShield) else 1f
        val ground = terrainPacing()
        val sky = weatherPacing()
        val speed = (if (tired) 1.4f else 2.5f) * (if (outdoor) 1.15f else 1f) *
            Derived.moveSpeed(stats, growth) * burden * (if (crouched) 0.5f else 1f) *
            guardMove * (ground?.first ?: 1f) * (sky?.first ?: 1f) *
            SpellCasting.moveFactor(playerEffects)
        camera.angle += turnInput * dt * 2.3f * guardTurn
        // Vertical look: the drag holds a pitch the renderer shears into the horizon.
        camera.pitch = (camera.pitch + lookInput * dt * 2.0f).coerceIn(-1.05f, 1.05f)

        val forward = moveInput * speed * dt
        val strafe = strafeInput * speed * 0.7f * dt
        if (abs(forward) > 0.0001f || abs(strafe) > 0.0001f) {
            val dx = camera.dirX * forward - camera.dirY * strafe
            val dy = camera.dirY * forward + camera.dirX * strafe
            val (nx, ny) = MapFactory.tryMove(map, camera.x, camera.y, dx, dy)
            camera.x = nx
            camera.y = ny
            footstep += dt * 9f
            camera.bob = sin(footstep) * 2.4f
            if (onOverland) walkedCells += sqrt(forward * forward + strafe * strafe)
            drainFatigue(dt * 0.7f * (ground?.second ?: 1f) * (sky?.second ?: 1f))
            if (burden < 1f) drainFatigue(dt * (1f - burden) * 3f)
            learnSkill(Skill.ATHLETICS, dt * 2f)
            if (tired) learnSkill(Skill.ENDURANCE, dt * 3f)
        } else {
            camera.bob *= 0.85f
            recoverFatigue(dt * 0.5f)
        }
    }

    /** What the ground asks of you: how fast you go, and how much it costs. */
    private fun terrainPacing(): Pair<Float, Float>? {
        if (!onOverland) return null
        val ford = map.floorAt(camera.x.toInt(), camera.y.toInt()) == Textures.FLOOR_FORD
        val biome = world.terrain.biomeAt(camera.x / map.width, camera.y / map.height)
        return OverlandGen.pacing(biome, ford)
    }

    /** The chronicler's name for the ground under your feet on the open road. */
    fun terrainLabel(): String {
        if (!onOverland) return ""
        val ford = map.floorAt(camera.x.toInt(), camera.y.toInt()) == Textures.FLOOR_FORD
        if (ford) return "the ford"
        return OverlandGen.biomeLabel(world.terrain.biomeAt(camera.x / map.width, camera.y / map.height))
    }

    /** What the sky asks of the walk: nothing under cover, rain's tax in the open. */
    fun weatherPacing(): Pair<Float, Float>? = if (!outdoor) null else rainPacing(weather.rain)

    /** The chronicler's note on what the sky will do to today's walk, for the bearings sheet. */
    fun weatherWalkNote(): String =
        if (outdoor && weather.rain > 0.25f) "Rain slows the road; the hours below ask more than the leagues admit."
        else ""

    /** The sky overhead in words: a weather word and the moon that is up, if any. */
    fun skyWord(): String {
        if (!outdoor) return ""
        val parts = mutableListOf(weather.kind.name.lowercase())
        moonWord()?.let { parts += it }
        return parts.joinToString(" \u00B7 ")
    }

    /** The first moon that is up, named as the chroniclers name it: "waxing bone moon". */
    fun moonWord(): String? {
        sky.moons.forEach { moon ->
            val mt = (timeOfDay + moon.offset) % 1f
            val el = sin(2f * Math.PI.toFloat() * (mt - 0.25f)) * 0.9f
            if (el < 0.05f) return@forEach
            val phase = 2f * Math.PI.toFloat() * (day + timeOfDay) / moon.periodDays + moon.phase0
            val illum = 0.5f + 0.5f * sin(phase)
            return "${moonPhaseWord(illum, cos(phase) > 0f)} ${moon.tintName} moon"
        }
        return null
    }

    private var fatigueFraction = 0f

    internal fun drainFatigue(amount: Float) {
        fatigueFraction += amount
        while (fatigueFraction >= 1f) {
            fatigueFraction -= 1f
            fatigue = (fatigue - 1).coerceAtLeast(0)
        }
    }

    private fun recoverFatigue(amount: Float) {
        fatigueFraction -= amount
        while (fatigueFraction <= -1f) {
            fatigueFraction += 1f
            fatigue = (fatigue + 1).coerceAtMost(maxFatigue)
        }
    }

    /** True while a torch is held in one of your hands; the satchel bundle stays dark. */
    val holdsTorch: Boolean
        get() = equipment.worn(Hand.RIGHT.slot)?.archetype == ItemArchetype.TORCH ||
            equipment.worn(Hand.LEFT.slot)?.archetype == ItemArchetype.TORCH

    /** The light you carry: only a torch in hand gives its circle against the dark. */
    val litTorch: Float get() = if (holdsTorch) torch else 0f

    /** The delver's weapon shown in hand: its own shape, tinted by its material. */
    val heldWeaponSpriteId: Int
        get() = equipment.bestWeapon()?.let { Sprites.forWeapon(it.archetype) } ?: -1
    val heldWeaponTint: Int
        get() = equipment.bestWeapon()?.material?.tint ?: 0xFFFFFF

    /** The board in the left hand, if the left hand carries one. */
    val shield: Item?
        get() = Shields.worn(equipment)

    /** The shield shown on the arm: its own shape, tinted by its material. */
    val heldShieldSpriteId: Int
        get() = shield?.let { Sprites.forShield(it.archetype) } ?: -1
    val heldShieldTint: Int
        get() = shield?.material?.tint ?: 0xFFFFFF

    private fun updateTorch(dt: Float) {
        // A torch only burns in your hand; the packed bundle does not spend itself.
        if (!holdsTorch) return
        if (outdoor) {
            torch = (torch - dt * 0.0015f * Derived.torchDrain(stats, growth)).coerceAtLeast(0f)
            return
        }
        torch = (torch - dt * 0.0042f * Derived.torchDrain(stats, growth)).coerceAtLeast(0f)
        if (torch < 0.18f && rng.nextFloat() < dt * 0.12f) {
            pushLog("The torch gutters; the walls step closer.")
        }
    }


    // ------------------------------------------------------------------ actions

    private var strikeHand: Hand = Hand.RIGHT

    /** Tap anywhere: you swing at whatever stands in front of you. */
    /** Tap anywhere: you swing at whatever stands in front of you. */
    fun strike() {
        if (dead) return
        ranged.heldRanged()?.let { ranged.tap(it); return }
        if (strikeCooldown > 0f) return
        // A weapon in each hand, the blows trade arms; one arm alone does all the work.
        val right = equipment.weaponIn(Hand.RIGHT)
        val left = if (equipment.leftClaimed) null else equipment.weaponIn(Hand.LEFT)
        val weapon = if (right != null && left != null) {
            if (strikeHand == Hand.RIGHT) right else left
        } else {
            right ?: left
        }
        strikeHand = if (strikeHand == Hand.RIGHT) Hand.LEFT else Hand.RIGHT
        // A scroll in the hand is spent by the strike: its formula resolves, a
        // use burns away, and no knowledge passes to the hand that held it.
        if (weapon != null && weapon.archetype == ItemArchetype.SCROLL) {
            castFromScroll(weapon)
            return
        }
        val category = weapon?.let { WeaponCategory.forWeapon(it.archetype) } ?: WeaponCategory.UNARMED
        val proficiency = proficiencies.value(category)
        val mastery = weapon?.let { masteries.value(it.uid) } ?: 0
        val shieldGuard = shield
        beginSwingArc(
            Derived.strikeCooldown(stats, growth, weapon, proficiency, mastery) *
                (if (shieldGuard != null && blocking) Shields.recoveryFactor(shieldGuard) else 1f)
        )
        drainFatigue(2f)

        val target = combat.pickMeleeTarget(camera, map.entities)
        if (target == null) {
            if (rng.nextInt(3) == 0) emit(GameEvent.Note("Your blade takes the wall. Sparks, then nothing."))
            return
        }
        if (fatigue <= 2 && rng.nextInt(2) == 0) {
            emit(GameEvent.Note("Your arms are lead; the swing goes wide."))
            return
        }

        // The hand asks first: Finesse, Melee, the family, the piece. Even the
        // surest blade finds air; a swing that misses still teaches a little.
        val sneak = crouched && target.detection == Detection.UNAWARE
        val harm = weapon?.archetype?.damageType ?: DamageType.BLUNT
        val attacker = combat.combatantForPlayer(weapon)
        val defender = combat.combatantFor(target)
        weaponPractice(weapon, 0.5f, 0.25f)
        val outcome = combat.resolveMelee(
            rng, attacker, defender, harm,
            hitChance = Derived.meleeAccuracy(stats, growth, proficiency, mastery),
            rawDamage = { Derived.strikeDamage(stats, growth, weapon, rng, proficiency, mastery) },
            critChance = Derived.critChance(stats),
            sneak = sneak,
            soakWhenBlocked = true
        )
        val weaponName = weapon?.let { styleRoster.nameFor(it) } ?: "bare hands"
        when (outcome.outcome) {
            StrikeOutcome.MISSED -> {
                emit(GameEvent.StrikeMissed(true, "you", target.name))
                recordCombat(combat.meleeResolution(attacker, defender, weaponName, category, proficiency, mastery, outcome))
                return
            }
            StrikeOutcome.DODGED -> {
                emit(GameEvent.StrikeDodged(true, "you", target.name))
                trainNpc(target, Skill.DEFENSE, 2f)
                recordCombat(combat.meleeResolution(attacker, defender, weaponName, category, proficiency, mastery, outcome))
                return
            }
            else -> {}
        }
        // Fortune's telling blow, and the unseen strike, teach their own arts —
        // but only a blow that arrives wakes the sleeper.
        if (outcome.critical) learnSkill(Skill.MELEE, 3f)
        if (outcome.sneak) {
            learnSkill(Skill.STEALTH, 5f)
            target.detection = Detection.AWARE
        }
        if (outcome.outcome == StrikeOutcome.BLOCKED) {
            if (outcome.attackerBlockedFatigue > 0f) drainFatigue(outcome.attackerBlockedFatigue)
            emit(GameEvent.StrikeBlocked(true, "you", target.name, outcome.guardName, outcome.blockZone, outcome.damage))
            if (outcome.damage <= 0) {
                recordCombat(combat.meleeResolution(attacker, defender, weaponName, category, proficiency, mastery, outcome))
                return
            }
        }
        recordCombat(
            combat.meleeResolution(attacker, defender, weaponName, category, proficiency, mastery, outcome, recordOutcome = StrikeOutcome.HIT)
        )
        target.hp -= outcome.damage
        target.hurtFlash = 1f
        // A landed blow wakes the arm's weave: charge paid, the one resolver strikes.
        weapon?.let {
            Enchanting.triggerEnchantments(
                this, it, EnchantmentActivation.ON_HIT, this, byPlayer = true, forcedTarget = target
            )
        }
        // The arm teaches its kind, and the piece teaches its keeper.
        weaponPractice(weapon, 2f)
        if (target.hp <= 0) {
            target.alive = false
            kills++
            brass += ((2 + rng.nextInt(6)) * Derived.lootFactor(stats)).roundToInt().coerceAtLeast(1)
            learnSkill(Skill.MELEE, 3f + target.level * 1.5f)
            weaponPractice(weapon, 3f, 2f)
            emit(GameEvent.EntityKilled(target.name, target.klass?.name, target.level, target.risesThisLife))
            recordKill(target)
            dropQuiver(target)
            // The kill confirmed: the piece's kill-weave answers its wielder.
            weapon?.let {
                Enchanting.triggerEnchantments(
                    this, it, EnchantmentActivation.WHEN_KILLING, this, byPlayer = true, forcedTarget = this
                )
            }
        } else {
            angerTheSettlement(target)
            emit(GameEvent.DamageDealt(true, "you", target.name, outcome.damage, sneak, outcome.critical))
            // A warding skin turns a share of harm back on the hand that dealt it.
            SpellCasting.reflectShare(target, outcome.damage)?.let { back ->
                vitality = (vitality - back).coerceAtLeast(0)
                emit(GameEvent.Note("The ${target.name}'s ward turns a share of the blow back. $back lost."))
            }
            // Taking a blow teaches armor and grit.
            trainNpc(target, Skill.DEFENSE, 1.5f)
            trainNpc(target, Skill.ENDURANCE, 1f)
        }
    }

    /** The arm teaches its kind, and the piece teaches its keeper: practice for both ledgers. */
    internal fun weaponPractice(weapon: Item?, profAmount: Float, masteryAmount: Float = profAmount) {
        val category = weapon?.let { WeaponCategory.forWeapon(it.archetype) } ?: WeaponCategory.UNARMED
        proficiencies.feed(category, profAmount)?.let { new ->
            pushLog("Your ${category.label.lowercase()} grow surer — ${category.label} $new.")
        }
        weapon?.takeIf { it.uid > 0 }?.let { piece ->
            masteries.feed(piece.uid, masteryAmount)?.let { new ->
                pushLog("The ${styleRoster.nameFor(piece)} grows familiar in your hand — mastery $new.")
            }
        }
    }

    /** Keep one resolved exchange in the verification ledger: newest last, the ledger trimmed. */
    internal fun recordCombat(resolution: CombatResolution) {
        combatTrail.addLast(resolution)
        while (combatTrail.size > TRAIL_LENGTH) combatTrail.removeFirst()
    }

    /** Elemental harm tells its own tale: the nature of the damage, not the spell. */
    private fun elementLine(element: String?, defender: String, amount: Int): String = when (element) {
        ElementalRules.FIRE -> "Flame washes over the $defender. $amount lost."
        ElementalRules.FROST -> "Frost bites deep into the $defender. $amount lost."
        ElementalRules.SHOCK -> "The spark strikes through the $defender. $amount lost."
        ElementalRules.POISON -> "The poison gnaws at the $defender. $amount lost."
        else -> "Harm finds the $defender. $amount lost."
    }

    private fun strikeFlavour(name: String, damage: Int): String = when (rng.nextInt(4)) {
        0 -> "Your slash opens the $name's shoulder. $damage."
        1 -> "You chop into the $name; it keens. $damage."
        2 -> "The blade bites deep. The $name staggers. $damage."
        else -> "A clean cut across the $name's ribs. $damage."
    }




    /** Where a body is struck by a shot in the air: the engine applies the harm. */
    internal fun applyProjectileImpact(p: Projectile, target: Entity?) {
        if (target != null) strikeEnemyWith(p, target) else strikePlayerWith(p)
    }

    /** The shared wind-up: cooldown set, the arc reset, the swing clock started. */
    internal fun beginSwingArc(cooldown: Float) {
        strikeCooldown = cooldown
        strikeArc = 0f
        swingTimer = 0.28f
    }

    /** The delver's own blood shows: the red edge on the screen's rim. */
    internal fun flashHurt() {
        hurtFlash = 0.9f
    }

    /** The systems speak in facts; the engine alone turns them into words. */
    fun emit(event: GameEvent) {
        translate(event).forEach { pushLog(it) }
    }

    private fun lessonsPhrase(lessons: Int): String =
        if (lessons > 0) " It dies wiser by $lessons lesson" + (if (lessons == 1) "." else "s.") else ""

    private fun translate(event: GameEvent): List<String> = when (event) {
        is GameEvent.Note -> listOf(event.text)
        is GameEvent.SpellCreated -> listOf("You bind the formula: ${event.name}.")
        is GameEvent.SpellLearned -> listOf("${event.name} joins your book.")
        is GameEvent.SpellForgotten -> listOf("${event.name} fades from your book.")
        is GameEvent.SpellCast ->
            if (event.byPlayer) listOf("You cast ${event.name}.")
            else listOf("The ${event.caster} casts ${event.name}.")
        is GameEvent.MagicComponentDiscovered -> listOf("You come to understand ${event.name}.")
        is GameEvent.MagicalProficiencyIncreased -> listOf("Your ${event.school} deepens.")
        is GameEvent.Healed -> listOf("A mend lands on ${event.target}: +${event.amount}.")
        is GameEvent.MagicStateApplied -> listOf("The ${event.state} takes hold of ${event.target}.")
        is GameEvent.MagicStateExpired -> listOf("The ${event.state} lets go of ${event.target}.")
        is GameEvent.AttributeModified -> listOf(
            "${event.attribute} ${if (event.amount >= 0) "lifts" else "wanes"} for ${event.target}" +
                (if (event.temporary) ", for a while." else ".")
        )
        is GameEvent.SkillModified -> listOf(
            "${event.skill} ${if (event.amount >= 0) "sharpens" else "dulls"} for ${event.target}" +
                (if (event.temporary) ", for a while." else ".")
        )
        is GameEvent.Summoned ->
            if (event.byPlayer) listOf("A summons answers: ${event.name} steps from the weave.")
            else listOf("The caller's summons answers: ${event.name} steps from the weave.")
        is GameEvent.SummonExpired -> listOf("The weave releases ${event.name}, and it is no more.")
        is GameEvent.ScrollUsed -> listOf(
            "The scroll speaks: ${event.spell}. " +
                (if (event.remaining > 0) {
                    "${event.remaining} use" + (if (event.remaining == 1) "" else "s") + " remain."
                } else {
                    "The vellum is spent."
                })
        )
        is GameEvent.ScrollSpent ->
            listOf("The last of the scroll's power leaves it, and it crumbles to ash.")
        is GameEvent.TomeStudied -> listOf(
            "You study \"${event.title}\": ${event.components} component" +
                (if (event.components == 1) "" else "s") + ", ${event.spells} spell" +
                (if (event.spells == 1) "" else "s") + " learned."
        )
        is GameEvent.EnchantmentFired ->
            if (event.byPlayer) {
                listOf("The ${event.item} answers — ${event.enchantment}.")
            } else {
                listOf("The ${event.item} answers its bearer — ${event.enchantment}.")
            }
        is GameEvent.EnchantmentSpent ->
            listOf("The last spark leaves the ${event.item}.")
        is GameEvent.EnchantmentDry ->
            listOf("The ${event.item}'s ${event.enchantment} has no charge left to pay.")
        is GameEvent.ItemRecharged ->
            listOf("The ${event.item} drinks your will — charge ${event.charge}/${event.max}.")
        is GameEvent.StrikeMissed -> listOf(
            if (event.byPlayer) "Your swing finds only air."
            else "The ${event.attacker}'s blow finds only air."
        )
        is GameEvent.StrikeDodged -> listOf(
            if (event.byPlayer) "The ${event.defender} slips your blow aside."
            else "You slip the ${event.attacker}'s blow aside."
        )
        is GameEvent.StrikeBlocked ->
            if (event.byPlayer) {
                listOf(
                    if (event.dealt <= 0) "The ${event.defender}'s ${event.guard} turns your blow aside."
                    else "Your blow grates along the ${event.defender}'s guard and lands anyway."
                )
            } else {
                listOf(
                    when {
                        event.dealt <= 0 ->
                            "The ${event.attacker}'s blow takes the ${event.guard} full on and is turned aside."
                        event.zone == BlockZone.EDGE ->
                            "The ${event.attacker}'s blow slides along the ${event.guard}'s edge. ${event.dealt} lost."
                        else ->
                            "The ${event.attacker}'s blow breaks on the ${event.guard}. ${event.dealt} lost."
                    }
                )
            }
        is GameEvent.DamageDealt ->
            if (event.element != null) {
                listOf(elementLine(event.element, event.defender, event.amount))
            } else if (event.byPlayer) {
                listOf(
                    if (event.sneak) "Your blade goes in where it isn't looking. ${event.amount}."
                    else strikeFlavour(event.defender, event.amount)
                ) + if (event.critical) listOf("A telling blow — Fortune was with you.") else emptyList()
            } else {
                listOf(
                    if (event.warded) "The ward takes most of the ${event.attacker}'s blow. ${event.amount} lost."
                    else "The ${event.attacker} lands a blow. ${event.amount} lost."
                )
            }
        is GameEvent.EntityKilled -> listOf(
            if (event.klassName != null) {
                "The ${event.defender} — ${event.klassName}, of level ${event.level} — comes apart and does not rise.${lessonsPhrase(event.lessons)}"
            } else {
                "The ${event.defender} comes apart and does not rise.${lessonsPhrase(event.lessons)}"
            }
        )
        is GameEvent.ProjectileFired -> listOf(
            if (event.gunpowder) "The ${event.weapon} speaks — a flower of smoke and thunder."
            else "The ${event.weapon} looses."
        ) + if (event.spent) {
            listOf(
                if (event.crossbow) "The string stands slack — foot in the stirrup, then."
                else "The pan is empty. The ${event.weapon} wants a fresh charge."
            )
        } else emptyList()
    }

    private fun strikeEnemyWith(p: Projectile, target: Entity) {
        // A shot that lands on a body falls at its feet: arrows and thrown steels are gathered again.
        if (p.recoverable) groundItems += GroundItem(p.x, p.y, p.ammoItem)
        var damage = ranged.projectileDamage(p)
        val harm = p.damageType
        val sneak = crouched && target.detection == Detection.UNAWARE
        if (sneak) {
            damage = (damage * Derived.sneakAttackMultiplier(null)).roundToInt()
            learnSkill(Skill.STEALTH, 4f)
            target.detection = Detection.AWARE
        }
        val hit = combat.resolveProjectileHit(
            harm, damage, sneak, stats[Attr.MIGHT], p.x, p.y, combat.combatantFor(target)
        )
        if (hit.blockZone != BlockZone.MISS && hit.damage <= 0) {
            emit(GameEvent.Note("Your ${p.label} takes the ${target.name}'s ${hit.guardName} full on and is turned aside."))
            recordCombat(
                CombatResolution(
                    "you", target.name, stats[Attr.FINESSE], growth.value(Skill.MARKSMANSHIP),
                    p.weaponName, p.category, p.proficiency, p.mastery,
                    ranged.rangedHitChance(p), 100, StrikeOutcome.BLOCKED,
                    (target.stats ?: StatBlock.balanced())[Attr.SWIFTNESS],
                    hit.bodyTemper, hit.bodyMaterial,
                    hit.dodgePenalty, 0, 0, 0, hit.soak,
                    hit.guardName, hit.blockZone.name, p.speedMps, p.massKg, p.marks, p.distance
                )
            )
            return
        }
        target.hp -= hit.damage
        target.hurtFlash = 1f
        learnSkill(Skill.MARKSMANSHIP, 1.5f)
        if (target.hp <= 0) {
            target.alive = false
            kills++
            brass += ((2 + rng.nextInt(6)) * Derived.lootFactor(stats)).roundToInt().coerceAtLeast(1)
            learnSkill(Skill.MARKSMANSHIP, 3f + target.level * 1.5f)
            emit(GameEvent.Note("Your ${p.label} brings the ${target.name} down. It does not rise."))
            recordKill(target)
            dropQuiver(target)
        } else {
            angerTheSettlement(target)
            emit(GameEvent.Note("Your ${p.label} strikes the ${target.name}. ${hit.damage}."))
            trainNpc(target, Skill.DEFENSE, 1.5f)
            trainNpc(target, Skill.ENDURANCE, 1f)
        }
        recordCombat(
            CombatResolution(
                "you", target.name, stats[Attr.FINESSE], growth.value(Skill.MARKSMANSHIP),
                p.weaponName, p.category, p.proficiency, p.mastery,
                ranged.rangedHitChance(p), 100, StrikeOutcome.HIT,
                (target.stats ?: StatBlock.balanced())[Attr.SWIFTNESS],
                hit.bodyTemper, hit.bodyMaterial,
                hit.dodgePenalty, 0, 0, hit.damage, hit.soak,
                hit.guardName, hit.blockZone.name, p.speedMps, p.massKg, p.marks, p.distance
            )
        )
    }

    /** A shot from the province's own archers arrives: the board may turn it aside. */
    private fun strikePlayerWith(p: Projectile) {
        // Their arrows fall at your feet when they land, and you may gather them.
        if (p.recoverable) groundItems += GroundItem(p.x, p.y, p.ammoItem)
        val damage = ranged.projectileDamage(p)
        val hit = combat.resolveProjectileHit(
            p.damageType, damage, false, 10, p.x, p.y, combat.combatantForPlayer(null)
        )
        var dealt = hit.damage
        if (wardTimer > 0f) dealt = (dealt * 0.45f).roundToInt().coerceAtLeast(1)
        vitality -= dealt
        if (hit.blockZone != BlockZone.MISS) {
            emit(
                GameEvent.Note(
                    if (dealt <= 0) "The ${p.shooterName}'s ${p.label} takes your ${hit.guardName} and is turned aside."
                    else "The ${p.shooterName}'s ${p.label} breaks on your ${hit.guardName}. $dealt lost."
                )
            )
        } else {
            hurtFlash = 0.9f
            learnSkill(Skill.DEFENSE, dealt * 0.8f)
            emit(GameEvent.Note("The ${p.shooterName}'s ${p.label} strikes you. $dealt lost."))
        }
        recordCombat(
            CombatResolution(
                p.shooterName, "you", p.marks, p.marks,
                p.weaponName, p.category, p.proficiency, p.mastery,
                ranged.rangedHitChance(p), 100,
                if (hit.blockZone != BlockZone.MISS) StrikeOutcome.BLOCKED else StrikeOutcome.HIT,
                stats[Attr.SWIFTNESS],
                hit.bodyTemper, hit.bodyMaterial,
                hit.dodgePenalty, 0, 0, dealt, hit.soak,
                hit.guardName, hit.blockZone.name, p.speedMps, p.massKg, p.marks, p.distance
            )
        )
    }


    /** A fallen archer's quiver spills: what it never loosed joins its spoils. */
    private fun dropQuiver(entity: Entity) {
        if (entity.ammoCount <= 0) return
        val arm = entity.equipment?.bestWeapon() ?: return
        if (!Ranged.isRanged(arm.archetype)) return
        val kind = Ranged.ammoOf(Ranged.formOf(arm).kind).firstOrNull() ?: return
        entity.loot += Ranged.rollAmmo(rng, kind, -1, entity.ammoCount)
        entity.ammoCount = 0
    }

    /** A creature of the province draws on you: honest arrows, honest spread, finite quiver. */

    /** One line of arms-lore for the HUD: what the held arm is doing, and what it feeds on. */
    fun rangedHud(): String? {
        val weapon = heldRanged ?: return null
        return when (rangedPhase) {
            RangedPhaseKind.DRAW -> if (drawFraction >= 1f) "full draw" else "drawing…"
            RangedPhaseKind.RELOAD ->
                if (Ranged.formOf(weapon).kind == RangedKind.CROSSBOW) "cocking…" else "loading…"
            RangedPhaseKind.IDLE -> when (Ranged.formOf(weapon).kind) {
                RangedKind.BOW -> {
                    val n = inventory.all.filter { Ranged.accepts(weapon, it) }.sumOf { it.count }
                    "${n.coerceAtLeast(0)} arrows"
                }
                RangedKind.SLING -> {
                    val n = inventory.all.filter { Ranged.accepts(weapon, it) }.sumOf { it.count }
                    "${n.coerceAtLeast(0)} shot"
                }
                RangedKind.CROSSBOW ->
                    if (loadedShots(weapon) > 0) "bolt set" else "slack"
                RangedKind.FIREARM, RangedKind.HAND_CANNON ->
                    "charged ${loadedShots(weapon)}/${Ranged.capacity(weapon)}"
                RangedKind.THROWN -> "×${weapon.count}"
            }
        }
    }

    /** The watch turns out for blood spilled among the folk; a warband needs no summons. */
    private fun alarmTheWatch(site: Site) {
        val warband = site.kind == SiteKind.CAMP
        repeat(1 + rng.nextInt(2) + site.garrison / 20) {
            val spot = openSpotNear() ?: return@repeat
            map.entities += spawnNpc(
                x = spot.first, y = spot.second,
                name = if (warband) "${site.name} warband" else "${site.name} watchman",
                spriteId = Sprites.HUSK,
                height = 1.0f, baseSpeed = 1.1f, level = 1 + depth + rng.nextInt(2),
                weights = OUTRIDER_WEIGHTS,
                personality = PersonalityBook.dealWatch(rng.nextLong(), warband)
            )
        }
        pushLog(
            if (warband) "The warband of ${site.name} comes running."
            else "The watch of ${site.name} comes running."
        )
    }

    /** Striking one of the folk costs the place's regard, and the watch turns out. */
    private fun angerTheSettlement(target: Entity) {
        if (!target.resident) return
        world.siteOrNull(currentSiteId)
            ?.takeIf { it.isSettlement }
            ?.let { site ->
                reputation.adjust(Layer.SETTLEMENT, site.id, -3, "Struck ${target.name}")
                alarmTheWatch(site)
            }
    }

    private fun recordKill(entity: Entity) {
        // Whatever it was, the world remembers this one by its own id, not its name.
        worldState.markDead(entity.persistId, minutes, "${entity.name} was slain")
        // A soul of a living place: the folk count drops, the watch is raised, the name is remembered.
        if (entity.resident) {
            val site = world.siteOrNull(currentSiteId) ?: return
            settlements.recordDeath(site.id)
            reputation.adjust(Layer.SETTLEMENT, site.id, -8, "Slew ${entity.name} in the street")
            alarmTheWatch(site)
            addRumor(
                "\"${entity.name} of ${site.name} was slain in cold blood.\"",
                "the streets of ${site.name}",
                site.id
            )
            val deed = "Slew ${entity.name} at ${site.name}"
            if (!deeds.contains(deed)) deeds += deed
            pushLog("${entity.name} falls. The folk of ${site.name} will not forget this.")
            return
        }
        // A chronicle beast met in the open: the deed is the world's, and its lair stands empty.
        if (entity.beastId >= 0) {
            worldState.slayBeast(entity.beastId, minutes, "${entity.name} was put down")
            val beast = world.beast(entity.beastId)
            val lair = beast?.let { world.siteOrNull(it.lairSiteId) }
            val deed = "Slew ${entity.name}" +
                (lair?.let { " in the wilds near ${it.name}" } ?: " in the open country")
            if (!deeds.contains(deed)) deeds += deed
            lair?.let { s ->
                val steward = world.stewardOf(s)
                if (steward != null && !steward.hostileByNature) {
                    reputation.adjust(Layer.POWER, steward.id, repGain(6), "Slew ${entity.name}, their terror")
                }
                reputation.logDeed(
                    Deed(day = day, domain = "battle", text = deed, siteId = s.id, powerId = steward?.id)
                        .also { spreadWord(it, s.id) }
                )
            }
            addRumor(
                "\"They say ${entity.name} is slain. The roads near ${lair?.name ?: "the wilds"} lie easier.\"",
                "the open road"
            )
            pushLog("${entity.name} falls, and the chronicle is one terror shorter.")
            return
        }
        if (entity.boss) {
            val deed = "Slew ${entity.name} beneath ${map.title}"
            if (!deeds.contains(deed)) deeds += deed
            reputation.logDeed(
                Deed(day = day, domain = "battle", text = deed, siteId = currentSiteId)
            )
            addRumor(
                "\"They say ${entity.name} is slain. The ${map.title} stands empty of its terror.\"",
                "the brass road"
            )
            pushLog("${entity.name} falls, and does not rise. The ${map.title} is broken.")
        }
        // A settlement remembers every soul struck down on its ground.
        if (entity.klass != null) {
            world.siteOrNull(currentSiteId)
                ?.takeIf { it.isSettlement }
                ?.let { recordSettlementDeath(it) }
        }
        // A fallen rider is worth more than a fallen husk, to everyone but its lord.
        (world.realms + world.organizations + world.groups)
            .firstOrNull { entity.name.startsWith("${it.name} ") }?.let { patron ->
                reputation.adjust(Layer.POWER, patron.id, -2, "Their riders fell on the road")
                world.siteOrNull(currentSiteId)?.let { site ->
                    reputation.adjust(Layer.SETTLEMENT, site.id, 2, "Riders of ${patron.name} put down")
                }
            }
        val site = world.siteOrNull(currentSiteId)
        val steward = site?.let { world.stewardOf(it) }
        val underground = site?.kind in setOf(SiteKind.VAULT, SiteKind.RUIN, SiteKind.BARROW)

        // Cleansing the walking dead in a steward's lands earns thanks; the same
        // blade, swung where their claims reach, earns watchmen.
        if (steward != null && !steward.hostileByNature) {
            reputation.adjust(Layer.POWER, steward.id, repGain(3), "Cleansed the dead beneath ${site?.name}")
        }
        site?.let { s ->
            reputation.adjust(Layer.SETTLEMENT, s.id, repGain(4), "Put down the dead that walked near ${s.name}")
        }

        // Brass pried from the burials offends the grave-gods, one story at a time.
        if (underground) {
            lootedBurials++
            if (lootedBurials % 5 == 0) {
                world.deities.filter { it.domain == "graves" }.forEach { god ->
                    reputation.adjust(Layer.DEITY, god.id, -3, "Pried brass from the dead")
                }
                reputation.logDeed(
                    Deed(
                        day = day, domain = "loot", text = "Pried brass from the dead",
                        siteId = currentSiteId, powerId = steward?.id
                    )
                )
                pushLog("The dead were owed better. The grave-gods count.")
                learnSkill(Skill.LOCKPICKING, 4f)
            }
        }

        reputation.logDeed(
            Deed(
                day = day, domain = "battle",
                text = "Put down a ${entity.name} beneath ${map.title}",
                siteId = currentSiteId, powerId = steward?.id
            ).also { spreadWord(it, currentSiteId) }
        )

        if (kills % 3 == 0) {
            val deed = "Put down a ${entity.name} beneath ${map.title}"
            deeds += deed
            rumors.add(
                0,
                Rumor(
                    text = "\"Something is killing the ${entity.name}s under ${map.title}.\"",
                    source = "${world.sites.random(rng).name} road",
                    daysOld = 0,
                    aboutPlayer = true
                )
            )
        }
    }

    /** A commanded presence makes good deeds weigh more; ill repute needs no help. */
    private fun repGain(delta: Int): Int =
        if (delta > 0) (delta * Derived.rewardFactor(stats)).roundToInt() else delta

    /** Word of a deed travels the roads at a walker's pace and lands day by day. */
    private fun spreadWord(deed: Deed, originSiteId: Int?) {
        val origin = originSiteId?.let { world.siteOrNull(it) } ?: return
        world.sites
            .filter { it.isSettlement && !it.ruined && it.id != origin.id }
            .forEach { site ->
                val leagues = MapFactory.distance(origin.x, origin.y, site.x, site.y) * WORLD_LEAGUES
                val days = (leagues / 2.1f / 24f).roundToInt().coerceIn(1, 8)
                pendingWord += Word(day + days, site.id, deed)
            }
        if (pendingWord.size > 80) {
            pendingWord.subList(0, pendingWord.size - 80).clear()
        }
    }

    /** Word arrives: the settlement's regard shifts by what the deed was worth. */
    private fun deliverWord() {
        val today = day
        val arrived = pendingWord.filter { it.arrivalDay <= today }
        if (arrived.isEmpty()) return
        pendingWord.removeAll(arrived.toSet())
        arrived.forEach { word ->
            val delta = when (word.deed.domain) {
                "battle" -> 2
                "offering" -> 3
                "delving" -> -4
                "loot" -> -3
                else -> 0
            }
            if (delta != 0) {
                reputation.adjust(Layer.SETTLEMENT, word.siteId, delta, "Word of it: ${word.deed.text}")
            }
        }
    }

    // ------------------------------------------------------------- world time

    /**
     * Spend [span] minutes of the world's own time and let the province use
     * them. One clock only: [minutes] is still the single time model, and this
     * is simply the seam where meaningful passage reaches the simulation.
     *
     * The frame loop does not come through here — what is loaded and awake is
     * already simulated per frame. This is for time that passes in a lump:
     * stairs, gates, rest, and the road.
     */
    private fun advanceWorld(span: Float) {
        if (span > 0f) minutes += span
        stepFolkYears()
        lastReport = WorldSimulation.advance(
            worldState, world, settlements, minutes, currentSiteId
        )
        lastReport.rumors.forEach { rumor ->
            rumors.add(0, rumor)
            if (rumors.size > 14) rumors.removeAt(rumors.size - 1)
        }
        if (lastReport.days > 0) ageRumors(lastReport.days.toFloat())
    }

    /** What the last advance of the world did, for the log and for verification. */
    var lastReport: WorldSimulation.Report = WorldSimulation.Report.NONE
        private set

    /** Tell the chronicler what the province did while you were elsewhere. */
    private fun reportWorldChange() {
        lastReport.notes.take(3).forEach { pushLog(it) }
    }

    /**
     * Walk the open province to a place you can name: the road is time, and the
     * world spends it. Duration comes from the leagues and the weather, the
     * world is carried forward across the whole walk, the country between is
     * rolled for what it holds, and the destination is then reconstructed from
     * base world plus persistent state — never freshly invented.
     */
    fun travelTo(site: Site): Boolean {
        if (dead) return false
        if (!onOverland) {
            pushLog("You must take the open ground before you can set out.")
            return false
        }
        val bearing = bearingTo(site)
        val hours = WorldSimulation.travelHours(bearing.leagues, weather.rain)
        // Put the walker where the walk ended before the country is rolled.
        val spot = WorldSimulation.overlandSpot(site, overland)
        camera.x = spot.first.coerceIn(1.2f, overland.width - 1.2f)
        camera.y = spot.second.coerceIn(1.2f, overland.height - 1.2f)
        overland.entrySpots[site.id]?.let { entry ->
            camera.x = entry.first
            camera.y = entry.second
        }
        pushLog(
            "You set out for ${site.name} — ${bearing.compass}, " +
                "${(bearing.leagues * 10).roundToInt() / 10f} leagues."
        )
        ensureLandmark(site)
        // 1-3: the road's hours are the world's hours.
        advanceWorld(hours * 60f)
        val road = lastReport
        // 4: the country between keeps its own counsel.
        repeat(WorldSimulation.encounterRolls(bearing.leagues)) { rollEncounter() }
        // 5: what the walk cost the walker.
        drainFatigue(WorldSimulation.travelFatigue(hours).toFloat())
        torch = (torch - hours * 0.05f).coerceAtLeast(0f)
        learnSkill(Skill.NAVIGATION, hours * 1.5f)
        learnSkill(Skill.ATHLETICS, hours * 1.2f)
        pushLog(
            "You walk ${hoursWord(hours)} and come within sight of ${site.name}."
        )
        reportWorldChange()
        // 6-7: arrive, and the place is put back as the world remembers it.
        enterSite(site)
        // The gate's own quarter hour belongs to this journey: one walk, one reckoning.
        lastReport = road + lastReport
        return true
    }

    /** The chronicler's word for a span of walking. */
    private fun hoursWord(hours: Float): String = when {
        hours < 1f -> "the better part of an hour"
        hours < 24f -> "${hours.roundToInt()} hours"
        else -> "${(hours / 24f).roundToInt()} days"
    }

    // ------------------------------------------------------- living settlements

    /** Turn the years: every settlement's folk rises and thins, and the chronicle remembers. */
    internal fun stepFolkYears() {
        val year = gameYear
        if (year <= ledgerYear) return
        val turns = settlements.stepYears(year, world.sites)
        turns.forEach { turn ->
            chronicle += turn.events
            turn.roseTo?.let { rose ->
                pushLog("Word comes from ${turn.site.name}: it stands a ${rose.label} now.")
                rebuildLandmark(turn.site)
            }
        }
        // The deep layer beneath the years: the walkers, the thinning, the left places.
        history.stepYears(year, world.sites, settlements, turns).forEach { age ->
            chronicle += age.toChronicle()
            if (age.kind == AgeEventKind.MIGRATION || age.kind == AgeEventKind.ABANDONED) {
                pushLog(age.text)
            }
        }
        ledgerYear = year
    }

    /** A place its folk raised to a new stage is redrawn on the open province, in place. */
    private fun rebuildLandmark(site: Site) {
        if (!overlandLazy.isInitialized()) return
        OverlandGen.redrawLandmark(overlandLazy.value, world, site, settlements.folkOf(site))
        // stood where the new walls rose? the gate takes you in
        if (onOverland && currentSiteId == site.id && map.isWall(camera.x, camera.y)) {
            overland.entrySpots[site.id]?.let { spot ->
                camera.x = spot.first
                camera.y = spot.second
            }
        }
    }

    /** A soul struck down on a settlement's ground is counted against its year. */
    internal fun recordSettlementDeath(site: Site) {
        if (!site.isSettlement) return
        settlements.recordDeath(site.id)
    }

    /** The living folk of a settlement, as the years have made them. */
    fun folkOf(siteId: Int): Int =
        world.siteOrNull(siteId)?.let { settlements.folkOf(it) } ?: 0

    /** The stage a settlement stands at, from its founding rank and its living folk. */
    fun stageAt(site: Site): SettlementStage = settlements.stageAt(site)

    internal fun addRumor(text: String, source: String, siteId: Int = -1, daysOld: Int = 0) {
        rumors.add(
            0,
            Rumor(text = text, source = source, daysOld = daysOld, aboutPlayer = true, siteId = siteId)
        )
        if (rumors.size > 14) rumors.removeAt(rumors.size - 1)
    }

    /** Opening the dead's doors is noticed — by their keepers, and by whoever claims the ground. */
    private fun recordDelving(site: Site) {
        if (site.kind !in setOf(SiteKind.VAULT, SiteKind.RUIN, SiteKind.BARROW)) return
        if (site.id in worldState.delvedSites) return
        worldState.delvedSites += site.id
        val steward = world.stewardOf(site)
        reputation.adjust(Layer.POWER, steward?.id, -5, "Delved where they keep the seal")
        world.claimsOn(site.id).forEach { claim ->
            if (claim.claimantId != steward?.id) {
                reputation.adjust(Layer.POWER, claim.claimantId, -3, "Delved on ground they claim")
            }
        }
        reputation.logDeed(
            Deed(
                day = day, domain = "delving", text = "Broke the seal of ${site.name}",
                siteId = site.id, powerId = steward?.id
            ).also { spreadWord(it, site.id) }
        )
        addRumor("\"Someone has broken the seal of ${site.name}.\"", "the brass road")
        pushLog("Word will get out that you opened ${site.name}.")
    }

    /** True when you stand at a living temple with brass enough for an offering. */
    fun canOffer(): Boolean {
        if (brass < 10) return false
        val site = world.siteOrNull(currentSiteId) ?: return false
        return site.structures.any { it.kind == StructureKind.TEMPLE && !it.ruined }
    }

    /** Silver at the altar: the god takes note, the house remembers, the town softens. */
    fun offerAtTemple() {
        if (!canOffer()) return
        val site = world.site(currentSiteId)
        brass -= 10
        val realm = world.realmOf(site)
        val deity = world.deities.firstOrNull { it.cultureId == realm?.cultureId }
            ?: world.deities.firstOrNull()
        val houseId = world.figures
            .lastOrNull { it.realmId == realm?.id && it.houseId != null && it.diedYear == null }
            ?.houseId
        val text = "Left silver at the temple of ${site.name}"
        deity?.let { reputation.adjust(Layer.DEITY, it.id, repGain(8), text) }
        houseId?.let { reputation.adjust(Layer.HOUSE, it, repGain(3), text) }
        reputation.adjust(Layer.SETTLEMENT, site.id, repGain(2), text)
        learnSkill(Skill.ETIQUETTE, 6f)
        reputation.logDeed(
            Deed(
                day = day, domain = "offering", text = text,
                siteId = site.id, powerId = realm?.id, houseId = houseId, deityId = deity?.id
            ).also { spreadWord(it, site.id) }
        )
        addRumor("\"A delver left silver at the temple of ${site.name}.\"", "temple steps")
        pushLog(
            if (deity != null) "You leave 10 brass at the temple. ${deity.name} takes note."
            else "You leave 10 brass at the temple. Someone takes note."
        )
    }

    /** True when you stand at a living shrine with a coin to spare. */
    fun canOfferAtShrine(): Boolean {
        if (brass < 5) return false
        val site = world.siteOrNull(currentSiteId) ?: return false
        return site.kind == SiteKind.SHRINE
    }

    /** A coin in the bowl at a wayside shrine: the god's ear, and the locals' quiet approval. */
    fun offerAtShrine() {
        if (!canOfferAtShrine()) return
        val site = world.site(currentSiteId)
        brass -= 5
        val cultureId = SiteGen.cultureId(world, site)
        val deity = world.deities.firstOrNull { it.cultureId == cultureId }
            ?: world.deities.firstOrNull()
        val text = "Left a coin at the shrine of ${site.name}"
        deity?.let { reputation.adjust(Layer.DEITY, it.id, repGain(5), text) }
        reputation.adjust(Layer.SETTLEMENT, site.id, repGain(2), text)
        learnSkill(Skill.ETIQUETTE, 4f)
        reputation.logDeed(
            Deed(
                day = day, domain = "offering", text = text,
                siteId = site.id, deityId = deity?.id
            ).also { spreadWord(it, site.id) }
        )
        addRumor("\"Someone left a coin at the stones of ${site.name}.\"", "the shrine road")
        pushLog(
            if (deity != null) "You leave 5 brass in the bowl. ${deity.name} takes note, and so do the locals."
            else "You leave 5 brass in the bowl. Someone takes note."
        )
    }

    // ------------------------------------------------------- scrolls & tomes

    /** The strike hand spends a scroll: the formula resolves, a use burns away. */
    private fun castFromScroll(item: Item) {
        beginSwingArc(
            Derived.strikeCooldown(
                stats, growth, null, proficiencies.value(WeaponCategory.UNARMED), 0
            )
        )
        drainFatigue(1f)
        val scroll = magicLedger.scrollFor(item)
        if (scroll == null) {
            emit(GameEvent.Note("The vellum is blank; whatever formula it held has faded."))
            return
        }
        val spell = spellRegistry.get(scroll.spellId)
        if (spell == null) {
            emit(GameEvent.Note("The scroll's art is beyond the world's lore."))
            return
        }
        scroll.remainingUses = (scroll.remainingUses - 1).coerceAtLeast(0)
        // The scroll's own power works the formula: no will spent, nothing learned.
        SpellCasting.resolve(this, spell, casterName = "you", byPlayer = true)
        emit(GameEvent.ScrollUsed(spell.name, scroll.remainingUses))
        if (scroll.spent) {
            equipment.slotOf(item)?.let { equipment.unequip(it) }
            inventory.remove(item)
            emit(GameEvent.ScrollSpent(spell.name))
        }
    }

    /**
     * Study a tome: what it holds joins your book — components first, then the
     * formulas they shape into. Possession teaches nothing until the pages are
     * read; a work once drawn dry teaches no more.
     */
    fun studyTome(item: Item): Boolean {
        val tome = magicLedger.tomeFor(item) ?: run {
            pushLog("That is no written work of the craft.")
            return false
        }
        val work = magicLedger.work(tome.workId)
        if (work == null) {
            pushLog("The pages are loose and illegible.")
            return false
        }
        if (tome.id in magicLedger.playerStudied) {
            pushLog("You have drawn \"${work.title}\" dry already.")
            return false
        }
        magicLedger.playerStudied += tome.id
        var components = 0
        var spells = 0
        work.componentIds.forEach { id ->
            if (ComponentRegistry.all().any { it.id == id } && discoverComponent(id)) components++
        }
        work.spellIds.forEach { id ->
            val spell = spellRegistry.get(id) ?: return@forEach
            if (knownMagic.learnSpell(id)) {
                emit(GameEvent.SpellLearned(spell.name))
                spells++
            }
        }
        growth.feed(Skill.SPELLCRAFTING, stats, 10f)
        emit(GameEvent.TomeStudied(work.title, spells, components))
        return spells > 0 || components > 0
    }

    /** A reader's name for a scroll or tome; null for ordinary things. */
    fun magicItemName(item: Item): String? = magicLedger.nameFor(item, spellRegistry)

    /** The province's chronicle with its magical history folded in, oldest first. */
    fun fullChronicle(): List<ChronicleEvent> =
        (world.events + magicLedger.chronicle).sortedBy { it.year }

    // -------------------------------------------------------------- spellcrafting

    /** The caster's Spellcrafting value: the hand that shapes formulas. */
    fun craft(): Int = growth.value(Skill.SPELLCRAFTING)

    /** The components this soul may shape — only the known. */
    fun knownComponents(): List<MagicalComponent> =
        ComponentRegistry.all().filter { knownMagic.knownComponents.contains(it.id) }

    /** The spells this soul keeps in its book. */
    fun knownSpells(): List<Spell> =
        spellRegistry.all().filter { knownMagic.knownSpells.contains(it.id) }

    /** Whether the caster could shape this effect's parameters right now. */
    fun canShape(effect: SpellEffect): Boolean {
        val component = ComponentRegistry.all().firstOrNull { it.id == effect.componentId } ?: return false
        if (!knownMagic.knows(component.id)) return false
        if (effect.delivery !in component.deliveries) return false
        // generic components take their selection from the effect itself
        when (component.targetKind) {
            MagicTargetKind.ATTRIBUTE -> if (MAGIC_TARGET_ATTRIBUTES.none { it.name == effect.target }) return false
            MagicTargetKind.SKILL -> if (Skill.entries.none { it.name == effect.target }) return false
            MagicTargetKind.NONE -> {}
        }
        if (effect.magnitude < component.magnitude.first) return false
        if (effect.magnitude > SpellForge.magnitudeCap(component, craft(), stats)) return false
        if (effect.duration < component.duration.first) return false
        if (effect.duration > SpellForge.durationCap(component, craft(), stats)) return false
        if (effect.area < component.area.start) return false
        if (effect.area > SpellForge.areaCap(component, craft(), stats)) return false
        if (effect.range < component.range.first) return false
        if (effect.range > SpellForge.rangeCap(component, craft())) return false
        return true
    }

    /** Shape and keep a new formula from known components; a forged spell is an ordinary spell. */
    fun craftSpell(name: String, effects: List<SpellEffect>): Spell? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || effects.isEmpty()) return null
        if (effects.size > SpellForge.maxEffects(craft(), stats)) return null
        if (effects.any { !canShape(it) }) return null
        val complexity = SpellCalc.complexity(effects)
        if (complexity > SpellForge.complexityBudget(craft(), stats)) return null
        val spell = SpellCalc.forge(
            id = SpellRegistry.newSpellId(world.seed, spellsForged++),
            name = trimmed,
            creator = "you",
            effects = effects
        )
        spellRegistry.register(spell)
        knownMagic.learnSpell(spell.id)
        growth.feed(Skill.SPELLCRAFTING, stats, 12f)
        emit(GameEvent.SpellCreated(spell.name, "you"))
        return spell
    }

    /** Take up a spell: spend the will, resolve the formula, grow by working magic. */
    fun castSpell(spellId: String): Boolean {
        val spell = spellRegistry.get(spellId) ?: return false
        if (!knownMagic.knownSpells.contains(spellId)) return false
        if (magicka < spell.cost) {
            pushLog("Your will is spent; ${spell.name} will not take.")
            return false
        }
        magicka -= spell.cost
        SpellCasting.resolve(this, spell, casterName = "you", byPlayer = true)
        growth.feed(Skill.SPELLCRAFTING, stats, 4f)
        spell.schools.forEach { school ->
            schoolProficiencies.feed(school, 4f)?.let {
                emit(GameEvent.MagicalProficiencyIncreased(it.label))
            }
        }
        return true
    }

    /**
     * A soul works its own lore: the same price as yours, paid from its own
     * well, through the same resolver — nothing about an NPC's magic is cheap.
     */
    fun castSpellAs(entity: Entity, spell: Spell): Boolean {
        if (!entity.alive || entity.magicka < spell.cost) return false
        entity.magicka -= spell.cost
        SpellCasting.resolve(this, spell, casterName = entity.name, byPlayer = false, caster = entity)
        return true
    }

    /** A component discovered in the world joins what this soul may shape. */
    fun discoverComponent(id: String): Boolean {
        if (!knownMagic.learnComponent(id)) return false
        emit(GameEvent.MagicComponentDiscovered(ComponentRegistry.get(id).name))
        return true
    }

    /** A formula of the assembled lore is taken into your book; it casts like any other. */
    fun learnGrimoireSpell(spellId: String): Boolean {
        val spell = SpellGrimoire.get(spellId) ?: return false
        spellRegistry.register(spell)
        if (!knownMagic.learnSpell(spell.id)) return false
        pushLog("You take ${spell.name} into your book.")
        return true
    }

    /** A formula passes into another soul's book: an NPC's magic is the same ledger. */
    fun teachSpell(entity: Entity, spellId: String): Boolean {
        val spell = spellRegistry.get(spellId)
            ?: SpellGrimoire.get(spellId)?.also { spellRegistry.register(it) }
            ?: return false
        return entity.magic.learnSpell(spell.id)
    }

    /** A summons answers: a temporary creature steps from the weave beside its caller. */
    internal fun spawnSummon(
        component: MagicalComponent, power: Int, x: Float = camera.x, y: Float = camera.y
    ): Entity? {
        val undead = component.id == "summon_undead"
        val offsets = listOf(
            Pair(1.2f, 0f), Pair(-1.2f, 0f), Pair(0f, 1.2f), Pair(0f, -1.2f),
            Pair(1f, 1f), Pair(-1f, -1f), Pair(1f, -1f), Pair(-1f, 1f)
        )
        val spot = offsets.firstOrNull { (dx, dy) -> !map.isWall(x + dx, y + dy) }
            ?: return null
        val life = 20 + power * 4
        val entity = Entity(
            x = x + spot.first, y = y + spot.second,
            spriteId = Sprites.HUSK, kind = EntityKind.ENEMY,
            height = if (undead) 1f else 0.8f,
            name = if (undead) "a summoned ghoul" else "a summoned beast",
            hp = life, maxHp = life, damage = 4 + power / 2, speed = 2.4f,
            level = 1 + power / 3
        )
        map.entities += entity
        return entity
    }

    /** The station accepts the hand: the spellcrafter may work while it is near. */
    fun useSpellcraftingStation() {
        atSpellcraftingStation = true
        pushLog("You lean over the spellcrafting station; a fresh formula asks for its parts.")
    }

    /** Held magic trickles and lapses; the station's reach ends when you step away. */
    private fun stepMagic(dt: Float) {
        SpellCasting.step(this, dt)
        val station = map.entities.firstOrNull { it.name == MagicStations.NAME }
        if (station == null || MapFactory.distance(camera.x, camera.y, station.x, station.y) > 2.4f) {
            atSpellcraftingStation = false
        }
    }

    fun castWard() {
        val cost = wardCost()
        if (magicka < cost) {
            pushLog("Not enough will left to shape a ward.")
            return
        }
        magicka -= cost
        wardTimer = 25f
        learnSkill(Skill.LORE, 3f)
        pushLog("A verdigris ward settles on your skin. It will hold a while.")
    }

    fun castMend() {
        val cost = mendCost()
        if (magicka < cost) {
            pushLog("The mending sigil will not take; your will is spent.")
            return
        }
        magicka -= cost
        val healed = ((10 + rng.nextInt(9)) * Derived.mendPotency(stats, growth)).roundToInt()
        vitality = (vitality + healed).coerceAtMost(maxVitality)
        learnSkill(Skill.MEDICINE, 4f)
        pushLog("Flesh knits, badly but enough. $healed restored.")
    }

    fun relightTorch() {
        // Nothing in hand: take up a torch and light it where you stand.
        if (!holdsTorch) {
            val spare = inventory.all.firstOrNull { it.archetype == ItemArchetype.TORCH }
            if (spare == null) {
                pushLog("No torch left to light.")
                return
            }
            val handsFull = equipment.leftClaimed ||
                (equipment.worn(Hand.RIGHT.slot) != null && equipment.worn(Hand.LEFT.slot) != null)
            if (handsFull) {
                pushLog("Your hands are full; take up the torch when one is free.")
                return
            }
            equipFromSatchel(spare)
            torch = 1f
            pushLog("You light the torch. The dark backs off.")
            return
        }
        // A torch from the bundle burns before your brass does.
        if (inventory.useTorch()) {
            torch = 1f
            pushLog("You light a fresh torch from the bundle. The dark backs off.")
            return
        }
        if (brass < 3) {
            pushLog("No pitch left to dress a torch. You need brass for that.")
            return
        }
        brass -= 3
        torch = 1f
        pushLog("You dress the torch with fresh pitch. The dark backs off.")
    }

    /** A grey remedy: bitter, effective, no skill in the drinking. */
    fun useRemedy(): Boolean {
        if (!inventory.useRemedy()) {
            pushLog("No remedies left in the satchel.")
            return false
        }
        val healed = 22 + rng.nextInt(9)
        vitality = (vitality + healed).coerceAtMost(maxVitality)
        pushLog("You drink a grey remedy. It tastes of cellar air. $healed restored.")
        return true
    }

    /** Lay a thing down where you stand; the USE hand takes it back up. */
    fun dropItem(item: Item) {
        if (!inventory.remove(item, item.count)) return
        groundItems += GroundItem(camera.x, camera.y, item)
        pushLog("You set the ${styleRoster.nameFor(item)} down on the ${if (outdoor) "ground" else "flags"}.")
    }

    /** What your back will bear right now, for the HUD's weight chip. */
    fun carryCapacity(): Float = Derived.carryCapacity(stats, growth)

    // ------------------------------------------------------------ the USE hand

    /** The nearest dropped thing within arm's reach, or null. */
    fun nearestDrop(): GroundItem? = interaction.nearestDrop()

    /** What the USE button would do right now, in the chronicler's words. */
    fun usePrompt(): String = interaction.usePrompt()

    /** The USE hand: whatever stands nearest — pocket, dropped thing, spoils, or the way out. */
    fun interact(): Interact = interaction.interact()

    // ------------------------------------------------------------ equipment

    /** A one-handed piece asks which hand takes it, when both stand free. */
    fun canChooseHand(item: Item): Boolean =
        item.archetype.slot != ItemSlot.SHIELD &&
            item.archetype.wear?.isHand == true && !item.archetype.twoHanded &&
            !equipment.leftClaimed &&
            equipment.worn(Hand.RIGHT.slot) == null &&
            equipment.worn(Hand.LEFT.slot) == null

    /** Don a thing from the satchel: it leaves the load, and what it displaced returns to it. */
    fun equipFromSatchel(item: Item, hand: Hand? = null) {
        // A bundle yields one of its kind to the hand; the rest stays shouldered.
        val taken = if (item.archetype.stacks && item.count > 1) {
            if (!inventory.remove(item, 1)) return
            item.copy(count = 1)
        } else {
            if (!inventory.remove(item)) return
            item
        }
        val result = equipment.equip(taken, hand)
        if (result == null) {
            inventory.add(taken)
            pushLog("That is not made to be worn.")
            return
        }
        val (slot, displaced) = result
        displaced.forEach { inventory.add(it) }
        validateBlocking()
        refreshConstantEnchantments()
        // A worn aura kindles as the piece settles into place.
        Enchanting.triggerEnchantments(this, taken, EnchantmentActivation.WHEN_WORN, this, byPlayer = true)
        val name = styleRoster.nameFor(taken)
        pushLog(
            when {
                slot == WearSlot.RIGHT_HAND && item.archetype.twoHanded -> "You take up the $name in both hands."
                slot.isHand -> "You take up the $name in your ${slot.label}."
                slot.isRing -> "You slide the $name onto your ${slot.label}."
                else -> "You buckle the $name on."
            }
        )
    }

    /** Strip a worn thing; it goes back to the satchel and the load. */
    fun unequipFromEquipment(slot: WearSlot) {
        val item = equipment.unequip(slot) ?: return
        inventory.add(item)
        validateBlocking()
        refreshConstantEnchantments()
        pushLog("You take off the ${styleRoster.nameFor(item)}.")
    }

    // ------------------------------------------------------------ enchantments

    /** Lay the worn constants onto the self, or take them back — exact reverts, never a loop. */
    private fun refreshConstantEnchantments() {
        val fresh = Enchanting.constantBonuses(equipment.items())
        Attr.entries.forEach { attr ->
            val delta = (fresh[attr] ?: 0) - (constantBonus[attr] ?: 0)
            if (delta != 0) stats.adjust(attr, delta)
        }
        constantBonus.clear()
        constantBonus.putAll(fresh)
        recomputePools()
    }

    /**
     * Call a woven thing's working by hand: charge checked and paid, the one
     * resolver does the magic. A mend finds its wearer; an offensive working
     * finds whatever stands in front of its wielder.
     */
    fun useEnchantedItem(item: Item): Boolean {
        val working = Enchanting.manualInstance(item) ?: run {
            pushLog("It holds no working you can call by hand.")
            return false
        }
        val def = working.def ?: return false
        if (def.chargeCost > working.currentCharge) {
            pushLog("The ${def.name.lowercase()} in the ${Enchanting.itemLabel(this, item)} has no charge left to pay.")
            return false
        }
        val hostile = def.delivery != Delivery.SELF
        val target = if (hostile) combat.pickMeleeTarget(camera, map.entities) else this
        Enchanting.triggerEnchantments(this, item, def.activation, this, byPlayer = true, forcedTarget = target)
        return true
    }

    /** Pour the will into a woven thing: the well pays, the piece drinks. */
    fun rechargeEnchantment(item: Item, requested: Int): Int {
        val (charge, spent) = Enchanting.recharge(this, item, requested)
        if (spent > 0) {
            emit(
                GameEvent.ItemRecharged(
                    Enchanting.itemLabel(this, item), charge,
                    item.enchantments.maxOfOrNull { it.maxCharge } ?: 0, magicka
                )
            )
        } else if (requested > 0) {
            pushLog("Your will is too spent, or the piece is already full.")
        }
        return charge
    }

    /**
     * The low-resource weaves: asked on the half-second, only on the crossing.
     * Below the threshold one fires and latches; it stays quiet until the pool
     * climbs back above the recovery mark — no healing loop, ever.
     */
    private fun evaluateLowEnchantments() {
        val pools = mapOf(
            EnchantmentActivation.WHEN_LOW_HEALTH to vitality / maxVitality.coerceAtLeast(1),
            EnchantmentActivation.WHEN_LOW_FATIGUE to fatigue / maxFatigue.coerceAtLeast(1),
            EnchantmentActivation.WHEN_LOW_MAGICKA to magicka / maxMagicka.coerceAtLeast(1)
        )
        equipment.items().forEach { item ->
            item.enchantments.forEach { instance ->
                val activation = instance.def?.activation ?: return@forEach
                val fraction = pools[activation] ?: return@forEach
                val key = "${item.uid}:${activation.name}"
                if (fraction <= EnchantRules.LOW_THRESHOLD) {
                    if (key !in lowLatched) {
                        lowLatched += key
                        Enchanting.triggerEnchantments(
                            this, item, activation, this, byPlayer = true, forcedTarget = this
                        )
                    }
                } else if (fraction >= EnchantRules.LOW_RECOVER) {
                    lowLatched -= key
                }
            }
        }
    }

    // ------------------------------------------------------------ loot

    /** The nearest thing with spoils: a fresh corpse or a container not yet emptied. */
    fun lootableHere(): Entity? = interaction.lootableHere()

    /** Strip one worn thing from a corpse; it goes straight to the satchel. */
    fun takeEquipped(entity: Entity, slot: WearSlot) = interaction.takeEquipped(entity, slot)

    /** Lift one thing from a corpse or container; brass rides along when it is the last. */
    fun takeItem(entity: Entity, item: Item) {
        // A scroll or tome lifted from its place will not regenerate there.
        if ((item.archetype == ItemArchetype.SCROLL || item.archetype == ItemArchetype.TOME) &&
            item in entity.loot
        ) {
            magicLedger.markTaken(item.uid)
        }
        interaction.takeItem(entity, item)
    }

    /** Empty a corpse or container of everything it holds — worn things first — coins included. */
    fun takeAll(entity: Entity): Int {
        entity.loot.filter {
            it.archetype == ItemArchetype.SCROLL || it.archetype == ItemArchetype.TOME
        }.forEach { magicLedger.markTaken(it.uid) }
        return interaction.takeAll(entity)
    }

    /** Rest: hours pass, wounds close, and something may find you first. */
    fun rest(hours: Int) {
        if (dead) return
        // Rest is only as safe as your name in the place you rest — and rain draws ears too.
        // A roof of your own beats the ditch: buildings sleep dry, barred, ambush-free.
        val sheltered = depth >= SiteGen.BUILDING_FLOOR_BASE
        val inn = sheltered && inBuilding?.kind == "tavern"
        if (inn) {
            if (brass < 5) {
                pushLog("The keeper of the ${inBuilding?.name} wants 5 brass for a bed. You haven't it.")
                return
            }
            brass -= 5
        }
        val wet = !sheltered && outdoor && weather.rain > 0.25f
        val ambush = when {
            sheltered -> false
            !outdoor -> rng.nextInt(3) == 0
            else -> (reputation.regardFor(currentSiteId) <= -25 && rng.nextInt(2) == 0) ||
                (wet && rng.nextInt(3) == 0)
        }
        val actual = if (ambush) (hours / 2).coerceAtLeast(1) else hours
        val (vitalityPerHour, fatiguePerHour) = restRecovery(wet)
        val woundsBefore = vitality
        val wearinessBefore = fatigue
        val willBefore = magicka
        // Hours spent sleeping are hours the province spends too.
        advanceWorld(actual * 60f)
        vitality = (vitality + actual * vitalityPerHour).coerceAtMost(maxVitality)
        fatigue = (fatigue + actual * fatiguePerHour).coerceAtMost(maxFatigue)
        magicka = (magicka + actual * 5).coerceAtMost(maxMagicka)
        learnSkill(Skill.SURVIVAL, actual * 0.9f)
        // A rest long enough to cross days lets the world put its souls back where they now stand.
        if (lastReport.moved > 0 && !onOverland) {
            SceneBinder.restore(worldState, currentSiteId, depth, map)
        }
        if (ambush) {
            pushLog("You are woken after $actual hours. Something is already in the room.")
            spawnAmbush()
        } else {
            torch = (torch + 0.35f).coerceAtMost(1f)
            pushLog(
                when {
                    inn -> "You take a bed at the ${inBuilding?.name}: dry, dark, and barred behind you."
                    sheltered -> "You sleep dry behind a barred door. The night passes without trouble."
                    wet -> "You rest $actual hours under the rain, cold and hardly dry. It buys little."
                    else -> "You rest $actual hours. The province gets on without you."
                }
            )
        }
        pushLog(
            "Wounds close some (vitality +${vitality - woundsBefore}); the tiredness lifts " +
                "(weariness -${wearinessBefore - fatigue}); will gathers (will +${magicka - willBefore})."
        )
    }

    /** Whole hours until first light, read from the clock as it stands. */
    fun hoursTillDawn(): Int = hoursTillDawn(hour, minute)

    /** Rest until first light, however long the clock says that is. */
    fun restTillDawn() = rest(hoursTillDawn())

    internal val OUTRIDER_WEIGHTS = mapOf("bulwark" to 3, "warden" to 2, "wayfarer" to 1)

    /** Roll a named creature of the province with its class and level-earned stats. */
    internal fun spawnNpc(
        x: Float,
        y: Float,
        name: String,
        spriteId: Int,
        height: Float,
        baseSpeed: Float,
        level: Int,
        weights: Map<String, Int>,
        personality: Personality? = null
    ): Entity {
        val klass = roster.byKey(MapFactory.weightedStringPick(weights, rng) ?: "")
        val stats = klass?.let { rollNpcStats(level, it.weights, rng) }
        val skills = Growth.forClass(klass).also { it.level = level }
        val entity = if (klass == null || stats == null) {
            Entity(
                x = x, y = y, spriteId = spriteId, kind = EntityKind.ENEMY, height = height,
                name = name, hp = 14 + level * 3, maxHp = 14 + level * 3,
                damage = 4 + level, speed = baseSpeed, level = level, skills = skills
            )
        } else {
            Entity(
                x = x, y = y, spriteId = spriteId, kind = EntityKind.ENEMY, height = height,
                name = name,
                hp = Derived.npcMaxHp(stats, skills, level), maxHp = Derived.npcMaxHp(stats, skills, level),
                damage = Derived.npcDamage(stats, skills, level), speed = Derived.npcSpeed(stats, skills, baseSpeed),
                level = level, klass = klass, stats = stats, skills = skills
            )
        }
        entity.personality = personality
        MapFactory.armLoot(entity, klass, level, siteCultureId(currentSiteId), rng, geography)
        return entity
    }

    /** A creature learns from every exchange with you, in the same grammar as you do. */
    fun trainNpc(entity: Entity, skill: Skill, amount: Float) {
        if (entity.kind != EntityKind.ENEMY || !entity.alive || amount <= 0f) return
        entity.skills.feed(skill, entity.stats ?: StatBlock.balanced(), amount) ?: return
        entity.risesThisLife++
        refreshNpc(entity)
        if (entity.skills.level > entity.level) {
            entity.level = entity.skills.level
            pushLog("The ${entity.name} fights like something older now — level ${entity.level}.")
        }
    }

    /** A creature's arm teaches its kind and its own piece, in the same grammar as the delver's. */
    internal fun trainNpcWeapon(entity: Entity, profAmount: Float, masteryAmount: Float) {
        if (entity.kind != EntityKind.ENEMY || !entity.alive) return
        val weapon = entity.equipment?.bestWeapon()
        val category = weapon?.let { WeaponCategory.forWeapon(it.archetype) } ?: WeaponCategory.UNARMED
        var learned = entity.proficiencies.feed(category, profAmount) != null
        if (weapon != null && entity.masteries.feed(weapon.uid, masteryAmount) != null) learned = true
        if (learned) refreshNpc(entity)
    }

    /** A creature's combat numbers follow its skills, exactly as the delver's do. */
    private fun refreshNpc(entity: Entity) {
        val npcStats = entity.stats
        if (npcStats != null) {
            val newMax = Derived.npcMaxHp(npcStats, entity.skills, entity.level)
            entity.hp += (newMax - entity.maxHp).coerceAtLeast(1)
            entity.maxHp = newMax
            val weapon = entity.equipment?.bestWeapon()
            val category = weapon?.let { WeaponCategory.forWeapon(it.archetype) } ?: WeaponCategory.UNARMED
            entity.damage = Derived.npcDamage(
                npcStats, entity.skills, entity.level, weapon,
                entity.proficiencies.value(category),
                weapon?.let { entity.masteries.value(it.uid) } ?: 0
            )
            entity.speed = Derived.npcSpeed(npcStats, entity.skills, entity.speed)
        } else {
            // The old hardcoded shapes learn only their level: a blunt arithmetic.
            entity.maxHp += 3
            entity.hp += 3
            entity.damage += 1
        }
    }

    // ------------------------------------------------------------ sneak

    /** How much light there is to be seen by: the sun's hour, or your torch. */
    private fun lightFactor(): Float = if (outdoor) {
        when (hour) {
            in 6..18 -> 1f
            in 5..6, in 19..20 -> 0.7f
            else -> 0.35f
        }
    } else {
        0.35f + torch * 0.65f
    }

    /** The standing contest: its Awareness against your Stealth, under this light. */
    private fun detectionRadius(entity: Entity): Float {
        val sight = lightFactor() * Derived.detectionScore(entity.stats ?: StatBlock.balanced(), entity.skills)
        return Derived.detectionRadius(sight, Derived.stealthScore(stats, growth, crouched))
    }

    internal fun updateDetection(entity: Entity, dist: Float, dt: Float) {
        val radius = detectionRadius(entity)
        val was = entity.detection
        entity.detection = when {
            dist < radius -> Detection.AWARE
            dist < radius * 1.6f -> Detection.SEARCHING
            dist > 16f -> Detection.UNAWARE
            else -> entity.detection
        }
        if (entity.detection == Detection.AWARE && was != Detection.AWARE) {
            trainNpc(entity, Skill.AWARENESS, 6f)
        }
        // Remaining unseen within earshot is its own lesson.
        if (crouched && dist < 12f && entity.detection == Detection.UNAWARE) {
            learnSkill(Skill.STEALTH, dt * 1.5f)
        }
    }

    /** The eye for the HUD: the worst any nearby creature has made of you. */
    fun detectionState(): DetectionState {
        var worst = DetectionState.NONE
        map.entities.forEach { entity ->
            if (entity.kind != EntityKind.ENEMY || !entity.alive) return@forEach
            if (MapFactory.distance(camera.x, camera.y, entity.x, entity.y) > 14f) return@forEach
            val state = when (entity.detection) {
                Detection.AWARE -> DetectionState.SEEN
                Detection.SEARCHING -> DetectionState.SEARCHING
                Detection.UNAWARE -> DetectionState.HIDDEN
            }
            if (state.ordinal > worst.ordinal) worst = state
        }
        return worst
    }

    /** Weight onto the heels: slower, quieter, harder to see. */
    fun toggleCrouch() {
        crouched = !crouched
        pushLog(if (crouched) "You crouch, weight on your heels, breath small." else "You rise to your full height.")
    }

    /** The board up on a tap, down on the next: the same one-press grammar as the crouch. */
    fun toggleBlock() {
        if (dead) return
        val guard = shield
        if (guard == null) {
            pushLog("You carry no shield to raise.")
            return
        }
        if (equipment.leftClaimed) {
            pushLog("The long arm has both hands; no shield answers.")
            return
        }
        blocking = !blocking
        pushLog(
            if (blocking) "You raise the ${styleRoster.nameFor(guard)} behind your guard."
            else "You lower the ${styleRoster.nameFor(guard)}."
        )
    }

    /** A guard whose board is gone is no guard: the state falls with the shield. */
    private fun validateBlocking() {
        if (blocking && (shield == null || equipment.leftClaimed)) {
            blocking = false
            pushLog("Your guard drops — the shield is gone from your arm.")
        }
    }

    /** Fingers into the pocket of something that cannot see you: Sleight against Awareness. */
    fun pickpocket(): Boolean = interaction.pickpocket()

    /** The kind gods of a domain hear you; the angry ones turn away. */
    private fun domainPiety(domain: String): Int {
        val gods = world.deities.filter { it.domain == domain }
        if (gods.isEmpty()) return 0
        val best = gods.maxOf { reputation.pietyFor(it.id) }
        val worst = gods.minOf { reputation.pietyFor(it.id) }
        return if (best >= 60) best else if (worst <= -40) worst else 0
    }

    fun wardCost(): Int {
        val base = (8 - (stats[Attr.INTELLECT] - StatBlock.BASE) / 2).coerceAtLeast(5)
        return when {
            domainPiety("the pale moon") >= 60 -> (base * 0.6f).roundToInt()
            domainPiety("the pale moon") <= -40 -> base + 4
            else -> base
        }
    }

    fun mendCost(): Int {
        val base = (12 - (stats[Attr.INTELLECT] - StatBlock.BASE) / 2).coerceAtLeast(8)
        return when {
            domainPiety("quiet") >= 60 -> (base * 0.66f).roundToInt()
            domainPiety("quiet") <= -40 -> base + 4
            else -> base
        }
    }

    private fun spawnAmbush() {
        val angle = rng.nextFloat() * 6.28f
        val ex = (camera.x + cos(angle) * 3f).coerceIn(1.5f, map.width - 1.5f)
        val ey = (camera.y + sin(angle) * 3f).coerceIn(1.5f, map.height - 1.5f)
        if (map.isWall(ex, ey)) return
        map.entities += spawnNpc(
            x = ex, y = ey, name = "husk", spriteId = Sprites.HUSK,
            height = 1.0f, baseSpeed = 1.0f, level = 1 + depth + rng.nextInt(2),
            weights = MapFactory.CREATURE_WEIGHTS.getValue("husk")
        )
        hurtFlash = 0.5f
    }

    // ---------------------------------------------------------- the living wild

    private var walkedCells = 0f
    private var nightWarnDay = -1
    private var skyEventDay = -1
    private var lastWeatherKind: WeatherKind? = null
    private var torchWarnDay = -1
    private var fatigueWarnDay = -1

    /** The aurora's strength over this spot, when you stand in the far north. */
    fun auroraStrength(): Float =
        if (!onOverland) 0f else SkyEvents.auroraStrength(
            world.seed, day, 1f - camera.y / OverlandGen.SIZE, weather.cloud
        )

    private fun weatherLine(kind: WeatherKind): String = when (kind) {
        WeatherKind.CLEAR -> "The sky has swept clean."
        WeatherKind.HAZE -> "A high haze thins the light."
        WeatherKind.OVERCAST -> "Cloud has come up from the sea. The stars are lost."
        WeatherKind.RAIN -> "Rain comes rattling down. Even the crows keep under."
    }
    private val sighted = mutableSetOf<Int>()

    /** Beasts of the chronicle slain in the wild: their lairs stand empty. */
    private fun slainBeastIds(): Set<Int> = worldState.slainBeastIds()

    /** The nearest site matching a calling, within a reach of country. */
    private fun nearestSiteOf(match: (Site) -> Boolean, radius: Float): Site? {
        val cx = camera.x / overland.width
        val cy = camera.y / overland.height
        return world.sites.filter(match)
            .minByOrNull { MapFactory.distance(it.x, it.y, cx, cy) }
            ?.takeIf { MapFactory.distance(it.x, it.y, cx, cy) <= radius }
    }

    /** A spot in the open, a few paces out from where you stand, clear of every door. */
    private fun openSpotNear(): Pair<Float, Float>? {
        repeat(12) {
            val angle = rng.nextFloat() * 6.28f
            val dist = 5f + rng.nextFloat() * 4f
            val x = camera.x + cos(angle) * dist
            val y = camera.y + sin(angle) * dist
            val blocked = map.portals.any { MapFactory.distance(x, y, it.x, it.y) < 2.2f }
            if (!map.isWall(x, y) && !blocked) return Pair(x, y)
        }
        return null
    }

    /**
     * The wild keeps its own counsel: rolled as you walk, from the seed, and
     * shaped by the ground — whose lair, whose camp, how far from help.
     */
    internal fun rollEncounter(force: Int? = null) {
        val roll = force ?: rng.nextInt(100)
        val lair = nearestSiteOf({ site ->
            world.beasts.any { it.lairSiteId == site.id && it.alive && it.id !in slainBeastIds() }
        }, 0.16f)
        val camp = nearestSiteOf({ it.kind == SiteKind.CAMP }, 0.12f)
        val gates = nearestSiteOf({ it.isSettlement && !it.ruined }, 0.10f)
        when {
            lair != null && roll < 40 -> spawnRoamingBeast(lair)
            camp != null && roll < 60 -> spawnBanditAmbush(camp)
            gates != null && roll < 85 -> spawnRoadPatrol(gates, roll)
            else -> spawnTraveler()
        }
    }

    /** The chronicle's terror, met where it hunts rather than where it sleeps. */
    private fun spawnRoamingBeast(lair: Site) {
        val beast = world.beasts.firstOrNull {
            it.lairSiteId == lair.id && it.alive && it.id !in slainBeastIds()
        } ?: return
        val spot = openSpotNear() ?: return
        val entity = MapFactory.rollEnemy(
            rng, spot.first, spot.second, 4 + day / 12,
            roster, SiteGen.cultureId(world, lair), geography
        )
        entity.name = beast.name
        entity.beastId = beast.id
        entity.personality = PersonalityBook.dealBeast(
            PersonalityBook.key(world.seed, "beast${beast.id}"), beast.name
        )
        entity.spriteId = Sprites.HOUND
        entity.height = 1.35f
        entity.maxHp = entity.maxHp * 3
        entity.hp = entity.maxHp
        entity.damage += (entity.damage * 0.4f).roundToInt().coerceAtLeast(1)
        map.entities += entity
        pushLog("${beast.name} breaks cover — the chronicle's own terror, out in the open.")
    }

    /** Reavers from the nearest warband, and the road gives you no ground. */
    private fun spawnBanditAmbush(camp: Site) {
        repeat(1 + rng.nextInt(2)) {
            val spot = openSpotNear() ?: return@repeat
            map.entities += spawnNpc(
                x = spot.first, y = spot.second,
                name = world.groupAt(camp.id)?.let { "${it.name} reaver" } ?: "brigand",
                spriteId = Sprites.HUSK, height = 1.0f, baseSpeed = 1.05f,
                level = 2 + day / 20, weights = OUTRIDER_WEIGHTS
            )
        }
        pushLog("Reavers step onto the road out of ${camp.name}'s country.")
    }

    /** Roads near living gates carry watchmen, and their welcome follows your name. */
    private fun spawnRoadPatrol(settlement: Site, roll: Int) {
        val sovereign = world.realmOf(settlement)
        when (reputation.standingFor(sovereign?.id)) {
            in Int.MIN_VALUE..-60 -> {
                if (roll % 2 == 0) {
                    repeat(1 + rng.nextInt(2)) {
                        val spot = openSpotNear() ?: return@repeat
                        map.entities += spawnNpc(
                            x = spot.first, y = spot.second,
                            name = "${sovereign?.name ?: "the hold"} outrider",
                            spriteId = Sprites.HUSK, height = 1.0f, baseSpeed = 1.05f,
                            level = 1 + depth + rng.nextInt(2), weights = OUTRIDER_WEIGHTS
                        )
                    }
                    pushLog("Riders of ${sovereign?.name ?: "the hold"} have found you on the road.")
                } else {
                    pushLog("You keep off the road's crown, and riders of ${sovereign?.name ?: "the hold"} pass by.")
                }
            }
            in 45..Int.MAX_VALUE -> {
                pushLog("A patrol of ${settlement.name} falls in beside you a while and shares the road's news.")
                addRumor(
                    "\"${settlement.name} keeps its roads honest, and its patrols sharp.\"",
                    "a patrol of ${settlement.name}"
                )
                learnSkill(Skill.ETIQUETTE, 4f)
            }
            else -> pushLog("Watchmen of ${settlement.name} mark you from the road and pass on.")
        }
    }

    /** Peddlers and pilgrims: always harmless, always worth the hearing. */
    private fun spawnTraveler() {
        val spot = openSpotNear() ?: return
        val entity = Entity(
            x = spot.first, y = spot.second,
            spriteId = Sprites.PILGRIM, kind = EntityKind.PROP, height = 1.05f,
            name = if (rng.nextBoolean()) "peddler" else "pilgrim",
            traveler = true
        )
        map.entities += entity
        pushLog(
            if (weather.kind == WeatherKind.RAIN) "A ${entity.name} plods past through the rain, hood low and hurrying."
            else "A ${entity.name} walks the road ahead, unhurried."
        )
    }

    // ------------------------------------------------------------------ portals & travel

    fun nearPortal(): Boolean = interaction.nearPortal()

    fun portalPrompt(): String = interaction.portalPrompt()

    fun usePortal() {
        interaction.nearestPortal()?.let { enter(it) }
    }

    /** Take the nearest way: down into the dark, up a stair, into a place, or out under the sky. */
    private fun enter(portal: Portal) {
        if (portal.targetFloor <= OverlandGen.OVERLAND_FLOOR) {
            leaveToOverland()
            return
        }
        if (portal.targetFloor >= SiteGen.BUILDING_FLOOR_BASE) {
            enterBuilding(portal)
            return
        }
        if (portal.targetFloor == 0 && depth >= SiteGen.BUILDING_FLOOR_BASE) {
            leaveBuilding(portal)
            return
        }
        portal.targetSiteId.takeIf { it >= 0 }?.let { id ->
            enterSite(world.site(id))
            return
        }
        val site = world.site(currentSiteId)
        val goingUnder = portal.targetFloor > 0
        depth = portal.targetFloor
        outdoor = !goingUnder
        // A stair is a quarter hour of the world's time, and the world takes it.
        advanceWorld(15f)
        map = sceneFor(site, depth)
        if (!goingUnder) surfaceMap = map
        val spot = map.arrivalSpots.getOrNull(portal.arrivalIndex)
        if (spot != null && !map.isWall(spot.first, spot.second)) {
            camera.x = spot.first
            camera.y = spot.second
        } else {
            camera.x = map.spawnX
            camera.y = map.spawnY
        }
        camera.angle = map.spawnAngle
        if (goingUnder) {
            world.siteOrNull(currentSiteId)?.let { recordDelving(it) }
            val skin = SiteGen.skinFor(SiteGen.cultureId(world, site))
            pushLog(
                if (!portal.down) "You climb the stair up. ${skin.darkLine}"
                else if (depth == 1) "You go down into ${map.title}. ${skin.darkLine}"
                else "You take the stair down. ${skin.darkLine}"
            )
            if (portal.down && depth == SiteGen.floorCount(world, site)) {
                SiteGen.bossFor(world, site).line?.let { pushLog(it) }
            }
        } else {
            pushLog("You come up into the grey light beside ${map.title}.")
            if (!deeds.contains("Climbed out of ${siteName()}")) deeds += "Climbed out of ${siteName()}"
        }
    }

    /** Through the door: an interior of exactly the footprint its walls keep. */
    private fun enterBuilding(portal: Portal) {
        val siteId = portal.targetSiteId.takeIf { it >= 0 } ?: currentSiteId
        val site = world.siteOrNull(siteId) ?: return
        val yard = surfaceMap ?: map.takeIf { it.buildings.isNotEmpty() }
        val index = portal.targetFloor - SiteGen.BUILDING_FLOOR_BASE
        val building = yard?.buildings?.getOrNull(index) ?: return
        surfaceMap = yard
        inBuilding = building
        depth = portal.targetFloor
        outdoor = false
        advanceWorld(2f)
        map = interiorFor(site, index, building)
        map.arrivalSpots.firstOrNull()?.let { spot ->
            camera.x = spot.first
            camera.y = spot.second
        } ?: run {
            camera.x = map.spawnX
            camera.y = map.spawnY
        }
        camera.angle = map.spawnAngle
        pushLog("You step into ${building.name}.")
    }

    /** Back out through the door, into the yard you left, by the door you used. */
    private fun leaveBuilding(portal: Portal) {
        val yard = surfaceMap
        inBuilding = null
        depth = 0
        outdoor = true
        advanceWorld(2f)
        if (yard == null) {
            enterSite(world.site(portal.targetSiteId.takeIf { it >= 0 } ?: currentSiteId))
            return
        }
        map = yard
        // The yard stood empty while you were inside: the world puts its souls back.
        SceneBinder.restore(worldState, currentSiteId, 0, yard)
        val spot = yard.arrivalSpots.getOrNull(portal.arrivalIndex)
        if (spot != null && !yard.isWall(spot.first, spot.second)) {
            camera.x = spot.first
            camera.y = spot.second
        } else {
            camera.x = yard.spawnX
            camera.y = yard.spawnY
        }
        pushLog("You step out into the yard of ${yard.title}.")
    }

    /** A landmark takes you in: the yard, the gate, the doorstep of the place itself. */
    private fun enterSite(site: Site) {
        val firstVisit = site.id !in visitedSites
        currentSiteId = site.id
        onOverland = false
        inBuilding = null
        depth = 0
        outdoor = true
        // Coming in through the gate costs a quarter hour, and the world spends it.
        advanceWorld(15f)
        map = sceneFor(site, 0)
        surfaceMap = map
        camera.x = map.spawnX
        camera.y = map.spawnY
        camera.angle = map.spawnAngle
        torch = (torch + 0.25f).coerceAtMost(1f)
        worldState.discover(site.id, day, minutes, site.name)
        pushLog("You come to ${site.name}.")
        reportWorldChange()
        if (site.kind == SiteKind.CAMP && map.entities.any { it.resident }) {
            pushLog("Folk keep the tents of ${site.name}; their watchfire burns at the heart.")
        }
        if (site.kind == SiteKind.SHRINE && weather.rain > 0.25f) {
            pushLog("The offering bowls brim with rainwater. No one kneels at the stones today.")
        }
        if (firstVisit) {
            deeds += "Stood within ${site.name}"
            if (site.kind in setOf(SiteKind.VAULT, SiteKind.RUIN, SiteKind.BARROW)) {
                recordDelving(site)
            }
        }
        if (site.isSettlement) {
            val regard = reputation.regardFor(site.id)
            val why = reputation.reasonFor(Layer.SETTLEMENT, site.id)
            when {
                regard <= -60 ->
                    pushLog("The gates of ${site.name} are barred against you. ${why ?: ""}".trim())
                regard <= -25 ->
                    pushLog("The gate-watch of ${site.name} knows your face and names it poorly.")
                regard >= 45 ->
                    pushLog(
                        "At the gates of ${site.name} they name you ${regardLabel(regard).lowercase()}. " +
                            (why ?: "")
                    )
            }
        }
    }

    /**
     * Stamp the pending lesser places whose ground you have come near: the
     * continent fills in around the walker, a handful of landmarks at a time.
     */
    private fun stampNearbyLandmarks() {
        if (!onOverland) return
        val bcx = camera.x.toInt() / 10
        val bcy = camera.y.toInt() / 10
        var stamped = 0
        for (gx in bcx - 1..bcx + 1) {
            for (gy in bcy - 1..bcy + 1) {
                val bucket =
                    overland.pendingLandmarks[OverlandGen.pendingKey(gx * 10, gy * 10)] ?: continue
                val iter = bucket.iterator()
                while (iter.hasNext()) {
                    val site = iter.next()
                    val d = MapFactory.distance(
                        site.x * (overland.width - 1), site.y * (overland.height - 1),
                        camera.x, camera.y
                    )
                    if (d > 14f) continue
                    iter.remove()
                    OverlandGen.stampLandmark(overland, site)
                    worldState.discover(site.id, day, minutes, site.name)
                    pushLog("You come upon ${site.name} — a ${site.kind.label}.")
                    if (++stamped >= 8) return
                }
            }
        }
    }

    /** A place's landmark, drawn if it has waited in the pending rolls. */
    private fun ensureLandmark(site: Site) {
        if (site.id in overland.stampedSites) return
        overland.pendingLandmarks[
            OverlandGen.pendingKey(OverlandGen.landmarkX(site), OverlandGen.landmarkY(site))
        ]?.removeAll { it.id == site.id }
        OverlandGen.stampLandmark(overland, site)
    }

    /** A place's landmark on the open ground, revealed as arrival demands it. */
    fun revealLandmark(siteId: Int) {
        world.siteOrNull(siteId)?.let { ensureLandmark(it) }
    }

    /** Out of a place and into the open country again, beside the way you came. */
    private fun leaveToOverland() {
        val site = world.site(currentSiteId)
        onOverland = true
        outdoor = true
        depth = 0
        surfaceMap = null
        inBuilding = null
        map = overland
        ensureLandmark(site)
        val spot = overland.entrySpots[site.id]
        camera.x = spot?.first ?: map.spawnX
        camera.y = spot?.second ?: map.spawnY
        camera.angle = -1.5708f
        pushLog("You take the open ground before ${site.name}.")
    }

    // ------------------------------------------------------------------ bearings

    /** Pins: rumor-named places and the nearest living steads, plus where you have stood. */
    fun bearings(): List<Bearing> {
        val rumorPins = rumors.mapNotNull { rumor ->
            rumor.siteId.takeIf { it >= 0 }
        }.toSet()
        val pins = mutableListOf<Bearing>()
        val stood = mutableListOf<Bearing>()
        val known = mutableListOf<Pair<Site, Float>>()
        val wx = overland.width - 1
        world.sites.forEach { site ->
            when {
                site.id in visitedSites -> stood += bearingTo(site).copy(
                    visited = true,
                    source = worldState.visitedDay(site.id)?.let { walkedAgoLabel(day - it) } ?: "walked"
                )
                site.id in rumorPins -> pins += bearingTo(site).copy(source = "rumor")
                site.isSettlement && !site.ruined -> known += site to MapFactory.distance(
                    site.x * wx, site.y * wx, camera.x, camera.y
                )
            }
        }
        // The roads remember what is near: the closest steads stand first, and a
        // continent's worth never floods the page at once.
        known.sortBy { it.second }
        known.take(60).forEach { (site, _) ->
            pins += bearingTo(site).copy(source = "the roads")
        }
        return pins.sortedBy { it.leagues } + stood.sortedBy { it.leagues }
    }

    private fun bearingTo(site: Site): Bearing {
        val dx = site.x * (overland.width - 1) + 0.5f - camera.x
        val dy = site.y * (overland.height - 1) + 0.5f - camera.y
        val leagues = sqrt(dx * dx + dy * dy) * OverlandGen.LEAGUES_PER_CELL
        // rain slows the leagues: the hours a walk asks swell with the weather
        val walkFactor = if (outdoor) rainPacing(weather.rain).first else 1f
        val settlement = site.isSettlement && !site.ruined
        // word of the trade roads: only for places you have stood in yourself
        val roads = if (settlement && site.id in visitedSites) {
            val flows = history.economy.tradeFlowsOf(site, settlements, world.sites)
            if (flows.isEmpty()) "" else
                "roads: " + flows.take(2).joinToString(" · ") { flow ->
                    val partner = world.siteOrNull(flow.partnerId)?.name ?: "afar"
                    val cargo = cargoWord(flow.cargo)
                    if (flow.incoming) "$cargo from $partner" else "$cargo to $partner"
                }
        } else ""
        return Bearing(
            site = site,
            compass = OverlandGen.windOf(dx, dy),
            leagues = leagues,
            hours = leagues / (2.1f * walkFactor),
            visited = site.id in visitedSites,
            source = "",
            folk = if (settlement) folkOf(site.id) else -1,
            stage = if (settlement) stageAt(site).label else "",
            roads = roads
        )
    }

    /** Where the smoke of the nearest living settlement sits on the sky, on the open road. */
    fun skylineBearing(): Float? {
        if (!onOverland) return null
        val nearest = world.sites
            .filter { it.isSettlement && !it.ruined }
            .minByOrNull {
                MapFactory.distance(
                    it.x * (overland.width - 1), it.y * (overland.height - 1), camera.x, camera.y
                )
            } ?: return null
        val dx = nearest.x * (overland.width - 1) + 0.5f - camera.x
        val dy = nearest.y * (overland.height - 1) + 0.5f - camera.y
        return atan2(dy, dx)
    }

    private fun ageRumors(days: Float) {
        val whole = days.roundToInt()
        if (whole <= 0) return
        for (i in rumors.indices) {
            rumors[i] = rumors[i].copy(daysOld = rumors[i].daysOld + whole)
        }
        rumors.removeAll { it.daysOld > 60 }
    }

    fun revive() {
        dead = false
        vitality = (maxVitality * 0.45f).roundToInt()
        fatigue = (maxFatigue * 0.5f).roundToInt()
        // Eight hours face-down is eight hours the province did not wait for you.
        advanceWorld(8 * 60f)
        if (onOverland) {
            // Die in the open and the nearest living gates take you in.
            val nearest = world.sites
                .filter { it.isSettlement && !it.ruined }
                .minByOrNull {
                    MapFactory.distance(
                        it.x * (overland.width - 1), it.y * (overland.height - 1), camera.x, camera.y
                    )
                } ?: world.site(world.vaultSiteId)
            currentSiteId = nearest.id
            worldState.discover(nearest.id, day, minutes, nearest.name)
            ensureLandmark(nearest)
            camera.x = overland.entrySpots[nearest.id]?.first ?: overland.spawnX
            camera.y = overland.entrySpots[nearest.id]?.second ?: overland.spawnY
            camera.angle = -1.5708f
            torch = 0.6f
            brass = (brass / 2)
            deeds += "Was dragged out of the wild half-dead"
            pushLog("Bearers found you and carried you to ${nearest.name}. Half your brass paid for it.")
            return
        }
        depth = 0
        outdoor = true
        map = sceneFor(world.site(currentSiteId), 0)
        surfaceMap = map
        inBuilding = null
        camera.x = map.spawnX
        camera.y = map.spawnY
        camera.angle = map.spawnAngle
        torch = 0.6f
        brass = (brass / 2)
        deeds += "Was dragged out of ${siteName()} half-dead"
        pushLog("Someone dragged you out and took half your brass for the trouble.")
    }

    fun standingFor(actor: Actor): Int = reputation.standingFor(actor.id)

    fun pushLog(text: String) {
        log += LogLine(text)
    }

    // ------------------------------------------------------------- presentation
    // The engine alone turns its own state into what the screen shows: one
    // immutable HUD snapshot and one fully-assembled render scene, both built
    // here so no composable ever reaches into engine fields.

    /** One whole frame of HUD, ready to publish. Built inside the engine. */
    fun hudSnapshot(): HudState {
        val clock = "$hour:${minute.toString().padStart(2, '0')}"
        return HudState(
            locationTitle = map.title,
            heading = heading,
            depthLabel = when {
                onOverland -> "${terrainLabel()} · ${skyWord()} · $clock"
                outdoor -> "${skyWord()} · $clock"
                else -> "$depth·${(torch * 100).roundToInt()}"
            },
            vitality = vitality,
            maxVitality = maxVitality,
            fatigue = fatigue,
            maxFatigue = maxFatigue,
            magicka = magicka,
            maxMagicka = maxMagicka,
            torch = torch,
            outdoor = outdoor,
            lines = log.takeLast(2).map { line ->
                FadingLine(line.text, (1f - (line.age - 3.5f) / 2.5f).coerceIn(0f, 1f))
            }.filter { it.alpha > 0.02f },
            usePrompt = usePrompt(),
            day = day,
            clock = clock,
            timeOfDay = timeOfDayLabel,
            brass = brass,
            kills = kills,
            explored = (map.exploredFraction() * 100).roundToInt(),
            dead = dead,
            warded = wardTimer > 0f,
            level = growth.level,
            className = klass?.name ?: "",
            crouched = crouched,
            blocking = blocking,
            detection = when (detectionState()) {
                DetectionState.SEEN -> "seen"
                DetectionState.SEARCHING -> "searching"
                DetectionState.HIDDEN -> "hidden"
                DetectionState.NONE -> ""
            },
            load = inventory.weight(),
            capacity = carryCapacity(),
            satchelCount = inventory.all.size,
            rangedLine = rangedHud() ?: "",
            overland = onOverland
        )
    }

    /**
     * The frame the renderer draws, assembled inside the engine: smoke on the
     * sky, meteors under the open heavens, and every easing of the hands.
     */
    fun buildRenderScene(): RenderScene {
        val bearing = skylineBearing()
            ?: atan2(2f - camera.y, (map.width / 2f) - camera.x)
        return RenderScene(
            map = map,
            camera = camera,
            torch = litTorch,
            outdoorLight = skyLight,
            townBearing = bearing,
            townDistance = if (outdoor) 1f else 0f,
            hurtFlash = hurtFlash,
            strikeArc = strikeArc,
            swingPhase = swingPhase,
            groundItems = groundItems,
            heldTorch = holdsTorch,
            heldWeaponSprite = heldWeaponSpriteId,
            heldWeaponTint = heldWeaponTint,
            heldShieldSprite = heldShieldSpriteId,
            heldShieldTint = heldShieldTint,
            shieldRaise = shieldRaise,
            sky = if (outdoor) sky else null,
            clock = animTime,
            dayNumber = day,
            timeOfDay = timeOfDay,
            weather = weather,
            aurora = auroraStrength(),
            meteors = if (outdoor) SkyEvents.meteors(
                world.seed, day, animTime,
                outdoorLight < 0.25f, weather.cloud
            ) else emptyList(),
            projectiles = projectiles
        )
    }

    private fun encodeRumor(rumor: Rumor): String =
        listOf(rumor.text, rumor.source, "${rumor.daysOld}", "${rumor.siteId}", "${rumor.aboutPlayer}")
            .joinToString("\u001F")

    private fun decodeRumor(raw: String): Rumor? {
        val fields = raw.split("\u001F")
        if (fields.size < 5) return null
        return Rumor(
            text = fields[0],
            source = fields[1],
            daysOld = fields[2].toIntOrNull() ?: 0,
            aboutPlayer = fields[4] == "true",
            siteId = fields[3].toIntOrNull() ?: -1
        )
    }

    fun toSaveSlot(): SaveSlot {
        // The self is saved without its worn constants: they are re-laid on waking.
        val baseStats = stats.copy().also { base ->
            constantBonus.forEach { (attr, bonus) -> base.adjust(attr, -bonus) }
        }
        return SaveSlot(
        seed = world.seed,
        day = day,
        minutes = minutes,
        outdoor = outdoor,
        depth = depth,
        x = camera.x,
        y = camera.y,
        angle = camera.angle,
        vitality = vitality,
        fatigue = fatigue,
        magicka = magicka,
        torch = torch,
        kills = kills,
        brass = brass,
        siteId = currentSiteId,
        siteName = siteName(),
        deeds = deeds,
        reputation = reputation.encode(),
        stats = baseStats.encode(),
        classKey = klass?.key ?: "",
        growth = growth.encode(),
        proficiencies = proficiencies.encode(),
        masteries = masteries.encode(),
        magic = MagicSave.encode(knownMagic, schoolProficiencies, spellRegistry),
        magicLedger = magicLedger.encodeDelta(),
        history = history.encode(),
        nextUid = ItemUids.current(),
        inventory = inventory.encode(),
        equipment = equipment.encode(),
        dropped = groundItems.joinToString("\u001E") { it.encode() },
        looted = "",
        onOverland = onOverland,
        settlements = settlements.encode(),
        visited = visitedSites.joinToString("\u001F"),
        rumors = rumors.joinToString("\u001E") { encodeRumor(it) },
        // The province's whole memory rides along: deaths by id, emptied
        // containers, every tracked soul, discoveries, and the world clock.
        worldState = worldState.encode(),
        worldVersion = WORLD_STATE_VERSION
        )
    }
}
