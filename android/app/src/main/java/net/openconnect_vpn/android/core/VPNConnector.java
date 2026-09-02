/*
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

import org.infradead.libopenconnect.LibOpenConnect.VPNStats;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import net.openconnect_vpn.android.R;
import net.openconnect_vpn.android.core.OpenVpnService.LocalBinder;
import net.openconnect_vpn.android.core.OpenVpnService.StatusListener;

/**
 * Binds UI to OpenVpnService.
 * Primary path: StatusListener. Broadcast is a backup.
 * Optional stats timer runs only while STATE_CONNECTED.
 */
public abstract class VPNConnector {

	public static final String TAG = "OpenConnect";

	private static final long CONNECTED_STATS_INTERVAL_MS = 2500L;

	public OpenVpnService service;
	public VPNStats oldStats = new VPNStats();
	public VPNStats newStats = new VPNStats();
	public VPNStats deltaStats = new VPNStats();
	public boolean statsValid = false;

	private Context mContext;
	private boolean mIsActivity;
	private BroadcastReceiver mReceiver;
	private String mOwnerName;

	private Handler mStatsHandler;
	private Runnable mStatsRunnable;
	private int mStatsCount = 0;
	private final boolean mPollStats;
	private boolean mStatsLooping;

	private final StatusListener mStatusListener = new StatusListener() {
		@Override
		public void onVpnStatus(OpenVpnService svc) {
			if (svc != null) {
				onUpdate(svc);
				if (mPollStats) {
					scheduleStats();
				}
			}
		}
	};

	public abstract void onUpdate(OpenVpnService service);

	@SuppressLint("UnspecifiedRegisterReceiverFlag")
    public VPNConnector(Context ctx, boolean isActivity) {
		this(ctx, isActivity, false);
	}

	@SuppressLint("UnspecifiedRegisterReceiverFlag")
    public VPNConnector(Context ctx, boolean isActivity, boolean pollStats) {
		mContext = ctx;
		mIsActivity = isActivity;
		mPollStats = pollStats;

		Intent intent = new Intent(mContext, OpenVpnService.class);
		intent.setAction(OpenVpnService.START_SERVICE);
		mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

		mReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (service != null) {
					onUpdate(service);
					if (mPollStats) {
						scheduleStats();
					}
				}
			}
		};
		if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			mContext.registerReceiver(mReceiver, new IntentFilter(
					OpenVpnService.ACTION_VPN_STATUS), Context.RECEIVER_NOT_EXPORTED);
		else
			mContext.registerReceiver(mReceiver, new IntentFilter(
					OpenVpnService.ACTION_VPN_STATUS));
		mOwnerName = mContext.getClass().getSimpleName();

		if (mPollStats) {
			mStatsHandler = new Handler(Looper.getMainLooper());
			mStatsRunnable = new Runnable() {
				@Override
				public void run() {
					if (mStatsHandler == null || service == null) {
						mStatsLooping = false;
						return;
					}
					if (service.getConnectionState()
							!= OpenConnectManagementThread.STATE_CONNECTED) {
						mStatsLooping = false;
						statsValid = false;
						mStatsCount = 0;
						return;
					}

					oldStats = newStats;
					newStats = service.getStats();

					deltaStats.rxBytes = newStats.rxBytes - oldStats.rxBytes;
					deltaStats.rxPkts = newStats.rxPkts - oldStats.rxPkts;
					deltaStats.txBytes = newStats.txBytes - oldStats.txBytes;
					deltaStats.txPkts = newStats.txPkts - oldStats.txPkts;

					service.requestStats();

					if (++mStatsCount >= 2) {
						statsValid = true;
					}
					onUpdate(service);
					mStatsHandler.postDelayed(mStatsRunnable, CONNECTED_STATS_INTERVAL_MS);
				}
			};
			scheduleStats();
		}
	}

	public void pauseStats() {
		if (mStatsHandler != null) {
			mStatsHandler.removeCallbacks(mStatsRunnable);
			mStatsLooping = false;
		}
	}

	public void resumeStats() {
		scheduleStats();
	}

	private void scheduleStats() {
		if (!mPollStats || mStatsHandler == null || mStatsRunnable == null) {
			return;
		}
		boolean connected = service != null
				&& service.getConnectionState()
						== OpenConnectManagementThread.STATE_CONNECTED;
		if (!connected) {
			mStatsHandler.removeCallbacks(mStatsRunnable);
			mStatsLooping = false;
			return;
		}
		if (mStatsLooping) {
			return;
		}
		mStatsLooping = true;
		mStatsHandler.post(mStatsRunnable);
	}

	// an Activity should call stopActiveDialog() from onPause()
	public void stopActiveDialog() {
		if (service != null) {
			service.stopActiveDialog(mContext);
		}
	}

	// a Fragment should call unbind() or stop()+unbind() from onDestroyView
	public void stop() {
		if (mReceiver != null) {
			try {
				mContext.unregisterReceiver(mReceiver);
			} catch (IllegalArgumentException e) {
				Log.w(TAG, "receiver already unregistered", e);
			}
			mReceiver = null;
		}

		if (mStatsHandler != null) {
			mStatsHandler.removeCallbacks(mStatsRunnable);
			mStatsHandler = null;
			mStatsLooping = false;
		}
	}

	public void unbind() {
		stop();
		if (service != null) {
			service.removeStatusListener(mStatusListener);
			service.updateActivityRefcount(mIsActivity ? -1 : 0);
		}
		try {
			mContext.unbindService(mConnection);
		} catch (IllegalArgumentException e) {
			Log.w(TAG, "service already unbound", e);
		}
		service = null;
	}

	public String getByteCountSummary() {
		if (!statsValid) {
			return "";
		}
		return mContext.getString(R.string.statusline_bytecount,
				OpenVpnService.humanReadableByteCount(newStats.rxBytes, false),
				OpenVpnService.humanReadableByteCount(deltaStats.rxBytes, true),
				OpenVpnService.humanReadableByteCount(newStats.txBytes, false),
				OpenVpnService.humanReadableByteCount(deltaStats.txBytes, true));
	}

	private ServiceConnection mConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName className, IBinder serviceBinder) {
			LocalBinder binder = (LocalBinder) serviceBinder;
			service = binder.getService();
			service.updateActivityRefcount(mIsActivity ? 1 : 0);
			service.addStatusListener(mStatusListener);
			scheduleStats();
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			if (service != null) {
				service.removeStatusListener(mStatusListener);
			}
			service = null;
			Log.w(TAG, mOwnerName + " was forcibly unbound from OpenVpnService");
		}
	};
}
