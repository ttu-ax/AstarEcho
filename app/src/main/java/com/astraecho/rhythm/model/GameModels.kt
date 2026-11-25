package com.astraecho.rhythm.model

import org.json.JSONArray
import org.json.JSONObject

enum class NoteType {
    TAP,
    HOLD,
    SLIDE,       // Cross-lane horizontal slide arrow
    HOLD_SLIDE   // Multi-segment hold + cross-lane slide rail
}

enum class FlickDirection {
    LEFT,
    RIGHT
}

data class SlidePoint(
    val timeMs: Long,
    val lane: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timeMs", timeMs)
        put("lane", lane)
    }

    companion object {
        fun fromJson(obj: JSONObject): SlidePoint = SlidePoint(
            timeMs = obj.getLong("timeMs"),
            lane = obj.getInt("lane")
        )
    }
}

enum class NoteGeometryStyle {
    CRYSTAL_CAPSULE, // Starlight rounded crystal with center diamond
    PIANO_BEVEL,     // Classic 3D beveled arcade piano block
    SAKURA_PETAL,    // Soft rounded jewel with central 4-petal blossom emblem
    MECHA_HEX,       // Sharp chamfered hexagon / tactical prism with laser core
    MATH_MANUSCRIPT  // Monochrome blackboard bracketed tile with engraved math glyph
}

enum class ParticleStyle {
    STAR_DIAMONDS,   // Radial star diamonds + double shockwaves
    ARCADE_RIPPLES,  // Classic concentric neon ripples + glowing orbs
    SAKURA_PETALS,   // Rotating drifting cherry blossom petals + golden glow
    CYBER_SPARKS,    // High-velocity angular laser sparks + diamond shock frames
    MATH_SYMBOLS     // Monochrome floating math symbols (∫, ∑, π, ∞, ∂, √, λ) + coordinate rings
}

/**
 * Rich multi-element theme skins: custom stage background, note geometry,
 * track styling, particle VFX style, judgment typography, and HUD chrome!
 */
