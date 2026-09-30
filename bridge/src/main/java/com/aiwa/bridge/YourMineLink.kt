package com.aiwa.bridge

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

/** The YourMine web app: Claude's sphere is opened in its Build form. */
const val YOURMINE_URL = "https://yourmine-dapp.web.app/"

// Well under what a browser accepts in an address (Chrome: 2 MB) and what an Intent can
// carry (about 1 MB in all): a sphere whose packed form is longer is only put on the clipboard.
private const val MAX_FRAGMENT_CHARS = 300_000

// The file name as YourMine's build.js accepts it in the fragment.
private val FRAGMENT_NAME = Regex("[A-Za-z0-9_.-]{1,60}")

/**
 * The address that makes YourMine open Build → Apps with the sphere's name and code filled in
 * (and nothing else: reading it and "Sign & Submit" stay the user's):
 *
 *     https://yourmine-dapp.web.app/#aiwa=1;<name>;<code>
 *
 * <code> is the source, raw-deflated (java's Deflater with nowrap = the browser's
 * `deflate-raw`), then base64url without padding. It sits in the URL FRAGMENT, which the
 * browser never sends to a server. Null when the name is not a plain file name or the packed
 * sphere is too long for an address.
 */
fun yourMineSphereUrl(name: String, code: String, base: String = YOURMINE_URL): String? {
    if (!FRAGMENT_NAME.matches(name)) return null
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    val packed = ByteArrayOutputStream()
    try {
        deflater.setInput(code.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) packed.write(buffer, 0, deflater.deflate(buffer))
    } finally {
        deflater.end()
    }
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(packed.toByteArray())
    return if (payload.length > MAX_FRAGMENT_CHARS) null else "$base#aiwa=1;$name;$payload"
}
