package de.uni_osnabrueck.ikw.eegdroid;
// test
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewStub;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;


public class Record extends AppCompatActivity {

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
    public static final String EXTRAS_DEVICE_MODEL = "DEVICE_MODEL"; // "2": old, "3": new
    private final static String TAG = Record.class.getSimpleName();
    private final Handler handler = new Handler();
    private final List<Float> dp_received = new ArrayList<>();
    private final List<List<Float>> accumulated = new ArrayList<>();
    private final int MAX_VISIBLE = 1000;  // see 500ms at the time on the plot
    private final ArrayList<Integer> pkgIDs = new ArrayList<>();
    private final int nChannels = 24;
    private final ArrayList<ArrayList<Entry>> lineEntryLists = new ArrayList<ArrayList<Entry>>() {
        {
            for (int i = 0; i < nChannels; i++) {
                ArrayList<Entry> lineEntries = new ArrayList<>();
                add(lineEntries);
            }
        }
    };
    private final String serviceUuid = "00000ee6-0000-1000-8000-00805f9b34fb";
    private final ArrayList<String> notifyingUUIDs = new ArrayList<String>() {
        {
            add("0000ee60-0000-1000-8000-00805f9b34fb");
            add("0000ee61-0000-1000-8000-00805f9b34fb");
            add("0000ee62-0000-1000-8000-00805f9b34fb");
        }
    };
    private final String configCharacteristicUuid = "0000ecc0-0000-1000-8000-00805f9b34fb";
    private final String codeCharacteristicUuid = "0000c0de-0000-1000-8000-00805f9b34fb";
    private final ArrayList<BluetoothGattCharacteristic> notifyingCharacteristics = new ArrayList<>();
    private BluetoothGattCharacteristic configCharacteristic;
    private BluetoothGattCharacteristic codeCharacteristic;
    private BluetoothGattCharacteristic mNotifyCharacteristic;
    private String selectedGain = "1";
    private byte selectedGainB = 0b00000000;
    private boolean generateDummy = false;
    private byte generateDummyB = (byte) 0b00000000;
    private boolean halfDummy = false;
    private byte halfDummyB = (byte) 0b00000000;
    private final int[] channelColors = new int[nChannels];
    private final boolean[] channelsShown = new boolean[nChannels];
    private final CheckBox[] checkBoxes = new CheckBox[nChannels];
    private final TextView[] channelValueViews = new TextView[nChannels];
    LSL.StreamInfo streamInfo;
    LSL.StreamOutlet streamOutlet = null;
    private TextView mConnectionState;
    private TextView viewDeviceAddress;
    private boolean mNewDevice;
    private String mDeviceAddress;
    private BluetoothLeService mBluetoothLeService;
    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }

            // hack for ensuring a successful connection
            // constants
            int CONNECT_DELAY = 2000;
            handler.postDelayed(() -> mBluetoothLeService.connect(mDeviceAddress), CONNECT_DELAY);  // connect with a defined delay
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            if (mBluetoothLeService != null) mBluetoothLeService = null;
        }
    };
    private int selectedScale;
    private byte selectedScaleB = 0b00000000;
    private boolean recording = false;
    private boolean notifying = false;
    private float res_time;
    private float res_freq;
    private int cnt = 0;
    private int enabledCheckboxes = 0;
    private TextView mXAxis;
    private TextView mDataResolution;
    private Spinner gain_spinner;
    private LineChart mChart;
    private ImageButton imageButtonRecord;
    private ImageButton imageButtonSave;
    private ImageButton imageButtonDiscard;
    private androidx.appcompat.widget.SwitchCompat switch_plots;
    private View layout_plots;
    private boolean plotting = false;
    private List<float[]> main_data;
    private int adaptiveEncodingFlag = 0; //Indicates whether adaptive encoding took place in this instant.
    private final ArrayList<Integer> adaptiveEncodingFlags = new ArrayList<>();
    private final View.OnClickListener imageDiscardOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            main_data = new ArrayList<>();
            Toast.makeText(
                    getApplicationContext(),
                    "Your EEG session was discarded.",
                    Toast.LENGTH_LONG
            ).show();
            buttons_prerecording();
        }
    };
    private float data_cnt = 0;
    private String start_time;
    private String end_time;
    private long start_watch;
    private String recording_time;
    private long start_timestamp;
    private long end_timestamp;
    private final View.OnClickListener imageRecordOnClickListener = v -> {
        if (!recording) {
            startTrial();
            Toast.makeText(
                    getApplicationContext(),
                    "Recording in process.",
                    Toast.LENGTH_LONG
            ).show();
            buttons_recording();
        } else {
            endTrial();
            buttons_postrecording();
        }
    };
    private final View.OnClickListener imageSaveOnClickListener = v -> {
        LayoutInflater layoutInflaterAndroid = LayoutInflater.from(getApplicationContext());
        View mView = layoutInflaterAndroid.inflate(R.layout.input_dialog_string, null);
        AlertDialog.Builder alertDialogBuilderUserInput = new AlertDialog.Builder(Record.this);
        alertDialogBuilderUserInput.setView(mView);

        final EditText userInputLabel = mView.findViewById(R.id.input_dialog_string_Input);

        alertDialogBuilderUserInput
                .setCancelable(false)
                .setTitle(R.string.session_label_title)
                .setMessage(getResources().getString(R.string.enter_session_label))
                .setPositiveButton(R.string.save, (dialogBox, id) -> {
                    if (!userInputLabel.getText().toString().isEmpty()) {
                        saveSession(userInputLabel.getText().toString());
                    } else saveSession();
                    Toast.makeText(getApplicationContext(), "Your EEG session was successfully stored.", Toast.LENGTH_LONG).show();
                });

        AlertDialog alertDialogAndroid = alertDialogBuilderUserInput.create();
        alertDialogAndroid.show();
        buttons_prerecording();
    };
    private Thread thread;
    private long plotting_start;
    private final CompoundButton.OnCheckedChangeListener switchPlotsOnCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (!isChecked) {
//                layout_plots.setVisibility(ViewStub.GONE);
//                mXAxis.setVisibility(ViewStub.GONE);
                plotting = false;
            } else {
                //layout_plots.setVisibility(ViewStub.VISIBLE);
                //mXAxis.setVisibility(ViewStub.VISIBLE);
                if (enabledCheckboxes != 0) {
                    plotting = true;
                    plotting_start = System.currentTimeMillis();
                } else {
                    Toast.makeText(getApplicationContext(),
                            "Need to select a Channel first",
                            Toast.LENGTH_SHORT).show();
                    buttonView.setChecked(false);
                }
            }
        }
    };
    private boolean deviceConnected = false;
    private boolean casting = false;
    private Menu menu;
    private List<List<Float>> recentlyDisplayedData;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private List<Float> microV;
    private CastThread caster;
    private Timer timer;
    private TimerTask timerTask;
    private boolean timerRunning = false;
    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
