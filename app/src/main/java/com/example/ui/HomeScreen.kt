package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppSettingsEntity
import com.example.data.PresetEntity
import kotlin.math.abs
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AudioViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val currentGains by viewModel.currentGains.collectAsState()
    val customPresets by viewModel.customPresets.collectAsState()

    // Realtime analysis states
    val engineActive by viewModel.engineActive.collectAsState()
    val playbackActive by viewModel.playbackActive.collectAsState()
    val spectrum by viewModel.spectrumData.collectAsState()
    val vuL by viewModel.vuLeft.collectAsState()
    val vuR by viewModel.vuRight.collectAsState()
    val peakL by viewModel.peakLeft.collectAsState()
    val peakR by viewModel.peakRight.collectAsState()
    val trackInfo by viewModel.currentTrackInfo.collectAsState()

    // Theme Color Tokens
    val neonColor = remember(settings.themeColor) {
        getThemeColor(settings.themeColor)
    }

    // Modal state
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }

    // Navigation rack panels expansion state
    var currentRackTab by remember { mutableStateOf(RackTab.DYNAMIC_FX) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home_scaffold"),
        containerColor = Color(0xFF050506)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Sound-reactive premium dark ambient glow
                .drawBehind {
                    val pulse = spectrum.average().toFloat().coerceIn(0f, 1f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                neonColor.copy(alpha = 0.09f * (1f + pulse)),
                                Color(0x307000FF).copy(alpha = 0.05f * (1f + pulse)),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2f, size.height / 4f),
                            radius = size.width * 0.8f
                        )
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Header & Quick Controls Panel
                HeaderPanel(
                    settings = settings,
                    engineActive = engineActive,
                    playbackActive = playbackActive,
                    trackInfo = trackInfo,
                    neonColor = neonColor,
                    onToggleEngine = { viewModel.toggleEngine() },
                    onTogglePlayback = { viewModel.togglePlayback() },
                    onColorChanged = { viewModel.updateThemeColor(it) }
                )

                // 2. Real-time Analyzer & Segmented VU Meter Section
                AnalyzerModule(
                    spectrum = spectrum,
                    vuL = vuL,
                    vuR = vuR,
                    peakL = peakL,
                    peakR = peakR,
                    neonColor = neonColor
                )

                // 3. Central Equalizer Console Panel
                EqualizerConsole(
                    settings = settings,
                    currentGains = currentGains,
                    customPresets = customPresets,
                    neonColor = neonColor,
                    onBandCountSelected = { viewModel.updateBandCount(it) },
                    onBandGainUpdated = { idx, value -> viewModel.updateBandGain(idx, value) },
                    onPresetSelected = { viewModel.selectPreset(it) },
                    onCustomPresetSelected = { viewModel.selectCustomPreset(it) },
                    onSavePresetClicked = { showSaveDialog = true },
                    onDeleteCustomPreset = { viewModel.deleteCustomPreset(it) }
                )

                // 4. DAW FX Rack Section (Modular Switcher Tabs)
                FxRackSwitcher(
                    activeTab = currentRackTab,
                    onTabSelected = { currentRackTab = it },
                    neonColor = neonColor
                )

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0x0AFFFFFF),
                    border = BorderStroke(1.dp, Color(0x14FFFFFF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        when (currentRackTab) {
                            RackTab.DYNAMIC_FX -> DynamicFxPanel(
                                settings = settings,
                                viewModel = viewModel,
                                neonColor = neonColor
                            )
                            RackTab.AMPLIFY_WIDE -> AmplificationPanel(
                                settings = settings,
                                viewModel = viewModel,
                                neonColor = neonColor
                            )
                            RackTab.STUDIO_MIX -> StudioMixPanel(
                                settings = settings,
                                viewModel = viewModel,
                                neonColor = neonColor
                            )
                            RackTab.DAW_DYNAMICS -> DynamicsCompressorPanel(
                                settings = settings,
                                viewModel = viewModel,
                                neonColor = neonColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Save Custom Preset Dialog Modal
            if (showSaveDialog) {
                Dialog(onDismissRequest = { showSaveDialog = false }) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xFF0C131E),
                        border = BorderStroke(1.dp, neonColor.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "SAVE MAIN MASTER PRESET",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = neonColor
                                )
                                IconButton(onClick = { showSaveDialog = false }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                                }
                            }

                            Text(
                                text = "This converts current sliders into a recallable custom user mastering config.",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )

                            OutlinedTextField(
                                value = presetNameInput,
                                onValueChange = { presetNameInput = it },
                                label = { Text("Preset Title", color = neonColor.copy(alpha = 0.7f)) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = neonColor,
                                    focusedLabelColor = neonColor,
                                    unfocusedBorderColor = Color(0x20FFFFFF),
                                    unfocusedLabelColor = Color.LightGray,
                                    focusedTextColor = Color.White
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = {
                                    if (presetNameInput.isNotBlank()) {
                                        viewModel.saveCustomPreset(presetNameInput.trim())
                                        presetNameInput = ""
                                        showSaveDialog = false
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = neonColor,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("STORE ENGINE PRESET", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Header & Power Controller Panel
@Composable
fun HeaderPanel(
    settings: AppSettingsEntity,
    engineActive: Boolean,
    playbackActive: Boolean,
    trackInfo: String,
    neonColor: Color,
    onToggleEngine: () -> Unit,
    onTogglePlayback: () -> Unit,
    onColorChanged: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0x0AFFFFFF),
        border = BorderStroke(1.dp, Color(0x14FFFFFF)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Application Neon Logo Shape
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LogoIcon(neonColor = neonColor)

                    Column {
                        Text(
                            text = "BRO EQ PRO",
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 20.sp,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "MASTERING ENGINE v4.2",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = neonColor,
                            letterSpacing = 2.sp
                        )
                    }
                }

                // Interactive Main Power ON/OFF Button with heavy glow
                IconButton(
                    onClick = onToggleEngine,
                    modifier = Modifier
                        .size(54.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    if (engineActive) neonColor.copy(alpha = 0.25f) else Color.Transparent,
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                        .border(
                            width = 2.dp,
                            color = if (engineActive) neonColor else Color(0x25FFFFFF),
                            shape = CircleShape
                        )
                ) {
                    PowerIcon(neonColor = neonColor, isOn = engineActive)
                }
            }

            // Built-in Synthesizer Monitoring controller trigger row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF040913), RoundedCornerShape(12.dp))
                    .border(BorderStroke(1.dp, Color(0x10FFFFFF)), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onTogglePlayback,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (playbackActive) neonColor else Color(0x15FFFFFF),
                            contentColor = if (playbackActive) Color.Black else Color.White
                        ),
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (playbackActive) {
                            PauseIcon(color = Color.Black)
                        } else {
                            PlayIcon(color = Color.White)
                        }
                    }

                    Column {
                        Text(
                            text = if (playbackActive) "PLAYBACK MONITORING ACTIVE" else "SYNTH MONITOR STANDBY",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = if (playbackActive) neonColor else Color.Gray
                        )
                        Text(
                            text = trackInfo,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.LightGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Dynamic Status Chip
                Box(
                    modifier = Modifier
                        .background(
                            if (engineActive) neonColor.copy(alpha = 0.1f) else Color(0x10FFFFFF),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            BorderStroke(
                                1.dp,
                                if (engineActive) neonColor.copy(alpha = 0.4f) else Color(0x20FFFFFF)
                            ),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (engineActive) "ACTIVE DSP" else "BYPASS",
                        color = if (engineActive) neonColor else Color.Gray,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }
            }

            // Quick Theme Color Swappers Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "THEME SKIN",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(end = 4.dp)
                )

                val skins = listOf("Neon Cyan", "Neon Blue", "Neon Red", "Neon Purple", "Neon Green", "Gold")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(skins) { colorName ->
                        val col = getThemeColor(colorName)
                        val isSel = settings.themeColor == colorName
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(col)
                                .border(
                                    width = if (isSel) 2.2.dp else 1.dp,
                                    color = if (isSel) Color.White else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { onColorChanged(colorName) }
                        )
                    }
                }
            }
        }
    }
}

// Logo representation
@Composable
fun LogoIcon(neonColor: Color) {
    val transition = rememberInfiniteTransition(label = "logo_anim")
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_pulse"
    )

    Canvas(modifier = Modifier.size(40.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = (size.width / 2f) * 0.9f
        val innerRadius = outerRadius * 0.5f

        // Glow
        drawCircle(
            color = neonColor.copy(alpha = 0.15f * pulse),
            radius = outerRadius * 1.25f
        )
        // Cyber hexagonal shell boundary ring
        drawCircle(
            color = neonColor,
            radius = outerRadius,
            style = Stroke(width = 2.dp.toPx())
        )
        // Center media playing cyber triangle
        val path = Path().apply {
            moveTo(center.x - 3.dp.toPx(), center.y - 7.dp.toPx())
            lineTo(center.x + 8.dp.toPx(), center.y)
            lineTo(center.x - 3.dp.toPx(), center.y + 7.dp.toPx())
            close()
        }
        drawPath(
            path = path,
            color = neonColor
        )
        // Glowing surrounding orbits
        for (i in 0 until 4) {
            val offsetRad = outerRadius + 3.dp.toPx()
            val angle = 2f * Math.PI.toFloat() * (i / 4f)
            drawCircle(
                color = neonColor,
                radius = 2.dp.toPx(),
                center = Offset(center.x + offsetRad * sin(angle), center.y + offsetRad * sin(angle))
            )
        }
    }
}

// Real-time Neon Frequencies Visualizer module
@Composable
fun AnalyzerModule(
    spectrum: FloatArray,
    vuL: Float,
    vuR: Float,
    peakL: Float,
    peakR: Float,
    neonColor: Color
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0x0AFFFFFF),
        border = BorderStroke(1.dp, Color(0x14FFFFFF)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Panel Header Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REALTIME FFT SPECTRUM & VU MODULE",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
                Text(
                    text = "44.1kHz / 32 BINS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = neonColor
                )
            }

            // Real Canvas displaying the sound spectrum and Stereo volume indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Split-Stereo Dual VU meter indicators L and R separate
                Column(
                    modifier = Modifier
                        .width(44.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Left meter
                            VuBarVertical(level = vuL, peak = peakL, meterLabel = "L", neonColor = neonColor)
                            // Right meter
                            VuBarVertical(level = vuR, peak = peakR, meterLabel = "R", neonColor = neonColor)
                        }
                    }
                }

                // Main Spectrum visual representation running at 60 FPS
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF02060C), RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, Color(0x08FFFFFF)), RoundedCornerShape(12.dp))
                        .padding(8.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val barSpacing = 4.dp.toPx()
                        val numBars = spectrum.size
                        val barWidth = (size.width - (barSpacing * (numBars - 1))) / numBars

                        // Draw frequency bands columns
                        for (i in 0 until numBars) {
                            val magnitude = spectrum[i].coerceIn(0.01f, 1f)
                            val barHeight = magnitude * size.height
                            val x = i * (barWidth + barSpacing)
                            val y = size.height - barHeight

                            // Gradient block color (themed)
                            val brush = Brush.verticalGradient(
                                colors = listOf(
                                    neonColor,
                                    neonColor.copy(alpha = 0.35f),
                                    Color.Transparent
                                )
                            )

                            drawRoundRect(
                                brush = brush,
                                topLeft = Offset(x, y),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                            )

                            // Glowing subtle reflection bar
                            drawRect(
                                color = neonColor.copy(alpha = 0.04f),
                                topLeft = Offset(x, 0f),
                                size = Size(barWidth, size.height)
                            )
                        }
                    }
                }

                // VU Meters & Core Stats Column from professional theme HTML
                val dynamicDb = remember(vuL, vuR) {
                    val maxVu = maxOf(vuL, vuR).coerceAtLeast(0.01f)
                    val db = -abs(20f * kotlin.math.log10(maxVu))
                    if (db > 0.0f) 0.0f else if (db < -60f) -60f else db
                }
                Column(
                    modifier = Modifier
                        .width(96.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF02060C), RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, Color(0x08FFFFFF)), RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "OUTPUT PEAK",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.sp,
                            color = neonColor,
                            letterSpacing = 0.5.sp
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            val dbStr = if (maxOf(vuL, vuR) > 0.015f) String.format("%.1f", dynamicDb) else "-INF"
                            Text(
                                text = dbStr,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                            if (dbStr != "-INF") {
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "dB",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "CORE LATENCY",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.sp,
                            color = neonColor,
                            letterSpacing = 0.2.sp
                        )
                        Text(
                            text = "1.2 ms",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color.White
                        )
                        Text(
                            text = "ULTRALOW",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 7.sp,
                            color = Color(0xFF00E676)
                        )
                    }
                }
            }
        }
    }
}

