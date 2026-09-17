package com.inspiredandroid.kai.tools

/**
 * True if the URL points at a host on the local network — the traffic Android's local network
 * protection gates. Loopback stays false: it never leaves the device and isn't gated. Public DNS
 * names that happen to resolve to LAN addresses can't be detected without resolving them; those
 * still fail with a plain connection error.
 */
fun isLocalNetworkUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val afterScheme = url.substringAfter("://")
    val authority = afterScheme.substringBefore("/").substringAfterLast("@").lowercase()
    val host = if (authority.startsWith("[")) {
        authority.substringAfter("[").substringBefore("]")
    } else {
        authority.substringBefore(":")
    }
    if (host.isEmpty()) return false

    // Loopback isn't gated by local network protection.
    if (host == "localhost" || host == "::1" || host.startsWith("127.")) return false

    // IPv6 link-local and unique-local addresses. An embedded-IPv4 quad
    // (e.g. ::ffff:192.168.0.1) is judged by its quad below.
    if (host.contains(":")) {
        FetchUrlTool.numericIpv4Octets(host)?.let { octets ->
            return isLanIpv4(octets)
        }
        return host.startsWith("fe8") || host.startsWith("fe9") ||
            host.startsWith("fea") || host.startsWith("feb") ||
            host.startsWith("fc") || host.startsWith("fd")
    }

    // Private and link-local IPv4 ranges, in any numeric notation resolvers
    // accept (decimal, octal/hex parts, inet_aton short forms).
    FetchUrlTool.numericIpv4Octets(host)?.let { return isLanIpv4(it) }

    // mDNS names and bare hostnames resolve on the local network.
    return host.endsWith(".local") || !host.contains(".")
}

/** 10/8, 172.16/12, 192.168/16, 169.254/16 — loopback stays false, it isn't gated. */
private fun isLanIpv4(octets: List<Int>): Boolean {
    val first = octets[0]
    val second = octets[1]
    return first == 10 ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) ||
        (first == 169 && second == 254)
}
