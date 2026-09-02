/*
 * Adapted from OpenVPN for Android
 * Copyright (c) 2012-2013, Arne Schwabe
 * Copyright (c) 2013, Kevin Cernekee
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * In addition, as a special exception, the copyright holders give
 * permission to link the code of portions of this program with the
 * OpenSSL library.
 */

package net.openconnect_vpn.android.core;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.*;
import android.preference.PreferenceManager;
import android.util.Log;
import net.openconnect_vpn.android.AuthPromptActivity;
import net.openconnect_vpn.android.MainActivity;
import net.openconnect_vpn.android.R;
import net.openconnect_vpn.android.VpnProfile;
import net.openconnect_vpn.android.VpnWidget;
import net.openconnect_vpn.android.api.GrantPermissionsActivity;
import net.openconnect_vpn.android.core.VPNLog.LogArrayAdapter;

import java.net.InetAddress;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import org.infradead.libopenconnect.LibOpenConnect;
import org.infradead.libopenconnect.LibOpenConnect.VPNStats;

public class OpenVpnService extends VpnService {

	public static final String TAG = "OpenConnect";

	public static final String START_SERVICE = "net.openconnect_vpn.android.START_SERVICE";
	public static final String START_SERVICE_STICKY = "net.openconnect_vpn.android.START_SERVICE_STICKY";
	public static final String ACTION_DISCONNECT = "net.openconnect_vpn.android.DISCONNECT";
	public static final String ALWAYS_SHOW_NOTIFICATION = "net.openconnect_vpn.android.NOTIFICATION_ALWAYS_VISIBLE";

	public static final String ACTION_VPN_STATUS = "net.openconnect_vpn.android.VPN_STATUS";
	public static final String EXTRA_CONNECTION_STATE = "net.openconnect_vpn.android.connectionState";
	public static final String EXTRA_UUID = "net.openconnect_vpn.android.UUID";

	/** Same-process UI updates; preferred over broadcasts on Android 14+. */
	public interface StatusListener {
		void onVpnStatus(OpenVpnService service);
	}

	// These are valid in the CONNECTED state
	public VpnProfile profile;
	public LibOpenConnect.IPInfo ipInfo;
	public String serverName;
	public String cstpCipher;
	public String dtlsCipher;
	public String cstpCompression;
	public String dtlsCompression;
	public Date startTime;

	public volatile String publicIp4;
	public volatile String publicIp6;
	public volatile String publicCountry;
	public volatile String publicIso;

	private DeviceStateReceiver mDeviceStateReceiver;
	private SharedPreferences mPrefs;

	private KeepAlive mKeepAlive;
	private int mIdleTimeout;

	private final IBinder mBinder = new LocalBinder();

	private String mUUID;
	private int mStartId;

	private boolean mSwitching;
	private boolean mFallingBack;
	private boolean mUserAbort;
	private boolean mAbortRestore;
	private String mSwitchFromUuid;
	private String mSwitchToUuid;
	private String mSwitchResult;
	private String mSwitchToast;

	private static final long VPN_THREAD_JOIN_MS = 3000L;

	private Thread mVPNThread;
	private OpenConnectManagementThread mVPN;

	private UserDialog mDialog;
	private Context mDialogContext;

	private final int NOTIFICATION_ID = 1;
	private int mActivityConnections;
	private boolean mNotificationActive;

	private int mConnectionState = OpenConnectManagementThread.STATE_DISCONNECTED;
	private String mConnectionStateNames[];
	private VPNStats mStats = new VPNStats();

	private VPNLog mVPNLog = new VPNLog();
	private Handler mHandler = new Handler(Looper.getMainLooper());
	private Runnable mGeoRunnable;
	private final CopyOnWriteArrayList<StatusListener> mStatusListeners =
			new CopyOnWriteArrayList<StatusListener>();

	public void addStatusListener(StatusListener listener) {
		if (listener == null) {
			return;
		}
		mStatusListeners.addIfAbsent(listener);
		final StatusListener l = listener;
		mHandler.post(new Runnable() {
			@Override
			public void run() {
				l.onVpnStatus(OpenVpnService.this);
			}
		});
	}

