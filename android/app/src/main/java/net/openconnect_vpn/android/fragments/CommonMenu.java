/*
 * Copyright (c) 2014, Kevin Cernekee
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package net.openconnect_vpn.android.fragments;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.PopupMenu;
import net.openconnect_vpn.android.FragActivity;
import net.openconnect_vpn.android.R;

public class CommonMenu {

	public static final int ID_IMPORT = 902;
	public static final int ID_LOG = 903;
	public static final int ID_SETTINGS = 904;
	public static final int ID_CLEAR_LOG = 905;

	private final Activity mActivity;

	public CommonMenu(Activity activity, Menu menu, boolean includeLog) {
		this(activity, menu, false, false, includeLog, false, null, null);
	}

	public CommonMenu(Activity activity, Menu menu, boolean showAdd, boolean showBackup,
			boolean showLog, boolean showClearLog, View.OnClickListener onAdd,
			PopupMenu.OnMenuItemClickListener extra) {
		mActivity = activity;
		if (showAdd && onAdd != null) {
			MenuItem add = menu.add(Menu.NONE, 1, Menu.NONE, R.string.menu_add_profile)
					.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
			View addView = LayoutInflater.from(activity).inflate(R.layout.action_add, null);
			addView.setOnClickListener(onAdd);
			add.setActionView(addView);
		}

		MenuItem menuItem = menu.add(Menu.NONE, 2, Menu.NONE, R.string.header_menu)
				.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
		View menuView = LayoutInflater.from(activity).inflate(R.layout.action_menu, null);
		final boolean backup = showBackup;
		final boolean log = showLog;
		final boolean clearLog = showClearLog;
		final PopupMenu.OnMenuItemClickListener extraListener = extra;
		menuView.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				PopupMenu popup = new PopupMenu(mActivity, v);
				if (backup) {
					popup.getMenu().add(Menu.NONE, ID_IMPORT, Menu.NONE, R.string.menu_import_profiles);
				}
				if (log) {
					popup.getMenu().add(Menu.NONE, ID_LOG, Menu.NONE, R.string.log);
				}
				if (clearLog) {
					popup.getMenu().add(Menu.NONE, ID_CLEAR_LOG, Menu.NONE, R.string.clear_log);
				}
				popup.getMenu().add(Menu.NONE, ID_SETTINGS, Menu.NONE, R.string.generalsettings);
				popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						if (onOptionsItemSelected(item)) {
							return true;
						}
						return extraListener != null && extraListener.onMenuItemClick(item);
					}
				});
				popup.show();
			}
		});
		menuItem.setActionView(menuView);
	}

	private boolean startFragActivity(String fragName) {
		Intent intent = new Intent(mActivity, FragActivity.class);
		intent.putExtra(FragActivity.EXTRA_FRAGMENT_NAME, fragName);
		mActivity.startActivity(intent);
		return true;
	}

	public boolean onOptionsItemSelected(MenuItem item) {
		final int itemId = item.getItemId();
		if (itemId == ID_LOG) {
			return startFragActivity("LogFragment");
		} else if (itemId == ID_SETTINGS) {
			return startFragActivity("GeneralSettings");
		}
		return false;
	}
}
