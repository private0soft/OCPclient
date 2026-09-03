/*
 * Adapted from OpenVPN for Android
 * Copyright (c) 2012-2013, Arne Schwabe
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
import java.util.Map;

import android.Manifest.permission;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import net.openconnect_vpn.android.AppListActivity;
import net.openconnect_vpn.android.R;
import net.openconnect_vpn.android.SavedLoginsActivity;
import net.openconnect_vpn.android.api.ExternalAppDatabase;
import net.openconnect_vpn.android.core.DeviceStateReceiver;
import net.openconnect_vpn.android.core.PerAppVpn;
import net.openconnect_vpn.android.core.CredentialStore;
import net.openconnect_vpn.android.core.ThemeManager;
import net.openconnect_vpn.android.core.UpdateCheck;

public class GeneralSettings extends ThemedPreferenceFragment
		implements OnPreferenceClickListener, OnClickListener, OnSharedPreferenceChangeListener,
		UpdateCheck.Listener {

	private ExternalAppDatabase mExtapp;
	private PreferenceManager mPrefs;
	/** True while seeding summaries in onCreate — must not recreate() here. */
	private boolean mBootstrapping;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);


		// Load the preferences from an XML resource
		addPreferencesFromResource(R.xml.general_settings);

		mExtapp = new ExternalAppDatabase(getActivity());

		for (String s : new String[] { "netchangereconnect", "screenoff", "log_enabled", "log_level" }) {
			Preference pref = findPreference(s);
			if (pref == null) {
				continue;
			}
			pref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
				@Override
				public boolean onPreferenceChange(Preference arg0, Object arg1) {
					Intent intent = new Intent(DeviceStateReceiver.PREF_CHANGED);
					intent.setPackage(getActivity().getPackageName());
					getActivity().sendBroadcast(intent, permission.ACCESS_NETWORK_STATE);
					return true;
				}
			});
		}

		Preference selectApps = findPreference(PerAppVpn.PREF_GLOBAL_PACKAGES);
		if (selectApps != null) {
			selectApps.setOnPreferenceClickListener(this);
		}

		Preference checkNow = findPreference("update_check_now");
		if (checkNow != null) {
			checkNow.setOnPreferenceClickListener(this);
		}
		bindVersionPref();
		onUpdateState(UpdateCheck.current(), UpdateCheck.isChecking());

		Preference savedLogins = findPreference("saved_domain_logins");
		if (savedLogins != null) {
			savedLogins.setOnPreferenceClickListener(this);
		}

		mPrefs = getPreferenceManager();
        SharedPreferences sp = mPrefs.getSharedPreferences();
		mBootstrapping = true;
        for (Map.Entry<String,?> entry : sp.getAll().entrySet()) {
            this.onSharedPreferenceChanged(sp, entry.getKey());
        }
		mBootstrapping = false;
		updatePerAppUi(sp);
		updateSavedLoginsUi();
	}

	@Override
	public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
		updatePerAppUi(getPreferenceScreen().getSharedPreferences());
		updateSavedLoginsUi();
		UpdateCheck.addListener(this);
    }

    @Override
    public void onPause() {
		UpdateCheck.removeListener(this);
        super.onPause();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

	public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
    	Preference pref = findPreference(key);
		if (pref instanceof ListPreference) {
			/* update all spinner prefs so the summary shows the current value */
			ListPreference lpref = (ListPreference)pref;
			lpref.setValue(sp.getString(key, ""));
			pref.setSummary(lpref.getEntry());
		}
		if (PerAppVpn.PREF_GLOBAL_MODE.equals(key) || PerAppVpn.PREF_GLOBAL_PACKAGES.equals(key)) {
			updatePerAppUi(sp);
		}
		if (ThemeManager.PREF_THEME.equals(key) && !mBootstrapping) {
			Activity activity = getActivity();
			if (activity != null) {
				ThemeManager.applyThemeChange(activity);
			}
		}
	}

	private void bindVersionPref() {
		Preference pref = findPreference("app_version");
		if (pref == null || getActivity() == null) {
			return;
		}
		String name = UpdateCheck.installedVersionName(getActivity());
		if (name.length() == 0) {
			name = "?";
		}
		pref.setTitle(getString(R.string.app_version_pref, name));
	}

	@Override
	public void onUpdateState(UpdateCheck.Info info, boolean checking) {
		Preference pref = findPreference("update_check_now");
		if (pref == null) {
			return;
		}
		if (checking) {
			pref.setTitle(R.string.update_checking);
			pref.setSummary("");
			pref.setSelectable(false);
			return;
		}
		if (info != null && info.available) {
			pref.setTitle(R.string.update_install);
			if (info.versionName.length() > 0) {
				pref.setSummary(info.versionName);
			} else {
				pref.setSummary("");
			}
			pref.setSelectable(true);
			return;
		}
		if (info != null && info.checked && !info.failed && !info.available) {
			pref.setTitle(R.string.update_up_to_date);
			pref.setSummary("");
			pref.setSelectable(false);
			return;
		}
		if (info != null && info.failed) {
			pref.setTitle(R.string.update_check_failed);
			pref.setSummary("");
			pref.setSelectable(true);
			return;
		}
		pref.setTitle(R.string.update_check_now);
		pref.setSummary("");
		pref.setSelectable(true);
	}

	private void updateSavedLoginsUi() {
		Preference pref = findPreference("saved_domain_logins");
		if (pref == null) {
			return;
		}
		Activity activity = getActivity();
		if (activity == null) {
			return;
		}
		int count = CredentialStore.listRealms(activity).size();
		if (count == 0) {
			pref.setSummary(R.string.saved_logins_summary);
		} else {
			pref.setSummary(getString(R.string.saved_logins_summary_count, count));
		}
	}

	private void updatePerAppUi(SharedPreferences sp) {
		String mode = PerAppVpn.getMode(sp, PerAppVpn.PREF_GLOBAL_MODE, PerAppVpn.MODE_ALL);
		Preference selectApps = findPreference(PerAppVpn.PREF_GLOBAL_PACKAGES);
		if (selectApps != null) {
			boolean needsList = PerAppVpn.MODE_ALLOWLIST.equals(mode)
					|| PerAppVpn.MODE_DENYLIST.equals(mode);
			selectApps.setEnabled(needsList);
			int count = PerAppVpn.getPackages(sp, PerAppVpn.PREF_GLOBAL_PACKAGES).size();
			if (PerAppVpn.MODE_ALLOWLIST.equals(mode)) {
				selectApps.setSummary(getString(R.string.per_app_summary_allowlist, count));
			} else if (PerAppVpn.MODE_DENYLIST.equals(mode)) {
				selectApps.setSummary(getString(R.string.per_app_summary_denylist, count));
			} else {
				selectApps.setSummary(R.string.per_app_select_apps_summary);
			}
		}
	}

	private void setClearApiSummary() {
		Preference clearapi = findPreference("clearapi");

		if(mExtapp.getExtAppList().isEmpty()) {
			clearapi.setEnabled(false);
			clearapi.setSummary(R.string.no_external_app_allowed);
		} else { 
			clearapi.setEnabled(true);
			clearapi.setSummary(getString(R.string.allowed_apps,getExtAppList(", ")));
		}
	}

	private String getExtAppList(String delim) {
		ApplicationInfo app;
		PackageManager pm = getActivity().getPackageManager();

		String applist=null;
		for (String packagename : mExtapp.getExtAppList()) {
			try {
				app = pm.getApplicationInfo(packagename, 0);
				if (applist==null)
					applist = "";
				else
					applist += delim;
				applist+=app.loadLabel(pm);

			} catch (NameNotFoundException e) {
				// App not found. Remove it from the list
				mExtapp.removeApp(packagename);
			}
		}

		return applist;
	}

	@Override
	public boolean onPreferenceClick(Preference preference) { 
		if (PerAppVpn.PREF_GLOBAL_PACKAGES.equals(preference.getKey())) {
			Intent intent = new Intent(getActivity(), AppListActivity.class);
			intent.putExtra(AppListActivity.EXTRA_USE_GLOBAL, true);
			startActivity(intent);
			return true;
		}
		if ("update_check_now".equals(preference.getKey())) {
			UpdateCheck.runNow(getActivity());
			return true;
		}
		if ("saved_domain_logins".equals(preference.getKey())) {
			startActivity(new Intent(getActivity(), SavedLoginsActivity.class));
			return true;
		}
		if(preference.getKey().equals("clearapi")){
			Builder builder = new AlertDialog.Builder(getActivity());
			builder.setPositiveButton(R.string.clear, this);
			builder.setNegativeButton(android.R.string.cancel, null);
			builder.setMessage(getString(R.string.clearappsdialog,getExtAppList("\n")));
			builder.show();
		}
			
		return true;
	}

	@Override
	public void onClick(DialogInterface dialog, int which) {
		if( which == Dialog.BUTTON_POSITIVE){
			mExtapp.clearAllApiApps();
			setClearApiSummary();
		}
	}



}