// Vertical VU bar Canvas rendering
@Composable
fun RowScope.VuBarVertical(
    level: Float,
    peak: Float,
    meterLabel: String,
    neonColor: Color
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF010408))
                .border(BorderStroke(1.dp, Color(0x10FFFFFF)), RoundedCornerShape(4.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val numSegments = 16
                val spacing = 2.dp.toPx()
                val segmentHeight = (size.height - (spacing * (numSegments - 1))) / numSegments

                // Determine active seg limits based on volume standard
                val valLimit = level * numSegments
                val peakInt = (peak * numSegments).toInt().coerceIn(0, numSegments - 1)

                for (s in 0 until numSegments) {
                    val isActive = s < valLimit
                    val isPeak = s == peakInt
                    val segmentY = size.height - (s + 1) * (segmentHeight + spacing)

                    val segColor = when {
                        s >= 13 -> if (isActive) Color(0xFFFF1E46) else Color(0x20FF1E46) // Clip zone
                        s >= 10 -> if (isActive) Color(0xFFFFBF00) else Color(0x20FFBF00) // Caution high
                        else -> if (isActive) neonColor else neonColor.copy(alpha = 0.15f) // Safe zone
                    }

                    drawRect(
                        color = if (isPeak && !isActive) Color.White else segColor,
                        topLeft = Offset(0f, segmentY),
                        size = Size(size.width, segmentHeight)
                    )
                }
            }
        }
        Text(
            text = meterLabel,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = Color.LightGray
        )
    }
}

