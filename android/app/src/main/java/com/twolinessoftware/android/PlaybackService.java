/*
 * Copyright (c) 2011 2linessoftware.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twolinessoftware.android;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.IntDef;
import android.util.Log;

import com.twolinessoftware.android.framework.service.comms.gpx.GpxTrackPoint;
import com.twolinessoftware.android.framework.util.Logger;
import com.vividsolutions.jts.geom.Coordinate;

import org.xmlpull.v1.XmlPullParserException;

import io.ticofab.androidgpxparser.parser.GPXParser;
import io.ticofab.androidgpxparser.parser.domain.Gpx;
import io.ticofab.androidgpxparser.parser.domain.WayPoint;

import static java.lang.Thread.sleep;


public class PlaybackService extends Service {

    private NotificationManager mNM;

    private static final String LOG = PlaybackService.class.getSimpleName();

    private static final int NOTIFICATION = 1;

    private ArrayList<GpxTrackPoint> pointList = new ArrayList<GpxTrackPoint>();

    public static final boolean CONTINUOUS = true;
    // Define the list of accepted constants and declare the NavigationMode annotation
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({RUNNING, STOPPED})
    public @interface ServiceRunningState{}
    public static final int RUNNING = 0;
    public static final int STOPPED = 1;

    private static final String PROVIDER_NAME = LocationManager.GPS_PROVIDER;

    private GpxTrackPoint lastPoint;

    private final IPlaybackService.Stub mBinder = new IPlaybackService.Stub() {


        @Override
        public void startService(String file, long delayTIme) throws RemoteException {

            broadcastStateChange(RUNNING);
            loadGpxFile(file);

        }

        @Override
        public void stopService() throws RemoteException {
            mLocationManager.removeTestProvider(PROVIDER_NAME);

            broadcastStateChange(STOPPED);

            cancelExistingTaskIfNecessary();

            onGpsPlaybackStopped();

            stopSelf();
        }

        @Override
        public int getState() throws RemoteException {
            return state;
        }

        @Override
        public boolean setSingleLocation(double latitude, double longitude) throws RemoteException {
            if(state == RUNNING) {
                return false;
            }

            Location loc = new Location(PROVIDER_NAME);
            loc.setAccuracy(50);
            loc.setTime(System.currentTimeMillis());
            loc.setElapsedRealtimeNanos(System.nanoTime());
            loc.setLatitude(latitude);
            loc.setLongitude(longitude);
            loc.setAltitude(20);


            mLocationManager.setTestProviderLocation(PROVIDER_NAME, loc);

            return true;
        }

    };

    private LocationManager mLocationManager;

    private long startTimeOffset;

    private long firstGpsTime;

    @ServiceRunningState
    private int state;

    private ReadFileTask task;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {

        mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        broadcastStateChange(STOPPED);

        setupTestProvider();

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d(LOG, "Starting Playback Service");

        String timeFromIntent = null;
        try {
            timeFromIntent = intent.getStringExtra("delayTimeOnReplay");
        } catch (java.lang.NullPointerException npe) {
            // suppress npe if delay time not available.
        }

        if (timeFromIntent != null && !"".equalsIgnoreCase(timeFromIntent)) {

        }

        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(LOG, "Stopping Playback Service");

    }

    private void cancelExistingTaskIfNecessary() {
        if (task != null) {
            try {
                task.cancel(true);
            } catch (Exception e) {
                Log.e(LOG, "Unable to cancel playback task. May already be stopped");
            }
        }
    }

    private void loadGpxFile(String file) {
        if (file != null) {

            broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadStarted);

            cancelExistingTaskIfNecessary();

            task = new ReadFileTask(file);
            task.execute(null, null);

            // Display a notification about us starting.  We put an icon in the status bar.
            showNotification();
        }

    }


    private void onGpsPlaybackStopped() {

        broadcastStateChange(STOPPED);

        // Cancel the persistent notification.
        mNM.cancel(NOTIFICATION);

        disableGpsProvider();

    }

    private void disableGpsProvider() {

        if (mLocationManager.getProvider(PROVIDER_NAME) != null) {

            mLocationManager.setTestProviderEnabled(PROVIDER_NAME, false);
            mLocationManager.clearTestProviderEnabled(PROVIDER_NAME);
            mLocationManager.clearTestProviderLocation(PROVIDER_NAME);

            mLocationManager.removeTestProvider(PROVIDER_NAME);

        }
    }

    private void setupTestProvider() {
        mLocationManager.addTestProvider(
                PROVIDER_NAME, //mock provider name
                false, //requiresNetwork,
                false, // requiresSatellite,
                false, // requiresCell,
                false, // hasMonetaryCost,
                false, // supportsAltitude,
                false, // supportsSpeed, s
                false, // upportsBearing,
                Criteria.POWER_LOW, // powerRequirement
                Criteria.ACCURACY_FINE); // accuracy

        mLocationManager.setTestProviderEnabled(PROVIDER_NAME, true);
    }


    /**
     * Show a notification while this service is running.
     */
    private void showNotification() {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        CharSequence text = "GPX Playback Running";

        // Set the icon, scrolling text and timestamp
        Notification notification = new Notification(R.drawable.ic_playback_running, text,
                System.currentTimeMillis());

        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);

        // Set the info for the views that show in the notification panel.
        notification.setLatestEventInfo(this, "GPX Playback Manager", text, contentIntent);

        // Send the notification.
        mNM.notify(NOTIFICATION, notification);
    }

    private String loadFile(String file) {

        try {
            File f = new File(file);

            FileInputStream fileIS = new FileInputStream(f);

            BufferedReader buf = new BufferedReader(new InputStreamReader(fileIS));

            String readString = new String();

            StringBuffer xml = new StringBuffer();
            while ((readString = buf.readLine()) != null) {
                xml.append(readString);
            }

            Logger.d(LOG, "Finished reading in file");

            return xml.toString();

        } catch (Exception e) {
            broadcastError("Error in the GPX file, unable to read it");
        }

        return null;
    }

    private double calculateHeadingFromPreviousPoint(GpxTrackPoint currentPoint, GpxTrackPoint lastPoint) {

        double angleBetweenPoints = Math.atan2((lastPoint.getLon() - currentPoint.getLon()), (lastPoint.getLat() - currentPoint.getLat()));
        return Math.toDegrees(angleBetweenPoints);
    }

    private double calculateSpeedFromPreviousPoint(GpxTrackPoint currentPoint, GpxTrackPoint lastPoint) {

        Coordinate startCoordinate = new Coordinate(lastPoint.getLon(), lastPoint.getLat());
        Coordinate endCoordinate = new Coordinate(currentPoint.getLon(), currentPoint.getLat());
        double distance = startCoordinate.distance(endCoordinate) * 100000;
        return distance;

    }

    private void broadcastStatus(GpsPlaybackBroadcastReceiver.Status status) {
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, status.toString());
        sendBroadcast(i);
    }

    private void broadcastError(String message) {
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, GpsPlaybackBroadcastReceiver.Status.fileError.toString());
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATE, state);
        sendBroadcast(i);
    }

    private void broadcastStateChange(@ServiceRunningState int newState) {
        state = newState;
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, GpsPlaybackBroadcastReceiver.Status.statusChange.toString());
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATE, state);
        sendBroadcast(i);
    }

    private class ReadFileTask extends AsyncTask<Void, Integer, Void> {

        private String file;

        public ReadFileTask(String file) {
            super();
            this.file = file;
        }

        @Override
        protected void onPostExecute(Void result) {
            broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadfinished);
        }

        @Override
        protected Void doInBackground(Void... arg0) {

            // Reset the existing values
            firstGpsTime = 0;
            startTimeOffset = 0;


            publishProgress(1);

            Gpx gpx = null;
            try (BufferedInputStream fileInputStream = new BufferedInputStream(new FileInputStream(file)) ) {
                GPXParser gpxParser = new GPXParser(); // consider injection
                gpx = gpxParser.parse(fileInputStream);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (XmlPullParserException e) {
                e.printStackTrace();
            }

            if(gpx != null) {
                List<WayPoint> points = gpx.getWayPoints();
                replayWayPoints(points);
            }

            return null;
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            switch (progress[0]) {
                case 1:
                    broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadfinished);
                    break;
            }

        }

    }

    private void replayWayPoints(List<WayPoint> points) {
        for(WayPoint point : points) {
            updateWayPointToLocationProvider(point);
            try {
                sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        onGpsPlaybackStopped();
    }

    private void updateWayPointToLocationProvider(WayPoint point) {
        Location loc = new Location(PROVIDER_NAME);
        loc.setLatitude(point.getLatitude());
        loc.setLongitude(point.getLongitude());

        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(System.nanoTime());

        loc.setAccuracy(10.0f);
        loc.setAltitude(100.0);



        Log.d("SendLocation", "Sending update for " + PROVIDER_NAME);
        mLocationManager.setTestProviderLocation(PROVIDER_NAME, loc);
    }


}
