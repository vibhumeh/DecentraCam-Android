package com.example.decentracam
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.funkatronics.kborsh.Borsh
import com.solana.mobilewalletadapter.clientlib.*
import com.solana.networking.Rpc20Driver
import com.solana.programs.SystemProgram
import com.solana.publickey.*
import com.solana.rpccore.JsonRpc20Request
import com.solana.rpccore.Rpc20Response
import com.solana.serialization.AnchorInstructionSerializer
import com.solana.transaction.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.funkatronics.encoders.Base58
import java.util.*
import org.json.JSONArray
import android.content.Context
import com.ditchoom.buffer.toArray
import diglol.crypto.Ed25519
import com.solana.signer.Ed25519Signer
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.nio.ByteOrder
import io.ktor.client.*
import com.solana.networking.HttpNetworkDriver
import com.solana.networking.HttpRequest

//loading privatekey
fun loadKeyFromJsonRaw(context: Context, rawId: Int): UByteArray {
    val json = context.resources.openRawResource(rawId)
        .bufferedReader().use { it.readText().trim() }

    val jsonArray = JSONArray(json.toString())
    val byteList = ByteArray(jsonArray.length()) {
        jsonArray.getInt(it).toByte()
    }

    return byteList.toUByteArray()
}

//system program to verify signature
//private val ED25519_PROGRAM_ID =
//    SolanaPublicKey.from("Ed25519SigVerify111111111111111111111111111")

private fun ULong.toLe8(): ByteArray {
    var v = this
    return ByteArray(8) { i ->
        val b = (v and 0xFFu).toByte()
        v = v shr 8
        b
    }
}

/* ---------- Anchor arg wrappers ---------- */

@Serializable
object Args_initialize

@Serializable
data class Args_verifyEd25519Instruction(
    val message: ByteArray,
    val signature: ByteArray
)

@Serializable
data class Args_storeHash(
    val hashId: ULong
)//first ID=1

/* ---------- constants to actually change ---------- */

private const val RPC_URL = "https://api.devnet.solana.com"
private const val PROGRAM_ID_STR = "EbRPnJaaBXkbur5nPB9BTfSf3w8FbiYnJQDAgmp78Esx"  //won't be found. will fail.        // your on‑chain program ID
private const val IDENTITY_URI = "https://raw.githubusercontent.com/solana-labs/wallet-adapter/main"
private const val ICON_URI     = "packages/wallet-adapter/example/favicon.png"
private const val APP_NAME     = "Decentracam"





private fun anchorDisc(ixName: String): ByteArray =
    MessageDigest.getInstance("SHA-256")
        .digest("global:$ixName".toByteArray())
        .copyOfRange(0, 8)
/* ---------- helper to fetch a recent blockhash ---------- */

private suspend fun Rpc20Driver.latestBlockhash(): String {
    val reqId = UUID.randomUUID().toString()
    val req = JsonRpc20Request(
        id = reqId,
        method = "getLatestBlockhash",
        params = JsonArray(
            listOf(JsonObject(mapOf("commitment" to JsonPrimitive("finalized"))))
        )
    )
    val resp: Rpc20Response<SolanaResponse<BlockhashResponse>> =
        makeRequest(req, SolanaResponse.serializer(BlockhashResponse.serializer()))

    resp.error?.let { throw RuntimeException("RPC error ${it.code}: ${it.message}") }
    return resp?.result?.value?.blockhash
        ?: error("RPC returned null – no blockhash available")

}
//TRANSACTION BUILDERS
@Serializable
object NoArgs
suspend fun buildVerifyIx(
    feePayer: SolanaPublicKey,
    counterPda: SolanaPublicKey,
    message: ByteArray,
    signature: ByteArray
): TransactionInstruction {
    System.setProperty("buffer.factory.jvm", "com.ditchoom.buffer.DefaultJvmBufferFactory")

    /* Anchor-encode args */
//    val data = Borsh.encodeToByteArray(
//        AnchorInstructionSerializer<Args_verifyEd25519Instruction>("verifyEd25519Instruction"),
//        Args_verifyEd25519Instruction(message, signature)
//    )

    val buf = ByteBuffer
        .allocate(8 + 4 + message.size + 4 + signature.size)
        .order(ByteOrder.LITTLE_ENDIAN)

    buf.put(anchorDisc("verify_ed25519_instruction"))
    buf.putInt(message.size)
    buf.put(message)
    buf.putInt(signature.size)
    buf.put(signature)

    val programId = SolanaPublicKey.from(PROGRAM_ID_STR)
    val instructionSysvar =
        SolanaPublicKey.from("Sysvar1nstructions1111111111111111111111111")

    return TransactionInstruction(
        programId,
        listOf(
            AccountMeta(feePayer, true, true),      // signer
            AccountMeta(instructionSysvar, false, false),
            AccountMeta(counterPda, false, true)    // counter
        ),
        buf.array()
    )
}





