package de.uni_osnabrueck.ikw.eegdroid;

import android.util.Log;
import android.widget.Toast;
import android.content.Context;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;


public class TraumschreiberService {

    public final static String DEVICE_NAME = "traumschreiber";
    //Names chosen according to the python tflow_edge Traumschreiber.py
    public final static UUID READ_SERVICE_UUID_OLD = UUID.fromString("a22686cb-9268-bd91-dd4f-b52d03d85593");
    public final static UUID READ_SERVICE_UUID_NEW = UUID.fromString("00000ee6-0000-1000-8000-00805f9b34fb");
    public final static UUID READ_CHAR_UUID_OLD = UUID.fromString("faa7b588-19e5-f590-0545-c99f193c5c3e");
    public final static UUID READ_CHAR_UUID_NEW = UUID.fromString("0000e617-0000-1000-8000-00805f9b34fb");
    public final static UUID WRITE_SERVICE_UUID_OLD = UUID.fromString("05bbfe57-2f19-ab84-c448-6769fe64d994");
    public final static UUID WRITE_SERVICE_UUID_NEW = UUID.fromString("00000ee6-0000-1000-8000-00805f9b34fb");
    public final static UUID WRITE_CHAR_UUID_OLD = UUID.fromString("fcbea85a-4d87-18a2-2141-0d8d2437c0a4");
    public final static UUID WRITE_CHAR_UUID_NEW = UUID.fromString("0000ecc0-0000-1000-8000-00805f9b34fb");
    private final ArrayList<String> notifyingUUIDs = new ArrayList<String>() {
        {
            add("0000ee60-0000-1000-8000-00805f9b34fb");
            add("0000ee61-0000-1000-8000-00805f9b34fb");
            add("0000ee62-0000-1000-8000-00805f9b34fb");
        }
    };
    private final String configCharacteristicUuid = "0000ecc0-0000-1000-8000-00805f9b34fb";
    private final String codeCharacteristicUuid = "0000c0de-0000-1000-8000-00805f9b34fb";
    private final static String TAG = "TraumschreiberService";
    private static final int signalScale = 298 / 1000000;
    public String mTraumschreiberDeviceAddress;
    private final byte[] dpcmBuffer = new byte[30];
    private final byte[] dpcmBuffer2 = new byte[30];
    private final int[] decodedSignal = new int[24];
    public static int[] signalBitShift = new int[24];
    private static int[] signalOffset = new int[24];
    private static int pkgCount;
    private boolean zeroCenterFlag = true;
    private boolean characteristic0Ready = false;


    public TraumschreiberService() {

    }


    public TraumschreiberService(String traumschreiberDeviceAddress) {
        this.mTraumschreiberDeviceAddress = traumschreiberDeviceAddress;
    }

    public static boolean isTraumschreiberDevice(String bluetoothDeviceName) {
        return bluetoothDeviceName.toLowerCase().contains(DEVICE_NAME);
    }

    public static boolean isNewModel(String bluetoothDeviceName) {
        return bluetoothDeviceName.startsWith("T");
    }

    public static void setSignalScaling(int[] newShift) {
        signalBitShift = newShift;
    }

