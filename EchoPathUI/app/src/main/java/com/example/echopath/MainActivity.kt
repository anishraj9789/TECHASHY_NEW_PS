package com.example.echopath

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.echopath.engine.AcousticRadarEngine
import com.example.echopath.engine.RadarTelemetry
import com.example.echopath.engine.RadarUpdateListener
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale


private val Black = Color(0xFF000000)
private val NeonGreen = Color(0xFF22E58B)
private val SoftGreen = Color(0xFF8DF0BD)
private val SkyBlue = Color(0xFF8AB4F8)
private val CardBg = Color(0xFF05140F)
private val CardBorder = Color(0xFF0F6B4A)
private val SwitchGreen = Color(0xFF0E9F6E)
private val NavBg = Color(0xFF050B09)


class MainActivity : ComponentActivity() {
    private lateinit var radarEngine: AcousticRadarEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        radarEngine = AcousticRadarEngine(this)
        
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Black, surface = Black)) {
                EchoPathApp(radarEngine)
            }
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        radarEngine.stop()
    }
}


private fun prefs(context: Context) =
    context.getSharedPreferences("echopath_prefs", Context.MODE_PRIVATE)

private fun vibrateOnce(context: Context) {
    val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
}


@Composable
fun EchoPathApp(radarEngine: AcousticRadarEngine) {
    val context = LocalContext.current

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var isOn by rememberSaveable { mutableStateOf(true) }
    var audioEnabled by remember { mutableStateOf(prefs(context).getBoolean("audio", true)) }
    var hapticsEnabled by remember { mutableStateOf(prefs(context).getBoolean("haptics", true)) }

    var telemetry by remember { mutableStateOf<RadarTelemetry?>(null) }
    
    LaunchedEffect(audioEnabled, hapticsEnabled) {
        radarEngine.soundEnabled = audioEnabled
        radarEngine.hapticsEnabled = hapticsEnabled
    }
    
    DisposableEffect(Unit) {
        radarEngine.setUpdateListener(object : RadarUpdateListener {
            override fun onRadarUpdate(t: RadarTelemetry) {
                telemetry = t
            }
        })
        if (isOn) radarEngine.start()
        onDispose { radarEngine.stop() }
    }

    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) engine?.language = Locale.getDefault()
        }
        engine
    }
    DisposableEffect(Unit) {
        onDispose { tts.stop(); tts.shutdown() }
    }

    fun speak(text: String) {
        if (audioEnabled) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "echopath")
    }

    Scaffold(
        containerColor = Black,
        bottomBar = { BottomBar(selectedTab) { selectedTab = it } }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Black)
                .padding(padding)
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            AppHeader()

            when (selectedTab) {
                0 -> HomeScreen(
                    isOn = isOn,
                    telemetry = telemetry,
                    onToggle = {
                        isOn = !isOn
                        if (hapticsEnabled) vibrateOnce(context)
                        if (isOn) {
                            speak("Scanning surroundings")
                            radarEngine.start()
                        } else {
                            speak("EchoPath off")
                            radarEngine.stop()
                        }
                    }
                )
                else -> SettingsScreen(
                    audioEnabled = audioEnabled,
                    hapticsEnabled = hapticsEnabled,
                    onAudioChange = {
                        audioEnabled = it
                        prefs(context).edit().putBoolean("audio", it).apply()
                    },
                    onHapticsChange = {
                        hapticsEnabled = it
                        prefs(context).edit().putBoolean("haptics", it).apply()
                        if (it) vibrateOnce(context)
                    }
                )
            }
        }
    }
}


@Composable
fun AppHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WaveformLogo(Modifier.size(width = 64.dp, height = 56.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text("EchoPath", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Navigate with Sound", color = SkyBlue, fontSize = 17.sp)
        }
    }
}