// 7/10/15/18/31 Selector Console
@Composable
fun EqualizerConsole(
    settings: AppSettingsEntity,
    currentGains: List<Float>,
    customPresets: List<PresetEntity>,
    neonColor: Color,
    onBandCountSelected: (Int) -> Unit,
    onBandGainUpdated: (Int, Float) -> Unit,
    onPresetSelected: (String) -> Unit,
    onCustomPresetSelected: (PresetEntity) -> Unit,
    onSavePresetClicked: () -> Unit,
    onDeleteCustomPreset: (Int) -> Unit
) {
    val bandsOptions = listOf(7, 10, 15, 18, 31)

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0x0AFFFFFF),
        border = BorderStroke(1.dp, Color(0x14FFFFFF)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header text & Action items
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "EQ CONSOLE MATRIX",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                    fontSize = 13.sp
                )
                // Export/save preset trigger button
                IconButton(
                    onClick = onSavePresetClicked,
                    modifier = Modifier.size(32.dp)
                ) {
                    SaveIcon(color = neonColor)
                }
            }

            // A. Band mode select options (7, 10, 15, 18, 31 count chips row)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PARAMETRIC RESOLUTION",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.Gray
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bandsOptions.forEach { bCount ->
                        val active = settings.currentBandCount == bCount
                        Box(
                            modifier = Modifier
                                .background(
                                    if (active) neonColor else Color(0x10FFFFFF),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onBandCountSelected(bCount) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "$bCount BANDS",
                                color = if (active) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // B. Row of Standard Preset Selectors & Custom Preset Selectors
            PresetSelectorRow(
                settings = settings,
                customPresets = customPresets,
                neonColor = neonColor,
                onPresetSelected = onPresetSelected,
                onCustomPresetSelected = onCustomPresetSelected,
                onDeletePreset = onDeleteCustomPreset
            )

            // C. EQ Consoles sliders container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF02050A), RoundedCornerShape(16.dp))
                    .border(BorderStroke(1.dp, Color(0x05FFFFFF)), RoundedCornerShape(16.dp))
                    .padding(vertical = 12.dp, horizontal = 12.dp)
            ) {
                // Dynamic Equalizer slider tracks scrollable horizontally
                val scrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    currentGains.forEachIndexed { index, gain ->
                        EqSliderColumn(
                            index = index,
                            totalBands = settings.currentBandCount,
                            gain = gain,
                            neonColor = neonColor,
                            onGainUpdated = { onBandGainUpdated(index, it) }
                        )
                    }
                }

                // If scrolled too far right visual scrollbar indicator
                if (currentGains.size > 10) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.6f)
                            .height(2.dp)
                            .background(Color(0x10FFFFFF))
                    )
                }
            }
        }
    }
}