    public static void initCentering(){
        signalOffset = new int[24];
        pkgCount = 0;
    }
    /***
     * decompress takes a bytearray dataBytes and converts it to integers, according to the way the Traumschreiber transmits the data via bluetooth
     * @param dataBytes
     * @return int[] data_ints of the datapoint values as integers
     */
    public int[] decompress(byte[] dataBytes, boolean newModel, String characteristicId) {

        boolean dpcmEncoded = true;
        int[] data_ints;
        int new_int;
        int bLen = newModel ? 3 : 2; // bytes needed to encode 1 int (old encodings)
        // ____OLD TRAUMSCHREIBER ____
        if (!newModel) {
            data_ints = new int[dataBytes.length / bLen];
            Log.d("Decompressing", "decompress: " + String.format("%02X %02X ", dataBytes[0], dataBytes[1]));
            //https://stackoverflow.com/questions/9581530/converting-from-byte-to-int-in-java
            //Example: rno[0]&0x000000ff)<<24|(rno[1]&0x000000ff)<<16|
            for (int ch = 0; ch < dataBytes.length / bLen; ch++) {
                new_int = (dataBytes[ch * bLen + 1]) << 8 | (dataBytes[ch * bLen]) & 0xff;
                //new_int = new_int << 8;
                data_ints[ch] = new_int;
            }

            // ____NEW TRAUMSCHREIBER____
        } else if (!dpcmEncoded) {
            // Process Header
            int header = dataBytes[0] & 0xff; // Unsigned Byte
            int pkg_id = header / 16; // 1. Nibble
            int pkgs_lost = header % 16; // 2. Nibble

            //Log.v(TAG, String.format("ID: %02d  Lost pkgs: %02d",pkg_id, pkgs_lost));

            // Prepare Data Array
            data_ints = new int[dataBytes.length / bLen + 2];   // +2: 1 for pkg id, 1 for lost pkg count
            data_ints[0] = pkg_id;
            data_ints[data_ints.length - 1] = pkgs_lost;

            // Decode
            /* value of channel n is encoded by 3 bytes placed at positions 3n+1, 3n+2 and 3n+3 in dataBytes*/
            for (int ch = 0; ch < dataBytes.length / bLen; ch++) {
                new_int = (dataBytes[ch * bLen + 1]) << 16 | (dataBytes[ch * bLen + 2] & 0xff) << 8 | (dataBytes[ch * bLen + 3] & 0xff);
                data_ints[ch + 1] = new_int;
            }

            // ____ DPCM DECODING ____
        } else {
            if (characteristicId.equals("0")) {
                System.arraycopy(dataBytes, 0, dpcmBuffer, 0, 20);
                characteristic0Ready = true;
                return null;

            } else if (characteristic0Ready && characteristicId.equals("1")) {
                System.arraycopy(dataBytes, 0, dpcmBuffer, 20, 10);
                System.arraycopy(dataBytes, 10, dpcmBuffer2, 0, 10);
                decodeDpcm(dpcmBuffer);
                handleCentering();
                return decodedSignal;

            } else if (characteristic0Ready && characteristicId.equals("2")) {
                System.arraycopy(dataBytes, 0, dpcmBuffer2, 10, 20);
                decodeDpcm(dpcmBuffer2);
                characteristic0Ready = false;
                handleCentering();
                return decodedSignal;

            } else if (characteristicId.equals("e")){
                for(int i = 0; i < 12; i++){
                    signalBitShift[i*2] = (dataBytes[i]>>4) & 0xf;
                    signalBitShift[i*2+1] = dataBytes[i] & 0xf;
                }
                Log.d(TAG, "RECEIVED FROM C0DE Characteritistic!" + Arrays.toString(signalBitShift));
                return new int[] {10000};

            } else {
                return null;
            }

        }
        return decodedSignal;

    }

    /***
     * Converts bytes to ints and adds the values of the current data to the previous data.
     * @param  deltaBytes
     * @return int[] data
     */
    public int[] decodeDpcm(byte[] deltaBytes) {
        //Log.v(TAG, "Encoded Delta: " + Arrays.toString(bytes));
        int[] delta = bytesTo10bitInts(deltaBytes);
        //Log.v(TAG, "Decoded Delta: " + Arrays.toString(data));

        for (int i = 0; i < delta.length; i++) {
            decodedSignal[i] += delta[i] << signalBitShift[i];
        }

        return decodedSignal;
    }

    /***
     * Turns an array of bytes into an array fo 10bit ints.
     * @param bytes
     * @return int[] data
     */
    public int[] bytesTo10bitInts(byte[] bytes) {
        // Number of ints : bytes*8/10 (8bits per byte and 10bits per int)
        int[] data = new int[bytes.length * 8 / 10];

        /**
         * Pattern repeats after 5 bytes. Therefore we process the bytes in chunks of 5.
         * Processing 5 bytes yields 4 (10bit) ints.
         * The index 'idx' of the resulting int array 'data' has to be adjusted at every loop step
         * to account for the gap between the indices resulting from the 5/4 byte-to-int ratio
         */
        int idx = 0;
        for (int i = 0; i <= bytes.length - 5; i += 5) {
            idx = i * 4 / 5;
            data[idx + 0] = ((bytes[i + 0] & 0xff) << 2) | ((bytes[i + 1] & 0xc0) >>> 6);
            data[idx + 1] = ((bytes[i + 1] & 0x3f) << 4) | ((bytes[i + 2] & 0xf0) >>> 4);
            data[idx + 2] = ((bytes[i + 2] & 0x0f) << 6) | ((bytes[i + 3] & 0xfc) >>> 2);
            data[idx + 3] = ((bytes[i + 3] & 0x03) << 8) | ((bytes[i + 4] & 0xff) >>> 0);
        }
        // Subtracting 1024 turns unsigned 10bit ints into their 2's complement
        for (int i = 0; i < data.length; i++) {
            if (data[i] > 511) data[i] -= 1024;
        }

        return data;
    }

    public void handleCentering() {
        if (pkgCount > 1000) {
            return;

        } else if (pkgCount == 1000) {
            Log.d(TAG, "Signal is actually being centered..");
            //Log.d(TAG, "Signal before: " + Arrays.toString(decodedSignal));
            for (int i = 0; i < decodedSignal.length; i++) {
                decodedSignal[i] = decodedSignal[i] - signalOffset[i];
            }
            Log.d(TAG, "Calculated Means: " + Arrays.toString(signalOffset));
            //Log.d(TAG, "Signal after: " + Arrays.toString(decodedSignal));

        } else {
            for (int i = 0; i < decodedSignal.length; i++) {
                signalOffset[i] += 0.001 * decodedSignal[i]; //Average over 1000 pkgs
            }
        }

        pkgCount++;
    }
}