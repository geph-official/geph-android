package io.geph.android.ui;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import androidx.fragment.app.DialogFragment;
import androidx.appcompat.app.AlertDialog;

import io.geph.android.R;


/**
 * @author j3sawyer
 */
public class SimpleDialogFragment extends DialogFragment {

    public static final String TITLE = "title";
    public static final String MESSAGE = "message";

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

        Bundle bundle = getArguments();

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setMessage(bundle.getString(MESSAGE))
                .setTitle(bundle.getString(TITLE))
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        // do nothing
                    }
                });
        return builder.create();
    }

    public static SimpleDialogFragment create(String title, String message) {
        SimpleDialogFragment d = new SimpleDialogFragment();
        Bundle arg = new Bundle();
        arg.putString(TITLE, title);
        arg.putString(MESSAGE, message);
        d.setArguments(arg);
        return d;
    }
}
