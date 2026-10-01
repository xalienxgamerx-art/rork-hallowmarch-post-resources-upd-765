package com.rork.hollowmarch.game

import kotlin.math.roundToInt

/**
 * Magic still at work on a body: held durations, mended pools, bent attributes
 * and skills, and their undo. One [ActiveMagicEffect] names what it touches —
 * a component, a magnitude, the time left, and (for weaves that bend a stat)
 * the exact revert to apply when the weave lapses.
 */
class ActiveMagicEffect(
    val componentId: String,
    var magnitude: Int,
    var remaining: Float,
    /** For FORTIFY/DRAIN: the attribute touched and the exact revert to apply. */
    val statTouched: Attr? = null,
    val statDelta: Int = 0,
    /** For generic Skill weaves: the skill touched, reverted by [statDelta]. */
    val skillTouched: Skill? = null,
    /** For Light: the glow the caster carried before the weave raised it. */
    val torchBaseline: Float? = null,
    /** Accumulator for per-second tick events on held harm and held senses. */
    var tickDebt: Float = 0f
)

/**
 * The one resolver every spell passes through: the formula drives behavior, so
 * player, NPC, scroll, and discovered spell all work the same way. The engine
 * hands in the world; this applies effects, timed magic, and events. Nothing
 * here knows a specific spell — only what [MagicalComponent] declares.
 */
object SpellCasting {

    // --------------------------------------------------------- component ids
    const val CONTROL_FEAR = "fear"
    const val CONTROL_CALM = "calm"
    const val CONTROL_FRENZY = "frenzy"
    const val CONTROL_CHARM = "charm"
    const val CONTROL_COMMAND = "command"
    const val CONTROL_PARALYZE = "paralyze"

    /** The crowd-control weaves; the NPC's wits read these each tick. */
    val CONTROL_IDS = setOf(CONTROL_FEAR, CONTROL_CALM, CONTROL_FRENZY, CONTROL_CHARM, CONTROL_COMMAND, CONTROL_PARALYZE)

    /** Weaves that bind a creature to the caster's side while they hold. */
    val ALLY_IDS = setOf("summon_creature", "summon_undead", CONTROL_CHARM, CONTROL_COMMAND)

    /** Weaves whose expiry unmakes the body they were cast on: summons. */
    val SUMMON_IDS = setOf("summon_creature", "summon_undead")

    /** Weaves that mend, cleanse, or bless the body they land on rather than harm it. */
    private val BENEFICIAL_BEHAVIORS = setOf(
        MagicBehavior.RESTORE, MagicBehavior.CURE, MagicBehavior.SHIELD, MagicBehavior.FORTIFY,
        MagicBehavior.HASTE, MagicBehavior.LIGHT, MagicBehavior.REFLECT
    )

    /** Whether a working helps the body it lands on; the wit to aim it lives with the caster. */
    fun isBeneficial(behavior: MagicBehavior): Boolean = behavior in BENEFICIAL_BEHAVIORS

    /**
     * The bodies a working falls on, by its delivery. Harm reaches foes. A
     * beneficial weave lands on whatever hand it is cast at — the delver's mend
     * touches any body in reach, enemy included — while a soul of the province
     * works its mends on its own side; only the choosing is the caster's wit.
     */
    fun targetsFor(engine: GameEngine, effect: SpellEffect, caster: Any = engine): List<Any> {
        if (effect.delivery == Delivery.SELF) return listOf(caster)
        val component = ComponentRegistry.get(effect.componentId)
        val living: List<Any> = when {
            isBeneficial(component.behavior) -> when (caster) {
                is GameEngine -> engine.map.entities.filter { it.alive }
                else -> alliesOf(engine, caster)
            }
            else -> hostilesOf(engine, caster)
        }
        val (cx, cy) = posOf(caster)
        val inReach = living.filter { distanceTo(cx, cy, it) <= effect.range.coerceAtLeast(2) }
        return when (effect.delivery) {
            Delivery.SELF -> listOf(caster)
            Delivery.TOUCH -> inReach.filter { distanceTo(cx, cy, it) <= 1.7f }
            Delivery.TARGET -> listOfNotNull(
                when (component.behavior) {
                    // a mend finds the emptiest cup in reach before the nearest one
                    MagicBehavior.RESTORE ->
                        inReach.minWithOrNull(compareBy({ hpFraction(it) }, { distanceTo(cx, cy, it) }))
                    else -> inReach.minByOrNull { distanceTo(cx, cy, it) }
                }
            )
            Delivery.AREA -> {
                val anchor = inReach.minByOrNull { distanceTo(cx, cy, it) } ?: return emptyList()
                val (ax, ay) = posOf(anchor)
                living.filter { MapFactory.distance(ax, ay, posOf(it).first, posOf(it).second) <= effect.area }
            }
        }
    }

