package com.example.core.runtime

import android.content.Context
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import com.example.core.database.secondary.ArtifactsDatabase
import com.example.core.database.secondary.ArtifactsEntity
import org.json.JSONObject
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

data class CameraCaptureResult(
  val imageFile: File,
  val fileSizeBytes: Long,
  val frameSha256: String,
  val captureTimestamp: Long,
  val width: Int = 1920,
  val height: Int = 1080,
  val galleryUri: String? = null,
  val decodes: Boolean = false,
  val visionLabels: List<Pair<String, Float>> = emptyList(),
  val averageLuminance: Float = 0f
) {
  /**
   * Resource handle — the canonical way captures cross the model boundary.
   * Phone-local purpclaw:// URI; never a PC path, never a bare filename.
   */
  fun toResourceHandleJson(): String =
    """{"uri":"purpclaw://captures/${imageFile.name}","mime":"image/jpeg",""" +
      """"bytes":$fileSizeBytes,"sha256":"$frameSha256","source":"android_native",""" +
      """"width":$width,"height":$height,"decodes":$decodes,"gallery_uri":${JSONObject.quote(galleryUri)},""" +
      """"vision_labels":${JSONObject.wrap(visionLabels.map { mapOf("label" to it.first, "confidence" to it.second) })}}"""

  fun resourceUri(): String = "purpclaw://captures/${imageFile.name}"
}

class CameraVisionEngine(private val context: Context) {

