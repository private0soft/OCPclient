/*
 * Dialog text preference that does not use android.preference.EditTextPreference.
 * On Android 15+ EditTextPreference.showDialog() calls getWindowInsetsController()
 * on a detached EditText and crashes the activity.
 */

package net.openconnect_vpn.android;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.preference.DialogPreference;
import android.text.InputType;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

public class TextPref extends DialogPreference {

	private String mText = "";
	private EditText mEdit;
	private final int mInputType;

	public TextPref(Context context, AttributeSet attrs) {
		super(context, attrs);
		TypedArray a = context.obtainStyledAttributes(attrs, new int[] { android.R.attr.inputType });
		mInputType = a.getInt(0, InputType.TYPE_CLASS_TEXT);
		a.recycle();
		setDialogLayoutResource(R.layout.pref_dialog_edittext);
		if (getPositiveButtonText() == null) {
			setPositiveButtonText(android.R.string.ok);
		}
		if (getNegativeButtonText() == null) {
			setNegativeButtonText(android.R.string.cancel);
		}
	}

	@Override
	protected void onBindDialogView(View view) {
		super.onBindDialogView(view);
		mEdit = (EditText) view.findViewById(R.id.edit);
		if (mEdit == null) {
			return;
		}
		mEdit.setInputType(mInputType);
		if ((mInputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) {
			mEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
		}
		String text = mText != null ? mText : "";
		mEdit.setText(text);
		mEdit.setSelection(mEdit.getText().length());
		mEdit.setOnEditorActionListener(new TextView.OnEditorActionListener() {
			@Override
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				if (actionId != EditorInfo.IME_ACTION_DONE) {
					return false;
				}
				Dialog dialog = getDialog();
				if (dialog != null) {
					onClick(dialog, AlertDialog.BUTTON_POSITIVE);
					dialog.dismiss();
				}
				return true;
			}
		});
	}

	@Override
	protected void showDialog(Bundle state) {
		super.showDialog(state);
		Dialog dialog = getDialog();
		if (dialog != null && dialog.getWindow() != null) {
			dialog.getWindow().setSoftInputMode(
					WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
		}
		if (mEdit != null) {
			mEdit.requestFocus();
		}
	}

	@Override
	protected void onDialogClosed(boolean positiveResult) {
		super.onDialogClosed(positiveResult);
		if (positiveResult && mEdit != null) {
			String value = mEdit.getText().toString();
			if (callChangeListener(value)) {
				setText(value);
			}
		}
	}

	public void setText(String text) {
		final boolean changed = !TextUtils.equals(mText, text);
		mText = text != null ? text : "";
		persistString(mText);
		if (changed) {
			notifyChanged();
		}
	}

	public String getText() {
		return mText;
	}

	@Override
	protected Object onGetDefaultValue(TypedArray a, int index) {
		return a.getString(index);
	}

	@Override
	protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
		String fallback = defaultValue != null ? (String) defaultValue : "";
		setText(restoreValue ? getPersistedString(mText) : fallback);
	}
}
