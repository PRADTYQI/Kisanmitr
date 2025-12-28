package com.example.kisanmitr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.kisanmitr.ui.theme.KisanMitrTheme
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var tts: TextToSpeech
    private var rewardedAd: RewardedAd? = null
    private final var TAG = "MainActivity"
    private lateinit var modelManager: ModelManager
    private lateinit var cameraExecutor: ExecutorService

    private val crops = listOf(
        Crop("apple", R.drawable.apple, "Apple_model_unquant.tflite"),
        Crop("tomato", R.drawable.tomato, "Tomato_model_unquant.tflite"),
        Crop("potato", R.drawable.potato, "Potato_model_unquant.tflite"),
        Crop("mango", R.drawable.mango, "Mango_model_unquant.tflite"),
        Crop("guava", R.drawable.guava, "Guava_model_unquant.tflite"),
        Crop("cotton", R.drawable.cotton, "Cotton_model_unquant.tflite")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this) {}
        loadRewardedAd()
        modelManager = ModelManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setContent {
            KisanMitrTheme {
                MainScreen(crops, modelManager) {
                    showRewardedAd()
                }
            }
        }
        requestPermissions()
    }

    private fun loadRewardedAd() {
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(this,"ca-app-pub-3940256099942544/5224354917", adRequest, object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                Log.d(TAG, adError.toString())
                rewardedAd = null
            }

            override fun onAdLoaded(ad: RewardedAd) {
                Log.d(TAG, "Ad was loaded.")
                rewardedAd = ad
            }
        })
    }

    private fun showRewardedAd() {
        rewardedAd?.let { ad ->
            ad.show(this) { rewardItem ->
                // Handle the reward.
                Log.d(TAG, "User earned the reward.")
            }
        } ?: run {
            Log.d(TAG, "The rewarded ad wasn't ready yet.")
        }
    }

    private fun requestPermissions() {
        requestMultiplePermissions.launch(arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        ))
    }

    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach {
                Log.d(TAG, "${it.key} = ${it.value}")
            }
        }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        modelManager.close()
    }
}

@Composable
fun MainScreen(
    crops: List<Crop>,
    modelManager: ModelManager,
    onShowRewardedAd: () -> Unit
) {
    var selectedCrop by remember { mutableStateOf(crops.first()) }
    var diagnosisResult by remember { mutableStateOf("Diagnosis will appear here.") }

    LaunchedEffect(selectedCrop) {
        modelManager.loadModel(selectedCrop.modelName)
    }

    Column(Modifier.fillMaxSize()) {
        CameraView(Modifier.weight(1f), modelManager) { result ->
            diagnosisResult = result.joinToString("\n")
        }
        CropSelector(
            crops = crops,
            selectedCrop = selectedCrop,
            onCropSelected = { selectedCrop = it }
        )
        DiagnosisResult(
            resultText = diagnosisResult,
            onPlayAudio = { /*TODO*/ },
            onGetRemedy = onShowRewardedAd
        )
        RemedyCards()
        BannerAd()
    }
}

@Composable
fun CameraView(
    modifier: Modifier = Modifier,
    modelManager: ModelManager,
    onResult: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = {
            val previewView = PreviewView(it)
            val executor = Executors.newSingleThreadExecutor()
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor, { imageProxy ->
                            val bitmap = imageProxy.toBitmap()
                            if (bitmap != null) {
                                val result = modelManager.classify(bitmap)
                                onResult(result)
                            }
                            imageProxy.close()
                        })
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (exc: Exception) {
                    Log.e("CameraView", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(it))
            previewView
        },
        modifier = modifier
    )
}

@Composable
fun CropSelector(
    crops: List<Crop>,
    selectedCrop: Crop,
    onCropSelected: (Crop) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items(crops) { crop ->
            Image(
                painter = painterResource(id = crop.iconRes),
                contentDescription = crop.name,
                modifier = Modifier
                    .size(64.dp)
                    .clickable { onCropSelected(crop) }
                    .padding(8.dp)
                    .background(
                        if (crop == selectedCrop) Color.White.copy(alpha = 0.5f)
                        else Color.Transparent
                    )
            )
        }
    }
}

@Composable
fun DiagnosisResult(
    resultText: String,
    onPlayAudio: () -> Unit,
    onGetRemedy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = resultText, style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onPlayAudio) {
                Text("Play Audio")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onGetRemedy) {
                Text("Get Expert Remedy")
            }
        }
    }
}

@Composable
fun RemedyCards() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        Card(modifier = Modifier.weight(1f)) { Text("Chemical", textAlign = TextAlign.Center) }
        Spacer(modifier = Modifier.width(8.dp))
        Card(modifier = Modifier.weight(1f)) { Text("Organic", textAlign = TextAlign.Center) }
        Spacer(modifier = Modifier.width(8.dp))
        Card(modifier = Modifier.weight(1f)) { Text("Traditional", textAlign = TextAlign.Center) }
    }
}

@Composable
fun BannerAd() {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = "ca-app-pub-3940256099942544/6300978111" // Test ID
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}

data class Crop(val name: String, val iconRes: Int, val modelName: String)
