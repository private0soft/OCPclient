/*
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package net.openconnect_vpn.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.openconnect_vpn.android.core.PerAppVpn;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Checklist of installed apps used for global or per-profile VPN allow/deny lists.
 */
public class AppListActivity extends ThemedActivity {

	public static final String EXTRA_USE_GLOBAL = "use_global";
	public static final String EXTRA_PROFILE_UUID = "profile_uuid";

	private SharedPreferences mPrefs;
	private String mPackagesKey;
	private boolean mShowSystem;
	private String mQuery = "";
	private final Set<String> mSelected = new HashSet<String>();
	private final List<AppEntry> mApps = new ArrayList<AppEntry>();
	private AppAdapter mAdapter;

	private static class AppEntry {
		final String packageName;
		final String label;
		final Drawable icon;
		final boolean system;

		AppEntry(String packageName, String label, Drawable icon, boolean system) {
			this.packageName = packageName;
			this.label = label;
			this.icon = icon;
			this.system = system;
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.app_list);
		setTitle(R.string.per_app_select_apps);

		boolean useGlobal = getIntent().getBooleanExtra(EXTRA_USE_GLOBAL, true);
		if (useGlobal) {
			mPrefs = PerAppVpn.globalPrefs(this);
			mPackagesKey = PerAppVpn.PREF_GLOBAL_PACKAGES;
		} else {
			String uuid = getIntent().getStringExtra(EXTRA_PROFILE_UUID);
			if (uuid == null || uuid.isEmpty()) {
				Toast.makeText(this, R.string.securid_internal_error, Toast.LENGTH_LONG).show();
				finish();
				return;
			}
			mPrefs = PerAppVpn.profilePrefs(this, uuid);
			mPackagesKey = PerAppVpn.PREF_PACKAGES;
		}

		mSelected.addAll(PerAppVpn.getPackages(mPrefs, mPackagesKey));

		EditText search = (EditText) findViewById(R.id.app_list_search);
		CheckBox systemCheck = (CheckBox) findViewById(R.id.app_list_system);
		ListView listView = (ListView) findViewById(R.id.app_list);
		Button save = (Button) findViewById(R.id.app_list_save);

		mAdapter = new AppAdapter();
		listView.setAdapter(mAdapter);
		search.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				mQuery = s != null ? s.toString().trim() : "";
				mAdapter.notifyDataSetChanged();
			}
		});
		listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				AppEntry entry = mAdapter.getVisible(position);
				if (mSelected.contains(entry.packageName)) {
					mSelected.remove(entry.packageName);
				} else {
					mSelected.add(entry.packageName);
				}
				mAdapter.notifyDataSetChanged();
			}
		});

		systemCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				mShowSystem = isChecked;
				mAdapter.notifyDataSetChanged();
			}
		});

		save.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				PerAppVpn.setPackages(mPrefs, mPackagesKey, mSelected);
				Toast.makeText(AppListActivity.this, R.string.per_app_saved, Toast.LENGTH_SHORT).show();
				setResult(RESULT_OK);
				finish();
			}
		});

		new LoadAppsTask().execute();
	}

	private class LoadAppsTask extends AsyncTask<Void, Void, List<AppEntry>> {
		@Override
		protected List<AppEntry> doInBackground(Void... voids) {
			PackageManager pm = getPackageManager();
			List<ApplicationInfo> installed = pm.getInstalledApplications(0);
			List<AppEntry> result = new ArrayList<AppEntry>();
			String self = getPackageName();

			for (ApplicationInfo info : installed) {
				if (self.equals(info.packageName)) {
					continue;
				}
				boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
				CharSequence labelCs = info.loadLabel(pm);
				String label = labelCs != null ? labelCs.toString() : info.packageName;
				Drawable icon;
				try {
					icon = info.loadIcon(pm);
				} catch (Exception e) {
					icon = pm.getDefaultActivityIcon();
				}
				result.add(new AppEntry(info.packageName, label, icon, system));
			}

			Collections.sort(result, new Comparator<AppEntry>() {
				@Override
				public int compare(AppEntry a, AppEntry b) {
					return a.label.toLowerCase(Locale.getDefault())
							.compareTo(b.label.toLowerCase(Locale.getDefault()));
				}
			});
			return result;
		}

		@Override
		protected void onPostExecute(List<AppEntry> result) {
			mApps.clear();
			mApps.addAll(result);
			mAdapter.notifyDataSetChanged();
		}
	}

	private class AppAdapter extends BaseAdapter {
		private final LayoutInflater mInflater = LayoutInflater.from(AppListActivity.this);
		private final List<AppEntry> mVisible = new ArrayList<AppEntry>();

		@Override
		public void notifyDataSetChanged() {
			rebuildVisible();
			super.notifyDataSetChanged();
		}

		private void rebuildVisible() {
			mVisible.clear();
			String query = mQuery.toLowerCase(Locale.getDefault());
			for (AppEntry e : mApps) {
				if (!mShowSystem && e.system && !mSelected.contains(e.packageName)) {
					continue;
				}
				if (!query.isEmpty() && !matches(e, query)) {
					continue;
				}
				mVisible.add(e);
			}
		}

		private boolean matches(AppEntry e, String query) {
			return e.label.toLowerCase(Locale.getDefault()).contains(query)
					|| e.packageName.toLowerCase(Locale.getDefault()).contains(query);
		}

		AppEntry getVisible(int position) {
			return mVisible.get(position);
		}

		@Override
		public int getCount() {
			return mVisible.size();
		}

		@Override
		public Object getItem(int position) {
			return getVisible(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View view = convertView;
			if (view == null) {
				view = mInflater.inflate(R.layout.app_list_item, parent, false);
			}
			AppEntry entry = getVisible(position);
			((ImageView) view.findViewById(R.id.app_icon)).setImageDrawable(entry.icon);
			((TextView) view.findViewById(R.id.app_label)).setText(entry.label);
			((TextView) view.findViewById(R.id.app_package)).setText(entry.packageName);
			((CheckBox) view.findViewById(R.id.app_selected))
					.setChecked(mSelected.contains(entry.packageName));
			return view;
		}
	}
}
