package com.example.doorsecurity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

// ── Kolory ────────────────────────────────────────────────────────────────
private val BgDark        = Color(0xFF0A0D12)
private val BgCard        = Color(0xFF111820)
private val BgCardAlt     = Color(0xFF161D27)
private val AccentGreen   = Color(0xFF00E676)
private val AccentRed     = Color(0xFFFF3D3D)
private val AccentAmber   = Color(0xFFFFC107)
private val TextPrimary   = Color(0xFFE8ECF0)
private val TextSecondary = Color(0xFF6E8098)
private val Border        = Color(0xFF1E2D3D)

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(
    val id: String,
    val timestamp: Long,
    val message: String,
    val level: LogLevel
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp * 1000L))
        }
}

private fun String.toLogLevel(): LogLevel = when (uppercase()) {
    "WARN"  -> LogLevel.WARN
    "ERROR" -> LogLevel.ERROR
    else    -> LogLevel.INFO
}

@Composable
fun rememberFirestoreLogs(): List<LogEntry> {
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }

    DisposableEffect(Unit) {
        val listener = FirebaseFirestore.getInstance()
            .collection("logs")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                logs = snapshot.documents.mapNotNull { doc ->
                    val timestamp = doc.getTimestamp("timestamp")?.seconds
                        ?: doc.getLong("timestamp")
                        ?: return@mapNotNull null
                    val message = doc.getString("message") ?: return@mapNotNull null
                    val level   = doc.getString("level")?.toLogLevel() ?: LogLevel.INFO
                    LogEntry(id = doc.id, timestamp = timestamp, message = message, level = level)
                }
            }
        onDispose { listener.remove() }
    }

    return logs
}

fun uriToBase64(context: Context, uri: Uri): String? {
    return try {
        // 1. Sprawdź rotację (EXIF)
        val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        // 2. Dekoduj bitmapę
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        var bitmap = BitmapFactory.decodeStream(stream)
        stream.close()

        // 3. Obróć jeśli trzeba
        if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        // 4. Skaluj
        val scaled = if (bitmap.width > 800) {
            val ratio = 800f / bitmap.width
            Bitmap.createScaledBitmap(bitmap, 800, (bitmap.height * ratio).toInt(), true)
        } else bitmap

        // 5. Kompresuj
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DoorSecurityTheme { DoorSecurityApp() } }
    }
}

@Composable
fun DoorSecurityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BgDark, surface = BgCard, primary = AccentGreen,
            onBackground = TextPrimary, onSurface = TextPrimary
        ),
        content = content
    )
}

enum class Screen { DASHBOARD, CAMERA, LOGS, FACES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoorSecurityApp() {
    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }
    val logs = rememberFirestoreLogs()

    Scaffold(
        containerColor = BgDark,
        topBar    = { TopBar(currentScreen, logs) },
        bottomBar = { BottomNavBar(currentScreen) { currentScreen = it } }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (currentScreen) {
                Screen.DASHBOARD -> DashboardScreen(logs)
                Screen.CAMERA    -> CameraScreen()
                Screen.LOGS      -> LogsScreen(logs)
                Screen.FACES     -> FacesScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(screen: Screen, logs: List<LogEntry>) {
    val title = when (screen) {
        Screen.DASHBOARD -> "DoorSecurity"
        Screen.CAMERA    -> "Podgląd kamery"
        Screen.LOGS      -> "Logi systemowe"
        Screen.FACES     -> "Baza twarzy"
    }
    val dotColor = if (logs.any { it.level == LogLevel.ERROR }) AccentRed else AccentGreen
    val isConnected = logs.isNotEmpty()

    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = TextPrimary)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgCard, titleContentColor = TextPrimary)
    )
}

