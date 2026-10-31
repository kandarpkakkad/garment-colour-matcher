package com.garmentcolour.app.pipeline

import com.garmentcolour.app.data.AdjustmentEntity
import com.garmentcolour.app.data.CaptureEntity
import com.garmentcolour.app.data.GarmentEntity
import com.garmentcolour.engine.Analysis
import com.garmentcolour.engine.Box
import com.garmentcolour.engine.Colour
import com.garmentcolour.engine.Engine
import com.garmentcolour.engine.EstimateCodec
import com.garmentcolour.engine.Garment
import com.garmentcolour.engine.Tier
import com.garmentcolour.engine.Vec3

enum class Source { CAMERA, IMPORT }

enum class AdjustmentKind { NEUTRAL_TAP, USE_ESTIMATE, BOX }

/** What the decoder found, recorded with the capture (pipeline LLD §4). */
data class Decoded(val width: Int, val height: Int, val sourceMegapixels: Double, val downscaled: Boolean)

/**
 * Engine results → rows. Pure, so the mapping is unit-tested without Android
 * (storage schema).
 */
object Records {
    fun capture(
        id: String,
        createdAt: Long,
        source: Source,
        decoded: Decoded,
        tier: Tier,
        analysis: Analysis,
        capturePath: String = "JPEG",
        exposureGain: Double = 1.0,
    ): CaptureEntity {
        val est = analysis.estimate
        val (x, y) = Colour.xy(Colour.M * analysis.applied)
        return CaptureEntity(
            id = id,
            createdAt = createdAt,
            source = source.name,
            capturePath = capturePath,
            exposureGain = exposureGain,
            width = decoded.width,
            height = decoded.height,
            sourceMegapixels = decoded.sourceMegapixels,
            downscaled = decoded.downscaled,
            tier = tier.name,
            estR = est.combined?.x,
            estG = est.combined?.y,
            estB = est.combined?.z,
            estAnswers = EstimateCodec.encode(est.answers),
            estDisagreement = est.disagreementDeg,
            estSupport = est.support,
            estScore = est.score,
            estLevel = est.level.name,
            estReason = est.reason?.name,
            appliedR = analysis.applied.x,
            appliedG = analysis.applied.y,
            appliedB = analysis.applied.z,
            appliedMethod = analysis.method.name,
            appliedX = x,
            appliedY = y,
            engineVersion = Engine.VERSION,
        )
    }

    fun garments(captureId: String, garments: List<Garment>): List<GarmentEntity> = garments.mapIndexed { rank, g ->
        GarmentEntity(
            captureId = captureId,
            rank = rank,
            label = g.label.name,
            fraction = g.fraction,
            boxL = g.bounds.l,
            boxT = g.bounds.t,
            boxR = g.bounds.r,
            boxB = g.bounds.b,
            linR = g.linear.x,
            linG = g.linear.y,
            linB = g.linear.z,
            xyzX = g.xyz.x,
            xyzY = g.xyz.y,
            xyzZ = g.xyz.z,
            labL = g.lab.l,
            labA = g.lab.a,
            labB = g.lab.b,
            hex = g.hex,
            clipped = g.clipped,
        )
    }

    fun adjustment(
        captureId: String,
        createdAt: Long,
        kind: AdjustmentKind,
        before: Vec3,
        after: Vec3,
        tap: Pair<Double, Double>? = null,
        box: Box? = null,
    ) = AdjustmentEntity(
        captureId = captureId,
        createdAt = createdAt,
        kind = kind.name,
        tapU = tap?.first,
        tapV = tap?.second,
        boxL = box?.l,
        boxT = box?.t,
        boxR = box?.r,
        boxB = box?.b,
        beforeR = before.x,
        beforeG = before.y,
        beforeB = before.z,
        afterR = after.x,
        afterG = after.y,
        afterB = after.z,
        angleDeg = Colour.angleDeg(before, after),
    )

    fun applied(c: CaptureEntity) = Vec3(c.appliedR, c.appliedG, c.appliedB)
}
