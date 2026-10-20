package com.localkeep.credential.webauthn

import com.upokecenter.cbor.CBOREncodeOptions
import com.upokecenter.cbor.CBORObject

/** Byte-exact WebAuthn CBOR structures. */
internal object Cbor {
    private val encodeOptions = CBOREncodeOptions.DefaultCtap2Canonical

    /** COSE EC2 public key: P-256 and ES256. */
    fun coseP256PublicKey(
        x: ByteArray,
        y: ByteArray,
    ): ByteArray {
        require(x.size == COORDINATE_SIZE && y.size == COORDINATE_SIZE) {
            "P-256 coordinates must be 32 bytes"
        }
        return CBORObject.NewMap().apply {
            Add(1, 2)
            Add(3, -7)
            Add(-1, 1)
            Add(-2, x)
            Add(-3, y)
        }.EncodeToBytes(encodeOptions)
    }

    /** WebAuthn `fmt=none` attestation object. */
    fun noneAttestationObject(authData: ByteArray): ByteArray =
        CBORObject.NewMap().apply {
            Add("fmt", "none")
            Add("attStmt", CBORObject.NewMap())
            Add("authData", authData)
        }.EncodeToBytes(encodeOptions)

    private const val COORDINATE_SIZE = 32
}
