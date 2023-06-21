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

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Message
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContentProviderCompat.requireContext
import co.umbrela.tools.stm32dfuprogrammer.databinding.ActivityMainBinding
import co.umbrela.tools.stm32dfuprogrammer.BuildConfig


class MainActivity : AppCompatActivity(), Dfu.DfuListener, Usb.OnUsbChangeListener,
    Handler.Callback {

    private val REQUEST_EXTERNAL_STORAGE = 1
    private val PERMISSIONS_STORAGE = arrayOf<String>(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.MANAGE_EXTERNAL_STORAGE,
    )


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

        verifyStoragePermissions(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            // Access to all files
            val uri: Uri = Uri.parse("package:" + BuildConfig.APPLICATION_ID)
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
            startActivity(intent)
        }
    }

    fun verifyStoragePermissions(activity: Activity?) {
        // Check if we have write permission
        val permission = ActivityCompat.checkSelfPermission(
            activity!!,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                activity,
                PERMISSIONS_STORAGE,
                REQUEST_EXTERNAL_STORAGE
            )
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