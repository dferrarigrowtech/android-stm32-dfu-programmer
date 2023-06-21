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

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

class Dfu {
    private val dfuFile: DfuFile
    private var usb: Usb? = null
    private var deviceVersion = 0 // STM bootloader version

    private val listeners: MutableList<DfuListener> = ArrayList()

    interface DfuListener {
        fun onStatusMsg(msg: String?)
    }

    init {
        dfuFile = DfuFile()
    }

    private fun onStatusMsg(msg: String) {
        for (listener in listeners) {
            listener.onStatusMsg(msg)
        }
    }

    fun setListener(listener: DfuListener?) {
        requireNotNull(listener) { "Listener is null" }
        listeners.add(listener)
    }

    fun setUsb(usb: Usb?) {
        this.usb = usb
        deviceVersion = this.usb!!.deviceVersion
    }

    // create byte buffer and compare content
    @get:Throws(Exception::class)
    private val isWrittenImageOk: Boolean
        get() {
            dfuFile.elementLength = dfuFile.file!!.size // Check this (?)
            dfuFile.elementStartAddress = 0x8000000 // Check this (?)
            dfuFile.maxBlockSize = 2048 // Check this (?)
            val startTime = System.currentTimeMillis()
            val deviceFirmware = readImage(dfuFile.elementLength)
            // create byte buffer and compare content
            val fileFw = ByteBuffer.wrap(
                dfuFile.file!!,
                ELEMENT1_OFFSET,
                dfuFile.elementLength
            ) // set offset and limit of firmware
            val deviceFw = ByteBuffer.wrap(deviceFirmware) // wrap whole array
            Log.i(TAG, "Verified completed in " + (System.currentTimeMillis() - startTime) + " ms")
            return fileFw == deviceFw
        }

    fun massErase() {
        if (!isUsbConnected) return
        var dfuStatus : DfuStatus
        val startTime = System.currentTimeMillis() // note current time
        try {
            do {
                clearStatus()
                dfuStatus = getStatus()
            } while (dfuStatus.bState.toInt() != STATE_DFU_IDLE)
            if (isDeviceProtected) {
                removeReadProtection()
                onStatusMsg("Read Protection removed. Device resets...Wait until it   re-enumerates ") // XXX This will reset the device
                return
            }
            massEraseCommand() // sent erase command request
            dfuStatus = getStatus() // initiate erase command, returns 'download busy' even if invalid address or ROP
            val pollingTime = dfuStatus.bwPollTimeout // note requested waiting time
            do {
                /* wait specified time before next getStatus call */
                Thread.sleep(pollingTime.toLong())
                clearStatus()
                dfuStatus = getStatus()
            } while (dfuStatus.bState.toInt() != STATE_DFU_IDLE)
            onStatusMsg("Mass erase completed in " + (System.currentTimeMillis() - startTime) + " ms")
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } catch (e: Exception) {
            onStatusMsg(e.toString())
        }
    }

    fun program() {
        if (!isUsbConnected) return
        try {
            if (isDeviceProtected) {
                onStatusMsg("Device is Read-Protected...First Mass Erase")
                return
            }
            openFile()
            onStatusMsg(
                """
                File Path: ${dfuFile.filePath}
                File Size: ${dfuFile.file!!.size} Bytes 
                ElementAddress: 0x" + Integer.toHexString(dfuFile.elementStartAddress))
                ElementSize: ${dfuFile.elementLength} Bytes
                Start writing file in blocks of ${dfuFile.maxBlockSize} Bytes 
                """.trimIndent()
            )
            val startTime = System.currentTimeMillis()
            writeImage()
            onStatusMsg("Programming completed in ${System.currentTimeMillis() - startTime} ms")
        } catch (e: Exception) {
            e.printStackTrace()
            onStatusMsg(e.toString())
        }
    }

    // check if usb device is active
    private val isUsbConnected: Boolean
        get() {
            if (usb != null && usb!!.isConnected) {
                return true
            }
            onStatusMsg("No device connected")
            return false
        }

