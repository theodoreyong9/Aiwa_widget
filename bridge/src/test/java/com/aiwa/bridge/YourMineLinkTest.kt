package com.aiwa.bridge

import java.util.Base64
import java.util.Random
import java.util.zip.Inflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The same decoding YourMine's build.js does in the browser: base64url, then `deflate-raw`.
// (That the browser really reads what java's Deflater writes was checked in Chromium.)
private fun unpack(url: String): Pair<String, String> {
    val match = Regex("^https://yourmine-dapp\\.web\\.app/#aiwa=1;([A-Za-z0-9_.-]{1,60});([A-Za-z0-9_-]+)$").matchEntire(url)
    assertNotNull("not an address YourMine's build.js accepts: ${url.take(120)}", match)
    val packed = Base64.getUrlDecoder().decode(match!!.groupValues[2])
    val inflater = Inflater(true)
    inflater.setInput(packed)
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (!inflater.finished()) {
        val n = inflater.inflate(buffer)
        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
        out.write(buffer, 0, n)
    }
    inflater.end()
    return match.groupValues[1] to out.toString("UTF-8")
}

class YourMineLinkTest {
    private val sphere = "(function(){window.YM_S=window.YM_S||{};window.YM_S['radio.sphere.js']={name:'Radio'};})();\n" + "// é ⬡ 🔮\n".repeat(400)

    @Test fun theAddressCarriesTheNameAndTheCodeBackExactly() {
        val (name, code) = unpack(yourMineSphereUrl("radio.sphere.js", sphere)!!)
        assertEquals("radio.sphere.js", name)
        assertEquals(sphere, code)
    }

    @Test fun theCodeIsPackedSmallerThanItIs() {
        val url = yourMineSphereUrl("radio.sphere.js", sphere)!!
        assertTrue(url.length < sphere.length / 2)
    }

    @Test fun aNameThatIsNotAPlainFileNameIsRefused() {
        for (bad in listOf("", "../x.sphere.js", "a b.sphere.js", "a;b", "x".repeat(61), "é.sphere.js")) {
            assertNull("accepted: $bad", yourMineSphereUrl(bad, sphere))
        }
    }

    @Test fun aSphereTooLongForAnAddressIsLeftToTheClipboard() {
        val noise = ByteArray(400_000).also { Random(7).nextBytes(it) }
        assertNull(yourMineSphereUrl("noise.sphere.js", Base64.getEncoder().encodeToString(noise)))
    }

    @Test fun anotherBaseAddressCanBeGiven() {
        val url = yourMineSphereUrl("a.sphere.js", "x", base = "http://localhost:8080/")!!
        assertTrue(url.startsWith("http://localhost:8080/#aiwa=1;a.sphere.js;"))
    }
}