// Individual EQ Console Fader column design
@Composable
fun EqSliderColumn(
    index: Int,
    totalBands: Int,
    gain: Float,
    neonColor: Color,
    onGainUpdated: (Float) -> Unit
) {
    // Determine dynamic freq labels based on index
    val freqLabel = remember(index, totalBands) {
        val frequencies = when (totalBands) {
            7 -> listOf("60Hz", "150Hz", "400Hz", "1kHz", "2.5kHz", "6kHz", "15kHz")
            10 -> listOf("31Hz", "62Hz", "125z", "250z", "500z", "1kHz", "2kHz", "4kHz", "8kHz", "16k")
            15 -> listOf("25Hz", "40Hz", "63Hz", "100Hz", "160Hz", "250Hz", "400Hz", "630Hz", "1kHz", "1.6k", "2.5k", "4kHz", "6.3k", "10k", "16k")
            18 -> listOf("20Hz", "35Hz", "55Hz", "90Hz", "140Hz", "220Hz", "350Hz", "560Hz", "900Hz", "1.4k", "2.2k", "3.5k", "5.6k", "9kHz", "11k", "14k", "17k", "21k")
            31 -> listOf(
                "20", "25", "31", "40", "50", "63", "80", "100", "125", "160", "200", "250", "315", "400", "500",
                "630", "800", "1k", "1.2k", "1.6k", "2k", "2.5k", "3.1k", "4k", "5k", "6.3k", "8k", "10k", "12k", "16k", "20k"
            )
            else -> listOf("EQ")
        }
        if (index < frequencies.size) frequencies[index] else "EQ"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(52.dp)
            .height(260.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Fader Gain DB value
        Text(
            text = String.format("%+.1f", gain),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (gain != 0f) neonColor else Color.Gray
        )

        // Physical vertical slider space using Slider component
        Box(
            modifier = Modifier
                .wrapContentHeight()
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Background line fader channels
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.85f)
                    .width(1.4.dp)
                    .background(Color(0x15FFFFFF)),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // subtle grid notches
                repeat(9) {
                    Box(modifier = Modifier
                        .size(4.dp, 1.dp)
                        .background(Color(0x35FFFFFF)))
                }
            }

            // Material slider rotated vertical
            Slider(
                value = gain,
                onValueChange = onGainUpdated,
                valueRange = -12f..12f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = neonColor,
                    inactiveTrackColor = Color.Transparent
                ),
                modifier = Modifier
                    .graphicsLayer {
                        rotationZ = 270f
                    }
                    .width(170.dp)
                    .testTag("eq_slider_${index}")
            )
        }

        // Notch position label (Central divider value)
        Text(
            text = freqLabel,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
            maxLines = 1
        )
    }
}

