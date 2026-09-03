/*
 * Adapted from OpenVPN for Android
 * Copyright (c) 2012-2013, Arne Schwabe
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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ListFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.Html.ImageGetter;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import net.openconnect_vpn.android.ConnectionEditorActivity;
import net.openconnect_vpn.android.R;
import net.openconnect_vpn.android.VpnProfile;
import net.openconnect_vpn.android.api.GrantPermissionsActivity;
import net.openconnect_vpn.android.core.FragCache;
import net.openconnect_vpn.android.core.FlagStore;
import net.openconnect_vpn.android.core.GeoLookup;
import net.openconnect_vpn.android.core.OpenConnectManagementThread;
import net.openconnect_vpn.android.core.OpenVpnService;
import net.openconnect_vpn.android.core.ProfileBackup;
import net.openconnect_vpn.android.core.ProfileManager;
import net.openconnect_vpn.android.core.VPNConnector;

public class VPNProfileList extends ListFragment {

	private static final String TAG = "OpenConnect";
	private static final int REQ_IMPORT = 4102;
	private boolean mIsTv;

	private ArrayAdapter<VpnProfile> mArrayadapter;
	private CommonMenu mDropdown;

	private AlertDialog mDialog;
	private EditText mDialogEntry;
	private EditText mDialogName;
	private EditText mDialogUsername;
	private EditText mDialogPassword;

	private VPNConnector mConn;
	private String mLastListState = "";

	private static class RowHolder {
		TextView title;
		TextView subtitle;
		View dot;
		ImageView flag;
		View settings;
		VpnProfile profile;
	}

	private class VPNArrayAdapter extends ArrayAdapter<VpnProfile> {

		public VPNArrayAdapter(Context context, int resource, int textViewResourceId) {
			super(context, resource, textViewResourceId);
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			RowHolder h;
			if (convertView == null) {
				convertView = LayoutInflater.from(getContext())
						.inflate(R.layout.vpn_list_item, parent, false);
				h = new RowHolder();
				h.title = (TextView) convertView.findViewById(R.id.vpn_item_title);
				h.subtitle = (TextView) convertView.findViewById(R.id.vpn_item_subtitle);
				h.dot = convertView.findViewById(R.id.vpn_item_dot);
				h.flag = (ImageView) convertView.findViewById(R.id.vpn_item_flag_img);
				h.settings = convertView.findViewById(R.id.quickedit_settings);
				convertView.setTag(h);
				convertView.setOnClickListener(new OnClickListener() {
					@Override
					public void onClick(View v) {
						RowHolder row = (RowHolder) v.getTag();
						if (row != null && row.profile != null) {
							startVPN(row.profile);
						}
					}
				});
				convertView.setOnLongClickListener(new OnLongClickListener() {
					@Override
					public boolean onLongClick(View v) {
						RowHolder row = (RowHolder) v.getTag();
						if (row != null && row.profile != null) {
							showProfileMenu(v, row.profile);
						}
						return true;
					}
				});
				h.settings.setOnClickListener(new OnClickListener() {
					@Override
					public void onClick(View v) {
						View rowView = (View) v.getParent();
						if (rowView == null) {
							return;
						}
						RowHolder row = (RowHolder) rowView.getTag();
						if (row != null && row.profile != null) {
							editVPN(row.profile);
						}
					}
				});
			} else {
				h = (RowHolder) convertView.getTag();
			}

			VpnProfile profile = getItem(position);
			h.profile = profile;
			if (h.title != null && profile != null) {
				h.title.setText(profile.getName());
			}
			bindConnectionState(h, profile);
			return convertView;
		}
	}

	private void bindConnectionState(RowHolder row, VpnProfile profile) {
		boolean connected = false;
		if (mConn != null && mConn.service != null
				&& mConn.service.getConnectionState() == OpenConnectManagementThread.STATE_CONNECTED
				&& mConn.service.profile != null
				&& profile != null
				&& profile.getUUIDString().equals(mConn.service.profile.getUUIDString())) {
			connected = true;
		}

		if (row.subtitle != null) {
			String country = GeoLookup.prefString(
					profile != null ? profile.mPrefs : null, GeoLookup.PREF_COUNTRY);
			if (country.length() > 0) {
				row.subtitle.setText(country);
				row.subtitle.setVisibility(View.VISIBLE);
			} else if (connected) {
				row.subtitle.setText(R.string.myoc_connected);
				row.subtitle.setVisibility(View.VISIBLE);
			} else {
				row.subtitle.setText("");
				row.subtitle.setVisibility(View.GONE);
			}
		}

		String iso = GeoLookup.isoOf(profile != null ? profile.mPrefs : null);
		boolean hasFlag = FlagStore.bind(row.flag, iso);
		if (row.dot != null) {
			row.dot.setVisibility(hasFlag ? View.GONE : View.VISIBLE);
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		mIsTv = getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
		if (!mIsTv)
			setHasOptionsMenu(true);
	}

	@Override
	public void onPause() {
		super.onPause();
		if (mDialog != null) {
			FragCache.put("VPNProfileList", "mDialogEntry", mDialogEntry.getText().toString());
			FragCache.put("VPNProfileList", "mDialogName",
					mDialogName != null ? mDialogName.getText().toString() : null);
			FragCache.put("VPNProfileList", "mDialogUsername",
					mDialogUsername != null ? mDialogUsername.getText().toString() : null);
			mDialog.dismiss();
			mDialog = null;
		} else {
			FragCache.put("VPNProfileList", "mDialogEntry", null);
			FragCache.put("VPNProfileList", "mDialogName", null);
			FragCache.put("VPNProfileList", "mDialogUsername", null);
		}
	}

	class MiniImageGetter implements ImageGetter {
		@Override
		public Drawable getDrawable(String source) {
			Drawable d = null;
			if ("ic_menu_add".equals(source))
				d = getActivity().getResources().getDrawable(android.R.drawable.ic_menu_add);
			else if("ic_menu_archive".equals(source))
				d = getActivity().getResources().getDrawable(R.drawable.ic_menu_archive);

			if (d != null) {
				d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
				return d;
			} else {
				return null;
			}
		}
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		final View v = inflater.inflate(R.layout.vpn_profile_list, container,false);

		TextView newvpntext = (TextView) v.findViewById(R.id.add_new_vpn_hint);
		newvpntext.setText(Html.fromHtml(getString(R.string.add_new_vpn_hint),new MiniImageGetter(),null));

		mArrayadapter = new VPNArrayAdapter(getActivity(), R.layout.vpn_list_item, R.id.vpn_item_title);
		setListAdapter(mArrayadapter);

		if (mIsTv) {
			((Button)v.findViewById(R.id.add_new_button)).setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					onAddProfileClicked("", "", "", "");
				}
			});
		}

    	mConn = new VPNConnector(getActivity(), false) {
			@Override
			public void onUpdate(OpenVpnService service) {
				String key = "";
				if (service != null) {
					String uuid = service.profile != null ? service.profile.getUUIDString() : "";
					key = service.getConnectionState() + ":" + uuid;
				}
				if (key.equals(mLastListState)) {
					return;
				}
				mLastListState = key;
				if (mArrayadapter != null) {
					mArrayadapter.notifyDataSetChanged();
				}
			}
    	};

		return v;
	}

    @Override
    public void onDestroyView() {
    	mConn.unbind();
    	super.onDestroyView();
    }

	class VpnProfileNameComperator implements Comparator<VpnProfile> {

		@Override
		public int compare(VpnProfile lhs, VpnProfile rhs) {
			String a = lhs.getName() != null ? lhs.getName() : "";
			String b = rhs.getName() != null ? rhs.getName() : "";
			return a.compareTo(b);
		}

	}

	@Override
	public void onResume() {
		super.onResume();

		// always refresh this on resume, as the list may have changed
		List<VpnProfile> allvpn = new ArrayList<VpnProfile>(ProfileManager.getProfiles());
		Collections.sort(allvpn);

		mArrayadapter.clear();
		mArrayadapter.addAll(allvpn);

		String s = FragCache.get("VPNProfileList", "mDialogEntry");
		if (s != null) {
			onAddProfileClicked(
					FragCache.get("VPNProfileList", "mDialogName"),
					s,
					FragCache.get("VPNProfileList", "mDialogUsername"),
					"");
		}
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		mDropdown = new CommonMenu(getActivity(), menu, true, true, true, false,
				new OnClickListener() {
					@Override
					public void onClick(View v) {
						onAddProfileClicked("", "", "", "");
					}
				},
				new PopupMenu.OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						if (item.getItemId() == CommonMenu.ID_IMPORT) {
							startImportProfiles();
							return true;
						}
						return false;
					}
				});
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (mDropdown != null && mDropdown.onOptionsItemSelected(item)) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void startImportProfiles() {
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.setType("*/*");
		startActivityForResult(intent, REQ_IMPORT);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
			return;
		}
		Uri uri = data.getData();
		if (requestCode == REQ_IMPORT) {
			doImport(uri);
		}
	}

	private void doImport(Uri uri) {
		Activity activity = getActivity();
		if (activity == null) {
			return;
		}
		try {
			InputStream in = activity.getContentResolver().openInputStream(uri);
			if (in == null) {
				throw new IllegalStateException("null input stream");
			}
			String json = ProfileBackup.readUtf8(in);
			ProfileBackup.ImportResult result = ProfileBackup.importAll(activity, json);
			refreshProfileList();
			String msg = getString(R.string.profiles_imported, result.imported);
			if (result.skipped > 0) {
				msg = msg + " " + getString(R.string.profiles_import_skipped, result.skipped);
			}
			Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
		} catch (Exception e) {
			Log.e(TAG, "import failed", e);
			Toast.makeText(activity, R.string.profiles_import_failed, Toast.LENGTH_LONG).show();
		}
	}

	private void refreshProfileList() {
		if (mArrayadapter == null) {
			return;
		}
		List<VpnProfile> allvpn = new ArrayList<VpnProfile>(ProfileManager.getProfiles());
		Collections.sort(allvpn);
		mArrayadapter.clear();
		mArrayadapter.addAll(allvpn);
	}

	private void handleNewVPNEntry() {
		String hostname = mDialogEntry.getText().toString().replaceAll("\\s", "");
		String name = mDialogName != null ? mDialogName.getText().toString().trim() : "";
		String username = mDialogUsername != null ? mDialogUsername.getText().toString().trim() : "";
		String password = mDialogPassword != null ? mDialogPassword.getText().toString() : "";

		mDialog.dismiss();
		mDialog = null;

		if (!hostname.equals("")) {
			editVPN(ProfileManager.create(hostname, name, username, password));
		}
	}

	private void onAddProfileClicked(String savedName, String savedHost,
			String savedUser, String savedPass) {
		final Context context = getActivity();
		if (context != null) {
			View v = View.inflate(context, R.layout.add_new_vpn, null);

			mDialogName = (EditText) v.findViewById(R.id.profile_name_entry);
			mDialogEntry = (EditText) v.findViewById(R.id.entry);
			mDialogUsername = (EditText) v.findViewById(R.id.username_entry);
			mDialogPassword = (EditText) v.findViewById(R.id.password_entry);

			if (savedName != null) {
				mDialogName.setText(savedName);
			}
			if (savedHost != null) {
				mDialogEntry.setText(savedHost);
			}
			if (savedUser != null) {
				mDialogUsername.setText(savedUser);
			}
			if (savedPass != null) {
				mDialogPassword.setText(savedPass);
			}

			AlertDialog.Builder builder = new AlertDialog.Builder(context)
				.setTitle(R.string.menu_add_profile)
				.setMessage(R.string.add_profile_hostname_prompt)
				.setView(v);

			builder.setPositiveButton(android.R.string.ok,
					new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					handleNewVPNEntry();
				}
			});
			builder.setNegativeButton(android.R.string.cancel, null);

			mDialogEntry.setOnEditorActionListener(new TextView.OnEditorActionListener() {
				@Override
				public boolean onEditorAction(TextView textView, int actionId, KeyEvent keyEvent) {
					if (actionId == EditorInfo.IME_ACTION_DONE ||
							(keyEvent != null &&
									keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
									keyEvent.getAction() == KeyEvent.ACTION_DOWN)) {
						if (mDialogEntry.getText().length() != 0) {
							handleNewVPNEntry();
						}
						return true;
					} else {
						return false;
					}
				}
			});

			mDialog = builder.create();

			mDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
				@Override
				public void onDismiss(DialogInterface dialog) {
					mDialog = null;
				}
			});

			mDialog.show();

			final Button okButton = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
			okButton.setEnabled(savedHost != null && !savedHost.equals(""));

			mDialogEntry.addTextChangedListener(new TextWatcher() {
				@Override
				public void afterTextChanged(Editable arg0) {
					okButton.setEnabled(mDialogEntry.getText().length() != 0);
				}

				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				}

				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
				}
			});
		}

	}

	private void editVPN(VpnProfile profile) {
		String pfx = getActivity().getPackageName();
		Intent vprefintent = new Intent(getActivity(), ConnectionEditorActivity.class)
			.putExtra(pfx + ".profileUUID", profile.getUUID().toString())
			.putExtra(pfx + ".profileName", profile.getName());

		startActivity(vprefintent);
	}

	private void showProfileMenu(View anchor, final VpnProfile profile) {
		if (getActivity() == null || profile == null) {
			return;
		}
		PopupMenu popup = new PopupMenu(getActivity(), anchor);
		popup.getMenu().add(0, 1, 0, R.string.duplicate_profile);
		popup.getMenu().add(0, 2, 1, R.string.remove_vpn);
		popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				if (item.getItemId() == 1) {
					duplicateVPN(profile);
					return true;
				}
				if (item.getItemId() == 2) {
					confirmDelete(profile);
					return true;
				}
				return false;
			}
		});
		popup.show();
	}

	private void duplicateVPN(VpnProfile profile) {
		VpnProfile copy = ProfileManager.duplicate(profile);
		if (copy == null) {
			Toast.makeText(getActivity(), R.string.duplicate_failed, Toast.LENGTH_SHORT).show();
			return;
		}
		refreshProfiles();
		editVPN(copy);
	}

	private void confirmDelete(final VpnProfile profile) {
		if (getActivity() == null || profile == null) {
			return;
		}
		new AlertDialog.Builder(getActivity())
				.setTitle(R.string.remove_vpn)
				.setMessage(getString(R.string.remove_vpn_query, profile.getName()))
				.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						ProfileManager.delete(profile.getUUIDString());
						refreshProfiles();
					}
				})
				.setNegativeButton(android.R.string.no, null)
				.show();
	}

	private void refreshProfiles() {
		if (mArrayadapter == null) {
			return;
		}
		List<VpnProfile> allvpn = new ArrayList<VpnProfile>(ProfileManager.getProfiles());
		Collections.sort(allvpn);
		mArrayadapter.clear();
		mArrayadapter.addAll(allvpn);
	}

	private void startVPN(VpnProfile profile) {
		Intent intent = new Intent(getActivity(), GrantPermissionsActivity.class);
		String pkg = getActivity().getPackageName();

		intent.putExtra(pkg + GrantPermissionsActivity.EXTRA_UUID, profile.getUUID().toString());
		intent.setAction(Intent.ACTION_MAIN);
		startActivity(intent);
	}
}
