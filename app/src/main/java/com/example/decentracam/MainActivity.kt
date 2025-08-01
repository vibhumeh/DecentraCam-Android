package com.example.decentracam
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.decentracam.ui.theme.DecentracamTheme
import java.io.File
import java.security.MessageDigest
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.solana.mobilewalletadapter.clientlib.*
import android.net.Uri


//import com.funkatronics.encoders.Base58
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

private var authToken: String? = null

class MainActivity : ComponentActivity() {
    //REMEMBER TO KEEP WALLET OPEN IN BACKGROUND BEFORE SIGNING
    private lateinit var outputDirectory: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        outputDirectory = getOutputDirectory()
        val solanaUri = Uri.parse("https://raw.githubusercontent.com/solana-labs/wallet-adapter/main")
        val iconUri = Uri.parse("packages/wallet-adapter/example/favicon.png")
        val identityName = "DecentraCam"

        //Construct the client
        val walletAdapter = MobileWalletAdapter(connectionIdentity = ConnectionIdentity(
            identityUri = solanaUri,
            iconUri = iconUri,
            identityName = identityName
        ),

        )



        val sender = ActivityResultSender(this)

//        fun connectOnly() {
//            lifecycleScope.launch {
//                val result = walletAdapter.connect(sender)
//
//                when (result) {
//                    is TransactionResult.Success -> {
//                        val account = result.authResult.accounts.first()
//                        authToken = result.authResult.authToken // 🔥 Save it globally
//                        Log.d("WALLET", "Connected: ${account.publicKey}, Token: $authToken")
//                    }
//                    else -> Log.e("WALLET", "Connect failed")
//                }
//            }
//        }
       // connectOnly(lifecycleScope, walletAdapter, sender)
        lifecycleScope.launch {
            try {
                val blockhash = getLatestBlockhash()
                Log.d("RPC", "Latest Blockhash: $blockhash")
            } catch (e: Exception) {
                Log.e("RPC", "Failed to fetch blockhash: ${e.message}")
            }
        }
        Log.d("WALLET", "Connected 2, Token: $authToken")

        fun connectAndSign(message: ByteArray) {
        lifecycleScope.launch {
            val result = walletAdapter.transact(sender) { authResult ->
                // signMessagesDetached takes: messages[], addresses[]
                signMessagesDetached(
                    arrayOf(message),
                    arrayOf(authResult.accounts.first().publicKey)
                )
            }

            when (result) {
                is TransactionResult.Success -> {
                    val signedBytes = result.successPayload
                        ?.messages
                        ?.firstOrNull()
                        ?.signatures
                        ?.firstOrNull()

                    if (signedBytes != null) {
                        //val sig = Base58.encodeToString(signedBytes)
                        Log.d("WALLET", "Signed message (Base58):")//$sig")
                    } else {
                        Log.e("WALLET", "Signing succeeded but no signature returned")
                    }
                }

                is TransactionResult.NoWalletFound -> {
                    Log.e("WALLET", "No compatible wallet installed")
                }

                is TransactionResult.Failure -> {
                    Log.e("WALLET", "Wallet error: ${result.e.message}")
                }
            }
        }
    }


        val requestPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermission.launch(Manifest.permission.CAMERA)
        }




        setContent {
            DecentracamTheme {
                CameraCapture(
                    outputDirectory = outputDirectory,
                    onImageReady = { file:File ->
                        val hash = sha256(file)
                        Log.d("HASH", "SHA-256: $hash")
                        println("Image hash = $hash")
                        // plug in wallet next


                        lifecycleScope.launch {
                            //initialiseAccount(lifecycleScope = lifecycleScope,wallet=walletAdapter,sender=sender)
                            verify_sig(lifecycleScope = lifecycleScope,wallet=walletAdapter,sender=sender,message=hash.toByteArray(), context = this@MainActivity)
                        //connectAndSign(hash.hexToBytes())
                        }
                        // plug in wallet next



                    }
                )
            }
        }
    }
    fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, "decentracam").apply { mkdirs() }
        }
        return mediaDir ?: filesDir
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
