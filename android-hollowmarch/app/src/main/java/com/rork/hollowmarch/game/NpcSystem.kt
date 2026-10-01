package com.rork.hollowmarch.game

import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The wits and hands of the province's fighting creatures, one tick at a
 * time: detection under this light, the heart's retreat and pursuit, the
 * shield-bearer's own judgment, the archer's craft, and the closing blow.
 * The blows themselves are resolved by CombatSystem, the shots loosed by
 * RangedCombatSystem — this system decides, it does not roll the dice.
 */
class NpcSystem(private val engine: GameEngine, private val rng: Random) {

    fun update(dt: Float) {
        engine.map.entities.forEach { entity ->
            entity.hurtFlash = (entity.hurtFlash - dt * 2.2f).coerceAtLeast(0f)
            if (entity.kind != EntityKind.ENEMY || !entity.alive) return@forEach
            if (entity.resident) return@forEach
            entity.attackCooldown = (entity.attackCooldown - dt).coerceAtLeast(0f)
            val dx = engine.camera.x - entity.x
            val dy = engine.camera.y - entity.y
            val dist = sqrt(dx * dx + dy * dy)
            // Held weaves bind the wits before the world does.
            val controls = SpellCasting.controlsOf(entity)
            if (SpellCasting.CONTROL_PARALYZE in controls) return@forEach
            if (dist > 12f) return@forEach
            engine.updateDetection(entity, dist, dt)
            val paceFactor = SpellCasting.moveFactor(entity.activeEffects)
            if (SpellCasting.CONTROL_FEAR in controls) {
                // The heart flees the hand that frightened it.
                val flee = entity.speed * 1.15f * paceFactor
                val nx = entity.x - dx / dist * flee * dt
                val ny = entity.y - dy / dist * flee * dt
                if (!engine.map.isWall(nx, entity.y)) entity.x = nx
                if (!engine.map.isWall(entity.x, ny)) entity.y = ny
                return@forEach
            }
            if (SpellCasting.CONTROL_CALM in controls) {
                entity.detection = Detection.UNAWARE
                return@forEach
            }
            // The blind do not chase, and the searching walk soft.
            if (entity.detection == Detection.UNAWARE) return@forEach
            if (SpellCasting.CONTROL_FRENZY in controls) {
                // The maddened weave makes the nearest body the enemy, whatever side it serves.
                val target = engine.map.entities
                    .filter { it !== entity && it.kind == EntityKind.ENEMY && it.alive }
                    .minByOrNull { MapFactory.distance(entity.x, entity.y, it.x, it.y) }
                if (target != null) {
                    val d = MapFactory.distance(entity.x, entity.y, target.x, target.y)
                    if (d <= 12f) strikeFellow(entity, target, d, paceFactor, dt)
                }
                return@forEach
            }
            if (SpellCasting.isAllied(entity)) {
                serveTheCaster(entity, paceFactor, dt)
                return@forEach
            }
            // the heart under the hide: the hurt may break and run, and every
            // chase lasts only as long as the temper says it does
            entity.personality?.let { heart ->
                val retreat = heart.retreatHpFraction()
                if (retreat > 0f && entity.hp < entity.maxHp * retreat) {
                    val nx = entity.x - dx / dist * entity.speed * 1.15f * paceFactor * dt
                    val ny = entity.y - dy / dist * entity.speed * 1.15f * dt
                    if (!engine.map.isWall(nx, entity.y)) entity.x = nx
                    if (!engine.map.isWall(entity.x, ny)) entity.y = ny
                    if (dist > heart.pursueRadius()) entity.detection = Detection.SEARCHING
                    return@forEach
                }
                // a hot temper keeps the chase alive; a calm one lets it go
                if (dist > heart.pursueRadius()) {
                    entity.detection = Detection.SEARCHING
                    return@forEach
                }
            }
            // The worker of wills: a spell it knows, within reach and price,
            // on its own tempo — paid from its own well, as you pay yours.
            if (entity.magic.knownSpells.isNotEmpty() && entity.detection == Detection.AWARE &&
                entity.attackCooldown <= 0f && tryCastSpell(entity, dist)
            ) {
                entity.attackCooldown = (2.6f + rng.nextFloat() * 2.2f) *
                    (entity.personality?.attackCooldownScale() ?: 1f)
                return@forEach
            }
            // The shield-bearer's own judgment: cover on the approach, drop the guard to strike.
            val npcGuard = entity.equipment?.let { Shields.worn(it) }
            if (npcGuard != null) {
                val handling = Shields.handling(npcGuard)
                entity.blocking = Shields.shouldBlock(
                    true, entity.detection == Detection.AWARE, dist, entity.attackCooldown, handling
                )
            }
            // The board eases up into the guard and eases down out of it.
            val wantGuard = if (npcGuard != null && entity.blocking) 1f else 0f
            entity.guardRaise += (wantGuard - entity.guardRaise) * (dt * 9f).coerceIn(0f, 1f)
            val guardPace = if (entity.blocking && npcGuard != null) Shields.moveFactor(npcGuard) else 1f
            val pace = (if (entity.detection == Detection.SEARCHING) entity.speed * 0.6f else entity.speed) * guardPace * paceFactor

            // The archer's craft: hold the ground and loose, so long as arrows last.
            val theirArm = entity.equipment?.bestWeapon()
            if (theirArm != null && entity.ammoCount > 0 && entity.detection == Detection.AWARE &&
                dist in 2.2f..11f && Ranged.isRanged(theirArm.archetype) &&
                Ranged.formOf(theirArm).kind != RangedKind.THROWN
            ) {
                entity.facingAngle = atan2(dy, dx)
                if (entity.attackCooldown <= 0f) {
                    engine.ranged.npcLoose(entity, theirArm, dist)
                    entity.attackCooldown = (2.0f + rng.nextFloat() * 1.6f) *
                        (entity.personality?.attackCooldownScale() ?: 1f)
                }
                return@forEach
            }

            if (dist > 1.05f) {
                val nx = entity.x + dx / dist * pace * dt
                val ny = entity.y + dy / dist * pace * dt
                if (!engine.map.isWall(nx, entity.y)) entity.x = nx
                if (!engine.map.isWall(entity.x, ny)) entity.y = ny
                // A guard that moves watches where it goes; a braced board keeps its angle.
                if (!entity.blocking) entity.facingAngle = atan2(dy, dx)
                // The chase itself is a teacher.
                engine.trainNpc(entity, Skill.ATHLETICS, dt * 2f)
            } else if (entity.detection == Detection.AWARE && entity.attackCooldown <= 0f) {
                meleeAttack(entity)
            }
        }
    }

