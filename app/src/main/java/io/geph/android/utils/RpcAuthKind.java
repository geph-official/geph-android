package io.geph.android.utils;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public enum RpcAuthKind {
    PASSWORD {
        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public boolean hasCredentials() {
            return true;
        }
    },
    SIGNATURE {
        @Override
        public String getUsername() {
            throw new UnsupportedOperationException("Not supported for this RpcAuthKind.");
        }

        @Override
        public String getPassword() {
            throw new UnsupportedOperationException("Not supported for this RpcAuthKind.");
        }

        @Override
        public boolean hasCredentials() {
            return false;
        }
    };

    String username;
    String password;

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public List<String> getFlags() {
        switch (this) {
            case PASSWORD:
                return Arrays.asList("auth-password", "--username", this.username, "--password", this.password);
            case SIGNATURE:
                // TODO: fix the flags here
                return Arrays.asList("auth-signature");
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
        }
        // TODO: handle SIGNATURE type later
        return null;
    }

    public abstract String getUsername();
    public abstract String getPassword();
    public abstract boolean hasCredentials();
}

