package io.geph.android.api.models;

import com.google.gson.annotations.SerializedName;

/**
 * @author j3sawyer
 */
public class Summary {
    public static final String STATUS_CONNECTED = "connected";
    public static final String STATUS_DISCONNECTED = "disconnected";

    @SerializedName("Status")
    private String status;

    @SerializedName("ErrCode")
    private String errCode;

    @SerializedName("Uptime")
    private long uptime;

    @SerializedName("BytesTX")
    private long bytesTx;

    @SerializedName("BytesRX")
    private long bytesRx;

    public String getStatus() {
        return status;
    }

    public String getErrCode() {
        return errCode;
    }

    public long getUptime() {
        return uptime;
    }

    public long getBytesTx() {
        return bytesTx;
    }

    public long getBytesRx() {
        return bytesRx;
    }
}
