package com.betterstreamflix.app.utils

/**
 * SS-B10-06 — why the app keeps global cleartext traffic permitted.
 *
 * Android [network_security_config](res/xml/network_security_config.xml) cannot
 * express "cleartext only for arbitrary IPTV playlist hosts": hosts are
 * user-supplied (hostnames and raw IPs), and NSC has no CIDR matching.
 * Flipping base cleartext to false would break IPTV, Cast LAN proxy, and Bypass
 * WebSocket paths. App-level allowlists (Cast / Bypass / resolve) remain the
 * hardening layer.
 *
 * Expose [requiresGlobalCleartext] and [iptvJustification] for Settings / docs.
 */
object CleartextTrafficPolicy {

    /** Product requires base-config cleartextTrafficPermitted=true. */
    const val requiresGlobalCleartext: Boolean = true

    /**
     * Short justification suitable for Settings copy or release notes.
     */
    const val iptvJustification: String =
        "Global cleartext stays enabled because IPTV playlists use arbitrary " +
            "user-supplied HTTP hosts and IPs; Android cannot domain-scope that. " +
            "LAN Cast/Bypass also need localhost cleartext. Host allowlists run in-app."
}
