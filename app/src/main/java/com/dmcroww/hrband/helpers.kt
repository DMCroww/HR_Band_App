package com.dmcroww.hrband

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

private var handler: Handler = Handler(Looper.getMainLooper())
private var webSocket: WebSocket? = null

class WSlink(private val service: BleService) {
	private var address: String = ""
	private var channel: String = ""
	private var code: String = ""
	private val id = "phoneclient"
	private val to = listOf("/heartrate", "/heartrate/text", "/heartrate/graph")

	fun connect(ip: String, channelName: String, authCode: String) {
		address = "ws://$ip"
		channel = channelName
		code = authCode
		init()
	}

	private fun init() {
		val client = OkHttpClient()
		val request = Request.Builder().url(address).build()

		val listener = WSlistener()
		webSocket = client.newWebSocket(request, listener)
	}

	inner class WSlistener: WebSocketListener() {
		override fun onOpen(webSocket: WebSocket, response: Response) {
			Log.d("WS", "Connected!")
			sendWS(listOf("server"), "keepalive")
		}

		override fun onMessage(webSocket: WebSocket, text: String) {
			Log.d("WS", text)
			try {
				val json = JSONObject(text)

				val type = json.optString("type")
				val from = json.getString("id")
				val data = json.opt("data") // Can be any type (String, Int, JSONObject, etc.)

				when (type) {
					"get" -> {
						when (data) {
							"heartrate" -> {
								replyWS(from, JSONArray(service.getStoredData()), "log")
							}

							"battery" -> {
								replyWS(from, service.getBattery(), "battery")
							}

							else -> Log.d("WS", "Unknown message data: $data")
						}
					}

					else -> Log.d("WS", "Unknown message type: $type")
				}
			} catch (e: Exception) {
				Log.e("WS", "Failed to parse message", e)
			}
		}

		override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
			// Handle connection closed
			Log.w("WS", "Closed! Reconnecting...")
			handler.postDelayed({init()}, 2000)
		}

		override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
			// Handle connection failure
			service.wsFailed()
			Log.e("WS", "Connection failed: ${t.message}. Retrying...")
			handler.postDelayed({init()}, 2000) // Retry after 2s
		}
	}

	fun sendWS(value: Any? = true, type: String = "heartrate") {
		to.forEach {
			val json = JSONObject().put("id", id).put("to", it).put("type", type).put("data", value).put("channel", channel).put("code", code)

			webSocket?.send(json.toString())
		}
	}

	fun replyWS(to: String, value: Any? = true, type: String = "heartrate") {
		val json = JSONObject().put("id", id).put("to", to).put("data", value).put("type", type).put("channel", channel).put("code", code)

		webSocket?.send(json.toString())
	}
}

//class OSCLink(private val ip: String, private val port: Int) {
//	private val address = InetAddress.getByName(ip)
//	private val socket = DatagramSocket()
//
//	fun sendHeartRate(heartRate: Int) {
//		val data = encodeOSCMessage("/chatbox/input", "===| $heartRate BPM |===", true)
//		val packet = DatagramPacket(data, data.size, address, port)
//		socket.send(packet)
//	}
//
//	private fun encodeOSCMessage(address: String, message: String, send: Boolean): ByteArray {
//		val addrBytes = address.toByteArray(Charsets.US_ASCII) + 0
//		val padding1 = (4 - addrBytes.size % 4) % 4
//		val typeTag = ",sT".toByteArray(Charsets.US_ASCII) + 0
//		val padding2 = (4 - typeTag.size % 4) % 4
//		val msgBytes = message.toByteArray(Charsets.US_ASCII) + 0
//		val padding3 = (4 - msgBytes.size % 4) % 4
//		val sendBytes = if (send) byteArrayOf(1, 0, 0, 0) else byteArrayOf(0, 0, 0, 0)
//
//		return addrBytes + ByteArray(padding1) + typeTag + ByteArray(padding2) + msgBytes + ByteArray(padding3) + sendBytes
//	}
//}

@SuppressLint("MissingPermission")
class DeviceAdapter(context: Context, private val devices: List<Array<String>>): ArrayAdapter<Array<String>>(context, android.R.layout.simple_list_item_2, devices) {
	override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
		val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
		val device = devices[position]

		view.findViewById<TextView>(android.R.id.text1).text = device[0]
		view.findViewById<TextView>(android.R.id.text2).text = device[1]

		return view
	}
}