	public void removeStatusListener(StatusListener listener) {
		if (listener != null) {
			mStatusListeners.remove(listener);
		}
	}

	public class LocalBinder extends Binder {
		public OpenVpnService getService() {
			// Return this instance of LocalService so clients can call public methods
			return OpenVpnService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		String action = intent.getAction();
		if( action !=null && action.equals(START_SERVICE))
			return mBinder;
		else
			return super.onBind(intent);
	}

	@Override
	public void onRevoke() {
		Log.i(TAG, "VPN access has been revoked");
		stopVPN();
	}

	@Override
	public void onCreate() {
		// Restore service state from disk if available
		// This gets overwritten if somebody calls startService()
		mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		mUUID = mPrefs.getString("service_mUUID", "");

		mVPNLog.restoreFromFile(getCacheDir().getAbsolutePath() + "/logdata.ser");
		if (!AppLog.isEnabled(mPrefs)) {
			mVPNLog.clear();
		}
		mConnectionStateNames = getResources().getStringArray(R.array.connection_states);
	}

	@Override
	public void onDestroy() {
		bindAppToVpn(false);
		killVPNThread(true);
		unregisterReceivers();
		if (mPrefs != null) {
			mPrefs.edit().putInt(VpnQuick.PREF_CONN_STATE,
					OpenConnectManagementThread.STATE_DISCONNECTED).apply();
		}
		VpnWidget.refresh(this);
		if (AppLog.isEnabled(mPrefs)) {
			mVPNLog.saveToFile(getCacheDir().getAbsolutePath() + "/logdata.ser");
		}
	}

	private synchronized boolean doStopVPN() {
		if (mVPN != null) {
			mVPN.stopVPN();
			return true;
		}
		return false;
	}

	private void killVPNThread(boolean joinThread) {
		killVPNThread(joinThread ? VPN_THREAD_JOIN_MS : 0);
	}

