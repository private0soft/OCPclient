/*
 * Manage shared domain credentials (realm logins).
 */

package net.openconnect_vpn.android;

import java.util.ArrayList;
import java.util.List;

import net.openconnect_vpn.android.core.CredentialStore;
import net.openconnect_vpn.android.core.ProfileManager;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

public class SavedLoginsActivity extends ThemedActivity {
	private List<CredentialStore.DomainLogin> mItems = new ArrayList<CredentialStore.DomainLogin>();
	private LoginAdapter mAdapter;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setTitle(R.string.saved_logins_title);
		setContentView(R.layout.activity_saved_logins);

		ListView list = (ListView) findViewById(R.id.saved_logins_list);
		mAdapter = new LoginAdapter();
		list.setAdapter(mAdapter);
		list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				if (position < 0 || position >= mItems.size()) {
					return;
				}
				final CredentialStore.DomainLogin login = mItems.get(position);
				new AlertDialog.Builder(SavedLoginsActivity.this)
						.setTitle(R.string.saved_logins_forget)
						.setMessage(getString(R.string.saved_logins_forget_confirm, login.domain))
						.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								CredentialStore.deleteRealm(SavedLoginsActivity.this, login.domain);
								ProfileManager.setSharedForDomain(login.domain, false);
								refreshList();
							}
						})
						.setNegativeButton(R.string.no, null)
						.show();
			}
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		ProfileManager.init(this);
		refreshList();
	}

	private void refreshList() {
		mItems = CredentialStore.listRealms(this);
		mAdapter.notifyDataSetChanged();

		TextView empty = (TextView) findViewById(R.id.saved_logins_empty);
		if (empty != null) {
			empty.setVisibility(mItems.isEmpty() ? View.VISIBLE : View.GONE);
		}
		ListView list = (ListView) findViewById(R.id.saved_logins_list);
		if (list != null) {
			list.setVisibility(mItems.isEmpty() ? View.GONE : View.VISIBLE);
		}
	}

	private final class LoginAdapter extends BaseAdapter {

		@Override
		public int getCount() {
			return mItems.size();
		}

		@Override
		public CredentialStore.DomainLogin getItem(int position) {
			return mItems.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View row = convertView;
			if (row == null) {
				row = LayoutInflater.from(parent.getContext())
						.inflate(R.layout.saved_login_item, parent, false);
			}
			CredentialStore.DomainLogin login = getItem(position);
			TextView title = (TextView) row.findViewById(android.R.id.text1);
			TextView subtitle = (TextView) row.findViewById(android.R.id.text2);
			title.setText(login.domain);
			String user = login.username != null && !login.username.isEmpty()
					? login.username : getString(R.string.shared_login);
			subtitle.setText(user);
			return row;
		}
	}
}
