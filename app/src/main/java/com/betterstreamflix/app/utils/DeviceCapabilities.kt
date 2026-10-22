package com.betterstreamflix.app.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Lightweight device capability checks for playback tuning
 * (Fire TV Stick RAM/decoder limits, OEM audio quirks, etc.).
 */
object DeviceCapabilities {

    fun isAmazonFireTv(context: Context): Boolean {
        if (Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) return true
        if (Build.MODEL.startsWith("AFT", ignoreCase = true)) return true
        if (Build.BRAND.equals("Amazon", ignoreCase = true)) return true
        return runCatching {
            context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
        }.getOrDefault(false)
    }

    fun isLowRamDevice(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return activityManager?.isLowRamDevice == true
    }

    fun isLeanbackDevice(context: Context): Boolean =
        runCatching {
            context.packageManager.hasSystemFeature("android.software.leanback")
        }.getOrDefault(false)

    /**
     * Known Fire TV Stick / Stick Lite / Stick 4K SKUs — not Cube / Fire TV Edition.
     * Used for Stick Energy auto-on (SE-001) without punishing Cube / high-RAM boxes.
     */
    fun isFireTvStickModel(
        model: String = Build.MODEL.orEmpty(),
    ): Boolean {
        val m = model.trim().uppercase()
        if (m.isEmpty()) return false
        // Explicit Cube / pendant codes — never auto Stick Energy.
        val cubes = setOf(
            "AFTA", "AFTB", "AFTBAMR", "AFTRIGID", "AFTGAZL", "AFTBUZ", "AFTPR001",
        )
        if (m in cubes) return false
        // Stick-class Amazon models (Stick, Stick Lite, Stick 4K, Stick 4K Max).
        val stickExact = setOf(
            "AFTM", "AFTT", "AFTS", "AFTN", "AFTMM", "AFTSS", "AFTSSS",
            "AFTKA", "AFTKM", "AFTKRT", "AFTBTX4", "AFTSSS", "AFTSO001",
        )
        if (m in stickExact) return true
        // Marketing strings sometimes appear in MODEL on sideloaded builds.
        if (m.contains("STICK") && !m.contains("CUBE")) return true
        return false
    }

    /**
     * Stick Energy auto-on when preference is unset (SE-001).
     * Legacy Fire OS, low-RAM, or known Stick SKU — not every Amazon OEM box.
     */
    fun shouldAutoEnableStickEnergy(context: Context): Boolean {
        if (!isAmazonFireTv(context)) return false
        return isLegacyFireOs(context) || isLowRamDevice(context) || isFireTvStickModel()
    }

    /** Xiaomi / OEM Android TV sticks that often crash on HW decoder init. */
    fun isFragileTvOem(): Boolean {
        val mfr = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return mfr.equals("Xiaomi", ignoreCase = true) ||
            mfr.contains("skyworth", ignoreCase = true) ||
            mfr.contains("amlogic", ignoreCase = true) ||
            model.contains("MIBOX", ignoreCase = true) ||
            model.contains("MiTV", ignoreCase = true) ||
            model.contains("Mi Box", ignoreCase = true)
    }

    /** Fire Stick / low-RAM / fragile leanback boxes struggle with huge buffers and HW-only decode. */
    fun shouldUseConstrainedPlayback(context: Context): Boolean {
        // Codec Comfort: once this stick learned HW decode pain, keep buffers conservative.
        if (com.betterstreamflix.app.player.CodecComfort.preferStablePlaybackProfile()) {
            return true
        }
        // Newer Fire Stick 4K Max / Cube have enough RAM — only constrain legacy/low-RAM/fragile.
        return isLegacyFireOs(context) ||
            isLowRamDevice(context) ||
            (isLeanbackDevice(context) && isFragileTvOem())
    }

    /**
     * Prefer software/extension renderers from the first player build.
     * Limited to devices that actually crash on HW decode — not every Leanback/Fire box —
     * so Settings → Software Decoder Off can force hardware on Shield / CCwGTV.
     * Also true after [com.betterstreamflix.app.player.CodecComfort] latches.
     */
    fun shouldPreferSoftwareDecoder(context: Context): Boolean {
        if (com.betterstreamflix.app.player.CodecComfort.preferSoftwareDecoder()) {
            return true
        }
        return isLegacyFireOs(context) ||
            isLowRamDevice(context) ||
            isFragileTvOem()
    }

    /** True for Fire OS 6-era Amazon devices (API ≤ 25). */
    fun isLegacyFireOs(context: Context): Boolean =
        isAmazonFireTv(context) && Build.VERSION.SDK_INT <= 25

    /**
     * Hard home-effect constraints excluding Stick Energy.
     * Low-RAM / constrained playback / animator-scale 0 — Ambient must stay off.
     * Stick Energy alone may still enter Ambient with reduced Ken Burns.
     * Codec Comfort alone does not hard-reduce home effects (CC-003).
     */
    fun shouldHardReduceHomeEffects(context: Context): Boolean {
        if (isLowRamDevice(context)) return true
        if (shouldUseConstrainedPlayback(context)) return true
        return isAnimatorDurationScaleOff(context)
    }

    fun isAnimatorDurationScaleOff(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 17 &&
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f

    /**
     * Soften home motion (ken-burns, swiper progress, focus zoom) on Stick Energy /
     * low-RAM / reduce-motion — **not** when only Codec Comfort is latched (CC-003).
     */
    fun shouldReduceHomeEffects(context: Context): Boolean {
        if (com.betterstreamflix.app.ui.StickEnergyMode.isEnabled(context)) return true
        return shouldHardReduceHomeEffects(context)
    }

    /**
     * ISO-639 language tags ExoPlayer understands for preferred audio selection.
     * TMDb/HLS streams often label tracks as `eng`/`spa` rather than `en`/`es`.
     */
    fun preferredAudioLanguages(providerLanguage: String?): List<String> {
        val base = providerLanguage
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()

        return when (base) {
            "es", "spa", "esp" -> listOf("spa", "es", "esp")
            "en", "eng" -> listOf("eng", "en")
            "de", "deu", "ger" -> listOf("deu", "ger", "de")
            "fr", "fra", "fre" -> listOf("fra", "fre", "fr")
            "it", "ita" -> listOf("ita", "it")
            "pt", "por" -> listOf("por", "pt", "pb", "pt-br")
            "ar", "ara" -> listOf("ara", "ar")
            "ja", "jpn" -> listOf("jpn", "ja")
            "ko", "kor" -> listOf("kor", "ko")
            "zh", "zho", "chi" -> listOf("zho", "chi", "zh", "cmn")
            else -> listOf(base)
        }
    }
}