    /** Whether one effect of a formula can land from [dist] paces, by its delivery's reach. */
    fun inReach(effect: SpellEffect, dist: Float): Boolean = when (effect.delivery) {
        Delivery.SELF -> true
        Delivery.TOUCH -> dist <= 1.7f
        Delivery.TARGET, Delivery.AREA -> dist <= effect.range.coerceAtLeast(2)
    }

    /** Where a body stands: the delver walks at the camera; a soul at its own two feet. */
    private fun posOf(body: Any): Pair<Float, Float> = when (body) {
        is GameEngine -> Pair(body.camera.x, body.camera.y)
        is Entity -> Pair(body.x, body.y)
        else -> Pair(0f, 0f)
    }

    private fun distanceTo(x: Float, y: Float, body: Any): Float = when (body) {
        is GameEngine -> MapFactory.distance(x, y, body.camera.x, body.camera.y)
        is Entity -> MapFactory.distance(x, y, body.x, body.y)
        else -> Float.MAX_VALUE
    }

    /** How whole a body stands, nought to one; a mend prefers the emptiest cup. */
    private fun hpFraction(body: Any): Float = when (body) {
        is Entity -> if (body.maxHp > 0) body.hp / body.maxHp.toFloat() else 1f
        else -> 1f
    }

    /** The foes of a caster: the delver hunts the province's enemies; a soul of the province hunts you and yours. */
    private fun hostilesOf(engine: GameEngine, caster: Any): List<Any> = when (caster) {
        is GameEngine -> engine.map.entities.filter { it.kind == EntityKind.ENEMY && it.alive }
        else -> listOf<Any>(engine) + engine.map.entities.filter { it.alive && isAllied(it) }
    }

    /** The caster's own side: itself, and whatever fights beside it. */
    private fun alliesOf(engine: GameEngine, caster: Any): List<Any> = when (caster) {
        is GameEngine -> listOf<Any>(engine) + engine.map.entities.filter { it.alive && isAllied(it) }
        is Entity -> listOf<Any>(caster) + engine.map.entities.filter {
            it.alive && it !== caster && it.kind == EntityKind.ENEMY && !isAllied(it)
        }
        else -> emptyList()
    }

    /**
     * Work a whole formula from [casterName]'s hand. Magicka is the caller's
     * book; this applies the world's half: harm, mending, held weaves, events.
     */
    fun resolve(engine: GameEngine, spell: Spell, casterName: String, byPlayer: Boolean, caster: Any = engine) {
        spell.effects.forEach { effect -> resolveEffect(engine, effect, casterName, byPlayer, caster) }
        engine.emit(GameEvent.SpellCast(spell.name, byPlayer, casterName))
    }

