/*
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Bottom sheet for picking another VPN profile while connected.
 * Does not show hostnames, IPs, or passwords.
 */

package net.openconnect_vpn.android.fragments;

import java.util.ArrayList;
import java.util.Collections;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import net.openconnect_vpn.android.R;
import net.openconnect_vpn.android.VpnProfile;
import net.openconnect_vpn.android.core.FlagStore;
import net.openconnect_vpn.android.core.GeoLookup;
import net.openconnect_vpn.android.core.ProfileManager;

public final class SwitchServerSheet {

	public interface Listener {
		void onPick(VpnProfile profile);
	}

	private SwitchServerSheet() {
	}

	public static Dialog show(Activity activity, String currentUuid, final Listener listener) {
		final Dialog dialog = new Dialog(activity, R.style.MyOC_BottomSheet);
		dialog.setContentView(R.layout.dialog_switch_server);
		Window window = dialog.getWindow();
		if (window != null) {
			window.setGravity(Gravity.BOTTOM);
			window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		}

		LinearLayout list = (LinearLayout) dialog.findViewById(R.id.switch_profile_list);
		LayoutInflater inflater = LayoutInflater.from(activity);

		ArrayList<VpnProfile> others = new ArrayList<VpnProfile>();
		VpnProfile current = null;
		for (VpnProfile p : ProfileManager.getProfiles()) {
			if (p == null || !p.isValid()) {
				continue;
			}
			if (p.getUUIDString().equals(currentUuid)) {
				current = p;
			} else {
				others.add(p);
			}
		}
		Collections.sort(others);

		if (current != null) {
			addRow(inflater, list, current, true, dialog, listener);
		}
		for (VpnProfile p : others) {
			addRow(inflater, list, p, false, dialog, listener);
		}

		dialog.findViewById(R.id.switch_cancel).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				dialog.dismiss();
			}
		});
		dialog.show();
		return dialog;
	}

	private static void addRow(LayoutInflater inflater, ViewGroup parent, final VpnProfile profile,
			final boolean current, final Dialog dialog, final Listener listener) {
		View row = inflater.inflate(R.layout.item_switch_profile, parent, false);
		TextView name = (TextView) row.findViewById(R.id.switch_item_name);
		TextView meta = (TextView) row.findViewById(R.id.switch_item_meta);
		TextView check = (TextView) row.findViewById(R.id.switch_item_check);
		ImageView flag = (ImageView) row.findViewById(R.id.switch_item_flag);

		name.setText(profile.getName());
		String country = GeoLookup.prefString(profile.mPrefs, GeoLookup.PREF_COUNTRY);
		if (current) {
			meta.setText(row.getResources().getString(R.string.switch_current));
			check.setVisibility(View.VISIBLE);
			row.setAlpha(0.55f);
		} else if (country.length() > 0) {
			meta.setText(country);
		} else {
			meta.setVisibility(View.GONE);
		}
		FlagStore.bind(flag, GeoLookup.isoOf(profile.mPrefs));

		row.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				dialog.dismiss();
				if (!current && listener != null) {
					listener.onPick(profile);
				}
			}
		});
		parent.addView(row);
	}
}
