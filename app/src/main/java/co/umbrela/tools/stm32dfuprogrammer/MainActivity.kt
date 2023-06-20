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

package co.umbrela.tools.stm32dfuprogrammer;

import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import co.umbrela.tools.stm32dfuprogrammer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), Dfu.DfuListener, Usb.OnUsbChangeListener,
    Handler.Callback {


    private lateinit var binding: ActivityMainBinding
    private lateinit var dfu: Dfu
    private lateinit var usb: Usb
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dfu = Dfu(Usb.USB_VENDOR_ID, Usb.USB_PRODUCT_ID)
        dfu.setListener(this)

        status = binding.tvStatus

        binding.btnMassErase.setOnClickListener {
            dfu.massErase()
        }

        binding.btnProgram.setOnClickListener {
            dfu.program()
        }

        binding.btnForceErase.setOnClickListener {
            dfu.fastOperations()
        }

        binding.btnVerify.setOnClickListener {
            dfu.verify()
        }

        binding.btnEnterDfu.setOnClickListener {
            Outputs.enterDfuMode()
        }

        binding.btnLeaveDfu.setOnClickListener {
            dfu.leaveDfuMode()
        }

        binding.btnReleaseReset.setOnClickListener {
            Outputs.enterNormalMode()
        }
    }

    override fun onStart() {
        super.onStart()

        /* Setup USB */usb = Usb(this)
        usb.setUsbManager(getSystemService(USB_SERVICE) as UsbManager)
        usb.setOnUsbChangeListener(this)

        // Handle two types of intents. Device attachment and permission
        registerReceiver(usb.getmUsbReceiver(), IntentFilter(Usb.ACTION_USB_PERMISSION))
        registerReceiver(usb.getmUsbReceiver(), IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        registerReceiver(usb.getmUsbReceiver(), IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))


        // Handle case where USB device is connected before app launches;
        // hence ACTION_USB_DEVICE_ATTACHED will not occur so we explicitly call for permission
        usb.requestPermission(this, Usb.USB_VENDOR_ID, Usb.USB_PRODUCT_ID)
    }

    override fun onStop() {
        super.onStop()

        /* USB */dfu.setUsb(null)
        usb.release()
        try {
            unregisterReceiver(usb.getmUsbReceiver())
        } catch (e: IllegalArgumentException) { /* Already unregistered */
        }
    }

    override fun onStatusMsg(msg: String?) {
        // TODO since we are appending we should make the TextView scrollable like a log
        status.append(msg)
    }

    override fun handleMessage(p0: Message): Boolean {
        return false
    }

    override fun onUsbConnected() {
        val deviceInfo = usb.getDeviceInfo(usb.usbDevice)
        status.text = deviceInfo
        dfu.setUsb(usb)
    }
}