    @Throws(Exception::class)
    private fun removeReadProtection() {
        unProtectCommand()
        val dfuStatus = getStatus()
        if (dfuStatus.bState.toInt() != STATE_DFU_DOWNLOAD_BUSY) {
            throw Exception("Failed to execute unprotect command")
        }
        usb!!.release() // XXX device will self-reset
        Log.i(TAG, "USB was released")
    }

    @Throws(Exception::class)
    private fun writeImage() {
        dfuFile.elementLength = dfuFile.file!!.size // Check this (?)
        dfuFile.elementStartAddress = 0x8000000 // Check this (?)
        dfuFile.maxBlockSize = 2048 // Check this (?)
        val address = dfuFile.elementStartAddress // flash start address
        val fileOffset = ELEMENT1_OFFSET // index offset of file
        val blockSize = dfuFile.maxBlockSize // max block size
        val block = ByteArray(blockSize)
        val numOfBlocks = dfuFile.elementLength / blockSize
        var blockNum = 0
        while (blockNum < numOfBlocks) {
            System.arraycopy(dfuFile.file!!, blockNum * blockSize + fileOffset, block, 0, blockSize)
            // send out the block to device
            writeBlock(address, block, blockNum)
            blockNum++
        }
        // check if last block is partial
        var remainder = dfuFile.elementLength - blockNum * blockSize
        if (remainder > 0) {
            System.arraycopy(dfuFile.file!!, blockNum * blockSize + fileOffset, block, 0, remainder)
            // Pad with 0xFF so our CRC matches the ST Bootloader and the ULink's CRC
            while (remainder < block.size) {
                block[remainder++] = 0xFF.toByte()
            }
            // send out the block to device
            writeBlock(address, block, blockNum)
        }
    }

    fun verify() {
        try {
            openFile()
            val result = isWrittenImageOk
            onStatusMsg(if (result) "Image Written is OK" else "Image written is NOT ok")
        } catch (e: Exception) {
            onStatusMsg("Error in verifying the image")
        }
    }

    @Throws(Exception::class)
    private fun readImage(bytesToRead : Int) : ByteArray {
        var dfuStatus: DfuStatus
        val maxBlockSize = dfuFile.maxBlockSize
        val startAddress = dfuFile.elementStartAddress
        val block = ByteArray(maxBlockSize)
        var remLength = bytesToRead
        val numOfBlocks = remLength / maxBlockSize
        do {
            clearStatus()
            dfuStatus = getStatus()
        } while (dfuStatus.bState.toInt() != STATE_DFU_IDLE)
        setAddressPointer(startAddress)
        getStatus()             // to execute
        dfuStatus = getStatus() //to verify
        if (dfuStatus.bState.toInt() == STATE_DFU_ERROR) {
            throw Exception("Start address not supported")
        }

        // will read full and last partial blocks ( NOTE: last partial block will be read with maxkblocksize)
        var nBlock = 0

        val deviceFw = ByteArray(remLength)
        while (nBlock <= numOfBlocks) {
            while (dfuStatus.bState.toInt() != STATE_DFU_IDLE) {        // todo if fails, maybe stop reading
                clearStatus()
                dfuStatus = getStatus()
            }
            upload(block, maxBlockSize, nBlock + 2)
            dfuStatus = getStatus()
            if (remLength >= maxBlockSize) {
                remLength -= maxBlockSize
                System.arraycopy(block, 0, deviceFw, nBlock * maxBlockSize, maxBlockSize)
            } else {
                System.arraycopy(block, 0, deviceFw, nBlock * maxBlockSize, remLength)
            }
            nBlock++
        }
        return deviceFw
    }

