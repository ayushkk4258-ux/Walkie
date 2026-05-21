package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.engine.WalkieTalkieEngine
import com.example.engine.PeerDevice
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.VoxAccent
import com.example.ui.theme.VoxBorder
import com.example.ui.theme.VoxDarkBg
import com.example.ui.theme.VoxGreen
import com.example.ui.theme.VoxGrey
import com.example.ui.theme.VoxRed
import com.example.ui.theme.VoxSubText
import com.example.ui.theme.VoxSurface
import com.example.ui.theme.VoxText

class MainActivity : ComponentActivity() {
    private var engine: WalkieTalkieEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        engine = WalkieTalkieEngine(applicationContext)

        setContent {
            MyApplicationTheme {
                MainScreen(engine = engine!!)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.dispose()
    }
}

@Composable
fun MainScreen(engine: WalkieTalkieEngine) {
    val context = LocalContext.current
    
    // Check & Manage Mic Permissions
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            engine.playChirpBeep(1000, 150)
        }
    }

    // Engine Flow States
    val isRecording by engine.isRecording.collectAsState()
    val isPlaying by engine.isPlaying.collectAsState()
    val peers by engine.peers.collectAsState()
    val channel by engine.channel.collectAsState()
    val volume by engine.volume.collectAsState()
    val statusMessage by engine.statusMessage.collectAsState()
    val wifiDirectActive by engine.wifiDirectActive.collectAsState()
    val btMeshActive by engine.btMeshActive.collectAsState()
    val loopbackEnabled by engine.loopbackEnabled.collectAsState()
    val audioLevel by engine.audioLevel.collectAsState()
    val activeIncomingPeer by engine.activeIncomingPeer.collectAsState()

    // Immersive bottom navigation state: "RADIO" | "EQUIP" | "CONFIG"
    var currentTab by remember { mutableStateOf("RADIO") }
    
    // Slider popups for user controls
    var showVolumeSlider by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VoxDarkBg)
    ) {
        // Status & Navigation safe spaces
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            
            // 1. TOP STATUS BAR (Wifi & BT labels to match tailwind style)
            TopStatusBar(
                wifiActive = wifiDirectActive,
                btActive = btMeshActive,
                loopbackEnabled = loopbackEnabled,
                onToggleLoopback = { engine.toggleLoopback() }
            )

            // 2. HEADER BAR
            HeaderBar(
                channel = channel,
                statusMessage = statusMessage,
                isRecording = isRecording
            )

            // Main Content Area based on Selected Bottom Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (currentTab) {
                    "RADIO" -> {
                        RadioConsoleView(
                            hasMicPermission = hasMicPermission,
                            onRequestPermission = {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            isRecording = isRecording,
                            isPlaying = isPlaying,
                            audioLevel = audioLevel,
                            activeIncomingPeer = activeIncomingPeer,
                            statusMessage = statusMessage,
                            peers = peers,
                            channel = channel,
                            volume = volume,
                            showVolumeSlider = showVolumeSlider,
                            onToggleVolumeSlider = { showVolumeSlider = !showVolumeSlider },
                            onVolumeChange = { engine.adjustVolume(it - volume) },
                            onIncrementChannel = { engine.setChannel(channel + 1) },
                            onDecrementChannel = { engine.setChannel(channel - 1) },
                            onPingPeer = { peer -> engine.triggerVirtualPing(peer) },
                            engine = engine
                        )
                    }
                    "EQUIP" -> {
                        EquipStatusView(
                            peers = peers,
                            onPingPeer = { engine.triggerVirtualPing(it) }
                        )
                    }
                    "CONFIG" -> {
                        ConfigSettingsView(
                            loopbackEnabled = loopbackEnabled,
                            onToggleLoopback = { engine.toggleLoopback() },
                            volume = volume,
                            onVolumeChanged = { engine.adjustVolume(it - volume) },
                            channel = channel,
                            onChannelChanged = { engine.setChannel(it) },
                            engine = engine
                        )
                    }
                }
            }

            // 4. BOTTOM TABS NAVIGATION
            TabsNavigationBar(
                currentTab = currentTab,
                onTabSelected = { currentTab = it }
            )
        }
    }
}

