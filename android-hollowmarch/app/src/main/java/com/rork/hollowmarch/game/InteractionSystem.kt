package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.Site
import com.rork.hollowmarch.world.SiteKind
import com.rork.hollowmarch.world.isSettlement
import kotlin.random.Random

/**
 * The player's hand on the world: the USE button's target selection and every
 * way it resolves — a pocket stolen, a dropped thing taken up, a corpse or
 * container emptied, a word with a passing soul, a door used. The scene the
 * door opens stays the engine's; the system only decides which way the hand
 * takes. Systems speak in facts; the engine turns them into words.
 */
class InteractionSystem(private val engine: GameEngine, private val rng: Random) {

    // ------------------------------------------------------------ the USE hand

    /** The nearest dropped thing within arm's reach, or null. */
    fun nearestDrop(): GroundItem? = engine.groundItems
        .filter { MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y) <= 1.6f }
        .minByOrNull { MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y) }

    private fun pickUp(drop: GroundItem) {
        engine.groundItems.remove(drop)
        engine.inventory.add(drop.item)
        engine.emit(GameEvent.Note("You take up the ${engine.styleRoster.nameFor(drop.item)}."))
    }

    /** A living, unwary pocket within fingers' reach — crouching required. */
    private fun pickpocketTarget(): Entity? {
        if (!engine.crouched) return null
        return engine.map.entities
            .filter { it.kind == EntityKind.ENEMY && it.alive && it.detection == Detection.UNAWARE }
            .filter { MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y) <= 1.4f }
            .minByOrNull { MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y) }
    }

    /** What the USE button would do right now, in the chronicler's words. */
    fun usePrompt(): String {
        if (engine.dead) return ""
        pickpocketTarget()?.let { return "Pick the ${it.name}'s pocket" }
        nearestDrop()?.let { return "Take up the ${engine.styleRoster.nameFor(it.item)}" }
        val candidates = mutableListOf<Pair<Float, String>>()
        lootableHere()?.let {
            candidates += Pair(
                MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y), "Search the ${it.name}"
            )
        }
        soulHere()?.let {
            candidates += Pair(
                MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y),
                if (it.resident) "Speak with ${it.name}" else "Speak with the ${it.name}"
            )
        }
        stationHere()?.let {
            candidates += Pair(
                MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y),
                "Study at ${MagicStations.NAME}"
            )
        }
        nearestPortal()?.let {
            candidates += Pair(MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y), it.prompt)
        }
        return candidates.minByOrNull { it.first }?.second ?: ""
    }

    /** The USE hand: whatever stands nearest — pocket, dropped thing, spoils, or the way out. */
    fun interact(): Interact {
        if (engine.dead) return Interact.NONE
        val options = mutableListOf<Triple<Float, Interact, () -> Unit>>()
        pickpocketTarget()?.let { target ->
            options += Triple(
                MapFactory.distance(engine.camera.x, engine.camera.y, target.x, target.y), Interact.PICKPOCKET
            ) { pickpocket() }
        }
        nearestDrop()?.let { drop ->
            options += Triple(
                MapFactory.distance(engine.camera.x, engine.camera.y, drop.x, drop.y), Interact.PICKED
            ) { pickUp(drop) }
        }
        lootableHere()?.let { spoils ->
            options += Triple(
                MapFactory.distance(engine.camera.x, engine.camera.y, spoils.x, spoils.y), Interact.LOOT
            ) { }
        }
        soulHere()?.let { soul ->
            options += Triple(
                MapFactory.distance(engine.camera.x, engine.camera.y, soul.x, soul.y), Interact.TRAVELER
            ) { meetTraveler(soul) }
        }
        nearestPortal()?.let { portal ->
            options += Triple(
                MapFactory.distance(engine.camera.x, engine.camera.y, portal.x, portal.y), Interact.PORTAL
            ) { engine.usePortal() }
        }
        stationHere()?.let { station ->
            options += Triple(
                MapFactory.distance(engine.camera.x, engine.camera.y, station.x, station.y),
                Interact.STATION
            ) { engine.useSpellcraftingStation() }
        }
        val nearest = options.minByOrNull { it.first } ?: return Interact.NONE
        nearest.third()
        return nearest.second
    }

    // ------------------------------------------------------------ loot

    private fun markLooted(entity: Entity) {
        if (entity.container && entity.loot.isEmpty() && entity.lootBrass == 0) {
            val id = entity.persistId
            if (id.isNotBlank() && !engine.worldState.isEmptied(id)) {
                engine.worldState.markEmptied(id, engine.minutes, "The ${entity.name} was emptied")
                stolenFrom(entity)
            }
        }
    }

    /** Lifting a keeper's things costs the place's regard — once per chest. */
    private fun stolenFrom(entity: Entity) {
        val site = engine.world.siteOrNull(engine.currentSiteId) ?: return
        if (!site.isSettlement || !entity.container) return
        engine.reputation.adjust(Layer.SETTLEMENT, site.id, -2, "Stole from the ${entity.name}")
        engine.emit(GameEvent.Note("The ${entity.name} will be missed, and the loss remembered."))
    }

    /** The nearest thing with spoils: a fresh corpse or a container not yet emptied. */
    fun lootableHere(): Entity? {
        var best: Entity? = null
        var bestDist = 1.7f
        engine.map.entities.forEach { entity ->
            val lootable = (entity.kind == EntityKind.ENEMY && !entity.alive || entity.container) &&
                (entity.loot.isNotEmpty() || entity.lootBrass > 0 || entity.equipment?.isEmpty() == false)
            if (!lootable) return@forEach
            val d = MapFactory.distance(engine.camera.x, engine.camera.y, entity.x, entity.y)
            if (d < bestDist) {
                bestDist = d
                best = entity
            }
        }
        return best
    }

    /** Strip one worn thing from a corpse; it goes straight to the satchel. */
    fun takeEquipped(entity: Entity, slot: WearSlot) {
        val item = entity.equipment?.unequip(slot) ?: return
        engine.inventory.add(item)
        engine.emit(GameEvent.Note("You strip the ${engine.styleRoster.nameFor(item)} from the ${entity.name}."))
    }

    /** Lift one thing from a corpse or container; brass rides along when it is the last. */
    fun takeItem(entity: Entity, item: Item) {
        if (!entity.loot.remove(item)) return
        engine.inventory.add(item)
        engine.emit(GameEvent.Note("You lift the ${engine.styleRoster.nameFor(item)}."))
        if (entity.loot.isEmpty() && entity.lootBrass > 0) {
            engine.brass += entity.lootBrass
            engine.emit(GameEvent.Note("You pry ${entity.lootBrass} brass from beneath it."))
            entity.lootBrass = 0
        }
        markLooted(entity)
    }

    /** Empty a corpse or container of everything it holds — worn things first — coins included. */
    fun takeAll(entity: Entity): Int {
        val hasGear = entity.equipment?.isEmpty() == false
        if (entity.loot.isEmpty() && !hasGear && entity.lootBrass == 0) return 0
        val gained = entity.lootBrass
        var taken = 0
        // The dead are stripped before they are emptied.
        if (hasGear) {
            WearSlot.entries.forEach { slot ->
                entity.equipment?.unequip(slot)?.let {
                    engine.inventory.add(it)
                    taken++
                }
            }
        }
        while (entity.loot.isNotEmpty()) {
            engine.inventory.add(entity.loot.removeAt(entity.loot.size - 1))
            taken++
        }
        entity.lootBrass = 0
        engine.brass += gained
        engine.emit(
            GameEvent.Note(
                "You empty the ${entity.name}: $taken ${if (taken == 1) "thing" else "things"}" +
                    if (gained > 0) " and $gained brass." else "."
            )
        )
        markLooted(entity)
        return taken
    }

    /** Fingers into the pocket of something that cannot see you: Sleight against Awareness. */
    fun pickpocket(): Boolean {
        if (engine.dead) return false
        if (!engine.crouched) {
            engine.emit(GameEvent.Note("You cannot pick a pocket standing up."))
            return false
        }
        val target = pickpocketTarget()
        if (target == null) {
            engine.emit(GameEvent.Note("No unwary pocket within reach."))
            return false
        }
        engine.learnSkill(Skill.SLEIGHT_OF_HAND, 4f)
        val chance = Derived.pickpocketChance(
            engine.growth.value(Skill.SLEIGHT_OF_HAND),
            target.skills.value(Skill.AWARENESS)
        )
        return if (rng.nextInt(100) < chance) {
            val coins = 2 + rng.nextInt(5) + target.level
            engine.brass += coins
            engine.world.siteOrNull(engine.currentSiteId)
                ?.takeIf { it.isSettlement }
                ?.let { engine.reputation.adjust(Layer.SETTLEMENT, it.id, -1, "Picked a pocket") }
            engine.emit(GameEvent.Note("Your fingers find $coins brass in the ${target.name}'s pocket. It never stirs."))
            target.loot.firstOrNull { it.archetype.slot == ItemSlot.TRINKET }?.let { trinket ->
                target.loot.remove(trinket)
                engine.inventory.add(trinket)
                engine.emit(GameEvent.Note("And something else besides: the ${engine.styleRoster.nameFor(trinket)}."))
            }
            engine.learnSkill(Skill.SLEIGHT_OF_HAND, 6f)
            true
        } else {
            target.detection = Detection.AWARE
            engine.trainNpc(target, Skill.AWARENESS, 8f)
            engine.emit(GameEvent.Note("The ${target.name} starts — it felt your fingers. It knows you now."))
            false
        }
    }

    // ------------------------------------------------------------ souls met

    /** A soul within a word's reach: residents at home and the road's travelers. */
    private fun soulHere(): Entity? = engine.map.entities
        .filter { it.traveler || (it.resident && it.alive) }
        .filter { MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y) <= 1.7f }
        .minByOrNull { MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y) }

    /** The road's small courtesy: news for news, and something for the walk. */
    private fun meetTraveler(traveler: Entity) {
        if (traveler.resident) {
            meetResident(traveler)
            return
        }
        engine.map.entities.remove(traveler)
        engine.learnSkill(Skill.ETIQUETTE, 3f)
        engine.learnSkill(Skill.SURVIVAL, 2f)
        // The road has heard your name already, and it grades its news accordingly.
        when (travelerTone(engine.reputation.regardFor(engine.currentSiteId))) {
            TravelerTone.GRUDGING -> {
                engine.addRumor(
                    "\"${engine.world.sites.random(rng).name} wants no talk with the likes of you.\"",
                    "a wary ${traveler.name}",
                    daysOld = 4 + rng.nextInt(10)
                )
                engine.emit(GameEvent.Note("The ${traveler.name} marks your face, says little, and keeps a hand near a knife."))
                return
            }
            TravelerTone.RICH -> {
                val unvisited = engine.world.sites.filter { it.id !in engine.visitedSites }
                if (unvisited.isNotEmpty() && rng.nextInt(3) < 2) {
                    val place = unvisited.random(rng)
                    engine.addRumor(
                        "\"${travelerLine(place)} ${travelerFlourish(place)}\"",
                        "a ${traveler.name} on the road",
                        place.id
                    )
                    engine.emit(GameEvent.Note("The ${traveler.name} talks of ${place.name} at length, and the bearing of it."))
                } else if (engine.sky.constellations.isNotEmpty()) {
                    val con = engine.sky.constellations.random(rng)
                    engine.addRumor("\"${con.lore}\"", "a ${traveler.name} on the road")
                    engine.emit(GameEvent.Note("The ${traveler.name} points out ${con.name} where it stands, and tells its story well."))
                } else {
                    engine.addRumor(
                        "\"${engine.world.sites.random(rng).name} saw a hard year. Everyone says so.\"",
                        "a ${traveler.name} on the road"
                    )
                    engine.emit(GameEvent.Note("The ${traveler.name} swaps the road's news and walks on."))
                }
            }
            TravelerTone.PLAIN -> {
                val unvisited = engine.world.sites.filter { it.id !in engine.visitedSites }
                if (unvisited.isNotEmpty() && rng.nextInt(3) < 2) {
                    val place = unvisited.random(rng)
                    engine.addRumor("\"${travelerLine(place)}\"", "a ${traveler.name} on the road", place.id)
                    engine.emit(GameEvent.Note("The ${traveler.name} names ${place.name} and the bearing of it."))
                } else if (rng.nextInt(4) == 0 && engine.sky.constellations.isNotEmpty()) {
                    // the road reads the sky too: lore of the constellations rides with it
                    val con = engine.sky.constellations.random(rng)
                    engine.addRumor("\"${con.lore}\"", "a ${traveler.name} on the road")
                    engine.emit(GameEvent.Note("The ${traveler.name} points out ${con.name} where it stands."))
                } else {
                    engine.addRumor(
                        "\"${engine.world.sites.random(rng).name} saw a hard year. Everyone says so.\"",
                        "a ${traveler.name} on the road"
                    )
                    engine.emit(GameEvent.Note("The ${traveler.name} swaps the road's news and walks on."))
                }
            }
        }
        // the road talks about the weather as much as anything
        if (engine.weather.kind == WeatherKind.RAIN && rng.nextInt(2) == 0) {
            engine.addRumor(
                "\"No one camps in this. Every fire tonight is a wet story.\"",
                "a soaked ${traveler.name} on the road"
            )
        }
        if (rng.nextBoolean()) {
            engine.inventory.add(Item(ItemArchetype.REMEDY, Material.VERDIGRIS))
            engine.emit(GameEvent.Note("A remedy is pressed into your hand for the sharing."))
        } else {
            engine.brass += 2 + rng.nextInt(4)
            engine.emit(GameEvent.Note("A few brass change hands for the news you carry."))
        }
    }

    /** The keeper's word: the folk of the place, and a rumor for the road. */
    private fun meetResident(soul: Entity) {
        engine.learnSkill(Skill.ETIQUETTE, 3f)
        val site = engine.world.siteOrNull(engine.currentSiteId)
        val folk = site?.let { engine.settlements.folkOf(it) } ?: 0
        val stage = site?.let { engine.stageAt(it).label } ?: "stead"
        engine.emit(
            GameEvent.Note(
                "${soul.name} gives you the time of day. \"" +
                    "We were $folk at the last turn of the year. A $stage keeps its doors shut after dark.\""
            )
        )
        val place = engine.world.sites.filter { !it.ruined }.randomOrNull(rng) ?: return
        engine.addRumor("\"${travelerLine(place)}\"", "${soul.name} of ${site?.name}", place.id)
        if (rng.nextInt(3) == 0) {
            engine.brass += 1 + rng.nextInt(3)
            engine.emit(GameEvent.Note("A little brass for the talk."))
        }
    }

    /** The extra word a welcome traveler spends on a place: richer news for the fondly regarded. */
    private fun travelerFlourish(site: Site): String = when (rng.nextInt(3)) {
        0 -> "They keep a good fire there, and better talk."
        1 -> "Word is the roads to it are watched and safely walked this season."
        else -> "The wells are sweet and the lofts are warm, for now."
    }

    private fun travelerLine(site: Site): String = when (site.kind) {
        SiteKind.VAULT -> "the sealed door of ${site.name} keeps its brass nails yet, if the hills have not swallowed it"
        SiteKind.BARROW -> "a cairn marks ${site.name}, and the dead beneath keep their own counsel"
        SiteKind.RUIN -> "${site.name} is fallen walls and cellars now; folk say the old keepers never left"
        SiteKind.CAMP -> "a warband keeps its fires at ${site.name}; walk wide or walk armed"
        SiteKind.SHRINE -> "the stones still stand at ${site.name}; leave a coin and pass quietly"
        else -> "${site.name} stands on the road still, and its wells are sweet this year"
    }

    // ------------------------------------------------------------ the way out

    /** The spellcrafting bench within hand's reach, when the place keeps one. */
    private fun stationHere(): Entity? = engine.map.entities
        .filter { it.kind == EntityKind.PROP && it.name == MagicStations.NAME }
        .filter { MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y) <= 1.7f }
        .minByOrNull { MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y) }

    internal fun nearestPortal(): Portal? {
        var best: Portal? = null
        var bestDist = 1.9f
        engine.map.portals.forEach { portal ->
            val d = MapFactory.distance(engine.camera.x, engine.camera.y, portal.x, portal.y)
            // a door answers only at its own threshold, a hand's reach from the wood;
            // the road waits until you stand on it
            if (d < bestDist && (portal.door || d < 1.9f)) {
                bestDist = d
                best = portal
            }
        }
        return best
    }

    fun nearPortal(): Boolean = nearestPortal() != null

    fun portalPrompt(): String = nearestPortal()?.prompt ?: ""
}