suspend fun buildStoreHashIx(
    feePayer: SolanaPublicKey,
    counterPda: SolanaPublicKey,
    hashId: ULong
): TransactionInstruction {

    val programId = SolanaPublicKey.from(PROGRAM_ID_STR)

    /* 1. derive hashes PDA = ["hash", signer, hashId_le] */
    val hashesPda = ProgramDerivedAddress.find(
        listOf(
            "hash".encodeToByteArray(),
            feePayer.bytes,
            hashId.toLe8()
        ),
        programId
    ).getOrThrow()

    /* 2. instruction data = discriminator + hash_id (u64 LE) */
    val data = ByteBuffer
        .allocate(16)                        // 8 + 8
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(anchorDisc("store_hash"))
        .putLong(hashId.toLong())           // LE because of order() above
        .array()

    /* 3. build ix — account order must match Rust struct */
    return TransactionInstruction(
        programId,
        listOf(
            AccountMeta(feePayer,  true,  true),   // signer
            AccountMeta(hashesPda, false, true),   // new Hashes PDA
            AccountMeta(counterPda, false, true),  // existing Counter PDA
            AccountMeta(SystemProgram.PROGRAM_ID, false, false)
        ),
        data
    )
}





////FINAL FUNCTIONS
/* ---------- do the initialize in one go ---------- */
//@Serializable
//object NoArgs //Not inside a function

