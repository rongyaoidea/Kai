package com.inspiredandroid.kai.tools

/**
 * Safety fence for cross-app automation (Android AccessibilityService + Shizuku).
 *
 * Pure Kotlin so it stays unit-testable in commonTest. The Android controller
 * feeds it the foreground package name; it answers whether an interaction may
 * proceed. Reads are always allowed — a model that cannot see cannot act safely.
 * Writes (tap / input / scroll / launch / privileged shell) additionally require
 * the write switch the caller already checked and must pass this gate.
 *
 * Two layers:
 * 1. Sensitive blocklist — packages whose UI must never be driven: banking and
 *    payment apps, password managers / authenticators, and the system package
 *    installer (silent-install protection). Matched by substring so vendor
 *    variants are covered without maintaining an exhaustive list.
 * 2. User allowlist — when non-empty, interaction is limited to exactly those
 *    package names. Empty means "everywhere except the blocklist".
 */
object AutomationPolicy {

    private val SENSITIVE_SUBSTRINGS = listOf(
        "bank",
        "pay",
        "wallet",
        "finance",
        "alipay",
        "wechatpay",
        "unionpay",
        "password",
        "passwd",
        "keepass",
        "bitwarden",
        "1password",
        "lastpass",
        "dashlane",
        "authenticator",
        "authy",
        "otp",
        "packageinstaller",
    )

    sealed interface Verdict {
        data object Allow : Verdict
        data class Deny(val reason: String) : Verdict
    }

    fun checkInteract(packageName: String?, allowedApps: Set<String>): Verdict {
        if (packageName.isNullOrBlank()) {
            return Verdict.Deny("foreground package is unknown; refusing to interact blindly")
        }
        val lowered = packageName.lowercase()
        val hit = SENSITIVE_SUBSTRINGS.firstOrNull { it in lowered }
        if (hit != null) {
            return Verdict.Deny(
                "package $packageName looks sensitive (matched \"$hit\"); " +
                    "interaction is blocked. Ask the user to do this step by hand.",
            )
        }
        if (allowedApps.isNotEmpty() && packageName !in allowedApps) {
            return Verdict.Deny(
                "package $packageName is not in the automation allowlist. " +
                    "Ask the user to approve it in Settings → Agent → Automation first.",
            )
        }
        return Verdict.Allow
    }

    fun describeForPrompt(allowedApps: Set<String>): String = if (allowedApps.isEmpty()) {
        "interact with any app except banking/payment, password-manager, authenticator, " +
            "and package-installer apps, which are always blocked"
    } else {
        "interact only inside these apps: ${allowedApps.sorted().joinToString(", ")}. " +
            "Anything else — including banking/payment and password-manager apps, which are " +
            "always blocked — must be refused with an explanation"
    }
}
