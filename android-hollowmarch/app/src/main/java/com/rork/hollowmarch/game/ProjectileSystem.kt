package com.rork.hollowmarch.game

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The shot in the air: honest physical things with mass, drag, and gravity,
 * stepped in honest sub-steps so nothing is flown through. The system owns the
 * flight, the collisions, the lifetime, and where spent shot comes back to the
 * ground; the harm a body takes when one lands is the engine's to apply.
 * Player shots and province shots ride the very same sky.
 */
class ProjectileSystem(private val engine: GameEngine, private val rng: Random) {

    val projectiles = mutableListOf<Projectile>()
    private var nextId = 1

    fun nextId(): Int = nextId++

    fun spawn(p: Projectile) {
        projectiles += p
    }

    fun step(dt: Float) {
        if (projectiles.isEmpty()) return
        val iterator = projectiles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.age += dt
            if (p.age > 10f) {
                iterator.remove()
                continue
            }
            val dragF = (1f - p.drag * dt).coerceIn(0f, 1f)
            p.vx *= dragF
            p.vy *= dragF
            p.vz *= dragF
            p.vz -= Ranged.GRAVITY_UPS * dt
            val speed = sqrt(p.vx * p.vx + p.vy * p.vy + p.vz * p.vz)
            val travel = speed * dt
            val steps = (travel / 0.25f).toInt().coerceIn(1, 40)
            val sdt = dt / steps
            var landed = false
            for (i in 0 until steps) {
                if (landed) break
                val px = p.x
                val py = p.y
                p.x += p.vx * sdt
                p.y += p.vy * sdt
                p.z += p.vz * sdt
                p.distance += speed * sdt
                val map = engine.map
                if (p.x < 0.5f || p.y < 0.5f || p.x > map.width - 0.5f || p.y > map.height - 0.5f ||
                    map.isWall(p.x, p.y)
                ) {
                    impactWall(p, px, py)
                    iterator.remove()
                    landed = true
                    break
                }
                if (p.z <= map.heightAt(p.x, p.y)) {
                    impactGround(p)
                    iterator.remove()
                    landed = true
                    break
                }
                if (!map.outdoor) {
                    val cx = p.x.toInt().coerceIn(0, map.width - 1)
                    val cy = p.y.toInt().coerceIn(0, map.height - 1)
                    val ceiling = map.ceilingHeights?.get(cy * map.width + cx) ?: 1f
                    if (p.z > ceiling) {
                        impactWall(p, px, py)
                        iterator.remove()
                        landed = true
                        break
                    }
                }
                if (p.fromPlayer) {
                    val target = projectileEnemyAt(p)
                    if (target != null) {
                        engine.applyProjectileImpact(p, target)
                        iterator.remove()
                        landed = true
                        break
                    }
                } else if (projectileHitsPlayer(p)) {
                    engine.applyProjectileImpact(p, null)
                    iterator.remove()
                    landed = true
                    break
                }
            }
        }
    }

    /** The enemy the shot's point stands inside, or null for air and scenery. */
    private fun projectileEnemyAt(p: Projectile): Entity? {
        val map = engine.map
        map.entities.forEach { entity ->
            if (entity.kind != EntityKind.ENEMY || !entity.alive) return@forEach
            val dx = p.x - entity.x
            val dy = p.y - entity.y
            val r = if (entity.spriteId == Sprites.HOUND) 0.30f else 0.42f
            if (dx * dx + dy * dy > r * r) return@forEach
            val base = map.heightAt(entity.x, entity.y)
            if (p.z < base || p.z > base + entity.height) return@forEach
            return entity
        }
        return null
    }

    /** True when the shot's point stands inside the delver's own body. */
    private fun projectileHitsPlayer(p: Projectile): Boolean {
        val camera = engine.camera
        val ground = engine.map.heightAt(camera.x, camera.y)
        if (p.z < ground || p.z > ground + 1f) return false
        val dx = p.x - camera.x
        val dy = p.y - camera.y
        return dx * dx + dy * dy < 0.42f * 0.42f
    }

    /** Where a spent shot ends: the ground gives recoverable shot back, powder shot never. */
    private fun impactGround(p: Projectile) {
        if (p.recoverable) engine.groundItems += GroundItem(p.x, p.y, p.ammoItem)
        if (MapFactory.distance(p.x, p.y, engine.camera.x, engine.camera.y) >= 9f) return
        if (p.fromPlayer) {
            if (rng.nextInt(3) == 0) {
                engine.emit(
                    GameEvent.Note(
                        if (p.recoverable) "Your ${p.label} stands in the ground, ripe for the taking."
                        else "Your ${p.label} flattens itself against the ground and is spent."
                    )
                )
            }
        } else if (rng.nextInt(2) == 0) {
            engine.emit(GameEvent.Note("The ${p.shooterName}'s ${p.label} buries itself in the dirt."))
        }
    }

    private fun impactWall(p: Projectile, px: Float, py: Float) {
        if (p.recoverable) engine.groundItems += GroundItem(px, py, p.ammoItem)
        if (p.fromPlayer && rng.nextInt(3) == 0 &&
            MapFactory.distance(px, py, engine.camera.x, engine.camera.y) < 8f
        ) {
            engine.emit(GameEvent.Note("Your ${p.label} shivers against the stone."))
        }
    }
}