enum class NoteSkin(
    val displayName: String,
    val subtitle: String,
    val noteShape: NoteGeometryStyle,
    val particleStyle: ParticleStyle,
    val perfectLabel: String,
    val greatLabel: String,
    val goodLabel: String,
    val missLabel: String,
    val tapMain: Int,
    val tapHighlight: Int,
    val tapDeep: Int,
    val holdMain: Int,
    val holdBody: Int,
    val slideMain: Int,
    val slideHighlight: Int,
    val railColor: Int,
    val trackTintTop: Int,
    val trackTintBottom: Int,
    val keyGlowColor: Int,
    val particlePrimary: Int,
    val particleSecondary: Int
) {
    ASTRA_CYBER(
        displayName = "星律冰晶",
        subtitle = "星云舞台 · 水晶胶囊 · 星芒冲击波",
        noteShape = NoteGeometryStyle.CRYSTAL_CAPSULE,
        particleStyle = ParticleStyle.STAR_DIAMONDS,
        perfectLabel = "PERFECT",
        greatLabel = "GREAT",
        goodLabel = "GOOD",
        missLabel = "MISS",
        tapMain = 0xFF38F2FF.toInt(),
        tapHighlight = 0xFFD9FCFF.toInt(),
        tapDeep = 0xFF0A6FD6.toInt(),
        holdMain = 0xFFFFD166.toInt(),
        holdBody = 0xFFFFB733.toInt(),
        slideMain = 0xFFFF3B8D.toInt(),
        slideHighlight = 0xFFFFC2DE.toInt(),
        railColor = 0xFF38F2FF.toInt(),
        trackTintTop = 0x90070C1C.toInt(),
        trackTintBottom = 0xE0060A18.toInt(),
        keyGlowColor = 0xFF38F2FF.toInt(),
        particlePrimary = 0xFF38F2FF.toInt(),
        particleSecondary = 0xFFFF3B8D.toInt()
    ),
    CLASSIC_MASTER(
        displayName = "经典律动",
        subtitle = "电音光束 · 3D琴键块 · 经典波纹",
        noteShape = NoteGeometryStyle.PIANO_BEVEL,
        particleStyle = ParticleStyle.ARCADE_RIPPLES,
        perfectLabel = "MAX PERFECT",
        greatLabel = "GREAT",
        goodLabel = "GOOD",
        missLabel = "MISS",
        tapMain = 0xFF2FA8FF.toInt(),
        tapHighlight = 0xFFE3F5FF.toInt(),
        tapDeep = 0xFF0942A8.toInt(),
        holdMain = 0xFF4CE8FF.toInt(),
        holdBody = 0xFF1CB8E8.toInt(),
        slideMain = 0xFF8BE83B.toInt(),
        slideHighlight = 0xFFE8FFBD.toInt(),
        railColor = 0xFF4CC9FF.toInt(),
        trackTintTop = 0x9506102A.toInt(),
        trackTintBottom = 0xE2081638.toInt(),
        keyGlowColor = 0xFF3898FF.toInt(),
        particlePrimary = 0xFF4CC9FF.toInt(),
        particleSecondary = 0xFF9BFF4D.toInt()
    ),
    SAKURA_PULSE(
        displayName = "樱落幻梦",
        subtitle = "月夜樱林 · 琉璃花瓣键 · 落樱特效",
        noteShape = NoteGeometryStyle.SAKURA_PETAL,
        particleStyle = ParticleStyle.SAKURA_PETALS,
        perfectLabel = "✦ PERFECT ✦",
        greatLabel = "✦ GREAT ✦",
        goodLabel = "GOOD",
        missLabel = "MISS",
        tapMain = 0xFFFF7EB6.toInt(),
        tapHighlight = 0xFFFFEBF4.toInt(),
        tapDeep = 0xFFC42D76.toInt(),
        holdMain = 0xFFFFCE7A.toInt(),
        holdBody = 0xFFFFA84D.toInt(),
        slideMain = 0xFFC77DFF.toInt(),
        slideHighlight = 0xFFF2DBFF.toInt(),
        railColor = 0xFFFF9ECE.toInt(),
        trackTintTop = 0x951A0A28.toInt(),
        trackTintBottom = 0xE2220B2E.toInt(),
        keyGlowColor = 0xFFFF7EB6.toInt(),
        particlePrimary = 0xFFFF98C8.toInt(),
        particleSecondary = 0xFFFFD88A.toInt()
    ),
    CRIMSON_OVERDRIVE(
        displayName = "绯红超载",
        subtitle = "机甲格栅 · 切角棱镜键 · 电弧火花",
        noteShape = NoteGeometryStyle.MECHA_HEX,
        particleStyle = ParticleStyle.CYBER_SPARKS,
        perfectLabel = "[ CRITICAL ]",
        greatLabel = "[ SYNC ]",
        goodLabel = "[ GRAZE ]",
        missLabel = "[ LOST ]",
        tapMain = 0xFFFF3D5A.toInt(),
        tapHighlight = 0xFFFFD6DC.toInt(),
        tapDeep = 0xFF9E0B23.toInt(),
        holdMain = 0xFFFF9E2C.toInt(),
        holdBody = 0xFFE86A17.toInt(),
        slideMain = 0xFF00F5D4.toInt(),
        slideHighlight = 0xFFD9FFF9.toInt(),
        railColor = 0xFFFF4D6D.toInt(),
        trackTintTop = 0x98160409.toInt(),
        trackTintBottom = 0xE51C050B.toInt(),
        keyGlowColor = 0xFFFF3D5A.toInt(),
        particlePrimary = 0xFFFF5C77.toInt(),
        particleSecondary = 0xFF00F5D4.toInt()
    ),
    EULER_CHALK(
        displayName = "欧拉手稿",
        subtitle = "黑白数理手稿 · 矩阵方括键 · 符号粒子",
        noteShape = NoteGeometryStyle.MATH_MANUSCRIPT,
        particleStyle = ParticleStyle.MATH_SYMBOLS,
        perfectLabel = "Q.E.D.",
        greatLabel = "≡ SYNC",
        goodLabel = "≈ NEAR",
        missLabel = "∅ NULL",
        tapMain = 0xFFF5F7FA.toInt(),
        tapHighlight = 0xFFFFFFFF.toInt(),
        tapDeep = 0xFF14161B.toInt(),
        holdMain = 0xFFE2E6EE.toInt(),
        holdBody = 0xFFC5CCD8.toInt(),
        slideMain = 0xFFFFFFFF.toInt(),
        slideHighlight = 0xFFFFFFFF.toInt(),
        railColor = 0xFFEAECEF.toInt(),
        trackTintTop = 0xA808090C.toInt(),
        trackTintBottom = 0xEC0C0D12.toInt(),
        keyGlowColor = 0xFFFFFFFF.toInt(),
        particlePrimary = 0xFFFFFFFF.toInt(),
        particleSecondary = 0xFFB8C0CC.toInt()
    )
}

