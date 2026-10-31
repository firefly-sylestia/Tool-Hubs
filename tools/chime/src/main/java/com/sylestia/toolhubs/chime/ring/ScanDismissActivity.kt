package com.sylestia.toolhubs.chime.ring

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.sylestia.toolhubs.designsystem.ToolHubsTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Scan the code to stop the alarm.
 *
 * The code is compared case-insensitively and with whitespace trimmed, because a printed sticker
 * routinely carries a trailing space or a newline and a scan that "should" have matched is the worst
 * possible failure here. With no code stored, any barcode is accepted — that is the "scan anything
 * you have taped to the door" case, and it is stated on screen so it is a choice, not a surprise.
 */
class ScanDismissActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val expected = intent.getStringExtra(EXTRA_CODE)
        val anyCode = intent.getBooleanExtra(EXTRA_ANY_CODE, false)
        val register = intent.getBooleanExtra(EXTRA_REGISTER, false)
        setContent {
            ToolHubsTheme {
                ScannerScreen(
                    register = register,
                    expectedCode = expected.takeUnless { anyCode },
                    onMatch = { value ->
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_SCANNED, value),
                        )
                        finish()
                    },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_ANY_CODE = "any_code"
        const val EXTRA_SCANNED = "scanned"
        const val EXTRA_REGISTER = "register"

        fun intent(context: Context, code: String?, anyCode: Boolean): Intent =
            Intent(context, ScanDismissActivity::class.java).apply {
                putExtra(EXTRA_CODE, code)
                putExtra(EXTRA_ANY_CODE, anyCode)
            }

        /**
         * Captures a code to keep, rather than checking one to dismiss.
         *
         * Registering the code by scanning it is the point: a code typed from memory at the desk is
         * a code nobody can reproduce at the door, and this returns whatever the camera read so the
         * alarm can compare the same bytes later.
         */
        fun registerIntent(context: Context): Intent =
            Intent(context, ScanDismissActivity::class.java).apply {
                putExtra(EXTRA_ANY_CODE, true)
                putExtra(EXTRA_REGISTER, true)
            }
    }
}

@Composable
private fun ScannerScreen(
    register: Boolean,
    expectedCode: String?,
    onMatch: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var seen by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf<String?>(null) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed -> granted = allowed }

    DisposableEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
        onDispose { }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0D14))) {
        if (granted) {
            CameraPreview(
                onBarcode = { value ->
                    val clean = value.trim()
                    if (clean.isEmpty()) return@CameraPreview
                    seen = clean
                    val ok = expectedCode == null ||
                        clean.equals(expectedCode.trim(), ignoreCase = true)
                    if (ok) onMatch(clean) else mismatch = clean
                },
            )
            ScannerFrame(register, expectedCode, seen, mismatch, onCancel)
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (register) {
                        "Chime needs the camera to read the code you want to keep."
                    } else {
                        "Chime needs the camera to read the code that dismisses this alarm."
                    },
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Grant the camera permission, or go back and dismiss by hand.",
                    color = Color(0xFF8FA3BF),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ScannerFrame(
    register: Boolean,
    expectedCode: String?,
    seen: String?,
    mismatch: String?,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (register) "Scan the code to keep" else "Scan to dismiss",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                modifier = Modifier.padding(top = 40.dp),
            )
            Text(
                when {
                    register -> "Point the camera at the barcode or QR code this alarm should accept."
                    expectedCode == null -> "Any barcode or QR code will do for this alarm."
                    else -> "This alarm only accepts the code you saved with it."
                },
                color = Color(0xFF8FA3BF),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Box(
            Modifier.size(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = Color(0xFF6FE3C4),
                strokeWidth = 2.dp,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            mismatch?.let {
                Text(
                    "That code is not the one for this alarm. Try: ${it.take(24)}",
                    color = Color(0xFFFFB4AB),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            seen?.takeIf { mismatch == null }?.let {
                Text("Read: ${it.take(28)}", color = Color(0xFF8FA3BF), fontSize = 12.sp)
            }
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text(
                    if (register) "Back without a code" else "Back to the alarm",
                    color = Color(0xFF6FE3C4),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/**
 * CameraX preview plus on-device barcode analysis.
 *
 * The analyser is bound to the lifecycle rather than the composable so a configuration change does
 * not leave a second camera session open, and every `ImageProxy` is closed in the completion
 * listener — the classic leak here is a closed frame that was never returned to the pool, which
 * stalls the pipeline after a few seconds of scanning.
 */
@Composable
private fun CameraPreview(onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    val analysis = remember { ImageAnalysis.Builder().build() }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { analysis.clearAnalyzer() }
            runCatching { scanner.close() }
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            val previewView = PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
            providerFuture.addListener({
                val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy, scanner, onBarcode) }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        context as androidx.lifecycle.LifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(viewContext))
            previewView
        },
    )
}

private fun analyze(
    proxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onBarcode: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            barcodes.firstNotNullOfOrNull { barcode: Barcode -> barcode.rawValue }?.let(onBarcode)
        }
        .addOnCompleteListener { proxy.close() }
}
