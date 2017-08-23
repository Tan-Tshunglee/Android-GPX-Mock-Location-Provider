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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.regex.Pattern;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.databinding.DataBindingUtil;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.ipaulpro.afilechooser.utils.FileUtils;
import com.twolinessoftware.android.databinding.MainBinding;
import com.twolinessoftware.android.framework.util.Logger;
import com.twolinessoftware.android.model.Location;

public class MainActivity extends Activity implements GpsPlaybackListener {

	private static final int REQUEST_FILE = 1;

	private static final String LOGNAME = "MainActivity";
	private static final String APP_DATA_CACHE_FILENAME = "gpx_app_data_cache";
	private static final String DEFAULT_PATH_TO_GPX_FILE = "/";

	private ServiceConnection connection;
	private IPlaybackService service;
	private EditText mEditText;

	private EditText mEditTextDelay;
	private String filepath;
	private String delayTimeOnReplay = "";

	private GpsPlaybackBroadcastReceiver receiver;

	private int state;

	private MainBinding mDataBinding;
	private ProgressDialog progressDialog;
	private TextView mLabelEditText;
	private LocationManager mLocationManager;
	private LocationListener mLocationListener = new LocationListener() {
		@Override
		public void onLocationChanged(android.location.Location location) {

			String timeText = (new Date()).toLocaleString();
			mGeoResult.setText(
					String.format(
							"Lat:%f Lng:%f at %s",
							location.getLatitude(),
							location.getLongitude(),
							timeText
					)
			);
		}

		@Override
		public void onStatusChanged(String provider, int status, Bundle extras) {

		}

		@Override
		public void onProviderEnabled(String provider) {

		}

		@Override
		public void onProviderDisabled(String provider) {

		}
	};
	private TextView mGeoResult;

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		String pattern = Pattern.quote("!@#$%^&*(.\\Q\\E.../");

		Logger.i(LOGNAME, pattern);
//		setContentView(R.layout.main);
		mDataBinding = DataBindingUtil.setContentView(this, R.layout.main);
		mDataBinding.setLocation(new Location());
		// test that mock locations are allowed so a more descriptive error
		// message can be logged
		if (Settings.Secure.getInt(getContentResolver(),
				Settings.Secure.ALLOW_MOCK_LOCATION, 0) == 0) {
			Toast.makeText(this, "MockLocations needs to be enabled",
					Toast.LENGTH_SHORT).show();
			finish();
		}

		mEditText = (EditText) findViewById(R.id.file_path);
		mLabelEditText = (TextView) findViewById(R.id.label_edit_text_delay);
		mEditTextDelay = (EditText) findViewById(R.id.editTextDelay);

