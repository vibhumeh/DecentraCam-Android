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

/* ---------- constants you’ll actually change ---------- */

private const val RPC_URL = "https://api.devnet.solana.com"
private const val PROGRAM_ID_STR = "EbRPnJaaBXkbur5nPB9BTfSf3w8FbiYnJQDAgmp78Esx"  //won't be found. will fail.        // your on‑chain program ID
private const val IDENTITY_URI = "https://raw.githubusercontent.com/solana-labs/wallet-adapter/main"
private const val ICON_URI     = "packages/wallet-adapter/example/favicon.png"
private const val APP_NAME     = "Decentracam"    // or whatever

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

/* ---------- do the initialize in one go ---------- */
@Serializable
object NoArgs // top-level or inside a class, not inside a function

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

        /* 2. Derive the PDA your program expects: seeds = ["counter", feePayer] */
       // val programId = SolanaPublicKey.from(PROGRAM_ID_STR)
//        val seeds = listOf(
//            "counter".encodeToByteArray(),
//            feePayer.bytes                   // second seed = user pubkey
//        )
//        //val counterPda = ProgramDerivedAddress.find(seeds, programId).getOrThrow()

        /* 3. Build Anchor instruction data (8‑byte discr only, no args) */
       // data object NoArgs// empty args marker
//        val initData = Borsh.encodeToByteArray(
//            AnchorInstructionSerializer<NoArgs>("initialize"),
//            NoArgs
//        )
        // If your instruction is called something else, swap the string above.

        /* 4. Compose the instruction */
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
