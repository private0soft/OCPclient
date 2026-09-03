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

import java.util.HashMap;
import java.util.Map;

import net.openconnect_vpn.android.AppListActivity;
import net.openconnect_vpn.android.ConnectionEditorActivity;
import net.openconnect_vpn.android.R;
import net.openconnect_vpn.android.ShowTextPreference;
import net.openconnect_vpn.android.TokenImportActivity;
import net.openconnect_vpn.android.VpnProfile;
import net.openconnect_vpn.android.core.PerAppVpn;
import net.openconnect_vpn.android.core.ProfileManager;
import net.openconnect_vpn.android.core.CredentialStore;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceManager;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;

public class ConnectionEditorFragment extends ThemedPreferenceFragment
		implements OnSharedPreferenceChangeListener {

	PreferenceManager mPrefs;
	VpnProfile mProfile;
	String mUUID;

    HashMap<String,Integer> fileSelectMap = new HashMap<String,Integer>();

    private final int IDX_TOKEN_STRING = 65536;

	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mProfile = ProfileManager.get(getArguments().getString("profileUUID"));
        mUUID = mProfile.getUUIDString();

        mPrefs = getPreferenceManager();
        mPrefs.setSharedPreferencesName(ProfileManager.getPrefsName(mUUID));
        mPrefs.setSharedPreferencesMode(Context.MODE_PRIVATE);

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.pref_openconnect);
        setClickListeners();

        SharedPreferences sp = mPrefs.getSharedPreferences();
        for (Map.Entry<String,?> entry : sp.getAll().entrySet()) {
            updatePref(sp, entry.getKey());
        }
        /* Ensure new per-app prefs show defaults even before first save */
        updatePref(sp, PerAppVpn.PREF_SOURCE);
        updatePref(sp, PerAppVpn.PREF_MODE);
        updatePerAppPrefs(sp);
        updateSharedLoginPref(sp);
    }

    @Override
	public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
		updatePerAppPrefs(getPreferenceScreen().getSharedPreferences());
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    private void updatePref(SharedPreferences sp, String key) {
        if (PerAppVpn.PREF_PACKAGES.equals(key)) {
            updatePerAppPrefs(sp);
            return;
        }

        String value;
        try {
            value = sp.getString(key, "");
        } catch (ClassCastException e) {
            /* wasn't a string preference */
            return;
        }

        if (PerAppVpn.PREF_SOURCE.equals(key) && (value == null || value.isEmpty())) {
            value = PerAppVpn.SOURCE_GLOBAL;
        }
        if (PerAppVpn.PREF_MODE.equals(key) && (value == null || value.isEmpty())) {
            value = PerAppVpn.MODE_ALL;
        }

        Preference pref = findPreference(key);
        if (pref != null) {
			if (pref instanceof ListPreference) {
				/* update all spinner prefs so the summary shows the current value */
				ListPreference lpref = (ListPreference)pref;
				lpref.setValue(value);
				pref.setSummary(lpref.getEntry());
			} else {
				/* for ShowTextPreference entries, hide the filename */
				if (fileSelectMap.containsKey(key) && !value.equals("")) {
					pref.setSummary(getString(R.string.stored));
				} else {
					pref.setSummary(value);
				}
			}
        }

        /* disable token_string item if the profile isn't using a software token */
        if (key.equals("software_token")) {
            pref = findPreference("token_string");
            if (pref != null) {
                pref.setEnabled(!value.equals("disabled"));
            }
        }

        /* similarly, if split tunnel is "auto", ignore manually entered subnets */
        if (key.equals("split_tunnel_mode")) {
            pref = findPreference("split_tunnel_networks");
            if (pref != null) {
                pref.setEnabled(!value.equals("auto"));
            }
        }

        if (key.equals(PerAppVpn.PREF_SOURCE) || key.equals(PerAppVpn.PREF_MODE)
				|| key.equals(PerAppVpn.PREF_PACKAGES)) {
			updatePerAppPrefs(sp);
		}

        if (key.equals("profile_name")) {
        	((ConnectionEditorActivity)getActivity()).setProfileName(value);
        }
        if (key.equals("server_address") || key.equals("use_shared_login")) {
        	updateSharedLoginPref(sp);
        }
    }

	private void updateSharedLoginPref(SharedPreferences sp) {
		Preference pref = findPreference("use_shared_login");
		if (pref == null) {
			return;
		}
		String domain = CredentialStore.loginDomain(sp.getString("server_address", ""));
		if (domain == null || domain.isEmpty()) {
			pref.setEnabled(false);
			pref.setSummary(R.string.use_shared_login_unavailable);
			return;
		}
		pref.setEnabled(true);
		if (CredentialStore.hasRealm(getActivity(), domain)) {
			String realmUser = CredentialStore.realmUsername(getActivity(), domain);
			if (realmUser != null && !realmUser.isEmpty()) {
				pref.setSummary(getString(R.string.use_shared_login_summary_saved, domain, realmUser));
			} else {
				pref.setSummary(getString(R.string.use_shared_login_summary_domain, domain));
			}
		} else {
			pref.setSummary(getString(R.string.use_shared_login_summary_domain, domain));
		}
	}

	private void updatePerAppPrefs(SharedPreferences sp) {
		String source = sp.getString(PerAppVpn.PREF_SOURCE, PerAppVpn.SOURCE_GLOBAL);
		boolean custom = PerAppVpn.SOURCE_CUSTOM.equals(source);
		String mode = PerAppVpn.getMode(sp, PerAppVpn.PREF_MODE, PerAppVpn.MODE_ALL);

		Preference modePref = findPreference(PerAppVpn.PREF_MODE);
		Preference appsPref = findPreference(PerAppVpn.PREF_PACKAGES);
		if (modePref != null) {
			modePref.setEnabled(custom);
		}
		if (appsPref != null) {
			boolean needsList = custom && (PerAppVpn.MODE_ALLOWLIST.equals(mode)
					|| PerAppVpn.MODE_DENYLIST.equals(mode));
			appsPref.setEnabled(needsList);
			int count = PerAppVpn.getPackages(sp, PerAppVpn.PREF_PACKAGES).size();
			if (!custom) {
				SharedPreferences globalSp = PerAppVpn.globalPrefs(getActivity());
				PerAppVpn.Config globalCfg = new PerAppVpn.Config(
						PerAppVpn.getMode(globalSp, PerAppVpn.PREF_GLOBAL_MODE, PerAppVpn.MODE_ALL),
						PerAppVpn.getPackages(globalSp, PerAppVpn.PREF_GLOBAL_PACKAGES),
						true);
				appsPref.setSummary(getString(R.string.per_app_source_global) + " — "
						+ PerAppVpn.summarize(getActivity(), globalCfg));
			} else if (PerAppVpn.MODE_ALLOWLIST.equals(mode)) {
				appsPref.setSummary(getString(R.string.per_app_summary_allowlist, count));
			} else if (PerAppVpn.MODE_DENYLIST.equals(mode)) {
				appsPref.setSummary(getString(R.string.per_app_summary_denylist, count));
			} else {
				appsPref.setSummary(R.string.per_app_select_apps_summary);
			}
		}
	}

	public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
		updatePref(sp, key);
	}

	private void setClickListeners() {
		for (int idx = 0; idx < ProfileManager.fileSelectKeys.length; idx++) {
			String key = ProfileManager.fileSelectKeys[idx];
			Preference p = findPreference(key);
			fileSelectMap.put(key, idx);

			/* Start up a FileSelect activity to import data from the filesystem */
			p.setOnPreferenceClickListener(new OnPreferenceClickListener() {
				@Override
				public boolean onPreferenceClick(Preference preference) {
					String key = preference.getKey();
					Integer idx = fileSelectMap.get(key);
					if (idx == null) {
						return false;
					}

					SharedPreferences sharedPrefs = getPreferenceScreen().getSharedPreferences();
					String value = sharedPrefs.getString(key, "");
					if (!value.isEmpty()) {
						ProfileManager.deleteFilePref(mProfile, key);
						((ShowTextPreference)preference).setText(null);
						updatePref(sharedPrefs, key);
					} else {
						Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
						intent.addCategory(Intent.CATEGORY_OPENABLE);
						intent.setType("*/*");

						startActivityForResult(intent, idx);
					}
					return false;
				}
			});
		}

		Preference p = findPreference("token_string");
		/* The TokenImport activity will set the token_string preference for us */
		p.setOnPreferenceClickListener(new OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				Intent intent = new Intent(getActivity(), TokenImportActivity.class);
				intent.putExtra(TokenImportActivity.EXTRA_UUID, mUUID);
				startActivityForResult(intent, IDX_TOKEN_STRING);
				return false;
			}
		});

		p = findPreference("delete_profile");
		// don't show delete preference when not on a tv
		if(getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
			p.setOnPreferenceClickListener(new OnPreferenceClickListener() {
				@Override
				public boolean onPreferenceClick(Preference preference) {
					((ConnectionEditorActivity)getActivity()).askProfileRemoval();
					return false;
				}
			});
		} else {
			getPreferenceScreen().removePreference(p);
		}

		Preference apps = findPreference(PerAppVpn.PREF_PACKAGES);
		if (apps != null) {
			apps.setOnPreferenceClickListener(new OnPreferenceClickListener() {
				@Override
				public boolean onPreferenceClick(Preference preference) {
					Intent intent = new Intent(getActivity(), AppListActivity.class);
					intent.putExtra(AppListActivity.EXTRA_USE_GLOBAL, false);
					intent.putExtra(AppListActivity.EXTRA_PROFILE_UUID, mUUID);
					startActivity(intent);
					return true;
				}
			});
		}

		updatePerAppPrefs(getPreferenceScreen().getSharedPreferences());
	}

	@Override
	public void onActivityResult(int idx, int resultCode, Intent data) {
		super.onActivityResult(idx, resultCode, data);

		if (resultCode != Activity.RESULT_OK) {
			return;
		}

		SharedPreferences prefs = mPrefs.getSharedPreferences();
		if (idx >= IDX_TOKEN_STRING) {
			updatePref(prefs, "token_string");
			updatePref(prefs, "software_token");
		} else {
			Uri path = data.getData();
			String key = ProfileManager.fileSelectKeys[idx];
			ShowTextPreference p = (ShowTextPreference)findPreference(key);
			String new_path = ProfileManager.storeFilePref(mProfile, key, path);
			p.setText(new_path);
			updatePref(prefs, key);
		}
	}
}