    @Throws(Exception::class)
    private fun openFile() {
        val extDownload: File
        var myFilePath: String? = null
        var myFileName: String? = null
        val fileInputStream: FileInputStream
        if (Environment.getExternalStorageState() != null) // todo not sure if this works
        {
            extDownload =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (extDownload.exists()) {
                val files = extDownload.listFiles()
                if (files!!.isNotEmpty()) {   // will select first dfu file found in dir
                    for (file in files) {
                        if (file.name.endsWith(".bin")) {
                            myFilePath = extDownload.toString()
                            myFileName = file.name
                            break
                        }
                    }
                }
            }
        }
        if (myFileName == null) throw Exception("No .bin file found in Download Folder")
        val myFile = File("$myFilePath/$myFileName")
        dfuFile.filePath = myFile.toString()
        dfuFile.file = ByteArray(myFile.length().toInt())

        //convert file into byte array
        fileInputStream = FileInputStream(myFile)
        fileInputStream.read(dfuFile.file)
        fileInputStream.close()
    }

    @Throws(Exception::class)
    private fun writeBlock(address: Int, block: ByteArray, blockNumber: Int) {
        var dfuStatus : DfuStatus
        do {
            clearStatus()
            dfuStatus = getStatus()
        } while (dfuStatus.bState.toInt() != STATE_DFU_IDLE)
        if (0 == blockNumber) {
            setAddressPointer(address)
            getStatus()
            dfuStatus = getStatus()
            if (dfuStatus.bState.toInt() == STATE_DFU_ERROR) {
                throw Exception("Start address not supported")
            }
        }
        do {
            clearStatus()
            dfuStatus = getStatus()
        } while (dfuStatus.bState.toInt() != STATE_DFU_IDLE)
        download(block, blockNumber + 2)
        dfuStatus = getStatus() // to execute
        if (dfuStatus.bState.toInt() != STATE_DFU_DOWNLOAD_BUSY) {
            throw Exception("error when downloading, was not busy ")
        }
        dfuStatus = getStatus() // to verify action
        if (dfuStatus.bState.toInt() == STATE_DFU_ERROR) {
            throw Exception("error when downloading, did not perform action")
        }
        while (dfuStatus.bState.toInt() != STATE_DFU_IDLE) {
            clearStatus()
            dfuStatus = getStatus()
        }
    }

    @get:Throws(Exception::class)
    private val isDeviceProtected: Boolean
        get() {
            var dfuStatus : DfuStatus
            var isProtected = false
            do {
                clearStatus()
                dfuStatus = getStatus()
            } while (dfuStatus.bState.toInt() != STATE_DFU_IDLE)
            setAddressPointer(mInternalFlashStartAddress)
            getStatus()             // to execute
            dfuStatus = getStatus() // to verify
            if (dfuStatus.bState.toInt() == STATE_DFU_ERROR) {
                isProtected = true
            }
            while (dfuStatus.bState.toInt() != STATE_DFU_IDLE) {
                clearStatus()
                dfuStatus = getStatus()
            }
            return isProtected
        }

    @Throws(Exception::class)
    private fun massEraseCommand() {
        val buffer = ByteArray(1)
        buffer[0] = 0x41
        download(buffer)
    }

    @Throws(Exception::class)
    private fun unProtectCommand() {
        val buffer = ByteArray(1)
        buffer[0] = 0x92.toByte()
        download(buffer)
    }

    @Throws(Exception::class)
    private fun setAddressPointer(Address: Int) {
        val buffer = ByteArray(5)
        buffer[0] = 0x21
        buffer[1] = (Address and 0xFF).toByte()
        buffer[2] = (Address shr 8 and 0xFF).toByte()
        buffer[3] = (Address shr 16 and 0xFF).toByte()
        buffer[4] = (Address shr 24 and 0xFF).toByte()
        download(buffer)
    }

