package br.org.adiem.rt004test

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

class MainActivity : Activity() {
    companion object {
        private const val REQ_BLE_PERMISSIONS = 701
        private const val MQTT_HOST = "tcp://91.108.125.144:1883"
        private const val MQTT_TOPIC = "adiem/timing/rt004/lap"
        private const val LAPWIZ_ADDRESS = "A4:C1:38:3C:BE:7D"
        private const val LAPWIZ_PREFIX = "LapWiz"
        private const val COUNTER_MODULUS = 1L shl 24
        private const val SERVICE_FFF0 = "0000fff0-0000-1000-8000-00805f9b34fb"
        private const val CHAR_WRITE_FFF3 = "0000fff3-0000-1000-8000-00805f9b34fb"
        private const val CHAR_WRITE_FFF1 = "0000fff1-0000-1000-8000-00805f9b34fb"
        private const val CHAR_NOTIFY_FFF4 = "0000fff4-0000-1000-8000-00805f9b34fb"
        private const val CHAR_NOTIFY_FFF6 = "0000fff6-0000-1000-8000-00805f9b34fb"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

        private val INIT_PACKET_1 = byteArrayOf(
            0x0F, 0x0C, 0x05, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x06, 0xFF.toByte(), 0xFF.toByte()
        )
        private val INIT_PACKET_2 = byteArrayOf(
            0x0F, 0x0D, 0x03, 0x00, 0x01, 0x00, 0x01,
            0x2C, 0x00, 0x3C, 0x00, 0x05, 0x00, 0x03,
            0x76, 0xFF.toByte(), 0xFF.toByte()
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bleGatt: BluetoothGatt? = null
    private var scanInProgress = false
    private var mqtt: MqttAsyncClient? = null

    private lateinit var bleStatus: TextView
    private lateinit var mqttStatus: TextView
    private lateinit var lastLapStatus: TextView
    private lateinit var queueStatus: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var connectBleButton: Button
    private lateinit var connectMqttButton: Button
    private lateinit var disconnectButton: Button

    // Type 0x02 traz um contador uint24. A cronometragem é feita pelo relógio monotônico
    // do Android entre as notificações, como no protótipo ESP32; o contador fica para diagnóstico.
    private var lastRawCounter: Long? = null
    private var unwrappedCounter = 0L
    private var lastAcceptedCounter = 0L
    private var lastAcceptedPassageElapsed = 0L
    private var localLapNumber = 0
    private var publishedCount = 0
    private var notificationIndex = 0
    private val notifyCharacteristics = mutableListOf<BluetoothGattCharacteristic>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        buildUi()
        setBleStatus("Desconectado")
        setMqttStatus("Desconectado")
        appendLog("ADIEM RT004 Android Teste v0.1")
        appendLog("Antes de conectar: abra o Live Timing no portal, selecione o evento e clique em Iniciar novo treino.")
        appendLog("Este aplicativo é coletor experimental. Mantenha-o aberto e a tela ligada durante o teste.")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(14))
            setBackgroundColor(Color.rgb(12, 15, 19))
        }
        val scroller = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "ADIEM  |  RT004 TESTE"
            setTextColor(Color.WHITE)
            textSize = 23f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        }
        content.addView(title)
        content.addView(text(
            "Protótipo Android para substituir o ESP32 temporariamente: BLE → MQTT → backend → Supabase.",
            14f, Color.rgb(192, 202, 213)
        ).apply { setPadding(0, 0, 0, dp(12)) })

        bleStatus = statusLine("BLE RT004: ...")
        mqttStatus = statusLine("MQTT: ...")
        lastLapStatus = statusLine("Última volta: aguardando referência")
        queueStatus = statusLine("Voltas publicadas com sucesso: 0")
        content.addView(bleStatus)
        content.addView(mqttStatus)
        content.addView(lastLapStatus)
        content.addView(queueStatus)

        connectMqttButton = button("1. Conectar ao broker MQTT") { connectMqtt() }
        connectBleButton = button("2. Conectar ao LapWiz RT004") { beginBleConnect() }
        disconnectButton = button("Desconectar tudo") { disconnectEverything() }
        content.addView(connectMqttButton)
        content.addView(connectBleButton)
        content.addView(disconnectButton)

        content.addView(text(
            "Importante: este app não inicia o treino no banco. Inicie-o no portal ADIEM primeiro. O backend só grava voltas quando há uma sessão ativa para este RT004.",
            13f, Color.rgb(255, 210, 130)
        ).apply { setPadding(0, dp(8), 0, dp(12)) })

        content.addView(text("LOG DE TESTE", 15f, Color.WHITE).apply {
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(5))
        })
        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(20, 25, 31))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(270)
            )
        }
        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(215, 224, 232))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        logScroll.addView(logView)
        content.addView(logScroll)
        content.addView(text(
            "Laboratório: MQTT sem TLS/autenticação no broker de teste. Não use esta versão em produção. Para o primeiro teste, feche o aplicativo oficial LapWiz em outros celulares.",
            12f, Color.rgb(150, 160, 171)
        ).apply { setPadding(0, dp(12), 0, 0) })
        scroller.addView(content)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun statusLine(value: String) = text(value, 14f, Color.rgb(230, 234, 239)).apply {
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        setPadding(dp(8), dp(5), dp(8), dp(5))
        layoutParams = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setBleStatus(value: String) = runOnUiThread { bleStatus.text = "BLE RT004: $value" }
    private fun setMqttStatus(value: String) = runOnUiThread { mqttStatus.text = "MQTT: $value" }
    private fun setQueueStatus() = runOnUiThread {
        queueStatus.text = "Voltas publicadas com sucesso: $publishedCount"
    }

    private fun appendLog(message: String) {
        mainHandler.post {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale("pt", "BR")).format(java.util.Date())
            logView.append("[$timestamp] $message\n")
            val lines = logView.lineCount
            if (lines > 600) {
                val all = logView.text.toString().lines().takeLast(400).joinToString("\n")
                logView.text = all + "\n"
            }
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ), REQ_BLE_PERMISSIONS)
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_BLE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                appendLog("Permissões BLE concedidas.")
                startBleScan()
            } else {
                appendLog("Permissão Bluetooth negada; não é possível conectar ao RT004.")
            }
        }
    }

    private fun beginBleConnect() {
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            appendLog("Ative o Bluetooth do Android e toque em conectar novamente.")
            return
        }
        if (bleGatt != null) {
            appendLog("Já existe uma conexão BLE ativa ou em andamento.")
            return
        }
        startBleScan()
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            appendLog("Scanner BLE indisponível. Confirme que o Bluetooth está ligado.")
            return
        }
        scanInProgress = true
        setBleStatus("Procurando LapWiz...")
        appendLog("Procurando ${LAPWIZ_ADDRESS} ou dispositivo com nome $LAPWIZ_PREFIX...")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            scanInProgress = false
            appendLog("Falha no scan BLE: ${e.message}")
            setBleStatus("Falha no scan")
            return
        }
        mainHandler.postDelayed({
            if (scanInProgress) {
                stopBleScan()
                setBleStatus("Não encontrado — tente novamente")
                appendLog("Tempo de busca esgotado após 15 s.")
            }
        }, 15_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!scanInProgress) return
        scanInProgress = false
        try { bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) { }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName.orEmpty()
            val targetAddress = device.address.equals(LAPWIZ_ADDRESS, ignoreCase = true)
            val targetName = name.startsWith(LAPWIZ_PREFIX, ignoreCase = true)
            if (!targetAddress && !targetName) return
            stopBleScan()
            appendLog("Encontrado: ${if (name.isBlank()) "LapWiz" else name} / ${device.address} (RSSI ${result.rssi} dBm).")
            setBleStatus("Conectando a ${if (name.isBlank()) device.address else name}...")
            bleGatt = device.connectGatt(this@MainActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanInProgress = false
            appendLog("BLE scan falhou: código $errorCode")
            setBleStatus("Erro de busca $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("Erro GATT $status; conexão encerrada.")
                setBleStatus("Erro GATT $status")
                try { gatt.close() } catch (_: Exception) { }
                if (bleGatt === gatt) bleGatt = null
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    bleGatt = gatt
                    appendLog("BLE conectado; descobrindo serviços...")
                    setBleStatus("Conectado — descobrindo serviços")
                    val started = try { gatt.discoverServices() } catch (_: Exception) { false }
                    if (!started) appendLog("Não foi possível iniciar descoberta de serviços.")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    appendLog("BLE desconectado.")
                    setBleStatus("Desconectado")
                    try { gatt.close() } catch (_: Exception) { }
                    if (bleGatt === gatt) bleGatt = null
                    resetPassageBaseline()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("Falha na descoberta dos serviços: $status")
                return
            }
            val service = gatt.getService(UUID.fromString(SERVICE_FFF0))
            if (service == null) {
                appendLog("Serviço FFF0 não encontrado no RT004.")
                setBleStatus("Serviço FFF0 ausente")
                return
            }
            notifyCharacteristics.clear()
            listOf(CHAR_NOTIFY_FFF4, CHAR_NOTIFY_FFF6).forEach { uuidString ->
                service.getCharacteristic(UUID.fromString(uuidString))?.let { characteristic ->
                    val props = characteristic.properties
                    if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                        (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        notifyCharacteristics.add(characteristic)
                    }
                }
            }
            if (notifyCharacteristics.isEmpty()) {
                appendLog("Nenhuma característica FFF4/FFF6 com NOTIFY/INDICATE foi encontrada.")
                return
            }
            notificationIndex = 0
            appendLog("Serviço FFF0 encontrado. Ativando ${notifyCharacteristics.size} canal(is) de notificação...")
            configureNextNotification(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("Falha ao ativar notificação ${descriptor.characteristic.uuid}: $status")
            } else {
                appendLog("Notificação habilitada: ${descriptor.characteristic.uuid}")
            }
            notificationIndex++
            configureNextNotification(gatt)
        }

        @Deprecated("Legacy Android callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleNotification(characteristic, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(characteristic, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun configureNextNotification(gatt: BluetoothGatt) {
        if (notificationIndex >= notifyCharacteristics.size) {
            appendLog("Notificações prontas. Enviando comandos de inicialização RT004...")
            setBleStatus("Conectado — inicializando RT004")
            sendInitCommands(gatt)
            return
        }
        val characteristic = notifyCharacteristics[notificationIndex]
        try {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                appendLog("Não foi possível ativar notificação local para ${characteristic.uuid}; seguindo.")
                notificationIndex++
                configureNextNotification(gatt)
                return
            }
            val descriptor = characteristic.getDescriptor(UUID.fromString(CCCD_UUID))
            if (descriptor == null) {
                appendLog("CCCD ausente em ${characteristic.uuid}; seguindo.")
                notificationIndex++
                configureNextNotification(gatt)
                return
            }
            val value = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 &&
                (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                val result = gatt.writeDescriptor(descriptor, value)
                if (result != BluetoothStatusCodes.SUCCESS) {
                    appendLog("Escrita CCCD falhou com código $result; seguindo.")
                    notificationIndex++
                    configureNextNotification(gatt)
                }
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                val queued = gatt.writeDescriptor(descriptor)
                if (!queued) {
                    appendLog("Não foi possível escrever CCCD; seguindo.")
                    notificationIndex++
                    configureNextNotification(gatt)
                }
            }
        } catch (e: Exception) {
            appendLog("Erro ativando ${characteristic.uuid}: ${e.message}")
            notificationIndex++
            configureNextNotification(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendInitCommands(gatt: BluetoothGatt) {
        val service = gatt.getService(UUID.fromString(SERVICE_FFF0)) ?: return
        var writeChar = service.getCharacteristic(UUID.fromString(CHAR_WRITE_FFF3))
        if (writeChar == null || (writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
            writeChar = service.getCharacteristic(UUID.fromString(CHAR_WRITE_FFF1))
        }
        if (writeChar == null) {
            appendLog("Característica de escrita FFF3/FFF1 não encontrada.")
            return
        }
        val target = writeChar
        if (!writeNoResponse(gatt, target, INIT_PACKET_1)) {
            appendLog("Comando de inicialização 1 não foi aceito pela pilha BLE.")
            return
        }
        mainHandler.postDelayed({
            val current = bleGatt
            if (current !== gatt) return@postDelayed
            if (writeNoResponse(gatt, target, INIT_PACKET_2)) {
                appendLog("Comandos de inicialização enviados. Aguardando pacotes Type02.")
                setBleStatus("Conectado — aguardando passagens")
            } else {
                appendLog("Comando de inicialização 2 não foi aceito.")
            }
        }, 300)
    }

    @SuppressLint("MissingPermission")
    private fun writeNoResponse(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: Exception) {
            appendLog("Erro ao escrever característica: ${e.message}")
            false
        }
    }

    private fun handleNotification(characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        if (data.isEmpty()) return
        val hex = data.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
        appendLog("BLE ${characteristic.uuid.toString().take(8).uppercase(Locale.US)} [$hex]")
        if (data.size < 11 || (data[0].toInt() and 0xFF) != 0x0F || (data[2].toInt() and 0xFF) != 0x02) return

        val rawCounter = ((data[8].toLong() and 0xFF) shl 16) or
            ((data[9].toLong() and 0xFF) shl 8) or
            (data[10].toLong() and 0xFF)
        val nowElapsed = SystemClock.elapsedRealtime()
        val prevRaw = lastRawCounter
        if (prevRaw == null) {
            lastRawCounter = rawCounter
            unwrappedCounter = rawCounter
            lastAcceptedCounter = unwrappedCounter
            lastAcceptedPassageElapsed = nowElapsed
            appendLog("Primeiro Type02 usado como referência: contador=$rawCounter")
            runOnUiThread { lastLapStatus.text = "Última volta: aguardando segunda passagem" }
            return
        }

        val rawDelta = (rawCounter - prevRaw + COUNTER_MODULUS) % COUNTER_MODULUS
        lastRawCounter = rawCounter
        unwrappedCounter += rawDelta
        val elapsedMs = nowElapsed - lastAcceptedPassageElapsed
        if (elapsedMs < 1500L) {
            appendLog("Type02 muito próximo (<1,5 s); acumulando contador, sem publicar volta.")
            return
        }
        if (elapsedMs > 300_000L) {
            appendLog("Intervalo superior a 5 min descartado; nova referência temporal criada.")
            lastAcceptedPassageElapsed = nowElapsed
            lastAcceptedCounter = unwrappedCounter
            return
        }

        val counterStart = lastAcceptedCounter
        val counterEnd = unwrappedCounter
        val counterDelta = counterEnd - counterStart
        lastAcceptedPassageElapsed = nowElapsed
        lastAcceptedCounter = unwrappedCounter
        localLapNumber++

        val payload = JSONObject().apply {
            put("device", "LapWiz-7DBE")
            put("address", LAPWIZ_ADDRESS)
            put("lap_number", localLapNumber)
            put("lap_time_ms", elapsedMs)
            put("counter_start", counterStart)
            put("counter_end", counterEnd)
            put("counter_delta", counterDelta)
            put("source", "android_ble_test")
            put("created_millis", nowElapsed)
        }
        runOnUiThread {
            lastLapStatus.text = "Última volta: ${localLapNumber} · ${(elapsedMs / 1000.0).let { String.format(Locale("pt", "BR"), "%.3f s", it) }}"
        }
        appendLog("PASSAGEM detectada: volta local $localLapNumber, ${elapsedMs} ms, delta contador=$counterDelta")
        enqueueOrPublish(payload.toString())
    }

    private fun resetPassageBaseline() {
        lastRawCounter = null
        unwrappedCounter = 0L
        lastAcceptedCounter = 0L
        lastAcceptedPassageElapsed = 0L
        localLapNumber = 0
    }

    private fun connectMqtt() {
        val existing = mqtt
        if (existing?.isConnected == true) {
            appendLog("MQTT já está conectado.")
            return
        }
        try {
            val clientId = "ADIEM-ANDROID-${UUID.randomUUID().toString().take(8)}"
            val client = MqttAsyncClient(MQTT_HOST, clientId, MemoryPersistence())
            mqtt = client
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = 10
                keepAliveInterval = 30
            }
            setMqttStatus("Conectando...")
            appendLog("Conectando ao broker de teste $MQTT_HOST (clientId $clientId)...")
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    appendLog("MQTT conectado.")
                    setMqttStatus("Conectado — $MQTT_HOST")
                }
                override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable) {
                    appendLog("Falha MQTT: ${exception.message ?: exception.javaClass.simpleName}")
                    setMqttStatus("Falha — toque para tentar novamente")
                }
            })
        } catch (e: Exception) {
            appendLog("Não foi possível iniciar MQTT: ${e.message}")
            setMqttStatus("Falha: ${e.message}")
        }
    }

    private fun enqueueOrPublish(json: String) {
        val client = mqtt
        if (client == null || !client.isConnected) {
            // Não enfileirar para envio futuro: o treino pode ser encerrado antes da reconexão,
            // e uma mensagem atrasada poderia ser associada à sessão seguinte pelo backend.
            appendLog("MQTT indisponível: esta volta NÃO foi enviada. Conecte MQTT antes de continuar.")
            return
        }
        publishJson(client, json)
    }

    private fun publishJson(client: MqttAsyncClient, json: String) {
        try {
            client.publish(
                MQTT_TOPIC,
                json.toByteArray(StandardCharsets.UTF_8),
                0,
                false,
                json,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken) {
                        publishedCount++
                        setQueueStatus()
                        appendLog("MQTT PUBLICADO em $MQTT_TOPIC.")
                    }
                    override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable) {
                        appendLog("Falha ao publicar MQTT: ${exception.message}. Esta volta não será reenviada automaticamente.")
                    }
                }
            )
        } catch (e: MqttException) {
            appendLog("Erro publicando MQTT: ${e.message}. Esta volta não será reenviada automaticamente.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectEverything() {
        stopBleScan()
        try { bleGatt?.disconnect() } catch (_: Exception) { }
        try { bleGatt?.close() } catch (_: Exception) { }
        bleGatt = null
        val client = mqtt
        mqtt = null
        if (client != null) {
            Thread {
                try {
                    if (client.isConnected) client.disconnect(1000)
                    client.close()
                } catch (_: Exception) { }
            }.start()
        }
        resetPassageBaseline()
        setBleStatus("Desconectado")
        setMqttStatus("Desconectado")
        appendLog("Desconexão solicitada.")
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        stopBleScan()
        try { bleGatt?.disconnect() } catch (_: Exception) { }
        try { bleGatt?.close() } catch (_: Exception) { }
        bleGatt = null
        try {
            mqtt?.let { if (it.isConnected) it.disconnectForcibly(500, 500) }
            mqtt?.close()
        } catch (_: Exception) { }
        mqtt = null
        super.onDestroy()
    }
}
