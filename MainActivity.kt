package com.example.myapplication


import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private var mediaPlayer: MediaPlayer? = null
    private var isFatigueDetected = false // Чтобы не повторять сигнал, пока не закончится

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // звук тревоги
        mediaPlayer = MediaPlayer.create(this, R.raw.alert_sound).apply {
            setOnCompletionListener {
                isFatigueDetected = false
            }
        }

        // разрешение на камеру
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                Toast.makeText(this, "Без камеры никак :(", Toast.LENGTH_SHORT).show()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Запускаем интерфейс
        setContent {
            var showAlert by remember { mutableStateOf(false) }

            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreview(
                        onFatigueDetected = { detected ->
                            if (detected && !isFatigueDetected) {
                                isFatigueDetected = true
                                showAlert = true
                                mediaPlayer?.start() // Включаем звук
                            }
                        }
                    )

                    // предупреждение на экране
                    if (showAlert) {
                        AlertDialog(
                            onDismissRequest = { showAlert = false },
                            title = {
                                Text(
                                    "ВНИМАНИЕ!",
                                    fontSize = 32.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            text = {
                                Text(
                                    "Пожалуйста, сделайте перерыв.",
                                    fontSize = 24.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center
                                )
                            },
                            confirmButton = {},
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreview(onFatigueDetected: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }


    var lastYawn by remember { mutableStateOf(0L) }
    var lastBlink by remember { mutableStateOf(0L) }
    var blinkCount by remember { mutableStateOf(0) }
    var lastNod by remember { mutableStateOf(0L) }

    //  детектор лиц
    val detector = remember {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        FaceDetection.getClient(options)
    }

    DisposableEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider) // куда выводим изображение
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().apply {
                setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image ?: return@setAnalyzer imageProxy.close()
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    val now = System.currentTimeMillis()

                    //  ищем признаки усталости
                    detector.process(image)
                        .addOnSuccessListener { faces ->
                            faces.firstOrNull()?.let { face ->
                                if (face.headEulerAngleY > 20 && now - lastYawn > 5000) {
                                    lastYawn = now
                                    onFatigueDetected(true)
                                }
                                if ((face.leftEyeOpenProbability ?: 1f) < 0.3 &&
                                    (face.rightEyeOpenProbability ?: 1f) < 0.3) {
                                    if (now - lastBlink < 500) {
                                        blinkCount++
                                        if (blinkCount > 3) onFatigueDetected(true)
                                    } else blinkCount = 1
                                    lastBlink = now
                                }

                                if (abs(face.headEulerAngleX) > 20 && now - lastNod < 2000) {
                                    lastNod = now
                                    onFatigueDetected(true)
                                }
                            }
                            imageProxy.close()
                        }
                        .addOnFailureListener {
                            it.printStackTrace()
                            imageProxy.close()
                        }
                }
            }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        onDispose {
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
