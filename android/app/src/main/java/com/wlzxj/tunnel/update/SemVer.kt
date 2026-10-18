package com.wlzxj.tunnel.update

/**
 * Numeric semver `major.minor.patch` (no prerelease suffix).
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    fun display(): String = "$major.$minor.$patch"

    companion object {
        private val TAG_REGEX = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

        fun parseTag(tagName: String): SemVer? {
            val match = TAG_REGEX.matchEntire(tagName.trim()) ?: return null
            return SemVer(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }
    }
}

fun SemVer.isNewerThan(other: SemVer): Boolean = this > other