@Composable
fun BottomNavBar(current: Screen, onSelect: (Screen) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = AccentGreen, selectedTextColor = AccentGreen,
        unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary,
        indicatorColor = AccentGreen.copy(alpha = 0.12f)
    )
    NavigationBar(containerColor = BgCard, tonalElevation = 0.dp) {
        NavigationBarItem(selected = current == Screen.DASHBOARD, onClick = { onSelect(Screen.DASHBOARD) },
            icon = { Icon(Icons.Default.Home, null) }, label = { Text("Dashboard", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }, colors = colors)
        NavigationBarItem(selected = current == Screen.CAMERA, onClick = { onSelect(Screen.CAMERA) },
            icon = { Icon(Icons.Default.Star, null) }, label = { Text("Kamera", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }, colors = colors)
        NavigationBarItem(selected = current == Screen.LOGS, onClick = { onSelect(Screen.LOGS) },
            icon = { Icon(Icons.Default.List, null) }, label = { Text("Logi", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }, colors = colors)
        NavigationBarItem(selected = current == Screen.FACES, onClick = { onSelect(Screen.FACES) },
            icon = { Icon(Icons.Default.Face, null) }, label = { Text("Twarze", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }, colors = colors)
    }
}

@Composable
fun DashboardScreen(logs: List<LogEntry>) {
    val errorCount = logs.count { it.level == LogLevel.ERROR }
    val warnCount  = logs.count { it.level == LogLevel.WARN }

    LazyColumn(modifier = Modifier.fillMaxSize().background(BgDark),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("LOGI",     logs.size.toString(), TextPrimary,                                       Modifier.weight(1f))
                StatCard("BŁĘDY",    errorCount.toString(), if (errorCount > 0) AccentRed   else TextPrimary, Modifier.weight(1f))
                StatCard("OSTRZEŻ.", warnCount.toString(),  if (warnCount  > 0) AccentAmber else TextPrimary, Modifier.weight(1f))
            }
        }
        item { SectionTitle("Ostatnie zdarzenia") }
        if (logs.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    border = BorderStroke(0.5.dp, Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Brak logów", fontSize = 13.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        else { items(logs.take(5)) { LogRow(it) } }
    }
}

@Composable
fun StatCard(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard), border = BorderStroke(0.5.dp, Border)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 10.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = valueColor, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── Camera ────────────────────────────────────────────────────────────────
data class LatestImage(
    val bitmap: Bitmap,
    val status: String?,
    val who: String?
)

data class DeviceInfo(
    val id: String,
    val name: String
)

@Composable
fun rememberFirestoreDevices(): List<DeviceInfo> {
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }

    DisposableEffect(Unit) {
        val listener = FirebaseFirestore.getInstance()
            .collection("devices_info")
            .orderBy("name", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                devices = snapshot.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    DeviceInfo(id = doc.id, name = name)
                }
            }
        onDispose { listener.remove() }
    }

    return devices
}

@Composable
fun rememberDeviceImage(deviceId: String?): LatestImage? {
    var image by remember(deviceId) { mutableStateOf<LatestImage?>(null) }

    DisposableEffect(deviceId) {
        if (deviceId == null) {
            image = null
            return@DisposableEffect onDispose {}
        }

        val listener = FirebaseFirestore.getInstance()
            .collection("images")
            .document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                val b64 = snapshot.getString("image_64") ?: snapshot.getString("image_b64")
                if (b64 == null) return@addSnapshotListener

                val status = snapshot.getString("status")
                val who = snapshot.getString("who")

                try {
                    val cleanB64 = if (b64.contains(",")) b64.substringAfter(",") else b64
                    val bytes  = Base64.decode(cleanB64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) image = LatestImage(bitmap, status, who)
                } catch (_: Exception) {}
            }
        onDispose { listener.remove() }
    }

    return image
}

