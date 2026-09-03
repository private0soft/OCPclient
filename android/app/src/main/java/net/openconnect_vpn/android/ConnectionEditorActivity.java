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

package net.openconnect_vpn.android;

import net.openconnect_vpn.android.core.ProfileManager;
import net.openconnect_vpn.android.fragments.ConnectionEditorFragment;
import net.openconnect_vpn.android.VpnProfile;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import net.openconnect_vpn.android.R;

public class ConnectionEditorActivity extends ThemedActivity {

    private String mName = "";
    private String mUUID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundResource(R.drawable.bg_screen_gradient);

        ConnectionEditorFragment frag = new ConnectionEditorFragment();
        mUUID = getIntent().getStringExtra(getPackageName() + ".profileUUID");
        VpnProfile current = ProfileManager.get(mUUID);
        if (current != null) {
            setProfileName(current.getName());
        }
        Bundle args = new Bundle();
        args.putString("profileUUID", mUUID);
        frag.setArguments(args);

        // Display the fragment as the main content.
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, frag)
                .commit();
    }

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.vpnpreferences_menu, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.duplicate_vpn) {
			duplicateProfile();
			return true;
		}
		if(item.getItemId() == R.id.remove_vpn)
			askProfileRemoval();
		return super.onOptionsItemSelected(item);
	}

	private void duplicateProfile() {
		VpnProfile source = ProfileManager.get(mUUID);
		VpnProfile copy = ProfileManager.duplicate(source);
		if (copy == null) {
			return;
		}
		String pfx = getPackageName();
		Intent intent = new Intent(this, ConnectionEditorActivity.class)
				.putExtra(pfx + ".profileUUID", copy.getUUIDString())
				.putExtra(pfx + ".profileName", copy.getName());
		startActivity(intent);
		finish();
	}

	public void setProfileName(String name) {
		mName = name;
    	setTitle(getString(R.string.edit_profile_title, mName));
	}

	public void askProfileRemoval() {
		AlertDialog.Builder dialog = new AlertDialog.Builder(this);
		dialog.setTitle("Confirm deletion");
		dialog.setMessage(getString(R.string.remove_vpn_query, mName));

		dialog.setPositiveButton(android.R.string.yes,
				new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				ProfileManager.delete(mUUID);
				finish();
			}
		});
		dialog.setNegativeButton(android.R.string.no,null);
		dialog.create().show();
	}
}
