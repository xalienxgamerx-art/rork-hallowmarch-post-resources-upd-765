package com.rork.hollowmarch.game

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The marksmanship hand: the arm's phase between tap and loose, what the
 * powder arms hold charged, and the honest arithmetic of every shot that
 * leaves a string or barrel — the delver's and the province archers' alike.
 * It decides and it launches; the flight itself belongs to ProjectileSystem.
 */
class RangedCombatSystem(private val engine: GameEngine, private val rng: Random) {

    var phase: RangedPhaseKind = RangedPhaseKind.IDLE
        private set
    var drawFraction: Float = 0f
        private set
    var muzzleFlash: Float = 0f
        private set
    private var rangedTimer: Float = 0f

    /** Loaded shots per piece, by its smith's mark: cocked bolts, charged barrels. */
    private val chambers = HashMap<Int, Int>()

    /** The very pieces the chambers hold — their make decides the harm. */
    private val chambered = HashMap<Int, Item>()

    /** The arm in the right hand, when that arm is of the marksmanship craft. */
    fun heldRanged(): Item? =
        engine.equipment.weaponIn(Hand.RIGHT)?.takeIf { Ranged.isRanged(it.archetype) }

    /** How many shots the piece holds cocked or charged right now. */
    fun loadedShots(weapon: Item): Int = chambers[weapon.uid] ?: 0

    /** The first bundle in the satchel this arm will feed on, or null. */
    private fun firstAmmoFor(weapon: Item): Item? =
        engine.inventory.all.firstOrNull { Ranged.accepts(weapon, it) && it.count > 0 }

    /** The marksmanship hand's grammar: draw, loose, cock, charge, throw — one tap at a time. */
    fun tap(weapon: Item) {
        when (phase) {
            RangedPhaseKind.DRAW -> {
                loose(weapon); return
            }
            RangedPhaseKind.RELOAD -> return
            RangedPhaseKind.IDLE -> {}
        }
        // The arm answers no faster than its recovery allows.
        if (engine.strikeCooldown > 0f) return
        when (Ranged.formOf(weapon).kind) {
            RangedKind.BOW, RangedKind.SLING -> {
                if (firstAmmoFor(weapon) == null) {
                    engine.emit(GameEvent.Note("Your quiver is bare. The ${engine.styleRoster.nameFor(weapon)} wants shot."))
                    return
                }
                phase = RangedPhaseKind.DRAW
                rangedTimer = Ranged.cycleSeconds(weapon, RangedPhaseKind.DRAW)
                drawFraction = 0f
                engine.drainFatigue(1f)
            }
            RangedKind.THROWN -> throwWeapon(weapon)
            RangedKind.CROSSBOW, RangedKind.FIREARM, RangedKind.HAND_CANNON -> {
                if (loadedShots(weapon) <= 0) beginRangedReload(weapon) else fireLoaded(weapon)
            }
        }
    }

    /** A bow or sling drawn: the second tap cuts the string with the draw as it stands. */
    private fun loose(weapon: Item) {
        val stack = firstAmmoFor(weapon)
        if (stack == null) {
            phase = RangedPhaseKind.IDLE
            engine.emit(GameEvent.Note("Your quiver is bare; the draw comes to nothing."))
            return
        }
        engine.inventory.remove(stack, 1)
        val shot = stack.copy(count = 1)
        val draw = drawFraction
        phase = RangedPhaseKind.IDLE
        launchProjectile(weapon, shot, draw)
        engine.beginSwingArc(Ranged.recoverySeconds(weapon) * (0.8f + 0.4f * (1f - draw)))
        engine.drainFatigue(2f)
        engine.learnSkill(Skill.MARKSMANSHIP, 0.4f + 0.8f * draw)
        engine.weaponPractice(weapon, 0.5f, 0.25f)
    }

    /** A steel taken from the hand and spent: the last of a stack empties the grip. */
    private fun throwWeapon(weapon: Item) {
        val held = engine.equipment.weaponIn(Hand.RIGHT) ?: return
        engine.equipment.unequip(WearSlot.RIGHT_HAND)
        if (held.count > 1) engine.equipment.equip(held.copy(count = held.count - 1), Hand.RIGHT)
        launchProjectile(held, held.copy(count = 1), 1f)
        engine.beginSwingArc(Ranged.recoverySeconds(held))
        engine.drainFatigue(2.5f)
        engine.learnSkill(Skill.MARKSMANSHIP, 0.8f)
        engine.weaponPractice(held, 0.5f, 0.25f)
        if (held.count <= 1) {
            engine.emit(GameEvent.Note("Your last ${engine.styleRoster.nameFor(held)} leaves your hand."))
        }
    }