    /**
     * Apply one effect of a formula to its targets. A [forcedTarget] names the
     * exact body the weave falls on — an enchantment's answering blow strikes
     * the body the combat event named, not the nearest one. A SELF delivery
     * still lands on its caster; constants are never resolved here at all.
     */
    fun resolveEffect(
        engine: GameEngine, effect: SpellEffect, casterName: String,
        byPlayer: Boolean, caster: Any = engine, forcedTarget: Any? = null
    ) {
        val component = ComponentRegistry.get(effect.componentId)
        val targets = if (forcedTarget != null && effect.delivery != Delivery.SELF) {
            listOf(forcedTarget)
        } else {
            targetsFor(engine, effect, caster)
        }
        val bodies: List<Any> = targets
        bodies.forEach { body ->
            when (component.behavior) {
                MagicBehavior.DAMAGE ->
                    if (effect.duration > 0) {
                        addTimed(engine, body, ActiveMagicEffect(component.id, effect.magnitude, effect.duration.toFloat()))
                        engine.emit(
                            GameEvent.MagicStateApplied(byPlayer, component.name.lowercase(), nameOf(body))
                        )
                    } else {
                        dealHarm(engine, body, effect.magnitude, casterName, byPlayer, component.aspect, component.element)
                    }
                MagicBehavior.RESTORE -> mend(engine, body, component.aspect, effect.magnitude, casterName, byPlayer)
                MagicBehavior.FORTIFY -> touchStat(engine, body, component, effect, +1, byPlayer)
                MagicBehavior.DRAIN -> touchStat(engine, body, component, effect, -1, byPlayer)
                MagicBehavior.SYPHON -> syphon(engine, component, effect, casterName, byPlayer, caster)
                MagicBehavior.SHIELD -> {
                    addTimed(engine, body, ActiveMagicEffect(component.id, effect.magnitude, effect.duration.toFloat()))
                    engine.emit(GameEvent.MagicStateApplied(byPlayer, component.name.lowercase(), nameOf(body)))
                }
                MagicBehavior.LIGHT -> kindleLight(engine, component, effect, body)
                MagicBehavior.FEAR -> {
                    targets.forEach { (it as? Entity)?.detection = Detection.AWARE }
                    holdState(engine, body, component, effect, byPlayer)
                }
                MagicBehavior.CALM -> {
                    targets.forEach { (it as? Entity)?.detection = Detection.UNAWARE }
                    holdState(engine, body, component, effect, byPlayer)
                }
                MagicBehavior.FRENZY, MagicBehavior.CHARM, MagicBehavior.COMMAND, MagicBehavior.PARALYZE ->
                    holdState(engine, body, component, effect, byPlayer)
                MagicBehavior.HASTE, MagicBehavior.SLOW ->
                    holdState(engine, body, component, effect, byPlayer)
                MagicBehavior.REFLECT ->
                    holdState(engine, body, component, effect, byPlayer)
                MagicBehavior.CURE -> cure(engine, body)
                MagicBehavior.SUMMON -> summon(engine, component, effect, byPlayer, caster)
                MagicBehavior.DETECT_LIFE, MagicBehavior.DETECT_MAGIC -> {
                    holdState(engine, body, component, effect, byPlayer)
                    // the senses whisper only to the hand that holds the book
                    if (byPlayer) sense(engine, component, effect.magnitude)
                }
            }
        }
    }

    /** A body takes spell harm; a held Shield answers first, a held ward turns a share back. */
    private fun dealHarm(
        engine: GameEngine, body: Any, raw: Int, casterName: String, byPlayer: Boolean,
        aspect: String, element: String?, allowReflect: Boolean = true
    ) {
        val dealt = ElementalRules.modify(raw, element)
        when (body) {
            is GameEngine -> when (aspect) {
                "magicka" -> engine.magicka = (engine.magicka - dealt).coerceAtLeast(0)
                "fatigue" -> engine.fatigue = (engine.fatigue - dealt).coerceAtLeast(0)
                else -> engine.vitality = (engine.vitality - dealt).coerceAtLeast(0)
            }
            is Entity -> {
                var landed = dealt
                if (aspect == "magicka" && body.maxMagicka > 0) {
                    // a soul with a well of will loses will, not blood
                    val before = body.magicka
                    body.magicka = (body.magicka - dealt).coerceAtLeast(0)
                    landed = before - body.magicka
                } else {
                    body.activeEffects.filter { it.componentId == "shield" }.forEach { shield ->
                        landed = 0
                        shield.remaining -= dealt / 10f
                    }
                    body.hp = (body.hp - landed).coerceAtLeast(0)
                }
                if (dealt > 0 || raw > 0) {
                    engine.emit(
                        GameEvent.DamageDealt(byPlayer, casterName, body.name, dealt, element = element)
                    )
                }
                // A warding skin turns a share of the harm back on the hand that dealt it.
                if (allowReflect && landed > 0 && byPlayer) {
                    reflectShare(body, landed)?.let { back ->
                        engine.vitality = (engine.vitality - back).coerceAtLeast(0)
                        engine.emit(GameEvent.Note("The ${body.name}'s ward turns a share of the harm back. $back lost."))
                    }
                }
                if (body.hp <= 0 && body.alive) {
                    body.alive = false
                    engine.emit(GameEvent.EntityKilled(body.name, null, body.level, 0))
                }
            }
        }
    }

