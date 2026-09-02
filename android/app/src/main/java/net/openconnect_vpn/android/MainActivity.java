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

package net.openconnect_vpn.android;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import net.openconnect_vpn.android.core.OpenConnectManagementThread;
import net.openconnect_vpn.android.core.OpenVpnService;
import net.openconnect_vpn.android.core.UpdateCheck;
import net.openconnect_vpn.android.core.UpdateDownloader;
import net.openconnect_vpn.android.core.VPNConnector;
import net.openconnect_vpn.android.fragments.StatusFragment;
import net.openconnect_vpn.android.fragments.VPNProfileList;

public class MainActivity extends ThemedActivity implements UpdateCheck.DownloadListener {

	public static final String TAG = "OpenConnect";

	private int mConnectionState = OpenConnectManagementThread.STATE_DISCONNECTED;
	private boolean mReady;
	private VPNConnector mConn;
	private TextView mUpdateButton;
	private boolean mUpdateVpnTried;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		ActionBar bar = getActionBar();
		if (bar != null) {
			bar.setDisplayShowHomeEnabled(false);
			bar.setDisplayShowTitleEnabled(false);
			bar.setDisplayShowCustomEnabled(true);
			View title = LayoutInflater.from(this).inflate(R.layout.action_bar_title, null);
			TextView versionView = (TextView) title.findViewById(R.id.action_bar_version);
			versionView.setText(getAppVersionLabel());
			mUpdateButton = (TextView) title.findViewById(R.id.action_bar_update);
			if (mUpdateButton != null) {
				mUpdateButton.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						UpdateCheck.startInstall(MainActivity.this);
					}
				});
			}
			ActionBar.LayoutParams lp = new ActionBar.LayoutParams(
					ActionBar.LayoutParams.WRAP_CONTENT,
					ActionBar.LayoutParams.MATCH_PARENT);
			lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
			bar.setCustomView(title, lp);
		}
	}

	private void showHome(Fragment frag) {
		getFragmentManager().beginTransaction()
				.replace(R.id.main_content, frag)
				.commitAllowingStateLoss();
	}

	private void updateUI(OpenVpnService service) {
		int newState = service.getConnectionState();
		/* MainActivity alone owns auth/cert dialogs for the main UI. */
		service.startActiveDialog(this);
		showSwitchResult(service);

		boolean keepStatus = newState != OpenConnectManagementThread.STATE_DISCONNECTED
				|| service.isSwitching();

		if (!mReady) {
			mReady = true;
			mConnectionState = newState;
			if (keepStatus) {
				showStatusIfNeeded();
			} else {
				showProfilesIfNeeded();
			}
			return;
		}

		if (mConnectionState != newState) {
			if (newState == OpenConnectManagementThread.STATE_CONNECTED && !mUpdateVpnTried) {
				mUpdateVpnTried = true;
				UpdateCheck.checkAfterConnect(this);
			}
			if (keepStatus) {
				showStatusIfNeeded();
			} else {
				showProfilesIfNeeded();
			}
			mConnectionState = newState;
		}
	}

	private void showSwitchResult(OpenVpnService service) {
		String toast = service.consumeSwitchToast();
		if (toast != null && toast.length() > 0) {
			Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
		}
		String msg = service.consumeSwitchResult();
		if (msg == null || msg.length() == 0) {
			return;
		}
		new AlertDialog.Builder(this)
				.setMessage(msg)
				.setPositiveButton(R.string.ok, null)
				.show();
	}

	private void showStatusIfNeeded() {
		Fragment f = getFragmentManager().findFragmentById(R.id.main_content);
		if (!(f instanceof StatusFragment)) {
			showHome(new StatusFragment());
		}
	}

	private void showProfilesIfNeeded() {
		Fragment f = getFragmentManager().findFragmentById(R.id.main_content);
		if (!(f instanceof VPNProfileList)) {
			showHome(new VPNProfileList());
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		UpdateCheck.addListener(this);
		if (mConn == null) {
			mConn = new VPNConnector(this, true) {
				@Override
				public void onUpdate(OpenVpnService service) {
					updateUI(service);
				}
			};
		}
		UpdateCheck.maybeCheck(this);
	}

	@Override
	public void onUpdateState(UpdateCheck.Info info, boolean checking) {
		if (mUpdateButton == null) {
			return;
		}
		if (info != null && info.available) {
			mUpdateButton.setVisibility(View.VISIBLE);
			mUpdateButton.setEnabled(!checking && !UpdateDownloader.isBusy());
			mUpdateButton.setText(getString(R.string.update_tag));
		} else {
			mUpdateButton.setVisibility(View.GONE);
		}
	}

	@Override
	public void onDownloadProgress(int percent, boolean error, String message) {
		if (mUpdateButton == null) {
			return;
		}
		if (error) {
			mUpdateButton.setEnabled(true);
			mUpdateButton.setText(getString(R.string.update_tag));
			if (message != null && message.length() > 0) {
				Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
			}
			return;
		}
		mUpdateButton.setEnabled(false);
		if (percent > 0) {
			mUpdateButton.setText(percent + "%");
		} else {
			mUpdateButton.setText("…");
		}
	}

	private String getAppVersionLabel() {
		try {
			String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
			return getString(R.string.myoc_version_label, name);
		} catch (NameNotFoundException e) {
			return "";
		}
	}

	@Override
	protected void onPause() {
		UpdateCheck.removeListener(this);
		if (mConn != null) {
			mConn.stopActiveDialog();
		}
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		if (mConn != null) {
			mConn.unbind();
			mConn = null;
		}
		super.onDestroy();
	}
}
