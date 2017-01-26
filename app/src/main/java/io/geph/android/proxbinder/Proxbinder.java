package io.geph.android.proxbinder;

import android.util.Log;

import java.util.Scanner;

import io.geph.android.api.GephService;

/**
 * @author j3sawyer
 */
public class Proxbinder {
    private String TAG;
    private int id;
    private Process proc;
    private GephService service;
    private Thread loggingThread;

    public Proxbinder(int _id, Process _proc, GephService _service) {
        id = _id;
        proc = _proc;
        service = _service;
        TAG = "Proxbinder-" + _id;

        loggingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Scanner in = new Scanner(proc.getErrorStream());
                while (in.hasNextLine()) {
                    Log.e(TAG, in.nextLine());
                }
                Log.e(TAG, "logging ended");
            }
        });
        loggingThread.start();
    }

    public GephService getService() {
        return service;
    }

    public void close() {
        proc.destroy();
        proc = null;
        service = null;
        try {
            loggingThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "closing");
    }
}
