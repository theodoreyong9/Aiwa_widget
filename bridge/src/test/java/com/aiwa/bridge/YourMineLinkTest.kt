package com.aiwa.bridge

import java.util.Base64
import java.util.Random
import java.util.zip.Inflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The same decoding the pages do in the browser: base64url, then `deflate-raw`. (That the browser really
// reads what java's Deflater writes was checked in Chromium, on YourMine's and on the Aiwa wallet page.)
private fun unpack(pattern: Regex, url: String): Pair<String, String> {
    val match = pattern.matchEntire(url)
    assertNotNull("not an address the page's fragment reader accepts: ${url.take(120)}", match)
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

// The exact patterns of the two pages: YourMine's build.js and the Aiwa wallet page's index.html.
private val YOURMINE = Regex("^https://yourmine-dapp\\.web\\.app/#aiwa=1;([A-Za-z0-9_.-]{1,60});([A-Za-z0-9_-]+)$")
private val AIWA = Regex("^https://theodoreyong9\\.github\\.io/Aiwa_project/#publish=1;([A-Za-z0-9_.-]{1,60});([A-Za-z0-9_-]+)$")

class YourMineLinkTest {
    private val sphere = "(function(){window.YM_S=window.YM_S||{};window.YM_S['radio.sphere.js']={name:'Radio'};})();\n" + "// é ⬡ 🔮\n".repeat(400)
    private val contract = "<!doctype html><title>Demo</title><script type=\"module\">/* é ✓ */</script>\n" + "<!-- pad -->\n".repeat(400)

    @Test fun theSphereAddressCarriesTheNameAndTheCodeBackExactly() {
        val (name, code) = unpack(YOURMINE, yourMineSphereUrl("radio.sphere.js", sphere)!!)
        assertEquals("radio.sphere.js", name)
        assertEquals(sphere, code)
    }

    @Test fun theContractAddressCarriesTheNameAndTheCodeBackExactly() {
        val (name, code) = unpack(AIWA, aiwaContractUrl("demo-app", contract)!!)
        assertEquals("demo-app", name)
        assertEquals(contract, code)
    }

    @Test fun theCodeIsPackedSmallerThanItIs() {
        assertTrue(yourMineSphereUrl("radio.sphere.js", sphere)!!.length < sphere.length / 2)
        assertTrue(aiwaContractUrl("demo-app", contract)!!.length < contract.length / 2)
    }

    @Test fun aNameThatIsNotAPlainFileNameIsRefused() {
        for (bad in listOf("", "../x.sphere.js", "a b.sphere.js", "a;b", "x".repeat(61), "é.sphere.js")) {
            assertNull("accepted: $bad", yourMineSphereUrl(bad, sphere))
            assertNull("accepted: $bad", aiwaContractUrl(bad, contract))
        }
    }

    @Test fun somethingTooLongForAnAddressIsLeftToTheClipboard() {
        val noise = Base64.getEncoder().encodeToString(ByteArray(400_000).also { Random(7).nextBytes(it) })
        assertNull(yourMineSphereUrl("noise.sphere.js", noise))
        assertNull(aiwaContractUrl("noise", noise))
    }

    @Test fun anotherBaseAddressCanBeGiven() {
        assertTrue(yourMineSphereUrl("a.sphere.js", "x", base = "http://localhost:8080/")!!.startsWith("http://localhost:8080/#aiwa=1;a.sphere.js;"))
        assertTrue(aiwaContractUrl("a", "x", base = "http://localhost:8081/")!!.startsWith("http://localhost:8081/#publish=1;a;"))
    }
}
