package com.example.decentracam

import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope

import com.solana.publickey.*
import com.solana.transaction.*
import com.solana.transaction.Transaction
import com.solana.rpccore.JsonRpc20Request
import com.solana.networking.Rpc20Driver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import com.solana.networking.HttpNetworkDriver
import com.solana.networking.HttpRequest

import com.funkatronics.encoders.Base58

import com.solana.programs.SystemProgram
import com.solana.transaction.Blockhash
import com.solana.rpccore.Rpc20Response
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.isSuccess
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch
import android.util.Base64
import io.ktor.http.*
import kotlinx.serialization.json.*
import android.content.Context
import kotlinx.serialization.Serializable

class KtorHttpDriver : HttpNetworkDriver {
    private val client = HttpClient(CIO)

    override suspend fun makeHttpRequest(request: HttpRequest): String {
        val response: HttpResponse = client.request(request.url) {
            method = HttpMethod(request.method)
            headers {
                request.properties.forEach { (key, value) ->
                    append(key, value)
                }
            }
            setBody(request.body)
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("HTTP ${response.status.value}: ${response.status.description}")
        }
        return response.bodyAsText()
    }
}

val rpcUrl = "https://api.devnet.solana.com"
val rpc = Rpc20Driver(rpcUrl, KtorHttpDriver())
// Connect only
fun connectOnly(
    lifecycleScope: LifecycleCoroutineScope,
    walletAdapter: MobileWalletAdapter,
    sender: ActivityResultSender
) {
    lifecycleScope.launchWhenStarted {
        val result = walletAdapter.connect(sender)

        when (result) {
            is TransactionResult.Success -> {
                val account = result.authResult.accounts.first()
                Log.d("WALLET", "Connected: ${account.publicKey}")
            }
            is TransactionResult.NoWalletFound -> {
                Log.e("WALLET", "No wallet app installed")
            }
            is TransactionResult.Failure -> {
                Log.e("WALLET", "Connect failed: ${result.e}")
            }
        }
    }
}

fun createBlockhashRequest(commitment: String = "finalized", requestId: String): JsonRpc20Request {
    val params = JsonArray(
        listOf(
            //JsonObject(emptyMap()), // empty object as required param
            //mapOf("commitment" to JsonPrimitive(commitment))
            JsonObject(mapOf("commitment" to JsonPrimitive(commitment)))
        )
    )
    return JsonRpc20Request(
        id = requestId,
        method = "getLatestBlockhash",
        params = params
    )
}
suspend fun getLatestBlockhash(): Blockhash {
    val rpc = Rpc20Driver("https://api.devnet.solana.com", KtorHttpDriver())

    val requestId = UUID.randomUUID().toString()
    val request = createBlockhashRequest("finalized", requestId)

    // The response serializer depends on the structure of the response
    val response: Rpc20Response<SolanaResponse<BlockhashResponse>> =
        rpc.makeRequest(
            request,
            SolanaResponse.serializer(BlockhashResponse.serializer())
        )
    Log.d("RPC", "FULL RESPONSE: $response")


    response.error?.let { error ->
        throw RuntimeException("Error fetching blockhash: ${error.code} - ${error.message}")
    }

    val base58Blockhash = response.result?.value?.blockhash
        ?: throw RuntimeException("Could not fetch latest blockhash: Unknown error")

    return Blockhash.from(base58Blockhash)
}

fun buildTransferTransaction(
    blockhash: String,
    fromPublicKey: SolanaPublicKey,
    toPublicKey: SolanaPublicKey,
    lamports: Long
): Transaction {
    val transferTxMessage = Message.Builder()
        .addInstruction(
            SystemProgram.transfer(
                fromPublicKey,
                toPublicKey,
                lamports
            )
        )
        .setRecentBlockhash(blockhash)
        .build()

    return Transaction(transferTxMessage)
}





