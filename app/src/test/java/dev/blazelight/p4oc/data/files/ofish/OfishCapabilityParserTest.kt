package dev.blazelight.p4oc.data.files.ofish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfishCapabilityParserTest {
    @Test
    fun `parse available sha256sum capabilities`() {
        val result = OfishCapabilityParser.parse(
            """
            unrelated
            #OFISH_HELLO
            caps base64=1 base64_decode=-d hash=sha256sum mv=1 mkdir=1 rm=1 awk=1 mktemp=1 chmod=1 mode=stat -c %a
            ### 200 ok
            """.trimIndent(),
        )

        assertTrue(result is OfishProbeResult.Available)
        val caps = (result as OfishProbeResult.Available).capabilities
        assertEquals(HashCommand.SHA256SUM, caps.hashCommand)
        assertEquals("-d", caps.base64DecodeFlag)
        assertEquals(ModeCommand.STAT_GNU, caps.modeCommand)
        assertTrue(caps.supportsMutation)
    }

    @Test
    fun `parse available shasum and BSD base64 decode`() {
        val result = OfishCapabilityParser.parse(
            "caps base64=1 base64_decode=-D hash=shasum -a 256 " +
                "mv=1 mkdir=1 rm=1 awk=1 mktemp=1 chmod=1 mode=stat -f %Lp\n### 200 ok",
        )

        assertTrue(result is OfishProbeResult.Available)
        val caps = (result as OfishProbeResult.Available).capabilities
        assertEquals(HashCommand.SHASUM_256, caps.hashCommand)
        assertEquals("-D", caps.base64DecodeFlag)
        assertEquals(ModeCommand.STAT_BSD, caps.modeCommand)
    }

    @Test
    fun `parse missing capabilities from 501 status`() {
        val result = OfishCapabilityParser.parse(
            "caps base64=0 base64_decode= hash= mv=1 mkdir=1 rm=1 awk=0 " +
                "mktemp=1 chmod=1 mode=stat -c %a\n### 501 caps_missing base64 hash awk",
        )

        assertTrue(result is OfishProbeResult.Missing)
        assertEquals(listOf("base64", "hash", "awk"), (result as OfishProbeResult.Missing).missing)
    }

    @Test
    fun `malformed output fails`() {
        assertTrue(OfishCapabilityParser.parse("### 200 ok") is OfishProbeResult.Failed)
        assertTrue(OfishCapabilityParser.parse("caps base64=1") is OfishProbeResult.Failed)
        assertTrue(
            OfishCapabilityParser.parse(
                "### 200 ok\ncaps base64=1 base64_decode=-d hash=sha256sum " +
                    "mv=1 mkdir=1 rm=1 awk=1 mktemp=1 chmod=1 mode=stat -c %a",
            ) is OfishProbeResult.Failed,
        )
    }
}
