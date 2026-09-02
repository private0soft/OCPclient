/*
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Invisible activity so a widget or Quick Settings tile can start/stop
 * the VPN with a user-visible task (needed for VpnService.prepare).
 */

package net.openconnect_vpn.android;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Toast;
import net.openconnect_vpn.android.core.OpenVpnService;
import net.openconnect_vpn.android.core.VpnQuick;

public class VpnToggleActivity extends ThemedActivity {

	private String mUUID;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (VpnQuick.isSessionActive(this)) {
			startService(VpnQuick.disconnectIntent(this));
			finish();
			return;
		}

		VpnProfile profile = VpnQuick.lastProfile(this);
		if (profile == null || !profile.isValid()) {
			Toast.makeText(this, R.string.widget_no_profile, Toast.LENGTH_SHORT).show();
			Intent home = new Intent(this, MainActivity.class);
			home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(home);
			finish();
			return;
		}

		mUUID = profile.getUUIDString();
		Intent prep;
		try {
			prep = VpnService.prepare(this);
		} catch (Exception e) {
			finish();
			return;
		}
		if (prep != null) {
			try {
				startActivityForResult(prep, 0);
			} catch (Exception e) {
				finish();
			}
			return;
		}
		startVpn();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode == RESULT_OK) {
			startVpn();
			return;
		}
		finish();
	}

	private void startVpn() {
		startService(VpnQuick.connectIntent(this, mUUID));
		finish();
	}
}