// Slider Row presets management
@Composable
fun PresetSelectorRow(
    settings: AppSettingsEntity,
    customPresets: List<PresetEntity>,
    neonColor: Color,
    onPresetSelected: (String) -> Unit,
    onCustomPresetSelected: (PresetEntity) -> Unit,
    onDeletePreset: (Int) -> Unit
) {
    val presets = listOf("Flat", "Bass Boost", "Vocal", "EDM", "Rock", "Jazz", "Dangdut", "Koplo", "DJ Club", "Cinema", "Gaming", "Podcast")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "FACTORY CHANNELS & CUSTOM MASTERS",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = Color.Gray
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Default engineering profiles
            items(presets) { pName ->
                // Custom modified values trigger ID 9999 so default profiles won't highlight then
                val selected = settings.currentPresetId <= 0 && settings.presetName == pName && settings.currentPresetId != 9999
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) neonColor.copy(alpha = 0.15f) else Color(0x08FFFFFF),
                    border = BorderStroke(
                        1.dp,
                        if (selected) neonColor else Color(0x10FFFFFF)
                    ),
                    modifier = Modifier.clickable { onPresetSelected(pName) }
                ) {
                    Text(
                        text = pName.uppercase(),
                        color = if (selected) neonColor else Color.LightGray,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // User customized storage presets loaded from Room database
            items(customPresets) { cPreset ->
                val selected = settings.currentPresetId == cPreset.id
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) neonColor.copy(alpha = 0.15f) else Color(0x06FFFFFF),
                    border = BorderStroke(
                        1.dp,
                        if (selected) neonColor else Color(0x08FFFFFF)
                    ),
                    modifier = Modifier.clickable { onCustomPresetSelected(cPreset) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = cPreset.name.uppercase(),
                            color = if (selected) neonColor else Color.LightGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Delete preset",
                            tint = Color.Gray.copy(alpha = 0.5f),
                            modifier = Modifier
                                .size(12.dp)
                                .clickable { onDeletePreset(cPreset.id) }
                        )
                    }
                }
            }
        }
    }
}