@Composable
fun WaveformLogo(modifier: Modifier = Modifier) {
    // heights as a fraction of the available height (dots at the ends, tall bars in the middle)
    val heights = listOf(0.08f, 0.35f, 0.6f, 0.95f, 0.6f, 0.35f, 0.08f)
    Canvas(modifier) {
        val gap = size.width / (heights.size)
        val strokeW = 5.dp.toPx()
        heights.forEachIndexed { i, h ->
            val x = gap * i + gap / 2
            val barH = size.height * h
            val top = (size.height - barH) / 2
            drawLine(
                color = NeonGreen,
                start = Offset(x, top),
                end = Offset(x, top + barH + 0.1f),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }
    }
}


@Composable
fun HomeScreen(isOn: Boolean, telemetry: RadarTelemetry?, onToggle: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PowerButton(isOn = isOn, onClick = onToggle)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isOn) "ON" else "OFF",
            color = if (isOn) NeonGreen else Color.Gray,
            fontSize = 36.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = if (isOn) {
                telemetry?.let {
                    "Status: ${it.tier} | Delta: ${String.format(Locale.US, "%.1f", it.delta)}"
                } ?: "Scanning surroundings..."
            } else "Tap to start",
            color = SkyBlue,
            fontSize = 18.sp
        )
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
fun PowerButton(isOn: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    val glowAlpha = if (isOn) pulse else 0f
    val ringColor = if (isOn) NeonGreen else Color(0xFF2A2F2D)

    Box(
        modifier = Modifier
            .size(300.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val outer = size.minDimension / 2

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NeonGreen.copy(alpha = 0.35f * glowAlpha), Color.Transparent),
                    center = center,
                    radius = outer
                ),
                radius = outer,
                center = center
            )
            drawCircle(
                color = ringColor.copy(alpha = 0.55f),
                radius = outer * 0.93f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(ringColor, SoftGreen.takeIf { isOn } ?: ringColor, ringColor),
                    center = center
                ),
                radius = outer * 0.80f,
                center = center,
                style = Stroke(width = 14.dp.toPx())
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF0B1A14), Black),
                    center = center,
                    radius = outer * 0.7f
                ),
                radius = outer * 0.70f,
                center = center
            )
            drawCircle(
                color = ringColor.copy(alpha = 0.5f),
                radius = outer * 0.70f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
        Icon(
            imageVector = Icons.Filled.PowerSettingsNew,
            contentDescription = "Power",
            tint = if (isOn) SoftGreen else Color.Gray,
            modifier = Modifier
                .size(84.dp)
                .alpha(if (isOn) 1f else 0.7f)
        )
    }
}


@Composable
fun SettingsScreen(
    audioEnabled: Boolean,
    hapticsEnabled: Boolean,
    onAudioChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Settings, null, tint = NeonGreen, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(14.dp))
            Text("Settings", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text("Manage your app preferences and safety features.", color = SkyBlue, fontSize = 17.sp)
        Spacer(Modifier.height(24.dp))

        SettingCard(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = "Audio",
            description = "Voice guidance and sound alerts.",
            checked = audioEnabled,
            onCheckedChange = onAudioChange
        )
        Spacer(Modifier.height(22.dp))
        SettingCard(
            icon = Icons.Filled.Vibration,
            title = "Haptics",
            description = "Vibration feedback for important actions.",
            checked = hapticsEnabled,
            onCheckedChange = onHapticsChange
        )
    }
}

@Composable
fun SettingCard(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg)
            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .border(BorderStroke(1.dp, CardBorder), CircleShape)
                .background(Color(0xFF071A13), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = NeonGreen, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(description, color = SkyBlue, fontSize = 16.sp, lineHeight = 21.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SwitchGreen,
                checkedBorderColor = SwitchGreen,
                uncheckedThumbColor = Color.LightGray,
                uncheckedTrackColor = Color(0xFF1B2420),
                uncheckedBorderColor = Color(0xFF2E3A35)
            )
        )
    }
}


@Composable
fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        color = NavBg,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        border = BorderStroke(1.dp, CardBorder.copy(alpha = 0.6f)),
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            NavItem(Icons.Filled.Home, "Home", selected == 0) { onSelect(0) }
            NavItem(Icons.Outlined.Settings, "Settings", selected == 1) { onSelect(1) }
        }
    }
}

@Composable
fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) NeonGreen else SkyBlue
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(32.dp))
        Text(label, color = tint, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .size(width = 28.dp, height = 4.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) NeonGreen else Color.Transparent)
        )
    }
}
