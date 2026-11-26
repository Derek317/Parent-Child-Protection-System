package app.family.igguard

internal enum class RiskBand(val minimum: Int, val title: String, val range: String) {
    LOW(0, "低風險", "0–<25"),
    POTENTIAL(25, "潛在風險", "25–<50"),
    MODERATE(50, "中度風險", "50–<75"),
    HIGH(75, "高風險", "75–100");

    companion object {
        fun forScore(score: Double): RiskBand = entries.last { score >= it.minimum }
    }
}