enum class Difficulty(
    val displayName: String,
    val tag: String,
    val stars: Int,
    val minIoiMs: Int,
    val thresholdMultiplier: Float,
    val chordChance: Float,
    val holdChance: Float,
    val slideChance: Float
) {
    EASY(
        displayName = "星尘 · 简单",
        tag = "EASY",
        stars = 3,
        minIoiMs = 260,
        thresholdMultiplier = 1.42f,
        chordChance = 0.06f,
        holdChance = 0.16f,
        slideChance = 0.12f
    ),
    NORMAL(
        displayName = "星轨 · 普通",
        tag = "NORMAL",
        stars = 6,
        minIoiMs = 165,
        thresholdMultiplier = 1.18f,
        chordChance = 0.18f,
        holdChance = 0.20f,
        slideChance = 0.20f
    ),
    HARD(
        displayName = "脉冲 · 困难",
        tag = "HARD",
        stars = 9,
        minIoiMs = 112,
        thresholdMultiplier = 1.02f,
        chordChance = 0.34f,
        holdChance = 0.20f,
        slideChance = 0.26f
    ),
    MASTER(
        displayName = "超新星 · 大师",
        tag = "MASTER",
        stars = 12,
        minIoiMs = 78,
        thresholdMultiplier = 0.88f,
        chordChance = 0.48f,
        holdChance = 0.18f,
        slideChance = 0.30f
    )
}

data class ChartNote(
    val id: Int,
    val timeMs: Long,
    val lane: Int,
    val type: NoteType,
    val durationMs: Long = 0L,
    val slideTargetLane: Int = lane,
    val pathPoints: List<SlidePoint> = emptyList()
) {
    val endTimeMs: Long
        get() = if (pathPoints.isNotEmpty()) pathPoints.last().timeMs else timeMs + durationMs

    val flickDirection: FlickDirection
        get() = if (slideTargetLane < lane) FlickDirection.LEFT else FlickDirection.RIGHT

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timeMs", timeMs)
        put("lane", lane)
        put("type", type.name)
        put("durationMs", durationMs)
        put("slideTargetLane", slideTargetLane)
        if (pathPoints.isNotEmpty()) {
            val arr = JSONArray()
            pathPoints.forEach { arr.put(it.toJson()) }
            put("pathPoints", arr)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): ChartNote {
            val rawType = obj.getString("type")
            val parsedType = when (rawType) {
                "FLICK" -> NoteType.SLIDE
                else -> runCatching { NoteType.valueOf(rawType) }.getOrDefault(NoteType.TAP)
            }
            val lane = obj.getInt("lane")
            val pts = mutableListOf<SlidePoint>()
            val arr = obj.optJSONArray("pathPoints")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    pts.add(SlidePoint.fromJson(arr.getJSONObject(i)))
                }
            }
            return ChartNote(
                id = obj.getInt("id"),
                timeMs = obj.getLong("timeMs"),
                lane = lane,
                type = parsedType,
                durationMs = obj.optLong("durationMs", 0L),
                slideTargetLane = obj.optInt("slideTargetLane", lane),
                pathPoints = pts
            )
        }
    }
}

