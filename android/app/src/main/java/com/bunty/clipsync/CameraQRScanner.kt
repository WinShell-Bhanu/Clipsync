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
 * A full-screen Composable that opens the device's back camera, streams live frames through
 * Google ML Kit's Barcode scanning API, and invokes a callback as soon as a valid QR code is found.
 *
 * Lifecycle of a scan session:
 *  1. On first composition the Accompanist permission helper requests CAMERA access from the user.
 *  2. Once granted, a CameraX [ProcessCameraProvider] is obtained and two use-cases are bound to
 *     the lifecycle: a [Preview] (live viewfinder) and an [ImageAnalysis] (background frame
 *     processor). The analysis use-case discards all but the most recent frame so the pipeline
 *     never builds up a backlog of unprocessed buffers.
 *  3. Each frame is forwarded to [processImageProxy]. The first successful detection sets
 *     [hasScanned] to `true`, stores the decoded string in [scannedQRCode], and raises [showLoading].
 *  4. [showLoading] triggers a [LaunchedEffect] that immediately unbinds the camera to stop
 *     frame capture, plays the Lottie loading overlay for 500 ms, then delivers the scanned
 *     string to the caller via [onQRCodeScanned].
 *  5. If the CAMERA permission has not been granted the composable renders nothing — the
 *     surrounding screen is responsible for prompting the user and recomposing after grant.
 *
 * @param onQRCodeScanned Invoked on the main thread once a QR code has been successfully decoded.
 *                        Receives the raw string payload embedded in the code.
 * @param modifier        Modifier applied to the outer [Box]; defaults to no-op.
 */
fun CameraQRScanner(
    onQRCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context           = LocalContext.current
    val lifecycleOwner    = LocalLifecycleOwner.current
    val cameraPermission  = rememberPermissionState(Manifest.permission.CAMERA)

    // Guards against processing multiple frames after the first successful decode.
    // Once true, every subsequent frame is immediately closed without being analysed.
    var hasScanned    by remember { mutableStateOf(false) }
    // Drives the UI switch from the live camera preview to the Lottie loading overlay.
    // Flipped to true the moment a valid QR code is detected in any incoming frame.
    var showLoading   by remember { mutableStateOf(false) }
    // Holds the raw QR payload from the first successful frame analysis.
    // Remains null until a barcode is detected; read by the LaunchedEffect to invoke the callback.
    var scannedQRCode by remember { mutableStateOf<String?>(null) }
    // Retained reference to the camera provider so the camera can be explicitly unbound
    // before the screen exits, releasing hardware resources and preventing leaks.
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Reacts to the showLoading flag becoming true after a successful QR scan.
    // Tears down the camera immediately to release hardware resources, waits just long
    // enough for the loading animation to give the user visual feedback, then fires the callback.
    LaunchedEffect(showLoading) {
        if (showLoading && scannedQRCode != null) {
            cameraProvider?.unbindAll()
            delay(500)
            onQRCodeScanned(scannedQRCode!!)
        }
    }

    // Fires once when this composable first enters the composition.
    // Proactively requests CAMERA permission so the system dialog appears without
    // requiring any additional user interaction beyond what is already on screen.
    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (cameraPermission.status.isGranted) {

            // The live preview is shown only while still waiting for a successful scan.
            // Once showLoading is true it is replaced by the loading overlay, making
            // the UI transition feel instantaneous from the user's perspective.
            if (!showLoading) {
                AndroidView(
                    factory = { ctx ->
                        val previewView    = PreviewView(ctx)
                        val cameraExecutor = Executors.newSingleThreadExecutor()

                        ProcessCameraProvider.getInstance(ctx).addListener({
                            val provider = ProcessCameraProvider.getInstance(ctx).get()
                            cameraProvider = provider

                            // Connects the Preview use-case to the PreviewView's Surface so the
                            // camera stream is rendered on screen continuously in real time.
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            // Configures the ImageAnalysis use-case with a drop-oldest strategy.
                            // If the analyser is still busy when the next frame arrives, the older
                            // frame is discarded to keep latency low on slower devices.
                            val imageAnalyzer = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also {
                                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                                        if (!hasScanned) {
                                            // Pass the frame to ML Kit for analysis; the callback
                                            // sets hasScanned so no further frames are processed.
                                            processImageProxy(imageProxy) { qrCode ->
                                                hasScanned    = true
                                                scannedQRCode = qrCode
                                                showLoading   = true
                                            }
                                        } else {
                                            // A scan has already succeeded; release the frame buffer
                                            // immediately without running any barcode analysis.
                                            imageProxy.close()
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

            // Full-screen overlay rendered once a QR code is found, displayed while the camera
            // provider finishes unbinding in the background. The elevated zIndex ensures this
            // layer paints over any residual camera surface still showing underneath.
            if (showLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFB1C2F6))
                        .zIndex(10f),
                    contentAlignment = Alignment.Center
                ) {
                    LottieLoadingAnimation()
                }
            }
        }
        // When CAMERA permission has not been granted, this Box intentionally renders nothing.
        // The parent QRScanScreen owns the permission-rationale UI and triggers a recomposition
        // once the user grants access so the camera preview can be initialised.
    }
}

@Composable
/**
 * Plays the bundled `Loading.lottie` animation asset as a polished visual transition between
 * the QR scan result and the next screen while Firestore processes the pairing request.
 *
 * The animation runs exactly once at 1.5× speed, keeping the wait short while still giving
 * the user clear feedback that the app is actively processing. Displayed centred inside the
 * blue loading overlay that covers the camera card in [CameraQRScanner].
 */
fun LottieLoadingAnimation() {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.Asset("Loading.lottie")
    )

    // Drives the animation from frame 0 to the final frame exactly once.
    // A speed value greater than 1.0 shortens the total run time so users are not left waiting.
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
 * Extracts the underlying [android.media.Image] from a CameraX [ImageProxy], converts it to an
 * ML Kit [InputImage] (applying the device's current rotation so the scanner always sees upright
 * content), and runs it through the [BarcodeScanning] client to detect QR codes.
 *
 * Filtering: only barcodes with [Barcode.valueType] equal to [Barcode.TYPE_TEXT] or
 * [Barcode.TYPE_URL] are forwarded to [onQRCodeDetected]. All other formats — EAN-13, Code 128,
 * Wi-Fi QR codes, etc. — are silently skipped because ClipSync pairing QR codes always encode
 * their JSON payload as a plain text or URL-scheme string.
 *
 * Resource management: [imageProxy] is unconditionally closed in `addOnCompleteListener`
 * regardless of success or failure, releasing the frame buffer back to CameraX so the pipeline
 * can continue delivering new frames without stalling on held references.
 *
 * If the underlying media image reference is null (an edge case on some devices when the camera
 * is shutting down mid-frame), the proxy is closed immediately and the function returns early
 * without attempting any barcode analysis.
 *
 * @param imageProxy       A single camera frame from [ImageAnalysis]. Ownership transfers to this
 *                         function — callers must not attempt to close it themselves.
 * @param onQRCodeDetected Invoked with the raw barcode string for each matching barcode found.
 *                         [CameraQRScanner] gates further calls using the [hasScanned] flag.
 */
private fun processImageProxy(
    imageProxy: ImageProxy,
    onQRCodeDetected: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        BarcodeScanning.getClient().process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    // ClipSync QR codes always encode their JSON payload as plain text or a URL.
                    // All other barcode formats (EAN-13, Code 128, Wi-Fi, etc.) are irrelevant.
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
                // Always release the frame buffer back to CameraX regardless of outcome,
                // preventing the ImageAnalysis pipeline from stalling on unreleased references.
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
