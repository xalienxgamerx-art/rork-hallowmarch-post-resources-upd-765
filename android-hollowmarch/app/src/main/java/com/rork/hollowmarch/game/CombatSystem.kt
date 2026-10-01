package com.rork.hollowmarch.game

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One side of an exchange, seen the same way whoever holds the steel. The
 * delver and a creature of the province wrap themselves into this shape so the
 * resolution below never asks whom it is serving.
 */
class Combatant(
    val name: String,
    val byPlayer: Boolean,
    val stats: StatBlock,
    val skills: Growth,
    val weapon: Item?,
    val worn: List<Item>,
    val guard: Item?,
    val blocking: Boolean,
    val facingX: Float,
    val facingY: Float,
    val x: Float,
    val y: Float
)

/**
 * One resolved melee exchange, everything the caller needs to apply the blow,
 * write the verification ledger, and let the engine tell the tale.
 */
class MeleeOutcome(
    val outcome: StrikeOutcome,
    val hitChance: Int,
    val hitRoll: Int,
    val dodgeChance: Int,
    val dodgeRoll: Int,
    val dodgePenalty: Int,
    /** The harm the arm rolled, before any board or mail had its say. */
    val rawDamage: Int,
    /** The harm that lands, after the board and the armor. */
    val damage: Int,
    val soak: Int,
    val blockZone: BlockZone,
    val guardName: String,
    val critical: Boolean,
    val sneak: Boolean,
    val defenderArmorTemper: String,
    val defenderArmorMaterial: String,
    /** The attacker's own arm shaken by a blow turned aside (blunt on a board). */
    val attackerBlockedFatigue: Float,
    /** The defender's arm shaken by a blunt blow breaking on their board. */
    val defenderBlockedFatigue: Float
)

/** What a shot in the air made of the body it found. */
class ProjectileHitOutcome(
    val damage: Int,
    val soak: Int,
    val blockZone: BlockZone,
    val guardName: String,
    val dodgePenalty: Int,
    val bodyTemper: String,
    val bodyMaterial: String
)

/**
 * The rules every hand plays by, delver and creature alike: one pipeline for
 * hit, dodge, sneak, fortune, the raised board, and the armor's temper. It
 * resolves and reports; it never applies harm, never writes the ledger's
 * prose, and never touches the screen.
 */
class CombatSystem(private val engine: GameEngine) {

    /** The delver as a combatant, holding [weapon]. */
    fun combatantForPlayer(weapon: Item?): Combatant = Combatant(
        "you", true, engine.stats, engine.growth, weapon,
        engine.equipment.items(), engine.shield, engine.blocking,
        engine.camera.dirX, engine.camera.dirY, engine.camera.x, engine.camera.y
    )

    /** A creature of the province as a combatant. */
    fun combatantFor(entity: Entity): Combatant = Combatant(
        entity.name, false, entity.stats ?: StatBlock.balanced(), entity.skills,
        entity.equipment?.bestWeapon(),
        entity.equipment?.items() ?: emptyList(),
        entity.equipment?.let { Shields.worn(it) },
        entity.blocking, cos(entity.facingAngle), sin(entity.facingAngle), entity.x, entity.y
    )

