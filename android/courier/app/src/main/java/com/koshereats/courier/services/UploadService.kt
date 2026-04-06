package com.koshereats.courier.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.koshereats.courier.data.repository.CourierRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UploadService handles the presign -> PUT -> return public URL flow for
 * courier document photos. Used by the onboarding screen.
 *
 * Dev stub mode: when the backend returns a "stub://" upload URL, we skip
 * the actual HTTP PUT and just return the public URL string. That keeps
 * onboarding working end-to-end without real S3 credentials.
 */
@Singleton
class UploadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val courierRepository: CourierRepository,
) {
    enum class Kind(val backendKey: String) {
        LICENSE("courier/license"),
        INSURANCE("courier/insurance"),
        REGISTRATION("courier/registration"),
        PROFILE("courier/profile"),
    }

    // Uses its own OkHttpClient so it doesn't inherit the auth interceptor
    // — presigned S3 URLs must NOT carry a Bearer token.
    private val plainHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun uploadImage(uri: Uri, kind: Kind): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val jpeg = compressToJpeg(uri)
            val presign = courierRepository.presignUpload(kind.backendKey, "image/jpeg").getOrThrow()

            if (presign.uploadUrl.startsWith("stub://")) {
                return@runCatching presign.publicUrl
            }

            val body = jpeg.toRequestBody("image/jpeg".toMediaType())
            val req = Request.Builder()
                .url(presign.uploadUrl)
                .put(body)
                .header("Content-Type", "image/jpeg")
                .build()

            val resp = plainHttp.newCall(req).execute()
            if (!resp.isSuccessful) {
                throw IllegalStateException("S3 upload failed: ${resp.code}")
            }
            presign.publicUrl
        }
    }

    /**
     * Reads the selected image from a content URI and re-encodes as a
     * reasonable-quality JPEG. This matches the iOS courier app's
     * compressionQuality: 0.85.
     */
    private fun compressToJpeg(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open image")
        val bitmap: Bitmap = input.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Could not decode image")

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
