package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.isSettlement
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The settlement's own life: the named souls keep their trade's hours — out to
 * work by their own dawn, home through their doors by dusk — and powers that
 * rate you Blood-owed send riders into the wilds near their holdings.
 *
 * State stays where it lives: the loaded map's entities and the world's memory
 * (WorldState) remain the one authority; this system only moves souls through
 * them and writes what it sees back. Nothing is said to the player here —
 * anything worth saying is emitted as a GameEvent for the engine to translate.
 *
 * RNG: the patrols draw from the engine's own seeded stream (passed in, the
 * same instance the other systems share); each soul's schedule, post, and
 * free-time heart keep their own per-key seeded generators, exactly as before.
 */
class SettlementSystem(private val engine: GameEngine, private val rng: Random) {

    /** Powers that rate you Blood-owed send riders; the next riders are due in this long. */
    private var patrolTimer = 15f

    /** Each loaded soul's own day, dealt once and kept while the scene stands. */
    private val schedules = mutableMapOf<String, WorkSchedule>()

    /** One step of settlement life: the residents first, then the road's riders. */
    fun update(dt: Float) {
        updateResidents(dt)
        updatePatrols(dt)
    }

    private fun scheduleOf(entity: Entity): WorkSchedule {
        val key = entity.persistId.ifBlank { entity.name }
        return schedules.getOrPut(key) {
            WorkSchedule.forRole(entity.role, WorkSchedule.key(engine.world.seed, key), entity.personality)
        }
    }

    /**
     * The named souls: each keeps its trade's own hours — out to its work by
     * its own dawn, home through its door by its own dusk, asleep by the hour
     * the streets empty. The homeless keep to the heart of the place.
     */
    private fun updateResidents(dt: Float) {
        val map = engine.map
        if (map.buildings.isEmpty()) return
        val hour = engine.hour
        val minutes = engine.minutes
        val night = hour >= 20 || hour < 6
        val gone = mutableListOf<Entity>()
        map.entities.forEach { entity ->
            if (!entity.resident || !entity.alive) return@forEach
            if (night && entity.homeBuilding >= 0) {
                val home = map.buildings.getOrNull(entity.homeBuilding) ?: return@forEach
                val dx = home.doorX + 0.5f - entity.x
                val dy = home.doorY + 0.5f - entity.y
                if (dx * dx + dy * dy < 5.3f) {
                    gone += entity
                    setActivity(entity, "asleep behind a barred door")
                    return@forEach
                }
                stepSoul(entity, home.doorX + 0.5f, home.doorY + 0.5f, dt, 0.7f)
            } else {
                val schedule = scheduleOf(entity)
                val hourOfDay = hour + (minutes % 60f) / 60f
                val block = schedule.blockAt(hourOfDay)
                var duty = block.duty
                var activity = schedule.activityAt(hourOfDay, entity.homeBuilding < 0)
                // a free hour goes where the heart pulls it; the trade's own hours stand
                entity.personality?.takeIf { !block.isSleep }?.let { heart ->
                    val soulKey = entity.persistId.ifBlank { entity.name }
                    val decideRng = Random(
                        engine.world.seed * 104729L + soulKey.hashCode() * 31L +
                            (minutes / 1440f).toLong() * 61L + hourOfDay.toInt() * 7L
                    )
                    val chosen = heart.freeTimeDecision(
                        hourOfDay, block.duty,
                        ROLES.byName(entity.role)?.let { workDuty(it.workplace) },
                        decideRng
                    )
                    if (chosen != block.duty) {
                        duty = chosen
                        activity = heart.freeTimeActivity(chosen, entity.role)
                    }
                }
                val target = resolveDuty(entity, duty)
                if (MapFactory.distance(entity.x, entity.y, target.first, target.second) > 0.7f) {
                    stepSoul(entity, target.first, target.second, dt, 0.55f)
                } else {
                    // at its post: a slow, small sway around the spot
                    if (entity.wanderPhase <= 0f) {
                        entity.wanderPhase = 3f + rng.nextFloat() * 4f
                        val ang = rng.nextFloat() * 6.28f
                        val r = rng.nextFloat() * 0.8f
                        entity.wanderX = target.first + cos(ang) * r
                        entity.wanderY = target.second + sin(ang) * r
                    } else {
                        entity.wanderPhase -= dt
                    }
                    stepSoul(entity, entity.wanderX, entity.wanderY, dt, 0.3f)
                }
                setActivity(entity, activity)
            }
        }
        if (gone.isNotEmpty()) map.entities.removeAll(gone.toSet())
        syncSouls()
    }

