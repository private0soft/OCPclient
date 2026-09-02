/*
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package net.openconnect_vpn.android.core;

import android.app.AlertDialog;
import android.view.Window;
import android.view.WindowManager;

public final class UserDialogUi {

	private UserDialogUi() {
	}

	public static void show(AlertDialog dialog) {
		dialog.show();
		Window window = dialog.getWindow();
		if (window == null) {
			return;
		}
		window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
		window.setSoftInputMode(
				WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
						| WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
	}
}