    @Throws(Exception::class)
    private fun getStatus() : DfuStatus {
        val buffer = ByteArray(6)
        val length = usb!!.controlTransfer(
            DFU_RequestType or USB_DIR_IN,
            DFU_GETSTATUS,
            0,
            0,
            buffer,
            6,
            500
        )
        if (length < 0) {
            throw Exception("USB Failed during getStatus")
        }

        val status = DfuStatus()
        status.bStatus = buffer[0] // state during request
        status.bState = buffer[4] // state after request
        status.bwPollTimeout = buffer[3].toInt() and 0xFF shl 16
        status.bwPollTimeout = status.bwPollTimeout or (buffer[2].toInt() and 0xFF shl 8)
        status.bwPollTimeout = status.bwPollTimeout or (buffer[1].toInt() and 0xFF)
        return status
    }

    @Throws(Exception::class)
    private fun clearStatus() {
        val length = usb!!.controlTransfer(DFU_RequestType, DFU_CLRSTATUS, 0, 0, null, 0, 0)
        if (length < 0) {
            throw Exception("USB Failed during clearStatus")
        }
    }

    // use for commands
    @Throws(Exception::class)
    private fun download(data: ByteArray) {
        val len = usb!!.controlTransfer(DFU_RequestType, DFU_DNLOAD, 0, 0, data, data.size, 50)
        if (len < 0) {
            throw Exception("USB Failed during command download")
        }
    }

    // use for firmware download
    @Throws(Exception::class)
    private fun download(data: ByteArray, nBlock: Int) {
        val len = usb!!.controlTransfer(DFU_RequestType, DFU_DNLOAD, nBlock, 0, data, data.size, 0)
        if (len < 0) {
            throw Exception("USB failed during firmware download")
        }
    }

    @Throws(Exception::class)
    private fun upload(data: ByteArray, length: Int, blockNum: Int) {
        val len = usb!!.controlTransfer(
            DFU_RequestType or USB_DIR_IN,
            DFU_UPLOAD,
            blockNum,
            0,
            data,
            length,
            100
        )
        if (len < 0) {
            throw Exception("USB comm failed during upload")
        }
    }

    // stores the result of a GetStatus DFU request
    private inner class DfuStatus {
        var bStatus: Byte = 0  // state during request
        var bwPollTimeout = 0   // minimum time in ms before next getStatus call should be made
        var bState: Byte = 0   // state after request
    }

    // holds all essential information for the Dfu File
    private inner class DfuFile {
        var filePath: String? = null
        var file: ByteArray? = null
        var maxBlockSize = 1024
        var elementStartAddress = 0
        var elementLength = 0
    }

    companion object {
        private const val TAG = "Dfu"
        private const val USB_DIR_OUT = 0
        private const val USB_DIR_IN = 128 //0x80
        private const val DFU_RequestType = 0x21 // '2' => Class request ; '1' => to interface
        private const val STATE_IDLE = 0x00
        private const val STATE_DETACH = 0x01
        private const val STATE_DFU_IDLE = 0x02
        private const val STATE_DFU_DOWNLOAD_SYNC = 0x03
        private const val STATE_DFU_DOWNLOAD_BUSY = 0x04
        private const val STATE_DFU_DOWNLOAD_IDLE = 0x05
        private const val STATE_DFU_MANIFEST_SYNC = 0x06
        private const val STATE_DFU_MANIFEST = 0x07
        private const val STATE_DFU_MANIFEST_WAIT_RESET = 0x08
        private const val STATE_DFU_UPLOAD_IDLE = 0x09
        private const val STATE_DFU_ERROR = 0x0A
        private const val STATE_DFU_UPLOAD_SYNC = 0x91
        private const val STATE_DFU_UPLOAD_BUSY = 0x92

        // DFU Commands, request ID code when using controlTransfers
        private const val DFU_DETACH = 0x00
        private const val DFU_DNLOAD = 0x01
        private const val DFU_UPLOAD = 0x02
        private const val DFU_GETSTATUS = 0x03
        private const val DFU_CLRSTATUS = 0x04
        private const val DFU_GETSTATE = 0x05
        private const val DFU_ABORT = 0x06
        const val ELEMENT1_OFFSET = 0 // constant offset in file array where image data starts

        // Device specific parameters
        const val mInternalFlashStartAddress = 0x08000000
    }
}