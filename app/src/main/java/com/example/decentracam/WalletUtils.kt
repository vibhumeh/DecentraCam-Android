package com.example.decentracam // match your package name

import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.solana.mobilewalletadapter.clientlib.*
//import com.solana.mobilewalletadapter.clientlib.protocol.TransactionResult
import com.solana.publickey.*
import com.solana.transaction.*
import com.solana.rpccore.JsonRpc20Request
import com.solana.networking.Rpc20Driver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import com.solana.networking.HttpNetworkDriver
import com.solana.networking.HttpRequest
import kotlinx.serialization.serializer

//import com.solana.rpccore.HttpNetworkDriver
//import com.solana.rpccore.HttpRequest
import com.solana.transaction.Blockhash
import com.solana.rpccore.Rpc20Response
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.isSuccess

class KtorHttpDriver : HttpNetworkDriver {
    private val client = HttpClient(CIO)

    override suspend fun makeHttpRequest(request: HttpRequest): String {
        val response: HttpResponse = client.request(request.url) {
            method = io.ktor.http.HttpMethod(request.method)
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
            JsonObject(emptyMap()), // empty object as required param
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

    response.error?.let { error ->
        throw RuntimeException("Error fetching blockhash: ${error.code} - ${error.message}")
    }

    val base58Blockhash = response.result?.value?.blockhash
        ?: throw RuntimeException("Could not fetch latest blockhash: Unknown error")

    return Blockhash.from(base58Blockhash)
}


