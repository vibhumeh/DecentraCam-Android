package com.example.decentracam
import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import java.io.File
import java.security.MessageDigest



fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
@Composable
fun CameraCapture(
    outputDirectory: File,
    onImageReady: (File) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
        factory = { ctx:Context ->
            val previewView = PreviewView(ctx)
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            //CameraXUtil.prepareFor-------------------------------------
            val cameraProvider = ProcessCameraProvider.getInstance(ctx).get()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )

    // Shutter button
    FloatingActionButton(
        onClick = {
            val photoFile = File(
                outputDirectory,
                "IMG_${System.currentTimeMillis()}.jpg"
            )
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
//                        val hash = sha256(photoFile)
//                        Log.d("HASH", "SHA-256: $hash")
                        onImageReady(photoFile)

                    }
                    override fun onError(exc: ImageCaptureException) {
                        Log.e("Cam", "Save failed", exc)
                    }
                })
        },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp)
    ) { Icon(Icons.Default.Camera, contentDescription = null) }
}}