    /** Where a block of the day sends a soul: the map answers with a real place. */
    private fun resolveDuty(entity: Entity, duty: Duty): Pair<Float, Float> {
        val key = entity.persistId.ifBlank { entity.name }
        return when (duty) {
            Duty.HOME -> homeSpot(entity) ?: heartSpot(key)
            Duty.WORK -> workSpot(entity, key)
            Duty.WELL -> heartSpot(key)
            Duty.MARKET -> marketSpot(key)
            Duty.TAVERN -> kindSpot("tavern") ?: heartSpot(key)
            Duty.TEMPLE -> kindSpot("temple") ?: heartSpot(key)
            Duty.GATE -> Pair(engine.map.spawnX, engine.map.spawnY)
            Duty.ROAD -> roadSpot()
            Duty.FIELDS -> fieldSpot(key)
            Duty.WATER -> waterSpot(key)
        }
    }

    private fun homeSpot(entity: Entity): Pair<Float, Float>? {
        val building = engine.map.buildings.getOrNull(entity.homeBuilding) ?: return null
        return engine.map.arrivalSpots.getOrNull(entity.homeBuilding)
            ?: Pair(building.doorX + 0.5f, building.doorY + 0.5f)
    }

    /** The trade's own workplace: its kept door, a house of its kind, or the land itself. */
    private fun workSpot(entity: Entity, key: String): Pair<Float, Float> {
        val map = engine.map
        map.arrivalSpots.getOrNull(entity.workBuilding)?.let { return it }
        val workplace = ROLES.byName(entity.role)?.workplace
        val kindIdx = when (workplace) {
            Workplace.TAVERN -> map.buildings.indexOfFirst { it.kind == "tavern" }
            Workplace.TEMPLE -> map.buildings.indexOfFirst { it.kind == "temple" }
            Workplace.KEEP -> map.buildings.indexOfFirst { it.kind == "keep" || it.kind == "citadel" }
            Workplace.GUILD -> map.buildings.indexOfFirst { it.kind == "guild hall" }
            else -> -1
        }
        if (kindIdx >= 0) map.arrivalSpots.getOrNull(kindIdx)?.let { return it }
        return when (workplace) {
            Workplace.FIELDS -> fieldSpot(key)
            Workplace.WATER -> waterSpot(key)
            Workplace.GATE -> Pair(map.spawnX, map.spawnY)
            Workplace.MARKET -> marketSpot(key)
            Workplace.WELL -> heartSpot(key)
            else -> homeSpot(entity) ?: heartSpot(key)
        }
    }

    private fun kindSpot(kind: String): Pair<Float, Float>? {
        val idx = engine.map.buildings.indexOfFirst { it.kind == kind }
        return if (idx >= 0) engine.map.arrivalSpots.getOrNull(idx) else null
    }

    private fun heartSpot(key: String): Pair<Float, Float> {
        val rng = Random(WorkSchedule.key(engine.world.seed, key))
        val ang = rng.nextFloat() * 6.28f
        val r = rng.nextFloat() * 1.4f
        return probeOpen(engine.map.width / 2f + cos(ang) * r, engine.map.height / 2f + sin(ang) * r)
    }

    private fun marketSpot(key: String): Pair<Float, Float> {
        val rng = Random(WorkSchedule.key(engine.world.seed, key) * 3L + 7L)
        val ang = rng.nextFloat() * 6.28f
        val r = 2.2f + rng.nextFloat() * 1.4f
        return probeOpen(engine.map.width / 2f + cos(ang) * r, engine.map.height / 2f + sin(ang) * r)
    }

    private fun fieldSpot(key: String): Pair<Float, Float> = edgeSpot(key, 0.36f)

    private fun waterSpot(key: String): Pair<Float, Float> = edgeSpot(key, 0.45f)

    /** A spot out toward the settlement's edge, at its own fixed bearing. */
    private fun edgeSpot(key: String, reach: Float): Pair<Float, Float> {
        val rng = Random(WorkSchedule.key(engine.world.seed, key) * 5L + 11L)
        val ang = rng.nextFloat() * 6.28f
        val r = minOf(engine.map.width, engine.map.height) * reach
        return probeOpen(engine.map.width / 2f + cos(ang) * r, engine.map.height / 2f + sin(ang) * r)
    }