// 4. DAW FX Switching options Tabs
enum class RackTab {
    DYNAMIC_FX,
    AMPLIFY_WIDE,
    STUDIO_MIX,
    DAW_DYNAMICS
}

@Composable
fun FxRackSwitcher(
    activeTab: RackTab,
    onTabSelected: (RackTab) -> Unit,
    neonColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF020409), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val options = listOf(
            RackTab.DYNAMIC_FX to "COLOR EQ",
            RackTab.AMPLIFY_WIDE to "AMPLIFY x SPATIAL",
            RackTab.STUDIO_MIX to "REVERB FX",
            RackTab.DAW_DYNAMICS to "COMPRESSOR DE-ESS"
        )

        options.forEach { (tab, title) ->
            val active = activeTab == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (active) neonColor else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (active) Color.Black else Color.Gray,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

// FX rack options A: Colorizer sliders
@Composable
fun DynamicFxPanel(
    settings: AppSettingsEntity,
    viewModel: AudioViewModel,
    neonColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RackModuleHeader("PREAMP COLORIZATION STAGE", neonColor)

        Text(
            text = "Tube harmonic warmth modeling & frequency coloration filters.",
            fontSize = 11.sp,
            color = Color.Gray
        )

        GridSliderItem(
            label = "BASS BOOST LEVEL",
            value = settings.bassBoost,
            range = 0f..100f,
            valueString = "${settings.bassBoost.toInt()}%",
            neonColor = neonColor,
            onValueChange = { viewModel.updateBassBoost(it) }
        )

        GridSliderItem(
            label = "VOCAL CLARITY EXCITOR",
            value = settings.vocalClarity,
            range = 0f..100f,
            valueString = "${settings.vocalClarity.toInt()}%",
            neonColor = neonColor,
            onValueChange = { viewModel.updateVocalClarity(it) }
        )

        GridSliderItem(
            label = "TUBE WARMTH SATURATION",
            value = settings.tubeWarmth,
            range = 0f..100f,
            valueString = "${settings.tubeWarmth.toInt()}%",
            neonColor = neonColor,
            onValueChange = { viewModel.updateTubeWarmth(it) }
        )

        GridSliderItem(
            label = "ANALOG TRANSISTOR SATURATION",
            value = settings.analogSaturation,
            range = 0f..100f,
            valueString = "${settings.analogSaturation.toInt()}%",
            neonColor = neonColor,
            onValueChange = { viewModel.updateAnalogSaturation(it) }
        )
    }
}

