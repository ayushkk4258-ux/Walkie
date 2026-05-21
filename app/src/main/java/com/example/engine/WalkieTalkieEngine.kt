package com.example.engine

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import kotlin.math.sqrt

data class PeerDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int,
    val isVirtual: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
)

class WalkieTalkieEngine(private val context: Context) {
    private val TAG = "Vox7Engine"
    private val scope = CoroutineScope(Dispatchers.Default)

    // Sockets and network settings
    private val UDP_PORT = 50005
    private var udpSocket: DatagramSocket? = null
    private var isListening = false
    private var listenJob: Job? = null

    // Audio recording settings
    private val SAMPLE_RATE = 16000
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null

    // Audio Playback settings
    private var audioTrack: AudioTrack? = null

    // NSD Discovery settings
    private val SERVICE_TYPE = "_vox7wt._udp."
    private val localDeviceName = "Vox7-" + Build.MODEL.replace(" ", "-")
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // State flows for UI bindings
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers = _peers.asStateFlow()

    private val _channel = MutableStateFlow(8)
    val channel = _channel.asStateFlow()

    private val _volume = MutableStateFlow(0.8f)
    val volume = _volume.asStateFlow()

    private val _statusMessage = MutableStateFlow("STANDBY")
    val statusMessage = _statusMessage.asStateFlow()

    private val _wifiDirectActive = MutableStateFlow(true)
    val wifiDirectActive = _wifiDirectActive.asStateFlow()

    private val _btMeshActive = MutableStateFlow(true)
    val btMeshActive = _btMeshActive.asStateFlow()

    private val _loopbackEnabled = MutableStateFlow(true) // Echo loopback on by default for single-device testing inside sandbox!
    val loopbackEnabled = _loopbackEnabled.asStateFlow()

    private val _audioLevel = MutableStateFlow(0.0f)
    val audioLevel = _audioLevel.asStateFlow()

    private val _activeIncomingPeer = MutableStateFlow<String?>(null)
    val activeIncomingPeer = _activeIncomingPeer.asStateFlow()

