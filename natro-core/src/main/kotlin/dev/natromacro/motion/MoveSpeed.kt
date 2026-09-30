package dev.natromacro.motion

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * Permanent hive multipliers folded into the speed the player types,
 * matching `nm_createWalk` in Natro.
 *
 * `both` is hasty guard and gifted hasty together (1.1 * 1.15 = 1.265).
 */
data class MoveSpeedProfile(
    val configured: Double,
    val base: Double,
    val hastyGuard: Boolean,
    val giftedHasty: Boolean,
) {
    companion object {
        fun fromConfigured(moveSpeedNum: Double): MoveSpeedProfile {
            val milli = moveSpeedNum * 1000.0
            val nudged = round((moveSpeedNum + 0.005) * 1000.0)
            val both = ahkMod(milli, 1265.0) == 0.0 || ahkMod(nudged, 1265.0) == 0.0
            val hastyGuard = both || ahkMod(milli, 1100.0) < 0.00001
            val giftedHasty = both || ahkMod(milli, 1150.0) < 0.00001
            val divisor = when {
                both -> 1.265
                hastyGuard -> 1.1
                giftedHasty -> 1.15
                else -> 1.0
            }
            return MoveSpeedProfile(
                configured = moveSpeedNum,
                base = round(moveSpeedNum / divisor),
                hastyGuard = hastyGuard,
                giftedHasty = giftedHasty,
            )
        }

        private fun ahkMod(value: Double, divisor: Double): Double {
            val q = floor(value / divisor + 1e-9)
            val m = value - divisor * q
            return if (m < 1e-4 || abs(divisor - m) < 1e-4) 0.0 else m
        }
    }
}

/** Buffs read from the top strip. Stacks are 0 when the haste icon is absent. */
data class BuffReading(
    val hasteStacks: Int = 0,
    val coconutHaste: Boolean = false,
    val bear: Boolean = false,
    val hastePlus: Boolean = false,
    val oil: Boolean = false,
    val smoothie: Boolean = false,
)

object MovespeedFormula {
    /**
     * Studs per second. [hasteCap] matches Walk.ahk: stacks at or below the cap
     * add no haste multiplier; only stacks above the cap are compensated,
     * at 10% each. Cap 0 compensates every stack.
     */
    fun velocity(profile: MoveSpeedProfile, buffs: BuffReading, hasteCap: Int = 0): Double {
        val stacks = buffs.hasteStacks.coerceAtLeast(0)
        return (profile.base + (if (buffs.coconutHaste) 10.0 else 0.0) + (if (buffs.bear) 4.0 else 0.0)) *
            (if (profile.hastyGuard) 1.1 else 1.0) *
            (if (profile.giftedHasty) 1.15 else 1.0) *
            (1.0 + max(0.0, (stacks - hasteCap).toDouble()) * 0.1) *
            (if (buffs.hastePlus) 2.0 else 1.0) *
            (if (buffs.oil) 1.2 else 1.0) *
            (if (buffs.smoothie) 1.25 else 1.0)
    }
}