@Composable
fun TopStatusBar(
    wifiActive: Boolean,
    btActive: Boolean,
    loopbackEnabled: Boolean,
    onToggleLoopback: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left item
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (wifiActive) VoxAccent else VoxGrey)
            )
            Text(
                text = "WIFI-DIRECT ACTIVE",
                color = if (wifiActive) VoxAccent else VoxGrey,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        // Center item (Loopback mode toggle badge for demo)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (loopbackEnabled) VoxAccent.copy(alpha = 0.15f) else Color.Transparent)
                .clickable { onToggleLoopback() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .border(1.dp, if (loopbackEnabled) VoxAccent.copy(alpha = 0.3f) else VoxBorder, RoundedCornerShape(4.dp))
        ) {
            Text(
                text = if (loopbackEnabled) "ECHO LOOP: ON" else "ECHO: OFF",
                color = if (loopbackEnabled) VoxAccent else VoxSubText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
        }

        // Right item
        Text(
            text = "BT-MESH: ${if (btActive) "ON" else "OFF"}",
            color = if (btActive) VoxAccent.copy(alpha = 0.8f) else VoxGrey,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun HeaderBar(
    channel: Int,
    statusMessage: String,
    isRecording: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildString {
                    append("VOX")
                },
                color = VoxText,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-0.5).sp
            )
            // Accent highlight for "7" as requested in Immersive mockup
            Box(
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Text(
                    text = "7",
                    color = VoxAccent,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
        Text(
            text = "Channel: 0$channel - Tactical Hub",
            color = VoxSubText,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun RadioConsoleView(
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    isRecording: Boolean,
    isPlaying: Boolean,
    audioLevel: Float,
    activeIncomingPeer: String?,
    statusMessage: String,
    peers: List<PeerDevice>,
    channel: Int,
    volume: Float,
    showVolumeSlider: Boolean,
    onToggleVolumeSlider: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onIncrementChannel: () -> Unit,
    onDecrementChannel: () -> Unit,
    onPingPeer: (PeerDevice) -> Unit,
    engine: WalkieTalkieEngine
) {
    val context = LocalContext.current
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        
        // 1. EQUALIZER / WAVEFORM BARS DANCING VISUALLY
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom
        ) {
            // Render 9 responsive sound wave bars to match tailwind style
            val heights = listOf(16, 24, 40, 32, 48, 24, 16, 32, 20)
            heights.forEachIndexed { idx, baseHt ->
                val waveScale = if (isRecording || isPlaying) {
                    audioLevel + (Math.sin((System.currentTimeMillis() / 80.0) + idx).toFloat() * 0.15f)
                } else {
                    0.0f
                }
                val scale by animateFloatAsState(
                    targetValue = waveScale.coerceIn(0.12f, 1.0f),
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 120f)
                )
                
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .width(6.dp)
                        .height((baseHt * scale).dp)
                        .background(
                            color = if (isRecording) VoxRed.copy(alpha = 0.3f + 0.7f * scale)
                            else if (isPlaying) VoxAccent.copy(alpha = 0.3f + 0.7f * scale)
                            else VoxAccent.copy(alpha = 0.15f),
                            shape = CircleShape
                        )
                )
            }
        }

        // 2. GIANT PUSH TO TALK (PTT) BUTTON WITH RADIAL TRANSLUCENCE
        Box(
            modifier = Modifier
                .size(240.dp)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            // Blurry pulse overlay for gorgeous modern tactical look
            val glowAnimScale by animateFloatAsState(
                targetValue = if (isRecording) 1.25f else 1.0f,
                animationSpec = spring(stiffness = 80f)
            )
            
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .scale(glowAnimScale)
                    .blur(if (isRecording) 24.dp else 4.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = if (isRecording) listOf(VoxRed.copy(alpha = 0.25f), Color.Transparent)
                            else listOf(VoxAccent.copy(alpha = 0.06f), Color.Transparent)
                        )
                    )
            )

            // Outer button cylinder
            val pttSize = 200.dp
            val buttonColor by animateColorAsState(
                targetValue = if (isRecording) VoxRed.copy(alpha = 0.08f) else VoxSurface,
                animationSpec = spring(stiffness = 180f)
            )
            val borderColor by animateColorAsState(
                targetValue = if (isRecording) VoxRed else if (isPlaying) VoxAccent else VoxBorder,
                animationSpec = spring(stiffness = 150f)
            )

            Box(
                modifier = Modifier
                    .size(pttSize)
                    .testTag("push_to_talk_button")
                    .clip(CircleShape)
                    .background(buttonColor)
                    .border(4.dp, borderColor, CircleShape)
                    .pointerInput(hasMicPermission) {
                        if (!hasMicPermission) {
                            detectTapGestures(onTap = { onRequestPermission() })
                        } else {
                            detectTapGestures(
                                onPress = {
                                    try {
                                        // Vibrate on trigger for professional tactile radio feedback
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(45, 120))
                                        } else {
                                            vibrator?.vibrate(45)
                                        }
                                        engine.startBroadcasting()
                                        tryAwaitRelease()
                                    } finally {
                                        engine.stopBroadcasting()
                                    }
                                }
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(12.dp)
                ) {
                    // Microphone Vector drawing to avoid loading heavy drawables
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_btn_speak_now),
                        contentDescription = "Microphone icon",
                        tint = if (isRecording) VoxRed else if (isPlaying) VoxAccent else VoxSubText.copy(alpha = 0.8f),
                        modifier = Modifier
                            .size(48.dp)
                            .scale(if (isRecording) 1.15f else 1.0f)
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Text(
                        text = if (!hasMicPermission) "GRANT MIC" else "PUSH TO TALK",
                        color = if (isRecording) VoxText else if (isPlaying) VoxAccent else VoxText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        textAlign = TextAlign.Center
                    )

                    // Wave transition active indicator
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(VoxBorder)
                    ) {
                        val activeWidth by animateFloatAsState(
                            targetValue = if (isRecording) 1.0f else 0.0f,
                            animationSpec = spring(stiffness = 60f)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(activeWidth)
                                .height(3.dp)
                                .background(if (isRecording) VoxRed else VoxAccent)
                        )
                    }
                }
            }

            // Absolute badges for listening state feedback
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        1.dp,
                        if (isRecording) VoxRed.copy(alpha = 0.5f) else if (isPlaying) VoxAccent.copy(alpha = 0.5f) else VoxBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .background(VoxDarkBg)
                    .padding(horizontal = 14.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (isRecording) "TRANSMITTING..." else if (isPlaying) "RECEIVING..." else "STANDBY READY",
                    color = if (isRecording) VoxRed else if (isPlaying) VoxAccent else VoxSubText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }
        }

        // 3. INCOMING VOICE ALERTS
        AnimatedVisibility(
            visible = isPlaying && activeIncomingPeer != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(VoxAccent.copy(alpha = 0.08f))
                    .border(1.dp, VoxAccent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(VoxAccent)
                    )
                    Text(
                        text = "Incoming Voice: $activeIncomingPeer",
                        color = VoxAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 4. AUTO-DISCOVERY PANEL
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(VoxSurface)
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "AUTO-DISCOVERY",
                        color = VoxSubText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(VoxAccent)
                    )
                }
                Text(
                    text = "SEARCHING...",
                    color = VoxAccent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(VoxAccent.copy(alpha = 0.1f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Peers Row Item List
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 12.dp)
            ) {
                items(peers) { peer ->
                    PeerAvatarItem(peer = peer, onPing = { onPingPeer(peer) })
                }
            }
        }

        // 5. QUICK PANEL ACTIONS (VOLUME TRIGGER AND CHANNELS SLIDES)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Volume trigger button
            Button(
                onClick = onToggleVolumeSlider,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoxBorder,
                    contentColor = VoxText
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_lock_silent_mode_off),
                    contentDescription = "Volume control icon",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "VOL: ${(volume * 100).toInt()}%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Channel selection trigger
            Button(
                onClick = onIncrementChannel,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoxAccent,
                    contentColor = VoxDarkBg
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_input_add),
                    contentDescription = "Increase channel icon",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "CHANNEL 0$channel",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // Persistent Volume Slider pop-out overlay
        AnimatedVisibility(
            visible = showVolumeSlider,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut()
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = VoxSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .border(1.dp, VoxBorder, RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SQUELCH VOLUME / AUDIO LEVEL", color = VoxSubText, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        Text("${(volume * 100).toInt()}%", color = VoxAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = volume,
                        onValueChange = onVolumeChange,
                        colors = SliderDefaults.colors(
                            thumbColor = VoxAccent,
                            activeTrackColor = VoxAccent,
                            inactiveTrackColor = VoxBorder
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun PeerAvatarItem(peer: PeerDevice, onPing: () -> Unit) {
    val initials = if (peer.name.length >= 2) {
        peer.name.take(2).uppercase()
    } else {
        peer.name.uppercase()
    }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clickable { onPing() }
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(VoxBorder)
                .border(
                    1.dp,
                    if (peer.isVirtual) VoxAccent.copy(alpha = 0.4f) else VoxAccent,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = VoxText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = peer.name,
            color = VoxSubText,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun EquipStatusView(
    peers: List<PeerDevice>,
    onPingPeer: (PeerDevice) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "EQUIPMENT LOGS",
            color = VoxAccent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = "Diagnostic status and connection mesh analysis",
            color = VoxSubText,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                DiagnosticCard(
                    title = "AUDIO ENGINE",
                    status = "ONLINE PCM 16-BIT",
                    metric = "16.0 kHz MONO"
                )
            }
            item {
                DiagnosticCard(
                    title = "RADIO FREQUENCY LINK",
                    status = "NSD BROADCAST ENABLED",
                    metric = "Local subnet UDP:50005"
                )
            }
            item {
                DiagnosticCard(
                    title = "LATENCY SQUELCH",
                    status = "HEALTHY",
                    metric = "< 15 ms average RTT"
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "SUB-NET ROUTING INDEX (${peers.size})",
                    color = VoxSubText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            items(peers) { peer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VoxSurface)
                        .clickable { onPingPeer(peer) }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(peer.name, color = VoxText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(peer.ipAddress, color = VoxSubText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (peer.isVirtual) VoxBorder else VoxGreen.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (peer.isVirtual) "SIMULATED P2P" else "TEST DIRECT",
                            color = if (peer.isVirtual) VoxSubText else VoxGreen,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticCard(
    title: String,
    status: String,
    metric: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = VoxSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, color = VoxSubText, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(2.dp))
                Text(status, color = VoxText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(metric, color = VoxAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun ConfigSettingsView(
    loopbackEnabled: Boolean,
    onToggleLoopback: () -> Unit,
    volume: Float,
    onVolumeChanged: (Float) -> Unit,
    channel: Int,
    onChannelChanged: (Int) -> Unit,
    engine: WalkieTalkieEngine
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "RADIO SETUP",
            color = VoxAccent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = "Configure voice links and local environment parameters",
            color = VoxSubText,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VoxSurface)
                        .padding(14.dp)
                ) {
                    Text("ACTIVE SIMULATION BRIDGE", color = VoxAccent, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text(
                        "Enables an acoustic loopback mirror and tactical peers for testing direct broadcasts inside single-emulator sandboxes.",
                        color = VoxSubText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Single Device Loopback Test", color = VoxText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Button(
                            onClick = onToggleLoopback,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (loopbackEnabled) VoxAccent else VoxBorder,
                                contentColor = if (loopbackEnabled) VoxDarkBg else VoxText
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (loopbackEnabled) "ENABLED" else "DISABLED", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VoxSurface)
                        .padding(14.dp)
                ) {
                    Text("SQUELCH THRESHOLD", color = VoxSubText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Slider(
                        value = volume,
                        onValueChange = onVolumeChanged,
                        colors = SliderDefaults.colors(
                            thumbColor = VoxAccent,
                            activeTrackColor = VoxAccent,
                            inactiveTrackColor = VoxBorder
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Quiet (0%)", color = VoxSubText, fontSize = 11.sp)
                        Text("Loud (100%)", color = VoxSubText, fontSize = 11.sp)
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VoxSurface)
                        .clickable { onChannelChanged(channel + 1) }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("PREPOSITIONS FREQUENCY CHANNEL", color = VoxSubText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Subnet frequency offset ID", color = VoxText, fontSize = 13.sp, fontWeight = FontWeight.Normal)
                    }
                    Text("CH 0$channel", color = VoxAccent, fontSize = 14.sp, fontWeight = FontWeight.Black)
                }
            }

            item {
                Button(
                    onClick = { engine.playChirpBeep(1200, 300) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoxBorder,
                        contentColor = VoxText
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("TEST CHIRP RECEIVER BEEP", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TabsNavigationBar(
    currentTab: String,
    onTabSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VoxSurface)
            .border(width = 1.dp, color = VoxBorder, shape = RoundedCornerShape(topStart = 0.dp))
            .padding(vertical = 12.dp, horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TabNavItem(
            label = "RADIO",
            iconPainter = painterResource(id = android.R.drawable.ic_menu_call),
            isSelected = currentTab == "RADIO",
            onClick = { onTabSelected("RADIO") }
        )
        TabNavItem(
            label = "EQUIP",
            iconPainter = painterResource(id = android.R.drawable.ic_menu_info_details),
            isSelected = currentTab == "EQUIP",
            onClick = { onTabSelected("EQUIP") }
        )
        TabNavItem(
            label = "CONFIG",
            iconPainter = painterResource(id = android.R.drawable.ic_menu_preferences),
            isSelected = currentTab == "CONFIG",
            onClick = { onTabSelected("CONFIG") }
        )
    }
}

@Composable
fun TabNavItem(
    label: String,
    iconPainter: androidx.compose.ui.graphics.painter.Painter,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Icon(
            painter = iconPainter,
            contentDescription = label,
            tint = if (isSelected) VoxAccent else VoxSubText.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isSelected) VoxAccent else VoxSubText.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}
