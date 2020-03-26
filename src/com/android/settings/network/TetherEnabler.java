/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.network;

import static android.net.ConnectivityManager.TETHERING_BLUETOOTH;
import static android.net.ConnectivityManager.TETHERING_USB;
import static android.net.ConnectivityManager.TETHERING_WIFI;

import static com.android.settings.AllInOneTetherSettings.DEDUP_POSTFIX;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothPan;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settings.datausage.DataSaverBackend;
import com.android.settings.widget.SwitchWidgetController;

import java.lang.annotation.Retention;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TetherEnabler is a helper to manage Tethering switch on/off state. It offers helper functions to
 * turn on/off different types of tethering interfaces and ensures tethering state updated by data
 * saver state.
 *
 * This class is not designed for extending. It's extendable solely for the test purpose.
 */

public class TetherEnabler implements SwitchWidgetController.OnSwitchChangeListener,
        DataSaverBackend.Listener, LifecycleObserver {

    /**
     * Interface definition for a callback to be invoked when the tethering has been updated.
     */
    public interface OnTetherStateUpdateListener {
        /**
         * Called when the tethering state has changed.
         *
         * @param state The new tethering state.
         */
        void onTetherStateUpdated(@TetheringState int state);
    }

    private static final String TAG = "TetherEnabler";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);


    @Retention(SOURCE)
    @IntDef(
            flag = true,
            value = {TETHERING_OFF, TETHERING_WIFI_ON, TETHERING_USB_ON, TETHERING_BLUETOOTH_ON}
    )
    @interface TetheringState {}
    @VisibleForTesting
    static final int TETHERING_OFF = 0;
    @VisibleForTesting
    static final int TETHERING_WIFI_ON = 1;
    @VisibleForTesting
    static final int TETHERING_USB_ON = 1 << 1;
    @VisibleForTesting
    static final int TETHERING_BLUETOOTH_ON = 1 << 2;

    // This KEY is used for a shared preference value, not for any displayed preferences.
    public static final String KEY_ENABLE_WIFI_TETHERING = "enable_wifi_tethering";
    public static final String WIFI_TETHER_DISABLE_KEY = "disable_wifi_tethering";
    public static final String USB_TETHER_KEY = "enable_usb_tethering";
    public static final String BLUETOOTH_TETHER_KEY = "enable_bluetooth_tethering" + DEDUP_POSTFIX;

    @VisibleForTesting
    final List<OnTetherStateUpdateListener> mListeners;
    private final SwitchWidgetController mSwitchWidgetController;
    private final WifiManager mWifiManager;
    private final ConnectivityManager mConnectivityManager;
    private final UserManager mUserManager;

    private final DataSaverBackend mDataSaverBackend;
    private boolean mDataSaverEnabled;
    @VisibleForTesting
    boolean mBluetoothTetheringStoppedByUser;

    private final Context mContext;

    @VisibleForTesting
    ConnectivityManager.OnStartTetheringCallback mOnStartTetheringCallback;
    private final AtomicReference<BluetoothPan> mBluetoothPan;
    private boolean mBluetoothEnableForTether;
    private final BluetoothAdapter mBluetoothAdapter;

    public TetherEnabler(Context context, SwitchWidgetController switchWidgetController,
            AtomicReference<BluetoothPan> bluetoothPan) {
        mContext = context;
        mSwitchWidgetController = switchWidgetController;
        mDataSaverBackend = new DataSaverBackend(context);
        mConnectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothPan = bluetoothPan;
        mDataSaverEnabled = mDataSaverBackend.isDataSaverEnabled();
        mListeners = new ArrayList<>();
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onStart() {
        mDataSaverBackend.addListener(this);
        mSwitchWidgetController.setListener(this);
        mSwitchWidgetController.startListening();

        final IntentFilter filter = new IntentFilter(
                ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiver(mTetherChangeReceiver, filter);

        mOnStartTetheringCallback = new OnStartTetheringCallback(this);
        updateState(null/*tethered*/);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onStop() {
        mBluetoothTetheringStoppedByUser = false;
        mDataSaverBackend.remListener(this);
        mSwitchWidgetController.stopListening();
        mContext.unregisterReceiver(mTetherChangeReceiver);
    }

    public void addListener(OnTetherStateUpdateListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            listener.onTetherStateUpdated(getTetheringState(null /* tethered */));
            mListeners.add(listener);
        }
    }

    public void removeListener(OnTetherStateUpdateListener listener) {
        if (listener != null) {
            mListeners.remove(listener);
        }
    }

    private void setSwitchEnabled(boolean enabled) {
        mSwitchWidgetController.setEnabled(
                enabled && !mDataSaverEnabled && mUserManager.isAdminUser());
    }

    @VisibleForTesting
    void updateState(@Nullable String[] tethered) {
        int state = getTetheringState(tethered);
        if (DEBUG) {
            Log.d(TAG, "updateState: " + state);
        }
        setSwitchCheckedInternal(state != TETHERING_OFF);
        setSwitchEnabled(true);
        for (int i = 0, size = mListeners.size(); i < size; ++i) {
            mListeners.get(i).onTetherStateUpdated(state);
        }
    }

    private void setSwitchCheckedInternal(boolean checked) {
        mSwitchWidgetController.stopListening();
        mSwitchWidgetController.setChecked(checked);
        mSwitchWidgetController.startListening();
    }

    @VisibleForTesting
    @TetheringState int getTetheringState(@Nullable String[] tethered) {
        int tetherState = TETHERING_OFF;
        if (tethered == null) {
            tethered = mConnectivityManager.getTetheredIfaces();
        }

        if (mWifiManager.isWifiApEnabled()) {
            tetherState |= TETHERING_WIFI_ON;
        }

        // Only check bluetooth tethering state if not stopped by user already.
        if (!mBluetoothTetheringStoppedByUser) {
            final BluetoothPan pan = mBluetoothPan.get();
            if (mBluetoothAdapter.getState() == BluetoothAdapter.STATE_ON
                    && pan != null && pan.isTetheringOn()) {
                tetherState |= TETHERING_BLUETOOTH_ON;
            }
        }

        String[] usbRegexs = mConnectivityManager.getTetherableUsbRegexs();
        for (String s : tethered) {
            for (String regex : usbRegexs) {
                if (s.matches(regex)) {
                    return tetherState | TETHERING_USB_ON;
                }
            }
        }

        return tetherState;
    }

    public static boolean isBluetoothTethering(@TetheringState int state) {
        return (state & TETHERING_BLUETOOTH_ON) != TETHERING_OFF;
    }

    public static boolean isUsbTethering(@TetheringState int state) {
        return (state & TETHERING_USB_ON) != TETHERING_OFF;
    }

    public static boolean isWifiTethering(@TetheringState int state) {
        return (state & TETHERING_WIFI_ON) != TETHERING_OFF;
    }

    @Override
    public boolean onSwitchToggled(boolean isChecked) {
        if (isChecked) {
            startTethering(TETHERING_WIFI);
        } else {
            stopTethering(TETHERING_USB);
            stopTethering(TETHERING_WIFI);
            stopTethering(TETHERING_BLUETOOTH);
        }
        return true;
    }

    public void stopTethering(int choice) {
        int state = getTetheringState(null /* tethered */);
        if ((choice == TETHERING_WIFI && isWifiTethering(state))
                || (choice == TETHERING_USB && isUsbTethering(state))
                || (choice == TETHERING_BLUETOOTH && isBluetoothTethering(state))) {
            setSwitchEnabled(false);
            mConnectivityManager.stopTethering(choice);
            if (choice == TETHERING_BLUETOOTH) {
                // Stop bluetooth tether won't invoke tether state changed callback, so we need this
                // boolean to remember the user action and update UI state immediately.
                mBluetoothTetheringStoppedByUser = true;
                updateState(null /* tethered */);
            }
        }
    }

    public void startTethering(int choice) {
        int state = getTetheringState(null /* tethered */);
        if ((choice == TETHERING_WIFI && isWifiTethering(state))
                || (choice == TETHERING_USB && isUsbTethering(state))) {
            return;
        }

        if (choice == TETHERING_BLUETOOTH) {
            mBluetoothTetheringStoppedByUser = false;
            if (isBluetoothTethering(state)) {
                return;
            } else if (mBluetoothAdapter.getState() == BluetoothAdapter.STATE_OFF) {
                if (DEBUG) {
                    Log.d(TAG, "Turn on bluetooth first.");
                }
                mBluetoothEnableForTether = true;
                mBluetoothAdapter.enable();
                return;
            }
        }

        setSwitchEnabled(false);
        mConnectivityManager.startTethering(choice, true /* showProvisioningUi */,
                mOnStartTetheringCallback, new Handler(Looper.getMainLooper()));
    }

    private final BroadcastReceiver mTetherChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            ArrayList<String> active = null;
            boolean shouldUpdateState = false;
            if (TextUtils.equals(ConnectivityManager.ACTION_TETHER_STATE_CHANGED, action)) {
                active = intent.getStringArrayListExtra(ConnectivityManager.EXTRA_ACTIVE_TETHER);
                shouldUpdateState = true;
            } else if (TextUtils.equals(WifiManager.WIFI_AP_STATE_CHANGED_ACTION, action)) {
                shouldUpdateState = handleWifiApStateChanged(intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_AP_STATE, WifiManager.WIFI_AP_STATE_FAILED));
            } else if (TextUtils.equals(BluetoothAdapter.ACTION_STATE_CHANGED, action)) {
                shouldUpdateState = handleBluetoothStateChanged(intent
                        .getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR));
            }

            if (shouldUpdateState) {
                if (active != null) {
                    updateState(active.toArray(new String[0]));
                } else {
                    updateState(null/*tethered*/);
                }
            }
        }
    };

    private boolean handleBluetoothStateChanged(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_ON:
                if (mBluetoothEnableForTether) {
                    startTethering(TETHERING_BLUETOOTH);
                }
                // Fall through.
            case BluetoothAdapter.STATE_OFF:
                // Fall through.
            case BluetoothAdapter.ERROR:
                mBluetoothEnableForTether = false;
                return true;
            default:
                // Return false for transition states.
                return false;
        }
    }

    private boolean handleWifiApStateChanged(int state) {
        switch (state) {
            case WifiManager.WIFI_AP_STATE_FAILED:
                Log.e(TAG, "Wifi AP is failed!");
                // fall through
            case WifiManager.WIFI_AP_STATE_ENABLED:
                // fall through
            case WifiManager.WIFI_AP_STATE_DISABLED:
                return true;
            default:
                // return false for transition state
                return false;
        }
    }

    @Override
    public void onDataSaverChanged(boolean isDataSaving) {
        mDataSaverEnabled = isDataSaving;
        setSwitchEnabled(true);
    }

    @Override
    public void onWhitelistStatusChanged(int uid, boolean isWhitelisted) {
        // we don't care, since we just want to read the value
    }

    @Override
    public void onBlacklistStatusChanged(int uid, boolean isBlacklisted) {
        // we don't care, since we just want to read the value
    }

    private static final class OnStartTetheringCallback extends
            ConnectivityManager.OnStartTetheringCallback {
        final WeakReference<TetherEnabler> mTetherEnabler;

        OnStartTetheringCallback(TetherEnabler enabler) {
            mTetherEnabler = new WeakReference<>(enabler);
        }

        @Override
        public void onTetheringStarted() {
            update();
        }

        @Override
        public void onTetheringFailed() {
            update();
        }

        private void update() {
            TetherEnabler enabler = mTetherEnabler.get();
            if (enabler != null) {
                enabler.updateState(null/*tethered*/);
            }
        }
    }
}