    /** The share of harm a body's warding skin turns back; null when no ward holds. */
    fun reflectShare(body: Entity, raw: Int): Int? {
        if (raw <= 0) return null
        val ward = body.activeEffects.firstOrNull { behaviorOf(it.componentId) == MagicBehavior.REFLECT }
            ?: return null
        return (raw * ward.magnitude / 100f).roundToInt().coerceAtLeast(1)
    }

    /** The share of harm the caster's own ward turns back; null when none holds. */
    fun playerReflectShare(engine: GameEngine, raw: Int): Int? {
        if (raw <= 0) return null
        val ward = engine.playerEffects.firstOrNull { behaviorOf(it.componentId) == MagicBehavior.REFLECT }
            ?: return null
        return (raw * ward.magnitude / 100f).roundToInt().coerceAtLeast(1)
    }

    /** A mend lands: a pool rises toward its measure, never past it. */
    private fun mend(
        engine: GameEngine, body: Any, aspect: String, amount: Int, casterName: String, byPlayer: Boolean
    ) {
        when (body) {
            is GameEngine -> {
                val before: Int
                val after: Int
                when (aspect) {
                    "magicka" -> {
                        before = engine.magicka
                        engine.magicka = (engine.magicka + amount).coerceAtMost(engine.maxMagicka)
                        after = engine.magicka
                    }
                    "fatigue" -> {
                        before = engine.fatigue
                        engine.fatigue = (engine.fatigue + amount).coerceAtMost(engine.maxFatigue)
                        after = engine.fatigue
                    }
                    else -> {
                        before = engine.vitality
                        engine.vitality = (engine.vitality + amount).coerceAtMost(engine.maxVitality)
                        after = engine.vitality
                    }
                }
                if (after > before) {
                    engine.emit(GameEvent.Healed(byPlayer, casterName, "you", after - before))
                }
            }
            is Entity -> when (aspect) {
                "health" -> {
                    val before = body.hp
                    body.hp = (body.hp + amount).coerceAtMost(body.maxHp)
                    if (body.hp > before) {
                        engine.emit(GameEvent.Healed(byPlayer, casterName, body.name, body.hp - before))
                    }
                }
                "magicka" -> if (body.maxMagicka > 0) {
                    val before = body.magicka
                    body.magicka = (body.magicka + amount).coerceAtMost(body.maxMagicka)
                    if (body.magicka > before) {
                        engine.emit(GameEvent.Healed(byPlayer, casterName, body.name, body.magicka - before))
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * FORTIFY lifts, DRAIN weighs: an Attribute or a Skill moves now, and the
     * exact amount it moved moves back when the weave lapses — clamped bounds
     * included, so the revert is always symmetric with the application.
     */
    private fun touchStat(
        engine: GameEngine, body: Any, component: MagicalComponent, effect: SpellEffect,
        sign: Int, byPlayer: Boolean
    ): Int {
        val delta = sign * effect.magnitude
        val attr: Attr? = when (component.targetKind) {
            MagicTargetKind.ATTRIBUTE -> Attr.entries.firstOrNull { it.name == effect.target }
            MagicTargetKind.NONE -> Attr.entries.firstOrNull { it.name.lowercase() == component.aspect }
            MagicTargetKind.SKILL -> null
        }
        val skill: Skill? = when (component.targetKind) {
            MagicTargetKind.SKILL -> Skill.entries.firstOrNull { it.name == effect.target }
            else -> null
        }
        if (attr == null && skill == null) return 0

        val applied: Int = when (body) {
            is GameEngine -> {
                // one weave per target: a new weave on the same Attribute or
                // Skill supersedes the older one, undone before the new takes
                // hold — so reverts can never tangle through clamped bounds
                val prior = engine.playerEffects.firstOrNull {
                    it.statTouched == attr && it.skillTouched == skill && (attr != null || skill != null)
                }
                if (prior != null && prior.statDelta != 0) {
                    prior.statTouched?.let { engine.stats.adjust(it, prior.statDelta) }
                    prior.skillTouched?.let { engine.growth.shift(it, prior.statDelta) }
                    engine.playerEffects.remove(prior)
                }
                val before = attr?.let { engine.stats[it] } ?: skill?.let { engine.growth.value(it) } ?: 0
                attr?.let { engine.stats.adjust(it, delta) }
                skill?.let { engine.growth.shift(it, delta) }
                val after = attr?.let { engine.stats[it] } ?: skill?.let { engine.growth.value(it) } ?: 0
                attr?.let {
                    engine.emit(GameEvent.AttributeModified(byPlayer, it.label, after - before, "you", effect.duration > 0))
                }
                skill?.let {
                    engine.emit(GameEvent.SkillModified(byPlayer, it.label, after - before, "you", effect.duration > 0))
                }
                after - before
            }
            is Entity -> {
                val prior = body.activeEffects.firstOrNull {
                    it.statTouched == attr && it.skillTouched == skill && (attr != null || skill != null)
                }
                if (prior != null && prior.statDelta != 0) {
                    prior.statTouched?.let { body.stats?.adjust(it, prior.statDelta) }
                    prior.skillTouched?.let { body.skills.shift(it, prior.statDelta) }
                    body.activeEffects.remove(prior)
                }
                val before = attr?.let { body.stats?.get(it) } ?: skill?.let { body.skills.value(it) } ?: 0
                attr?.let { body.stats?.adjust(it, delta) }
                skill?.let { body.skills.shift(it, delta) }
                val after = attr?.let { body.stats?.get(it) } ?: skill?.let { body.skills.value(it) } ?: 0
                attr?.let {
                    engine.emit(GameEvent.AttributeModified(byPlayer, it.label, after - before, body.name, effect.duration > 0))
                }
                skill?.let {
                    engine.emit(GameEvent.SkillModified(byPlayer, it.label, after - before, body.name, effect.duration > 0))
                }
                after - before
            }
            else -> 0
        }
        if (effect.duration > 0 && applied != 0) {
            addTimed(
                engine, body,
                ActiveMagicEffect(component.id, effect.magnitude, effect.duration.toFloat(), attr, -applied, skill)
            )
        }
        return applied
    }

    /**
     * A syphon takes from the target and pours what it took into the caster:
     * pools drain and mend by the same honest count; an Attribute or Skill
     * bends down on the target and up on the caster, both weaves expiring
     * back to where each body stood. What the target had not, the caster
     * gains not — nothing is minted from a clamped bound.
     */
    private fun syphon(
        engine: GameEngine, component: MagicalComponent, effect: SpellEffect,
        casterName: String, byPlayer: Boolean, caster: Any
    ) {
        targetsFor(engine, effect, caster).forEach { target ->
            when (component.targetKind) {
                MagicTargetKind.ATTRIBUTE, MagicTargetKind.SKILL -> {
                    // touchStat answers the signed move; a drain walks it down,
                    // so what it took is the fall, made positive
                    val drained = -touchStat(engine, target, component, effect, -1, byPlayer)
                    if (drained > 0) {
                        touchStat(engine, caster, component, effect.copy(magnitude = drained), +1, byPlayer)
                    }
                }
                MagicTargetKind.NONE -> {
                    val drained = drainPool(engine, target, component.aspect, effect.magnitude, casterName, byPlayer)
                    if (drained > 0) {
                        mend(engine, caster, component.aspect, drained, casterName, byPlayer)
                    }
                }
            }
        }
    }

    /** A pool is tapped for the syphon; what actually came out is returned. */
    private fun drainPool(
        engine: GameEngine, body: Any, aspect: String, amount: Int, casterName: String, byPlayer: Boolean
    ): Int {
        val taken: Int = when (body) {
            is GameEngine -> when (aspect) {
                "magicka" -> {
                    val before = body.magicka
                    body.magicka = (body.magicka - amount).coerceAtLeast(0)
                    before - body.magicka
                }
                "fatigue" -> {
                    val before = body.fatigue
                    body.fatigue = (body.fatigue - amount).coerceAtLeast(0)
                    before - body.fatigue
                }
                else -> {
                    val before = body.vitality
                    body.vitality = (body.vitality - amount).coerceAtLeast(0)
                    before - body.vitality
                }
            }
            is Entity -> when (aspect) {
                "health" -> {
                    val before = body.hp
                    body.hp = (body.hp - amount).coerceAtLeast(0)
                    if (body.hp <= 0 && body.alive) {
                        body.alive = false
                        engine.emit(GameEvent.EntityKilled(body.name, null, body.level, 0))
                    }
                    before - body.hp
                }
                "magicka" -> if (body.maxMagicka > 0) {
                    val before = body.magicka
                    body.magicka = (body.magicka - amount).coerceAtLeast(0)
                    before - body.magicka
                } else {
                    0
                }
                // a beast's blood alone bleeds; weariness is the delver's alone to lose
                else -> 0
            }
            else -> 0
        }
        if (taken > 0) {
            engine.emit(GameEvent.DamageDealt(byPlayer, casterName, nameOf(body), taken, element = null))
        }
        return taken
    }

    /** Held movement magic: Haste quickens the feet, Slow weighs them. */
    fun moveFactor(effects: List<ActiveMagicEffect>): Float {
        var factor = 1f
        effects.forEach { effect ->
            when (behaviorOf(effect.componentId)) {
                MagicBehavior.HASTE -> factor *= 1f + effect.magnitude / 100f
                MagicBehavior.SLOW -> factor *= (1f - effect.magnitude / 100f).coerceAtLeast(0.3f)
                else -> {}
            }
        }
        return factor.coerceIn(0.3f, 2f)
    }

    /** Whether a body's weaves bind it to the caster's side: summons, charms, commands. */
    fun isAllied(entity: Entity): Boolean = entity.activeEffects.any { it.componentId in ALLY_IDS }

    /** The crowd-control weaves currently holding a body. */
    fun controlsOf(entity: Entity): Set<String> =
        entity.activeEffects.map { it.componentId }.filter { it in CONTROL_IDS }.toSet()

    // ------------------------------------------------------------- internals

    private fun behaviorOf(componentId: String): MagicBehavior? =
        ComponentRegistry.all().firstOrNull { it.id == componentId }?.behavior

    private fun nameOf(body: Any): String = when (body) {
        is GameEngine -> "you"
        is Entity -> body.name
        else -> ""
    }

    private fun holdState(
        engine: GameEngine, body: Any, component: MagicalComponent, effect: SpellEffect, byPlayer: Boolean
    ) {
        addTimed(engine, body, ActiveMagicEffect(component.id, effect.magnitude, effect.duration.toFloat()))
        engine.emit(GameEvent.MagicStateApplied(byPlayer, component.name.lowercase(), nameOf(body)))
    }

    private fun kindleLight(engine: GameEngine, component: MagicalComponent, effect: SpellEffect, body: Any) {
        if (body !is GameEngine) return
        // The glow remembers what the caster carried before the weave raised it.
        val baseline = engine.playerEffects
            .firstOrNull { it.componentId == component.id }?.torchBaseline ?: engine.torch
        engine.torch = (engine.torch + effect.magnitude / 40f).coerceAtMost(1f)
        addTimed(
            engine, body,
            ActiveMagicEffect(component.id, effect.magnitude, effect.duration.toFloat(), torchBaseline = baseline)
        )
    }

    /** Cure Poison washes out held poison and nothing else. */
    private fun cure(engine: GameEngine, body: Any) {
        val isPoison: (ActiveMagicEffect) -> Boolean = { effect ->
            val component = ComponentRegistry.all().firstOrNull { it.id == effect.componentId }
            component?.behavior == MagicBehavior.DAMAGE && component.element == ElementalRules.POISON
        }
        when (body) {
            is GameEngine -> {
                val cured = engine.playerEffects.filter(isPoison)
                engine.playerEffects.removeAll(cured.toSet())
                if (cured.isNotEmpty()) engine.emit(GameEvent.MagicStateExpired("poison", "you"))
            }
            is Entity -> {
                val cured = body.activeEffects.filter(isPoison)
                body.activeEffects.removeAll(cured.toSet())
                if (cured.isNotEmpty()) engine.emit(GameEvent.MagicStateExpired("poison", body.name))
            }
        }
    }

    /** A summons answers: a temporary creature steps from the weave beside its caller. */
    private fun summon(
        engine: GameEngine, component: MagicalComponent, effect: SpellEffect, byPlayer: Boolean, caster: Any
    ) {
        val (x, y) = posOf(caster)
        val spawned = engine.spawnSummon(component, effect.magnitude, x, y) ?: return
        spawned.activeEffects += ActiveMagicEffect(component.id, effect.magnitude, effect.duration.toFloat())
        engine.emit(GameEvent.Summoned(byPlayer, spawned.name))
    }

    /** Detection reads what the world actually carries: life, and tracked magic. */
    private fun sense(engine: GameEngine, component: MagicalComponent, magnitude: Int) {
        val line = if (component.behavior == MagicBehavior.DETECT_LIFE) {
            val range = magnitude.coerceAtLeast(4).toFloat()
            val living = engine.map.entities
                .filter { it.kind == EntityKind.ENEMY && it.alive }
                .filter { MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y) <= range }
                .sortedBy { MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y) }
            if (living.isEmpty()) {
                "You sense no life within ${range.toInt()} paces."
            } else {
                "You sense life: " + living.take(3).joinToString(", ") {
                    "${it.name} (${MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y).toInt()} paces)"
                } + "."
            }
        } else {
            val range = 15f
            val marked = engine.map.entities
                .filter { it.alive && it.activeEffects.isNotEmpty() }
                .filter { MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y) <= range }
            val stations = engine.map.entities
                .filter { it.name == MagicStations.NAME }
                .filter { MapFactory.distance(engine.camera.x, engine.camera.y, it.x, it.y) <= range }
            val seen = mutableListOf<String>()
            marked.forEach { seen += "magic held by ${it.name}" }
            stations.forEach { seen += "the working bench" }
            if (seen.isEmpty()) {
                "You sense no working magic within ${range.toInt()} paces."
            } else {
                "You sense magic: ${seen.take(3).joinToString(", ")}."
            }
        }
        engine.emit(GameEvent.Note(line))
    }

    /**
     * Held weaves join a body's list with a deterministic stack rule: the same
     * component on the same target refreshes rather than piles up; different
     * components, and the same component bent on different targets, stack.
     */
    private fun addTimed(engine: GameEngine, body: Any, effect: ActiveMagicEffect) {
        when (body) {
            is GameEngine -> {
                val existing = engine.playerEffects.firstOrNull {
                    it.componentId == effect.componentId &&
                        it.statTouched == effect.statTouched && it.skillTouched == effect.skillTouched
                }
                if (existing != null) {
                    existing.magnitude = effect.magnitude
                    existing.remaining = effect.remaining
                } else {
                    engine.playerEffects += effect
                }
            }
            is Entity -> {
                val existing = body.activeEffects.firstOrNull {
                    it.componentId == effect.componentId &&
                        it.statTouched == effect.statTouched && it.skillTouched == effect.skillTouched
                }
                if (existing != null) {
                    existing.magnitude = effect.magnitude
                    existing.remaining = effect.remaining
                } else {
                    body.activeEffects += effect
                }
            }
        }
    }

    /** The heart of held magic: harm trickles, senses whisper, expiries undo themselves. */
    fun step(engine: GameEngine, dt: Float) {
        // the player's held weaves
        val expiredPlayer = engine.playerEffects.filter { it.remaining <= dt }
        engine.playerEffects.forEach { effect ->
            effect.remaining -= dt
            tickHeld(engine, engine, effect, dt, isPlayer = true)
        }
        engine.playerEffects.removeAll { it.remaining <= 0f }
        expiredPlayer.forEach { expire(engine, engine, it, isPlayer = true) }
        // every body's held weaves
        val unmade = mutableListOf<Entity>()
        engine.map.entities.forEach { entity ->
            if (entity.activeEffects.isEmpty()) return@forEach
            val expired = entity.activeEffects.filter { it.remaining <= dt }
            entity.activeEffects.forEach { effect ->
                effect.remaining -= dt
                tickHeld(engine, entity, effect, dt, isPlayer = false)
            }
            entity.activeEffects.removeAll { it.remaining <= 0f }
            expired.forEach { expire(engine, entity, it, isPlayer = false) }
            // a summons whose weave ran out is unmade, not left to rot
            if (expired.any { it.componentId in SUMMON_IDS }) unmade += entity
        }
        unmade.forEach { summoned ->
            engine.map.entities.remove(summoned)
            engine.emit(GameEvent.SummonExpired(summoned.name))
        }
    }

    private fun tickHeld(engine: GameEngine, body: Any, effect: ActiveMagicEffect, dt: Float, isPlayer: Boolean) {
        val component = ComponentRegistry.all().firstOrNull { it.id == effect.componentId } ?: return
        when (component.behavior) {
            MagicBehavior.DAMAGE -> {
                val dose = (effect.magnitude * dt).coerceAtLeast(0f)
                when (body) {
                    is GameEngine -> if (isPlayer) when (component.aspect) {
                        "magicka" -> body.magicka = (body.magicka - dose).toInt().coerceAtLeast(0)
                        "fatigue" -> body.fatigue = (body.fatigue - dose).toInt().coerceAtLeast(0)
                        else -> body.vitality = (body.vitality - dose).toInt().coerceAtLeast(0)
                    }
                    is Entity -> {
                        body.hp = (body.hp - dose).toInt().coerceAtLeast(0)
                        if (body.hp <= 0 && body.alive) body.alive = false
                    }
                }
                // the harm is applied continuously; the tale is told once a second
                effect.tickDebt += dt
                if (effect.tickDebt >= 1f) {
                    effect.tickDebt %= 1f
                    when (body) {
                        is GameEngine -> if (isPlayer) {
                            engine.emit(GameEvent.DamageDealt(false, "the ${component.name.lowercase()}", "you", effect.magnitude, element = component.element))
                        }
                        is Entity -> engine.emit(
                            GameEvent.DamageDealt(false, "the ${component.name.lowercase()}", body.name, effect.magnitude, element = component.element)
                        )
                    }
                }
            }
            MagicBehavior.DETECT_LIFE, MagicBehavior.DETECT_MAGIC -> {
                effect.tickDebt += dt
                if (effect.tickDebt >= 2f) {
                    effect.tickDebt %= 2f
                    if (isPlayer) sense(engine, component, effect.magnitude)
                }
            }
            else -> {}
        }
    }

    private fun expire(engine: GameEngine, body: Any, effect: ActiveMagicEffect, isPlayer: Boolean) {
        // the exact amount that moved moves back — clamped bounds included
        if (effect.statDelta != 0) {
            if (isPlayer) {
                effect.statTouched?.let { engine.stats.adjust(it, effect.statDelta) }
                effect.skillTouched?.let { engine.growth.shift(it, effect.statDelta) }
            } else {
                (body as? Entity)?.let { entity ->
                    effect.statTouched?.let { entity.stats?.adjust(it, effect.statDelta) }
                    effect.skillTouched?.let { entity.skills.shift(it, effect.statDelta) }
                }
            }
        }
        effect.torchBaseline?.let { if (isPlayer) engine.torch = it }
        val component = ComponentRegistry.all().firstOrNull { it.id == effect.componentId }
        if (component != null && component.id in CONTROL_IDS) {
            engine.emit(GameEvent.MagicStateExpired(component.name.lowercase(), nameOf(body)))
        }
    }
}

/** The places where formulas are shaped: a squat stone bench in every living stead. */
object MagicStations {
    const val NAME = "the spellcrafting station"

    /** Set the bench near the heart of a living place, clear of every other thing. */
    fun place(map: GameMap, cx: Int, cy: Int) {
        val candidates = listOf(
            Pair(cx - 3, cy + 1), Pair(cx + 3, cy + 1),
            Pair(cx - 3, cy - 3), Pair(cx + 3, cy - 3), Pair(cx + 2, cy + 3)
        )
        val spot = candidates.firstOrNull { (x, y) ->
            !map.isWall(x + 0.5f, y + 0.5f) &&
                map.entities.none { MapFactory.distance(it.x, it.y, x + 0.5f, y + 0.5f) < 2.2f }
        } ?: return
        map.entities += Entity(
            x = spot.first + 0.5f, y = spot.second + 0.5f,
            spriteId = Sprites.STANDING_STONE, kind = EntityKind.PROP, height = 0.95f,
            name = NAME
        )
    }
}
