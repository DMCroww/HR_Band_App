package com.dmcroww.hrband

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

@SuppressLint("MissingPermission")
class BleService: Service() {
	private lateinit var broadcastReceiver: BroadcastReceiver
	private lateinit var btStateReceiver: BroadcastReceiver

	private val deviceList = mutableListOf<BluetoothDevice>()
	private val WS = WSlink(this)

	private val handler = Handler(Looper.getMainLooper())

	private var connected = false
	private var connectCounter = 0
	private var battLoopDelay = 1.minutes
	private var deviceAddress = "C2:8B:8C:0A:D2:E3"
	private var channel = ""
	private var code = ""
	private var wsAddress = "192.168.1.4"
	private var autoReconnect = false
	private val timeSource = TimeSource.Monotonic
	private var disconnectedAt = timeSource.markNow()
	private var connectedDevice = ""

	private var heartRate = -1
	private var batteryLevel = -1

	var heartRateList = mutableListOf<Int>()

	private val bluetoothAdapter: BluetoothAdapter by lazy {
		val bluetoothManager = getSystemService(BluetoothManager::class.java)
		bluetoothManager.adapter
	}
	private val bleScanner by lazy {bluetoothAdapter.bluetoothLeScanner}
	private var bluetoothGatt: BluetoothGatt? = null

	private val binder = LocalBinder()
	private lateinit var notificationManager: NotificationManager

	@SuppressLint("UnspecifiedRegisterReceiverFlag")
	override fun onCreate() {
		super.onCreate()
		notificationManager = getSystemService(NotificationManager::class.java)
		createNotificationChannel()
		startForeground(1, buildNotification("Disconnected"))

		broadcastReceiver = object: BroadcastReceiver() {
			override fun onReceive(context: Context?, intent: Intent) {

				when (intent.action.toString().replace("com.dmcroww.hrband.", "")) {
					"SCAN" -> scanForDevices()
					"DISCONNECT" -> disconnectBLE(true)
					"OPTIONS" -> readOptions()
					"CONNECTED?" -> {
						sendBroadcast(Intent("com.dmcroww.hrband.HEARTRATE").putExtra("data", heartRate))
						sendBroadcast(Intent("com.dmcroww.hrband.BATTERY").putExtra("data", batteryLevel))
						sendBroadcast(Intent("com.dmcroww.hrband.CONNECTED").putExtra("data", connectedDevice))
					}

					"CONNECT" -> {
						val address = intent.getStringExtra("addr")
						deviceList.find {it.address == address}?.let {connectToBLEDevice(it)}
					}
				}
			}
		}

		registerReceiver(broadcastReceiver, IntentFilter().apply {
			addAction("com.dmcroww.hrband.SCAN")
			addAction("com.dmcroww.hrband.CONNECT")
			addAction("com.dmcroww.hrband.DISCONNECT")
			addAction("com.dmcroww.hrband.OPTIONS")
			addAction("com.dmcroww.hrband.CONNECTED?")
		})

		// Register receiver to listen for Bluetooth state changes
		btStateReceiver = object: BroadcastReceiver() {
			override fun onReceive(context: Context?, intent: Intent?) {
				if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
					val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
					if (state == BluetoothAdapter.STATE_ON) {
						Log.i("BLE", "Bluetooth enabled, restarting scan.")
						scanForDevices() // Restart scanning when Bluetooth is enabled
					}
				}
			}
		}

		registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

		readOptions()

		WS.connect(wsAddress, channel, code)