    private fun roadSpot(): Pair<Float, Float> = Pair(
        (engine.map.spawnX + engine.map.width / 2f) / 2f,
        (engine.map.spawnY + engine.map.height / 2f) / 2f
    )

    /** The nearest open ground to a point, walking inward; walls never hold a soul. */
    private fun probeOpen(x: Float, y: Float): Pair<Float, Float> {
        val map = engine.map
        if (!map.isWall(x, y)) return Pair(x, y)
        val cx = map.width / 2f
        val cy = map.height / 2f
        val dx = cx - x
        val dy = cy - y
        val len = maxOf(sqrt(dx * dx + dy * dy), 0.001f)
        var px = x
        var py = y
        repeat(20) {
            px += dx / len * 0.8f
            py += dy / len * 0.8f
            if (!map.isWall(px, py)) return Pair(px, py)
        }
        return Pair(cx, cy)
    }

    /** What a soul is doing, written into the world's memory when it changes. */
    private fun setActivity(entity: Entity, text: String) {
        if (entity.persistId.isBlank()) return
        val record = engine.worldState.npc(entity.persistId) ?: return
        if (record.activity != text) record.activity = text
    }

    /**
     * Write the souls of the loaded scene back into the world's memory, so that
     * where they stand now is where they stand when the scene is gone. The
     * loaded map is a view; this keeps the authority in step with it.
     */
    private fun syncSouls() {
        engine.map.entities.forEach { entity ->
            if (!entity.resident || entity.persistId.isBlank()) return@forEach
            val record = engine.worldState.npc(entity.persistId) ?: return@forEach
            record.x = entity.x
            record.y = entity.y
            record.alive = entity.alive
            // a trade dealt at generation is the record's to keep, too
            if (record.role.isBlank() && entity.role.isNotBlank()) {
                record.role = entity.role
                record.workBuilding = entity.workBuilding
            }
            if (record.personality == null && entity.personality != null) {
                record.personality = entity.personality
            }
        }
    }

    private fun stepSoul(entity: Entity, tx: Float, ty: Float, dt: Float, speed: Float) {
        val map = engine.map
        val dx = tx - entity.x
        val dy = ty - entity.y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 0.1f) return
        val nx = entity.x + dx / dist * speed * dt
        val ny = entity.y + dy / dist * speed * dt
        if (!map.isWall(nx, entity.y) && !map.doorwayAt(nx, entity.y)) entity.x = nx
        if (!map.isWall(entity.x, ny) && !map.doorwayAt(entity.x, ny)) entity.y = ny
    }

    /** Realms that rate you Blood-owed send riders into the wilds near their holdings. */
    private fun updatePatrols(dt: Float) {
        if (!engine.outdoor || engine.onOverland || engine.dead) return
        patrolTimer -= dt
        if (patrolTimer > 0f) return
        patrolTimer = 25f + rng.nextFloat() * 35f
        val here = engine.world.siteOrNull(engine.currentSiteId) ?: return
        // Outlaws need no pretext; only realms answer with riders.
        val hunters = engine.world.realms.filter { realm ->
            engine.reputation.standingFor(realm.id) <= -60 && engine.world.sites.any { site ->
                site.sovereignRealmId == realm.id && site.isSettlement &&
                    MapFactory.distance(site.x, site.y, here.x, here.y) <= 2.2f
            }
        }
        if (hunters.isEmpty() || rng.nextInt(3) != 0) return
        val realm = hunters.random(rng)
        repeat(1 + rng.nextInt(2)) {
            val angle = rng.nextFloat() * 6.28f
            val ex = (engine.camera.x + cos(angle) * 4f).coerceIn(1.5f, engine.map.width - 1.5f)
            val ey = (engine.camera.y + sin(angle) * 4f).coerceIn(1.5f, engine.map.height - 1.5f)
            if (!engine.map.isWall(ex, ey)) {
                engine.map.entities += engine.spawnNpc(
                    x = ex, y = ey, name = "${realm.name} outrider", spriteId = Sprites.HUSK,
                    height = 1.0f, baseSpeed = 1.05f, level = 1 + engine.depth + rng.nextInt(2),
                    weights = engine.OUTRIDER_WEIGHTS,
                    personality = PersonalityBook.dealWatch(rng.nextLong(), warband = true)
                )
            }
        }
        engine.emit(GameEvent.Note("Riders of ${realm.name} have found you on the road."))
    }
}