		mEditTextDelay.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {

			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {

			}

			@Override
			public void afterTextChanged(Editable s) {
				delayTimeOnReplay = mEditTextDelay.getText().toString();
			}
		});

		mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
		mGeoResult = (TextView) findViewById(R.id.geoResult);

	}

	@Override
	protected void onStart() {
		bindStatusListener();
		connectToService();
		registerLocationProvider();
		super.onStart();
	}


	@Override
	protected void onStop() {
		if (receiver != null)
			unregisterReceiver(receiver);

		try {
			unbindService(connection);
		} catch (Exception ie) {
		}
		unregisterLocationProvider();
		super.onStop();

	}

	private void unregisterLocationProvider() {
		mLocationManager.removeUpdates(mLocationListener);
	}

	private void registerLocationProvider() {
		mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, mLocationListener, getMainLooper());
	}


	private void hideProgressDialog() {
		if (progressDialog != null)
			progressDialog.cancel();
	}

	private void showProgressDialog() {
		// Display progress dialog

		progressDialog = ProgressDialog.show(this,
				getString(R.string.please_wait),
				getString(R.string.loading_file), true);
		progressDialog.setCancelable(true);
		progressDialog.setOnCancelListener(new OnCancelListener() {
			@Override
			public void onCancel(DialogInterface dialog) {
				dialog.dismiss();
			}
		});
	}

	private void bindStatusListener() {
		receiver = new GpsPlaybackBroadcastReceiver(this);
		IntentFilter filter = new IntentFilter();
		filter.addAction(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
		registerReceiver(receiver, filter);
	}

	private void connectToService() {
		Intent i = new Intent(getApplicationContext(), PlaybackService.class);
		connection = new PlaybackServiceConnection();
		bindService(i, connection, Context.BIND_AUTO_CREATE);
	}

	public void onClickOpenFile(View view) {
		pickFile();
	}

	public void onClickStart(View view) {
		startPlaybackService();
	}

	public void onClickStop(View view) {
		stopPlaybackService();
	}

	public void onClickSingle(View view) {
		setSingleLocation();
	}

	private void setSingleLocation() {
		Location location = mDataBinding.getLocation();
		String latitude = location.latitude.get();
		String longitude = location.longitude.get();
		if(!isValidateData(latitude, longitude)) {
			Toast.makeText(this, "Input Validate Data!", Toast.LENGTH_SHORT).show();
			return;
		}

		if(service != null) {
			try {
				service.setSingleLocation(Double.valueOf(latitude), Double.valueOf(longitude));
			} catch (RemoteException e) {
				Toast.makeText(this, R.string.service_not_connected, Toast.LENGTH_SHORT).show();
			}
		} else {
			Toast.makeText(this, R.string.service_not_connected, Toast.LENGTH_SHORT).show();
		}

	}

	private boolean isValidateData(String latitude, String longitude) {
		if(TextUtils.isEmpty(latitude) || TextUtils.isEmpty(longitude)) {
			return false;
		}

		double latDouble = 0;
		double lngDouble = 0;
		try {
			latDouble = Double.valueOf(latitude);
			lngDouble = Double.valueOf(longitude);
		} catch (NumberFormatException e) {
			e.printStackTrace();
			return false;
		}

		if(Math.abs(latDouble) > 90 || Math.abs(lngDouble) > 180) {
			return false;
		}
		return true;
	}

	/**
	 * Opens the file manager to select a file to open.
	 */
	public void openFile() {

		String fileName = getGpxFilePath();

		Intent intent = new Intent(FileManagerIntents.ACTION_PICK_FILE);

		// Construct URI from file name.
		File file = new File(fileName);
		intent.setData(Uri.fromFile(file));

		// Set fancy title and button (optional)
		intent.putExtra(FileManagerIntents.EXTRA_TITLE,
				getString(R.string.open_title));
		intent.putExtra(FileManagerIntents.EXTRA_BUTTON_TEXT,
				getString(R.string.open_button));

		try {
			startActivityForResult(intent, REQUEST_FILE);

		} catch (ActivityNotFoundException e) {
			// No compatible file manager was found.
			Toast.makeText(this, R.string.no_filemanager_installed,
					Toast.LENGTH_SHORT).show();
		}
	}

	public void pickFile() {

		Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
		intent.setType("*/*");
		startActivityForResult(intent, REQUEST_FILE);
	}

    /*
     * "Start" button clicked
     */
    public void startPlaybackService() {

        if (TextUtils.isEmpty(filepath)) {
            Toast.makeText(this, "No File Loaded", Toast.LENGTH_SHORT).show();
            return;
        }


		if (TextUtils.isEmpty(delayTimeOnReplay)) {
			delayTimeOnReplay = "0";
			mEditTextDelay.setText(delayTimeOnReplay);
		}

        try {
            long delayTime = Long.valueOf(delayTimeOnReplay);

            if (service != null) {
                service.startService(filepath, delayTime);
            }

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Delay time number invalid.", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
            return;
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

	public void stopPlaybackService() {

		try {
			if (service != null) {
				saveGpxFilePath(filepath);
				mEditText.setText(filepath);

				service.stopService();
			}


		} catch (RemoteException e) {
		}
	}

	private void updateUi() {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Button start = (Button) findViewById(R.id.start);
				Button stop = (Button) findViewById(R.id.stop);

				switch (state) {
					case PlaybackService.RUNNING:
						start.setEnabled(false);
						stop.setEnabled(true);
						break;
					case PlaybackService.STOPPED:
						start.setEnabled(true);
						stop.setEnabled(false);
						break;
				}

			}

		});

	}

	class PlaybackServiceConnection implements ServiceConnection {

		public void onServiceConnected(ComponentName name, IBinder boundService) {
			service = IPlaybackService.Stub.asInterface(boundService);
			try {
				state = service.getState();
			} catch (RemoteException e) {
				Logger.e(LOGNAME, "Unable to access state:" + e.getMessage());
			}
			updateUi();
		}

		public void onServiceDisconnected(ComponentName name) {
			service = null;
		}

	}

	/**
	 * This is called after the file manager finished.
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		switch (requestCode) {
		case REQUEST_FILE:
			if (resultCode == RESULT_OK && data != null) {
				// obtain the filename
				Uri fileUri = data.getData();

				String filePath = FileUtils.getPath(this, fileUri);
				if (filePath != null) {
					mEditText.setText(filePath);
					this.filepath = filePath;
				}
			}
			break;
		}
	}

	@Override
	public void onFileLoadStarted() {
		Logger.d(LOGNAME, "File loading started");
	}

	@Override
	public void onFileLoadFinished() {
		Logger.d(LOGNAME, "File loading finished");
	}

	@Override
	public void onStatusChange(int newStatus) {
		state = newStatus;
		updateUi();
	}

	@Override
	public void onFileError(String message) {
		hideProgressDialog();
	}

	/**
	 * Saves filepath to private application data saved on disk.
	 * @param filepath
	 */
	public void saveGpxFilePath(String filepath) {

		try {
			FileOutputStream fos = openFileOutput(APP_DATA_CACHE_FILENAME, Context.MODE_PRIVATE);
			if (filepath != null) {
				fos.write(filepath.getBytes());
			} else {
				fos.write(DEFAULT_PATH_TO_GPX_FILE.getBytes());
			}
			fos.close();

		} catch (java.lang.Exception e) {
			Logger.d(LOGNAME, "saveGpxFilePath exception: " + e.getMessage());
		}
	}

	/**
	 * Method gets the path to last GPX file loaded based on value saved in private application
	 * data saved on disk. Defaults to "/".
	 *
	 * @return path to gpx file
	 */
	public String getGpxFilePath() {

		String filepath = DEFAULT_PATH_TO_GPX_FILE;

		try {
			FileInputStream fis = openFileInput(APP_DATA_CACHE_FILENAME);
			filepath = convertStreamToString(fis);
			if (filepath == null || filepath.equalsIgnoreCase("")) {
				filepath = DEFAULT_PATH_TO_GPX_FILE;
			}
			fis.close();

		} catch (java.lang.Exception e) {
			Logger.d(LOGNAME, "getGpxFilePath - no cache file detected - default path being used e.g /");
		}
		return filepath;
	}

	/**
	 * Method reads input stream to string.
	 *
	 * @param is
	 * @return file contents
	 * @throws Exception
	 */
	public static String convertStreamToString(InputStream is) throws Exception {
		BufferedReader reader = new BufferedReader(new InputStreamReader(is));
		StringBuilder sb = new StringBuilder();
		String line = null;
		while ((line = reader.readLine()) != null) {
			sb.append(line).append("\n");
		}
		reader.close();
		return sb.toString();
	}

}