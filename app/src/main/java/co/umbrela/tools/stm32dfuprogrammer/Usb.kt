/*
 * Copyright 2015 Umbrela Smart, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package co.umbrela.tools.stm32dfuprogrammer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

class Usb(private val mContext: Context) {
    private var mUsbManager: UsbManager? = null
    var usbDevice: UsbDevice? = null
        private set
    private var mConnection: UsbDeviceConnection? = null
    private var mInterface: UsbInterface? = null
    var deviceVersion = 0
        private set

    /* Callback Interface */
    interface OnUsbChangeListener {
        fun onUsbConnected()
    }

    fun setOnUsbChangeListener(l: OnUsbChangeListener?) {
        mOnUsbChangeListener = l
    }

    private var mOnUsbChangeListener: OnUsbChangeListener? = null

    /* Broadcast Receiver*/
    private val mUsbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            //call method to set up device communication
                            setDevice(device)
                            if (mOnUsbChangeListener != null) {
                                mOnUsbChangeListener!!.onUsbConnected()
                            } else {
                                // TODO
                            }
                        } else {
                            // TODO
                        }
                    } else {
                        Log.d(TAG, "permission denied for device $device")
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                synchronized(this) {
                    //request permission for just attached USB Device if it matches the VID/PID
                    requestPermission(mContext, USB_VENDOR_ID, USB_PRODUCT_ID)
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (usbDevice != null && usbDevice == device) {
                        release()
                    }
                }
            }
        }
    }

    fun getmUsbReceiver(): BroadcastReceiver {
        return mUsbReceiver
    }

    fun setUsbManager(usbManager: UsbManager?) {
        mUsbManager = usbManager
    }

    fun requestPermission(context: Context?, vendorId: Int, productId: Int) {
        // Setup Pending Intent
        val permissionIntent =
            PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), 0)
        val device = getUsbDevice(vendorId, productId)
        if (device != null) {
            mUsbManager!!.requestPermission(device, permissionIntent)
        }
    }

    private fun getUsbDevice(vendorId: Int, productId: Int): UsbDevice? {
        val deviceList = mUsbManager!!.deviceList
        val deviceIterator: Iterator<UsbDevice> = deviceList.values.iterator()
        var device: UsbDevice
        while (deviceIterator.hasNext()) {
            device = deviceIterator.next()
            if (device.vendorId == vendorId && device.productId == productId) {
                return device
            }
        }
        return null
    }

    fun release(): Boolean {
        var isReleased = false
        if (mConnection != null) {
            isReleased = mConnection!!.releaseInterface(mInterface)
            mConnection!!.close()
            mConnection = null
        }
        return isReleased
    }

    fun setDevice(device: UsbDevice?) {
        usbDevice = device

        // The first interface is the one we want
        mInterface =
            device!!.getInterface(0) // todo check when changing if alternative interface is changing
        if (device != null) {
            val connection = mUsbManager!!.openDevice(device)
            if (connection != null && connection.claimInterface(mInterface, true)) {
                Log.i(TAG, "open SUCCESS")
                mConnection = connection

                // get the bcdDevice version
                val rawDescriptor = mConnection!!.rawDescriptors
                deviceVersion = rawDescriptor[13].toInt() shl 8
                deviceVersion = deviceVersion or rawDescriptor[12].toInt()
                Log.i("USB", getDeviceInfo(device))
            } else {
                Log.e(TAG, "open FAIL")
                mConnection = null
            }
        }
    }

    val isConnected: Boolean
        get() = mConnection != null

    fun getDeviceInfo(device: UsbDevice?): String {
        if (device == null) return "No device found."
        val sb = StringBuilder()
        sb.append(
            """
            Model: ${device.deviceName}
            ID: ${device.deviceId} (0x${Integer.toHexString(device.deviceId)})
            Class: ${device.deviceClass}
            Subclass: ${device.deviceSubclass}
            Protocol: ${device.deviceProtocol}
            Vendor ID ${device.vendorId} (0x${Integer.toHexString(device.vendorId)})
            Product ID: ${device.productId} (0x${Integer.toHexString(device.productId)})
            Device Ver: 0x${Integer.toHexString(deviceVersion)}
            Interface count: ${device.interfaceCount}
            """.trimIndent()
        )
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            sb.append(
                """
                Interface: $usbInterface\n")
                Endpoint Count: ${usbInterface.endpointCount}
                """.trimIndent()
            )
            for (j in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(j)
                sb.append("Endpoint: $ep\n")
            }
        }
        return sb.toString()
    }

    /**
     * Performs a control transaction on endpoint zero for this device.
     * The direction of the transfer is determined by the request type.
     * If requestType & [android.hardware.usb.UsbConstants.USB_ENDPOINT_DIR_MASK] is
     * [android.hardware.usb.UsbConstants.USB_DIR_OUT], then the transfer is a write,
     * and if it is [android.hardware.usb.UsbConstants.USB_DIR_IN], then the transfer
     * is a read.
     *
     * @param requestType MSB selects direction, rest defines to whom request is addressed
     * @param request     DFU command ID
     * @param value       0 for commands, >0 for firmware blocks
     * @param index       often 0
     * @param buffer      buffer for data portion of transaction,
     * or null if no data needs to be sent or received
     * @param length      the length of the data to send or receive
     * @param timeout     50ms f
     * @return length of data transferred (or zero) for success,
     * or negative value for failure
     */
    fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
        timeout: Int
    ): Int {
        synchronized(this) {
            return mConnection!!.controlTransfer(
                requestType,
                request,
                value,
                index,
                buffer,
                length,
                timeout
            )
        }
    }

    companion object {
        const val TAG = "Umbrela Client: USB"

        /* USB DFU ID's (may differ by device) */
        const val USB_VENDOR_ID = 1155 // VID while in DFU mode 0x0483
        const val USB_PRODUCT_ID = 57105 // PID while in DFU mode 0xDF11
        const val ACTION_USB_PERMISSION = "co.umbrela.tools.stm32dfuprogrammer.USB_PERMISSION"
    }
}