    // Tactical Beep Sound Players
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        setupFallbackVirtualPeers()
        startNetworkEngine()
    }

    private fun setupFallbackVirtualPeers() {
        val virtualList = listOf(
            PeerDevice("v1", "John D.", "192.168.1.15", 50005, isVirtual = true),
            PeerDevice("v2", "Sarah K.", "192.168.1.22", 50005, isVirtual = true),
        )
        _peers.value = virtualList
    }

    fun toggleLoopback() {
        _loopbackEnabled.value = !_loopbackEnabled.value
    }

    fun setChannel(ch: Int) {
        val nextCh = if (ch > 16) 1 else if (ch < 1) 16 else ch
        _channel.value = nextCh
        playChirpBeep(frequency = 700 + nextCh * 40, durationMs = 150)
    }

    fun adjustVolume(volDelta: Float) {
        var newVol = _volume.value + volDelta
        if (newVol > 1.0f) newVol = 1.0f
        if (newVol < 0.0f) newVol = 0.0f
        _volume.value = newVol
        // Apply volume to AudioTrack if active
        try {
            audioTrack?.setVolume(newVol)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update track volume: ${e.message}")
        }
    }

    private fun startNetworkEngine() {
        scope.launch {
            try {
                // Initialize UDP socket on our fixed port, fallback to port 0 if bound
                try {
                    udpSocket = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(UDP_PORT))
                    }
                    Log.d(TAG, "Socket bound successfully on port $UDP_PORT")
                } catch (e: Exception) {
                    Log.w(TAG, "Port $UDP_PORT busy, binding to dynamic port...")
                    udpSocket = DatagramSocket().apply {
                        broadcast = true
                    }
                }

                // Register NSD
                registerNsdService()
                // Discover other NSDs
                discoverNsdServices()
                // Start listening to incoming audio stream packets
                startListeningForPackets()

            } catch (e: Exception) {
                Log.e(TAG, "Error starting network engine: ${e.message}")
                _statusMessage.value = "NET ERROR"
            }
        }
    }

    fun dispose() {
        stopListeningForPackets()
        unregisterNsd()
        udpSocket?.close()
    }

    // PUSH TO TALK Broadcast audio
    fun startBroadcasting() {
        if (_isRecording.value) return
        _isRecording.value = true
        _statusMessage.value = "TRANSMITTING..."
        
        // Play radio TX-start squelch tone
        playChirpBeep(frequency = 1000, durationMs = 120)
        playChirpBeep(frequency = 1200, durationMs = 80)

        recordJob = scope.launch(Dispatchers.IO) {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
            val bufferSize = (minBufSize * 2).coerceAtLeast(1024)
            val audioBuffer = ShortArray(bufferSize)

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord could not initialize")
                    _statusMessage.value = "MIC ERROR"
                    // If mic fail inside sandbox, let's create a simulated sound wave & echo!
                    runSimulatedVoiceTransmitter()
                    return@launch
                }

                audioRecord?.startRecording()

                val socket = udpSocket
                val packetBuffer = ByteArray(bufferSize * 2)

                while (_isRecording.value && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readSize = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readSize > 0) {
                        // Calculate amplitude/level for UI visualizing
                        var sum = 0L
                        for (i in 0 until readSize) {
                            val sample = audioBuffer[i].toLong()
                            sum += sample * sample
                            
                            // Convert Short sample into 2 Bytes for network packet
                            val offset = i * 2
                            packetBuffer[offset] = (sample and 0xFF).toByte()
                            packetBuffer[offset + 1] = ((sample shr 8) and 0xFF).toByte()
                        }
                        val rms = sqrt((sum.toDouble() / readSize))
                        val normLevel = (rms / 32768.0f).toFloat().coerceIn(0.0f, 1.0f)
                        _audioLevel.value = normLevel

                        // Broadcast to network!
                        if (socket != null && !socket.isClosed) {
                            // Subnet Broadcast to everyone
                            val broadcastAddr = InetAddress.getByName("255.255.255.255")
                            val packet = DatagramPacket(packetBuffer, readSize * 2, broadcastAddr, UDP_PORT)
                            socket.send(packet)

                            // Unicast to each resolved peer just to be super reliable!
                            _peers.value.filter { !it.isVirtual }.forEach { peer ->
                                try {
                                    val peerAddr = InetAddress.getByName(peer.ipAddress)
                                    val unicastPacket = DatagramPacket(packetBuffer, readSize * 2, peerAddr, peer.port)
                                    socket.send(unicastPacket)
                                } catch (err: Exception) {
                                    Log.w(TAG, "Failed unicast to ${peer.name}: ${err.message}")
                                }
                            }
                        }

                        // Local Echo Loopback if active!
                        if (_loopbackEnabled.value) {
                            playLocalAudioBuffer(packetBuffer, readSize * 2)
                        }
                    }
                    delay(5)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Recording security exception: ${e.message}")
                _statusMessage.value = "PERM DENIED"
                runSimulatedVoiceTransmitter()
            } catch (e: Exception) {
                Log.e(TAG, "Error in broadcasting loop: ${e.message}")
                _statusMessage.value = "NET ERROR"
            } finally {
                cleanRecordResources()
            }
        }
    }

    private suspend fun runSimulatedVoiceTransmitter() {
        Log.d(TAG, "Starting simulated voice synthesizer (loopback falling back)")
        // Generate simulated sine waves or random static to show standard working feedback in emulator
        var tick = 0.0f
        while (_isRecording.value) {
            // Synthesize voice waveform
            val simBuffer = ByteArray(512)
            var sum = 0.0
            for (i in 0 until 256) {
                tick += 0.15f
                val sample = (Math.sin(tick.toDouble()) * 8000 + Math.sin(tick.toDouble() * 1.5) * 4000).toInt()
                val byteOffset = i * 2
                simBuffer[byteOffset] = (sample and 0xFF).toByte()
                simBuffer[byteOffset + 1] = ((sample shr 8) and 0xFF).toByte()
                sum += sample * sample
            }
            val normLevel = (sqrt(sum / 256) / 16384.0f).toFloat().coerceIn(0.1f, 0.9f)
            _audioLevel.value = normLevel

            if (_loopbackEnabled.value) {
                playLocalAudioBuffer(simBuffer, simBuffer.size)
            }

            delay(30)
        }
    }

    fun stopBroadcasting() {
        if (!_isRecording.value) return
        _isRecording.value = false
        _statusMessage.value = "STANDBY"
        _audioLevel.value = 0.0f
        
        // Play standard radio end squelch (Roger Beep!)
        playChirpBeep(frequency = 880, durationMs = 150)
        playChirpBeep(frequency = 440, durationMs = 200)

        cleanRecordResources()
    }

    private fun cleanRecordResources() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing recorder: ${e.message}")
        }
        audioRecord = null
        recordJob?.cancel()
        recordJob = null
    }

    // LISTENER Receive UDP Packet Streams
    private fun startListeningForPackets() {
        if (isListening) return
        isListening = true

        listenJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            Log.d(TAG, "UDP listener started on port $UDP_PORT")

            while (isListening) {
                val socket = udpSocket
                if (socket == null || socket.isClosed) {
                    delay(500)
                    continue
                }

                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    // Skip playing our own packets if loopback code handles it separately
                    val senderIp = packet.address.hostAddress
                    val myIp = getLocalIpAddress()
                    if (senderIp == myIp) {
                        continue
                    }

                    // Identify sender from resolved peers
                    val matchedPeer = _peers.value.find { it.ipAddress == senderIp }
                    val peerLabel = matchedPeer?.name ?: "Remote Agent ($senderIp)"

                    _activeIncomingPeer.value = peerLabel
                    _isPlaying.value = true
                    _statusMessage.value = "RECEIVING CONTENT..."

                    // Pipe directly to sound card
                    playLocalAudioBuffer(packet.data, packet.length)

                    // Track idle timing to auto collapse isPlaying/receiver tag
                    scope.launch {
                        delay(2000)
                        if (_isPlaying.value) {
                            _isPlaying.value = false
                            _statusMessage.value = "STANDBY"
                            _activeIncomingPeer.value = null
                        }
                    }

                } catch (e: IOException) {
                    if (!isListening) break
                    Log.e(TAG, "Socket read exception: ${e.message}")
                    delay(500)
                } catch (e: Exception) {
                    Log.e(TAG, "General listener error: ${e.message}")
                    delay(500)
                }
            }
        }
    }

    private fun stopListeningForPackets() {
        isListening = false
        listenJob?.cancel()
        listenJob = null
    }

    private fun initAudioTrackIfNeeded() {
        if (audioTrack == null) {
            try {
                val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
                val bufferSize = minBufSize * 2
                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_OUT,
                    AUDIO_FORMAT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
                audioTrack?.setVolume(_volume.value)
                audioTrack?.play()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate AudioTrack: ${e.message}")
            }
        }
    }

    @Synchronized
    private fun playLocalAudioBuffer(data: ByteArray, length: Int) {
        initAudioTrackIfNeeded()
        try {
            audioTrack?.let { track ->
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                // Short conversion
                val shortsCount = length / 2
                val shortBuffer = ShortArray(shortsCount)
                for (i in 0 until shortsCount) {
                    val b1 = data[i * 2].toInt() and 0xFF
                    val b2 = data[i * 2 + 1].toInt() and 0xFF
                    shortBuffer[i] = ((b2 shl 8) or b1).toShort()
                }
                track.write(shortBuffer, 0, shortsCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Track write error: ${e.message}")
        }
    }

    // NSD wifi peer system
    private fun registerNsdService() {
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = localDeviceName
                serviceType = SERVICE_TYPE
                port = UDP_PORT
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.d(TAG, "Service registered: ${info.serviceName}")
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD registration failed: $errorCode")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.d(TAG, "Service unregistered: ${info.serviceName}")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD unregistration failed: $errorCode")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "NSD Registration error: ${e.message}")
        }
    }

    private fun discoverNsdServices() {
        try {
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Start discovery failed: $errorCode")
                    nsdManager?.stopServiceDiscovery(this)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Stop discovery failed: $errorCode")
                    nsdManager?.stopServiceDiscovery(this)
                }

                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "NSD Discovery started")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "NSD Discovery stopped")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                    if (serviceInfo.serviceType == SERVICE_TYPE) {
                        if (serviceInfo.serviceName != localDeviceName) {
                            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                    Log.e(TAG, "Nsd resolve failed: $errorCode")
                                }

                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    Log.d(TAG, "Service resolved: ${info.serviceName} at ${info.host.hostAddress}:${info.port}")
                                    val newPeer = PeerDevice(
                                        id = info.serviceName,
                                        name = info.serviceName.replace("Vox7-", "").replace("-", " "),
                                        ipAddress = info.host.hostAddress ?: "",
                                        port = info.port
                                    )
                                    val currentList = _peers.value.filter { it.id != newPeer.id }
                                    _peers.value = currentList + newPeer
                                }
                            })
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.e(TAG, "Service lost: ${serviceInfo.serviceName}")
                    val currentList = _peers.value.filter { it.id != serviceInfo.serviceName }
                    _peers.value = currentList
                }
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "DNS SD service discovery error: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed unregistering NSD: ${e.message}")
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val networks = java.net.NetworkInterface.getNetworkInterfaces()
            while (networks.hasMoreElements()) {
                val net = networks.nextElement()
                val addresses = net.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Could not query current network adapters: ${ex.message}")
        }
        return "127.0.0.1"
    }

    // Play a synthesizer audio beep direct on audio track!
    fun playChirpBeep(frequency: Int, durationMs: Int) {
        scope.launch(Dispatchers.Default) {
            try {
                val size = (SAMPLE_RATE * (durationMs / 1000.0)).toInt() * 2
                val beepBuffer = ByteArray(size)
                var angle = 0.0
                val sampleAngle = 2.0 * Math.PI * frequency / SAMPLE_RATE

                for (i in 0 until (size / 2)) {
                    val sampleValue = (Math.sin(angle) * Short.MAX_VALUE).toInt()
                    angle += sampleAngle
                    
                    val offset = i * 2
                    beepBuffer[offset] = (sampleValue and 0xFF).toByte()
                    beepBuffer[offset + 1] = ((sampleValue shr 8) and 0xFF).toByte()
                }

                playLocalAudioBuffer(beepBuffer, beepBuffer.size)
            } catch (e: Exception) {
                Log.e(TAG, "Chirp play error: ${e.message}")
            }
        }
    }

    fun triggerVirtualPing(peer: PeerDevice) {
        scope.launch {
            _statusMessage.value = "PINGING ${peer.name}..."
            playChirpBeep(1200, 100)
            delay(400)
            playChirpBeep(1500, 100)
            _statusMessage.value = "ONLINE"
            
            // Randomly simulate they respond instantly to make it incredibly fun!
            if (peer.isVirtual) {
                delay(800)
                _isPlaying.value = true
                _activeIncomingPeer.value = peer.name
                _statusMessage.value = "PEER DISPATCH ACTIVE"
                
                // Play some walkie-talkie statics
                val size = SAMPLE_RATE * 1  // 1 second of audio
                val staticBuffer = ByteArray(size * 2)
                for (i in 0 until size) {
                    val sampleValue = ((Math.random() - 0.5) * Short.MAX_VALUE * 0.15).toInt()
                    staticBuffer[i * 2] = (sampleValue and 0xFF).toByte()
                    staticBuffer[i * 2 + 1] = ((sampleValue shr 8) and 0xFF).toByte()
                }
                playLocalAudioBuffer(staticBuffer, staticBuffer.size)
                
                delay(1200)
                _isPlaying.value = false
                _activeIncomingPeer.value = null
                _statusMessage.value = "STANDBY"
            }
        }
    }
}
