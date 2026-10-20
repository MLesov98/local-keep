package com.localkeep.credential.webauthn

import kotlin.test.Test
import kotlin.test.assertContentEquals

class CborTest {

    @Test
    fun `coseP256PublicKey encodes a known EC2 P-256 key byte-exactly`() {
        // Fixture: x = 32 bytes of 0x01, y = 32 bytes of 0x02 (arbitrary but fixed — not a real point).
        val x = ByteArray(32) { 0x01 }
        val y = ByteArray(32) { 0x02 }

        // Derivation (CTAP2-canonical, RFC 7049 §3.9 length-first rule: all 5 keys encode to 1
        // byte, so length-first reduces to numeric key order 1, 3, -1, -2, -3 — which happens to
        // match natural insertion order here):
        //   A5                map(5)
        //   01 02              kty(1): 2 (EC2)
        //   03 26              alg(3): -7 (ES256, negative int head = 0x20|6)
        //   20 01              crv(-1): 1 (P-256)
        //   21 5820 <32x01>    x(-2): bstr(32)
        //   22 5820 <32x02>    y(-3): bstr(32)
        val expectedHex = "A5" + "0102" + "0326" + "2001" +
            "215820" + "01".repeat(32) +
            "225820" + "02".repeat(32)

        val actual = Cbor.coseP256PublicKey(x, y)
        assertContentEquals(hexToBytes(expectedHex), actual)
    }

    @Test
    fun `noneAttestationObject encodes a minimal fmt=none object byte-exactly`() {
        // authData is a short 4-byte fixture here (NOT a real 37+-byte authenticatorData) — this
        // test verifies CBOR byte-string/map wrapping only; real authenticatorData layout is
        // covered separately by WebAuthnCodec's own byte-layout logic (Task 2).
        val authData = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

        // Derivation (CTAP2-canonical, RFC 7049 §3.9 length-first rule: text keys sorted by
        // encoded length — "fmt"(4B encoded) < "attStmt"(8B) < "authData"(9B), which happens to
        // match natural insertion order here):
        //   A3                    map(3)
        //   63 666D74             "fmt" (tstr, 3)
        //   64 6E6F6E65           "none" (tstr, 4)
        //   67 61747453746D74     "attStmt" (tstr, 7)
        //   A0                    map(0) — empty attStmt
        //   68 61757468446174 61  "authData" (tstr, 8)
        //   44 DEADBEEF           bstr(4)
        val expectedHex = "A3" +
            "63" + "666D74" + "64" + "6E6F6E65" +
            "67" + "61747453746D74" + "A0" +
            "68" + "6175746844617461" + "44" + "DEADBEEF"

        val actual = Cbor.noneAttestationObject(authData)
        assertContentEquals(hexToBytes(expectedHex), actual)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
