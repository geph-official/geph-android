package io.geph.android.ui;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;

import io.geph.android.MainActivityInterface;
import io.geph.android.R;

/**
 * @author j3sawyer
 */

public class LoginFragment extends Fragment {

    private static final String DIALOG_TAG = "login";

    private AutoCompleteTextView mUsername;
    private EditText mPassword;
    private Button mSignIn;

    public LoginFragment() {
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View frag = inflater.inflate(R.layout.fragment_login, container, false);

        mUsername = (AutoCompleteTextView) frag.findViewById(R.id.sign_in_username);
        mPassword = (EditText) frag.findViewById(R.id.sign_in_password);
        mSignIn = (Button) frag.findViewById(R.id.sign_in_button);

        mSignIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String username = mUsername.getText().toString();
                String password = mPassword.getText().toString();

                if (username.isEmpty()) {
                    SimpleDialogFragment.create(
                            getString(R.string.missing_field),
                            getString(R.string.username_is_required)
                    ).show(getChildFragmentManager(), DIALOG_TAG);
                    return;
                }
                if (password.isEmpty()) {
                    SimpleDialogFragment.create(
                            getString(R.string.missing_field),
                            getString(R.string.password_is_required)
                    ).show(getChildFragmentManager(), DIALOG_TAG);
                    return;
                }

                InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(mPassword.getWindowToken(), 0);

                ((MainActivityInterface) getActivity()).signIn(username, password);
            }
        });

        return frag;
    }
}