fun initialiseAccount(
    lifecycleScope: LifecycleCoroutineScope,
    wallet: MobileWalletAdapter,
    sender: ActivityResultSender
) {
    val rpc = Rpc20Driver(RPC_URL, KtorHttpDriver())

    lifecycleScope.launch {
        /* 1. Connect to the wallet and grab the fee‑payer pubkey */
        val auth = wallet.connect(sender) as? TransactionResult.Success
            ?: error("wallet connect failed / cancelled")
        val feePayer = SolanaPublicKey.from(Base58.encodeToString( auth.authResult.accounts.first().publicKey))
// --- 0.  Program + PDA as before ---
        val programId  = SolanaPublicKey.from(PROGRAM_ID_STR)
        val counterPda = ProgramDerivedAddress.find(
            listOf("counter".encodeToByteArray(), feePayer.bytes),
            programId
        ).getOrThrow()

        // --- 1. Anchor discriminator only (Initialize has no args) ---

        val initData = Borsh.encodeToByteArray(
            AnchorInstructionSerializer<NoArgs>("initialize"),
            NoArgs
        )

// --- 2.   **Accounts in exact Rust order** ---
        val ix = TransactionInstruction(
            programId,
            listOf(
                AccountMeta(feePayer,    true,  true),   // signer
                AccountMeta(counterPda,  false, true),   // counter PDA (init)
                AccountMeta(SystemProgram.PROGRAM_ID, false, false) // system_program
            ),
            initData
        )

        /* 5. Build, ask wallet to sign & send */
        val blockhash = rpc.latestBlockhash()
        val msg = Message.Builder()
            .addInstruction(ix)
            .setRecentBlockhash(blockhash)
            //.setFeePayer(feePayer)
            .build()
        val unsignedTx = Transaction(msg).serialize()

        val result = wallet.transact(sender) {
            reauthorize(
                identityUri = Uri.parse(IDENTITY_URI),
                iconUri = Uri.parse(ICON_URI),
                identityName = APP_NAME,
                authToken = auth.authResult.authToken
            )
//            val simResp = rpc.simulateTransaction(unsignedTx)   // helper you write once
//            Log.d("SIM", simResp.logs.joinToString("\n"))

            signAndSendTransactions(arrayOf(unsignedTx))
        }

        when (result) {
            is TransactionResult.Success -> Log.d("INIT", "sig $result")
            is TransactionResult.Failure -> {
                Log.e("INIT", "Tx failed: ${result.e.message}")
                return@launch              // don’t throw, just exit coroutine
            }

            else -> error("wallet flow aborted")
        }
    }
}
fun verify_sig(message: ByteArray,
    context: Context,
    lifecycleScope: LifecycleCoroutineScope,
               wallet: MobileWalletAdapter,
               sender: ActivityResultSender){
lifecycleScope.launch {
    val auth = wallet.connect(sender) as? TransactionResult.Success
        ?: error("wallet connect failed / cancelled")
    val feePayer = SolanaPublicKey.from(Base58.encodeToString( auth.authResult.accounts.first().publicKey))
    val secretKey = loadKeyFromJsonRaw(context, R.raw.auth_privkey_temp).toByteArray().sliceArray(0 until 32)//extract seed from privatekey

    val publicKey = loadKeyFromJsonRaw(context, R.raw.auth_pubkey_temp)
    val keyPair=Ed25519.generateKeyPair(secretKey)
    val signer = object : Ed25519Signer() {
        override val publicKey: ByteArray get() = keyPair.publicKey
        override suspend fun signPayload(payload: ByteArray): ByteArray = Ed25519.sign(keyPair, payload)
    }
    val sig = signer.signPayload(message)
    //Log.d("KEY_SIZE", "publicKey size: ${final_publicKey.size}")

    val edIx = buildEd25519IxWithPublicKey(publicKey = publicKey.toByteArray(), message = message, signature = sig,feePayer=feePayer)

    val blockhash = rpc.latestBlockhash()

    val msgEd = Message.Builder()
        .addInstruction(edIx)
        .setRecentBlockhash(blockhash)
        //.setFeePayer(feePayer)
        .build()

    val txEd = Transaction(msgEd).serialize()

    val resultEd = wallet.transact(sender) {
        reauthorize(
            identityUri = Uri.parse(IDENTITY_URI),
            iconUri = Uri.parse(ICON_URI),
            identityName = APP_NAME,
            authToken = auth.authResult.authToken
        )
        signAndSendTransactions(arrayOf(txEd))
    }

    when (resultEd) {
        is TransactionResult.Success -> Log.d("ED25519", "Signature: $resultEd")
        is TransactionResult.Failure -> {
            Log.e("ED25519", "Failed: ${resultEd.e.message}")
            return@launch
        }

        else -> error("Wallet flow aborted")
    }
}




}
fun authVerification(
    lifecycleScope: LifecycleCoroutineScope,
    wallet: MobileWalletAdapter,
    sender: ActivityResultSender,
    message: ByteArray,
    context: Context
) {
    val rpc = Rpc20Driver(RPC_URL, KtorHttpDriver())

    lifecycleScope.launch {
        /* 1. Connect to the wallet and grab the fee‑payer pubkey */
        val auth = wallet.connect(sender) as? TransactionResult.Success
            ?: error("wallet connect failed / cancelled")
        val feePayer = SolanaPublicKey.from(Base58.encodeToString( auth.authResult.accounts.first().publicKey))
        val secretKey = loadKeyFromJsonRaw(context, R.raw.auth_privkey_temp).toByteArray().sliceArray(0 until 32)//extract seed from privatekey

        val publicKey = loadKeyFromJsonRaw(context, R.raw.auth_pubkey_temp)
        val keyPair=Ed25519.generateKeyPair(secretKey)
        val signer = object : Ed25519Signer() {
            override val publicKey: ByteArray get() = keyPair.publicKey
            override suspend fun signPayload(payload: ByteArray): ByteArray = Ed25519.sign(keyPair, payload)
        }
        val sig = signer.signPayload(message)

// --- 0.  Program + PDA as before ---
        val programId  = SolanaPublicKey.from(PROGRAM_ID_STR)
        val counterPda = ProgramDerivedAddress.find(
            listOf("counter".encodeToByteArray(), feePayer.bytes),
            programId
        ).getOrThrow()

        // --- 1. Anchor discriminator only (Initialize has no args) ---


// --- 2.   **Accounts in exact Rust order** ---
        val ix = buildVerifyIx(feePayer=feePayer,counterPda = counterPda, message = message , signature = sig)

        /* 5. Build, ask wallet to sign & send */
        val blockhash = rpc.latestBlockhash()
        val msg = Message.Builder()
            .addInstruction(ix)
            .setRecentBlockhash(blockhash)
            //.setFeePayer(feePayer)
            .build()
        val unsignedTx = Transaction(msg).serialize()

        val result = wallet.transact(sender) {
            reauthorize(
                identityUri = Uri.parse(IDENTITY_URI),
                iconUri = Uri.parse(ICON_URI),
                identityName = APP_NAME,
                authToken = auth.authResult.authToken
            )
//            val simResp = rpc.simulateTransaction(unsignedTx)   // helper you write once
//            Log.d("SIM", simResp.logs.joinToString("\n"))

            signAndSendTransactions(arrayOf(unsignedTx))
        }

        when (result) {
            is TransactionResult.Success -> Log.d("Auth Verification", "sig $result")
            is TransactionResult.Failure -> {
                Log.e("Auth Verification", "Tx failed: ${result.e.message}")
                return@launch              // don’t throw, just exit coroutine
            }

            else -> error("wallet flow aborted")
        }
    }
}



