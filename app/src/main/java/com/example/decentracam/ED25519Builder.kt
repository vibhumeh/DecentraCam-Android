package com.example.decentracam

import com.solana.programs.SystemProgram
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val ED25519_PROG_ID =
    SolanaPublicKey.from("Ed25519SigVerify111111111111111111111111111")

/**
 * Build a Solana ed25519-signature-verify instruction (1 sig).
 *
 * @param message   Bytes that were signed
 * @param signature 64-byte detached sig (R || S)
 * @param pubkey    32-byte signer pubkey
 */
fun buildEd25519Ix(
    message: ByteArray,
    signature: ByteArray,
    pubkey: ByteArray,
    feePayer:SolanaPublicKey
): TransactionInstruction {
    require(signature.size == 64) { "signature must be 64 bytes" }
    require(pubkey.size == 32)    { "pubkey must be 32 bytes" }
    require(message.size <= UShort.MAX_VALUE.toInt()) { "message too long" }

    /* --- 1. offsets ------------------------------------------------------- */
    val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
    val sigOffset      = 16                                          // DATA_START
    val pubkeyOffset   = sigOffset + signature.size                  // 80
    val msgOffset      = pubkeyOffset + pubkey.size                  // 112
    val msgSize        = message.size

    header.put(1)                // numSignatures
    header.put(0)                // padding
    header.putShort(sigOffset.toShort())
    header.putShort(0)           // signature_instruction_index (this ix)
    header.putShort(pubkeyOffset.toShort())
    header.putShort(0)           // pubkey_instruction_index
    header.putShort(msgOffset.toShort())
    header.putShort(msgSize.toShort())
    header.putShort(0)           // message_instruction_index

    /* --- 2. full instruction data ---------------------------------------- */
    val data = ByteArray(16 + signature.size + pubkey.size + msgSize)
    System.arraycopy(header.array(),   0, data, 0, 16)
    System.arraycopy(signature,        0, data, sigOffset,     signature.size)
    System.arraycopy(pubkey,           0, data, pubkeyOffset,  pubkey.size)
    System.arraycopy(message,          0, data, msgOffset,     msgSize)

    /* --- 3. build TransactionInstruction --------------------------------- */
    return TransactionInstruction(
        ED25519_PROG_ID,
        //emptyList<AccountMeta>(),   // the native program takes no accounts
        listOf(
            AccountMeta(feePayer,    true,  true),   // signer
        ),
        data
    )
}