// FX rack B: Amplification & spatial widener sliders
@Composable
fun AmplificationPanel(
    settings: AppSettingsEntity,
    viewModel: AudioViewModel,
    neonColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RackModuleHeader("DYNAMIC SOUND EXPANSER STAGE", neonColor)

        Text(
            text = "Stereo room widening, crosstalk matrices, and Haas psychoacoustic panning.",
            fontSize = 11.sp,
            color = Color.Gray
        )

        GridSliderItem(
            label = "STEREO HAAS WIDENER",
            value = settings.stereoWidener,
            range = 0f..100f,
            valueString = "${settings.stereoWidener.toInt()}%",
            neonColor = neonColor,
            onValueChange = { viewModel.updateStereoWidener(it) }
        )

        GridSliderItem(
            label = "L&R EXTREME EXPANSION (CROSSTALK)",
            value = settings.extremeStereo,
            range = 0f..100f,
            valueString = "${settings.extremeStereo.toInt()}%",
            neonColor = neonColor,
            onValueChange = { viewModel.updateExtremeStereo(it) }
        )

        GridSliderItem(
            label = "3D MASTER SPATIALIZATION",
            value = settings.threeDAudio,
            range = 0f..100f,
            valueString = "${settings.threeDAudio.toInt()}%",
            neonColor = neonColor,
            onValueChange = { viewModel.updateThreeDAudio(it) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "ANDROID SPATIALIZER DRIVER", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(text = "Unlocks spatial profiles on Bluetooth headset drivers", color = Color.Gray, fontSize = 10.sp)
            }
            Switch(
                checked = settings.spatialAudio,
                onCheckedChange = { viewModel.updateSpatialAudio(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = neonColor,
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color(0x15FFFFFF)
                )
            )
        }
    }
}

// FX rack C: Delay / Reverb Comb sliders
@Composable
fun StudioMixPanel(
    settings: AppSettingsEntity,
    viewModel: AudioViewModel,
    neonColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RackModuleHeader("ACOUSTICAL MASTER REVERBERATION", neonColor)

        Text(
            text = "Digital feedback delay pipelines & multi-tap ambient reflections.",
            fontSize = 11.sp,
            color = Color.Gray
        )

        GridSliderItem(
            label = "PLATE REVERB DENSITY MIX",
            value = settings.reverbLevel,
            range = 0f..100f,
            valueString = "${settings.reverbLevel.toInt()}%",
            neonColor = neonColor,
            onValueChange = { viewModel.updateReverbLevel(it) }
        )

        GridSliderItem(
            label = "DIGITAL DELAY TIME FEED",
            value = settings.delayMs,
            range = 50f..800f,
            valueString = "${settings.delayMs.toInt()}ms",
            neonColor = neonColor,
            onValueChange = { viewModel.updateDelayMs(it) }
        )

        GridSliderItem(
            label = "DELAY REGENERATIVE FEEDBACK",
            value = settings.delayFeedback,
            range = 0f..100f,
            valueString = "${settings.delayFeedback.toInt()}%",
            neonColor = neonColor,
            onValueChange = { viewModel.updateDelayFeedback(it) }
        )
    }
}

// FX rack D: Dynamics Compressor panel sliders
@Composable
fun DynamicsCompressorPanel(
    settings: AppSettingsEntity,
    viewModel: AudioViewModel,
    neonColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RackModuleHeader("STUDIO MASTER DYNAMICS COMPRESSION", neonColor)

        Text(
            text = "Knee compression thresholds, subwoofer crossover, and noise gated threshold values.",
            fontSize = 11.sp,
            color = Color.Gray
        )

        GridSliderItem(
            label = "COMPRESSOR THRESHOLD LEVEL",
            value = settings.compressorThreshold,
            range = -48f..0f,
            valueString = String.format("%.1fdB", settings.compressorThreshold),
            neonColor = neonColor,
            onValueChange = { viewModel.updateCompressorThreshold(it) }
        )

        GridSliderItem(
            label = "COMPRESSION SLOPE RATIO",
            value = settings.compressorRatio,
            range = 1f..12f,
            valueString = String.format("1:%.1f", settings.compressorRatio),
            neonColor = neonColor,
            onValueChange = { viewModel.updateCompressorRatio(it) }
        )

        GridSliderItem(
            label = "SUB crossover FREQUENCY",
            value = settings.crossoverFreq,
            range = 50f..220f,
            valueString = "${settings.crossoverFreq.toInt()}Hz",
            neonColor = neonColor,
            onValueChange = { viewModel.updateCrossoverFreq(it) }
        )

        GridSliderItem(
            label = "EXPRESS LOUDNESS MAXIMIZER",
            value = settings.loudnessMaximizer,
            range = 0f..100f,
            valueString = "${settings.loudnessMaximizer.toInt()}%",
            neonColor = neonColor,
            onValueChange = { viewModel.updateLoudnessMaximizer(it) }
        )

        GridSliderItem(
            label = "SILENCE NOISE REDUCTION GATE",
            value = settings.noiseReduction,
            range = 0f..100f,
            valueString = "${settings.noiseReduction.toInt()}%",
            neonColor = neonColor,
            onValueChange = { viewModel.updateNoiseReduction(it) }
        )
    }
}

