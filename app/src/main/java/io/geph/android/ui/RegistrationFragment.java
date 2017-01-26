package io.geph.android.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.gson.Gson;

import java.io.IOException;

import io.geph.android.MainActivityInterface;
import io.geph.android.R;
import io.geph.android.api.models.Captcha;
import io.geph.android.api.models.DeriveKeys;
import io.geph.android.api.models.RegisterAccountRequest;
import io.geph.android.proxbinder.Proxbinder;
import io.geph.android.proxbinder.ProxbinderFactory;
import retrofit2.Response;

/**
 * @author j3sawyer
 */
public class RegistrationFragment extends Fragment {
    public static final String CAPTCHA_ID_EXTRA = "captcha_id";
    public static final String CAPTCHA_IMAGE_EXTRA = "captcha_image";
    private static final String TAG = RegistrationFragment.class.getSimpleName();
    private static final String REG_DIALOG_TAG = "reg";
    private String mCaptchaId;
    private String mCaptchaImage;

    private ImageView mCaptcha;
    private EditText mPassword;
    private TextView mUsername;
    private EditText mCaptchaAns;
    private Button mRefreshCaptcha;
    private Button mSubmit;

    private Proxbinder mProxbinder;

    //
    private View.OnClickListener onSubmitClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            String username = mUsername.getText().toString();
            String password = mPassword.getText().toString();
            String captchaAns = mCaptchaAns.getText().toString();

            if (username.isEmpty()) {
                SimpleDialogFragment.create(
                        getString(R.string.missing_field),
                        getString(R.string.username_is_required)
                ).show(getChildFragmentManager(), REG_DIALOG_TAG);
                return;
            }

            if (password.isEmpty()) {
                SimpleDialogFragment.create(
                        getString(R.string.missing_field),
                        getString(R.string.password_is_required)
                ).show(getChildFragmentManager(), REG_DIALOG_TAG);
                return;
            }

            if (captchaAns.isEmpty()) {
                SimpleDialogFragment.create(
                        getString(R.string.missing_field),
                        getString(R.string.captcha_must_be_answered)
                ).show(getChildFragmentManager(), REG_DIALOG_TAG);
                return;
            }

            prepareReg(username, password, captchaAns);
        }
    };
    //
    private View.OnClickListener onRefreshCaptchaClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            refreshCaptcha();
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        mProxbinder.close();
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_registration, container, false);

        mPassword = (EditText) v.findViewById(R.id.reg_password);
        mUsername = (TextView) v.findViewById(R.id.reg_username);
        mCaptchaAns = (EditText) v.findViewById(R.id.reg_captcha_answer);
        mCaptcha = (ImageView) v.findViewById(R.id.captcha_placeholder);
        mSubmit = (Button) v.findViewById(R.id.submit);
        mRefreshCaptcha = (Button) v.findViewById(R.id.refresh_captcha);

        mSubmit.setOnClickListener(onSubmitClickListener);
        mRefreshCaptcha.setOnClickListener(onRefreshCaptchaClickListener);

        mProxbinder = ProxbinderFactory.getProxbinder(getContext());

        Bundle args = getArguments();
        mCaptchaId = args.getString(CAPTCHA_ID_EXTRA);
        mCaptchaImage = args.getString(CAPTCHA_IMAGE_EXTRA);

        invalidateCaptchaImage();

        return v;
    }

    private void refreshCaptcha() {
        new AsyncTask<Void, Void, Captcha>() {
            @Override
            protected Captcha doInBackground(Void... voids) {
                try {
                    return mProxbinder.getService().getFreshCaptcha().execute().body();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }

            @Override
            protected void onPostExecute(Captcha captcha) {
                if (captcha == null) {
                    // handle error appropriately
                } else {
                    mCaptchaId = captcha.getCaptchaId();
                    mCaptchaImage = captcha.getCaptchaImg();
                    mCaptchaAns.setText("");

                    invalidateCaptchaImage();
                }
            }
        }.execute();
    }

    private void invalidateCaptchaImage() {
        byte[] decodedString = Base64.decode(mCaptchaImage, Base64.DEFAULT);
        Bitmap decodedBitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);

        mCaptcha.setImageBitmap(decodedBitmap);

        Log.d(TAG, " current captcha-id is '" + mCaptchaId + "'");
    }

    private void prepareReg(final String username, final String password, final String captchaAns) {
        ((MainActivityInterface) getActivity()).showProgressBar();
        new AsyncTask<Void, Void, DeriveKeys>() {
            @Override
            protected DeriveKeys doInBackground(Void... voids) {
                try {
                    return mProxbinder.getService()
                            .deriveKeys(username, password)
                            .execute()
                            .body();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }

            @Override
            protected void onPostExecute(DeriveKeys deriveKeys) {
                ((MainActivityInterface) getActivity()).dismissProgressBar();
                if (deriveKeys == null) {
                    // handle error
                } else {
                    Log.d(TAG, new Gson().toJson(deriveKeys));

                    doReg(username, deriveKeys.getPubKey(), captchaAns);
                }
            }
        }.execute();
    }

    private void doReg(String username, String pubKey, String captchaAns) {
        final RegisterAccountRequest req =
                new RegisterAccountRequest(username, pubKey, mCaptchaId, captchaAns);
        Log.d(TAG, new Gson().toJson(req));

        final MainActivityInterface mai = ((MainActivityInterface) getActivity());

        mai.showProgressBar();

        new AsyncTask<Void, Void, Response<Object>>() {
            @Override
            protected Response<Object> doInBackground(Void... voids) {

                try {
                    return mProxbinder.getService()
                            .registerAccount(req)
                            .execute();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }

            @Override
            protected void onPostExecute(Response<Object> resp) {
                super.onPostExecute(resp);

                mai.dismissProgressBar();
                switch (resp.code()) {
                    case 200:
                        // simple hack to hide the soft keyboard as the registration process finishes
                        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(mCaptchaAns.getWindowToken(), 0);
                        // finish up the registration process
                        mai.finishRegistration();
                        return;
                    case 400:
                        Log.d(TAG, "Malformed request");
                        SimpleDialogFragment.create(
                                getString(R.string.registration_error),
                                getString(R.string.please_try_again_later) + " (MALFORMED_REQ)"
                        ).show(getChildFragmentManager(), REG_DIALOG_TAG);
                        break;
                    case 403:
                        Log.d(TAG, "Wrong captcha");
                        SimpleDialogFragment.create(
                                getString(R.string.incorrect_captcha),
                                getString(R.string.please_answer_the_captcha_again)
                        ).show(getChildFragmentManager(), REG_DIALOG_TAG);
                        break;
                    case 409:
                        Log.d(TAG, "Username already existing");
                        SimpleDialogFragment.create(
                                getString(R.string.duplicate_username),
                                getString(R.string.please_choose_a_different_username)
                        ).show(getChildFragmentManager(), REG_DIALOG_TAG);
                        break;
                    case 500:
                        Log.e(TAG, "Server internal error");
                        SimpleDialogFragment.create(
                                getString(R.string.registration_error),
                                getString(R.string.please_try_again_later) + " (INTERNAL_ERROR)"
                        ).show(getChildFragmentManager(), REG_DIALOG_TAG);
                        break;
                    default:
                        Log.e(TAG, "Status code " + resp.code());
                        SimpleDialogFragment.create(
                                getString(R.string.registration_error),
                                getString(R.string.unexpected_error_occuured) + " (" + resp.code() + ")"
                        ).show(getChildFragmentManager(), REG_DIALOG_TAG);
                }
                refreshCaptcha();
            }
        }.execute();
    }
}
