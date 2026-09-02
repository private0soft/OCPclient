/*
 * Copyright (c) 2019.
 * Copyright (c) 2026 MyOCApp contributors
 *
 * Quick Settings tile: tap in the shade to connect or disconnect.
 */

package net.openconnect_vpn.android;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import net.openconnect_vpn.android.core.OpenConnectManagementThread;
import net.openconnect_vpn.android.core.OpenVpnService;
import net.openconnect_vpn.android.core.VPNConnector;
import net.openconnect_vpn.android.core.VpnQuick;

public class QSTileService extends TileService {

	private VPNConnector mConn;

	@Override
	public void onStartListening() {
		super.onStartListening();
		apply(VpnQuick.connectionState(this), nameOf(VpnQuick.lastProfile(this)));
		mConn = new VPNConnector(this, false) {
			@Override
			public void onUpdate(OpenVpnService service) {
				String name = service.getReconnectName();
				if (name == null) {
					name = nameOf(VpnQuick.lastProfile(QSTileService.this));
				}
				apply(service.getConnectionState(), name);
			}
		};
	}

	@Override
	public void onStopListening() {
		if (mConn != null) {
			mConn.unbind();
			mConn = null;
		}
		super.onStopListening();
	}

	@Override
	public void onClick() {
		super.onClick();
		if (isLocked()) {
			unlockAndRun(new Runnable() {
				@Override
				public void run() {
					launchToggle();
				}
			});
			return;
		}
		launchToggle();
	}

	@SuppressLint("StartActivityAndCollapseDeprecated")
	private void launchToggle() {
		if (Build.VERSION.SDK_INT >= 34) {
			PendingIntent pi = PendingIntent.getActivity(this, 2,
					VpnQuick.toggleIntent(this),
					PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			startActivityAndCollapse(pi);
		} else {
			startActivityAndCollapse(VpnQuick.toggleIntent(this));
		}
	}

	private void apply(int state, String profileName) {
		Tile tile = getQsTile();
		if (tile == null) {
			return;
		}
		int tileState;
		String label;
		if (state == OpenConnectManagementThread.STATE_CONNECTED) {
			tileState = Tile.STATE_ACTIVE;
			label = profileName != null ? profileName : getString(R.string.widget_connected);
		} else if (state != OpenConnectManagementThread.STATE_DISCONNECTED && state != 0) {
			tileState = Tile.STATE_ACTIVE;
			label = getString(R.string.widget_connecting);
		} else {
			tileState = Tile.STATE_INACTIVE;
			label = profileName != null ? profileName : getString(R.string.app);
		}
		tile.setState(tileState);
		tile.setLabel(label);
		if (Build.VERSION.SDK_INT >= 29) {
			if (state == OpenConnectManagementThread.STATE_CONNECTED) {
				tile.setSubtitle(getString(R.string.widget_connected));
			} else if (tileState == Tile.STATE_ACTIVE) {
				tile.setSubtitle(getString(R.string.widget_connecting));
			} else {
				tile.setSubtitle(getString(R.string.widget_connect));
			}
		}
		tile.updateTile();
	}

	private static String nameOf(VpnProfile profile) {
		return profile != null ? profile.getName() : null;
	}
}
