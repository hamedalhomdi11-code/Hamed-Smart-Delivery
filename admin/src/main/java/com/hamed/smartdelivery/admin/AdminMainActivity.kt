package com.hamed.smartdelivery.admin

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Night = Color(0xFF07111D)
private val Panel = Color(0xFF101F2D)
private val Mint = Color(0xFF5EF0C2)
private val Muted = Color(0xFF8EA5B8)
private data class Driver(val id: String, val username: String, val status: String, val validUntilMs: Long)

private class AdminApi(context: Context) {
    private val baseUrl = BuildConfig.HAMED_API_BASE_URL.trim().trimEnd('/')
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "hamed_admin_secure_session", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    fun token(): String? = prefs.getString("session_token", null)
    fun saveToken(value: String) { prefs.edit().putString("session_token", value).apply() }
    fun clearToken() { prefs.edit().remove("session_token").apply() }

    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, token: String? = token()): JSONObject =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) throw IllegalStateException("عنوان خادم الحسابات غير مضبوط في نسخة التطبيق.")
            val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 12000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/json")
                if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token)
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = if (response.isBlank()) JSONObject() else JSONObject(response)
                if (code !in 200..299) {
                    val error = json.optString("error", "HTTP " + code)
                    throw IllegalStateException(errorMessage(error, code))
                }
                json
            } finally { connection.disconnect() }
        }

    suspend fun login(username: String, password: String): String =
        request("/v1/admin/login", "POST", JSONObject().put("username", username).put("password", password), null).getString("token")
    suspend fun drivers(): List<Driver> {
        val rows = request("/v1/admin/drivers").optJSONArray("drivers") ?: JSONArray()
        return (0 until rows.length()).map { i ->
            val item = rows.getJSONObject(i)
            Driver(item.getString("id"), item.getString("username"), item.getString("status"), item.optLong("valid_until_ms"))
        }
    }
    suspend fun createDriver(username: String, password: String, duration: Int, unit: String) {
        request("/v1/admin/drivers", "POST", JSONObject().put("username", username).put("password", password)
            .put("durationValue", duration).put("durationUnit", unit))
    }
    suspend fun updateDriver(id: String, duration: Int, unit: String) {
        request("/v1/admin/drivers/" + id, "PATCH", JSONObject().put("durationValue", duration).put("durationUnit", unit))
    }
    suspend fun setStatus(id: String, enabled: Boolean) {
        request("/v1/admin/drivers/" + id + "/" + if (enabled) "enable" else "disable", "POST", JSONObject())
    }
    suspend fun logout() {
        runCatching { request("/v1/admin/logout", "POST", JSONObject()) }
        clearToken()
    }
    private fun errorMessage(error: String, code: Int): String = when (error) {
        "admin_bootstrap_secret_not_configured" -> "لم يتم إعداد كلمة مرور الإدارة في أسرار Cloudflare بعد."
        "invalid_credentials" -> "اسم المستخدم أو كلمة المرور غير صحيحة."
        "admin_session_expired" -> "انتهت جلسة الإدارة. سجّل الدخول مرة أخرى."
        "auth0_management_api_not_configured" -> "إعداد Auth0 Management API أو اتصال قاعدة المستخدمين غير مكتمل."
        "username_already_exists_or_auth0_rejected_it" -> "اسم المستخدم مستخدم مسبقاً أو رفضه Auth0."
        "driver_password_must_be_at_least_10_characters" -> "كلمة مرور السائق يجب ألا تقل عن 10 أحرف."
        "username_format_invalid" -> "صيغة اسم المستخدم غير صحيحة."
        else -> error + " (HTTP " + code + ")"
    }
}

class AdminMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AdminDashboard() }
    }
}

