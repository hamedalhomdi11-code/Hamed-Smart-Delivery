package com.hamed.smartdelivery

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.auth0.android.Auth0
import com.auth0.android.authentication.AuthenticationAPIClient
import com.auth0.android.authentication.AuthenticationException
import com.auth0.android.authentication.storage.SecureCredentialsManager
import com.auth0.android.authentication.storage.SharedPreferencesStorage
import com.auth0.android.callback.Callback
import com.auth0.android.provider.WebAuthProvider
import com.auth0.android.result.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class DriverSessionCheck(val allowed: Boolean, val message: String)

@Composable
fun DriverAuthenticationGate(
    activity: ComponentActivity,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val account = remember { Auth0.getInstance(context) }
    val credentialsManager = remember {
        SecureCredentialsManager(
            AuthenticationAPIClient(account),
            context,
            SharedPreferencesStorage(context)
        )
    }
    val scope = rememberCoroutineScope()
    var authenticated by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var signingIn by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("جارٍ التحقق من جلسة الدخول") }

    suspend fun validateWithServer(accessToken: String): DriverSessionCheck = withContext(Dispatchers.IO) {
        val base = BuildConfig.HAMED_API_BASE_URL.trim().trimEnd('/')
        if (base.isBlank() || base.contains(".invalid")) {
            return@withContext DriverSessionCheck(false, "خدمة الحسابات لم تُربط بالخادم بعد.")
        }
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$base/v1/driver/session").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 12000
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                DriverSessionCheck(true, "تم التحقق من صلاحية الحساب.")
            } else {
                val error = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                val reason = when (error) {
                    "driver_account_not_provisioned" -> "هذا الحساب غير مضاف إلى قائمة السائقين لدى الإدارة."
                    "driver_account_not_started" -> "لم يبدأ وقت صلاحية هذا الحساب بعد."
                    "driver_account_expired" -> "انتهت صلاحية هذا الحساب."
                    "driver_account_disabled" -> "هذا الحساب معطّل من الإدارة."
                    else -> "تعذر التحقق من صلاحية الحساب (HTTP $code)."
                }
                DriverSessionCheck(false, reason)
            }
        } catch (_: Exception) {
            DriverSessionCheck(false, "تعذر الاتصال بخادم الحسابات. تحقق من الإنترنت ثم أعد المحاولة.")
        } finally {
            connection?.disconnect()
        }
    }

    LaunchedEffect(Unit) {
        checking = true
        if (credentialsManager.hasValidCredentials()) {
            runCatching { credentialsManager.awaitCredentials() }
                .onSuccess { credentials ->
                    val check = validateWithServer(credentials.accessToken.orEmpty())
                    authenticated = check.allowed
                    message = check.message
                    if (!check.allowed) credentialsManager.clearCredentials()
                }
                .onFailure {
                    credentialsManager.clearCredentials()
                    message = "انتهت جلسة الدخول. سجّل الدخول من جديد."
                }
        } else {
            message = "سجّل الدخول بحساب السائق المخصص لك."
        }
        checking = false
    }

    if (authenticated) {
        content()
    } else {
        DriverLoginScreen(
            loading = checking || signingIn,
            message = message,
            onLogin = {
                signingIn = true
                message = "جارٍ فتح تسجيل الدخول الآمن"
                WebAuthProvider.login(account)
                    .withConnection("Username-Password-Authentication")
                    .withAudience(BuildConfig.HAMED_API_AUDIENCE)
                    .withScope("openid profile email offline_access")
                    .start(activity, object : Callback<Credentials, AuthenticationException> {
                        override fun onFailure(exception: AuthenticationException) {
                            signingIn = false
                            message = if (exception.isAuthenticationCanceled) {
                                "تم إلغاء تسجيل الدخول."
                            } else {
                                "تعذر تسجيل الدخول. تحقق من إعدادات Auth0 واتصال الإنترنت."
                            }
                        }

                        override fun onSuccess(credentials: Credentials) {
                            runCatching { credentialsManager.saveCredentials(credentials) }
                            scope.launch {
                                val check = validateWithServer(credentials.accessToken.orEmpty())
                                authenticated = check.allowed
                                message = check.message
                                signingIn = false
                                if (!check.allowed) credentialsManager.clearCredentials()
                            }
                        }
                    })
            },
            onClear = {
                credentialsManager.clearCredentials()
                message = "تم مسح جلسة الدخول المحلية. يمكنك المحاولة مجدداً."
            }
        )
    }
}

@Composable
private fun DriverLoginScreen(
    loading: Boolean,
    message: String,
    onLogin: () -> Unit,
    onClear: () -> Unit
) {
    val night = Color(0xFF07111D)
    val panel = Color(0xFF101F2D)
    val mint = Color(0xFF5EF0C2)
    val muted = Color(0xFF8EA5B8)
    Column(
        modifier = Modifier.fillMaxSize().background(night).padding(26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(color = panel, shape = RoundedCornerShape(26.dp)) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = mint, modifier = Modifier.padding(24.dp).size(42.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text("حامد سمارت دليفري", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("دخول السائقين المعتمدين", color = mint, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
        Spacer(Modifier.height(24.dp))
        Surface(color = panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = mint)
                Spacer(Modifier.width(10.dp))
                Text(message, color = muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onLogin,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mint, contentColor = night)
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = night, strokeWidth = 2.dp)
            else Text("تسجيل الدخول", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onClear, enabled = !loading) {
            Text("إعادة ضبط جلسة الدخول", color = muted, fontSize = 12.sp)
        }
    }
}
