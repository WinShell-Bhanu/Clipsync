package com.bunty.clipsync

import android.Manifest
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.airbnb.lottie.compose.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
/**
 * CameraQRScanner renders a live camera preview and scans frames for QR codes
 * using Google ML Kit's Barcode API.
 *
 * **Flow:**
 * 1. Requests `CAMERA` permission via Accompanist on first composition.
 * 2. Once granted, opens the back camera and attaches an [ImageAnalysis] use-case
 *    that processes frames one-at-a-time on a background executor.
 * 3. When a QR code is detected, the camera is unbound and a Lottie loading animation
 *    is shown for 500 ms before [onQRCodeScanned] is called.
 *
 * The [hasScanned] flag ensures only the first detected QR code is acted upon,
 * preventing duplicate callbacks.
 *
 * @param onQRCodeScanned Called on the main thread with the raw QR code string.
 * @param modifier        Optional [Modifier] (typically fills the parent card).
 */
fun CameraQRScanner(
    onQRCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context           = LocalContext.current
    val lifecycleOwner    = LocalLifecycleOwner.current
    val cameraPermission  = rememberPermissionState(Manifest.permission.CAMERA)

    // Prevents processing more than one QR code per scan session
    var hasScanned    by remember { mutableStateOf(false) }
    // Controls whether the loading Lottie overlay is shown instead of the camera
    var showLoading   by remember { mutableStateOf(false) }
    // The raw string value of the first detected QR code
    var scannedQRCode by remember { mutableStateOf<String?>(null) }
    // Kept so the camera can be fully unbound before navigating away
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // When showLoading becomes true: unbind the camera, wait 500 ms for the animation,
    // then fire the callback so the parent screen can navigate
    LaunchedEffect(showLoading) {
        if (showLoading && scannedQRCode != null) {
            cameraProvider?.unbindAll()  // stop camera before navigating
            delay(500)                   // let the Lottie animation play briefly
            onQRCodeScanned(scannedQRCode!!)
        }
    }

    // Request camera permission as soon as this composable enters the composition
    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (cameraPermission.status.isGranted) {

            // Show camera preview only when not yet loading
            if (!showLoading) {
                AndroidView(
                    factory = { ctx ->
                        val previewView    = PreviewView(ctx)
                        val cameraExecutor = Executors.newSingleThreadExecutor()

                        ProcessCameraProvider.getInstance(ctx).addListener({
                            val provider = ProcessCameraProvider.getInstance(ctx).get()
                            cameraProvider = provider

                            // Use-case 1: live preview surface
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            // Use-case 2: image analysis — only keep the latest frame to avoid backpressure
                            val imageAnalyzer = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also {
                                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                                        if (!hasScanned) {
                                            // Process the frame; set hasScanned=true on first hit
                                            processImageProxy(imageProxy) { qrCode ->
                                                hasScanned    = true
                                                scannedQRCode = qrCode
                                                showLoading   = true
                                            }
                                        } else {
                                            imageProxy.close()  // discard frames after first scan
                                        }
                                    }
                                }

                            try {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalyzer
                                )
                            } catch (e: Exception) {
                                Log.e("CameraQRScanner", "Camera binding failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Loading overlay — shown after a QR code is detected while the camera winds down
            if (showLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFB1C2F6))
                        .zIndex(10f),  // render above the camera preview
                    contentAlignment = Alignment.Center
                ) {
                    LottieLoadingAnimation()
                }
            }
        }
        // If permission is not granted the Box is empty — the parent QRScanScreen
        // handles the "Scan QR" button and will re-compose once permission is granted.
    }
}

@Composable
/**
 * LottieLoadingAnimation plays the `Loading.lottie` asset once at 1.5× speed.
 *
 * Used as a visual transition while the scanned QR data is being processed by Firestore.
 * Displayed on top of the camera card inside [CameraQRScanner].
 */
fun LottieLoadingAnimation() {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.Asset("Loading.lottie")
    )

    // Play once (iterations = 1) at 1.5× speed so it doesn't feel sluggish
    val progress by animateLottieCompositionAsState(
        composition  = composition,
        iterations   = 1,
        speed        = 1.5f,
        restartOnPlay = true
    )

    LottieAnimation(
        composition = composition,
        progress    = { progress },
        modifier    = Modifier.size(250.dp)
    )
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
/**
 * Processes a single [ImageProxy] frame from the CameraX [ImageAnalysis] use-case
 * and scans it for QR codes using ML Kit's [BarcodeScanning] client.
 *
 * Only `TYPE_TEXT` and `TYPE_URL` barcodes are acted upon — other barcode types
 * (like product UPC codes) are ignored.
 *
 * **Important:** [imageProxy] is always closed in the `addOnCompleteListener` to
 * release the frame buffer back to the camera pipeline.
 *
 * @param imageProxy        The camera frame to analyse (must be closed after use).
 * @param onQRCodeDetected  Called with the raw barcode string when a match is found.
 */
private fun processImageProxy(
    imageProxy: ImageProxy,
    onQRCodeDetected: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees  // apply device rotation so ML Kit sees upright frames
        )

        BarcodeScanning.getClient().process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    // Only process plain-text or URL QR codes (ClipSync QR codes are JSON text)
                    if (barcode.valueType == Barcode.TYPE_TEXT ||
                        barcode.valueType == Barcode.TYPE_URL) {
                        barcode.rawValue?.let { qrCode ->
                            onQRCodeDetected(qrCode)
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("CameraQRScanner", "Barcode scanning failed", e)
            }
            .addOnCompleteListener {
                imageProxy.close()  // always release the frame, even on failure
            }
    } else {
        imageProxy.close()
    }
}
