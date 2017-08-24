package com.twolinessoftware.android;

import java.util.List;

import android.location.Location;
import android.location.LocationManager;
import android.support.annotation.NonNull;

import io.ticofab.androidgpxparser.parser.domain.Point;

/**
 * Created by 01370737 on 2017/8/24.
 */

public class LocationMockPlayer {
    private static LocationMockPlayer sInstance;

    private final LocationManager mLocationManager;
    private volatile PlayThread mCurrentPlayThread;
    private LocationPlayCallback mLocationPlayCallback;

    public static synchronized LocationMockPlayer singleInstance(LocationManager locationManager) {
        if(sInstance == null) {
            sInstance = new LocationMockPlayer(locationManager);
        }
        return sInstance;
    }


    private LocationMockPlayer(LocationManager locationManager) {
        this.mLocationManager = locationManager;
    }

    public synchronized void replayWaypoints(@NonNull List<? extends Point> points) {
        reset();
        if(points.size() > 0) {
            mCurrentPlayThread = new PlayThread() {
                @Override
                public void play() {
                    int pointsCount = points.size();
                    for(int i = 0; i < pointsCount; i++) {
                        if(isPlayStopped()) {
                            break;
                        }
                        Point point = points.get(i);

                        updateWayPointToLocationProvider(point);
                        long sleepTime = i == pointsCount - 1 ?
                                0 : points.get(i + 1).getTime().getMillis() - point.getTime().getMillis();
                        try {
                            sleep(sleepTime);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            };
            mCurrentPlayThread.start();
        }
    }

    public boolean stopPlay() {
        if(mCurrentPlayThread != null) {
            mCurrentPlayThread.stopPlay();
            mCurrentPlayThread.interrupt();
            return true;
        }
        return false;
    }


    private void notifyCompleteCallBack() {
        if(mLocationPlayCallback != null) {
            mLocationPlayCallback.onComplete();
        }
    }

    private void notifyStartCallBack() {
        if(mLocationPlayCallback != null) {
            mLocationPlayCallback.onStart();
        }
    }

    private void updateWayPointToLocationProvider(Point point) {
        Location loc = new Location(LocationManager.GPS_PROVIDER);
        loc.setLatitude(point.getLatitude());
        loc.setLongitude(point.getLongitude());

        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(System.nanoTime());

        loc.setAccuracy(10.0f);
        loc.setAltitude(100.0);

        try {
            mLocationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }


    private void reset() {
        if(mCurrentPlayThread != null) {
            mCurrentPlayThread.stopPlay();
            mCurrentPlayThread = null;
        }
    }

    public void setPlayCallBack(LocationPlayCallback playCallBack) {
        this.mLocationPlayCallback = playCallBack;
    }

    public boolean isPlaying() {
        return mCurrentPlayThread != null;
    }

    abstract class PlayThread extends Thread {
        private boolean mStopped = false;

        public void stopPlay() {
            mStopped = true;
        }

        public final boolean isPlayStopped() {
            return mStopped;
        }

        @Override
        public void run() {
            notifyStartCallBack();
            play();
            notifyCompleteCallBack();
            mCurrentPlayThread = null;
        }

        public abstract void play();
    }
}
