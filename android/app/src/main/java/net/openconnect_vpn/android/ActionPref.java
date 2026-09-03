package net.openconnect_vpn.android;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

/** Full-width action row. Clicks go through the preference, like other settings. */
public class ActionPref extends Preference {

	public ActionPref(Context context, AttributeSet attrs) {
		super(context, attrs);
		setLayoutResource(R.layout.pref_update_action);
		setPersistent(false);
		setSelectable(true);
		setEnabled(true);
	}

	public ActionPref(Context context) {
		this(context, null);
	}
}
