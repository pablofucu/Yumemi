package com.pablo.yumemi.data.settings

/** Pure helpers about the Hermes base URL. */
object HostRules {

    /** Extracts the lowercase host of a URL (with or without scheme). Empty if none. */
    fun hostOf(url: String): String {
        var s = url.trim()
        val schemeIdx = s.indexOf("://")
        if (schemeIdx >= 0) s = s.substring(schemeIdx + 3)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        if (s.startsWith("[")) {
            return s.substringAfter('[').substringBefore(']').lowercase()
        }
        return s.substringBefore(':').lowercase()
    }

    private fun ipv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = IntArray(4)
        for (i in 0 until 4) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            nums[i] = n
        }
        return nums
    }

    /** True for localhost, Tailscale (100.64.0.0/10, *.ts.net) and RFC1918 private ranges. */
    fun isPrivateOrTailscaleHost(host: String): Boolean {
        val h = host.lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "::1" || h.endsWith(".localhost")) return true
        if (h.endsWith(".ts.net")) return true
        val ip = ipv4(h) ?: return false
        return when {
            ip[0] == 127 -> true
            ip[0] == 10 -> true
            ip[0] == 192 && ip[1] == 168 -> true
            ip[0] == 172 && ip[1] in 16..31 -> true
            ip[0] == 100 && ip[1] in 64..127 -> true
            else -> false
        }
    }

    /** Trims, removes trailing slashes and adds a scheme when missing. */
    fun normalizeBaseUrl(input: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (!s.contains("://")) {
            val scheme = if (isPrivateOrTailscaleHost(hostOf(s))) "http" else "https"
            s = "$scheme://$s"
        }
        return s
    }

    /**
     * Where the automation feeds live by default: /feeds next to the Hermes API (same host and
     * port, behind the same key). Empty when there is no base URL.
     */
    fun defaultFeedsUrl(baseUrl: String): String {
        val url = normalizeBaseUrl(baseUrl)
        if (hostOf(url).isEmpty()) return ""
        return "$url/feeds"
    }

    /** True when the URL is plain http:// and the host is not on a private/Tailscale network. */
    fun isInsecure(input: String): Boolean {
        val url = normalizeBaseUrl(input)
        if (!url.startsWith("http://", ignoreCase = true)) return false
        return !isPrivateOrTailscaleHost(hostOf(url))
    }
}
