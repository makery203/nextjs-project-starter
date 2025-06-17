package com.example.plantidentification

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Bitmap.createScaledBitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.model.Model
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var resultTextView: TextView
    private lateinit var identifyButton: Button
    private lateinit var uploadButton: Button
    private lateinit var modeSwitch: Switch

    private var isOnlineMode = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val imageBitmap = result.data?.extras?.get("data") as? Bitmap
            if (imageBitmap != null) {
                imageView.setImageBitmap(imageBitmap)
                identifyPlant(imageBitmap)
            } else {
                Toast.makeText(this, "Failed to capture image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val bitmap = uriToBitmap(uri)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                identifyPlant(bitmap)
            } else {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private lateinit var tfliteModel: Model

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        resultTextView = findViewById(R.id.tvResult)
        identifyButton = findViewById(R.id.btnIdentify)
        uploadButton = findViewById(R.id.btnUpload)
        modeSwitch = findViewById(R.id.switchMode)

        checkCameraPermission()

        identifyButton.setOnClickListener {
            openCamera()
        }

        uploadButton.setOnClickListener {
            openGallery()
        }

        modeSwitch.setOnCheckedChangeListener { _, isChecked ->
            isOnlineMode = isChecked
            val modeText = if (isOnlineMode) "Online" else "Offline"
            Toast.makeText(this, "Switched to $modeText mode", Toast.LENGTH_SHORT).show()
        }

        // Load TensorFlow Lite model
        try {
            tfliteModel = Model.createModel(this, "plant_model.tflite")
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load model", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                Toast.makeText(this, "Camera permission already granted", Toast.LENGTH_SHORT).show()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Toast.makeText(this, "Camera permission is needed to identify plants", Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        takePictureLauncher.launch(intent)
    }

    private fun openGallery() {
        pickImageLauncher.launch("image/*")
    }

    private fun identifyPlant(bitmap: Bitmap) {
        if (isOnlineMode) {
            identifyPlantOnline(bitmap)
        } else {
            identifyPlantOffline(bitmap)
        }
    }

    private fun identifyPlantOffline(bitmap: Bitmap) {
        // Preprocess bitmap to model input size
        val inputSize = 224 // example size, adjust to your model
        val scaledBitmap = createScaledBitmap(bitmap, inputSize, inputSize, true)
        val tensorImage = TensorImage.fromBitmap(scaledBitmap)

        // Run inference
        val inputBuffer: ByteBuffer = tensorImage.buffer

        val outputShape = intArrayOf(1, 1001) // example output shape, adjust to your model
        val outputBuffer = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)

        try {
            tfliteModel.run(inputBuffer, outputBuffer.buffer.rewind())
            val confidences = outputBuffer.floatArray
            val maxIndex = confidences.indices.maxByOrNull { confidences[it] } ?: -1
            val label = getLabelForIndex(maxIndex)
            resultTextView.text = "Offline identification: $label"
        } catch (e: Exception) {
            e.printStackTrace()
            resultTextView.text = "Failed to run model inference"
        }
    }

    private fun getLabelForIndex(index: Int): String {
        // Placeholder labels, replace with your model's labels
        val labels = listOf("Ficus lyrata", "Monstera deliciosa", "Aloe vera", "Unknown")
        return if (index in labels.indices) labels[index] else "Unknown"
    }

    private fun identifyPlantOnline(bitmap: Bitmap) {
        resultTextView.text = "Identifying plant online..."
        val retrofit = Retrofit.Builder()
            .baseUrl("https://your-plant-api.example.com/") // Replace with your API base URL
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(PlantApiService::class.java)

        val imageBase64 = bitmapToBase64(bitmap)
        val request = PlantIdentificationRequest(imageBase64)

        service.identifyPlant(request).enqueue(object : Callback<PlantIdentificationResponse> {
            override fun onResponse(
                call: Call<PlantIdentificationResponse>,
                response: Response<PlantIdentificationResponse>
            ) {
                if (response.isSuccessful) {
                    val result = response.body()?.classification ?: "Unknown"
                    resultTextView.text = "Online identification: $result"
                } else {
                    resultTextView.text = "API error: ${response.code()}"
                }
            }

            override fun onFailure(call: Call<PlantIdentificationResponse>, t: Throwable) {
                resultTextView.text = "Network error: ${t.message}"
            }
        })
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val byteArray = outputStream.toByteArray()
        return android.util.Base64.encodeToString(byteArray, android.util.Base64.DEFAULT)
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

interface PlantApiService {
    @POST("identify")
    fun identifyPlant(@Body request: PlantIdentificationRequest): Call<PlantIdentificationResponse>
}

data class PlantIdentificationRequest(val imageBase64: String)

data class PlantIdentificationResponse(val classification: String)
