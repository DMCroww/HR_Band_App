package com.dmcroww.hrband

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity: AppCompatActivity() {
	private lateinit var titleText: TextView
	private lateinit var scanButton: Button
	private lateinit var deviceListView: ListView
	private lateinit var statusText: TextView
	private lateinit var optButton: Button
	private lateinit var exitButton: Button

	private lateinit var deviceView: View
	private lateinit var deviceNameText: TextView
	private lateinit var batteryText: TextView
	private lateinit var bpmText: TextView
	private lateinit var disconnectButton: Button

	private lateinit var optionsView: View
	private lateinit var reconnectSwitch: Switch
	private lateinit var wsAddressInput: EditText
	private lateinit var channelInput: EditText
	private lateinit var codeInput: EditText
	private lateinit var opSaveButton: Button

	private lateinit var broadcastReceiver: BroadcastReceiver

	private val deviceList = mutableListOf<Array<String>>()

	private var connected = ""
	private var wsAddress = "192.168.1.4"
	private var channel = ""
	private var code = ""
	private var autoReconnect = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		// Request permissions first
		checkPermissions()
	}

	@SuppressLint("InflateParams", "UnspecifiedRegisterReceiverFlag")
	private fun initializeApp() {
		broadcastReceiver = object: BroadcastReceiver() {
			override fun onReceive(context: Context?, intent: Intent) {

				when (intent.action.toString().replace("com.dmcroww.hrband.", "")) {
					"STATUS" -> statusText.text = intent.getStringExtra("data")
					"DEVICE" -> {
						deviceList.add(intent.getStringArrayExtra("data")!!)
						deviceListView.adapter = DeviceAdapter(this@MainActivity, deviceList)
					}

					"DEVICECLEAR" -> {
						deviceList.clear()
						deviceListView.adapter = DeviceAdapter(this@MainActivity, deviceList)
					}

					"BTENABLE" -> requestBluetoothEnable()
					"HEARTRATE" -> runOnUiThread {bpmText.text = "${intent.getIntExtra("data", -1)} ♥"}
					"BATTERY" -> runOnUiThread {batteryText.text = "\uD83D\uDD0B ${intent.getIntExtra("data", -1)}%"}
					"CONNECTED" -> {
						connected = intent.getStringExtra("data")!!

						runOnUiThread {
							val conn = connected.isNotBlank()

							if (conn) hideSystemUI() else showSystemUI()

							deviceView.visibility = if (conn) View.VISIBLE else View.GONE
							titleText.visibility = if (conn) View.GONE else View.VISIBLE
							scanButton.visibility = if (conn) View.GONE else View.VISIBLE
							deviceListView.visibility = if (conn) View.GONE else View.VISIBLE
							statusText.visibility = if (conn) View.GONE else View.VISIBLE

							deviceNameText.text = connected.ifBlank {"Disconnected."}
						}
					}
				}
			}
		}

		val filter = IntentFilter().apply {
			addAction("com.dmcroww.hrband.STATUS")
			addAction("com.dmcroww.hrband.DEVICE")
			addAction("com.dmcroww.hrband.DEVICECLEAR")
			addAction("com.dmcroww.hrband.HEARTRATE")
			addAction("com.dmcroww.hrband.BATTERY")
			addAction("com.dmcroww.hrband.CONNECTED")
			addAction("com.dmcroww.hrband.BTENABLE")
		}
		registerReceiver(broadcastReceiver, filter)



		optButton = findViewById(R.id.button_options)
		exitButton = findViewById(R.id.button_exit)
		titleText = findViewById(R.id.title)
		scanButton = findViewById(R.id.scan_button)
		deviceListView = findViewById(R.id.device_list)
		statusText = findViewById(R.id.status_text)

		optButton.setOnClickListener {openOptions()}
		exitButton.setOnClickListener {exitApp()}
		scanButton.setOnClickListener {scanForDevices()}
		deviceListView.setOnItemClickListener {_, _, position, _ ->
			connectToBLEDevice(deviceList[position])
		}

		// Inflate the device view but keep it hidden initially
		val inflater = layoutInflater
		deviceView = inflater.inflate(R.layout.layout_device, null)
		addContentView(
			deviceView, LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
			)
		)
		deviceView.visibility = View.GONE

		// Get references to device view elements
		deviceNameText = deviceView.findViewById(R.id.device_name)
		batteryText = deviceView.findViewById(R.id.batt)
		bpmText = deviceView.findViewById(R.id.bpm)
		disconnectButton = deviceView.findViewById(R.id.button_disconnect)

		disconnectButton.setOnClickListener {
			disconnectBLE()
		}

		// Inflate the options view but keep it hidden initially

		optionsView = inflater.inflate(R.layout.layout_options, null)
		addContentView(
			optionsView, LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
			)
		)
		optionsView.visibility = View.GONE

		// Get references to device view elements
		reconnectSwitch = optionsView.findViewById(R.id.option_reconnect)
		wsAddressInput = optionsView.findViewById(R.id.option_wsUrl)
		channelInput = optionsView.findViewById(R.id.option_channel)
		codeInput = optionsView.findViewById(R.id.option_code)
		opSaveButton = optionsView.findViewById(R.id.button_op_save)


		opSaveButton.setOnClickListener {saveOptions()}


		startService(Intent(this, BleService::class.java))

		readOptions()
		reconnectSwitch.isChecked = autoReconnect
		wsAddressInput.setText(wsAddress)
		channelInput.setText(channel)
		codeInput.setText(code)

		sendBroadcast(Intent("com.dmcroww.hrband.CONNECTED?"))
	}

	private fun checkPermissions() {
		val permissions = mutableListOf<String>()

		permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
//		permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			permissions.add(Manifest.permission.BLUETOOTH_SCAN)
			permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
		} else {
			permissions.add(Manifest.permission.BLUETOOTH)
			permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			permissions.add(Manifest.permission.POST_NOTIFICATIONS)
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			permissions.add(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
		}

		permissions.add(Manifest.permission.FOREGROUND_SERVICE)

		val permissionsToRequest = permissions.filter {
			ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
		}.toTypedArray()

		if (permissionsToRequest.isNotEmpty()) {
			Log.d("Permissions", "Requesting permissions: ${permissionsToRequest.joinToString()}")
			ActivityCompat.requestPermissions(this, permissionsToRequest, 1)
		} else {
			Log.d("Permissions", "All base permissions already granted.")
			initializeApp() // Continue initialization after getting permissions
		}
	}

	private fun requestBackgroundLocation() {
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {

			AlertDialog.Builder(this).setTitle("Background Location Permission").setMessage("To scan for BLE devices in the background, please allow background location access.").setPositiveButton("OK") {_, _ ->
				ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
			}.setNegativeButton("No") {_, _ ->
				Toast.makeText(this, "Background location denied! The app may not function correctly.", Toast.LENGTH_LONG).show()
			}.setCancelable(false).show()
		}
	}

	override fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<out String>,
		grantResults: IntArray,
	) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)

		if (requestCode == 1) {
			val allGranted = grantResults.all {it == PackageManager.PERMISSION_GRANTED}

			if (allGranted) {
				Log.d("Permissions", "All permissions granted.")
				requestBackgroundLocation()
			} else {
				AlertDialog.Builder(this).setTitle("Permissions Required").setMessage("The app cannot function without required permissions.").setPositiveButton("OK") {_, _ ->
					Toast.makeText(this, "Permissions required! Exiting app...", Toast.LENGTH_LONG).show()
					stopService(Intent(this, BleService::class.java))
					finishAffinity()
					System.exit(0)
				}.setCancelable(false).show()
			}
		} else if (requestCode == 2) { // Background location request
			if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				Log.d("Permissions", "Background location granted.")
				initializeApp() // Continue initialization after getting permissions
			} else {
				Log.e("Permissions", "Background location denied.")
				Toast.makeText(this, "Background location denied! The app may not function correctly.", Toast.LENGTH_LONG).show()
			}
		}
	}

	private fun requestBluetoothEnable() {
		val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
		enableBtIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		startActivity(enableBtIntent)
	}

	private fun scanForDevices() {
		sendBroadcast(Intent("com.dmcroww.hrband.SCAN"))
	}

	private fun connectToBLEDevice(device: Array<String>) {
		sendBroadcast(Intent("com.dmcroww.hrband.CONNECT").putExtra("addr", device[1]))
		statusText.text = "Connecting..."
		return
	}

	private fun disconnectBLE() {
		sendBroadcast(Intent("com.dmcroww.hrband.DISCONNECT"))
	}

	private fun hideSystemUI() {
		WindowCompat.setDecorFitsSystemWindows(window, false)
		WindowInsetsControllerCompat(
			window, window.decorView.findViewById(android.R.id.content)
		).let {controller ->
			controller.hide(WindowInsetsCompat.Type.systemBars())

			controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		}
	}

	private fun showSystemUI() {
		WindowCompat.setDecorFitsSystemWindows(window, true)
		WindowInsetsControllerCompat(
			window, window.decorView.findViewById(android.R.id.content)
		).let {controller ->
			controller.show(WindowInsetsCompat.Type.systemBars())

			controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
		}
	}

	private fun saveOptions() {
		val prefs = getSharedPreferences("MyPrefs", MODE_PRIVATE).edit()
		prefs.putBoolean("reconnect", reconnectSwitch.isChecked)
		prefs.putString("wsAddress", wsAddressInput.text.toString().ifBlank {"192.168.1.4"})
		prefs.putString("channel", channelInput.text.toString().ifBlank {""})
		prefs.putString("code", codeInput.text.toString().ifBlank {""})
		prefs.apply()
		runOnUiThread {
			val conn = connected.isNotBlank()
			deviceView.visibility = if (conn) View.VISIBLE else View.GONE
			titleText.visibility = if (conn) View.GONE else View.VISIBLE
			scanButton.visibility = if (conn) View.GONE else View.VISIBLE
			deviceListView.visibility = if (conn) View.GONE else View.VISIBLE
			statusText.visibility = if (conn) View.GONE else View.VISIBLE

			optionsView.visibility = View.GONE
		}
		Toast.makeText(this, "Options saved.", Toast.LENGTH_SHORT).show()
		readOptions()

		sendBroadcast(Intent("com.dmcroww.hrband.OPTIONS"))
		Log.d("Options", "ws: $wsAddress, conn: $autoReconnect")
	}

	private fun readOptions() {
		val prefs = getSharedPreferences("MyPrefs", MODE_PRIVATE)
		autoReconnect = prefs.getBoolean("reconnect", false)
		wsAddress = prefs.getString("wsAddress", "192.168.1.4").toString()
		channel = prefs.getString("channel", "").toString()
		code = prefs.getString("code", "").toString()
	}

	private fun openOptions() {
		runOnUiThread {
			deviceView.visibility = View.GONE
			titleText.visibility = View.GONE
			scanButton.visibility = View.GONE
			deviceListView.visibility = View.GONE
			statusText.visibility = View.GONE

			optionsView.visibility = View.VISIBLE
		}
	}

	private fun exitApp() {
		AlertDialog.Builder(this).setTitle("Exit App").setMessage("Are you sure you want to exit?").setPositiveButton("Yes") {_, _ ->
			stopService(Intent(this, BleService::class.java))
			finishAffinity()
			System.exit(0) // Kill the process to ensure the app is fully closed
		}.setNegativeButton("No", null).show()
	}

	override fun onDestroy() {
		super.onDestroy()
		unregisterReceiver(broadcastReceiver)
	}
}