//            Log.d("Device connected: ", deviceConnected ? "true" : "false");
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                deviceConnected = true;
                buttons_prerecording();
                setConnectionStatus(true);
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                deviceConnected = false;
                setConnectionStatus(false);
                clearUI();
                disableCheckboxes();
                data_cnt = 0;
                if (timer != null) {
                    timer.cancel();
                    timer.purge();
                }
                timerRunning = false;
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                data_cnt = 0;
                discoverCharacteristics(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action) && deviceConnected) {
                int[] data = intent.getIntArrayExtra(BluetoothLeService.EXTRA_DATA);
                if (data[0] == 10000) { // 10000 indicates no Data but adaptive Encoding
                    adaptiveEncodingFlag = 1;
                    /*String bitshiftch1 = Integer.toString(TraumschreiberService.signalBitShift[0]);
                    Toast.makeText(getApplicationContext(),
                            "Adaptive Encoding, Ch1 shifted by " + bitshiftch1 + "bits",
                            Toast.LENGTH_LONG).show();*/
                    return;
                }
                data_cnt++;
                if (!timerRunning) startTimer();
                long last_data = System.currentTimeMillis();
                enableCheckboxes(1);
                microV = transData(Objects.requireNonNull(intent.getIntArrayExtra(BluetoothLeService.EXTRA_DATA)));
                streamData(microV);
                //if (data_cnt==1) Toast.makeText(getApplicationContext(), "Callibrating offset..", Toast.LENGTH_LONG);
                //if (data_cnt==1000) Toast.makeText(getApplicationContext(), "Callibrated offset", Toast.LENGTH_LONG);
                if (data_cnt % 30 == 0) displayData(microV);
                if (plotting & data_cnt % 2 == 0) {
                    accumulated.add(microV);
                    long plotting_elapsed = last_data - plotting_start;
                    int ACCUM_PLOT_MS = 30;
                    if (plotting_elapsed > ACCUM_PLOT_MS) {
                        addEntries(accumulated);
                        accumulated.clear();
                        plotting_start = System.currentTimeMillis();
                    }
                }
                if (recording) {
                    storeData(microV);
                    mConnectionState.setText(R.string.recording);
                    mConnectionState.setTextColor(Color.RED);
                } else {
                    mConnectionState.setText(R.string.device_connected);
                    mConnectionState.setTextColor(Color.GREEN);
                }
            }
        }
    };

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.EXTRA_DATA);
        return intentFilter;
    }

    private void setGainSpinner() {
        int gains_set = mNewDevice ? R.array.gains_new : R.array.gains_old;
        gain_spinner.setAdapter(new ArrayAdapter<>(getApplicationContext(),
                android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(gains_set)));
        int gain_default = 1;
        gain_spinner.setSelection(gain_default);
        gain_spinner.setEnabled(true);
        //selectedGain = gain_spinner.getSelectedItem().toString();
        gain_spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int parsed = Integer.parseInt(gain_spinner.getSelectedItem().toString());
                    switch(parsed) {
                        case 1:
                            generateDummy = false;
                            generateDummyB = (byte) 0;
                            break;
                        case 2:
                            generateDummy = true;
                            generateDummyB = (byte) 0b00110000;

                    }
                /*switch (position) {
                    case 1:
                        selectedGain = "2";
                        selectedGainB = (byte) 0b01000000;
                        break;
                    case 2:
                        selectedGain = "4";
                        selectedGainB = (byte) 0b10000000;
                        break;
                    case 3:
                        selectedGain = "8";
                        selectedGainB = (byte) 0b11000000;
                        break;
                    default:
                        selectedGain = "1";
                        selectedGainB = (byte) 0b00000000;
                }*/
                if (configCharacteristic != null) updateConfiguration();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // sometimes you need nothing here
            }
        });
    }

    private void updateConfiguration() {
        // Declare bytearray
        byte[] configBytes = new byte[3];

        // Concatenate binary strings
        configBytes[0] = (byte) (selectedGainB | generateDummyB | halfDummyB);
        //configBytes[1] = selectedScaleB;
        configBytes[2] = 0b00000000;


        configCharacteristic.setValue(configBytes);
        mBluetoothLeService.writeCharacteristic(configCharacteristic);
    }

    private void initializeTimerTask() {
        timerTask = new TimerTask() {
            public void run() {
                handler.post(() -> {
                    res_time = 1000 / data_cnt;
                    String hertz = (int) data_cnt + "Hz";
                    res_freq = data_cnt;
                    @SuppressLint("DefaultLocale") String resolution = String.format("%.2f", res_time) + "ms - ";
                    String content = resolution + hertz;
                    if (data_cnt != 0) mDataResolution.setText(content);
                    if (!notifying) mDataResolution.setText("No data");
                    data_cnt = 0;
                });
            }
        };
    }

    private void startTimer() {
        //set a new Timer
        timer = new Timer();
        //initialize the TimerTask's job
        initializeTimerTask();
        // schedule the timer, the stimulus presence will repeat every 1 seconds
        timer.schedule(timerTask, 1000, 1000);
        timerRunning = true;
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        setContentView(R.layout.activity_record);

        // LSL stuff
        final UUID uid = UUID.randomUUID();
        streamInfo = new LSL.StreamInfo("Traumschreiber-EEG", "Markers", 24, LSL.IRREGULAR_RATE, LSL.ChannelFormat.float32, uid.toString());
        try {
            streamOutlet = new LSL.StreamOutlet(streamInfo);
        } catch (IOException ex) {
            Log.v("LSL issue:", Objects.requireNonNull(ex.getMessage()));
            return;
        }

        imageButtonRecord = findViewById(R.id.imageButtonRecord);
        imageButtonSave = findViewById(R.id.imageButtonSave);
        imageButtonDiscard = findViewById(R.id.imageButtonDiscard);
        switch_plots = findViewById(R.id.switch_plots);
        gain_spinner = findViewById(R.id.gain_spinner);

        layout_plots = findViewById(R.id.linearLayout_chart);
        layout_plots.setVisibility(ViewStub.VISIBLE);

        mXAxis = findViewById(R.id.XAxis_title);
        mXAxis.setVisibility(ViewStub.VISIBLE);
        imageButtonRecord.setOnClickListener(imageRecordOnClickListener);
        imageButtonSave.setOnClickListener(imageSaveOnClickListener);
        imageButtonDiscard.setOnClickListener(imageDiscardOnClickListener);
        switch_plots.setOnCheckedChangeListener(switchPlotsOnCheckedChangeListener);

        // Sets up UI references.
        mConnectionState = findViewById(R.id.connection_state);
        viewDeviceAddress = findViewById(R.id.device_address);
        mConnectionState = findViewById(R.id.connection_state);

        LinearLayout[] checkBoxRows = new LinearLayout[3];
        checkBoxRows[0] = findViewById(R.id.checkBoxRow1);
        checkBoxRows[1] = findViewById(R.id.checkBoxRow2);
        checkBoxRows[2] = findViewById(R.id.checkBoxRow3);

        LinearLayout[] channelValueRows = new LinearLayout[3];
        channelValueRows[0] = findViewById(R.id.channelValueRow1);
        channelValueRows[1] = findViewById(R.id.channelValueRow2);
        channelValueRows[2] = findViewById(R.id.channelValueRow3);

        getChannelColors(); // fills int[] channelColors with values
        for (int i = 0; i < nChannels; i++) {

            channelValueViews[i] = createChannelValueView(i);
            channelValueRows[i / 8].addView(channelValueViews[i]);

            checkBoxes[i] = createPlottingCheckbox(i);
            checkBoxRows[i / 8].addView(checkBoxes[i]);
        }
        mDataResolution = findViewById(R.id.resolution_value);
        setChart();
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
    }

    private TextView createChannelValueView (int i){
        // Create View for Channel Value
        TextView channelValueView = new TextView(getApplicationContext());
        LinearLayout.LayoutParams valueLayout = new LinearLayout.LayoutParams(15, -1, 1f);
        //valueLayout.width = 6;
        //valueLayout.height = ViewGroup.LayoutParams.MATCH_PARENT;
        //valueLayout.weight = 1;
        valueLayout.topMargin = 2;
        channelValueView.setLayoutParams(valueLayout);
        channelValueView.setTextAlignment(RelativeLayout.TEXT_ALIGNMENT_VIEW_END);
        channelValueView.setText("0μV");
        channelValueView.setTextColor(channelColors[i]);
        channelValueView.setTextSize(13);
        // channelValueView.setGravity(0);
        return channelValueView;
    }
    private CheckBox createPlottingCheckbox (int i) {
        // Create Checkbox for displaying channel
        CheckBox box = new CheckBox(getApplicationContext());
        LinearLayout.LayoutParams boxLayout = new LinearLayout.LayoutParams(15, -2, 1f);
        //boxLayout.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        //boxLayout.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        //boxLayout.weight = 1;
        box.setLayoutParams(boxLayout);
        box.setText(Integer.toString(i + 1));
        box.setTextColor(channelColors[i]);
        box.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int channelId = Integer.parseInt(buttonView.getText().toString()) - 1;
            if (isChecked) {
                enabledCheckboxes++;
                if (enabledCheckboxes <= 8) {
                    channelsShown[channelId] = true;
                } else {
                    Toast.makeText(
                            getApplicationContext(),
                            "Can't plot more than 8 channels simultaneously.",
                            Toast.LENGTH_LONG
                    ).show();
                    box.setChecked(false);
                    enabledCheckboxes--;
                }
            }
            if (!isChecked) {
                if (!plotting | enabledCheckboxes > 1) {
                    enabledCheckboxes--;
                    channelsShown[channelId] = false;
                } else {
                    Toast.makeText(
                            getApplicationContext(),
                            "Need to plot at least one channel",
                            Toast.LENGTH_SHORT
                    ).show();
                    buttonView.setChecked(true); //Check this box again
                }
            }
        });
        return box;
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unbindService(mServiceConnection);
        } catch(Exception e) {
            Log.w(TAG, e.toString());
        }
        mBluetoothLeService = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.bluetooth_connect, menu);
        this.menu = menu;
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        int id = item.getItemId();
        if (id == R.id.scan) {
            if (!deviceConnected) {
                Intent intent = new Intent(this, DeviceScanActivity.class);
                startActivityForResult(intent, 1200);
            } else {
                //Handles the Dialog to confirm the closing of the activity
                AlertDialog.Builder alert = new AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_title)
                        .setMessage(getResources().getString(R.string.confirmation_disconnect));
                alert.setPositiveButton(android.R.string.yes, (dialog, which) -> mBluetoothLeService.disconnect());
                alert.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    // close dialog
                    dialog.cancel();
                });
                alert.show();
            }
            return true;
        }

        if (id == android.R.id.home) {
            if (recording) {
                //Handles the Dialog to confirm the closing of the activity
                AlertDialog.Builder alert = new AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_title)
                        .setMessage(getResources().getString(R.string.confirmation_close_record));
                alert.setPositiveButton(android.R.string.yes, (dialog, which) -> onBackPressed());
                alert.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    // close dialog
                    dialog.cancel();
                });
                alert.show();
            } else {
                onBackPressed();
            }
            return true;
        }

        if (id == R.id.notify) toggleNotifying();

        if (id == R.id.cast) {
            MenuItem menuItemCast = menu.findItem(R.id.cast);
            if (!casting) {
                casting = true;
                caster = new CastThread();
                caster.start();
                menuItemCast.setIcon(R.drawable.ic_cast_blue_24dp);
            } else {
                casting = false;
                caster.staph();
                menuItemCast.setIcon(R.drawable.ic_cast_white_24dp);
            }
        }

        if (id==R.id.centering) {
            Toast.makeText(getApplicationContext(),
                    "Centering Signal around 0..",
                    Toast.LENGTH_LONG).show();
            TraumschreiberService.initCentering();
        }
        return super.onOptionsItemSelected(item);
    }

    private void toggleNotifying() {
        MenuItem menuItemNotify = menu.findItem(R.id.notify);
        menuItemNotify.setEnabled(false);
        waitForBluetoothCallback(mBluetoothLeService);
        if (!notifying) {
            notifying = true;
            mBluetoothLeService.setCharacteristicNotification(mNotifyCharacteristic, notifying);
            menuItemNotify.setIcon(R.drawable.ic_notifications_active_blue_24dp);
        } else {
            notifying = false;
            mDataResolution.setText("No data");
            mBluetoothLeService.setCharacteristicNotification(mNotifyCharacteristic, notifying);
            menuItemNotify.setIcon(R.drawable.ic_notifications_off_white_24dp);
        }

        menuItemNotify.setEnabled(true);

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        // Check which request we're responding to
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == 1200) {
            // Make sure the request was successful
            if (resultCode == RESULT_OK) {
                // The user picked a contact.
                // The Intent's data Uri identifies which contact was selected
                String mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
                mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);
                String model = intent.getStringExtra(EXTRAS_DEVICE_MODEL);
                if (model != null) mNewDevice = model.equals("3");
                setGainSpinner();
                Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
                bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
            }
        }
    }

    // Discovers services and characteristics
    private void discoverCharacteristics(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        String charUuid;
        List<BluetoothGattCharacteristic> gattCharacteristics;
        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            // If we find the right service
            if (serviceUuid.equals(gattService.getUuid().toString())) {
                gattCharacteristics = gattService.getCharacteristics();
                for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                    charUuid = gattCharacteristic.getUuid().toString();
                    // If the characteristic is a notifying characteristic
                    if (notifyingUUIDs.contains(charUuid)) {
                        notifyingCharacteristics.add(gattCharacteristic);
                        mBluetoothLeService.setCharacteristicNotification(gattCharacteristic, false);
                        mNotifyCharacteristic = gattCharacteristic; // Store the last one here for toggling
                    } else if (configCharacteristicUuid.contains(charUuid)) {
                        configCharacteristic = gattCharacteristic;
                    } else if (codeCharacteristicUuid.contains(charUuid)) {
                        codeCharacteristic = gattCharacteristic;
                    }
                }
                prepareNotifications();
                waitForBluetoothCallback(mBluetoothLeService);
                mBluetoothLeService.setCharacteristicNotification(codeCharacteristic, true);
            }
        }
    }

    private void prepareNotifications() {
        // set notifications of all notifyingCharacteristics except the one used for toggling.
        mBluetoothLeService.setNewTraumschreiber(mNewDevice);

        for (BluetoothGattCharacteristic characteristic : notifyingCharacteristics) {
            waitForBluetoothCallback(mBluetoothLeService);
            if (characteristic != mNotifyCharacteristic) {
                mBluetoothLeService.setCharacteristicNotification(characteristic, true);
            }
        }
    }

    private void waitForBluetoothCallback(BluetoothLeService service){
        while (service.isBusy) {
            Handler handler = new Handler();
            handler.postDelayed(() -> Log.d(TAG, "Waiting for bluetooth operation to finish."), 300);
        }

    }

    private void clearUI() {
        for (TextView view : channelValueViews) view.setText("0μV");
        mDataResolution.setText(R.string.no_data);
        data_cnt = 0;
    }

    private void enableCheckboxes(int n) {
        for (int i = 0; i < n; i++) checkBoxes[i].setEnabled(true);
    }

    private void disableCheckboxes() {
        for (CheckBox box : checkBoxes) box.setEnabled(false);
    }

    private List<Float> transData(int[] data) {
        // Conversion formula (old): V_in = X * 1.65V / (1000 * GAIN * PRECISION)
        // Conversion formula (new): V_in = X * (298 / (10^9 * gain))
        float gain = Float.parseFloat(selectedGain); // 1 by default
        List<Float> data_trans = new ArrayList<>();
        if (!mNewDevice) { // old model
            pkgIDs.add((int) data_cnt); // store pkg ID
            float precision = 2048;
            float numerator = 1650;
            float denominator = gain * precision;
            for (int datapoint : data) data_trans.add((datapoint * numerator) / denominator);
        } else {
            for (float datapoint : data) data_trans.add(datapoint * 298 / 1000000 / gain);
        }
        return data_trans;
    }

    @SuppressLint("DefaultLocale")
    private void displayData(List<Float> signalMicroV) {
        if (signalMicroV != null) {
            for (int i = 0; i < 24; i++) {
                String channelValueS = "";
                float channelValueF = signalMicroV.get(i);
                //if(signalMicroV.get(i) > 0) value += "+";
                if (channelValueF >= 1000 | channelValueF <= -1000) {
                    channelValueF = channelValueF / 1000;
                    channelValueS += String.format("%.1f", channelValueF);
                    channelValueS += "mV";
                } else {
                    channelValueS += String.format("%.0f", channelValueF);
                    channelValueS += "μV";
                }
                channelValueViews[i].setText(channelValueS);
            }
        }
    }

    private void streamData(List<Float> data_microV) {
        float[] sample = new float[24];

        for (int i = 0; i < data_microV.size(); i++) {
            sample[i] = data_microV.get(i);
        }
        streamOutlet.push_sample(sample);
        Log.v("LSL", "Sample sent!");
//        try {
//            streamOutlet.push_sample(sample);
//        } catch (Exception ex) {
//            Log.d("LSL issue", Objects.requireNonNull(ex.getMessage()));
//            streamOutlet.close();
//            streamInfo.destroy();
//        }
    }


    private void setChart() {
        OnChartValueSelectedListener ol = new OnChartValueSelectedListener() {
            @Override
            public void onValueSelected(Entry entry, Highlight h) {
                //entry.getData() returns null here
            }

            @Override
            public void onNothingSelected() {

            }
        };
        mChart = findViewById(R.id.layout_chart);
        mChart.setOnChartValueSelectedListener(ol);
        // enable description text
        mChart.getDescription().setEnabled(false);
        // enable touch gestures
        mChart.setTouchEnabled(true);
        // enable scaling and dragging
        mChart.setDragEnabled(true);
        mChart.setScaleEnabled(true);
        mChart.setDrawGridBackground(true);
        // if disabled, scaling can be done on x- and y-axis separately
        mChart.setPinchZoom(true);
        // set an alternative background color
        LineData data = new LineData();
        data.setValueTextColor(Color.BLACK);
        data.setDrawValues(true);
        // add empty data
        mChart.setData(data);
        // get the legend (only possible after setting data)
        Legend l1 = mChart.getLegend();
        // modify the legend ...
        l1.setForm(Legend.LegendForm.LINE);
        l1.setTextColor(Color.BLACK);
        // set the y left axis
        YAxis leftAxis = mChart.getAxisLeft();
        leftAxis.setTextColor(Color.GRAY);
        leftAxis.setAxisMaximum(30f);
        leftAxis.setAxisMinimum(-30f);
        leftAxis.setLabelCount(13, true); // from -35 to 35, a label each 5 microV
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.WHITE);
        // disable the y right axis
        YAxis rightAxis = mChart.getAxisRight();
        rightAxis.setEnabled(false);
        // set the x bottom axis
        XAxis bottomAxis = mChart.getXAxis();
        bottomAxis.setLabelCount(5, true);
        bottomAxis.setValueFormatter(new MyXAxisValueFormatter());
        bottomAxis.setPosition(XAxis.XAxisPosition.TOP);
        bottomAxis.setGridColor(Color.WHITE);
        bottomAxis.setTextColor(Color.GRAY);
    }

    private LineDataSet createSet(int channelId) {
        LineDataSet set = new LineDataSet(lineEntryLists.get(channelId), String.format("Ch-%d", channelId + 1));
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        set.setColor(channelColors[channelId]);
        set.setDrawCircles(false);
        set.setLineWidth(1f);
        set.setValueTextColor(channelColors[channelId]);
        set.setVisible(channelsShown[channelId]);
        return set;
    }

    private void addEntries(final List<List<Float>> e_list) {
        adjustScale(e_list);
        final List<ILineDataSet> datasets = new ArrayList<>();  // for adding multiple plots
        float x = 0;
        float DATAPOINT_TIME = 6f;

        /*** Loop through all "rows",each row has nChannels entries **/
        for (int i = 0; i < e_list.size(); i++) {
            cnt += 2; // TODO: manage skipping every other frame in a cleaner way
            x = cnt * DATAPOINT_TIME; // timestamp for x axis in ms
            List<Float> f = e_list.get(i);

            /***  add the entries of every shown channel.**/
            for (int n = 0; n < nChannels; n++) {
                //the ith entryList represents the stored data of the ith channel
                // TODO: Chec if if condition makes a difference. CUrrent: NO
                lineEntryLists.get(n).add(new Entry(x, f.get(n)));
            }
        }
        final float f_x = x;

        if (thread != null) thread.interrupt();
        final Runnable runnable = () -> {

            // PLOTTABLE DATASET CREATION
            for (int i = 0; i < nChannels; i++) {
                if (channelsShown[i]) {
                    LineDataSet set = createSet(i);
                    datasets.add(set);
                }

            }
            LineData linedata = new LineData(datasets);
            linedata.notifyDataChanged();
            linedata.setDrawValues(false);
            mChart.setData(linedata);
            mChart.notifyDataSetChanged();
            // limit the number of visible entries
            mChart.setVisibleXRangeMaximum(MAX_VISIBLE);
            // move to the latest entry
            mChart.moveViewToX(f_x);
        };
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(runnable);
            }
        });
        thread.start();

        // max time range in ms (x value) to store on plot
        int PLOT_MEMO = 2000;
        // as soon as we have recorded more than PLOT_MEMO miliseconds, remove earlier entries
        // from chart.
        if (x > PLOT_MEMO) {
            for (int j = 0; j < e_list.size(); j++) {
                for (int i = 0; i < mChart.getData().getDataSetCount(); i++) {
                    mChart.getData().getDataSetByIndex(i).removeFirst();
                }
            }
        }
    }

    /**
     * adjusts the scale according to the maximal and minimal value of the data given in
     *
     * @param e_list
     */
    private void adjustScale(final List<List<Float>> e_list) {
        if (recentlyDisplayedData == null) {
            recentlyDisplayedData = new ArrayList<>();
        }
        if (recentlyDisplayedData.size() > 50 * e_list.size())
            recentlyDisplayedData = recentlyDisplayedData.subList(e_list.size(), recentlyDisplayedData.size());
        for (List<Float> innerList : e_list) {
            recentlyDisplayedData.add(innerList);
        }
        recentlyDisplayedData.addAll(e_list);
        int max = 0;
        int min = 0;
        for (List<Float> innerList : recentlyDisplayedData) {
            int channel = 0;
            for (Float entry : innerList) {
                if (channelsShown[channel]) {
                    if (entry > max) {
                        max = entry.intValue();
                    }
                    if (entry < min) {
                        min = entry.intValue();
                    }
                }
                channel++;
            }
        }

        // include this part to make the axis symmetric (0 always visible in the middle)
        /*if (max < min * -1) max = min * -1;
        min = max * -1;
        */

        int range = max - min;
        max += 0.1 * range;
        min -= 0.1 * range;
        YAxis leftAxis = mChart.getAxisLeft();
        leftAxis.setAxisMaximum(max);
        leftAxis.setAxisMinimum(min);
    }

    //Starts a recording session
    @SuppressLint({"SimpleDateFormat", "SetTextI18n"})
    private void startTrial() {
        cnt = 0;
        main_data = new ArrayList<>();
        start_time = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        start_timestamp = new Timestamp(start_watch).getTime();
        recording = true;
    }

    //Finish a recording session
    @SuppressLint("SimpleDateFormat")
    private void endTrial() {
        recording = false;
        end_time = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        long stop_watch = System.currentTimeMillis();
        end_timestamp = new Timestamp(stop_watch).getTime();
        recording_time = Long.toString(stop_watch - start_watch);
    }


    //Stores data while session is running
    private void storeData(List<Float> data_microV) {
        if (dp_received.size() == 0) start_watch = System.currentTimeMillis();
        float[] f_microV = new float[data_microV.size()];
        float curr_received = System.currentTimeMillis() - start_watch;
        dp_received.add(curr_received);
        int i = 0;
        for (Float f : data_microV)
            f_microV[i++] = (f != null ? f : Float.NaN); // Or whatever default you want
        main_data.add(f_microV);
        adaptiveEncodingFlags.add(adaptiveEncodingFlag);
        adaptiveEncodingFlag = 0;
    }

    private void saveSession() {
        saveSession("default");
    }

    //Saves the data at the end of session
    @SuppressLint("DefaultLocale")
    private void saveSession(final String tag) {
        final String top_header = "Username, User ID, Session ID,Session Tag,Date,Shape (rows x columns)," +
                "Duration (ms),Starting Time,Ending Time,Resolution (ms),Resolution (Hz)," +
                "Unit Measure,Starting Timestamp,Ending Timestamp";
        final String username = getSharedPreferences("userPreferences", 0).getString("username", "user");
        final String userID = getSharedPreferences("userPreferences", 0).getString("userID", "12345678");
        //final String dp_header = "Pkg ID,Pkg Loss,Time,Ch-1,Ch-2,Ch-3,Ch-4,Ch-5,Ch-6,Ch-7,Ch-8";
        final UUID id = UUID.randomUUID();
        @SuppressLint("SimpleDateFormat") final String date = new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss").format(new Date());
        final char delimiter = ',';
        final char break_line = '\n';

        int rows = main_data.size();
        int cols = main_data.get(0).length;
        final StringBuilder header = new StringBuilder();
        for (int i = 1; i <= cols; i++) header.append(String.format("Ch-%d,", i));
        header.append("Bitshift-Ch1,Bitshift-Ch2,Bitshift-Ch3,Bitshift-Ch4,Bitshift-Ch5,Bitshift-Ch6,");
        header.append("Bitshift-Notification");
        //header.append(String.format("Ch-%d", cols));
        new Thread(() -> {
            try {
                File formatted = new File(MainActivity.getDirSessions(),
                        date + "_" + tag + ".csv");
                // if file doesn't exists, then create it
                if (!formatted.exists()) //noinspection ResultOfMethodCallIgnored
                    formatted.createNewFile();
                FileWriter fileWriter = new FileWriter(formatted);
                fileWriter.append(top_header);
                fileWriter.append(break_line);
                fileWriter.append(username);
                fileWriter.append(delimiter);
                fileWriter.append(userID);
                fileWriter.append(delimiter);
                fileWriter.append(id.toString());
                fileWriter.append(delimiter);
                fileWriter.append(tag);
                fileWriter.append(delimiter);
                fileWriter.append(date);
                fileWriter.append(delimiter);
                fileWriter.append(String.valueOf(rows)).append("x").append(String.valueOf(cols));
                fileWriter.append(delimiter);
                fileWriter.append(recording_time);
                fileWriter.append(delimiter);
                fileWriter.append(start_time);
                fileWriter.append(delimiter);
                fileWriter.append(end_time);
                fileWriter.append(delimiter);
                fileWriter.append(String.valueOf(res_time));
                fileWriter.append(delimiter);
                fileWriter.append(String.valueOf(res_freq));
                fileWriter.append(delimiter);
                fileWriter.append("µV");
                fileWriter.append(delimiter);
                fileWriter.append(Long.toString(start_timestamp));
                fileWriter.append(delimiter);
                fileWriter.append(Long.toString(end_timestamp));
                fileWriter.append(delimiter);
                fileWriter.append(break_line);
                fileWriter.append(header.toString());
                fileWriter.append(break_line);
                for (int i = 0; i < rows; i++) {
                    //fileWriter.append(String.valueOf(pkgIDs.get(i)));
                    //fileWriter.append(delimiter);
                    //fileWriter.append(String.valueOf(pkgsLost.get(i)));
                    //fileWriter.append(delimiter);
                    //fileWriter.append(String.valueOf(dp_received.get(i)));
                    //fileWriter.append(delimiter);
                    for (int j = 0; j < cols; j++) {
                        fileWriter.append(String.valueOf(main_data.get(i)[j]));
                        fileWriter.append(delimiter);
                    }
                    for(int j=0; j<6;j++) {
                        fileWriter.append(String.valueOf(TraumschreiberService.signalBitShift[j]));
                        fileWriter.append(delimiter);
                    }
                    fileWriter.append(String.valueOf(adaptiveEncodingFlags.get(i)));
                    fileWriter.append(break_line);
                }
                fileWriter.flush();
                fileWriter.close();
            } catch (Exception e) {
                Log.e(TAG, "Error storing the data into a CSV file: " + e);
            }
        }).start();
    }

    private void buttons_nodata() {
        imageButtonRecord.setImageResource(R.drawable.ic_fiber_manual_record_pink_24dp);
        imageButtonRecord.setEnabled(false);
        imageButtonSave.setImageResource(R.drawable.ic_save_gray_24dp);
        imageButtonSave.setEnabled(false);
        imageButtonDiscard.setImageResource(R.drawable.ic_delete_gray_24dp);
        imageButtonDiscard.setEnabled(false);
    }

    private void buttons_prerecording() {
        imageButtonRecord.setImageResource(R.drawable.ic_fiber_manual_record_red_24dp);
        imageButtonRecord.setEnabled(true);
        imageButtonSave.setImageResource(R.drawable.ic_save_gray_24dp);
        imageButtonSave.setEnabled(false);
        imageButtonDiscard.setImageResource(R.drawable.ic_delete_gray_24dp);
        imageButtonDiscard.setEnabled(false);
    }

    private void buttons_recording() {
        imageButtonRecord.setImageResource(R.drawable.ic_stop_black_24dp);
        imageButtonSave.setImageResource(R.drawable.ic_save_gray_24dp);
        imageButtonSave.setEnabled(false);
        imageButtonDiscard.setImageResource(R.drawable.ic_delete_gray_24dp);
        imageButtonDiscard.setEnabled(false);
    }

    private void buttons_postrecording() {
        imageButtonRecord.setImageResource(R.drawable.ic_fiber_manual_record_pink_24dp);
        imageButtonRecord.setEnabled(true);
        imageButtonSave.setEnabled(true);
        imageButtonSave.setImageResource(R.drawable.ic_save_black_24dp);
        imageButtonDiscard.setEnabled(true);
        imageButtonDiscard.setImageResource(R.drawable.ic_delete_black_24dp);
    }

    private void setConnectionStatus(boolean connected) {
        MenuItem menuItem = menu.findItem(R.id.scan);
        MenuItem menuItemNotify = menu.findItem(R.id.notify);
        MenuItem menuItemCast = menu.findItem(R.id.cast);
        MenuItem menuItemCentering = menu.findItem(R.id.centering);

        if (connected) {
            menuItem.setIcon(R.drawable.ic_bluetooth_connected_blue_24dp);
            mConnectionState.setText(R.string.device_connected);
            mConnectionState.setTextColor(Color.GREEN);
            switch_plots.setEnabled(true);
            gain_spinner.setEnabled(true);
            viewDeviceAddress.setText(mDeviceAddress);
            menuItemNotify.setVisible(true);
            menuItemCast.setVisible(true);
            menuItemCentering.setVisible(true);
        } else {
            menuItem.setIcon(R.drawable.ic_bluetooth_searching_white_24dp);
            mConnectionState.setText(R.string.no_device);
            mConnectionState.setTextColor(Color.LTGRAY);
            buttons_nodata();
            switch_plots.setEnabled(false);
            gain_spinner.setEnabled(false);
            viewDeviceAddress.setText(R.string.no_address);
            menuItemNotify.setVisible(false);
            menuItemCast.setVisible(false);
            menuItemCentering.setVisible(false);
        }
    }

    private void getChannelColors() {
        // If you figure out a way to do this in a for loop, please feel free to make this better.
        channelColors[0] = ContextCompat.getColor(this, R.color.Ch1);
        channelColors[1] = ContextCompat.getColor(this, R.color.Ch2);
        channelColors[2] = ContextCompat.getColor(this, R.color.Ch3);
        channelColors[3] = ContextCompat.getColor(this, R.color.Ch4);
        channelColors[4] = ContextCompat.getColor(this, R.color.Ch5);
        channelColors[5] = ContextCompat.getColor(this, R.color.Ch6);
        channelColors[6] = ContextCompat.getColor(this, R.color.Ch7);
        channelColors[7] = ContextCompat.getColor(this, R.color.Ch8);
        channelColors[8] = ContextCompat.getColor(this, R.color.Ch9);
        channelColors[9] = ContextCompat.getColor(this, R.color.Ch10);
        channelColors[10] = ContextCompat.getColor(this, R.color.Ch11);
        channelColors[11] = ContextCompat.getColor(this, R.color.Ch12);
        channelColors[12] = ContextCompat.getColor(this, R.color.Ch13);
        channelColors[13] = ContextCompat.getColor(this, R.color.Ch14);
        channelColors[14] = ContextCompat.getColor(this, R.color.Ch15);
        channelColors[15] = ContextCompat.getColor(this, R.color.Ch16);
        channelColors[16] = ContextCompat.getColor(this, R.color.Ch17);
        channelColors[17] = ContextCompat.getColor(this, R.color.Ch18);
        channelColors[18] = ContextCompat.getColor(this, R.color.Ch19);
        channelColors[19] = ContextCompat.getColor(this, R.color.Ch20);
        channelColors[20] = ContextCompat.getColor(this, R.color.Ch21);
        channelColors[21] = ContextCompat.getColor(this, R.color.Ch22);
        channelColors[22] = ContextCompat.getColor(this, R.color.Ch23);
        channelColors[23] = ContextCompat.getColor(this, R.color.Ch24);
    }


    class CastThread extends Thread {
        String IP = getSharedPreferences("userPreferences", 0).getString("IP", getResources().getString(R.string.default_IP));
        String PORT = getSharedPreferences("userPreferences", 0).getString("port", getResources().getString(R.string.default_port));
        // best way found until now to encode the values, a stringified JSON. Looks like:
        JSONObject toSend = new JSONObject();
//        private volatile boolean exit = false;
        // {'pkg': 1, 'time': 1589880540884, '1': -149.85352, '2': -18.530273, '3': 191.74805, '4': -305.34668, '5': 0, '6': -142.60254, '7': -1.6113281, '8': -29.80957}

        public void run() {
            try {
                WSClient c = new WSClient(new URI("ws://" + IP + ":" + PORT));
                c.setReuseAddr(true);
                // c.setConnectionLostTimeout(0); // default is 60 seconds
                // TODO: check if TCP_NODELAY improves speed, also .connect() vs .connectBlocking()
                // TODO: Add connect/disconnect control by cast button pressed and message received
                c.setTcpNoDelay(true);
                c.connectBlocking();
                int pkg = 0;
                List<Float> lastV = null; // store last octet of EEG values
                while (c.isOpen()) {
                    if (microV != null && lastV != microV) {
                        toSend = new JSONObject();
                        // timestamp in milliseconds since January 1, 1970, 00:00:00 GMT
                        long time = new Date().getTime();
                        toSend.put("pkg", pkg); // add pkg number
                        toSend.put("time", time); // add time
                        for (int i = 0; i < microV.size(); i++) {
                            // add voltage amplitudes
                            toSend.put(Integer.toString(i + 1), microV.get(i));
                        }
                        c.send(toSend.toString());
                        lastV = microV; // store current as last
                        pkg++; // increase package counter
//                        Log.d("WS", "Sent: " + toSend.toString());
                    }
                }
            } catch (URISyntaxException | JSONException | InterruptedException e) {
                e.printStackTrace();
                Log.d("WS", "URI error:" + e);
            }
        }

        public void staph() {

            Log.d("CastThread", "Stopped");
//            exit = true;
            if (out != null) {
                out.close();
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }

}
