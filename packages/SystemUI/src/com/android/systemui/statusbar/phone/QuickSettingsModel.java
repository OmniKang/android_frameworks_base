/*
 * Copyright (C) 2012 The Android Open Source Project
 * This code has been modified. Portions copyright (C) 2013, OmniRom Project.
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

package com.android.systemui.statusbar.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.BluetoothStateChangeCallback;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.SyncStatusObserver;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.util.omni.OmniTorchConstants;
import com.android.systemui.R;
import com.android.systemui.settings.BrightnessController.BrightnessStateChangeCallback;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationController.LocationSettingsChangeCallback;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class QuickSettingsModel implements BluetoothStateChangeCallback,
        NetworkSignalChangedCallback,
        BatteryStateChangeCallback,
        BrightnessStateChangeCallback,
        RotationLockControllerCallback,
        LocationSettingsChangeCallback {
    // Sett InputMethoManagerService
    private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";

    /** Represents the state of a given attribute. */
    static class State {
        int iconId;
        String label;
        boolean enabled = false;
        int mode;
    }
    static class BatteryState extends State {
        int batteryLevel;
        boolean pluggedIn;
    }
    static class BatteryBackState extends State {
        String healthString;
        String voltage;
        String temperature;
    }
    static class ActivityState extends State {
        boolean activityIn;
        boolean activityOut;
    }
    static class RSSIState extends ActivityState {
        int signalIconId;
        String signalContentDescription;
        int dataTypeIconId;
        String dataContentDescription;
    }
    static class WifiState extends ActivityState {
        String signalContentDescription;
        boolean connected;
    }
    static class UserState extends State {
        Drawable avatar;
    }
    static class BrightnessState extends State {
        boolean autoBrightness;
    }
    static class ImmersiveState extends State {
        boolean isEnabled;
    }
    static class QuiteHourState extends State {
        boolean isEnabled;
    }
    public static class BluetoothState extends State {
        boolean connected = false;
        String stateContentDescription;
    }
    public static class RotationLockState extends State {
        boolean visible = false;
    }

    /** The callback to update a given tile. */
    interface RefreshCallback {
        public void refreshView(QuickSettingsTileView view, State state);
    }

    public static class BasicRefreshCallback implements RefreshCallback {
        private final QuickSettingsBasicTile mView;
        private boolean mShowWhenEnabled;

        public BasicRefreshCallback(QuickSettingsBasicTile v) {
            mView = v;
        }
        public void refreshView(QuickSettingsTileView ignored, State state) {
            if (mShowWhenEnabled) {
                mView.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
            }
            if (state.iconId != 0) {
                mView.setImageDrawable(null); // needed to flush any cached IDs
                mView.setImageResource(state.iconId);
            }
            if (state.label != null) {
                mView.setText(state.label);
            }
        }
        public BasicRefreshCallback setShowWhenEnabled(boolean swe) {
            mShowWhenEnabled = swe;
            return this;
        }
    }

    /** Broadcast receive to determine if there is an alarm set. */
    private BroadcastReceiver mAlarmIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_ALARM_CHANGED)) {
                onAlarmChanged(intent);
                onNextAlarmChanged();
            }
        }
    };

    /** Broadcast receive to determine usb tether. */
    private BroadcastReceiver mUsbIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(UsbManager.ACTION_USB_STATE)) {
                mUsbConnected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
            }

            if (intent.getAction().equals(Intent.ACTION_MEDIA_SHARED)) {
                mMassStorageActive = true;
            }

            if (intent.getAction().equals(Intent.ACTION_MEDIA_UNSHARED)) {
                mMassStorageActive = false;
            }

            onUsbChanged();
        }
    };

    /** Broadcast receive to determine torch. */
    private BroadcastReceiver mTorchIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mTorchActive = intent.getIntExtra(OmniTorchConstants.EXTRA_CURRENT_STATE, 0) != 0;
            onTorchChanged();
        }
    };

    /** Broadcast receive to determine ringer. */
    private BroadcastReceiver mRingerIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                updateRingerState();
            }
        }
    };

    /** Broadcast receive to determine battery. */
    private BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Resources r = mContext.getResources();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                int plugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
                mBatteryBackState.voltage = "" + intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) + " "
                        + r.getString(R.string.quick_settings_battery_voltage_units);
                mBatteryBackState.temperature = "" + tenthsToFixedString(intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0))
                        + r.getString(R.string.quick_settings_battery_temperature_units);

                int health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
                if (health == BatteryManager.BATTERY_HEALTH_GOOD) {
                    mBatteryBackState.healthString = r.getString(R.string.quick_settings_battery_health_good);
                } else if (health == BatteryManager.BATTERY_HEALTH_OVERHEAT) {
                    mBatteryBackState.healthString = r.getString(R.string.quick_settings_battery_health_overheat);
                } else if (health == BatteryManager.BATTERY_HEALTH_DEAD) {
                    mBatteryBackState.healthString = r.getString(R.string.quick_settings_battery_health_dead);
                } else if (health == BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE) {
                    mBatteryBackState.healthString = r.getString(R.string.quick_settings_battery_health_over_voltage);
                } else if (health == BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE) {
                    mBatteryBackState.healthString = r.getString(R.string.quick_settings_battery_health_unspecified_failure);
                } else if (health == BatteryManager.BATTERY_HEALTH_COLD) {
                    mBatteryBackState.healthString = r.getString(R.string.quick_settings_battery_health_cold);
                } else {
                    mBatteryBackState.healthString = r.getString(R.string.quick_settings_battery_health_unknown);
                }

                refreshBatteryBackTile();
            }
        }
    };

    /** ContentObserver to determine the ringer */
    private class RingerObserver extends ContentObserver {
        public RingerObserver(Handler handler) {
            super(handler);
        }

        @Override 
        public void onChange(boolean selfChange) {
            updateRingerState();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.VIBRATE_WHEN_RINGING), false, this,
                    UserHandle.USER_ALL);
        }
    }

    /** ContentObserver to determine the SleepTime */
    private class SleepObserver extends ContentObserver {
        public SleepObserver(Handler handler) {
            super(handler);
        }

        @Override 
        public void onChange(boolean selfChange) {
            updateSleepState();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), false, this,
                    UserHandle.USER_ALL);
        }
    }

    /** ContentObserver to determine the next alarm */
    private class NextAlarmObserver extends ContentObserver {
        public NextAlarmObserver(Handler handler) {
            super(handler);
        }

        @Override public void onChange(boolean selfChange) {
            onNextAlarmChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NEXT_ALARM_FORMATTED), false, this,
                    UserHandle.USER_ALL);
        }
    }

    /** ContentObserver to watch adb */
    private class BugreportObserver extends ContentObserver {
        public BugreportObserver(Handler handler) {
            super(handler);
        }

        @Override public void onChange(boolean selfChange) {
            onBugreportChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BUGREPORT_IN_POWER_MENU), false, this);
        }
    }

    /** ContentObserver to watch brightness **/
    private class BrightnessObserver extends ContentObserver {
        public BrightnessObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onBrightnessLevelChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, this, mUserTracker.getCurrentUserId());
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }

    /** ContentObserver to watch Network State */
    private class NetworkObserver extends ContentObserver {
        public NetworkObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onMobileNetworkChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.PREFERRED_NETWORK_MODE), false, this);
        }
    }

    /** ContentObserver to watch immersive **/
    private class ImmersiveObserver extends ContentObserver {
        public ImmersiveObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onImmersiveChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.IMMERSIVE_MODE),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }

    /** ContentObserver to watch quitehour **/
    private class QuiteHourObserver extends ContentObserver {
        public QuiteHourObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onQuiteHourChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUIET_HOURS_ENABLED),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }

    /** Callback for changes to remote display routes. */
    private class RemoteDisplayRouteCallback extends MediaRouter.SimpleCallback {
        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo route) {
            updateRemoteDisplays();
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final CurrentUserTracker mUserTracker;
    private final NextAlarmObserver mNextAlarmObserver;
    private final BugreportObserver mBugreportObserver;
    private final BrightnessObserver mBrightnessObserver;
    private final ImmersiveObserver mImmersiveObserver;
    private final QuiteHourObserver mQuiteHourObserver;
    private final RingerObserver mRingerObserver;
    private LocationController mLocationController;
    private final SleepObserver mSleepObserver;
    private final NetworkObserver mMobileNetworkObserver;

    private ConnectivityManager mCM;
    private TelephonyManager mTM;
    private WifiManager mWifiManager;

    private boolean mUsbTethered = false;
    private boolean mUsbConnected = false;
    private boolean mMassStorageActive = false;
    private boolean mTorchActive = false;
    private String[] mUsbRegexs;
    private Object mSyncObserverHandle = null;

    // Sleep: Screen timeout sub-tile resources
    private static final int SCREEN_TIMEOUT_15     =   15000;
    private static final int SCREEN_TIMEOUT_30     =   30000;
    private static final int SCREEN_TIMEOUT_60     =   60000;
    private static final int SCREEN_TIMEOUT_120    =  120000;
    private static final int SCREEN_TIMEOUT_300    =  300000;
    private static final int SCREEN_TIMEOUT_600    =  600000;
    private static final int SCREEN_TIMEOUT_1800   = 1800000;

    private static final Ringer[] RINGERS = new Ringer[] {
        new Ringer(AudioManager.RINGER_MODE_SILENT, false, R.drawable.ic_qs_ring_off, R.string.quick_settings_ringer_off),
        new Ringer(AudioManager.RINGER_MODE_VIBRATE, true, R.drawable.ic_qs_vibrate_on, R.string.quick_settings_vibration_on),
        new Ringer(AudioManager.RINGER_MODE_NORMAL, false, R.drawable.ic_qs_ring_on, R.string.quick_settings_ringer_on),
        new Ringer(AudioManager.RINGER_MODE_NORMAL, true, R.drawable.ic_qs_ring_vibrate_on, R.string.quick_settings_ringer_normal)
    };

    private ArrayList<Ringer> mRingers;
    private int mRingerIndex;

    private AudioManager mAudioManager;
    private Vibrator mVibrator;

    private final MediaRouter mMediaRouter;
    private final RemoteDisplayRouteCallback mRemoteDisplayRouteCallback;

    private final boolean mHasMobileData;
    private long mLastClickTime = 0;

    private boolean mRibbon = false;

    private QuickSettingsTileView mUserTile;
    private RefreshCallback mUserCallback;
    private UserState mUserState = new UserState();

    private QuickSettingsTileView mTimeTile;
    private RefreshCallback mTimeCallback;
    private State mTimeState = new State();

    private QuickSettingsTileView mAlarmTile;
    private RefreshCallback mAlarmCallback;
    private State mAlarmState = new State();

    private QuickSettingsTileView mAirplaneModeTile;
    private RefreshCallback mAirplaneModeCallback;
    private State mAirplaneModeState = new State();

    private QuickSettingsTileView mSyncModeTile;
    private RefreshCallback mSyncModeCallback;
    private State mSyncModeState = new State();

    private QuickSettingsTileView mRingerModeTile;
    private RefreshCallback mRingerModeCallback;
    private State mRingerModeState = new State();

    private QuickSettingsTileView mSleepModeTile;
    private RefreshCallback mSleepModeCallback;
    private State mSleepModeState = new State();

    private QuickSettingsTileView mUsbModeTile;
    private RefreshCallback mUsbModeCallback;
    private State mUsbModeState = new State();

    private QuickSettingsTileView mTorchTile;
    private RefreshCallback mTorchCallback;
    private State mTorchState = new State();

    private QuickSettingsTileView mWifiTile;
    private QuickSettingsTileView mWifiBackTile;
    private RefreshCallback mWifiCallback;
    private RefreshCallback mWifiBackCallback;
    private WifiState mWifiState = new WifiState();
    private WifiState mWifiBackState = new WifiState();

    private QuickSettingsTileView mRemoteDisplayTile;
    private RefreshCallback mRemoteDisplayCallback;
    private State mRemoteDisplayState = new State();

    private QuickSettingsTileView mRSSITile;
    private RefreshCallback mRSSICallback;
    private RSSIState mRSSIState = new RSSIState();

    private QuickSettingsTileView mMobileNetworkTile;
    private RefreshCallback mMobileNetworkCallback;
    private State mMobileNetworkState = new State();

    private QuickSettingsTileView mBluetoothTile;
    private QuickSettingsTileView mBluetoothBackTile;
    private RefreshCallback mBluetoothCallback;
    private RefreshCallback mBluetoothBackCallback;
    private BluetoothState mBluetoothState = new BluetoothState();
    private BluetoothState mBluetoothBackState = new BluetoothState();

    private QuickSettingsTileView mBatteryTile;
    private RefreshCallback mBatteryCallback;
    private BatteryState mBatteryState = new BatteryState();

    private QuickSettingsTileView mBatteryBackTile;
    private RefreshCallback mBatteryBackCallback;
    private BatteryBackState mBatteryBackState = new BatteryBackState();

    private QuickSettingsTileView mLocationTile;
    private RefreshCallback mLocationCallback;
    private State mLocationState = new State();

    private QuickSettingsTileView mBackLocationTile;
    private RefreshCallback mBackLocationCallback;
    private State mBackLocationState = new State();

    private QuickSettingsTileView mImeTile;
    private RefreshCallback mImeCallback = null;
    private State mImeState = new State();

    private QuickSettingsTileView mRotationLockTile;
    private RefreshCallback mRotationLockCallback;
    private RotationLockState mRotationLockState = new RotationLockState();

    private QuickSettingsTileView mBrightnessTile;
    private RefreshCallback mBrightnessCallback;
    private BrightnessState mBrightnessState = new BrightnessState();

    private QuickSettingsTileView mImmersiveTile;
    private RefreshCallback mImmersiveCallback;
    private ImmersiveState mImmersiveState = new ImmersiveState();

    private QuickSettingsTileView mQuiteHourTile;
    private RefreshCallback mQuiteHourCallback;
    private QuiteHourState mQuiteHourState = new QuiteHourState();

    private QuickSettingsTileView mBugreportTile;
    private RefreshCallback mBugreportCallback;
    private State mBugreportState = new State();

    private QuickSettingsTileView mSettingsTile;
    private RefreshCallback mSettingsCallback;
    private State mSettingsState = new State();

    private QuickSettingsTileView mSslCaCertWarningTile;
    private RefreshCallback mSslCaCertWarningCallback;
    private State mSslCaCertWarningState = new State();

    private RotationLockController mRotationLockController;

    private SyncStatusObserver mSyncObserver = new SyncStatusObserver() {
        public void onStatusChanged(int which) {
            // update state/view if something happened
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateSyncState();
                }
            });
        }
    };

    public QuickSettingsModel(Context context, boolean ribbon) {
        mContext = context;
        mRibbon = ribbon;
        mHandler = new Handler();
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mBrightnessObserver.startObserving();
                mImmersiveObserver.startObserving();
                mQuiteHourObserver.startObserving();
                mSleepObserver.startObserving();
                mRingerObserver.startObserving();
                mSleepObserver.startObserving();
                mMobileNetworkObserver.startObserving();
                refreshRotationLockTile();
                onBrightnessLevelChanged();
                onNextAlarmChanged();
                onBugreportChanged();
                rebindMediaRouterAsCurrentUser();
                onUsbChanged();
            }
        };

        mNextAlarmObserver = new NextAlarmObserver(mHandler);
        mNextAlarmObserver.startObserving();
        mBugreportObserver = new BugreportObserver(mHandler);
        mBugreportObserver.startObserving();
        mBrightnessObserver = new BrightnessObserver(mHandler);
        mBrightnessObserver.startObserving();
        mImmersiveObserver = new ImmersiveObserver(mHandler);
        mImmersiveObserver.startObserving();
        mQuiteHourObserver = new QuiteHourObserver(mHandler);
        mQuiteHourObserver.startObserving();
        mSleepObserver = new SleepObserver(mHandler);
        mSleepObserver.startObserving();
        mRingerObserver = new RingerObserver(mHandler);
        mRingerObserver.startObserving();
        mMobileNetworkObserver = new NetworkObserver(mHandler);
        mMobileNetworkObserver.startObserving();

        mMediaRouter = (MediaRouter)context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        rebindMediaRouterAsCurrentUser();

        mRemoteDisplayRouteCallback = new RemoteDisplayRouteCallback();

        mCM = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mHasMobileData = mCM.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        mTM = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        IntentFilter alarmIntentFilter = new IntentFilter();
        alarmIntentFilter.addAction(Intent.ACTION_ALARM_CHANGED);
        context.registerReceiver(mAlarmIntentReceiver, alarmIntentFilter);

        IntentFilter usbIntentFilter = new IntentFilter();
        usbIntentFilter.addAction(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        usbIntentFilter.addAction(UsbManager.ACTION_USB_STATE);
        usbIntentFilter.addAction(Intent.ACTION_MEDIA_SHARED);
        usbIntentFilter.addAction(Intent.ACTION_MEDIA_UNSHARED);
        context.registerReceiver(mUsbIntentReceiver, usbIntentFilter);

        IntentFilter ringerIntentFilter = new IntentFilter();
        ringerIntentFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        context.registerReceiver(mRingerIntentReceiver, ringerIntentFilter);

        IntentFilter torchIntentFilter = new IntentFilter();
        torchIntentFilter.addAction(OmniTorchConstants.ACTION_STATE_CHANGED);
        context.registerReceiver(mTorchIntentReceiver, torchIntentFilter);

        if(mSyncObserverHandle != null) {
            //Unregistering sync state listener
            ContentResolver.removeStatusChangeListener(mSyncObserverHandle);
            mSyncObserverHandle = null;
        } else {
            // Registering sync state listener
            mSyncObserverHandle = ContentResolver.addStatusChangeListener(
                    ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, mSyncObserver);
        }

        mRingers = new ArrayList<Ringer>();
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);

        IntentFilter batteryFilter = new IntentFilter();
        batteryFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        context.registerReceiver(mBatteryReceiver, batteryFilter);
    }

    void updateResources() {
        refreshSettingsTile();
        refreshBatteryTile();
        refreshBatteryBackTile();
        refreshBluetoothTile();
        refreshBrightnessTile();
        refreshImmersiveTile();
        onQuiteHourChanged();
        refreshRotationLockTile();
        refreshRssiTile();
        refreshLocationTile();
        refreshBackLocationTile();
        updateSleepState();
        updateRingerState();
        updateSleepState();
        onMobileNetworkChanged();
    }

    // Settings
    void addSettingsTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSettingsTile = view;
        mSettingsCallback = cb;
        refreshSettingsTile();
    }

    void refreshSettingsTile() {
        Resources r = mContext.getResources();
        mSettingsState.label = r.getString(R.string.quick_settings_settings_label);
        mSettingsCallback.refreshView(mSettingsTile, mSettingsState);
    }

    // User
    void addUserTile(QuickSettingsTileView view, RefreshCallback cb) {
        mUserTile = view;
        mUserCallback = cb;
        mUserCallback.refreshView(mUserTile, mUserState);
    }

    void setUserTileInfo(String name, Drawable avatar) {
        mUserState.label = name;
        mUserState.avatar = avatar;
        mUserCallback.refreshView(mUserTile, mUserState);
    }

    // Time
    void addTimeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mTimeTile = view;
        mTimeCallback = cb;
        mTimeCallback.refreshView(view, mTimeState);
    }

    // Alarm
    void addAlarmTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAlarmTile = view;
        mAlarmCallback = cb;
        mAlarmCallback.refreshView(view, mAlarmState);
    }

    void onAlarmChanged(Intent intent) {
        if (mRibbon) return;

        mAlarmState.enabled = intent.getBooleanExtra("alarmSet", false);
        mAlarmCallback.refreshView(mAlarmTile, mAlarmState);
    }

    void onNextAlarmChanged() {
        if (mRibbon) return;

        final String alarmText = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.NEXT_ALARM_FORMATTED,
                UserHandle.USER_CURRENT);
        mAlarmState.label = alarmText;

        // When switching users, this is the only clue we're going to get about whether the
        // alarm is actually set, since we won't get the ACTION_ALARM_CHANGED broadcast
        mAlarmState.enabled = ! TextUtils.isEmpty(alarmText);

        mAlarmCallback.refreshView(mAlarmTile, mAlarmState);
    }

    // Usb Mode
    void addUsbModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mUsbModeTile = view;
        mUsbModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mUsbConnected) {
                    setUsbTethering(!mUsbTethered);
                }
            }
        });
        mUsbModeCallback = cb;
        onUsbChanged();
    }

    void onUsbChanged() {
        updateState();
        Resources r = mContext.getResources();
        if (mUsbConnected && !mMassStorageActive) {
            if (mUsbTethered) {
                mUsbModeState.iconId = R.drawable.ic_qs_usb_tether_on;
                mUsbModeState.label = r.getString(R.string.quick_settings_usb_tether_on_label);
            } else {
                mUsbModeState.iconId = R.drawable.ic_qs_usb_tether_connected;
                mUsbModeState.label = r.getString(R.string.quick_settings_usb_tether_connected_label);
            }
            mUsbModeState.enabled = true;
        } else {
            mUsbModeState.iconId = R.drawable.ic_qs_usb_tether_off;
            mUsbModeState.label = r.getString(R.string.quick_settings_usb_tether_off_label);
            mUsbModeState.enabled = false;
        }
        mUsbModeCallback.refreshView(mUsbModeTile, mUsbModeState);
    }

    // Torch Mode
    void addTorchTile(QuickSettingsTileView view, RefreshCallback cb) {
        mTorchTile = view;
        mTorchTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(OmniTorchConstants.ACTION_TOGGLE_STATE);
                mContext.sendBroadcast(i);
            }
        });
        mTorchCallback = cb;
        onTorchChanged();
    }

    void onTorchChanged() {
        Resources r = mContext.getResources();
        if (mTorchActive) {
            mTorchState.iconId = R.drawable.ic_qs_torch_on;
            mTorchState.label = r.getString(R.string.quick_settings_torch);
        } else {
            mTorchState.iconId = R.drawable.ic_qs_torch_off;
            mTorchState.label = r.getString(R.string.quick_settings_torch_off);
        }
        mTorchState.enabled = mTorchActive;
        mTorchCallback.refreshView(mTorchTile, mTorchState);
    }

    // Airplane Mode
    void addAirplaneModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAirplaneModeTile = view;
        mAirplaneModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAirplaneModeState.enabled) {
                    setAirplaneModeState(false);
                } else {
                    setAirplaneModeState(true);
                }
            }
        });
        mAirplaneModeCallback = cb;
        int airplaneMode = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);
        onAirplaneModeChanged(airplaneMode != 0);
    }
    private void setAirplaneModeState(boolean enabled) {
        // TODO: Sets the view to be "awaiting" if not already awaiting

        // Change the system setting
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON,
                                enabled ? 1 : 0);

        // Post the intent
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", enabled);
        mContext.sendBroadcast(intent);
    }
    // NetworkSignalChanged callback
    @Override
    public void onAirplaneModeChanged(boolean enabled) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mAirplaneModeState.enabled = enabled;
        mAirplaneModeState.iconId = (enabled ?
                R.drawable.ic_qs_airplane_on :
                R.drawable.ic_qs_airplane_off);
        mAirplaneModeState.label = r.getString(R.string.quick_settings_airplane_mode_label);
        mAirplaneModeCallback.refreshView(mAirplaneModeTile, mAirplaneModeState);
    }

    // Sync Mode
    void addSyncModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSyncModeTile = view;
        mSyncModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getSyncState()) {
                    ContentResolver.setMasterSyncAutomatically(false);
                } else {
                    ContentResolver.setMasterSyncAutomatically(true);
                }
                updateSyncState();
            }
        });
        mSyncModeCallback = cb;
        updateSyncState();
    }

    private boolean getSyncState() {
        return ContentResolver.getMasterSyncAutomatically();
    }

    private void updateSyncState() {
        Resources r = mContext.getResources();
        mSyncModeState.enabled = getSyncState();
        mSyncModeState.iconId = (getSyncState() ?
                R.drawable.ic_qs_sync_on :
                R.drawable.ic_qs_sync_off);
        mSyncModeState.label = (getSyncState() ?
                r.getString(R.string.quick_settings_sync) :
                r.getString(R.string.quick_settings_sync_off));
        mSyncModeCallback.refreshView(mSyncModeTile, mSyncModeState);
    }

    // Wifi
    void addWifiTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiTile = view;
        mWifiCallback = cb;
        mWifiCallback.refreshView(mWifiTile, mWifiState);
    }
    void addWifiBackTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiBackTile = view;
        mWifiBackTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCM.getTetherableWifiRegexs().length != 0) {
                    int state = mWifiManager.getWifiApState();
                    switch (state) {
                         case WifiManager.WIFI_AP_STATE_ENABLING:
                         case WifiManager.WIFI_AP_STATE_ENABLED:
                              setSoftapEnabled(false);
                              break;
                         case WifiManager.WIFI_AP_STATE_DISABLING:
                         case WifiManager.WIFI_AP_STATE_DISABLED:
                             setSoftapEnabled(true);
                             break;
                    }
                }
            }
        });
        mWifiBackCallback = cb;
        mWifiCallback.refreshView(mWifiBackTile, mWifiBackState);
    }

    private void setSoftapEnabled(boolean enable) {
        final ContentResolver cr = mContext.getContentResolver();
        /**
         * Disable Wifi if enabling tethering
         */
        int wifiState = mWifiManager.getWifiState();
        if (enable && ((wifiState == WifiManager.WIFI_STATE_ENABLING) ||
                    (wifiState == WifiManager.WIFI_STATE_ENABLED))) {
            mWifiManager.setWifiEnabled(false);
            Settings.Global.putInt(cr, Settings.Global.WIFI_SAVED_STATE, 1);
        }

        // Turn on the Wifi AP
        mWifiManager.setWifiApEnabled(null, enable);

        /**
         *  If needed, restore Wifi on tether disable
         */
        if (!enable) {
            int wifiSavedState = 0;
            try {
                wifiSavedState = Settings.Global.getInt(cr, Settings.Global.WIFI_SAVED_STATE);
            } catch (Settings.SettingNotFoundException e) {
                // Do nothing here
            }
            if (wifiSavedState == 1) {
                mWifiManager.setWifiEnabled(true);
                Settings.Global.putInt(cr, Settings.Global.WIFI_SAVED_STATE, 0);
            }
        }
    }

    private int getWifiApTypeIcon() {
        int state = mWifiManager.getWifiApState();
        switch (state) {
            case WifiManager.WIFI_AP_STATE_ENABLING:
            case WifiManager.WIFI_AP_STATE_ENABLED:
                return R.drawable.ic_qs_wifi_ap_on;
            case WifiManager.WIFI_AP_STATE_DISABLING:
            case WifiManager.WIFI_AP_STATE_DISABLED:
                return R.drawable.ic_qs_wifi_ap_off;
        }
        return R.drawable.ic_qs_wifi_no_network;
    }

    private String getWifiApString() {
        Resources r = mContext.getResources();
        int state = mWifiManager.getWifiApState();
        switch (state) {
            case WifiManager.WIFI_AP_STATE_ENABLING:
            case WifiManager.WIFI_AP_STATE_ENABLED:
                return r.getString(R.string.quick_settings_wifi_label);
            case WifiManager.WIFI_AP_STATE_DISABLING:
            case WifiManager.WIFI_AP_STATE_DISABLED:
                return r.getString(R.string.quick_settings_wifi_off_label);
        }
        return r.getString(R.string.quick_settings_wifi_off_label);
    }

    private boolean getWifiApEnabled() {
        int state = mWifiManager.getWifiApState();
        switch (state) {
            case WifiManager.WIFI_AP_STATE_ENABLING:
            case WifiManager.WIFI_AP_STATE_ENABLED:
                return true;
            case WifiManager.WIFI_AP_STATE_DISABLING:
            case WifiManager.WIFI_AP_STATE_DISABLED:
                return false;
        }
        return false;
    }

    // Remove the double quotes that the SSID may contain
    public static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }
    // Remove the period from the network name
    public static String removeTrailingPeriod(String string) {
        if (string == null) return null;
        final int length = string.length();
        if (string.endsWith(".")) {
            return string.substring(0, length - 1);
        }
        return string;
    }
    // NetworkSignalChanged callback
    @Override
    public void onWifiSignalChanged(boolean enabled, int wifiSignalIconId,
            boolean activityIn, boolean activityOut,
            String wifiSignalContentDescription, String enabledDesc) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();

        boolean wifiConnected = enabled && (wifiSignalIconId > 0) && (enabledDesc != null);
        boolean wifiNotConnected = (wifiSignalIconId > 0) && (enabledDesc == null);
        mWifiState.enabled = enabled;
        mWifiState.connected = wifiConnected;
        mWifiState.activityIn = enabled && activityIn;
        mWifiState.activityOut = enabled && activityOut;
        int wifiApIconId = getWifiApTypeIcon();
        String wifiApString = getWifiApString();
        if (wifiConnected) {
            mWifiState.iconId = wifiSignalIconId;
            mWifiState.label = removeDoubleQuotes(enabledDesc);
            mWifiState.signalContentDescription = wifiSignalContentDescription;

            wifiApIconId = wifiSignalIconId;
            wifiApString = getWifiIpAddr();
            mWifiBackState.signalContentDescription = wifiSignalContentDescription;
        } else if (wifiNotConnected) {
            mWifiState.iconId = R.drawable.ic_qs_wifi_0;
            mWifiState.label = r.getString(R.string.quick_settings_wifi_label);
            mWifiState.signalContentDescription = r.getString(R.string.accessibility_no_wifi);

            wifiApIconId = mWifiState.iconId;
            wifiApString = mWifiState.label;
            mWifiBackState.signalContentDescription = mWifiState.signalContentDescription;
        } else {
            mWifiState.iconId = R.drawable.ic_qs_wifi_no_network;
            mWifiState.label = r.getString(R.string.quick_settings_wifi_off_label);
            mWifiState.signalContentDescription = r.getString(R.string.accessibility_wifi_off);

            wifiApIconId = mWifiState.iconId;
            wifiApString = mWifiState.label;
            mWifiBackState.signalContentDescription = mWifiState.signalContentDescription;
        }
        mWifiBackState.iconId = getWifiApEnabled() ? getWifiApTypeIcon() : wifiApIconId;
        mWifiBackState.label = getWifiApEnabled() ? getWifiApString() : wifiApString;
        mWifiBackState.connected = getWifiApEnabled();
        mWifiCallback.refreshView(mWifiTile, mWifiState);
        mWifiBackCallback.refreshView(mWifiBackTile, mWifiBackState);
    }

    private boolean isWifiEnabled() {
        return mWifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED;
    }

    String getWifiIpAddr() {
        WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        int ip = wifiInfo.getIpAddress();

        String ipString = String.format(
            "%d.%d.%d.%d",
            (ip & 0xff),
            (ip >> 8 & 0xff),
            (ip >> 16 & 0xff),
            (ip >> 24 & 0xff));

        return ipString;
    }

    boolean deviceHasMobileData() {
        return mHasMobileData;
    }

    // RSSI
    void addRSSITile(QuickSettingsTileView view, RefreshCallback cb) {
        mRSSITile = view;
        mRSSICallback = cb;
        mRSSICallback.refreshView(mRSSITile, mRSSIState);
    }

    // NetworkSignalChanged callback
    @Override
    public void onMobileDataSignalChanged(
            boolean enabled, int mobileSignalIconId, String signalContentDescription,
            int dataTypeIconId, boolean activityIn, boolean activityOut,
            String dataContentDescription,String enabledDesc) {
        if (deviceHasMobileData()) {
            // TODO: If view is in awaiting state, disable
            Resources r = mContext.getResources();
            mRSSIState.signalIconId = enabled && (mobileSignalIconId > 0)
                    ? mobileSignalIconId
                    : R.drawable.ic_qs_signal_no_signal;
            mRSSIState.signalContentDescription = enabled && (mobileSignalIconId > 0)
                    ? signalContentDescription
                    : r.getString(R.string.accessibility_no_signal);
            mRSSIState.dataTypeIconId = enabled && (dataTypeIconId > 0) && !mWifiState.enabled
                    ? dataTypeIconId
                    : 0;
            mRSSIState.activityIn = enabled && activityIn;
            mRSSIState.activityOut = enabled && activityOut;
            mRSSIState.dataContentDescription = enabled && (dataTypeIconId > 0) && !mWifiState.enabled
                    ? dataContentDescription
                    : r.getString(R.string.accessibility_no_data);
            mRSSIState.label = enabled
                    ? removeTrailingPeriod(enabledDesc)
                    : r.getString(R.string.quick_settings_rssi_emergency_only);
            mRSSICallback.refreshView(mRSSITile, mRSSIState);
        }
        onMobileNetworkChanged();
    }

    void refreshRssiTile() {
        if (mRSSICallback == null) {
            return;
        }
        mRSSICallback.refreshView(mRSSITile, mRSSIState);
    }

    boolean deviceSupportsCDMALTE() {
        return (mTM.getLteOnCdmaMode() == PhoneConstants.LTE_ON_CDMA_TRUE);
    }

    boolean deviceSupportsGSMLTE() {
        return (mTM.getLteOnGsmMode() != 0);
    }

    // Mobile Network
    void addMobileNetworkTile(QuickSettingsTileView view, RefreshCallback cb) {
        mMobileNetworkTile = view;
        mMobileNetworkTile.setOnClickListener(new View.OnClickListener() {
              @Override
              public void onClick(View v) {
                  if (SystemClock.elapsedRealtime() - mLastClickTime < 1000){
                      return;
                  }
                  mLastClickTime = SystemClock.elapsedRealtime();

                  toggleMobileNetworkState();
              }
        });
        mMobileNetworkCallback = cb;
        onMobileNetworkChanged();
    }

    void onMobileNetworkChanged() {
        if (deviceHasMobileData()) {
            mMobileNetworkState.label = getNetworkType(mContext.getResources());
            mMobileNetworkState.iconId = getNetworkTypeIcon();
            mMobileNetworkState.enabled = true;
            mMobileNetworkCallback.refreshView(mMobileNetworkTile, mMobileNetworkState);
        }
    }

    private void toggleMobileNetworkState() {
        boolean usesQcLte = SystemProperties.getBoolean(
                        "ro.config.qc_lte_network_modes", false);
        int network = get2G3G();
        switch(network) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
                if (deviceSupportsGSMLTE()) {
                    mTM.toggleMobileNetwork(Phone.NT_MODE_GSM_ONLY);
                } else if (deviceSupportsCDMALTE()) {
                    mTM.toggleMobileNetwork(Phone.NT_MODE_CDMA);
                }
                break;
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
                mTM.toggleMobileNetwork(Phone.NT_MODE_CDMA_NO_EVDO);
                break;
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_EVDO_NO_CDMA:
                mTM.toggleMobileNetwork(Phone.NT_MODE_CDMA);
                break;
            case Phone.NT_MODE_CDMA:
                if (deviceSupportsCDMALTE()) {
                    if (usesQcLte) {
                        mTM.toggleMobileNetwork(Phone.NT_MODE_GLOBAL);
                    } else {
                        mTM.toggleMobileNetwork(Phone.NT_MODE_LTE_CDMA_AND_EVDO);
                    }
                } else {
                    mTM.toggleMobileNetwork(Phone.NT_MODE_CDMA_NO_EVDO);
                }
                break;
            case Phone.NT_MODE_GSM_UMTS:
                mTM.toggleMobileNetwork(Phone.NT_MODE_WCDMA_PREF);
                break;
            case Phone.NT_MODE_WCDMA_ONLY:
                mTM.toggleMobileNetwork(Phone.NT_MODE_GSM_UMTS);
                break;
            case Phone.NT_MODE_GSM_ONLY:
                mTM.toggleMobileNetwork(Phone.NT_MODE_WCDMA_ONLY);
                break;
            case Phone.NT_MODE_WCDMA_PREF:
                if (deviceSupportsGSMLTE()) {
                    if (usesQcLte) {
                        mTM.toggleMobileNetwork(Phone.NT_MODE_GLOBAL);
                    } else {
                        mTM.toggleMobileNetwork(Phone.NT_MODE_LTE_GSM_WCDMA);
                    }
                } else {
                    mTM.toggleMobileNetwork(Phone.NT_MODE_GSM_ONLY);
                }
                break;
        }
    }

    private String getNetworkType(Resources r) {
        boolean isEnabled = mCM.getMobileDataEnabled();

        int state = get2G3G();
        switch (state) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
                if (!isEnabled) {
                    return r.getString(R.string.network_4G) + r.getString(R.string.quick_settings_network_disable);
                } else {
                    return r.getString(R.string.network_4G);
                }
            case Phone.NT_MODE_GSM_UMTS:
                if (!isEnabled) {
                    return r.getString(R.string.network_3G_auto) + r.getString(R.string.quick_settings_network_disable);
                } else {
                    return r.getString(R.string.network_3G_auto);
                }
            case Phone.NT_MODE_WCDMA_ONLY:
                if (!isEnabled) {
                    return r.getString(R.string.network_3G_only) + r.getString(R.string.quick_settings_network_disable);
                } else {
                    return r.getString(R.string.network_3G_only);
                }
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_GSM_ONLY:
                if (!isEnabled) {
                    return r.getString(R.string.network_2G) + r.getString(R.string.quick_settings_network_disable);
                } else {
                    return r.getString(R.string.network_2G);
                }
            case Phone.NT_MODE_CDMA:
            case Phone.NT_MODE_WCDMA_PREF:
                if (!isEnabled) {
                    return r.getString(R.string.network_3G) + r.getString(R.string.quick_settings_network_disable);
                } else {
                    return r.getString(R.string.network_3G);
                }
        }
        return r.getString(R.string.quick_settings_network_unknown);
    }

    private int getNetworkTypeIcon() {
        int state = get2G3G();
        switch (state) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
                return R.drawable.ic_qs_lte_on;
            case Phone.NT_MODE_WCDMA_ONLY:
                return R.drawable.ic_qs_3g_on;
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_GSM_ONLY:
                return R.drawable.ic_qs_2g_on;
            case Phone.NT_MODE_CDMA:
            case Phone.NT_MODE_WCDMA_PREF:
            case Phone.NT_MODE_GSM_UMTS:
                return R.drawable.ic_qs_2g3g_on;
        }
        return R.drawable.ic_qs_unexpected_network;
    }

    private int get2G3G() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.PREFERRED_NETWORK_MODE, Phone.PREFERRED_NT_MODE);
    }

    // Bluetooth
    void addBluetoothTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBluetoothTile = view;
        mBluetoothCallback = cb;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothState.enabled = adapter.isEnabled();
        mBluetoothState.connected =
                (adapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED);
        onBluetoothStateChange(mBluetoothState);
    }
    void addBluetoothBackTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBluetoothBackTile = view;
        mBluetoothBackCallback = cb;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothBackState.enabled = adapter.isEnabled();
        mBluetoothBackState.connected =
                (adapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED);
        onBluetoothStateChange(mBluetoothBackState);
    }
    boolean deviceSupportsBluetooth() {
        return (BluetoothAdapter.getDefaultAdapter() != null);
    }
    // BluetoothController callback
    @Override
    public void onBluetoothStateChange(boolean on) {
        mBluetoothState.enabled = on;
        onBluetoothStateChange(mBluetoothState);
    }
    public void onBluetoothStateChange(BluetoothState bluetoothStateIn) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mBluetoothState.enabled = bluetoothStateIn.enabled;
        mBluetoothState.connected = bluetoothStateIn.connected;
        if (mBluetoothState.enabled) {
            if (mBluetoothState.connected) {
                mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_on;
                mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_connected);
            } else {
                mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_not_connected;
                mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_on);
            }
            mBluetoothState.label = r.getString(R.string.quick_settings_bluetooth_label);
        } else {
            mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_off;
            mBluetoothState.label = r.getString(R.string.quick_settings_bluetooth_off_label);
            mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_off);
        }

        // Back tile: Show paired devices
        if (mBluetoothBackTile != null) {
            boolean isPaired = false;
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            Set<BluetoothDevice> btDevices = adapter.getBondedDevices();
            if (btDevices.size() == 1) {
                // Show a generic label about the number of paired bluetooth devices
                isPaired = true;
                mBluetoothBackState.label = 
                    r.getString(R.string.quick_settings_bluetooth_number_paired, btDevices.size());
            } else {
                isPaired = false;
                mBluetoothBackState.label = r.getString(R.string.quick_settings_bluetooth_disabled);
            }
            mBluetoothBackState.iconId = isBluetoothPaired(isPaired && bluetoothStateIn.enabled);
        }

        mBluetoothCallback.refreshView(mBluetoothTile, mBluetoothState);

        if (mBluetoothBackTile != null) {
            mBluetoothBackCallback.refreshView(mBluetoothBackTile, mBluetoothBackState);
        }
    }

    private int isBluetoothPaired(boolean paired) {
        if (paired) {
            return R.drawable.ic_qs_bluetooth_paired_on;
        }
        return R.drawable.ic_qs_bluetooth_off;
    }

    void refreshBluetoothTile() {
        if (mBluetoothTile != null) {
            onBluetoothStateChange(mBluetoothState.enabled);
        }
    }

    // Battery
    void addBatteryTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBatteryTile = view;
        mBatteryCallback = cb;
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }

    void addBackBatteryTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBatteryBackTile = view;
        mBatteryBackCallback = cb;
        mBatteryBackCallback.refreshView(mBatteryBackTile, mBatteryBackState);
    }

    // BatteryController callback
    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn) {
        mBatteryState.batteryLevel = level;
        mBatteryState.pluggedIn = pluggedIn;
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }

    void refreshBatteryTile() {
        if (mBatteryCallback == null) {
            return;
        }
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }

    void refreshBatteryBackTile() {
        if (mBatteryBackCallback == null) {
            return;
        }
        mBatteryBackCallback.refreshView(mBatteryBackTile, mBatteryBackState);
    }

    /**
     * Format a number of tenths-units as a decimal string without using a
     * conversion to float.  E.g. 347 -> "34.7", -99 -> "-9.9"
     */
    private final String tenthsToFixedString(int x) {
        int tens = x / 10;
        // use Math.abs to avoid "-9.-9" about -99
        return Integer.toString(tens) + "." + Math.abs(x - 10 * tens);
    }

    // Location
    void addLocationTile(QuickSettingsTileView view, RefreshCallback cb) {
        mLocationTile = view;
        mLocationCallback = cb;
        mLocationCallback.refreshView(mLocationTile, mLocationState);
    }

    void refreshLocationTile() {
        if (mLocationTile != null) {
            onLocationSettingsChanged(mLocationState.enabled, mLocationState.mode);
        }
    }

    @Override
    public void onLocationSettingsChanged(boolean locationEnabled, int locationMode) {
        int textResId = locationEnabled ? R.string.quick_settings_location_label
                : R.string.quick_settings_location_off_label;
        String label = mContext.getText(textResId).toString();
        mLocationState.enabled = locationEnabled;
        mLocationState.mode = locationMode;
        mLocationState.label = label;
        mLocationState.iconId = getLocationDrawableMode(mLocationController.locationMode());
        mLocationCallback.refreshView(mLocationTile, mLocationState);
        refreshBackLocationTile();
    }

    void addBackLocationTile(QuickSettingsTileView view, LocationController controller, RefreshCallback cb) {
        mBackLocationTile = view;
        mLocationController = controller;
        mBackLocationCallback = cb;
        mBackLocationCallback.refreshView(mBackLocationTile, mBackLocationState);
    }

    void refreshBackLocationTile() {
        if (mBackLocationTile != null) {
            onBackLocationSettingsChanged(mLocationController.locationMode(), mLocationState.enabled);
        }
    }

    private void onBackLocationSettingsChanged(int mode, boolean locationEnabled) {
        mBackLocationState.enabled = locationEnabled;
        mBackLocationState.label = getLocationMode(mContext.getResources(), mode);
        mBackLocationState.iconId = getLocationDrawableMode(mode);
        mBackLocationCallback.refreshView(mBackLocationTile, mBackLocationState);
    }

    private int getLocationDrawableMode(int location) {
        switch (location) {
            case Settings.Secure.LOCATION_MODE_SENSORS_ONLY:
                return R.drawable.ic_qs_location_on_gps;
            case Settings.Secure.LOCATION_MODE_BATTERY_SAVING:
                return R.drawable.ic_qs_location_on_wifi;
            case Settings.Secure.LOCATION_MODE_HIGH_ACCURACY:
                return R.drawable.ic_qs_location_on;
        }
        return R.drawable.ic_qs_location_off;
    }

    private String getLocationMode(Resources r, int location) {
        switch (location) {
            case Settings.Secure.LOCATION_MODE_SENSORS_ONLY:
                return r.getString(R.string.quick_settings_location_mode_sensors);
            case Settings.Secure.LOCATION_MODE_BATTERY_SAVING:
                return r.getString(R.string.quick_settings_location_mode_battery);
            case Settings.Secure.LOCATION_MODE_HIGH_ACCURACY:
                return r.getString(R.string.quick_settings_location_mode_high);
        }
        return r.getString(R.string.quick_settings_location_off_label);
    }

    // Bug report
    void addBugreportTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBugreportTile = view;
        mBugreportCallback = cb;
        onBugreportChanged();
    }
    // SettingsObserver callback
    public void onBugreportChanged() {
        if (mRibbon) return;

        final ContentResolver cr = mContext.getContentResolver();
        boolean enabled = false;
        try {
            enabled = (Settings.Global.getInt(cr, Settings.Global.BUGREPORT_IN_POWER_MENU) != 0);
        } catch (SettingNotFoundException e) {
        }

        mBugreportState.enabled = enabled && mUserTracker.isCurrentUserOwner();
        mBugreportCallback.refreshView(mBugreportTile, mBugreportState);
    }

    // Remote Display
    void addRemoteDisplayTile(QuickSettingsTileView view, RefreshCallback cb) {
        mRemoteDisplayTile = view;
        mRemoteDisplayCallback = cb;
        final int[] count = new int[1];
        mRemoteDisplayTile.setOnPrepareListener(new QuickSettingsTileView.OnPrepareListener() {
            @Override
            public void onPrepare() {
                mMediaRouter.addCallback(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                        mRemoteDisplayRouteCallback,
                        MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
                updateRemoteDisplays();
            }
            @Override
            public void onUnprepare() {
                mMediaRouter.removeCallback(mRemoteDisplayRouteCallback);
            }
        });

        updateRemoteDisplays();
    }

    private void rebindMediaRouterAsCurrentUser() {
        mMediaRouter.rebindAsUser(mUserTracker.getCurrentUserId());
    }

    private void updateRemoteDisplays() {
        if (mRibbon) return;

        Resources r = mContext.getResources();
        MediaRouter.RouteInfo connectedRoute = mMediaRouter.getSelectedRoute(
                MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
        boolean enabled = connectedRoute != null
                && connectedRoute.matchesTypes(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
        boolean connecting;
        if (enabled) {
            connecting = connectedRoute.isConnecting();
        } else {
            connectedRoute = null;
            connecting = false;
            enabled = mMediaRouter.isRouteAvailable(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                    MediaRouter.AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE);
        }

        mRemoteDisplayState.enabled = enabled;
        if (connectedRoute != null) {
            mRemoteDisplayState.label = connectedRoute.getName().toString();
            mRemoteDisplayState.iconId = connecting ?
                    R.drawable.ic_qs_cast_connecting : R.drawable.ic_qs_cast_connected;
        } else {
            mRemoteDisplayState.label = r.getString(
                    R.string.quick_settings_remote_display_no_connection_label);
            mRemoteDisplayState.iconId = R.drawable.ic_qs_cast_available;
        }
        mRemoteDisplayCallback.refreshView(mRemoteDisplayTile, mRemoteDisplayState);
    }

    // IME
    void addImeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mImeTile = view;
        mImeCallback = cb;
        mImeCallback.refreshView(mImeTile, mImeState);
    }
    /* This implementation is taken from
       InputMethodManagerService.needsToShowImeSwitchOngoingNotification(). */
    private boolean needsToShowImeSwitchOngoingNotification(InputMethodManager imm) {
        List<InputMethodInfo> imis = imm.getEnabledInputMethodList();
        final int N = imis.size();
        if (N > 2) return true;
        if (N < 1) return false;
        int nonAuxCount = 0;
        int auxCount = 0;
        InputMethodSubtype nonAuxSubtype = null;
        InputMethodSubtype auxSubtype = null;
        for(int i = 0; i < N; ++i) {
            final InputMethodInfo imi = imis.get(i);
            final List<InputMethodSubtype> subtypes = imm.getEnabledInputMethodSubtypeList(imi,
                    true);
            final int subtypeCount = subtypes.size();
            if (subtypeCount == 0) {
                ++nonAuxCount;
            } else {
                for (int j = 0; j < subtypeCount; ++j) {
                    final InputMethodSubtype subtype = subtypes.get(j);
                    if (!subtype.isAuxiliary()) {
                        ++nonAuxCount;
                        nonAuxSubtype = subtype;
                    } else {
                        ++auxCount;
                        auxSubtype = subtype;
                    }
                }
            }
        }
        if (nonAuxCount > 1 || auxCount > 1) {
            return true;
        } else if (nonAuxCount == 1 && auxCount == 1) {
            if (nonAuxSubtype != null && auxSubtype != null
                    && (nonAuxSubtype.getLocale().equals(auxSubtype.getLocale())
                            || auxSubtype.overridesImplicitlyEnabledSubtype()
                            || nonAuxSubtype.overridesImplicitlyEnabledSubtype())
                    && nonAuxSubtype.containsExtraValueKey(TAG_TRY_SUPPRESSING_IME_SWITCHER)) {
                return false;
            }
            return true;
        }
        return false;
    }
    void onImeWindowStatusChanged(boolean visible) {
        if (mRibbon) return;

        InputMethodManager imm =
                (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> imis = imm.getInputMethodList();

        mImeState.enabled = (visible && needsToShowImeSwitchOngoingNotification(imm));
        mImeState.label = getCurrentInputMethodName(mContext, mContext.getContentResolver(),
                imm, imis, mContext.getPackageManager());
        if (mImeCallback != null) {
            mImeCallback.refreshView(mImeTile, mImeState);
        }
    }
    private static String getCurrentInputMethodName(Context context, ContentResolver resolver,
            InputMethodManager imm, List<InputMethodInfo> imis, PackageManager pm) {
        if (resolver == null || imis == null) return null;
        Resources r = context.getResources();
        final String currentInputMethodId = Settings.Secure.getString(resolver,
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (TextUtils.isEmpty(currentInputMethodId)) return null;
        for (InputMethodInfo imi : imis) {
            if (currentInputMethodId.equals(imi.getId())) {
                final InputMethodSubtype subtype = imm.getCurrentInputMethodSubtype();
                final CharSequence summary = subtype != null
                        ? subtype.getDisplayName(context, imi.getPackageName(),
                                imi.getServiceInfo().applicationInfo)
                        : r.getString(R.string.quick_settings_ime_label);
                return summary.toString();
            }
        }
        return null;
    }

    // Rotation lock
    void addRotationLockTile(QuickSettingsTileView view,
            RotationLockController rotationLockController,
            RefreshCallback cb) {
        mRotationLockTile = view;
        mRotationLockCallback = cb;
        mRotationLockController = rotationLockController;
        onRotationLockChanged();
    }
    void onRotationLockChanged() {
        onRotationLockStateChanged(mRotationLockController.isRotationLocked(),
                mRotationLockController.isRotationLockAffordanceVisible());
    }
    @Override
    public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
        Resources r = mContext.getResources();
        mRotationLockState.visible = true;
        mRotationLockState.enabled = rotationLocked;
        mRotationLockState.iconId = rotationLocked
                ? R.drawable.ic_qs_rotation_locked
                : R.drawable.ic_qs_auto_rotate;
        mRotationLockState.label = rotationLocked
                ? r.getString(R.string.quick_settings_rotation_locked_label)
                : r.getString(R.string.quick_settings_rotation_unlocked_label);
        mRotationLockCallback.refreshView(mRotationLockTile, mRotationLockState);
    }
    void refreshRotationLockTile() {
        if (mRotationLockTile != null) {
            onRotationLockChanged();
        }
    }

    // Brightness
    void addBrightnessTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBrightnessTile = view;
        mBrightnessCallback = cb;
        onBrightnessLevelChanged();
    }
    @Override
    public void onBrightnessLevelChanged() {
        Resources r = mContext.getResources();
        int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                mUserTracker.getCurrentUserId());
        mBrightnessState.autoBrightness =
                (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mBrightnessState.iconId = mBrightnessState.autoBrightness
                ? R.drawable.ic_qs_brightness_auto_on
                : R.drawable.ic_qs_brightness_auto_off;
        mBrightnessState.label = r.getString(R.string.quick_settings_brightness_label);
        mBrightnessCallback.refreshView(mBrightnessTile, mBrightnessState);
    }
    void refreshBrightnessTile() {
        onBrightnessLevelChanged();
    }

    // Immersive
    void addImmersiveTile(QuickSettingsTileView view, RefreshCallback cb) {
        mImmersiveTile = view;
        mImmersiveCallback = cb;
        onImmersiveChanged();
    }

    private void onImmersiveChanged() {
        Resources r = mContext.getResources();
        int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_MODE, 0,
                mUserTracker.getCurrentUserId());
        mImmersiveState.isEnabled = (mode == 1);
        mImmersiveState.iconId = mImmersiveState.isEnabled
                ? R.drawable.ic_qs_immersive_on
                : R.drawable.ic_qs_immersive_off;
        mImmersiveState.label = mImmersiveState.isEnabled
                ? r.getString(R.string.quick_settings_immersive_mode_label)
                : r.getString(R.string.quick_settings_immersive_mode_off_label);
        mImmersiveCallback.refreshView(mImmersiveTile, mImmersiveState);
    }
    void refreshImmersiveTile() {
        onImmersiveChanged();
    }

    // QuietHour
    void addQuiteHourTile(QuickSettingsTileView view, RefreshCallback cb) {
        mQuiteHourTile = view;
        mQuiteHourCallback = cb;
        onQuiteHourChanged();
    }

    private void onQuiteHourChanged() {
        Resources r = mContext.getResources();
        int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_ENABLED, 0,
                mUserTracker.getCurrentUserId());
        mQuiteHourState.isEnabled = (mode == 1);
        mQuiteHourState.iconId = mQuiteHourState.isEnabled
                ? R.drawable.ic_qs_quiet_hours_on
                : R.drawable.ic_qs_quiet_hours_off;
        mQuiteHourState.label = mQuiteHourState.isEnabled
                ? r.getString(R.string.quick_settings_quiethours_label)
                : r.getString(R.string.quick_settings_quiethours_off_label);
        mQuiteHourCallback.refreshView(mQuiteHourTile, mQuiteHourState);
    }

    // SSL CA Cert warning.
    public void addSslCaCertWarningTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSslCaCertWarningTile = view;
        mSslCaCertWarningCallback = cb;
        // Set a sane default while we wait for the AsyncTask to finish (no cert).
        setSslCaCertWarningTileInfo(false, true);
    }
    public void setSslCaCertWarningTileInfo(boolean hasCert, boolean isManaged) {
        if (mRibbon) return;

        Resources r = mContext.getResources();
        mSslCaCertWarningState.enabled = hasCert;
        if (isManaged) {
            mSslCaCertWarningState.iconId = R.drawable.ic_qs_certificate_info;
        } else {
            mSslCaCertWarningState.iconId = android.R.drawable.stat_notify_error;
        }
        mSslCaCertWarningState.label = r.getString(R.string.ssl_ca_cert_warning);
        mSslCaCertWarningCallback.refreshView(mSslCaCertWarningTile, mSslCaCertWarningState);
    }

    private void updateState() {
        mUsbRegexs = mCM.getTetherableUsbRegexs();

        String[] available = mCM.getTetherableIfaces();
        String[] tethered = mCM.getTetheredIfaces();
        String[] errored = mCM.getTetheringErroredIfaces();
        updateState(available, tethered, errored);
    }

    private void updateState(String[] available, String[] tethered,
            String[] errored) {
        updateUsbState(available, tethered, errored);
    }

    private void updateUsbState(String[] available, String[] tethered,
            String[] errored) {

        mUsbTethered = false;
        for (String s : tethered) {
            for (String regex : mUsbRegexs) {
                if (s.matches(regex)) mUsbTethered = true;
            }
        }

    }

    private void setUsbTethering(boolean enabled) {
        if (mCM.setUsbTethering(enabled) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
            return;
        }
    }

    // Sleep Mode
    void addSleepModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSleepModeTile = view;
        mSleepModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                screenTimeoutChangeState();
                updateSleepState();
            }
        });
        mSleepModeCallback = cb;
        updateSleepState();
    }

    private void updateSleepState() {
        mSleepModeState.enabled = true;
        mSleepModeState.iconId = R.drawable.ic_qs_screen_timeout;
        mSleepModeState.label = screenTimeoutGetLabel(getScreenTimeout());
        mSleepModeCallback.refreshView(mSleepModeTile, mSleepModeState);
    }

    private String screenTimeoutGetLabel(int currentTimeout) {
        Resources r = mContext.getResources();
        switch(currentTimeout) {
               case SCREEN_TIMEOUT_15:
                   return r.getString(R.string.quick_settings_sleep_label_back_15);
               case SCREEN_TIMEOUT_30:
                   return r.getString(R.string.quick_settings_sleep_label_back_30);
               case SCREEN_TIMEOUT_60:
                   return r.getString(R.string.quick_settings_sleep_label_back_60);
               case SCREEN_TIMEOUT_120:
                   return r.getString(R.string.quick_settings_sleep_label_back_120);
               case SCREEN_TIMEOUT_300:
                   return r.getString(R.string.quick_settings_sleep_label_back_300);
               case SCREEN_TIMEOUT_600:
                   return r.getString(R.string.quick_settings_sleep_label_back_600);
               case SCREEN_TIMEOUT_1800:
                   return r.getString(R.string.quick_settings_sleep_label_back_1800);
        }
        return r.getString(R.string.quick_settings_sleep_label_back_unknown);
    }

    private int getScreenTimeout() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, 0);
    }

    private void screenTimeoutChangeState() {
        int screenTimeout = getScreenTimeout();

        if (screenTimeout == SCREEN_TIMEOUT_15) {
            screenTimeout = SCREEN_TIMEOUT_30;
        } else if (screenTimeout == SCREEN_TIMEOUT_30) {
            screenTimeout = SCREEN_TIMEOUT_60;
        } else if (screenTimeout == SCREEN_TIMEOUT_60) {
            screenTimeout = SCREEN_TIMEOUT_120;
        } else if (screenTimeout == SCREEN_TIMEOUT_120) {
            screenTimeout = SCREEN_TIMEOUT_300;
        } else if (screenTimeout == SCREEN_TIMEOUT_300) {
            screenTimeout = SCREEN_TIMEOUT_600;
        } else if (screenTimeout == SCREEN_TIMEOUT_600) {
            screenTimeout = SCREEN_TIMEOUT_1800;
        } else if (screenTimeout == SCREEN_TIMEOUT_1800) {
            screenTimeout = SCREEN_TIMEOUT_15;
        }

        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, screenTimeout);
    }

    // Ringer Mode
    void addRingerModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mRingerModeTile = view;
        mRingerModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRingerState();
                updateRingerState();
            }
        });
        mRingerModeCallback = cb;
        updateRingerState();
    }

    private void updateRingerState() {
        Resources r = mContext.getResources();
        updateRingerSettings();
        findCurrentState();
        mRingerModeState.enabled = true;
        mRingerModeState.iconId = mRingers.get(mRingerIndex).mDrawable;
        mRingerModeState.label = r.getString(mRingers.get(mRingerIndex).mString);
        mRingerModeCallback.refreshView(mRingerModeTile, mRingerModeState);
    }

    private void findCurrentState() {
        boolean vibrateWhenRinging = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.VIBRATE_WHEN_RINGING, 0, UserHandle.USER_CURRENT) == 1;
        int ringerMode = mAudioManager.getRingerMode();

        mRingerIndex = 0;

        for (int i = 0; i < mRingers.size(); i++) {
            Ringer r = mRingers.get(i);
            if (ringerMode == r.mRingerMode && vibrateWhenRinging == r.mVibrateWhenRinging) {
                mRingerIndex = i;
                break;
            }
        }
    }

    private void updateRingerSettings() {
        boolean hasVibrator = mVibrator.hasVibrator();

        mRingers.clear();

        for (Ringer r : RINGERS) {
             if (hasVibrator || !r.mVibrateWhenRinging) {
                 mRingers.add(r);
             }
        }
        if (mRingers.isEmpty()) {
            mRingers.add(RINGERS[0]);
        }
    }

    private void toggleRingerState() {
        mRingerIndex++;
        if (mRingerIndex >= mRingers.size()) {
            mRingerIndex = 0;
        }

        Ringer r = mRingers.get(mRingerIndex);

        // If we are setting a vibrating state, vibrate to indicate it
        if (r.mVibrateWhenRinging) {
            mVibrator.vibrate(250);
        }

        // Set the desired state
        ContentResolver resolver = mContext.getContentResolver();
        Settings.System.putIntForUser(resolver, Settings.System.VIBRATE_WHEN_RINGING,
                r.mVibrateWhenRinging ? 1 : 0, UserHandle.USER_CURRENT);
        mAudioManager.setRingerMode(r.mRingerMode);
    }

    private static class Ringer {
        final boolean mVibrateWhenRinging;
        final int mRingerMode;
        final int mDrawable;
        final int mString;

        Ringer(int ringerMode, boolean vibrateWhenRinging, int drawable, int string) {
            mVibrateWhenRinging = vibrateWhenRinging;
            mRingerMode = ringerMode;
            mDrawable = drawable;
            mString = string;
        }
    }
}
