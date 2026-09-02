package net.openconnect_vpn.android.fragments;

import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.view.View;
import android.widget.ListView;
import net.openconnect_vpn.android.R;

public abstract class ThemedPreferenceFragment extends PreferenceFragment {

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		if (view != null) {
			view.setBackgroundResource(R.drawable.bg_screen_gradient);
		}
		ListView list = (ListView) view.findViewById(android.R.id.list);
		if (list == null) {
			return;
		}
		list.setCacheColorHint(0);
		list.setDivider(new ColorDrawable(0x00000000));
		list.setDividerHeight(0);
		list.setSelector(R.drawable.bg_selectable);
		list.setDrawSelectorOnTop(false);
		int pad = getResources().getDimensionPixelSize(R.dimen.stdpadding);
		list.setPadding(pad / 2, 12, pad / 2, pad);
		list.setClipToPadding(false);
		list.setVerticalScrollBarEnabled(false);
		list.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			list.setNestedScrollingEnabled(true);
		}
	}
}