    /** The start of a cock or a charge; the shot is taken from the satchel when it completes. */
    private fun beginRangedReload(weapon: Item) {
        if (firstAmmoFor(weapon) == null) {
            val want = Ranged.ammoOf(Ranged.formOf(weapon).kind).firstOrNull()?.label ?: "shot"
            engine.emit(GameEvent.Note("You reach for a $want and find none. The ${engine.styleRoster.nameFor(weapon)} stays slack."))
            return
        }
        phase = RangedPhaseKind.RELOAD
        rangedTimer = Ranged.cycleSeconds(weapon, RangedPhaseKind.RELOAD)
    }

    /** The phase clock: the draw filling, the cock or charge completing. */
    fun update(dt: Float) {
        muzzleFlash = (muzzleFlash - dt * 2.6f).coerceAtLeast(0f)
        when (phase) {
            RangedPhaseKind.DRAW -> {
                val weapon = heldRanged()
                if (weapon == null) {
                    phase = RangedPhaseKind.IDLE
                    return
                }
                val total = Ranged.cycleSeconds(weapon, RangedPhaseKind.DRAW)
                drawFraction = (drawFraction + dt / total).coerceIn(0f, 1f)
                // Holding at full strain tires the arm slowly.
                if (drawFraction >= 1f) engine.drainFatigue(dt * 2f)
            }
            RangedPhaseKind.RELOAD -> {
                rangedTimer -= dt
                if (rangedTimer <= 0f) {
                    phase = RangedPhaseKind.IDLE
                    val weapon = heldRanged() ?: return
                    val stack = firstAmmoFor(weapon) ?: return
                    // Every barrel takes its own ball, as far as the satchel allows.
                    val want = Ranged.capacity(weapon).coerceAtLeast(1)
                    val have = minOf(want, stack.count)
                    engine.inventory.remove(stack, have)
                    chambered[weapon.uid] = stack.copy(count = have)
                    chambers[weapon.uid] = have
                }
            }
            RangedPhaseKind.IDLE -> {}
        }
    }

    /** A cocked bolt or a charged barrel speaks: the chamber empties, the shot flies. */
    private fun fireLoaded(weapon: Item) {
        val held = chambered[weapon.uid]
        val loaded = chambers[weapon.uid] ?: 0
        if (loaded <= 0 || held == null) {
            beginRangedReload(weapon)
            return
        }
        val shot = held.copy(count = 1)
        val left = held.count - 1
        if (left <= 0) chambered.remove(weapon.uid) else chambered[weapon.uid] = held.copy(count = left)
        chambers[weapon.uid] = loaded - 1
        launchProjectile(weapon, shot, 1f)
        engine.beginSwingArc(Ranged.recoverySeconds(weapon))
        engine.drainFatigue(2f)
        engine.learnSkill(Skill.MARKSMANSHIP, 0.5f)
        engine.weaponPractice(weapon, 0.5f, 0.25f)
        if (Ranged.isGunpowder(weapon)) {
            muzzleFlash = 1f
            // The flash and the thunder harm nothing themselves — only the ball does.
            engine.map.entities.forEach { entity ->
                if (entity.kind == EntityKind.ENEMY && entity.alive &&
                    entity.detection == Detection.UNAWARE &&
                    MapFactory.distance(entity.x, entity.y, engine.camera.x, engine.camera.y) < 16f
                ) entity.detection = Detection.SEARCHING
            }
        }
        engine.emit(
            GameEvent.ProjectileFired(
                engine.styleRoster.nameFor(weapon),
                Ranged.isGunpowder(weapon),
                loaded - 1 <= 0,
                Ranged.formOf(weapon).kind == RangedKind.CROSSBOW
            )
        )
    }

    /** One shot into the air: honest speed from the arm, honest doubt from the hand. */
    private fun launchProjectile(weapon: Item, shot: Item, drawFraction: Float) {
        val marks = engine.growth.value(Skill.MARKSMANSHIP)
        val category = WeaponCategory.forWeapon(weapon.archetype) ?: WeaponCategory.UNARMED
        val proficiency = engine.proficiencies.value(category)
        val mastery = engine.masteries.value(weapon.uid)
        val moving = abs(engine.moveInput) > 0.05f || abs(engine.strafeInput) > 0.05f
        val spread = Ranged.spreadDegrees(weapon, drawFraction, marks, proficiency, mastery, moving)
        val ammoKind = Ranged.ammoKindOf(shot)
        val thrown = ammoKind == null
        val mass = if (thrown) {
            Ranged.throwSpecOf(shot.archetype)?.massKg ?: 0.2f
        } else {
            Ranged.ammoMassKg(shot)
        }
        val speedMps = Ranged.launchSpeedMps(weapon, mass, drawFraction, marks)
        val half = spread * 0.5f
        val yaw = (rng.nextFloat() * 2f - 1f) * half
        val pitch = (
            engine.camera.pitch + Math.toRadians(((rng.nextFloat() * 2f - 1f) * half).toDouble()).toFloat()
            ).coerceIn(-1.4f, 1.4f)
        val angle = engine.camera.angle + Math.toRadians(yaw.toDouble())
        val speedUps = speedMps * Ranged.MPS_TO_UPS
        val horizontal = speedUps * cos(pitch)
        val ground = engine.map.heightAt(engine.camera.x, engine.camera.y)
        engine.shots.spawn(
            Projectile(
                id = engine.shots.nextId(), fromPlayer = true, shooterName = "you",
                weaponName = engine.styleRoster.nameFor(weapon),
                category = category, proficiency = proficiency, mastery = mastery, marks = marks,
                spreadDegrees = spread,
                ammoItem = shot,
                label = if (thrown) engine.styleRoster.nameFor(shot) else (ammoKind?.label ?: "shot"),
                damageType = ammoKind?.damageType ?: shot.archetype.damageType,
                recoverable = thrown || (ammoKind?.recoverable ?: false),
                x = engine.camera.x + engine.camera.dirX * 0.3f,
                y = engine.camera.y + engine.camera.dirY * 0.3f,
                z = ground + engine.camera.eye + 0.05f,
                vx = (cos(angle) * horizontal).toFloat(),
                vy = (sin(angle) * horizontal).toFloat(),
                vz = speedUps * sin(pitch),
                massKg = mass,
                drag = ammoKind?.drag ?: 0.02f,
                quality = shot.quality,
                headMaterial = shot.headMaterial,
                spriteId = ammoKind?.let { Ranged.spriteFor(it) } ?: Sprites.forWeapon(shot.archetype)
            )
        )
    }