    /** An allied weave fights the caster's foes and heels to the caster otherwise. */
    private fun serveTheCaster(entity: Entity, paceFactor: Float, dt: Float) {
        val foe = engine.map.entities
            .filter { it !== entity && it.kind == EntityKind.ENEMY && it.alive && !SpellCasting.isAllied(it) }
            .minByOrNull { MapFactory.distance(entity.x, entity.y, it.x, it.y) }
        if (foe != null) {
            val d = MapFactory.distance(entity.x, entity.y, foe.x, foe.y)
            if (d <= 9f) {
                strikeFellow(entity, foe, d, paceFactor, dt)
                return
            }
        }
        // otherwise heel: stay near the caster's side
        val dx = engine.camera.x - entity.x
        val dy = engine.camera.y - entity.y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > 2.5f) {
            val pace = entity.speed * paceFactor
            val nx = entity.x + dx / dist * pace * dt
            val ny = entity.y + dy / dist * pace * dt
            if (!engine.map.isWall(nx, entity.y)) entity.x = nx
            if (!engine.map.isWall(entity.x, ny)) entity.y = ny
        }
    }

    /** One entity strikes another — the frenzy's chaos, or an ally's service. */
    private fun strikeFellow(attacker: Entity, target: Entity, dist: Float, paceFactor: Float, dt: Float) {
        if (dist > 1.05f) {
            val dx = target.x - attacker.x
            val dy = target.y - attacker.y
            val pace = attacker.speed * paceFactor
            val nx = attacker.x + dx / dist * pace * dt
            val ny = attacker.y + dy / dist * pace * dt
            if (!engine.map.isWall(nx, attacker.y)) attacker.x = nx
            if (!engine.map.isWall(attacker.x, ny)) attacker.y = ny
            return
        }
        if (attacker.attackCooldown > 0f) return
        attacker.attackCooldown = 1.5f + rng.nextFloat()
        var dealt = attacker.damage + rng.nextInt(4)
        target.activeEffects.filter { it.componentId == "shield" }.forEach { dealt = 0 }
        if (dealt > 0) {
            target.hp = (target.hp - dealt).coerceAtLeast(0)
            target.hurtFlash = 1f
            engine.emit(GameEvent.DamageDealt(false, attacker.name, target.name, dealt))
            // A warding skin turns a share back — applied once, never chained.
            SpellCasting.reflectShare(target, dealt)?.let { back ->
                attacker.hp = (attacker.hp - back).coerceAtLeast(0)
                engine.emit(GameEvent.Note("The ${target.name}'s ward turns a share of the harm back. $back lost."))
            }
            if (attacker.hp <= 0 && attacker.alive) {
                attacker.alive = false
                engine.emit(GameEvent.EntityKilled(attacker.name, null, attacker.level, 0))
            }
        }
        if (target.hp <= 0 && target.alive) {
            target.alive = false
            engine.emit(GameEvent.EntityKilled(target.name, null, target.level, 0))
        }
    }

    /**
     * A caster works its lore against the delver: one affordable, in-reach,
     * worthwhile formula, chosen by lot among what the moment allows.
     */
    private fun tryCastSpell(entity: Entity, dist: Float): Boolean {
        val spell = entity.magic.knownSpells
            .mapNotNull { engine.spellRegistry.get(it) }
            .filter { it.cost <= entity.magicka }
            .filter { formula -> worthCasting(entity, formula) }
            .filter { formula -> spellInReach(entity, formula, dist) }
            .randomOrNull(rng) ?: return false
        return engine.castSpellAs(entity, spell)
    }

    /**
     * Reach is judged per side: harm is measured against the delver, while a
     * beneficial working is measured against the nearest hand on the caster's
     * own side — itself at nought paces — so a healer mends its fellows
     * whether you stand near or far.
     */
    private fun spellInReach(entity: Entity, spell: Spell, distToDelver: Float): Boolean {
        val needsAlly = spell.effects.any {
            it.delivery != Delivery.SELF && SpellCasting.isBeneficial(ComponentRegistry.get(it.componentId).behavior)
        }
        val allyDist = if (needsAlly) nearestAllyDistance(entity) else Float.MAX_VALUE
        return spell.effects.all { effect ->
            val component = ComponentRegistry.get(effect.componentId)
            val reach =
                if (effect.delivery != Delivery.SELF && SpellCasting.isBeneficial(component.behavior)) allyDist
                else distToDelver
            SpellCasting.inReach(effect, reach)
        }
    }

    /** The closest living hand on the caster's own side; it counts itself at nought paces. */
    private fun nearestAllyDistance(entity: Entity): Float {
        var best = 0f
        engine.map.entities.forEach { other ->
            if (other.alive && other !== entity && other.kind == EntityKind.ENEMY && !SpellCasting.isAllied(other)) {
                val d = MapFactory.distance(entity.x, entity.y, other.x, other.y)
                if (best == 0f || d < best) best = d
            }
        }
        return best
    }

    /** The judgment before the working: never spend will on a weave that holds nothing new. */
    private fun worthCasting(entity: Entity, spell: Spell): Boolean {
        var mends = 0
        spell.effects.forEach { effect ->
            val component = ComponentRegistry.get(effect.componentId)
            when {
                component.behavior == MagicBehavior.RESTORE -> mends++
                effect.delivery == Delivery.SELF ->
                    // a held weave is not bought again while it lasts
                    if (entity.activeEffects.any { it.componentId == effect.componentId }) return false
                component.id in SpellCasting.SUMMON_IDS ->
                    // one servant serves the house; a second is waste
                    if (engine.map.entities.any { SpellCasting.isAllied(it) }) return false
                component.id in SpellCasting.CONTROL_IDS ->
                    // the bind already on the delver is not paid for twice
                    if (engine.playerEffects.any { it.componentId == effect.componentId }) return false
            }
        }
        // a mend is kept for a hurt hand — its own, or a fellow's within the weave's reach
        if (mends > 0 && mends == spell.effects.size && entity.hp >= entity.maxHp * 3 / 4) {
            val mendReach = spell.effects
                .filter { ComponentRegistry.get(it.componentId).behavior == MagicBehavior.RESTORE }
                .maxOf { it.range.coerceAtLeast(2) }
            val aFellowHurts = engine.map.entities.any {
                it.alive && it !== entity && it.kind == EntityKind.ENEMY && !SpellCasting.isAllied(it) &&
                    it.hp < it.maxHp * 3 / 4 && MapFactory.distance(entity.x, entity.y, it.x, it.y) <= mendReach
            }
            if (!aFellowHurts) return false
        }
        return true
    }

    /** A creature in reach takes its swing at the delver, by the same rules as yours. */
    private fun meleeAttack(entity: Entity) {
        entity.facingAngle = atan2(engine.camera.y - entity.y, engine.camera.x - entity.x)
        entity.attackCooldown = (1.5f + rng.nextFloat()) * (entity.personality?.attackCooldownScale() ?: 1f)
        // Its hand asks the same questions yours does: Finesse, Melee, the family, the piece.
        val theirWeapon = entity.equipment?.bestWeapon()
        val theirCategory = theirWeapon?.let { WeaponCategory.forWeapon(it.archetype) }
            ?: WeaponCategory.UNARMED
        val theirProficiency = entity.proficiencies.value(theirCategory)
        val theirMastery = theirWeapon?.let { entity.masteries.value(it.uid) } ?: 0
        val theirStats = entity.stats ?: StatBlock.balanced()
        val attacker = engine.combat.combatantFor(entity)
        val defender = engine.combat.combatantForPlayer(null)
        val outcome = engine.combat.resolveMelee(
            rng, attacker, defender,
            theirWeapon?.archetype?.damageType ?: DamageType.SHARP,
            hitChance = Derived.meleeAccuracy(theirStats, entity.skills, theirProficiency, theirMastery),
            rawDamage = { entity.damage + rng.nextInt(4) },
            critChance = null,
            sneak = false,
            soakWhenBlocked = false
        )
        // A missed swing still teaches hand and piece a little.
        engine.trainNpc(entity, Skill.MELEE, 1f)
        engine.trainNpcWeapon(entity, 0.5f, 0.25f)
        val weaponName = theirWeapon?.let { engine.styleRoster.nameFor(it) } ?: "bare hands"
        when (outcome.outcome) {
            StrikeOutcome.MISSED -> {
                engine.emit(GameEvent.StrikeMissed(false, entity.name, "you"))
                engine.recordCombat(
                    engine.combat.meleeResolution(attacker, defender, weaponName, theirCategory, theirProficiency, theirMastery, outcome)
                )
                return
            }
            StrikeOutcome.DODGED -> {
                // You move before the edge arrives; what you wear has no say in a blow that lands on nothing.
                engine.emit(GameEvent.StrikeDodged(false, entity.name, "you"))
                engine.learnSkill(Skill.DEFENSE, 1.5f)
                engine.trainNpc(entity, Skill.MELEE, 1.5f)
                engine.recordCombat(
                    engine.combat.meleeResolution(attacker, defender, weaponName, theirCategory, theirProficiency, theirMastery, outcome)
                )
                return
            }
            StrikeOutcome.BLOCKED -> {
                val guardDealt = outcome.damage
                engine.vitality -= guardDealt
                // A maul through the board shakes the arm that holds it.
                if (outcome.defenderBlockedFatigue > 0f) engine.drainFatigue(outcome.defenderBlockedFatigue)
                engine.learnSkill(Skill.DEFENSE, 1.5f + outcome.rawDamage * 0.3f)
                engine.emit(GameEvent.StrikeBlocked(false, entity.name, "you", outcome.guardName, outcome.blockZone, guardDealt))
                engine.recordCombat(
                    engine.combat.meleeResolution(attacker, defender, weaponName, theirCategory, theirProficiency, theirMastery, outcome)
                )
                return
            }
            StrikeOutcome.HIT -> {}
        }
        val warded = engine.wardTimer > 0f
        var dealt = outcome.damage
        if (warded) dealt = (dealt * 0.45f).roundToInt().coerceAtLeast(1)
        engine.vitality -= dealt
        // The blow landed: the attacker's weave wakes, and the delver's worn defenses answer.
        theirWeapon?.let {
            Enchanting.triggerEnchantments(
                engine, it, EnchantmentActivation.ON_HIT, entity, byPlayer = false, forcedTarget = engine
            )
        }
        Enchanting.triggerDefensive(engine, EnchantmentActivation.WHEN_ATTACKED, entity)
        Enchanting.triggerDefensive(engine, EnchantmentActivation.WHEN_DAMAGED, entity)
        if (engine.vitality <= 0) {
            theirWeapon?.let {
                Enchanting.triggerEnchantments(
                    engine, it, EnchantmentActivation.WHEN_KILLING, entity,
                    byPlayer = false, forcedTarget = entity
                )
            }
        }
        // Your warding skin turns a share of the blow back on the hand that dealt it.
        SpellCasting.playerReflectShare(engine, dealt)?.let { back ->
            entity.hp = (entity.hp - back).coerceAtLeast(0)
            engine.emit(GameEvent.Note("Your ward turns a share of the blow back. $back lost."))
            if (entity.hp <= 0 && entity.alive) {
                entity.alive = false
                engine.emit(GameEvent.EntityKilled(entity.name, null, entity.level, 0))
            }
        }
        engine.learnSkill(Skill.DEFENSE, dealt * 0.9f)
        engine.trainNpc(entity, Skill.MELEE, 3f)
        engine.trainNpcWeapon(entity, 3f, 2f)
        engine.flashHurt()
        engine.emit(GameEvent.DamageDealt(false, entity.name, "you", dealt, warded = warded))
        engine.recordCombat(
            engine.combat.meleeResolution(attacker, defender, weaponName, theirCategory, theirProficiency, theirMastery, outcome, damageOverride = dealt)
        )
    }
}