		scanForDevices()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		return START_STICKY
	}

	override fun onBind(intent: Intent?): IBinder = binder

	inner class LocalBinder: Binder() {
		fun getService(): BleService = this@BleService
	}

	private val scanCallback = object: ScanCallback() {
		override fun onScanResult(callbackType: Int, result: ScanResult) {
			val device = result.device

			if (autoReconnect && device.address == deviceAddress) return connectToBLEDevice(device)

			if (device.name != null && !deviceList.contains(device)) {
				deviceList.add(device)

				sendBroadcast(Intent("com.dmcroww.hrband.DEVICE").putExtra("data", emptyArray<String>().plus(device.name).plus(device.address)))
			}
		}
	}

	private fun scanForDevices() {

		if (!bluetoothAdapter.isEnabled) {
			Log.w("BLE", "Bluetooth is disabled. Asking user to enable it.")
			sendBroadcast(Intent("com.dmcroww.hrband.BTENABLE"))

			return
		}

		val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
		val wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "MyApp:WakeLock")
		wakeLock.acquire(11 * 1000L) // Wake up for 10 seconds

		Log.d("BLE", "Scanning...")
		updateNotification("Scanning...")
		deviceList.clear()
		sendBroadcast(Intent("com.dmcroww.hrband.DEVICECLEAR").putExtra("data", ""))

		bleScanner.startScan(scanCallback)
		sendBroadcast(Intent("com.dmcroww.hrband.STATUS").putExtra("data", "Scanning..."))
		Handler(Looper.getMainLooper()).postDelayed({
			if (!connected) {
				bleScanner.stopScan(scanCallback)
				sendBroadcast(Intent("com.dmcroww.hrband.STATUS").putExtra("data", "Scan done."))
			}
		}, 9000)
	}

	@SuppressLint("MissingPermission")
	private fun connectToBLEDevice(device: BluetoothDevice) {
		connected = true
		bleScanner.stopScan(scanCallback)
		deviceList.clear()
		autoReconnect = getSharedPreferences("MyPrefs", MODE_PRIVATE).getBoolean("reconnect", false)

		updateNotification("Connecting...")
		sendBroadcast(Intent("com.dmcroww.hrband.STATUS").putExtra("data", "Connecting..."))

		bluetoothGatt = device.connectGatt(this, false, object: BluetoothGattCallback() {
			override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
				if (newState == BluetoothProfile.STATE_CONNECTED) {
					Log.d("BLE", "Connected to ${device.address}")
					WS.sendWS(true, "connected")
					connectedDevice = device.name

					sendBroadcast(Intent("com.dmcroww.hrband.CONNECTED").putExtra("data", connectedDevice))

					getSharedPreferences("MyPrefs", MODE_PRIVATE).edit().putString("btAddress", device.address).apply()

					gatt.discoverServices()
				} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
					Log.d("BLE", "Disconnected")

					WS.sendWS(false, "connected")

					disconnectBLE()
					if (autoReconnect) Handler(Looper.getMainLooper()).postDelayed({
						if (!connected) delayScan()
					}, 2000)
				}
			}


			override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {

				val hrService = gatt.getService(UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB"))
				val hrCharacteristic = hrService?.getCharacteristic(UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB"))

				if (hrCharacteristic != null) {
					gatt.setCharacteristicNotification(hrCharacteristic, true)

					val descriptor = hrCharacteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
					if (descriptor != null) {
						descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
						gatt.writeDescriptor(descriptor)
					} else startBatteryLoop(gatt)
				} else startBatteryLoop(gatt)
			}

			// Once descriptor write is confirmed, now it's safe to do other things
			override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
				startBatteryLoop(gatt)
			}

			// Wrap battery loop starter for clarity
			fun startBatteryLoop(gatt: BluetoothGatt) {
				val battService = gatt.getService(UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB"))
				val battCharacteristic = battService?.getCharacteristic(UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB"))
				if (battCharacteristic != null) battLevelLoop(gatt, battCharacteristic)
			}


			// Legacy support for older Android versions
			override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
				onCharacteristicChanged(gatt, characteristic, characteristic.value)
			}


			override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
				if (characteristic.uuid == UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")) {
					val flags = value[0].toInt()
					val isHeartRate16Bit = flags and 0x01 != 0

					val heartRate = if (isHeartRate16Bit) {
						((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
					} else {
						value[1].toInt() and 0xFF
					}

					if (heartRate == 0) return

					heartRateList.add(heartRate)
					if (heartRateList.size > 1200) heartRateList.removeAt(0)

					WS.sendWS(heartRate)
					updateNotification("\uD83D\uDD0B $batteryLevel% , ♥ $heartRate bpm")
					sendBroadcast(Intent("com.dmcroww.hrband.HEARTRATE").putExtra("data", heartRate))
				}
			}

			override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic, status: Int) {
				val value = characteristic.value
				if (characteristic.uuid == UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")) {
					batteryLevel = value[0].toInt()
//						Log.d("BLE", "Battery: $batteryLevel (loop)")
					WS.sendWS(batteryLevel, "battery")

					battLoopDelay = if (batteryLevel < 15) 10.minutes
					else if (batteryLevel < 30) 30.minutes
					else 60.minutes

					updateNotification("\uD83D\uDD0B $batteryLevel% , ♥ $heartRate bpm")
					sendBroadcast(Intent("com.dmcroww.hrband.BATTERY").putExtra("data", batteryLevel))
				}
			}
		})
	}

	fun delayScan() {
		val now = timeSource.markNow()
		val delta = now - disconnectedAt

		val delay = if (delta < 1.minutes) 0.1.seconds // every ~10s till 1min mark
		else if (delta < 5.minutes) 10.seconds // every 20s till 5min mark
		else if (delta < 15.minutes) 50.seconds // every 1min till 15min mark
		else if (delta < 1.hours) 290.seconds // every 5min till 1h mark
		else 890.seconds // every 15min till infinity

		connectCounter += 1

		Log.d("BLE", "Counter: ${connectCounter}x , delay: ${delay + 10.seconds}")

		handler.postDelayed({
			scanForDevices()
			handler.postDelayed({
				if (!connected) delayScan()
				else connectCounter = 0
			}, 10000)
		}, delay.toLong(DurationUnit.MILLISECONDS))
	}

	private fun battLevelLoop(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
		gatt.readCharacteristic(characteristic)
		Handler(Looper.getMainLooper()).postDelayed({
			battLevelLoop(gatt, characteristic)
		}, battLoopDelay.toLong(DurationUnit.MILLISECONDS))
	}

	private fun disconnectBLE(manualDc: Boolean = false) {
		updateNotification("Disconnected.")

		connectedDevice = ""
		sendBroadcast(Intent("com.dmcroww.hrband.CONNECTED").putExtra("data", connectedDevice))

		disconnectedAt = timeSource.markNow()
		connected = false
		if (manualDc) autoReconnect = false

		WS.sendWS(false, "connected")

		bluetoothGatt?.disconnect()
		bluetoothGatt?.close()
		bluetoothGatt = null

		scanForDevices()
	}

	private fun readOptions() {
		val prefs = getSharedPreferences("MyPrefs", MODE_PRIVATE)
		autoReconnect = prefs.getBoolean("reconnect", false)
		wsAddress = prefs.getString("wsAddress", "192.168.1.4").toString()
		deviceAddress = prefs.getString("btAddress", null).toString()
		channel = prefs.getString("channel", null).toString()
		code = prefs.getString("code", null).toString()
	}

	fun getStoredData(): List<Int> {
		return heartRateList
	}

	fun getBattery(): Int {
		return batteryLevel
	}

	fun wsFailed() {
		sendBroadcast(Intent("com.dmcroww.hrband.STATUS").putExtra("data", "WS Disconnected."))
	}

	private fun createNotificationChannel() {
		val channel = NotificationChannel("ble_service", "BLE Service", NotificationManager.IMPORTANCE_LOW)
		notificationManager.createNotificationChannel(channel)
	}

	private fun buildNotification(content: String): Notification {
		val intent = Intent(this, MainActivity::class.java).apply {flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP}
		val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

		return NotificationCompat.Builder(this, "ble_service").setContentTitle(content).setSmallIcon(R.drawable.ic_notif_running).setContentIntent(pendingIntent).build()
	}

	private fun updateNotification(content: String) {
		notificationManager.notify(1, buildNotification(content))
	}

	override fun onDestroy() {
		disconnectBLE(true)
		super.onDestroy()
	}
}