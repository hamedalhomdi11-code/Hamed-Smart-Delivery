package com.hamed.smartdelivery.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Security
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

private val Night = Color(0xFF07111D)
private val Panel = Color(0xFF101F2D)
private val Mint = Color(0xFF5EF0C2)
private val Cyan = Color(0xFF53C7F4)
private val Muted = Color(0xFF8EA5B8)

class AdminMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AdminDashboard() }
    }
}

@Composable
private fun AdminDashboard() {
    var showCreateDialog by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("1") }
    var durationUnit by remember { mutableStateOf("أيام") }
    var expanded by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Night,
        surface = Panel,
        primary = Mint,
        secondary = Cyan
    )) {
        Scaffold(containerColor = Night) { insets ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 24.dp, bottom = 28.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Rounded.AdminPanelSettings, null, tint = Mint, modifier = Modifier.padding(13.dp).size(30.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Hamed Smart Delivery", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                            Text("لوحة إدارة السائقين", color = Mint, fontSize = 12.sp)
                        }
                    }
                }
                item {
                    Surface(color = Color(0xFF382B19), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Security, null, tint = Color(0xFFFFC66D))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("الربط الآمن لم يكتمل", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("لم يتم تسجيل جهاز الإدارة لدى الخادم بعد. تبقى عمليات إنشاء الحسابات والتعديل مقفلة حتى إكمال تهيئة الحماية.", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        AdminMetric("إجمالي السائقين", "—", Modifier.weight(1f))
                        AdminMetric("الحسابات النشطة", "—", Modifier.weight(1f))
                    }
                }
                item {
                    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Group, null, tint = Mint)
                                Spacer(Modifier.width(9.dp))
                                Text("إدارة حسابات السائقين", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            Text("إنشاء الحسابات وتحديد مدة الصلاحية وتعطيلها أو إعادة تفعيلها سيتم عبر الخادم المركزي بعد إكمال تهيئة جهاز الإدارة وربط Auth0 وCloudflare.", color = Muted, fontSize = 12.sp)
                            Button(
                                onClick = { showCreateDialog = true },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Night)
                            ) {
                                Icon(Icons.Rounded.PersonAdd, null)
                                Spacer(Modifier.width(8.dp))
                                Text("إعداد حساب سائق جديد", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                item {
                    Text("ملاحظات الصلاحية", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("تُحسب المدة من وقت حفظها على الخادم. الوحدات المدعومة: أيام، أسابيع، وأشهر تقويمية. ساعة الخادم هي المرجع الرسمي.", color = Muted, fontSize = 12.sp)
                }
                item {
                    Text("ADMIN • 0.1.0 • OWNER DEVICE ONLY", color = Muted, fontSize = 10.sp)
                }
            }
        }

        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                containerColor = Panel,
                title = { Text("إعداد حساب سائق", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("اسم المستخدم") }, singleLine = true)
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("كلمة المرور") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(value = duration, onValueChange = { duration = it.filter(Char::isDigit) }, label = { Text("مدة الصلاحية") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Box {
                            OutlinedButton(onClick = { expanded = true }) { Text("الوحدة: $durationUnit") }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                listOf("أيام", "أسابيع", "أشهر").forEach { unit ->
                                    DropdownMenuItem(text = { Text(unit) }, onClick = { durationUnit = unit; expanded = false })
                                }
                            }
                        }
                        Text("الحفظ غير متاح حتى اكتمال تهيئة جهاز الإدارة وواجهة الخادم. لن يتم حفظ كلمة المرور محلياً.", color = Muted, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    Button(onClick = { }, enabled = false) { Text("حفظ الحساب") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("إغلاق", color = Mint) }
                }
            )
        }
    }
}

@Composable
private fun AdminMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(15.dp)) {
            Text(title, color = Muted, fontSize = 10.sp)
            Text(value, color = Mint, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
