package io.geph.android.utils;

import java.util.Arrays;
import java.util.List;

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

    @Override
    public String toString() {
        switch (this) {
            case PASSWORD:
                return "PASSWORD";
            case SIGNATURE:
                return "SIGNATURE";
            default:
                throw new IllegalArgumentException("Invalid RpcAuthKind type");
        }
    }
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
                return Arrays.asList(new String[]{"--username", this.username, "--password", this.password});
            case SIGNATURE:
                return List.of();
            default:
                throw new IllegalArgumentException("Invalid RpcAuthKind type");
        }
    }

    public abstract String getUsername();
    public abstract String getPassword();
    public abstract boolean hasCredentials();
}