fun sendSolNew(lifecycleScope: LifecycleCoroutineScope,walletAdapter: MobileWalletAdapter, sender: ActivityResultSender,toAddress: String = "Gwfh4iT6bTrqD8HqPFoFn2ocFrbsbhVRWV7vidZR4PRc") {
   // val sender = ActivityResultSender(this)
    val lamports = (0.1 * 1_000_000_000).toLong() // 0.1 SOL in lamports

    lifecycleScope.launch {
        try {
            // 1. Connect to wallet
            val result = walletAdapter.transact(sender) { authResult ->
                val payerPubkey = authResult.accounts.first().publicKey
                val payerKey = SolanaPublicKey.from(Base58.encodeToString(payerPubkey))
                val recipientKey = SolanaPublicKey.from(toAddress)


                // 2. Fetch blockhash using new SolanaRpcClient
                val rpcClient = Rpc20Driver("https://api.devnet.solana.com", KtorHttpDriver())
                val blockhashResponse = getLatestBlockhash()

                if (blockhashResponse == null) {
                    Log.e("WALLET", "Failed to fetch blockhash: ${blockhashResponse}")
                    return@transact
                }

                // 3. Build transfer instruction using System Program helper
                val transferInstruction = SystemProgram.transfer(
                    fromPublicKey = payerKey,
                    toPublickKey = recipientKey,
                    lamports = lamports
                )

                // 4. Build Message and Transaction
                val message = Message.Builder()
                    .addInstruction(transferInstruction)
                    .setRecentBlockhash(blockhashResponse)
                    .build()

                val unsignedTx = Transaction(message)

                // 5. Sign & Send
                signAndSendTransactions(arrayOf(unsignedTx.serialize()))

            }

            when (result) {
                is TransactionResult.Success -> {
                    //val sig = result.signatures.firstOrNull()
                    Log.d("WALLET", "Transaction successful! Sig:")
                }
                else -> Log.e("WALLET", "Transaction failed or cancelled")
            }

        } catch (e: Exception) {
            Log.e("WALLET", "Error sending SOL: ${e.message}")
        }
    }
}


    fun sendSolanaTransaction(    lifecycleScope: LifecycleCoroutineScope,
                                  walletAdapter: MobileWalletAdapter,
                                  sender:ActivityResultSender,toAddress: String, lamports: Long = 10000000) {
        //val sender = ActivityResultSender(this)

        // Identity for wallet adapter (same as before)
//        val walletAdapter = MobileWalletAdapter(
//            connectionIdentity = ConnectionIdentity(
//                identityUri = Uri.parse("https://yourdapp.com"),
//                iconUri = Uri.parse("favicon.ico"),
//                identityName = "Solana Kotlin Transfer Example"
//            )
//        )

        lifecycleScope.launch {
            try {
                val result = walletAdapter.transact(sender) { authResult ->
                    // Get user public key from wallet
                    val publicKeyBytes = authResult.accounts.first().publicKey
                    val tobytes=Base58.decode(toAddress)
                    // Log the length
                    Log.d("WALLET", "PublicKey byte array length: ${publicKeyBytes.size}")

// Log Base58 version for debugging
                    Log.d("WALLET", "PublicKey Base58: ${Base58.encode(publicKeyBytes)}")
                    Log.d("WALLET", "to address bytes byte array length: ${tobytes.size}")




                    val userPublicKey = SolanaPublicKey(publicKeyBytes)

                    // Fetch blockhash
                    val rpcClient = Rpc20Driver("https://api.devnet.solana.com", KtorHttpDriver())
                    val blockhashResponse = getLatestBlockhash()

                    if (blockhashResponse.toString() == null) {
                        Log.e("WALLET", "Blockhash error: ${blockhashResponse}")
                        return@transact
                    }

                    // Build transfer transaction
                    val transferTx = buildTransferTransaction(
                        blockhashResponse.toString(),
                        userPublicKey,
                        SolanaPublicKey(Base58.decode(toAddress)),
                        lamports
                    )

                    // Ask wallet to sign and send

                    signAndSendTransactions(arrayOf(transferTx.serialize()))
                }

                when (result) {
                    is TransactionResult.Success -> {
                        //val sigBytes = result.successPayload?.signatures?.first()
                        if (1==1) {
                            //val sig = Base58.encode(sigBytes)
                            Log.d("WALLET", "Transaction successful! Sig:")
                            Log.d("WALLET", "Explorer: https://explorer.solana.com/tx/")//$sig?cluster=devnet")
                        }
                    }
                    is TransactionResult.NoWalletFound -> {
                        Log.e("WALLET", "No compatible wallet app found!")
                    }
                    is TransactionResult.Failure -> {
                        Log.e("WALLET", "Wallet error", result.e)
                    }
                }
            } catch (e: Exception) {
                Log.e("WALLET", "Error sending transaction", e)
            }
        }
    }


