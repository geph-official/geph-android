package io.geph.android.utils;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public enum RpcAuthKind {
    PASSWORD() {
        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public String getSecret() {
            throw new UnsupportedOperationException("Cannot get secret for Password rpc authKind");
        }

        @Override
        public boolean isPassword() { return true; }

        @Override
        public boolean isSignature() { return false; }
    },
    SIGNATURE {
        @Override
        public String getUsername() {
            throw new UnsupportedOperationException("Cannot get username for Signature rpc authKind");
        }

        @Override
        public String getPassword() {
            throw new UnsupportedOperationException("Cannot get password for Signature rpc authKind");
        }

        @Override
        public String getSecret() {
            return secret;
        }

        @Override
        public boolean isPassword() { return false; }

        @Override
        public boolean isSignature() { return true; }
    };

    private Context context;

    String username;
    String password;

    String secret;

   public void setContext(Context context) {
       this.context = context;
   }

    public File getSecretPath() {
        File file = new File(context.getCacheDir(), "secret");

        return file;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setSecret(String secret) { this.secret = secret; }

    public List<String> getFlags() {
        switch (this) {
            case PASSWORD:
                return Arrays.asList("auth-password", "--username", this.username, "--password", this.password);
            case SIGNATURE:
                return Arrays.asList("auth-keypair", "--sk-path", getSecretPath().toString());
            default:
                throw new IllegalArgumentException("Invalid RpcAuthKind type");
        }
    }

    public static JSONObject toJSON(RpcAuthKind authKind) throws JSONException {
        JSONObject jsonObject = new JSONObject();
        JSONObject inner = new JSONObject();

        if (authKind == RpcAuthKind.PASSWORD) {
            inner.put("username", authKind.username);
            inner.put("password", authKind.password);
            jsonObject.put("Password", inner);
        } else if (authKind == RpcAuthKind.SIGNATURE) {
            inner.put("secret", authKind.secret);
            jsonObject.put("Signature", inner);
        } else {
            throw new JSONException("Unsupported RpcAuthKind type");
        }

        return jsonObject;
    }

    public static RpcAuthKind fromJSON(JSONObject json) throws JSONException {
        if (json.has("Password")) {
            RpcAuthKind authKind = PASSWORD;
            JSONObject inner = json.getJSONObject("Password");
            authKind.setUsername(inner.getString("username"));
            authKind.setPassword(inner.getString("password"));

            return authKind;
        } else if (json.has("Signature")) {
            RpcAuthKind authKind = SIGNATURE;
            JSONObject inner = json.getJSONObject("Signature");
            authKind.setSecret(inner.getString("sk"));

            return authKind;
        } else {
            throw new JSONException("Unsupported RpcAuthKind type");
        }
    }

    public abstract String getUsername();
    public abstract String getPassword();
    public abstract String getSecret();
    public abstract boolean isPassword();
    public abstract boolean isSignature();
}

