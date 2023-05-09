package io.geph.android.utils;

import android.content.Context;
import android.os.Environment;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author j3sawyer
 */

public class FileUtils {
    /**
     * Copy an asset file to a local destination.
     *
     * @param assetPath
     * @param localPath
     * @param context
     */
    public static void copyFile(String assetPath, String localPath, Context context) {
        try {
            InputStream in = context.getAssets().open(assetPath);
            FileOutputStream out = new FileOutputStream(localPath);
            int read;
            byte[] buffer = new byte[4096];
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.close();
            in.close();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /* Checks if external storage is available for read and write */
    public static boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    /* Checks if external storage is available to at least read */
    public static boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state) ||
                Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            return true;
        }
        return false;
    }
}
