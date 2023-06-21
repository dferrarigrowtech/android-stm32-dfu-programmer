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

class Dfu(private val deviceVid: Int, private val devicePid: Int) {
    private val dfuFile: DfuFile
    private var usb: Usb? = null
    private var deviceVersion //STM bootloader version
            = 0
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
    }// wrap whole array// set offset and limit of firmware// Check this (?)

    // Check this (?)
    // Check this (?)
    // create byte buffer and compare content
    // similar to verify()
    @get:Throws(Exception::class)
    private val isWrittenImageOk: Boolean
        private get() {
            dfuFile.elementLength = dfuFile.file!!.size // Check this (?)
            dfuFile.elementStartAddress = 0x8000000 // Check this (?)
            dfuFile.maxBlockSize = 2048 // Check this (?)
            val deviceFirmware = ByteArray(dfuFile.elementLength)
            val startTime = System.currentTimeMillis()
            readImage(deviceFirmware)
            // create byte buffer and compare content
            val fileFw = ByteBuffer.wrap(
                dfuFile.file,
                ELEMENT1_OFFSET,
                dfuFile.elementLength
            ) // set offset and limit of firmware
            val deviceFw = ByteBuffer.wrap(deviceFirmware) // wrap whole array
            val result = fileFw == deviceFw
            Log.i(TAG, "Verified completed in " + (System.currentTimeMillis() - startTime) + " ms")
            return result
        }

    fun massErase() {
        if (!isUsbConnected) return
        val dfuStatus = DfuStatus()
        val startTime = System.currentTimeMillis() // note current time
        try {
            do {
                clearStatus()
                getStatus(dfuStatus)
            } while (dfuStatus.bState.toInt() != STATE_DFU_IDLE)
            if (isDeviceProtected) {
                removeReadProtection()
                onStatusMsg("Read Protection removed. Device resets...Wait until it   re-enumerates ") // XXX This will reset the device
                return
            }
            massEraseCommand() // sent erase command request
            getStatus(dfuStatus) // initiate erase command, returns 'download busy' even if invalid address or ROP
            val pollingTime = dfuStatus.bwPollTimeout // note requested waiting time
            do {
                /* wait specified time before next getStatus call */
                Thread.sleep(pollingTime.toLong())
                clearStatus()
                getStatus(dfuStatus)
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
        private get() {
            if (usb != null && usb!!.isConnected) {
                return true
            }
            onStatusMsg("No device connected")
            return false
        }

    @Throws(Exception::class)
    private fun removeReadProtection() {
        val dfuStatus = DfuStatus()
        unProtectCommand()
        getStatus(dfuStatus)
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
        val Block = ByteArray(blockSize)
        val NumOfBlocks = dfuFile.elementLength / blockSize
        var blockNum: Int
        blockNum = 0
        while (blockNum < NumOfBlocks) {
            System.arraycopy(dfuFile.file, blockNum * blockSize + fileOffset, Block, 0, blockSize)
            // send out the block to device
            writeBlock(address, Block, blockNum)
            blockNum++
        }
        // check if last block is partial
        var remainder = dfuFile.elementLength - blockNum * blockSize
        if (remainder > 0) {
            System.arraycopy(dfuFile.file, blockNum * blockSize + fileOffset, Block, 0, remainder)
            // Pad with 0xFF so our CRC matches the ST Bootloader and the ULink's CRC
            while (remainder < Block.size) {
                Block[remainder++] = 0xFF.toByte()
            }
            // send out the block to device
            writeBlock(address, Block, blockNum)
        }
    }

    fun myVerify() {
        try {
            openFile()
            val result = isWrittenImageOk
            onStatusMsg(if (result) "Image Written is OK" else "Image written is NOT ok")
        } catch (e: Exception) {
            onStatusMsg("Error in verifying the image")
        }
    }

    @Throws(Exception::class)
    private fun readImage(deviceFw: ByteArray) {
        val dfuStatus = DfuStatus()
        val maxBlockSize = dfuFile.maxBlockSize
        val startAddress = dfuFile.elementStartAddress
        val block = ByteArray(maxBlockSize)
        var nBlock: Int
        var remLength = deviceFw.size
        val numOfBlocks = remLength / maxBlockSize
        do {
            clearStatus()
            getStatus(dfuStatus)
        } while (dfuStatus.bState.toInt() != STATE_DFU_IDLE)
        setAddressPointer(startAddress)
        getStatus(dfuStatus) // to execute
        getStatus(dfuStatus) //to verify
        if (dfuStatus.bState.toInt() == STATE_DFU_ERROR) {
            throw Exception("Start address not supported")
        }


        // will read full and last partial blocks ( NOTE: last partial block will be read with maxkblocksize)
        nBlock = 0
        while (nBlock <= numOfBlocks) {
            while (dfuStatus.bState.toInt() != STATE_DFU_IDLE) {        // todo if fails, maybe stop reading
                clearStatus()
                getStatus(dfuStatus)
            }
            upload(block, maxBlockSize, nBlock + 2)
            getStatus(dfuStatus)
            if (remLength >= maxBlockSize) {
                remLength -= maxBlockSize
                System.arraycopy(block, 0, deviceFw, nBlock * maxBlockSize, maxBlockSize)
            } else {
                System.arraycopy(block, 0, deviceFw, nBlock * maxBlockSize, remLength)
            }
            nBlock++
        }
    }

    @Throws(Exception::class)
    private fun openFile() {
        val extDownload: File
        var myFilePath: String? = null
        var myFileName: String? = null
        val fileInputStream: FileInputStream
        val myFile: File
        if (Environment.getExternalStorageState() != null) // todo not sure if this works
        {
            extDownload =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (extDownload.exists()) {
                val files = extDownload.listFiles()
                // todo support multiple dfu files in dir
                if (files.size > 0) {   // will select first dfu file found in dir
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
        myFile = File("$myFilePath/$myFileName")
        dfuFile.filePath = myFile.toString()
        dfuFile.file = ByteArray(myFile.length().toInt())

        //convert file into byte array
        fileInputStream = FileInputStream(myFile)
        fileInputStream.read(dfuFile.file)
        fileInputStream.close()
    }

    @Throws(Exception::class)
    private fun writeBlock(address: Int, block: ByteArray, blockNumber: Int) {
        val dfuStatus = DfuStatus()
        do {
            clearStatus()
            getStatus(dfuStatus)
        } while (dfuStatus.bState.toInt() != STATE_DFU_IDLE)
        if (0 == blockNumber) {
            setAddressPointer(address)
            getStatus(dfuStatus)
            getStatus(dfuStatus)
            if (dfuStatus.bState.toInt() == STATE_DFU_ERROR) {
                throw Exception("Start address not supported")
            }
        }
        do {
            clearStatus()
            getStatus(dfuStatus)
        } while (dfuStatus.bState.toInt() != STATE_DFU_IDLE)
        download(block, blockNumber + 2)
        getStatus(dfuStatus) // to execute
        if (dfuStatus.bState.toInt() != STATE_DFU_DOWNLOAD_BUSY) {
            throw Exception("error when downloading, was not busy ")
        }
        getStatus(dfuStatus) // to verify action
        if (dfuStatus.bState.toInt() == STATE_DFU_ERROR) {
            throw Exception("error when downloading, did not perform action")
        }
        while (dfuStatus.bState.toInt() != STATE_DFU_IDLE) {
            clearStatus()
            getStatus(dfuStatus)
        }
    }

    // to execute
    // to verify
    @get:Throws(Exception::class)
    private val isDeviceProtected: Boolean
        private get() {
            val dfuStatus = DfuStatus()
            var isProtected = false
            do {
                clearStatus()
                getStatus(dfuStatus)
            } while (dfuStatus.bState.toInt() != STATE_DFU_IDLE)
            setAddressPointer(mInternalFlashStartAddress)
            getStatus(dfuStatus) // to execute
            getStatus(dfuStatus) // to verify
            if (dfuStatus.bState.toInt() == STATE_DFU_ERROR) {
                isProtected = true
            }
            while (dfuStatus.bState.toInt() != STATE_DFU_IDLE) {
                clearStatus()
                getStatus(dfuStatus)
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
    private fun getStatus(status: DfuStatus) {
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
        status.bStatus = buffer[0] // state during request
        status.bState = buffer[4] // state after request
        status.bwPollTimeout = buffer[3].toInt() and 0xFF shl 16
        status.bwPollTimeout = status.bwPollTimeout or (buffer[2].toInt() and 0xFF shl 8)
        status.bwPollTimeout = status.bwPollTimeout or (buffer[1].toInt() and 0xFF)
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
        var bStatus // state during request
                : Byte = 0
        var bwPollTimeout // minimum time in ms before next getStatus call should be made
                = 0
        var bState // state after request
                : Byte = 0
    }

    // holds all essential information for the Dfu File
    private inner class DfuFile {
        var filePath: String? = null
        var file: ByteArray? = null
        var PID = 0
        var VID = 0
        var BootVersion = 0
        var maxBlockSize = 1024
        var elementStartAddress = 0
        var elementLength = 0
        var TargetName: String? = null
        var TargetSize = 0
        var NumElements = 0
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

        // The following 4 parameters are just for verify function
        const val TARGET_NAME_START = 22
        const val TARGET_NAME_MAX_END = 276
        const val TARGET_SIZE = 277
        const val TARGET_NUM_ELEMENTS = 281

        // Device specific parameters
        const val mInternalFlashString =
            "@Internal Flash  /0x08000000/04*016Kg,01*064Kg,07*128Kg" // STM32F405RG, 1MB Flash, 192KB SRAM
        const val mInternalFlashSize = 1048575
        const val mInternalFlashStartAddress = 0x08000000
        const val mOptionByteStartAddress = 0x1FFFC000
        private const val OPT_BOR_1 = 0x08
        private const val OPT_BOR_2 = 0x04
        private const val OPT_BOR_3 = 0x00
        private const val OPT_BOR_OFF = 0x0C
        private const val OPT_WDG_SW = 0x20
        private const val OPT_nRST_STOP = 0x40
        private const val OPT_nRST_STDBY = 0x80
        private const val OPT_RDP_OFF = 0xAA00
        private const val OPT_RDP_1 = 0x3300
    }
}