fun storeHash(
    lifecycleScope: LifecycleCoroutineScope,
    wallet: MobileWalletAdapter,
    sender: ActivityResultSender,
    hashId: Int//converted to Ulong afterwards
) {
    val rpc = Rpc20Driver(RPC_URL, KtorHttpDriver())

    lifecycleScope.launch {
        /* 1. Connect to the wallet and grab the fee‑payer pubkey */
        val auth = wallet.connect(sender) as? TransactionResult.Success
            ?: error("wallet connect failed / cancelled")
        val feePayer = SolanaPublicKey.from(Base58.encodeToString( auth.authResult.accounts.first().publicKey))

// --- 0.  Program + PDA as before ---
        val programId  = SolanaPublicKey.from(PROGRAM_ID_STR)
        val counterPda = ProgramDerivedAddress.find(
            listOf("counter".encodeToByteArray(), feePayer.bytes),
            programId
        ).getOrThrow()

        // --- 1. Anchor discriminator only (Initialize has no args) ---
// --- 2.   **Accounts in exact Rust order** ---
        val ix = buildStoreHashIx(feePayer=feePayer, counterPda = counterPda, hashId = hashId.toULong())

        /* 5. Build, ask wallet to sign & send */
        val blockhash = rpc.latestBlockhash()
        val msg = Message.Builder()
            .addInstruction(ix)
            .setRecentBlockhash(blockhash)
            //.setFeePayer(feePayer)
            .build()
        val unsignedTx = Transaction(msg).serialize()

        val result = wallet.transact(sender) {
            reauthorize(
                identityUri = Uri.parse(IDENTITY_URI),
                iconUri = Uri.parse(ICON_URI),
                identityName = APP_NAME,
                authToken = auth.authResult.authToken
            )
//            val simResp = rpc.simulateTransaction(unsignedTx)   // helper you write once
//            Log.d("SIM", simResp.logs.joinToString("\n"))
            val txBytes = Transaction(msg).serialize()

            val sim = rpc.simulateTransaction(txBytes)

            if (sim?.err != null) {
                Log.e("SIMULATION RESULT", "Program error: ${sim.err}")
            }
            sim?.logs?.forEach { Log.d("SIMULATION RESULT", it) }
            Log.d("SIMULATION RESULT", "CAT")
            signAndSendTransactions(arrayOf(unsignedTx))
        }

        when (result) {
            is TransactionResult.Success -> Log.d("Auth Verification", "sig $result")
            is TransactionResult.Failure -> {
                Log.e("Auth Verification", "Tx failed: ${result.e.message}")
                return@launch              // don’t throw, just exit coroutine
            }

            else -> error("wallet flow aborted")
        }
    }
}