// Custom title container for racks
@Composable
fun RackModuleHeader(title: String, neonColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(6.dp, 12.dp)
                .background(neonColor)
        )
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color.White,
            letterSpacing = 1.sp
        )
    }
}

// Customized fader element layout
@Composable
fun GridSliderItem(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueString: String,
    neonColor: Color,
    onValueChange: (Float) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.LightGray
            )
            Text(
                text = valueString,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = neonColor
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = neonColor,
                inactiveTrackColor = Color(0x15FFFFFF)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("rack_slider_${label.replace(" ", "_")}")
        )
    }
}

// Dynamic Theme Color Finder
fun getThemeColor(skinName: String): Color {
    return when (skinName) {
        "Neon Cyan" -> Color(0xFF00F2FF)
        "Neon Blue" -> Color(0xFF2979FF)
        "Neon Red" -> Color(0xFFFF1744)
        "Neon Purple" -> Color(0xFFD500F9)
        "Neon Green" -> Color(0xFF00E676)
        "Gold" -> Color(0xFFFFD600)
        else -> Color(0xFF00F2FF)
    }
}

// Custom Programmatic Vector Icons for 100% Core Compile Stability
@Composable
fun PowerIcon(neonColor: Color, isOn: Boolean) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val r = size.width * 0.32f
        drawArc(
            color = if (isOn) neonColor else Color(0x35FFFFFF),
            startAngle = -240f,
            sweepAngle = 300f,
            useCenter = false,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
        drawLine(
            color = if (isOn) neonColor else Color(0x35FFFFFF),
            start = Offset(center.x, center.y - r * 1.2f),
            end = Offset(center.x, center.y + r * 0.1f),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun PlayIcon(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.35f, h * 0.2f)
            lineTo(w * 0.8f, h * 0.5f)
            lineTo(w * 0.35f, h * 0.8f)
            close()
        }
        drawPath(path = path, color = color)
    }
}

@Composable
fun PauseIcon(color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val barW = 3.dp.toPx()
        val barH = h * 0.7f
        val gap = 3.dp.toPx()
        val center = w / 2f
        val startY = (h - barH) / 2f
        drawRect(
            color = color,
            topLeft = Offset(center - gap - barW, startY),
            size = Size(barW, barH)
        )
        drawRect(
            color = color,
            topLeft = Offset(center + gap, startY),
            size = Size(barW, barH)
        )
    }
}

@Composable
fun SaveIcon(color: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.2f, h * 0.2f)
            lineTo(w * 0.7f, h * 0.2f)
            lineTo(w * 0.8f, h * 0.3f)
            lineTo(w * 0.8f, h * 0.8f)
            lineTo(w * 0.2f, h * 0.8f)
            close()
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.8.dp.toPx())
        )
        drawRect(
            color = color,
            topLeft = Offset(w * 0.35f, w * 0.2f),
            size = Size(w * 0.3f, h * 0.2f)
        )
        drawRect(
            color = color,
            topLeft = Offset(w * 0.35f, h * 0.55f),
            size = Size(w * 0.3f, h * 0.25f)
        )
    }
}