  companion object {
    private const val TAG = "CameraVisionEngine"

    @JvmStatic
    fun computeFileSha256(file: File): String = try {
      val digest = MessageDigest.getInstance("SHA-256")
      val bytes = file.readBytes()
      digest.digest(bytes).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
      "sha256_${System.currentTimeMillis()}"
    }

    /**
     * Re-analyze an existing capture file for vision labels + luminance.
     * Used by vision.analyze tool when re-processing a persisted purpclaw:// URI.
     * Returns: Triple(labels, averageLuminance, decodes).
     */
    @JvmStatic
    suspend fun analyzeFrame(file: File, context: Context): Triple<List<Pair<String, Float>>, Float, Boolean> {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      val decodes = bounds.outWidth > 0 && bounds.outHeight > 0
      if (!decodes || !file.exists()) {
        return Triple(emptyList(), 0f, false)
      }
      // Average luminance
      val avgLum = runCatching {
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 16 })
        if (bmp == null) 0f else {
          var total = 0.0
          var count = 0
          for (y in 0 until bmp.height step 2) {
            for (x in 0 until bmp.width step 2) {
              val pixel = bmp.getPixel(x, y)
              val r = android.graphics.Color.red(pixel)
              val g = android.graphics.Color.green(pixel)
              val b = android.graphics.Color.blue(pixel)
              total += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
              count++
            }
          }
          bmp.recycle()
          if (count == 0) 0f else (total / count).toFloat()
        }
      }.getOrDefault(0f)
      // ML Kit labels (suspend call cannot live inside non-suspend runCatching)
      @Suppress("UNCHECKED_CAST")
      val labels: List<Pair<String, Float>> = try {
        val labeler = com.google.mlkit.vision.label.ImageLabeling.getClient(
          com.google.mlkit.vision.label.defaults.ImageLabelerOptions.Builder()
            // These are broad classifier candidates, not verified YOLO
            // detections. Low-confidence labels previously produced the
            // imaginary-plush failure, so uncertain guesses are suppressed.
            .setConfidenceThreshold(0.80f).build()
        )
        val image = com.google.mlkit.vision.common.InputImage.fromFilePath(context, android.net.Uri.fromFile(file))
        suspendCancellableCoroutine { cont ->
          labeler.process(image)
            .addOnSuccessListener { labels ->
              val result = labels.sortedByDescending { it.confidence }.take(8)
                .map { it.text to it.confidence }
              labeler.close()
              if (cont.isActive) cont.resume(result)
            }
            .addOnFailureListener {
              labeler.close()
              if (cont.isActive) cont.resume(emptyList<String>())
            }
        } as List<Pair<String, Float>>
      } catch (e: Exception) {
        Log.w(TAG, "Label analysis failed: ${e.message}")
        emptyList()
      }
      return Triple(labels, avgLum, true)
    }
  }

  private var imageCapture: ImageCapture? = null

  fun bindCamera(lifecycleOwner: LifecycleOwner, onBound: (Boolean) -> Unit) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
      try {
        val cameraProvider = cameraProviderFuture.get()
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        imageCapture = ImageCapture.Builder()
          .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
          .build()

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
          lifecycleOwner,
          cameraSelector,
          imageCapture
        )
        Log.i(TAG, "CameraX bound successfully to lifecycle")
        onBound(true)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to bind CameraX: ${e.message}", e)
        onBound(false)
      }
    }, ContextCompat.getMainExecutor(context))
  }

  suspend fun captureOpticalFrame(): CameraCaptureResult = withContext(Dispatchers.IO) {
    val outputDir = File(context.filesDir, "evidence").apply { mkdirs() }
    val photoFile = File(outputDir, "optical_frame_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")

    val capture = imageCapture
    if (capture != null) {
      val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
      suspendCancellableCoroutine { continuation ->
        capture.takePicture(
          outputOptions,
          ContextCompat.getMainExecutor(context),
          object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
              val hash = computeFileSha256(photoFile)
              val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
              BitmapFactory.decodeFile(photoFile.absolutePath, bounds)
              val decodes = bounds.outWidth > 0 && bounds.outHeight > 0
              val galleryUri = if (decodes) publishToGallery(photoFile) else null
              val baseResult = CameraCaptureResult(
                imageFile = photoFile,
                fileSizeBytes = photoFile.length(),
                frameSha256 = hash,
                captureTimestamp = System.currentTimeMillis(),
                width = bounds.outWidth.coerceAtLeast(0),
                height = bounds.outHeight.coerceAtLeast(0),
                galleryUri = galleryUri,
                decodes = decodes
              )
              // Camera callback is on the main executor; persist metadata on
              // IO before returning the canonical handle to the tool chain.
              kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val result = baseResult.copy(
                  visionLabels = if (decodes) analyzeLabels(photoFile) else emptyList(),
                  averageLuminance = if (decodes) measureAverageLuminance(photoFile) else 0f
                )
                persistArtifact(result)
                if (continuation.isActive) continuation.resume(result)
              }
            }

            override fun onError(exception: ImageCaptureException) {
              Log.e(TAG, "Optical frame capture failed: ${exception.message}", exception)
              // TRUTH LAW: never fabricate an image. Report failure honestly.
              continuation.resume(
                CameraCaptureResult(
                  imageFile = photoFile,
                  fileSizeBytes = -1,
                  frameSha256 = "",
                  captureTimestamp = System.currentTimeMillis()
                )
              )
            }
          }
        )
      }
    } else {
      // Camera not bound to a lifecycle — honest failure, never a fake file.
      CameraCaptureResult(
        imageFile = photoFile,
        fileSizeBytes = -1,
        frameSha256 = "",
        captureTimestamp = System.currentTimeMillis()
      )
    }
  }

  private fun publishToGallery(file: File): String? = try {
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PurpClaw")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: return null
    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    context.contentResolver.update(uri, ContentValues().apply {
      put(MediaStore.Images.Media.IS_PENDING, 0)
    }, null, null)
    uri.toString()
  } catch (e: Exception) {
    Log.e(TAG, "Gallery publish failed: ${e.message}", e)
    null
  }

  private suspend fun persistArtifact(result: CameraCaptureResult) {
    val id = result.imageFile.nameWithoutExtension
    ArtifactsDatabase.getDatabase(context).artifactsDao().insert(
      ArtifactsEntity(
        id = id,
        parentArtifactId = null,
        chatId = null,
        createdAt = result.captureTimestamp,
        sha256Prefix = result.frameSha256.take(16),
        type = "IMAGE",
        visibility = "PRIVATE",
        mime = "image/jpeg",
        bytes = result.fileSizeBytes,
        metadataJson = JSONObject().apply {
          put("resourceUri", result.resourceUri())
          put("localPath", result.imageFile.absolutePath)
          put("galleryUri", result.galleryUri)
          put("width", result.width)
          put("height", result.height)
          put("decodes", result.decodes)
          put("source", "ANDROID_NATIVE")
          put("visionLabels", org.json.JSONArray().apply {
            result.visionLabels.forEach { (label, confidence) ->
              put(JSONObject().put("label", label).put("confidence", confidence))
            }
          })
          put("averageLuminance", result.averageLuminance)
          put("tooDarkForReliableScene", result.averageLuminance < 0.06f)
        }.toString(),
        integrityStatus = if (result.decodes && result.fileSizeBytes > 0) "OK" else "INTEGRITY_FAILED"
      )
    )
  }

  private suspend fun analyzeLabels(file: File): List<Pair<String, Float>> =
    suspendCancellableCoroutine { continuation ->
      val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.80f).build()
      )
      val image = InputImage.fromFilePath(context, Uri.fromFile(file))
      labeler.process(image)
        .addOnSuccessListener { labels ->
          val result = labels.sortedByDescending { it.confidence }.take(8)
            .map { it.text to it.confidence }
          labeler.close()
          if (continuation.isActive) continuation.resume(result)
        }
        .addOnFailureListener { error ->
          Log.e(TAG, "On-device vision failed: ${error.message}", error)
          labeler.close()
          if (continuation.isActive) continuation.resume(emptyList())
        }
    }

  private fun measureAverageLuminance(file: File): Float {
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 16 })
      ?: return 0f
    var total = 0.0
    var count = 0
    for (y in 0 until bitmap.height step 2) {
      for (x in 0 until bitmap.width step 2) {
        val pixel = bitmap.getPixel(x, y)
        val r = android.graphics.Color.red(pixel)
        val g = android.graphics.Color.green(pixel)
        val b = android.graphics.Color.blue(pixel)
        total += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        count++
      }
    }
    bitmap.recycle()
    return if (count == 0) 0f else (total / count).toFloat()
  }
}
