/*
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Dedicated foreground UI for VPN login / cert / error prompts.
 * Keeps credential dialogs on top instead of behind other apps.
 */

package net.openconnect_vpn.android;

import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import net.openconnect_vpn.android.core.OpenVpnService;
import net.openconnect_vpn.android.core.VPNConnector;

public class AuthPromptActivity extends ThemedActivity {

	private VPNConnector mConn;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		applyPromptActivityWindow();
	}

	@Override
	protected void onResume() {
		super.onResume();
		bindPrompt();
	}

	@Override
	protected void onPause() {
		if (mConn != null) {
			mConn.stopActiveDialog();
			mConn.unbind();
			mConn = null;
		}
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		if (mConn != null) {
			mConn.stopActiveDialog();
			mConn.unbind();
			mConn = null;
		}
		super.onDestroy();
	}

	private void bindPrompt() {
		if (mConn != null) {
			return;
		}
		mConn = new VPNConnector(this, true) {
			@Override
			public void onUpdate(OpenVpnService service) {
				if (service.hasPendingUserDialog()) {
					service.startActiveDialog(AuthPromptActivity.this);
				} else if (!isFinishing()) {
					finish();
				}
			}
		};
	}

	private void applyPromptActivityWindow() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
			setShowWhenLocked(true);
			setTurnScreenOn(true);
		}
		getWindow().addFlags(
				WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
						| WindowManager.LayoutParams.FLAG_DIM_BEHIND);
	}
}