@Composable
private fun AdminDashboard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember { AdminApi(context) }
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(api.token()) }
    var username by remember { mutableStateOf("Hamed") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("سجّل الدخول بحساب الإدارة.") }
    var loading by remember { mutableStateOf(false) }
    var drivers by remember { mutableStateOf(emptyList<Driver>()) }
    var showCreate by remember { mutableStateOf(false) }
    var newUsername by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("1") }
    var durationUnit by remember { mutableStateOf("days") }
    var expanded by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { api.drivers() }.onSuccess { drivers = it; message = "تم تحديث قائمة السائقين." }
                .onFailure { message = it.message ?: "تعذر تحميل الحسابات."; if (message.contains("انتهت جلسة الإدارة")) { api.clearToken(); token = null } }
            loading = false
        }
    }
    LaunchedEffect(token) { if (token != null) refresh() }

    MaterialTheme(colorScheme = darkColorScheme(background = Night, surface = Panel, primary = Mint)) {
        Scaffold(containerColor = Night) { insets ->
            if (token == null) {
                Column(Modifier.fillMaxSize().padding(insets).padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(color = Panel, shape = RoundedCornerShape(24.dp)) {
                        Icon(Icons.Rounded.Lock, null, tint = Mint, modifier = Modifier.padding(22.dp).size(42.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Hamed Smart Delivery", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text("تسجيل دخول الإدارة", color = Mint, fontSize = 14.sp)
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("اسم المستخدم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("كلمة المرور") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = Muted, fontSize = 12.sp)
                    Button(onClick = {
                        scope.launch {
                            loading = true
                            runCatching { api.login(username.trim(), password) }
                                .onSuccess { api.saveToken(it); token = it; password = ""; message = "تم تسجيل الدخول." }
                                .onFailure { message = it.message ?: "تعذر تسجيل الدخول." }
                            loading = false
                        }
                    }, enabled = !loading && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp)) else Text("دخول آمن")
                    }
                    Text("تُحفظ الجلسة على الجهاز بتشفير Android Keystore بعد أول دخول.", color = Muted, fontSize = 11.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(insets).padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 22.dp, bottom = 28.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Panel, shape = RoundedCornerShape(15.dp)) {
                                Icon(Icons.Rounded.AdminPanelSettings, null, tint = Mint, modifier = Modifier.padding(12.dp).size(30.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Hamed Smart Delivery", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                Text("لوحة إدارة السائقين", color = Mint, fontSize = 12.sp)
                            }
                            TextButton(onClick = { scope.launch { api.logout(); token = null; drivers = emptyList() } }) { Text("خروج", color = Muted) }
                        }
                    }
                    item {
                        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(15.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column { Text("إجمالي السائقين", color = Muted, fontSize = 11.sp); Text(drivers.size.toString(), color = Mint, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                                Column(horizontalAlignment = Alignment.End) { Text("الحسابات النشطة", color = Muted, fontSize = 11.sp); Text(drivers.count { it.status == "active" }.toString(), color = Mint, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Group, null, tint = Mint)
                            Spacer(Modifier.width(8.dp))
                            Text("حسابات السائقين", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { refresh() }, enabled = !loading) { Text("تحديث") }
                        }
                    }
                    items(drivers, key = { it.id }) { driver ->
                        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(driver.username, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(if (driver.status == "active") "نشط" else "معطّل", color = if (driver.status == "active") Mint else Color(0xFFFFA36C), fontSize = 11.sp)
                                    }
                                    TextButton(onClick = {
                                        scope.launch {
                                            loading = true
                                            runCatching { api.setStatus(driver.id, driver.status != "active"); api.drivers() }
                                                .onSuccess { drivers = it; message = "تم تحديث حالة الحساب." }
                                                .onFailure { message = it.message ?: "تعذر تحديث الحساب." }
                                            loading = false
                                        }
                                    }, enabled = !loading) { Text(if (driver.status == "active") "تعطيل" else "تفعيل") }
                                }
                                Text("ينتهي: " + if (driver.validUntilMs > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(driver.validUntilMs)) else "غير معروف", color = Muted, fontSize = 11.sp)
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        loading = true
                                        runCatching { api.updateDriver(driver.id, duration.toIntOrNull() ?: 1, durationUnit); api.drivers() }
                                            .onSuccess { drivers = it; message = "تم تحديث مدة الصلاحية." }
                                            .onFailure { message = it.message ?: "تعذر تحديث الصلاحية." }
                                        loading = false
                                    }
                                }, enabled = !loading) { Text("تطبيق المدة الحالية") }
                            }
                        }
                    }
                    item {
                        Button(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Night)) {
                            Icon(Icons.Rounded.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("إنشاء حساب سائق")
                        }
                    }
                    item { Text(message, color = Muted, fontSize = 11.sp) }
                }
            }
        }
        if (showCreate) {
            AlertDialog(onDismissRequest = { showCreate = false }, containerColor = Panel,
                title = { Text("إنشاء حساب سائق", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedTextField(value = newUsername, onValueChange = { newUsername = it }, label = { Text("اسم المستخدم") }, singleLine = true)
                        OutlinedTextField(value = newPassword, onValueChange = { newPassword = it }, label = { Text("كلمة مرور السائق") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(value = duration, onValueChange = { duration = it.filter(Char::isDigit) }, label = { Text("مدة الصلاحية") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Box {
                            OutlinedButton(onClick = { expanded = true }) { Text("الوحدة: " + when (durationUnit) { "days" -> "أيام"; "weeks" -> "أسابيع"; else -> "أشهر" }) }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                listOf("days" to "أيام", "weeks" to "أسابيع", "months" to "أشهر").forEach { pair ->
                                    DropdownMenuItem(text = { Text(pair.second) }, onClick = { durationUnit = pair.first; expanded = false })
                                }
                            }
                        }
                        Text("تُنشأ هوية السائق في Auth0 وتُربط بصلاحية خادمية. كلمة المرور لا تُحفظ في قاعدة التطبيق.", color = Muted, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    Button(enabled = !loading && newUsername.isNotBlank() && newPassword.length >= 10 && (duration.toIntOrNull() ?: 0) > 0, onClick = {
                        scope.launch {
                            loading = true
                            runCatching { api.createDriver(newUsername.trim(), newPassword, duration.toInt(), durationUnit); api.drivers() }
                                .onSuccess { drivers = it; showCreate = false; newUsername = ""; newPassword = ""; message = "تم إنشاء حساب السائق." }
                                .onFailure { message = it.message ?: "تعذر إنشاء الحساب." }
                            loading = false
                        }
                    }) { Text("إنشاء") }
                },
                dismissButton = { TextButton(onClick = { showCreate = false }) { Text("إلغاء") } }
            )
        }
    }
}
