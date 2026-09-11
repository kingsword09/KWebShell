package io.github.kingsword09.kwebshell.services

import io.github.kingsword09.kwebshell.core.KWebConfigurationException

/**
 * An exact, closed contract-version range. Provider keys and dependency edges
 * declare ranges instead of bare versions so version and target mismatch fail
 * before native initialization.
 */
public class KWebServiceVersionRange private constructor(
    public val minimum: KWebServiceVersion,
    public val maximumInclusive: KWebServiceVersion?,
) : Comparable<KWebServiceVersionRange> {
    init {
        val maximum = maximumInclusive
        if (maximum != null && maximum < minimum) {
            throw KWebConfigurationException(
                code = "service.version-range-invalid",
                details = mapOf(
                    "minimum" to minimum.toString(),
                    "maximum" to maximum.toString(),
                ),
                message = "A service contract range cannot end before it starts.",
            )
        }
    }

    public fun contains(version: KWebServiceVersion): Boolean {
        if (version < minimum) return false
        val maximum = maximumInclusive ?: return true
        return version <= maximum
    }

    override fun compareTo(other: KWebServiceVersionRange): Int {
        val minimumOrder = minimum.compareTo(other.minimum)
        if (minimumOrder != 0) return minimumOrder
        val selfMaximum = maximumInclusive
        val otherMaximum = other.maximumInclusive
        if (selfMaximum == null && otherMaximum == null) return 0
        if (selfMaximum == null) return 1
        if (otherMaximum == null) return -1
        return selfMaximum.compareTo(otherMaximum)
    }

    /** Deterministic description used by catalog metadata and error details. */
    public fun describe(): String {
        val maximum = maximumInclusive
        return when {
            maximum == null -> ">=$minimum"
            maximum == minimum -> "$minimum"
            else -> ">=$minimum <=$maximum"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is KWebServiceVersionRange &&
            minimum == other.minimum &&
            maximumInclusive == other.maximumInclusive

    override fun hashCode(): Int {
        val maximum = maximumInclusive
        return minimum.hashCode() * 31 + (maximum?.hashCode() ?: 0)
    }

    override fun toString(): String = "KWebServiceVersionRange(${describe()})"

    public companion object {
        public fun exact(version: KWebServiceVersion): KWebServiceVersionRange =
            KWebServiceVersionRange(version, version)

        public fun atLeast(minimum: KWebServiceVersion): KWebServiceVersionRange =
            KWebServiceVersionRange(minimum, null)

        public fun between(
            minimum: KWebServiceVersion,
            maximumInclusive: KWebServiceVersion,
        ): KWebServiceVersionRange = KWebServiceVersionRange(minimum, maximumInclusive)
    }
}
