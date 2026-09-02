/*
 * Adapted from OpenVPN for Android
 * Copyright (c) 2012-2013, Arne Schwabe
 * All rights reserved.
 *
 * Crash-dump sharing is disabled: minidumps can contain hostnames, IPs,
 * and credentials from the VPN log.
 */

package net.openconnect_vpn.android.fragments;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import net.openconnect_vpn.android.R;

public class SendDumpFragment extends Fragment  {

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_senddump, container, false);
	}
}
