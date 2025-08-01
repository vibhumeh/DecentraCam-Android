package com.example.decentracam

import com.solana.programs.SystemProgram
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

private val ED25519_PROG_ID =
    SolanaPublicKey.from("Ed25519SigVerify111111111111111111111111111")

/**
 * Kotlin equivalent of web3.js Ed25519Program.createInstructionWithPublicKey
 * Layout: [u8 numSigs][u8 padding][14-byte offsets][32B pubkey][64B sig]
 * All offsets are u16 LE and relative to the start of instruction data.
 *
 * instructionIndex:
 *   null -> 0xFFFF (means “this instruction”, same as web3.js default)
 *   else -> explicit index
 */
fun buildEd25519IxWithPublicKey(
    publicKey: ByteArray,
    message: ByteArray,
    signature: ByteArray,
    feePayer:SolanaPublicKey,
    instructionIndex: Int? = null
): TransactionInstruction {
    require(publicKey.size == 32) { "publicKey must be 32 bytes" }
    require(signature.size == 64) { "signature must be 64 bytes" }
    require(message.size <= 0xFFFF) { "message too long for u16 length" }

    val headerLen = 16 // ED25519_INSTRUCTION_LAYOUT.span in web3.js
    val publicKeyOffset   = headerLen
    val signatureOffset   = publicKeyOffset + publicKey.size         // 16 + 32 = 48
    val messageDataOffset = signatureOffset + signature.size         // 48 + 64 = 112
    val messageDataSize   = message.size

    val idxShort: Short = ((instructionIndex ?: 0xFFFF) and 0xFFFF).toShort()

    // Build header (little endian)
    val header = ByteBuffer.allocate(headerLen).order(ByteOrder.LITTLE_ENDIAN)
    header.put(1)                         // numSignatures
    header.put(0)                         // padding
    header.putShort(signatureOffset.toShort())
    header.putShort(idxShort)             // signature_instruction_index
    header.putShort(publicKeyOffset.toShort())
    header.putShort(idxShort)             // public_key_instruction_index
    header.putShort(messageDataOffset.toShort())
    header.putShort(messageDataSize.toShort())
    header.putShort(idxShort)             // message_instruction_index

    // Concatenate: header | pubkey | signature | message
    val data = ByteArray(headerLen + 32 + 64 + messageDataSize)
    System.arraycopy(header.array(), 0, data, 0, headerLen)
    System.arraycopy(publicKey,      0, data, publicKeyOffset, publicKey.size)
    System.arraycopy(signature,      0, data, signatureOffset, signature.size)
    System.arraycopy(message,        0, data, messageDataOffset, messageDataSize)

    return TransactionInstruction(
        ED25519_PROG_ID,
        //emptyList<AccountMeta>(),   // native program uses no accounts
        listOf(
            AccountMeta(feePayer,    true,  true),   // signer)
        ),
        data
    )
}