@Composable
fun CameraScreen() {
    val devices = rememberFirestoreDevices()
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    val latest = rememberDeviceImage(if (showPreview) selectedDevice?.id else null)

    // Stan dla edycji nazwy
    var deviceToEdit by remember { mutableStateOf<DeviceInfo?>(null) }
    var newDeviceName by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionTitle("Wybierz urządzenie")
        if (devices.isEmpty()) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                border = BorderStroke(0.5.dp, Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Brak urządzeń", fontSize = 13.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                }
            }
        } else {
            devices.forEach { device ->
                DeviceRow(
                    device = device,
                    isSelected = selectedDevice?.id == device.id,
                    onClick = {
                        selectedDevice = device
                        showPreview = false
                    },
                    onDelete = {
                        val deviceIdToDelete = device.id
                        FirebaseFirestore.getInstance()
                            .collection("devices_info")
                            .document(deviceIdToDelete)
                            .delete()
                            .addOnSuccessListener {
                                if (selectedDevice?.id == deviceIdToDelete) {
                                    selectedDevice = null
                                    showPreview = false
                                }
                            }
                    },
                    onEdit = {
                        deviceToEdit = device
                        newDeviceName = device.name
                    }
                )
            }
        }

        // Dialog zmiany nazwy
        if (deviceToEdit != null) {
            AlertDialog(
                onDismissRequest = { deviceToEdit = null },
                containerColor = BgCard,
                title = { Text("Zmień nazwę urządzenia", color = TextPrimary, fontSize = 16.sp, fontFamily = FontFamily.Monospace) },
                text = {
                    OutlinedTextField(
                        value = newDeviceName,
                        onValueChange = { newDeviceName = it },
                        label = { Text("Nowa nazwa", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            unfocusedBorderColor = Border,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = AccentGreen
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val id = deviceToEdit!!.id
                            val name = newDeviceName.trim()
                            if (name.isNotBlank()) {
                                FirebaseFirestore.getInstance()
                                    .collection("devices_info")
                                    .document(id)
                                    .update("name", name)
                                    .addOnSuccessListener {
                                        deviceToEdit = null
                                    }
                            }
                        }
                    ) {
                        Text("ZAPISZ", color = AccentGreen, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deviceToEdit = null }) {
                        Text("ANULUJ", color = TextSecondary, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        if (selectedDevice != null) {
            Button(
                onClick = {
                    showPreview = true
                    val db = FirebaseFirestore.getInstance()
                    db.collection("commands_queue")
                        .document(selectedDevice!!.id)
                        .collection("commands")
                        .add(mapOf(
                            "command" to "capture",
                            "timestamp" to System.currentTimeMillis() / 1000L
                        ))
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen,
                    contentColor = BgDark
                )
            ) {
                Text("ZOBACZ PODGLĄD Z KAMERY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (showPreview) {
            val borderColor = when (latest?.status?.uppercase()) {
                "OK" -> AccentGreen
                "NO" -> AccentRed
                else -> Border
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                border = BorderStroke(2.dp, borderColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                        if (latest != null) {
                            Image(
                                bitmap = latest.bitmap.asImageBitmap(),
                                contentDescription = "Ostatni obraz z kamery",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            )
                        } else {
                            Text(
                                "Oczekiwanie na odpowiedź urządzenia…",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (latest != null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(BgCardAlt)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "ROZPOZNANO: ${latest.who ?: "Nieznany"}",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceRow(
    device: DeviceInfo, 
    isSelected: Boolean = false, 
    onClick: () -> Unit = {},
    onDelete: () -> Unit = {},
    onEdit: () -> Unit = {}
) {
    val borderColor = if (isSelected) AccentGreen.copy(alpha = 0.5f) else Border
    val containerColor = if (isSelected) BgCardAlt else BgCard

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSelected) 1.dp else 0.5.dp, borderColor),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) AccentGreen else Border)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    device.name,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edytuj",
                    tint = AccentAmber.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Usuń",
                    tint = AccentRed.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}


@Composable
fun LogsScreen(logs: List<LogEntry>) {
    var filter by remember { mutableStateOf<LogLevel?>(null) }
    val filtered = if (filter == null) logs else logs.filter { it.level == filter }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("Wszystkie", filter == null,           AccentGreen) { filter = null }
            FilterChip("INFO",      filter == LogLevel.INFO,  AccentGreen) { filter = LogLevel.INFO }
            FilterChip("WARN",      filter == LogLevel.WARN,  AccentAmber) { filter = LogLevel.WARN }
            FilterChip("ERROR",     filter == LogLevel.ERROR, AccentRed)   { filter = LogLevel.ERROR }
        }
        HorizontalDivider(color = Border, thickness = 0.5.dp)
        when {
            logs.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    border = BorderStroke(0.5.dp, Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Brak logów", fontSize = 13.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Brak logów dla wybranego filtra", fontSize = 13.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
            }
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.id }) { LogRow(it) }
            }
        }
    }
}

@Composable
fun FacesScreen() {
    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var personName by remember { mutableStateOf("") }
    var uploadState by remember { mutableStateOf<UploadState>(UploadState.Idle) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedUri = uri
            // Dekoduj podgląd
            val stream = context.contentResolver.openInputStream(uri)
            selectedBitmap = BitmapFactory.decodeStream(stream)
            stream?.close()
            uploadState = UploadState.Idle
        }
    }

    Column(
        Modifier.fillMaxSize().background(BgDark).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionTitle("Dodaj twarz do bazy")

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            border = BorderStroke(0.5.dp, Border),
            modifier = Modifier.fillMaxWidth().height(240.dp).clickable { picker.launch("image/*") }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (selectedBitmap != null) {
                    Image(
                        bitmap = selectedBitmap!!.asImageBitmap(),
                        contentDescription = "Wybrane zdjęcie",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Add, null, tint = TextSecondary, modifier = Modifier.size(40.dp))
                        Text("Kliknij aby wybrać zdjęcie", fontSize = 13.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        OutlinedTextField(
            value = personName,
            onValueChange = { personName = it },
            label = { Text("Imię osoby", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentGreen,
                unfocusedBorderColor = Border,
                focusedLabelColor = AccentGreen,
                unfocusedLabelColor = TextSecondary,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentGreen
            )
        )

        val canSend = selectedUri != null && personName.isNotBlank() && uploadState !is UploadState.Loading

        Button(
            onClick = {
                uploadState = UploadState.Loading
                val base64 = uriToBase64(context, selectedUri!!)
                if (base64 == null) {
                    uploadState = UploadState.Error("Nie udało się przetworzyć zdjęcia")
                    return@Button
                }
                FirebaseFirestore.getInstance().collection("faces").add(
                    mapOf(
                        "name"      to personName.trim(),
                        "image_b64" to base64,
                        "timestamp" to System.currentTimeMillis() / 1000L
                    )
                ).addOnSuccessListener {
                    uploadState = UploadState.Success
                    selectedUri = null
                    selectedBitmap = null
                    personName = ""
                }.addOnFailureListener { e ->
                    uploadState = UploadState.Error(e.message ?: "Błąd")
                }
            },
            enabled = canSend,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentGreen,
                disabledContainerColor = AccentGreen.copy(alpha = 0.2f),
                contentColor = Color(0xFF0A0D12),
                disabledContentColor = TextSecondary
            )
        ) {
            if (uploadState is UploadState.Loading) {
                CircularProgressIndicator(color = Color(0xFF0A0D12), modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Wyślij do bazy", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        // Status
        when (val state = uploadState) {
            is UploadState.Success -> StatusBanner(
                "Wysłano — malina przetworzy zdjęcie",
                AccentGreen
            )

            is UploadState.Error -> StatusBanner("Błąd: ${state.message}", AccentRed)
            else -> {}
        }
    }
}

sealed class UploadState {
    object Idle    : UploadState()
    object Loading : UploadState()
    object Success : UploadState()
    data class Error(val message: String) : UploadState()
}

@Composable
fun StatusBanner(text: String, color: Color) {
    Box(
        Modifier.fillMaxWidth()
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .border(0.5.dp, color.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(text, fontSize = 12.sp, color = color, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        color = TextSecondary, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun FilterChip(label: String, selected: Boolean, activeColor: Color, onClick: () -> Unit) {
    val bg     = if (selected) activeColor.copy(alpha = 0.15f) else BgCard
    val text   = if (selected) activeColor else TextSecondary
    val border = if (selected) activeColor.copy(alpha = 0.5f)  else Border
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(bg)
        .border(0.5.dp, border, RoundedCornerShape(6.dp)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, fontSize = 11.sp, color = text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun LogRow(entry: LogEntry) {
    val (levelColor, levelBg) = when (entry.level) {
        LogLevel.INFO  -> AccentGreen to AccentGreen.copy(alpha = 0.08f)
        LogLevel.WARN  -> AccentAmber to AccentAmber.copy(alpha = 0.08f)
        LogLevel.ERROR -> AccentRed   to AccentRed.copy(alpha = 0.10f)
    }
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(0.5.dp, Border), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.background(levelBg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text(entry.level.name, fontSize = 10.sp, color = levelColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.message, fontSize = 13.sp, color = TextPrimary, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(entry.formattedTime, fontSize = 10.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
