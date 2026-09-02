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

package net.openconnect_vpn.android.fragments;

import java.util.ArrayList;
import java.util.Collections;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Outline;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import net.openconnect_vpn.android.R;
import net.openconnect_vpn.android.VpnProfile;
import net.openconnect_vpn.android.core.FlagStore;
import net.openconnect_vpn.android.core.GeoLookup;
import net.openconnect_vpn.android.core.OpenConnectManagementThread;
import net.openconnect_vpn.android.core.OpenVpnService;
import net.openconnect_vpn.android.core.ProfileManager;
import net.openconnect_vpn.android.core.VPNConnector;

import org.infradead.libopenconnect.LibOpenConnect;

/**
 * Status screen: render VPN state only.
 * Auth dialogs and screen switching belong to MainActivity.
 */
public class StatusFragment extends Fragment {

	private static final String PREF_SWITCH_GAP_NOTE = "switch_gap_note_seen";

	private View mView;
	private VPNConnector mConn;

	private CommonMenu mDropdown;
	private Button mDisconnectButton;
	private View mSwitchButton;
	private TextView mSwitchLabel;

	/** Avoid rebuilding switch-flag chips on every stats tick. */
	private String mFlagsBoundForUuid;
	private String mCachedOtherUuid;
	private int mCachedOtherCount = -1;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		super.onCreateView(inflater, container, savedInstanceState);

		mView = inflater.inflate(R.layout.status, container, false);
		mDisconnectButton = (Button) mView.findViewById(R.id.disconnect_button);
		mSwitchButton = mView.findViewById(R.id.switch_server_button);
		mSwitchLabel = (TextView) mView.findViewById(R.id.switch_server_label);
		ProfileManager.init(getActivity());

		mDisconnectButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				if (mConn == null || mConn.service == null) {
					return;
				}
				mDisconnectButton.setEnabled(false);
				if (mConn.service.canAbortSwitch()) {
					mConn.service.abortSwitch();
					return;
				}
				if (mConn.service.getConnectionState() ==
						OpenConnectManagementThread.STATE_DISCONNECTED
						&& !mConn.service.isSwitching()) {
					mConn.service.startReconnectActivity(getActivity());
				} else {
					mConn.service.stopVPN();
				}
			}
		});

		mSwitchButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (!v.isEnabled() || mConn == null || mConn.service == null) {
					return;
				}
				if (mConn.service.isSwitching()) {
					return;
				}
				maybeShowGapNote();
			}
		});

		/* pollStats=true → rates/uptime only while CONNECTED (see VPNConnector). */
		mConn = new VPNConnector(getActivity(), false, true) {
			@Override
			public void onUpdate(OpenVpnService service) {
				updateUI(service);
			}
		};

		return mView;
	}

	@Override
	public void onResume() {
		super.onResume();
		mCachedOtherCount = -1;
		mCachedOtherUuid = null;
		if (mConn != null) {
			mConn.resumeStats();
		}
	}

	@Override
	public void onPause() {
		if (mConn != null) {
			mConn.pauseStats();
		}
		super.onPause();
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		mDropdown = new CommonMenu(getActivity(), menu, true);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (mDropdown.onOptionsItemSelected(item)) {
			return true;
		} else {
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onDestroyView() {
		if (mConn != null) {
			mConn.unbind();
			mConn = null;
		}
		super.onDestroyView();
	}

	private void maybeShowGapNote() {
		Activity activity = getActivity();
		if (activity == null) {
			return;
		}
		boolean seen = PreferenceManager.getDefaultSharedPreferences(activity)
				.getBoolean(PREF_SWITCH_GAP_NOTE, false);
		if (seen) {
			openSwitchSheet();
			return;
		}
		new AlertDialog.Builder(activity)
				.setTitle(R.string.switch_gap_title)
				.setMessage(R.string.switch_gap_message)
				.setNeutralButton(R.string.switch_gap_open_settings,
						new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								markGapNoteSeen();
								openVpnSettings();
								openSwitchSheet();
							}
						})
				.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						markGapNoteSeen();
						openSwitchSheet();
					}
				})
				.setOnCancelListener(new DialogInterface.OnCancelListener() {
					@Override
					public void onCancel(DialogInterface dialog) {
						markGapNoteSeen();
					}
				})
				.show();
	}

	private void markGapNoteSeen() {
		Activity activity = getActivity();
		if (activity == null) {
			return;
		}
		PreferenceManager.getDefaultSharedPreferences(activity)
				.edit()
				.putBoolean(PREF_SWITCH_GAP_NOTE, true)
				.apply();
	}

	private void openVpnSettings() {
		Activity activity = getActivity();
		if (activity == null) {
			return;
		}
		try {
			activity.startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
		} catch (Exception e) {
			try {
				activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
			} catch (Exception ignored) {
			}
		}
	}

	private void openSwitchSheet() {
		Activity activity = getActivity();
		if (activity == null || mConn == null || mConn.service == null) {
			return;
		}
		SwitchServerSheet.show(activity, mConn.service.getProfileUuid(),
				new SwitchServerSheet.Listener() {
					@Override
					public void onPick(VpnProfile profile) {
						if (mConn != null && mConn.service != null && profile != null) {
							mConn.service.switchToProfile(profile.getUUIDString());
						}
					}
				});
	}

	private void setText(int id, String value) {
		TextView tv = (TextView) mView.findViewById(id);
		if (tv != null) {
			tv.setText(value);
		}
	}

	private static String firstNonEmpty(String a, String b) {
		if (a != null && a.length() > 0) {
			return a;
		}
		return b != null ? b : "";
	}

	private int otherProfileCount(String currentUuid) {
		if (currentUuid != null && currentUuid.equals(mCachedOtherUuid) && mCachedOtherCount >= 0) {
			return mCachedOtherCount;
		}
		int n = 0;
		for (VpnProfile p : ProfileManager.getProfiles()) {
			if (p != null && p.isValid() && !p.getUUIDString().equals(currentUuid)) {
				n++;
			}
		}
		mCachedOtherUuid = currentUuid;
		mCachedOtherCount = n;
		return n;
	}

	private void bindStatusFlag(OpenVpnService service) {
		String iso = firstNonEmpty(service.publicIso,
				GeoLookup.isoOf(service.profile != null ? service.profile.mPrefs : null));
		ImageView img = (ImageView) mView.findViewById(R.id.status_orb_flag_img);
		TextView fallback = (TextView) mView.findViewById(R.id.status_orb_flag);
		View inner = mView.findViewById(R.id.status_orb_inner);
		boolean shown = FlagStore.bind(img, iso);
		if (inner != null) {
			inner.setVisibility(shown ? View.GONE : View.VISIBLE);
		}
		if (fallback != null) {
			fallback.setVisibility(shown ? View.GONE : View.VISIBLE);
			if (!shown) {
				fallback.setText("VPN");
			}
		}
	}

	private void bindOtherServerFlags(String currentUuid) {
		if (currentUuid != null && currentUuid.equals(mFlagsBoundForUuid)) {
			return;
		}
		mFlagsBoundForUuid = currentUuid;
		LinearLayout row = (LinearLayout) mView.findViewById(R.id.switch_server_flags);
		if (row == null) {
			return;
		}
		row.removeAllViews();
		Activity activity = getActivity();
		if (activity == null) {
			return;
		}
		ArrayList<VpnProfile> others = new ArrayList<VpnProfile>();
		for (VpnProfile p : ProfileManager.getProfiles()) {
			if (p != null && p.isValid() && !p.getUUIDString().equals(currentUuid)) {
				others.add(p);
			}
		}
		Collections.sort(others);
		float density = getResources().getDisplayMetrics().density;
		final int size = Math.round(24 * density);
		int gap = Math.round(3 * density);
		int shown = 0;
		for (int i = 0; i < others.size() && shown < 3; i++) {
			String iso = GeoLookup.isoOf(others.get(i).mPrefs);
			FrameLayout half = new FrameLayout(activity);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size / 2, size);
			if (shown > 0) {
				lp.setMarginStart(gap);
			}
			half.setLayoutParams(lp);
			clipLeftSemicircle(half, size);

			ImageView flag = new ImageView(activity);
			FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(size, size);
			flp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
			flag.setLayoutParams(flp);
			flag.setScaleType(ImageView.ScaleType.CENTER_CROP);
			if (!FlagStore.bind(flag, iso)) {
				continue;
			}
			half.addView(flag);
			row.addView(half);
			shown++;
		}
		row.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
	}

	private static void clipLeftSemicircle(final View view, final int fullSize) {
		view.setClipToOutline(true);
		view.setOutlineProvider(new ViewOutlineProvider() {
			@Override
			public void getOutline(View v, Outline outline) {
				outline.setRoundRect(0, 0, fullSize, fullSize, fullSize / 2f);
			}
		});
	}

	private void setSwitchingCard(boolean on, String message) {
		int details = on ? View.GONE : View.VISIBLE;
		mView.findViewById(R.id.status_rates).setVisibility(details);
		mView.findViewById(R.id.public_country).setVisibility(on ? View.GONE : View.VISIBLE);
		mView.findViewById(R.id.public_ip4).setVisibility(on ? View.GONE : View.VISIBLE);
		mView.findViewById(R.id.public_ip6).setVisibility(on ? View.GONE : View.VISIBLE);
		mView.findViewById(R.id.server_name).setVisibility(details);
		mView.findViewById(R.id.status_local_ip4_row).setVisibility(details);
		mView.findViewById(R.id.local_ip6).setVisibility(details);
		mView.findViewById(R.id.tunnel_crypto).setVisibility(details);
		TextView msg = (TextView) mView.findViewById(R.id.status_switch_msg);
		if (on) {
			msg.setText(message);
			msg.setVisibility(View.VISIBLE);
		} else {
			msg.setVisibility(View.GONE);
		}
	}

	private void updateSwitchButton(OpenVpnService service, boolean switching, boolean session) {
		if (!session) {
			mSwitchButton.setVisibility(View.GONE);
			mFlagsBoundForUuid = null;
			return;
		}
		mSwitchButton.setVisibility(View.VISIBLE);
		int others = otherProfileCount(service.getProfileUuid());
		boolean enabled = !switching && others > 0;
		mSwitchButton.setEnabled(enabled);
		mSwitchButton.setAlpha(enabled ? 1f : 0.45f);
		mSwitchButton.setClickable(enabled);
		if (others == 0) {
			mSwitchLabel.setText(R.string.switch_no_other);
		} else {
			mSwitchLabel.setText(R.string.switch_server);
		}
		bindOtherServerFlags(service.getProfileUuid());
	}

	private void updateUI(OpenVpnService service) {
		if (!isAdded() || mView == null || service == null) {
			return;
		}
		int state = service.getConnectionState();
		boolean switching = service.isSwitching();
		boolean connected = state == OpenConnectManagementThread.STATE_CONNECTED;
		boolean session = connected || switching
				|| state != OpenConnectManagementThread.STATE_DISCONNECTED;

		View progress = mView.findViewById(R.id.status_orb_progress);
		progress.setVisibility(switching || (!connected && session) ? View.VISIBLE : View.GONE);

		if (switching) {
			String target = service.getSwitchToName();
			String msg = service.isFallingBack()
					? getString(R.string.returning_to, target)
					: getString(R.string.switching_to, target);
			setText(R.id.connection_state, msg);
			mView.findViewById(R.id.connection_time).setVisibility(View.INVISIBLE);
			mView.findViewById(R.id.connection_rows).setVisibility(View.VISIBLE);
			setSwitchingCard(true, msg);
			bindStatusFlag(service);
		} else if (connected) {
			setSwitchingCard(false, "");
			bindConnectedCard(service);
		} else {
			setSwitchingCard(false, "");
			setText(R.id.connection_state, service.getConnectionStateName());
			setText(R.id.connection_time, "");
			mView.findViewById(R.id.connection_rows).setVisibility(View.INVISIBLE);
			mView.findViewById(R.id.connection_time).setVisibility(View.INVISIBLE);
		}

		updateSwitchButton(service, switching, session);

		if (state == OpenConnectManagementThread.STATE_DISCONNECTED && !switching) {
			mDisconnectButton.setVisibility(View.GONE);
		} else {
			mDisconnectButton.setVisibility(View.VISIBLE);
			mDisconnectButton.setEnabled(true);
			if (service.canAbortSwitch()) {
				mDisconnectButton.setText(R.string.abort_switch);
				mDisconnectButton.setBackgroundResource(R.drawable.bg_button_abort);
				mDisconnectButton.setTextColor(getResources().getColor(R.color.myoc_warn_text, null));
			} else {
				mDisconnectButton.setText(R.string.disconnect);
				styleWideButton();
			}
		}
	}

	private void bindConnectedCard(OpenVpnService service) {
		mView.findViewById(R.id.connection_rows).setVisibility(View.VISIBLE);
		mView.findViewById(R.id.connection_time).setVisibility(View.VISIBLE);

		bindStatusFlag(service);

		String title = getString(R.string.state_connected_to, service.profile.getName());
		setText(R.id.connection_state, title);
		setText(R.id.connection_time,
				getString(R.string.uptime) + "  ·  "
						+ OpenVpnService.formatElapsedTime(service.startTime.getTime()));

		int statsVisibility = mConn.statsValid ? View.VISIBLE : View.INVISIBLE;
		mView.findViewById(R.id.tx).setVisibility(statsVisibility);
		mView.findViewById(R.id.rx).setVisibility(statsVisibility);

		setText(R.id.tx, "↑  " + getString(R.string.oneway_bytecount,
				OpenVpnService.humanReadableByteCount(mConn.deltaStats.txBytes, true),
				OpenVpnService.humanReadableByteCount(mConn.newStats.txBytes, false)));

		setText(R.id.rx, "↓  " + getString(R.string.oneway_bytecount,
				OpenVpnService.humanReadableByteCount(mConn.deltaStats.rxBytes, true),
				OpenVpnService.humanReadableByteCount(mConn.newStats.rxBytes, false)));
		setText(R.id.server_name, getString(R.string.server_name) + "  ·  " + service.serverName);

		String country = firstNonEmpty(service.publicCountry,
				GeoLookup.prefString(service.profile.mPrefs, GeoLookup.PREF_COUNTRY));
		String ip4 = firstNonEmpty(service.publicIp4,
				GeoLookup.prefString(service.profile.mPrefs, GeoLookup.PREF_IP4));
		String ip6 = firstNonEmpty(service.publicIp6,
				GeoLookup.prefString(service.profile.mPrefs, GeoLookup.PREF_IP6));

		boolean lookupOn = PreferenceManager
				.getDefaultSharedPreferences(getActivity())
				.getBoolean(GeoLookup.PREF_ENABLED, true);

		if (country.length() > 0) {
			setText(R.id.public_country, country);
			mView.findViewById(R.id.public_country).setVisibility(View.VISIBLE);
		} else if (lookupOn) {
			setText(R.id.public_country, getString(R.string.public_ip_looking));
			mView.findViewById(R.id.public_country).setVisibility(View.VISIBLE);
		} else {
			mView.findViewById(R.id.public_country).setVisibility(View.GONE);
		}

		if (ip4.length() > 0) {
			setText(R.id.public_ip4, getString(R.string.public_ip4) + "  ·  " + ip4);
			mView.findViewById(R.id.public_ip4).setVisibility(View.VISIBLE);
		} else {
			mView.findViewById(R.id.public_ip4).setVisibility(View.GONE);
		}

		if (ip6.length() > 0) {
			setText(R.id.public_ip6, getString(R.string.public_ip6) + "  ·  " + ip6);
			mView.findViewById(R.id.public_ip6).setVisibility(View.VISIBLE);
		} else {
			mView.findViewById(R.id.public_ip6).setVisibility(View.GONE);
		}

		LibOpenConnect.IPInfo ip = service.ipInfo;
		String dis = getString(R.string.disabled);

		if (ip != null && ip.addr != null && ip.netmask != null) {
			setText(R.id.local_ip4, getString(R.string.local_ip4) + "  ·  " + ip.addr);
			setText(R.id.local_ip4_netmask, ip.netmask);
		} else {
			setText(R.id.local_ip4, getString(R.string.local_ip4) + "  ·  " + dis);
			setText(R.id.local_ip4_netmask, "");
		}

		setText(R.id.local_ip6, getString(R.string.local_ip6) + "  ·  "
				+ (ip != null && ip.netmask6 != null ? ip.netmask6 : dis));

		String dtls = service.dtlsCipher;
		String cstp = service.cstpCipher;
		String transport;
		if (dtls != null && dtls.length() > 0) {
			transport = "DTLS · " + dtls;
		} else if (cstp != null && cstp.length() > 0) {
			transport = "CSTP · " + cstp;
		} else {
			transport = getString(R.string.unknown);
		}
		String compr = service.dtlsCompression != null ? service.dtlsCompression
				: service.cstpCompression;
		if (compr != null && compr.length() > 0) {
			transport += " · " + compr;
		}
		setText(R.id.tunnel_crypto, getString(R.string.tunnel_crypto) + "  ·  " + transport);
	}

	private void styleWideButton() {
		if (mDisconnectButton == null) {
			return;
		}
		mDisconnectButton.setBackgroundResource(R.drawable.bg_button_disconnect);
		mDisconnectButton.setTextColor(getResources().getColor(R.color.myoc_text, null));
	}
}