data class ChartConfig(
    val laneCount: Int = 4,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val onlyTapAndHold: Boolean = false
) {
    val clampedLanes: Int get() = laneCount.coerceIn(4, 6)
}

data class BeatmapChart(
    val songId: String,
    val title: String,
    val artist: String,
    val bpm: Int,
    val durationMs: Long,
    val laneCount: Int,
    val difficulty: Difficulty,
    val onlyTapAndHold: Boolean,
    val notes: List<ChartNote>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("songId", songId)
        put("title", title)
        put("artist", artist)
        put("bpm", bpm)
        put("durationMs", durationMs)
        put("laneCount", laneCount)
        put("difficulty", difficulty.name)
        put("onlyTapAndHold", onlyTapAndHold)
        val arr = JSONArray()
        notes.forEach { arr.put(it.toJson()) }
        put("notes", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject): BeatmapChart {
            val arr = obj.getJSONArray("notes")
            val list = ArrayList<ChartNote>(arr.length())
            for (i in 0 until arr.length()) {
                list.add(ChartNote.fromJson(arr.getJSONObject(i)))
            }
            return BeatmapChart(
                songId = obj.getString("songId"),
                title = obj.getString("title"),
                artist = obj.optString("artist", "Unknown"),
                bpm = obj.optInt("bpm", 120),
                durationMs = obj.optLong("durationMs", 60_000L),
                laneCount = obj.optInt("laneCount", 4),
                difficulty = Difficulty.valueOf(obj.optString("difficulty", "NORMAL")),
                onlyTapAndHold = obj.optBoolean("onlyTapAndHold", false),
                notes = list
            )
        }
    }
}

data class SongItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val artist: String,
    val audioPath: String,
    val bpm: Int,
    val durationMs: Long,
    val isBuiltIn: Boolean,
    val isFromVideo: Boolean = false,
    val accentHex: Long = 0xFF38F2FF
)

data class BackgroundImportTask(
    val id: String,
    val title: String,
    val status: String,
    val progress: Float
)

data class PlayerSettings(
    val trackTiltDegrees: Float = 42f,
    val noteSpeed: Float = 1.05f,
    val laneCount: Int = 4,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val onlyTapAndHold: Boolean = false,
    val audioOffsetMs: Int = 0,
    val skin: NoteSkin = NoteSkin.ASTRA_CYBER,
    val customParserApiUrl: String = "" // Optional fallback parser endpoint with {url} placeholder
) {
    val approachTimeMs: Long
        get() = (1220f / noteSpeed.coerceIn(0.5f, 2.0f)).toLong()
}

enum class Judgment(val label: String, val scoreWeight: Float, val colorHex: Long) {
    PERFECT("PERFECT", 1.0f, 0xFFFFD166),
    GREAT("GREAT", 0.75f, 0xFF38F2FF),
    GOOD("GOOD", 0.40f, 0xFFB388FF),
    MISS("MISS", 0.0f, 0xFFFF4D6D);

    fun styledLabel(skin: NoteSkin): String = when (this) {
        PERFECT -> skin.perfectLabel
        GREAT -> skin.greatLabel
        GOOD -> skin.goodLabel
        MISS -> skin.missLabel
    }
}

data class GameResult(
    val songTitle: String,
    val difficulty: Difficulty,
    val laneCount: Int,
    val onlyTapAndHold: Boolean,
    val score: Int,
    val maxCombo: Int,
    val perfectCount: Int,
    val greatCount: Int,
    val goodCount: Int,
    val missCount: Int,
    val totalNotes: Int
) {
    val accuracy: Float
        get() {
            if (totalNotes <= 0) return 100f
            val weighted = perfectCount * 1.0f + greatCount * 0.75f + goodCount * 0.40f
            return (weighted / totalNotes * 100f).coerceIn(0f, 100f)
        }

    val rank: String
        get() = when {
            accuracy >= 98f && missCount == 0 -> "S+"
            accuracy >= 93f -> "S"
            accuracy >= 85f -> "A"
            accuracy >= 75f -> "B"
            else -> "C"
        }

    val isFullCombo: Boolean get() = missCount == 0 && totalNotes > 0
}
