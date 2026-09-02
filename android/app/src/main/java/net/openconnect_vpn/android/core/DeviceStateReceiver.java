/*
 * Copyright (c) 2013, Kevin Cernekee
 * Copyright (c) 2026 MyOCApp contributors
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package net.openconnect_vpn.android.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class DeviceStateReceiver extends BroadcastReceiver {

	public static final String TAG = "OpenConnect";

	public static final String PREF_CHANGED = "net.openconnect_vpn.android.PREF_CHANGED";

	private final OpenVPNManagement mManagement;
	private final OpenVpnService mService;
	private final SharedPreferences mPrefs;
	private final Handler mHandler = new Handler(Looper.getMainLooper());

	private boolean mPauseOnScreenOff;
	private boolean mNetchangeReconnect;

	private boolean mScreenOff;
	private boolean mNetworkOff = true;
	private int mNetworkType = -1;
	private boolean mKeepaliveActive;
	private boolean mPaused;

	private ConnectivityManager mCm;
	private ConnectivityManager.NetworkCallback mNetCb;

	public DeviceStateReceiver(OpenVPNManagement management, SharedPreferences prefs,
			OpenVpnService service) {
		mManagement = management;
		mPrefs = prefs;
		mService = service;
		readPrefs();
	}

	private void readPrefs() {
		mPauseOnScreenOff = mPrefs.getBoolean("screenoff", false);
		mNetchangeReconnect = mPrefs.getBoolean("netchangereconnect", true);
	}

	private void updatePauseState() {
		boolean pause = false;
		if (mPauseOnScreenOff && mScreenOff && !mKeepaliveActive) {
			pause = true;
		}
		if (mNetworkOff) {
			pause = true;
		}
		if (pause && !mPaused) {
			Log.i(TAG, "pausing: mScreenOff=" + mScreenOff + " mNetworkOff=" + mNetworkOff);
			mManagement.pause();
		} else if (!pause && mPaused) {
			Log.i(TAG, "resuming: mScreenOff=" + mScreenOff + " mNetworkOff=" + mNetworkOff);
			mManagement.resume();
		}
		mPaused = pause;
	}

	@Override
	public void onReceive(Context context, Intent intent) {
		String s = intent.getAction();

		if (PREF_CHANGED.equals(s)) {
			/*
			 * Do not call into libopenconnect from this thread. JNI stored
			 * the VPN thread's JNIEnv; using it from the UI thread deadlocks.
			 */
			readPrefs();
			updatePauseState();
			return;
		} else if (Intent.ACTION_SCREEN_OFF.equals(s)) {
			mScreenOff = true;
		} else if (Intent.ACTION_SCREEN_ON.equals(s)) {
			mScreenOff = false;
		}
		updatePauseState();
	}

	public void start(Context context) {
		mCm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
		mNetCb = new ConnectivityManager.NetworkCallback() {
			@Override
			public void onAvailable(Network network) {
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						applyNetwork(uplinkOf(network));
					}
				});
			}

			@Override
			public void onLost(Network network) {
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						applyNetwork(findUplink());
					}
				});
			}

			@Override
			public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						applyNetwork(uplinkOf(network));
					}
				});
			}
		};
		NetworkRequest request = new NetworkRequest.Builder()
				.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
				.build();
		mCm.registerNetworkCallback(request, mNetCb);
		applyNetwork(findUplink());
	}

	public void stop() {
		if (mCm != null && mNetCb != null) {
			try {
				mCm.unregisterNetworkCallback(mNetCb);
			} catch (RuntimeException e) {
				Log.w(TAG, "unregisterNetworkCallback", e);
			}
		}
		mNetCb = null;
		mCm = null;
	}

	private Network findUplink() {
		if (mCm == null) {
			return null;
		}
		Network[] nets = mCm.getAllNetworks();
		if (nets == null) {
			return null;
		}
		for (int i = 0; i < nets.length; i++) {
			Network n = nets[i];
			if (isVpnTransport(n)) {
				continue;
			}
			NetworkCapabilities caps = mCm.getNetworkCapabilities(n);
			if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
				return n;
			}
		}
		return null;
	}

	private Network uplinkOf(Network network) {
		if (network != null && !isVpnTransport(network)) {
			return network;
		}
		return findUplink();
	}

	private boolean isVpnTransport(Network network) {
		if (mCm == null || network == null) {
			return false;
		}
		NetworkCapabilities caps = mCm.getNetworkCapabilities(network);
		return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
	}

	private void applyNetwork(Network network) {
		if (mCm == null || network == null || isVpnTransport(network)) {
			mNetworkOff = true;
			setUnderlying(null);
			updatePauseState();
			return;
		}
		NetworkCapabilities caps = mCm.getNetworkCapabilities(network);
		if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
				|| caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
			mNetworkOff = true;
			setUnderlying(null);
			updatePauseState();
			return;
		}

		int networkType = transportCode(caps);
		if (mNetworkType != -1 && mNetworkType != networkType) {
			if (!mPaused && mNetchangeReconnect) {
				Log.i(TAG, "reconnecting due to network type change");
				mManagement.reconnect();
			}
		}
		mNetworkType = networkType;
		mNetworkOff = false;
		setUnderlying(network);
		updatePauseState();
	}

	private void setUnderlying(Network network) {
		if (mService == null) {
			return;
		}
		try {
			mService.setUnderlyingNetworks(network == null ? null : new Network[] { network });
		} catch (Exception e) {
			Log.w(TAG, "setUnderlyingNetworks", e);
		}
	}

	private static int transportCode(NetworkCapabilities caps) {
		if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
				|| caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
			return 1;
		}
		if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
			return 2;
		}
		return 3;
	}

	public void setKeepalive(boolean active) {
		mKeepaliveActive = active;
		updatePauseState();
	}
}
