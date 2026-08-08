package com.bing.androidvoiceflow.capture.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class CaptureServiceConfig(
    val baseUrl: String,
    val username: String,
    val password: String
) {
    fun createCaptureUrl(): String = endpointUrl("api/captures")

    fun healthUrl(): String = endpointUrl("api/health")

    private fun endpointUrl(path: String): String = requireNotNull(baseUrl.toHttpUrlOrNull())
        .newBuilder()
        .addPathSegments(path)
        .build()
        .toString()
}

internal interface CaptureServiceConfigProvider {
    fun load(): CaptureServiceConfig?
}

internal class CaptureServiceConfigStore(context: Context) : CaptureServiceConfigProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun load(): CaptureServiceConfig? = synchronized(this) {
        val baseUrl = preferences.getString(KEY_BASE_URL, null)?.trim().orEmpty()
        val encryptedCredentials = preferences.getString(KEY_CREDENTIALS, null).orEmpty()
        val iv = preferences.getString(KEY_CREDENTIALS_IV, null).orEmpty()
        if (baseUrl.isBlank() || encryptedCredentials.isBlank() || iv.isBlank()) return null

        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
            )
            val credentials = JSONObject(
                String(
                    cipher.doFinal(Base64.decode(encryptedCredentials, Base64.NO_WRAP)),
                    Charsets.UTF_8
                )
            )
            CaptureServiceConfig(
                baseUrl = baseUrl,
                username = credentials.getString("username"),
                password = credentials.getString("password")
            ).validated()
        }.getOrNull()
    }

    fun save(baseUrl: String, username: String, password: String): CaptureServiceConfig = synchronized(this) {
        val config = CaptureServiceConfig(
            baseUrl = baseUrl.trim(),
            username = username.trim(),
            password = password
        ).validated()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val credentialsJson = JSONObject()
            .put("username", config.username)
            .put("password", config.password)
            .toString()
        val encryptedCredentials = cipher.doFinal(credentialsJson.toByteArray(Charsets.UTF_8))
        preferences.edit(commit = true) {
            putString(KEY_BASE_URL, config.baseUrl)
            putString(KEY_CREDENTIALS, Base64.encodeToString(encryptedCredentials, Base64.NO_WRAP))
            putString(KEY_CREDENTIALS_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        config
    }

    fun clear() = synchronized(this) {
        preferences.edit {
            remove(KEY_BASE_URL)
            remove(KEY_CREDENTIALS)
            remove(KEY_CREDENTIALS_IV)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun CaptureServiceConfig.validated(): CaptureServiceConfig {
        val url = baseUrl.toHttpUrlOrNull()
        val loopbackHttp = url?.scheme == "http" && url.host in LOOPBACK_HOSTS
        require(url != null && (url.scheme == "https" || loopbackHttp)) {
            "Capture Service Base URL 必须使用 HTTPS；仅本机调试允许 HTTP"
        }
        require(username.isNotBlank()) { "Basic Auth 用户名不能为空" }
        require(password.isNotEmpty()) { "Basic Auth 密码不能为空" }
        require(':' !in username && !username.contains('\n') && !username.contains('\r')) {
            "Basic Auth 用户名格式无效"
        }
        require(!password.contains('\n') && !password.contains('\r')) {
            "Basic Auth 密码格式无效"
        }
        return copy(baseUrl = url.toString().removeSuffix("/"))
    }

    private companion object {
        const val PREFERENCES_NAME = "capture_service_config"
        const val KEY_BASE_URL = "base_url"
        const val KEY_CREDENTIALS = "encrypted_basic_credentials"
        const val KEY_CREDENTIALS_IV = "basic_credentials_iv"
        const val KEY_ALIAS = "android_voiceflow_capture_service"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
    }
}
