package com.example.vpn

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.DnsShieldApplication
import com.example.MainActivity
import com.example.R
import com.example.data.repository.ShieldRepository
import com.example.dns.DnsDecision
import com.example.dns.DnsWireCodec
import com.example.dns.IpUdpPacketCodec
import com.example.dns.ParsedUdpPacket
import com.example.dns.UpstreamDnsForwarder
import com.example.dns.UpstreamDnsResult
import com.example.receiver.ServiceRestartReceiver
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class DnsVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()
    private val upstreamSemaphore = Semaphore(MAX_CONCURRENT_UPSTREAM)

    private lateinit var repository: ShieldRepository
    private lateinit var upstreamForwarder: UpstreamDnsForwarder

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var tunOutputStream: FileOutputStream? = null
    @Volatile private var packetLoopJob: Job? = null
    @Volatile private var userStoppedExplicitly = false
    @Volatile private var latestBlockedToday = 0L

    override fun onCreate() {
        super.onCreate()
        repository = (application as DnsShieldApplication).repository
        upstreamForwarder = UpstreamDnsForwarder { socket -> protect(socket) }
        createNotificationChannel()

        serviceScope.launch {
            repository.statsSnapshot
                .map { it.blockedToday }
                .distinctUntilChanged()
                .collect { count ->
                    latestBlockedToday = count
                    if (vpnInterface != null) {
                        val manager = getSystemService(NotificationManager::class.java)
                        manager?.notify(NOTIFICATION_ID, buildNotification(count))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            userStoppedExplicitly = true
            serviceScope.launch {
                repository.setVpnDesiredEnabled(false)
                stopVpnTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        userStoppedExplicitly = false
        startForegroundNotification()

        if (action == null) {
            serviceScope.launch {
                val shouldRun = repository.isVpnDesiredEnabled()
                if (!shouldRun || prepare(this@DnsVpnService) != null) {
                    stopVpnTunnel()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    startVpnTunnelIfNeeded()
                }
            }
        } else {
            serviceScope.launch {
                if (prepare(this@DnsVpnService) != null) {
                    repository.setVpnDesiredEnabled(false)
                    stopVpnTunnel()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    repository.setVpnDesiredEnabled(true)
                    startVpnTunnelIfNeeded()
                }
            }
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notification = buildNotification(latestBlockedToday)
        val fgType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
    }

    private suspend fun startVpnTunnelIfNeeded() {
        if (vpnInterface != null && packetLoopJob?.isActive == true) {
            repository.updateVpnRunning(true)
            return
        }

        repository.ensureRulesLoaded(applicationContext)

        val builder =
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(VPN_MTU)
                .addAddress(VPN_ADDRESS_V4, 32)
                .addDnsServer(VPN_DNS_V4)
                .addRoute(VPN_DNS_V4, 32)

        runCatching {
            builder
                .addAddress(VPN_ADDRESS_V6, 128)
                .addDnsServer(VPN_DNS_V6)
                .addRoute(VPN_DNS_V6, 128)
        }

        val established = runCatching { builder.establish() }.getOrNull()
        if (established == null) {
            repository.updateVpnRunning(false)
            repository.setVpnDesiredEnabled(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        vpnInterface = established
        tunOutputStream = FileOutputStream(established.fileDescriptor)
        repository.updateVpnRunning(true)

        packetLoopJob?.cancel()
        packetLoopJob = serviceScope.launch { runPacketLoop(established) }
    }

    private suspend fun runPacketLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buffer = ByteArray(PACKET_BUFFER_SIZE)

        try {
            while (vpnInterface != null) {
                val readBytes =
                    try {
                        input.read(buffer)
                    } catch (_: Exception) {
                        break
                    }
                if (readBytes < 0) break
                if (readBytes == 0) continue

                val udpPacket = IpUdpPacketCodec.parseUdpDnsPacket(buffer, readBytes) ?: continue
                handleDnsPacket(udpPacket)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Keep service resilient on unexpected read errors
        } finally {
            repository.updateVpnRunning(false)
        }
    }

    private fun handleDnsPacket(udpPacket: ParsedUdpPacket) {
        val payload = udpPacket.payload
        val query = DnsWireCodec.parseQuery(payload, payload.size) ?: return

        // Internal Gemini API host always forwards immediately without semaphore or holding
        if (repository.filterEngine.isAlwaysAllowedInternalHost(query.domain)) {
            serviceScope.launch {
                try {
                    val upstreamReply = upstreamForwarder.forward(payload) ?: return@launch
                    val ipResponse = IpUdpPacketCodec.buildUdpResponsePacket(udpPacket, upstreamReply)
                    writePacketToTun(ipResponse)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
            }
            return
        }

        // Fast synchronous path (< 10 ms) for blocklist, custom rules, keywords, and cached ADULT
        val instant = repository.decisionEngine.checkInstantDecision(query.domain)
        if (instant is DnsDecision.Block) {
            val dnsResponse = DnsWireCodec.buildBlockedResponse(payload, query)
            val ipResponse = IpUdpPacketCodec.buildUdpResponsePacket(udpPacket, dnsResponse)
            writePacketToTun(ipResponse)

            serviceScope.launch {
                runCatching {
                    repository.recordBlockedQuery(
                        domain = query.domain,
                        queryType = query.qtypeLabel,
                        reason = instant.reason,
                        durationMs = instant.latencyMs,
                    )
                }
            }
            return
        }

        // Asynchronous decision path (Whitelist, Safe list, Cache SAFE, Upstream 1.1.1.3, AI)
        serviceScope.launch {
            try {
                val decision =
                    repository.decisionEngine.evaluate(query.domain) {
                        val reply =
                            upstreamSemaphore.withPermit { upstreamForwarder.forward(payload) }
                        val blockedByUpstream =
                            reply != null && DnsWireCodec.isZeroIpAnswer(reply, reply.size)
                        UpstreamDnsResult(reply, blockedByUpstream)
                    }

                when (decision) {
                    is DnsDecision.Block -> {
                        val dnsResponse = DnsWireCodec.buildBlockedResponse(payload, query)
                        val ipResponse =
                            IpUdpPacketCodec.buildUdpResponsePacket(udpPacket, dnsResponse)
                        writePacketToTun(ipResponse)
                        runCatching {
                            repository.recordBlockedQuery(
                                domain = query.domain,
                                queryType = query.qtypeLabel,
                                reason = decision.reason,
                                durationMs = decision.latencyMs,
                            )
                        }
                    }
                    is DnsDecision.AllowWithUpstream -> {
                        val ipResponse =
                            IpUdpPacketCodec.buildUdpResponsePacket(udpPacket, decision.upstreamBytes)
                        writePacketToTun(ipResponse)
                    }
                    is DnsDecision.AllowImmediate -> {
                        val reply = upstreamSemaphore.withPermit { upstreamForwarder.forward(payload) }
                        if (reply != null) {
                            val ipResponse = IpUdpPacketCodec.buildUdpResponsePacket(udpPacket, reply)
                            writePacketToTun(ipResponse)
                        }
                    }
                    is DnsDecision.Drop -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Drop silently without crashing
            }
        }
    }

    private fun writePacketToTun(packet: ByteArray) {
        val out = tunOutputStream ?: return
        synchronized(writeLock) {
            runCatching {
                out.write(packet)
                out.flush()
            }
        }
    }

    private fun stopVpnTunnel() {
        packetLoopJob?.cancel()
        packetLoopJob = null
        synchronized(writeLock) {
            runCatching { tunOutputStream?.close() }
            tunOutputStream = null
        }
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        repository.updateVpnRunning(false)
    }

    override fun onRevoke() {
        userStoppedExplicitly = true
        serviceScope.launch {
            repository.setVpnDesiredEnabled(false)
            stopVpnTunnel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!userStoppedExplicitly) {
            scheduleRestartIfNeeded()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val shouldRestart = !userStoppedExplicitly
        stopVpnTunnel()
        serviceScope.cancel()
        if (shouldRestart) {
            scheduleRestartIfNeeded()
        }
        super.onDestroy()
    }

    private fun scheduleRestartIfNeeded() {
        runCatching {
            val restartIntent = Intent(applicationContext, ServiceRestartReceiver::class.java)
            val pendingIntent =
                PendingIntent.getBroadcast(
                    applicationContext,
                    RESTART_REQUEST_CODE,
                    restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val alarmManager = getSystemService(AlarmManager::class.java)
            alarmManager?.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000L,
                pendingIntent,
            )
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
                .apply {
                    description = getString(R.string.notification_channel_desc)
                    setShowBadge(false)
                }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(blockedToday: Long): Notification {
        val openAppIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, DnsVpnService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val contentText = "Filtering DNS • $blockedToday blocked today"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .addAction(
                R.drawable.ic_shield_notification,
                getString(R.string.notification_stop),
                stopIntent,
            )
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.action.START_VPN"
        const val ACTION_STOP = "com.example.action.STOP_VPN"

        private const val CHANNEL_ID = "dns_shield_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val RESTART_REQUEST_CODE = 2002

        private const val VPN_MTU = 1500
        private const val PACKET_BUFFER_SIZE = 32767
        private const val MAX_CONCURRENT_UPSTREAM = 64

        private const val VPN_ADDRESS_V4 = "10.111.222.1"
        private const val VPN_DNS_V4 = "10.111.222.2"
        private const val VPN_ADDRESS_V6 = "fd11:1111:1111::1"
        private const val VPN_DNS_V6 = "fd11:1111:1111::2"

        fun start(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).apply { action = ACTION_START }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
                .onFailure { context.stopService(Intent(context, DnsVpnService::class.java)) }
        }
    }
}