/* ----------- response models ---------- */
@Serializable
data class SimResultValue(
    val err: JsonElement? = null, // Can be null or an object
    val logs: List<String>? = null,
    val accounts: JsonElement? = null,
    val innerInstructions: JsonElement? = null,
    val loadedAccountsDataSize: Long? = null,
    val replacementBlockhash: ReplacementBlockhash? = null,
    val returnData: JsonElement? = null,
    val unitsConsumed: Int? = null
)

@Serializable
data class ReplacementBlockhash(
    val blockhash: String,
    val lastValidBlockHeight: Long
)

@Serializable
data class SimResultWrapper(
    val value: SimResultValue?=null
)




/* ----------- extension on Rpc20Driver ---------- */

suspend fun makeRequestRaw(
    client: HttpClient,
    url: String,
    req: JsonObject
): JsonObject {
    val response: HttpResponse = client.post(url) {
        contentType(ContentType.Application.Json)
        setBody(req.toString())
    }

    val text = response.bodyAsText()
    return Json.parseToJsonElement(text).jsonObject
}
fun JsonRpc20Request.toJson(): JsonObject = buildJsonObject {
    put("jsonrpc", JsonPrimitive("2.0"))
    put("id", JsonPrimitive(this@toJson.id))
    put("method", JsonPrimitive(this@toJson.method))
    put("params", this@toJson.params ?: JsonNull)

}
suspend fun Rpc20Driver.simulateTransaction(
    txBytes: ByteArray,
    commitment: String = "processed"   // same default as web3.js
): SimResultValue? {

    val b64 = Base64.encodeToString(txBytes, Base64.NO_WRAP)

    val req = JsonRpc20Request(
        id = UUID.randomUUID().toString(),
        method = "simulateTransaction",
        params = JsonArray(
            listOf(
                JsonPrimitive(b64),
                JsonObject(
                    mapOf(
                        "encoding"   to JsonPrimitive("base64"),
                        "commitment" to JsonPrimitive(commitment),
                        // skip signature verif for speed – wallet does it anyway later
                        "sigVerify"  to JsonPrimitive(false)
                    )
                )
            )
        )
    )

    val resp: Rpc20Response<SolanaResponse<SimResultWrapper>> =
        makeRequest(
            req,
            SolanaResponse.serializer(SimResultWrapper.serializer())
        )
    val rawResp = makeRequestRaw(HttpClient(), "https://api.devnet.solana.com", req.toJson())
    Log.d("SIM_RAW", rawResp.toString())




    resp.error?.let { throw RuntimeException("simulateTransaction RPC error ${it.code}: ${it.message}") }
    val value=resp.result?.value?.value
    return value//resp.result?.value
}
fun saveCounter(context: Context, value: Int) {
    val prefs = context.getSharedPreferences("my_prefs", Context.MODE_PRIVATE)
    prefs.edit().putInt("counter", value).apply()
}
fun loadCounter(context: Context): Int {
    val prefs = context.getSharedPreferences("my_prefs", Context.MODE_PRIVATE)
    return prefs.getInt("counter", 1)
}
fun saveInit(context: Context, value: Boolean) {
    val prefs = context.getSharedPreferences("init_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("init_required", value).apply()
}
fun loadInit(context: Context): Boolean {
    val prefs = context.getSharedPreferences("init_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("init_required", true)
}