fun submitHashBundle(
    context: Context,
    lifecycleScope: LifecycleCoroutineScope,
    wallet: MobileWalletAdapter,
    sender: ActivityResultSender,
    // 32-byte SHA-256 hex of your image
    message: ByteArray,
    // counter.hash_id you expect (starts at 1)
    hashId: ULong,
    // resource IDs of your keys in res/raw
    runSimFirst: Boolean = true            // set false in production
) {
    lifecycleScope.launch {
        /* 0. connect to wallet → fee payer */
        val auth = wallet.connect(sender) as? TransactionResult.Success
            ?: error("wallet connect cancelled")
        val feePayer = SolanaPublicKey.from(Base58.encodeToString( auth.authResult.accounts.first().publicKey))

        /* 1. RPC driver */
        val rpc = Rpc20Driver("https://api.devnet.solana.com", KtorHttpDriver())

        /* 2. derive counter PDA */
        val programId = SolanaPublicKey.from(PROGRAM_ID_STR)
        val counterPda = ProgramDerivedAddress.find(
            listOf("counter".encodeToByteArray(), feePayer.bytes),
            programId
        ).getOrThrow()

        /* 3. load keys & sign message */
        val secretKey = loadKeyFromJsonRaw(context, R.raw.auth_privkey_temp).toByteArray().sliceArray(0 until 32)//extract seed from privatekey

        val publicKey = loadKeyFromJsonRaw(context, R.raw.auth_pubkey_temp)
        val keyPair=Ed25519.generateKeyPair(secretKey)
        val signer = object : Ed25519Signer() {
            override val publicKey: ByteArray get() = keyPair.publicKey
            override suspend fun signPayload(payload: ByteArray): ByteArray = Ed25519.sign(keyPair, payload)
        }
        val signature = signer.signPayload(message)

        /* 4. build three instructions in correct order */
        val edIx = buildEd25519IxWithPublicKey(
            publicKey = publicKey.toByteArray(),
            message = message,
            signature = signature,
            feePayer = feePayer,          // only needed for discriminator calc
            instructionIndex = null       // 0xFFFF = “this instruction”
        )

        val verifyIx = buildVerifyIx(
            feePayer = feePayer,
            counterPda = counterPda,
            message = message,
            signature = signature
        )

        val storeIx = buildStoreHashIx(
            feePayer = feePayer,
            counterPda = counterPda,
            hashId = hashId
        )

        /* 5. build bundled tx */
        val recentBlockhash = rpc.latestBlockhash()
        val msg = Message.Builder()
            //.setFeePayer(feePayer)
            .setRecentBlockhash(recentBlockhash)
            .addInstruction(edIx)       // 1️⃣ native ed25519 verify
            .addInstruction(verifyIx)   // 2️⃣ your program’s verify
            .addInstruction(storeIx)    // 3️⃣ store hash
            .build()
        val txBytes = Transaction(msg).serialize()

        /* 6. optional simulation */
        if (runSimFirst) {
            val sim = rpc.simulateTransaction(txBytes)
            sim?.logs?.forEach { Log.d("SIM", it) }
            sim?.err?.let { error("Sim failed: $it") }
        }

        /* 7. hand to wallet */
        val result = wallet.transact(sender) {
            reauthorize(
                identityUri = Uri.parse(IDENTITY_URI),
                iconUri = Uri.parse(ICON_URI),
                identityName = APP_NAME,
                authToken = auth.authResult.authToken
            )
            signAndSendTransactions(arrayOf(txBytes))
        }

        when (result) {
            is TransactionResult.Success -> {Log.d("Auth Verification", "sig ${result.payload.signatures}")

            }
            is TransactionResult.Failure -> {
                Log.e("Overall", "Tx failed: ${result.e.message}")
                return@launch              // don’t throw, just exit coroutine
            }

            else -> error("wallet flow aborted")
        }
    }
}