    /** Whatever stands close, in front of you, and breathing. */
    fun pickMeleeTarget(camera: Camera, entities: List<Entity>): Entity? =
        entities.filter { it.kind == EntityKind.ENEMY && it.alive }
            .filter { entity ->
                val dx = entity.x - camera.x
                val dy = entity.y - camera.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > 2.2f) return@filter false
                val dot = (dx / dist) * camera.dirX + (dy / dist) * camera.dirY
                dot > 0.55f
            }
            .minByOrNull { MapFactory.distance(it.x, it.y, camera.x, camera.y) }

    /**
     * The whole exchange, rolled in one honest order: the hand, the feet, the
     * arm's harm, fortune, the unseen strike, the raised board, and finally
     * the armor. [rawDamage] is asked only when a blow actually arrives, so
     * the dice are spent exactly as the old ledger spent them.
     */
    fun resolveMelee(
        rng: Random,
        attacker: Combatant,
        defender: Combatant,
        harm: DamageType,
        hitChance: Int,
        rawDamage: () -> Int,
        critChance: Int?,
        sneak: Boolean,
        soakWhenBlocked: Boolean
    ): MeleeOutcome {
        val hitRoll = rng.nextInt(100)
        val dodgePenalty = Derived.armorDodgePenalty(defender.worn)
        val dodgeChance = Derived.dodgeChance(defender.stats, defender.skills, defender.worn)
        val dodgeRoll = rng.nextInt(100)
        val bodyPiece = defender.worn.firstOrNull { it.archetype.slot == ItemSlot.BODY }
        val temper = bodyPiece?.archetype?.armorTemper?.name ?: "NONE"
        val material = bodyPiece?.material?.label ?: "NONE"

        if (hitRoll >= hitChance) {
            return MeleeOutcome(
                StrikeOutcome.MISSED, hitChance, hitRoll, dodgeChance, dodgeRoll, dodgePenalty,
                0, 0, 0, BlockZone.MISS, "", false, sneak, temper, material, 0f, 0f
            )
        }
        if (!sneak && dodgeRoll < dodgeChance) {
            return MeleeOutcome(
                StrikeOutcome.DODGED, hitChance, hitRoll, dodgeChance, dodgeRoll, dodgePenalty,
                0, 0, 0, BlockZone.MISS, "", false, sneak, temper, material, 0f, 0f
            )
        }
        var damage = rawDamage()
        var critical = false
        if (critChance != null && rng.nextInt(100) < critChance) {
            damage = (damage * 1.5f).roundToInt() + 2
            critical = true
        }
        if (sneak) {
            damage = (damage * Derived.sneakAttackMultiplier(attacker.weapon?.archetype)).roundToInt()
        }

        // A raised board between the blow and its mark: geometry first, then the make.
        val guard = defender.guard
        if (guard != null && defender.blocking && !sneak) {
            val offAxis = Shields.offAxisDegrees(
                defender.facingX, defender.facingY, attacker.x - defender.x, attacker.y - defender.y
            )
            val (zone, eff) = Shields.resolve(
                guard, harm, offAxis, defender.skills.value(Skill.DEFENSE), attacker.stats[Attr.MIGHT]
            )
            if (zone != BlockZone.MISS) {
                val guardName = engine.styleRoster.nameFor(guard)
                val afterBlock = (damage * (1f - eff)).roundToInt()
                return if (soakWhenBlocked) {
                    // The body under the board still wears what it wears.
                    val soak = Derived.armorSoak(defender.worn, harm)
                    MeleeOutcome(
                        StrikeOutcome.BLOCKED, hitChance, hitRoll, dodgeChance, dodgeRoll, dodgePenalty,
                        damage, (afterBlock - soak).coerceAtLeast(0), soak, zone, guardName,
                        critical, sneak, temper, material,
                        if (harm == DamageType.BLUNT && afterBlock > 0) afterBlock * 0.2f else 0f, 0f
                    )
                } else {
                    MeleeOutcome(
                        StrikeOutcome.BLOCKED, hitChance, hitRoll, dodgeChance, dodgeRoll, dodgePenalty,
                        damage, afterBlock, 0, zone, guardName,
                        critical, sneak, temper, material, 0f,
                        if (harm == DamageType.BLUNT && afterBlock > 0) damage * (1f - eff) * 0.5f else 0f
                    )
                }
            }
        }
        val soak = Derived.armorSoak(defender.worn, harm)
        return MeleeOutcome(
            StrikeOutcome.HIT, hitChance, hitRoll, dodgeChance, dodgeRoll, dodgePenalty,
            damage, (damage - soak).coerceAtLeast(1), soak, BlockZone.MISS, "",
            critical, sneak, temper, material, 0f, 0f
        )
    }

    /**
     * A shot in the air finds a body: the board may turn it, the armor always
     * has its say, and one point of harm gets through a bare hit.
     */
    fun resolveProjectileHit(
        harm: DamageType,
        rawDamage: Int,
        sneak: Boolean,
        attackerMight: Int,
        attackerX: Float,
        attackerY: Float,
        defender: Combatant
    ): ProjectileHitOutcome {
        val worn = defender.worn
        val bodyPiece = worn.firstOrNull { it.archetype.slot == ItemSlot.BODY }
        val dodgePenalty = Derived.armorDodgePenalty(worn)
        var damage = rawDamage
        var blockZone = BlockZone.MISS
        var guardName = ""
        val guard = defender.guard
        if (guard != null && defender.blocking && !sneak) {
            val offAxis = Shields.offAxisDegrees(
                defender.facingX, defender.facingY, attackerX - defender.x, attackerY - defender.y
            )
            val (zone, eff) = Shields.resolve(
                guard, harm, offAxis, defender.skills.value(Skill.DEFENSE), attackerMight
            )
            if (zone != BlockZone.MISS) {
                blockZone = zone
                damage = (damage * (1f - eff)).roundToInt()
                guardName = engine.styleRoster.nameFor(guard)
            }
        }
        val soak = Derived.armorSoak(worn, harm)
        damage = (damage - soak).coerceAtLeast(if (blockZone != BlockZone.MISS) 0 else 1)
        return ProjectileHitOutcome(
            damage, soak, blockZone, guardName, dodgePenalty,
            bodyPiece?.archetype?.armorTemper?.name ?: "NONE",
            bodyPiece?.material?.label ?: "NONE"
        )
    }

    /** The verification ledger's entry for a melee exchange, built once for every hand. */
    fun meleeResolution(
        attacker: Combatant,
        defender: Combatant,
        weaponName: String,
        category: WeaponCategory,
        proficiency: Int,
        mastery: Int,
        o: MeleeOutcome,
        recordOutcome: StrikeOutcome = o.outcome,
        damageOverride: Int? = null
    ): CombatResolution = CombatResolution(
        attacker.name, defender.name, attacker.stats[Attr.FINESSE], attacker.skills.value(Skill.MELEE),
        weaponName, category, proficiency, mastery, o.hitChance, o.hitRoll, recordOutcome,
        defender.stats[Attr.SWIFTNESS],
        o.defenderArmorTemper, o.defenderArmorMaterial,
        o.dodgePenalty, o.dodgeChance, o.dodgeRoll, damageOverride ?: o.damage, o.soak,
        o.guardName, o.blockZone.name
    )
}
