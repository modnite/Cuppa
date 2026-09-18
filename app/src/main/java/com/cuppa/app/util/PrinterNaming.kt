package com.cuppa.app.util

/**
 * Single source of truth for turning a printer's display name into the URI-safe resource name used
 * in `/printers/<name>` paths, the mDNS `rp` TXT record, and the share URI shown in the UI.
 *
 * MUST produce exactly the same result as sanitizeResourceName in cups_server.cpp: the native IPP
 * server uses it to find the queue a client asks for, so any drift means a client following our
 * own advertised path gets "not found". Anything outside [A-Za-z0-9._-] becomes '_' (runs of them
 * collapse into one, none at the ends), so "USB Printer (0x09C5:0x0588)" becomes
 * "USB_Printer_0x09C5_0x0588".
 */
object PrinterNaming {
    fun resourceName(name: String): String {
        val out = StringBuilder(name.length)
        for (c in name) {
            val plain = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'
            if (plain) {
                out.append(c)
            } else if (out.isNotEmpty() && out.last() != '_') {
                out.append('_')
            }
        }
        while (out.isNotEmpty() && out.last() == '_') out.setLength(out.length - 1)
        return if (out.isEmpty()) "printer" else out.toString()
    }

    /**
     * Name with the decorations that make one physical printer show up as several distinct
     * mDNS entries stripped off: a trailing " [mac-address]" some printers add for their second
     * interface, a trailing " (n)" that mDNS appends on a name conflict, and Cuppa's own
     * " (Cuppa)" suffix. Lowercased, for comparison only.
     */
    fun comparableName(name: String): String {
        var n = name
        var previous: String
        do {
            previous = n
            n = n.replace(Regex("\\s*\\[[0-9A-Fa-f:]{6,17}]\\s*$"), "")
                .replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
                .replace(Regex("\\s*\\(Cuppa\\)\\s*$", RegexOption.IGNORE_CASE), "")
                .trim()
        } while (n != previous)
        return n.lowercase()
    }
}