    /** A creature of the province looses at the delver: honest speed, honest doubt. */
    fun npcLoose(entity: Entity, weapon: Item, dist: Float) {
        entity.ammoCount -= 1
        val marks = entity.skills.value(Skill.MARKSMANSHIP)
        val category = WeaponCategory.forWeapon(weapon.archetype) ?: WeaponCategory.UNARMED
        val proficiency = entity.proficiencies.value(category)
        val ammoKind = Ranged.ammoOf(Ranged.formOf(weapon).kind).first()
        val shot = Ranged.rollAmmo(rng, ammoKind, -1, 1)
        val mass = Ranged.ammoMassKg(shot)
        val speedMps = Ranged.launchSpeedMps(weapon, mass, 1f, marks)
        val spread = Ranged.spreadDegrees(weapon, 1f, marks, proficiency, 0, false)
        val baseAngle = atan2(engine.camera.y - entity.y, engine.camera.x - entity.x)
        val half = spread * 0.5f
        val angle = baseAngle + Math.toRadians(((rng.nextFloat() * 2f - 1f) * half).toDouble())
        val dz = engine.map.heightAt(engine.camera.x, engine.camera.y) + engine.camera.eye -
            engine.map.heightAt(entity.x, entity.y)
        val pitch = atan2(dz, dist)
        val speedUps = speedMps * Ranged.MPS_TO_UPS
        val horizontal = speedUps * cos(pitch)
        engine.shots.spawn(
            Projectile(
                id = engine.shots.nextId(), fromPlayer = false, shooterName = entity.name,
                weaponName = engine.styleRoster.nameFor(weapon),
                category = category, proficiency = proficiency, mastery = 0, marks = marks,
                spreadDegrees = spread,
                ammoItem = shot, label = ammoKind.label,
                damageType = ammoKind.damageType,
                recoverable = ammoKind.recoverable,
                x = entity.x + cos(baseAngle) * 0.3f,
                y = entity.y + sin(baseAngle) * 0.3f,
                z = engine.map.heightAt(entity.x, entity.y) + 0.55f,
                vx = (cos(angle) * horizontal).toFloat(),
                vy = (sin(angle) * horizontal).toFloat(),
                vz = speedUps * sin(pitch),
                massKg = mass, drag = ammoKind.drag,
                quality = shot.quality, headMaterial = shot.headMaterial,
                spriteId = Ranged.spriteFor(ammoKind)
            )
        )
        engine.trainNpc(entity, Skill.MARKSMANSHIP, 1.5f)
        if (rng.nextInt(3) == 0) engine.emit(GameEvent.Note("The ${entity.name} looses at you."))
    }

    /** The harm a shot in the air would deal as it flies right now. */
    fun projectileDamage(p: Projectile): Int {
        val thrown = Ranged.throwSpecOf(p.ammoItem.archetype) != null
        return if (thrown) {
            Ranged.impactDamageThrown(p.ammoItem.archetype, p.speedMps, p.ammoItem)
        } else {
            Ranged.impactDamage(
                p.massKg, p.speedMps, Ranged.ammoKindOf(p.ammoItem)!!, p.headMaterial, p.quality
            )
        }
    }

    /** The ledger's ranged entry: the geometry answered, the arrival speed and mass recorded. */
    fun rangedHitChance(p: Projectile): Int =
        (100f * (0.45f / (Math.toRadians(p.spreadDegrees.toDouble()) * maxOf(p.distance, 1f))))
            .toInt().coerceIn(5, 99)
}