	private void killVPNThread(long joinTimeoutMs) {
		Thread t;
		synchronized (this) {
			t = mVPNThread;
		}
		if (t == null) {
			doStopVPN();
			return;
		}
		doStopVPN();
		if (joinTimeoutMs <= 0) {
			return;
		}
		try {
			t.join(joinTimeoutMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			Log.e(TAG, "OpenConnect thread join interrupted");
		}
		if (t.isAlive()) {
			Log.w(TAG, "OpenConnect thread did not exit within " + joinTimeoutMs + "ms");
		}
		bindAppToVpn(false);
	}

	private void waitForPreviousVpnThreadExit() {
		Thread previous;
		synchronized (this) {
			previous = mVPNThread;
		}
		if (previous == null) {
			return;
		}
		killVPNThread(VPN_THREAD_JOIN_MS);
	}

	synchronized boolean isActiveVpnThread(OpenConnectManagementThread t) {
		return mVPN == t;
	}

	private PendingIntent getMainActivityIntent() {
		// Touching "Configure" on the system VPN dialog will restore the app
		// (same as clicking the launcher icon)
		Intent intent = new Intent(getBaseContext(), MainActivity.class);
		intent.setAction(Intent.ACTION_MAIN);
		intent.addCategory(Intent.CATEGORY_LAUNCHER);

		PendingIntent startLW = PendingIntent.getActivity(this, 0,
				intent, PendingIntent.FLAG_IMMUTABLE);
		return startLW;
	}

	@SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerDeviceStateReceiver(OpenVPNManagement management) {
		// Registers BroadcastReceiver to track network connection changes.
		IntentFilter filter = new IntentFilter();
		filter.addAction(DeviceStateReceiver.PREF_CHANGED);
		filter.addAction(Intent.ACTION_SCREEN_OFF);
		filter.addAction(Intent.ACTION_SCREEN_ON);
		mDeviceStateReceiver = new DeviceStateReceiver(management, mPrefs, this);
		if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			registerReceiver(mDeviceStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
		else
			registerReceiver(mDeviceStateReceiver, filter);
		mDeviceStateReceiver.start(this);
	}

	@SuppressLint("UnspecifiedRegisterReceiverFlag")
	private synchronized void registerKeepAlive() {
		String DNSServer = null;
		if (ipInfo != null && ipInfo.DNS != null && !ipInfo.DNS.isEmpty()) {
			try {
				String dns = ipInfo.DNS.get(0);
				if (dns != null && InetAddress.getByName(dns) != null) {
					DNSServer = dns;
				}
			} catch (Exception e) {
				Log.i(TAG, "KeepAlive: ignoring invalid tunnel DNS", e);
			}
		}
		if (DNSServer == null) {
			Log.i(TAG, "KeepAlive: no tunnel DNS, relying on OpenConnect DPD");
			return;
		}

		// Half of the server idle timeout, so a missed ping still has a second chance.
		int idle = this.mIdleTimeout;
		if (idle < 60 || idle > 7200)
			idle = 1800;
		idle = idle / 2;
		if (idle < 60) {
			idle = 60;
		}
		if (idle > 1800) {
			idle = 1800;
		}
		Log.d(TAG, "calculated KeepAlive interval: " + idle + " seconds");

		IntentFilter filter = new IntentFilter(KeepAlive.ACTION_KEEPALIVE_ALARM);
		mKeepAlive = new KeepAlive(idle, DNSServer, mDeviceStateReceiver);
		if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			registerReceiver(mKeepAlive, filter, Context.RECEIVER_NOT_EXPORTED);
		else
			registerReceiver(mKeepAlive, filter);
		mKeepAlive.start(this);
	}

	private void unregisterReceivers() {
		try {
			if (mDeviceStateReceiver != null) {
				mDeviceStateReceiver.stop();
				unregisterReceiver(mDeviceStateReceiver);
			}
			mDeviceStateReceiver = null;
		} catch (IllegalArgumentException iae) {
			// catch "Receiver not registered" error
			Log.w(TAG, "can't unregister DeviceStateReceiver", iae);
		}

		try {
			if (mKeepAlive != null) {
				mKeepAlive.stop(this);
				unregisterReceiver(mKeepAlive);
			}
			mKeepAlive = null;
		} catch (IllegalArgumentException iae) {
			Log.w(TAG, "can't unregister KeepAlive", iae);
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

		if (intent == null) {
			Log.e(TAG, "OpenVpnService started with null intent");
			stopSelf();
			return START_NOT_STICKY;
		}

		String action = intent.getAction();
		if (START_SERVICE.equals(action)) {
			return START_NOT_STICKY;
		} else if (START_SERVICE_STICKY.equals(action)) {
			return START_REDELIVER_INTENT;
		} else if (ACTION_DISCONNECT.equals(action)) {
			if (mVPN != null) {
				stopVPN();
			} else {
				setConnectionState(OpenConnectManagementThread.STATE_DISCONNECTED);
				stopSelf();
			}
			return START_NOT_STICKY;
		}

		String requested = intent.getStringExtra(EXTRA_UUID);
		if (requested == null) {
			requested = mUUID;
		}
		if (requested == null) {
			return START_NOT_STICKY;
		}
		ProfileManager.init(getApplicationContext());
		VpnProfile next = ProfileManager.get(requested);
		if (next == null) {
			return START_NOT_STICKY;
		}

		synchronized (this) {
			String previous = mUUID;
			boolean aborted = mUserAbort;
			if (aborted) {
				mUserAbort = false;
				mSwitching = false;
				mFallingBack = false;
				mAbortRestore = false;
				mSwitchFromUuid = null;
				mSwitchToUuid = null;
			}
			boolean replacing = !aborted && mVPN != null && previous != null
					&& !requested.equals(previous);
			if (replacing) {
				if (!mSwitching) {
					mSwitchFromUuid = previous;
					mSwitching = true;
					mFallingBack = false;
					mAbortRestore = false;
					log(VPNLog.LEVEL_INFO, "switching profile '"
							+ profileName(previous) + "' -> '" + next.getName() + "'");
				}
				mSwitchToUuid = requested;
			}
			mStartId = startId;
		}

		wakeUpActivity();
		beginProfile(requested);
		if (isSwitching()) {
			setConnectionState(OpenConnectManagementThread.STATE_CONNECTING);
		}
		return START_NOT_STICKY;
    }

	private void beginProfile(String uuid) {
		ProfileManager.init(getApplicationContext());
		VpnProfile next = ProfileManager.get(uuid);
		if (next == null) {
			return;
		}
		synchronized (this) {
			mUUID = uuid;
			mPrefs.edit().putString("service_mUUID", mUUID).apply();
			profile = next;
		}

		waitForPreviousVpnThreadExit();

		synchronized (this) {
			mVPN = new OpenConnectManagementThread(getApplicationContext(), profile, this);
			mVPNThread = new Thread(mVPN, "OpenVPNManagementThread");
			mVPNThread.start();
			ProfileManager.setConnectedVpnProfile(profile);
		}

		unregisterReceivers();
		registerDeviceStateReceiver(mVPN);
	}

	public Builder getVpnServiceBuilder() {
		VpnService.Builder b = new VpnService.Builder();
		b.setSession(profile.mName);
		b.setConfigureIntent(getMainActivityIntent());
		/* Drop packets if the tunnel is up but the network is gone. Not a notification. */
		b.setBlocking(true);
		if (profile != null && profile.mPrefs != null) {
			PerAppVpn.apply(b, this, PerAppVpn.resolve(this, profile.mPrefs));
		}
		return b;
	}

	// From: https://stackoverflow.com/questions/3758606/how-can-i-convert-byte-size-into-a-human-readable-format-in-java
	public static String humanReadableByteCount(long bytes, boolean mbit) {
		if(mbit)
			bytes = bytes *8;
		int unit = mbit ? 1000 : 1024;
		if (bytes < unit)
			return bytes + (mbit ? " bit" : " B");

		int exp = (int) (Math.log(bytes) / Math.log(unit));
		String pre = (mbit ? "kMGTPE" : "KMGTPE").charAt(exp-1) + (mbit ? "" : "");
		if(mbit)
			return String.format(Locale.getDefault(),"%.1f %sbit", bytes / Math.pow(unit, exp), pre);
		else
			return String.format(Locale.getDefault(),"%.1f %sB", bytes / Math.pow(unit, exp), pre);
	}

	public static String formatElapsedTime(long startTime) {
		StringBuilder sb = new StringBuilder();
		startTime = (new Date().getTime() - startTime) / 1000;
		if (startTime >= 60 * 60 * 24) {
			// days
			sb.append(String.format(Locale.getDefault(),"%1$d:", startTime / (60 * 60 * 24)));
		}
		if (startTime >= 60 * 60) {
			// hours
			startTime %= 60 * 60 * 24;
			sb.append(String.format(Locale.getDefault(), "%1$02d:", startTime / (60 * 60)));
			startTime %= 60 * 60;
		}
		// minutes:seconds
		sb.append(String.format(Locale.getDefault(), "%1$02d:%2$02d", startTime / 60, startTime % 60));
		return sb.toString();
	}

	/* called from the activity on broadcast receipt, or startup */
	public synchronized void startActiveDialog(Context context) {
		if (mDialog == null || context == null) {
			return;
		}
		/* Another Activity already owns the dialog window. */
		if (mDialogContext != null && mDialogContext != context) {
			return;
		}
		if (mDialogContext == context) {
			return;
		}
		mDialogContext = context;
		mDialog.onStart(context);
	}

	public synchronized boolean hasPendingUserDialog() {
		return mDialog != null;
	}

	/* called when the activity shuts down (mDialog will be re-rendered when the activity starts again) */
	public synchronized void stopActiveDialog(Context context) {
		if (mDialogContext != context) {
			return;
		}
		if (mDialog != null) {
			mDialog.onStop(mDialogContext);
		}
		mDialogContext = null;
	}

	private synchronized void setDialog(Context context, UserDialog dialog) {
		mDialogContext = context;
		mDialog = dialog;
	}

	@SuppressWarnings("deprecation")
	private void updateNotification() {
		if (mNotificationActive) {
			NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			manager.cancel(NOTIFICATION_ID);
			mNotificationActive = false;
		}
		/* No live Activity holding the dialog → bring dedicated prompt UI. */
		if (mDialog != null && mDialogContext == null && mActivityConnections == 0) {
			bringUiForPrompt();
		}
	}

	private void bringUiForPrompt() {
		Intent intent = new Intent(this, AuthPromptActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
				| Intent.FLAG_ACTIVITY_CLEAR_TOP
				| Intent.FLAG_ACTIVITY_SINGLE_TOP);
		try {
			startActivity(intent);
		} catch (Exception e) {
			Log.w(TAG, "cannot open UI for VPN prompt", e);
		}
	}

	private void wakeUpActivity() {
		mHandler.post(new Runnable() {
			@Override
			public void run() {
				for (StatusListener l : mStatusListeners) {
					try {
						l.onVpnStatus(OpenVpnService.this);
					} catch (Exception e) {
						Log.w(TAG, "status listener failed", e);
					}
				}

				/*
				 * Android 14+: package-targeted so RECEIVER_NOT_EXPORTED
				 * dynamic receivers still receive the event.
				 */
				Intent vpnstatus = new Intent(ACTION_VPN_STATUS);
				vpnstatus.setPackage(getPackageName());
				vpnstatus.putExtra(EXTRA_CONNECTION_STATE, mConnectionState);
				vpnstatus.putExtra(EXTRA_UUID, mUUID);
				sendBroadcast(vpnstatus);

				updateNotification();

				if (mConnectionState == OpenConnectManagementThread.STATE_CONNECTED &&
						mKeepAlive == null) {
					registerKeepAlive();
				}
			}
		});
	}

	public void updateActivityRefcount(int num) {
		mActivityConnections += num;
		updateNotification();
	}

	/* called from the VPN thread; blocks until user responds */
	public Object promptUser(UserDialog dialog) {
		Object ret;

		ret = dialog.earlyReturn();
		if (ret != null) {
			return ret;
		}

		setDialog(null, dialog);
		wakeUpActivity();
		ret = mDialog.waitForResponse();

		setDialog(null, null);
		return ret;
	}

	public synchronized void threadDone() {
		threadDone(null);
	}

	public synchronized void threadDone(OpenConnectManagementThread t) {
		if (t != null && mVPN != null && t != mVPN) {
			Log.i(TAG, "VPN thread exited after being replaced");
			return;
		}
		Log.i(TAG, "VPN thread has terminated");
		mVPN = null;
		final int startId = mStartId;
		mHandler.post(new Runnable() {
			@Override
			public void run() {
				onVpnThreadExited(startId);
			}
		});
	}

	private void onVpnThreadExited(int startId) {
		String fallbackUuid = null;
		synchronized (this) {
			if (mVPN != null) {
				return;
			}
			if (!mUserAbort && mSwitching && !mFallingBack
					&& mSwitchFromUuid != null
					&& ProfileManager.get(mSwitchFromUuid) != null) {
				mFallingBack = true;
				fallbackUuid = mSwitchFromUuid;
				log(VPNLog.LEVEL_INFO, "switch failed, restoring '"
						+ profileName(fallbackUuid) + "'");
			} else {
				completeSwitchFailureLocked();
			}
		}
		if (fallbackUuid != null) {
			beginProfile(fallbackUuid);
			setConnectionState(OpenConnectManagementThread.STATE_CONNECTING);
			return;
		}
		setConnectionState(OpenConnectManagementThread.STATE_DISCONNECTED);
		if (stopSelfResult(startId) == false) {
			Log.w(TAG, "not stopping service due to startId mismatch");
		} else {
			unregisterReceivers();
		}
	}

	private void completeSwitchFailureLocked() {
		if (!mUserAbort && mSwitching) {
			String toName = profileName(mSwitchToUuid);
			String fromName = profileName(mSwitchFromUuid);
			if (mFallingBack) {
				mSwitchResult = getString(R.string.switch_failed_both, toName, fromName);
			} else {
				mSwitchResult = getString(R.string.switch_failed_target, toName);
			}
			persistSwitchResultLocked();
			log(VPNLog.LEVEL_ERR, mSwitchResult);
		}
		mSwitching = false;
		mFallingBack = false;
		mSwitchFromUuid = null;
		mSwitchToUuid = null;
		mUserAbort = false;
		mAbortRestore = false;
	}

	private void onSwitchConnectedLocked() {
		if (!mSwitching) {
			return;
		}
		if (mFallingBack) {
			String restored = profile != null ? profile.getName() : profileName(mSwitchFromUuid);
			if (mAbortRestore) {
				mSwitchResult = null;
				mSwitchToast = getString(R.string.switch_aborted, restored);
			} else {
				String failed = profileName(mSwitchToUuid);
				mSwitchResult = getString(R.string.switch_reverted, failed, restored);
				mSwitchToast = null;
			}
			persistSwitchResultLocked();
			if (mSwitchResult != null) {
				log(VPNLog.LEVEL_INFO, mSwitchResult);
			} else if (mSwitchToast != null) {
				log(VPNLog.LEVEL_INFO, mSwitchToast);
			}
		} else {
			mSwitchResult = null;
			mSwitchToast = null;
			persistSwitchResultLocked();
		}
		mSwitching = false;
		mFallingBack = false;
		mSwitchFromUuid = null;
		mSwitchToUuid = null;
		mUserAbort = false;
		mAbortRestore = false;
	}

	private void persistSwitchResultLocked() {
		if (mPrefs == null) {
			return;
		}
		if (mSwitchResult == null) {
			mPrefs.edit().remove("switch_result_msg").apply();
		} else {
			mPrefs.edit().putString("switch_result_msg", mSwitchResult).apply();
		}
	}

	private String profileName(String uuid) {
		if (uuid == null) {
			return "";
		}
		VpnProfile p = ProfileManager.get(uuid);
		return p != null ? p.getName() : uuid;
	}

	public synchronized void setConnectionState(int state) {
		setConnectionState(state, null);
	}

	public synchronized void setConnectionState(int state, OpenConnectManagementThread from) {
		if (from != null && mVPN != null && from != mVPN) {
			return;
		}
		if (state == OpenConnectManagementThread.STATE_DISCONNECTED
				&& mSwitching && from != null) {
			cancelGeoLookup();
			bindAppToVpn(false);
			return;
		}
		if (state == OpenConnectManagementThread.STATE_CONNECTED &&
				mConnectionState != OpenConnectManagementThread.STATE_CONNECTED) {
			startTime = new Date();
			bindAppToVpn(true);
			clearPublicGeo();
			if (profile != null) {
				GeoLookup.clear(profile.mPrefs);
			}
			scheduleGeoLookup();
			onSwitchConnectedLocked();
		}
		if (state != OpenConnectManagementThread.STATE_CONNECTED) {
			cancelGeoLookup();
			bindAppToVpn(false);
			if (state == OpenConnectManagementThread.STATE_DISCONNECTED) {
				clearPublicGeo();
			}
		}
		mConnectionState = state;
		if (mPrefs != null) {
			mPrefs.edit().putInt(VpnQuick.PREF_CONN_STATE, state).apply();
		}
		VpnWidget.refresh(this);
		wakeUpActivity();
	}

	private void clearPublicGeo() {
		publicIp4 = null;
		publicIp6 = null;
		publicCountry = null;
		publicIso = null;
	}

	private void bindAppToVpn(boolean on) {
		ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		if (cm == null) {
			return;
		}
		try {
			if (!on) {
				cm.bindProcessToNetwork(null);
				return;
			}
			Network vpn = findVpnNetwork(cm);
			if (vpn != null) {
				cm.bindProcessToNetwork(vpn);
			}
		} catch (Exception e) {
			Log.w(TAG, "bindProcessToNetwork", e);
		}
	}

	private Network findVpnNetwork(ConnectivityManager cm) {
		Network[] networks = cm.getAllNetworks();
		if (networks == null) {
			return null;
		}
		for (Network n : networks) {
			NetworkCapabilities caps = cm.getNetworkCapabilities(n);
			if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
				return n;
			}
		}
		return null;
	}

	private void cancelGeoLookup() {
		if (mGeoRunnable != null) {
			mHandler.removeCallbacks(mGeoRunnable);
			mGeoRunnable = null;
		}
	}

	private void scheduleGeoLookup() {
		cancelGeoLookup();
		if (!mPrefs.getBoolean(GeoLookup.PREF_ENABLED, true)) {
			return;
		}
		final VpnProfile p = profile;
		mGeoRunnable = new Runnable() {
			@Override
			public void run() {
				mGeoRunnable = null;
				if (getConnectionState() != OpenConnectManagementThread.STATE_CONNECTED
						|| p == null) {
					return;
				}
				boolean wantV6 = true;
				try {
					wantV6 = !p.mPrefs.getBoolean("disable_ipv6", true);
				} catch (ClassCastException e) {
					wantV6 = false;
				}
				final boolean lookupV6 = wantV6;
				new Thread(new Runnable() {
					@Override
					public void run() {
						GeoLookup.Result r = GeoLookup.fetch(lookupV6);
						if (r == null
								|| getConnectionState() != OpenConnectManagementThread.STATE_CONNECTED) {
							return;
						}
						if (r.iso.length() == 2) {
							FlagStore.ensure(OpenVpnService.this, r.iso);
						}
						GeoLookup.save(p.mPrefs, r);
						publicIso = r.iso;
						publicCountry = r.country;
						publicIp4 = r.ip4;
						publicIp6 = r.ip6;
						wakeUpActivity();
						VpnWidget.refresh(OpenVpnService.this);
					}
				}, "geo-lookup").start();
			}
		};
		mHandler.postDelayed(mGeoRunnable, 1500);
	}

	public synchronized int getConnectionState() {
		return mConnectionState;
	}

	public String getConnectionStateName() {
		return mConnectionStateNames[getConnectionState()];
	}

	public void requestStats() {
		if (mVPN != null) {
			mVPN.requestStats();
		}
	}

	public synchronized void setStats(VPNStats stats) {
		if (stats == null) {
			return;
		}
		mStats = stats;
		wakeUpActivity();
	}

	public synchronized VPNStats getStats() {
		return mStats;
	}

	public synchronized void setIPInfo(LibOpenConnect.IPInfo ipInfo, String serverName, int idleTimeout,
			String cstpCipher, String dtlsCipher, String cstpCompression, String dtlsCompression) {
		this.ipInfo = ipInfo;
		this.serverName = serverName;
		this.mIdleTimeout = idleTimeout;
		this.cstpCipher = cstpCipher;
		this.dtlsCipher = dtlsCipher;
		this.cstpCompression = cstpCompression;
		this.dtlsCompression = dtlsCompression;
	}

	public LogArrayAdapter getArrayAdapter(Context context) {
		return mVPNLog.getArrayAdapter(context);
	}

	public void putArrayAdapter(LogArrayAdapter adapter) {
		if (adapter != null) {
			mVPNLog.putArrayAdapter(adapter);
		}
	}

	public void log(final int level, final String msg) {
		if (!AppLog.allows(mPrefs, level)) {
			return;
		}
		mHandler.post(new Runnable() {

			@Override
			public void run() {
				mVPNLog.add(level, msg);
			}
		});
	}

	public void clearLog() {
		mVPNLog.clear();
	}

	public String dumpLog() {
		return mVPNLog.dump();
	}

	public String getReconnectName() {
		if (mUUID == null || mUUID.length() == 0) {
			return null;
		}
		ProfileManager.init(getApplicationContext());
		VpnProfile p = ProfileManager.get(mUUID);
		if (p == null || !p.isValid()) {
			return null;
		}
		return p.getName();
	}

	public synchronized boolean isSwitching() {
		return mSwitching;
	}

	public synchronized boolean isFallingBack() {
		return mSwitching && mFallingBack;
	}

	public synchronized boolean canAbortSwitch() {
		return mSwitching && !mFallingBack && mSwitchFromUuid != null;
	}

	public synchronized String getProfileUuid() {
		return mUUID;
	}

	public synchronized String getSwitchToName() {
		if (mFallingBack) {
			return profileName(mSwitchFromUuid);
		}
		return profileName(mSwitchToUuid);
	}

	public synchronized String consumeSwitchToast() {
		String msg = mSwitchToast;
		mSwitchToast = null;
		return msg;
	}

	public synchronized String consumeSwitchResult() {
		String msg = mSwitchResult;
		mSwitchResult = null;
		if ((msg == null || msg.length() == 0) && mPrefs != null) {
			msg = mPrefs.getString("switch_result_msg", null);
		}
		if (mPrefs != null) {
			mPrefs.edit().remove("switch_result_msg").apply();
		}
		return msg;
	}

	public void switchToProfile(String uuid) {
		if (uuid == null || uuid.length() == 0) {
			return;
		}
		synchronized (this) {
			if (uuid.equals(mUUID) && !mSwitching) {
				return;
			}
			if (ProfileManager.get(uuid) == null) {
				return;
			}
			if (!mSwitching) {
				mSwitchFromUuid = mUUID;
				mSwitching = true;
				mFallingBack = false;
				mAbortRestore = false;
				log(VPNLog.LEVEL_INFO, "switching profile '"
						+ profileName(mSwitchFromUuid) + "' -> '"
						+ profileName(uuid) + "'");
			}
			mSwitchToUuid = uuid;
			mUserAbort = false;
		}
		beginProfile(uuid);
		setConnectionState(OpenConnectManagementThread.STATE_CONNECTING);
	}

	public void abortSwitch() {
		String restore;
		synchronized (this) {
			if (!canAbortSwitch()) {
				restore = null;
			} else {
				mFallingBack = true;
				mAbortRestore = true;
				mUserAbort = false;
				restore = mSwitchFromUuid;
				log(VPNLog.LEVEL_INFO, "aborting switch, restoring '"
						+ profileName(restore) + "'");
			}
		}
		if (restore == null) {
			stopVPN();
			return;
		}
		beginProfile(restore);
		setConnectionState(OpenConnectManagementThread.STATE_CONNECTING);
	}

	public void startReconnectActivity(Context context) {
		Intent intent = new Intent(context, GrantPermissionsActivity.class);
		intent.putExtra(getPackageName() + GrantPermissionsActivity.EXTRA_UUID, mUUID);
		context.startActivity(intent);
	}

	public void stopVPN() {
		synchronized (this) {
			mUserAbort = true;
			mSwitching = false;
			mFallingBack = false;
			mAbortRestore = false;
			mSwitchFromUuid = null;
			mSwitchToUuid = null;
			mSwitchResult = null;
			persistSwitchResultLocked();
		}
		/*
		 * Signal the VPN thread to stop and update UI immediately.
		 * Joining on the main/binder thread freezes Status (Disconnect
		 * appears dead) on Android 14+ with stricter ANR timing.
		 */
		final Thread vpnThread;
		synchronized (this) {
			vpnThread = mVPNThread;
		}
		doStopVPN();
		ProfileManager.setConnectedVpnProfileDisconnected();
		setConnectionState(OpenConnectManagementThread.STATE_DISCONNECTED);

		if (vpnThread == null) {
			return;
		}
		if (Looper.myLooper() == Looper.getMainLooper()) {
			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						vpnThread.join(VPN_THREAD_JOIN_MS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					if (vpnThread.isAlive()) {
						Log.w(TAG, "OpenConnect thread did not exit within "
								+ VPN_THREAD_JOIN_MS + "ms");
					}
					bindAppToVpn(false);
				}
			}, "vpn-stop-join").start();
		} else {
			try {
				vpnThread.join(VPN_THREAD_JOIN_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (vpnThread.isAlive()) {
				Log.w(TAG, "OpenConnect thread did not exit within "
						+ VPN_THREAD_JOIN_MS + "ms");
			}
			bindAppToVpn(false);
		}
	}
}
