package eu.kanade.domain.ui.model

enum class BottomBarStyle(
    val title: String,
    val styleTag: String,
    val animationName: String,
    val summary: String,
) {
    DEFAULT(
        title = "Classic Material",
        styleTag = "Default",
        animationName = "Material Fade",
        summary = "Standard bottom bar with classic pill indicator and fade transition",
    ),
    GLASS_DOCK(
        title = "Glass Dock",
        styleTag = "Modern Floating",
        animationName = "Frosted Glide",
        summary = "Floating translucent glass dock with a soft luminous active halo",
    ),
    CAPSULE_BAR(
        title = "Capsule Bar",
        styleTag = "Simple & Clean",
        animationName = "Crisp Snap",
        summary = "Minimal outlined capsule rail with a bold solid active pill",
    ),
    ELEVATED_BUBBLE(
        title = "Elevated Bubble",
        styleTag = "Bold & Modern",
        animationName = "Spring Pop",
        summary = "Sleek rail where the selected tab rises in a lifted orb",
    ),
    FLOATING_CENTER(
        title = "Floating Center",
        styleTag = "Orbital Notch",
        animationName = "Gravity Arc",
        summary = "Curved dock crest cradling a glowing orbital sphere around active tab",
    ),
    WAVE_DOCK(
        title = "Wave Dock",
        styleTag = "Fluid Crest",
        animationName = "Liquid Wave",
        summary = "Flowing wave silhouette with a fluid curved crest over active tab",
    ),
    FLOATING_PILL(
        title = "Floating Pill",
        styleTag = "Luminous Pill",
        animationName = "Glow Shift",
        summary = "Floating rail with an illuminated raised pill medallion and cue dots",
    ),
    CENTER_DOCK(
        title = "Center Dock",
        styleTag = "Horizon Ring",
        animationName = "Horizon Sweep",
        summary = "Open rail segments framing a glowing circular ring spotlight",
    ),
}
