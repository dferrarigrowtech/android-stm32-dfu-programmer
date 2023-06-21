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

import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public class Dfu {
    private static final String TAG = "Dfu";
    private final static int USB_DIR_OUT = 0;
    private final static int USB_DIR_IN = 128;       //0x80
    private final static int DFU_RequestType = 0x21;  // '2' => Class request ; '1' => to interface

    private final static int STATE_IDLE = 0x00;
    private final static int STATE_DETACH = 0x01;
    private final static int STATE_DFU_IDLE = 0x02;
    private final static int STATE_DFU_DOWNLOAD_SYNC = 0x03;
    private final static int STATE_DFU_DOWNLOAD_BUSY = 0x04;
    private final static int STATE_DFU_DOWNLOAD_IDLE = 0x05;
    private final static int STATE_DFU_MANIFEST_SYNC = 0x06;
    private final static int STATE_DFU_MANIFEST = 0x07;
    private final static int STATE_DFU_MANIFEST_WAIT_RESET = 0x08;
    private final static int STATE_DFU_UPLOAD_IDLE = 0x09;
    private final static int STATE_DFU_ERROR = 0x0A;
    private final static int STATE_DFU_UPLOAD_SYNC = 0x91;
    private final static int STATE_DFU_UPLOAD_BUSY = 0x92;

    // DFU Commands, request ID code when using controlTransfers
    private final static int DFU_DETACH = 0x00;
    private final static int DFU_DNLOAD = 0x01;
    private final static int DFU_UPLOAD = 0x02;
    private final static int DFU_GETSTATUS = 0x03;
    private final static int DFU_CLRSTATUS = 0x04;
    private final static int DFU_GETSTATE = 0x05;
    private final static int DFU_ABORT = 0x06;

    public final static int ELEMENT1_OFFSET = 0;  // constant offset in file array where image data starts

    // The following 4 parameters are just for verify function
    public final static int TARGET_NAME_START = 22;
    public final static int TARGET_NAME_MAX_END = 276;
    public final static int TARGET_SIZE = 277;
    public final static int TARGET_NUM_ELEMENTS = 281;


    // Device specific parameters
    public static final String mInternalFlashString = "@Internal Flash  /0x08000000/04*016Kg,01*064Kg,07*128Kg"; // STM32F405RG, 1MB Flash, 192KB SRAM
    public static final int mInternalFlashSize = 1048575;
    public static final int mInternalFlashStartAddress = 0x08000000;
    public static final int mOptionByteStartAddress = 0x1FFFC000;
    private static final int OPT_BOR_1 = 0x08;
    private static final int OPT_BOR_2 = 0x04;
    private static final int OPT_BOR_3 = 0x00;
    private static final int OPT_BOR_OFF = 0x0C;
    private static final int OPT_WDG_SW = 0x20;
    private static final int OPT_nRST_STOP = 0x40;
    private static final int OPT_nRST_STDBY = 0x80;
    private static final int OPT_RDP_OFF = 0xAA00;
    private static final int OPT_RDP_1 = 0x3300;


    private final int deviceVid;
    private final int devicePid;
    private final DfuFile dfuFile;

    private Usb usb;
    private int deviceVersion;  //STM bootloader version

    private final List<DfuListener> listeners = new ArrayList<>();

    public interface DfuListener {
        void onStatusMsg(String msg);
    }

    public Dfu(int usbVendorId, int usbProductId) {
        this.deviceVid = usbVendorId;
        this.devicePid = usbProductId;

        dfuFile = new DfuFile();
    }

    private void onStatusMsg(final String msg) {
        for (DfuListener listener : listeners) {
            listener.onStatusMsg(msg);
        }
    }

    public void setListener(final DfuListener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener is null");
        listeners.add(listener);
    }

    public void setUsb(Usb usb) {
        this.usb = usb;
        this.deviceVersion = this.usb.getDeviceVersion();
    }


    // similar to verify()
    private boolean isWrittenImageOk() throws Exception {
        dfuFile.elementLength = dfuFile.file.length; // Check this (?)
        dfuFile.elementStartAddress = 0x8000000; // Check this (?)
        dfuFile.maxBlockSize = 2048; // Check this (?)
        byte[] deviceFirmware = new byte[dfuFile.elementLength];
        long startTime = System.currentTimeMillis();
        readImage(deviceFirmware);
        // create byte buffer and compare content
        ByteBuffer fileFw = ByteBuffer.wrap(dfuFile.file, ELEMENT1_OFFSET, dfuFile.elementLength);    // set offset and limit of firmware
        ByteBuffer deviceFw = ByteBuffer.wrap(deviceFirmware);    // wrap whole array
        boolean result = fileFw.equals(deviceFw);
        Log.i(TAG, "Verified completed in " + (System.currentTimeMillis() - startTime) + " ms");
        return result;
    }

    public void massErase() {

        if (!isUsbConnected()) return;

        DfuStatus dfuStatus = new DfuStatus();
        long startTime = System.currentTimeMillis();  // note current time

        try {
            do {
                clearStatus();
                getStatus(dfuStatus);
            } while (dfuStatus.bState != STATE_DFU_IDLE);

            if (isDeviceProtected()) {
                removeReadProtection();
                onStatusMsg("Read Protection removed. Device resets...Wait until it   re-enumerates "); // XXX This will reset the device
                return;
            }

            massEraseCommand();                 // sent erase command request
            getStatus(dfuStatus);                // initiate erase command, returns 'download busy' even if invalid address or ROP
            int pollingTime = dfuStatus.bwPollTimeout;  // note requested waiting time
            do {
            /* wait specified time before next getStatus call */
                Thread.sleep(pollingTime);
                clearStatus();
                getStatus(dfuStatus);
            } while (dfuStatus.bState != STATE_DFU_IDLE);
            onStatusMsg("Mass erase completed in " + (System.currentTimeMillis() - startTime) + " ms");

        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (Exception e) {
            onStatusMsg(e.toString());
        }
    }

    public void program() {

        if (!isUsbConnected()) return;

        try {
            if (isDeviceProtected()) {
                onStatusMsg("Device is Read-Protected...First Mass Erase");
                return;
            }

            openFile();
            onStatusMsg("File Path: " + dfuFile.filePath + "\n");
            onStatusMsg("File Size: " + dfuFile.file.length + " Bytes \n");
            onStatusMsg("ElementAddress: 0x" + Integer.toHexString(dfuFile.elementStartAddress));
            onStatusMsg("\tElementSize: " + dfuFile.elementLength + " Bytes\n");
            onStatusMsg("Start writing file in blocks of " + dfuFile.maxBlockSize + " Bytes \n");

            long startTime = System.currentTimeMillis();
            writeImage();
            onStatusMsg("Programming completed in " + (System.currentTimeMillis() - startTime) + " ms\n");

        } catch (Exception e) {
            e.printStackTrace();
            onStatusMsg(e.toString());
        }
    }

    // check if usb device is active
    private boolean isUsbConnected() {
        if (usb != null && usb.isConnected()) {
            return true;
        }
        onStatusMsg("No device connected");
        return false;
    }

    private void removeReadProtection() throws Exception {
        DfuStatus dfuStatus = new DfuStatus();
        unProtectCommand();
        getStatus(dfuStatus);
        if (dfuStatus.bState != STATE_DFU_DOWNLOAD_BUSY) {
            throw new Exception("Failed to execute unprotect command");
        }
        usb.release();     // XXX device will self-reset
        Log.i(TAG, "USB was released");
    }

    private void writeImage() throws Exception {
        dfuFile.elementLength = dfuFile.file.length; // Check this (?)
        dfuFile.elementStartAddress = 0x8000000; // Check this (?)
        dfuFile.maxBlockSize = 2048; // Check this (?)
        int address = dfuFile.elementStartAddress;  // flash start address
        int fileOffset = ELEMENT1_OFFSET;   // index offset of file
        int blockSize = dfuFile.maxBlockSize;   // max block size
        byte[] Block = new byte[blockSize];
        int NumOfBlocks = dfuFile.elementLength / blockSize;
        int blockNum;

        for (blockNum = 0; blockNum < NumOfBlocks; blockNum++) {
            System.arraycopy(dfuFile.file, (blockNum * blockSize) + fileOffset, Block, 0, blockSize);
            // send out the block to device
            writeBlock(address, Block, blockNum);
        }
        // check if last block is partial
        int remainder = dfuFile.elementLength - (blockNum * blockSize);
        if (remainder > 0) {
            System.arraycopy(dfuFile.file, (blockNum * blockSize) + fileOffset, Block, 0, remainder);
            // Pad with 0xFF so our CRC matches the ST Bootloader and the ULink's CRC
            while (remainder < Block.length) {
                Block[remainder++] = (byte) 0xFF;
            }
            // send out the block to device
            writeBlock(address, Block, blockNum);
        }
    }

    public void myVerify() {
        try{
            openFile();
            boolean result = isWrittenImageOk();
            onStatusMsg(result ? "Image Written is OK" : "Image written is NOT ok");
        } catch (Exception e) {
            onStatusMsg("Error in verifying the image");
        }

    }


    private void readImage(byte[] deviceFw) throws Exception {

        DfuStatus dfuStatus = new DfuStatus();
        int maxBlockSize = dfuFile.maxBlockSize;
        int startAddress = dfuFile.elementStartAddress;
        byte[] block = new byte[maxBlockSize];
        int nBlock;
        int remLength = deviceFw.length;
        int numOfBlocks = remLength / maxBlockSize;

        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        setAddressPointer(startAddress);
        getStatus(dfuStatus);   // to execute
        getStatus(dfuStatus);   //to verify
        if (dfuStatus.bState == STATE_DFU_ERROR) {
            throw new Exception("Start address not supported");
        }


        // will read full and last partial blocks ( NOTE: last partial block will be read with maxkblocksize)
        for (nBlock = 0; nBlock <= numOfBlocks; nBlock++) {

            while (dfuStatus.bState != STATE_DFU_IDLE) {        // todo if fails, maybe stop reading
                clearStatus();
                getStatus(dfuStatus);
            }
            upload(block, maxBlockSize, nBlock + 2);
            getStatus(dfuStatus);

            if (remLength >= maxBlockSize) {
                remLength -= maxBlockSize;
                System.arraycopy(block, 0, deviceFw, (nBlock * maxBlockSize), maxBlockSize);
            } else {
                System.arraycopy(block, 0, deviceFw, (nBlock * maxBlockSize), remLength);
            }
        }
    }

    private void openFile() throws Exception {

        File extDownload;
        String myFilePath = null;
        String myFileName = null;
        FileInputStream fileInputStream;
        File myFile;

        if (Environment.getExternalStorageState() != null)  // todo not sure if this works
        {
            extDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

            if (extDownload.exists()) {
                File[] files = extDownload.listFiles();
                // todo support multiple dfu files in dir
                if (files.length > 0) {   // will select first dfu file found in dir
                    for (File file : files) {
                        if (file.getName().endsWith(".bin")) {
                            myFilePath = extDownload.toString();
                            myFileName = file.getName();
                            break;
                        }
                    }
                }
            }
        }


        if (myFileName == null) throw new Exception("No .bin file found in Download Folder");

        myFile = new File(myFilePath + "/" + myFileName);
        dfuFile.filePath = myFile.toString();
        dfuFile.file = new byte[(int) myFile.length()];

        //convert file into byte array
        fileInputStream = new FileInputStream(myFile);
        fileInputStream.read(dfuFile.file);
        fileInputStream.close();
    }

    private void writeBlock(int address, byte[] block, int blockNumber) throws Exception {

        DfuStatus dfuStatus = new DfuStatus();

        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        if (0 == blockNumber) {
            setAddressPointer(address);
            getStatus(dfuStatus);
            getStatus(dfuStatus);
            if (dfuStatus.bState == STATE_DFU_ERROR) {
                throw new Exception("Start address not supported");
            }
        }

        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        download(block, (blockNumber + 2));
        getStatus(dfuStatus);   // to execute
        if (dfuStatus.bState != STATE_DFU_DOWNLOAD_BUSY) {
            throw new Exception("error when downloading, was not busy ");
        }
        getStatus(dfuStatus);   // to verify action
        if (dfuStatus.bState == STATE_DFU_ERROR) {
            throw new Exception("error when downloading, did not perform action");
        }

        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }
    }

    private boolean isDeviceProtected() throws Exception {

        DfuStatus dfuStatus = new DfuStatus();
        boolean isProtected = false;

        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        setAddressPointer(mInternalFlashStartAddress);
        getStatus(dfuStatus); // to execute
        getStatus(dfuStatus);   // to verify

        if (dfuStatus.bState == STATE_DFU_ERROR) {
            isProtected = true;
        }
        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }
        return isProtected;
    }

    private void massEraseCommand() throws Exception {
        byte[] buffer = new byte[1];
        buffer[0] = 0x41;
        download(buffer);
    }

    private void unProtectCommand() throws Exception {
        byte[] buffer = new byte[1];
        buffer[0] = (byte) 0x92;
        download(buffer);
    }

    private void setAddressPointer(int Address) throws Exception {
        byte[] buffer = new byte[5];
        buffer[0] = 0x21;
        buffer[1] = (byte) (Address & 0xFF);
        buffer[2] = (byte) ((Address >> 8) & 0xFF);
        buffer[3] = (byte) ((Address >> 16) & 0xFF);
        buffer[4] = (byte) ((Address >> 24) & 0xFF);
        download(buffer);
    }

    private void getStatus(DfuStatus status) throws Exception {
        byte[] buffer = new byte[6];
        int length = usb.controlTransfer(DFU_RequestType | USB_DIR_IN, DFU_GETSTATUS, 0, 0, buffer, 6, 500);

        if (length < 0) {
            throw new Exception("USB Failed during getStatus");
        }
        status.bStatus = buffer[0]; // state during request
        status.bState = buffer[4]; // state after request
        status.bwPollTimeout = (buffer[3] & 0xFF) << 16;
        status.bwPollTimeout |= (buffer[2] & 0xFF) << 8;
        status.bwPollTimeout |= (buffer[1] & 0xFF);
    }

    private void clearStatus() throws Exception {
        int length = usb.controlTransfer(DFU_RequestType, DFU_CLRSTATUS, 0, 0, null, 0, 0);
        if (length < 0) {
            throw new Exception("USB Failed during clearStatus");
        }
    }

    // use for commands
    private void download(byte[] data) throws Exception {
        int len = usb.controlTransfer(DFU_RequestType, DFU_DNLOAD, 0, 0, data, data.length, 50);
        if (len < 0) {
            throw new Exception("USB Failed during command download");
        }
    }

    // use for firmware download
    private void download(byte[] data, int nBlock) throws Exception {
        int len = usb.controlTransfer(DFU_RequestType, DFU_DNLOAD, nBlock, 0, data, data.length, 0);
        if (len < 0) {
            throw new Exception("USB failed during firmware download");
        }
    }

    private void upload(byte[] data, int length, int blockNum) throws Exception {
        int len = usb.controlTransfer(DFU_RequestType | USB_DIR_IN, DFU_UPLOAD, blockNum, 0, data, length, 100);
        if (len < 0) {
            throw new Exception("USB comm failed during upload");
        }
    }

    // stores the result of a GetStatus DFU request
    private class DfuStatus {
        byte bStatus;       // state during request
        int bwPollTimeout;  // minimum time in ms before next getStatus call should be made
        byte bState;        // state after request
    }

    // holds all essential information for the Dfu File
    private class DfuFile {
        String filePath;
        byte[] file;
        int PID;
        int VID;
        int BootVersion;
        int maxBlockSize = 1024;

        int elementStartAddress;
        int elementLength;

        String TargetName;
        int TargetSize;
        int NumElements;
    }
}
