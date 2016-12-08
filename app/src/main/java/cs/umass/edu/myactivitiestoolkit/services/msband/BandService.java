package cs.umass.edu.myactivitiestoolkit.services.msband;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.microsoft.band.BandClient;
import com.microsoft.band.BandClientManager;
import com.microsoft.band.BandException;
import com.microsoft.band.BandIOException;
import com.microsoft.band.BandInfo;
import com.microsoft.band.ConnectionState;
import com.microsoft.band.UserConsent;
import com.microsoft.band.sensors.BandAccelerometerEventListener;
import com.microsoft.band.sensors.BandGyroscopeEvent;
import com.microsoft.band.sensors.BandGyroscopeEventListener;
import com.microsoft.band.sensors.BandHeartRateEvent;
import com.microsoft.band.sensors.BandHeartRateEventListener;
import com.microsoft.band.sensors.HeartRateConsentListener;
import com.microsoft.band.sensors.SampleRate;

import cs.umass.edu.myactivitiestoolkit.R;
import cs.umass.edu.myactivitiestoolkit.constants.Constants;
import cs.umass.edu.myactivitiestoolkit.ppg.PPGSensorReading;
import cs.umass.edu.myactivitiestoolkit.processing.Filter;
import cs.umass.edu.myactivitiestoolkit.services.SensorService;
import cs.umass.edu.myactivitiestoolkit.view.activities.MainActivity;
import edu.umass.cs.MHLClient.sensors.AccelerometerReading;
import edu.umass.cs.MHLClient.sensors.GPSReading;
import edu.umass.cs.MHLClient.sensors.GyroscopeReading;

/**
 * The BandService is responsible for starting and stopping the sensors on the
 * Band and receiving accelerometer and gyroscope data periodically. It is a
 * foreground service, so that the user can close the application on the phone
 * and continue to receive data from the wearable device. Because the
 * {@link BandGyroscopeEvent} also receives accelerometer readings, we only need
 * to register a {@link BandGyroscopeEventListener} and no
 * {@link BandAccelerometerEventListener}. This should be compatible with both
 * the Microsoft Band and Microsoft Band 2.
 *
 * @author Sean Noran
 * @see Service#startForeground(int, Notification)
 * @see BandClient
 * @see BandGyroscopeEventListener
 */
public class BandService extends SensorService implements BandGyroscopeEventListener, BandHeartRateEventListener, LocationListener {

    /**
     * used for debugging purposes
     */
    private static final String TAG = BandService.class.getName();
    Filter bufferingFilter = new Filter(3.0);
    static int label = 0;

    /**
     * The object which receives sensor data from the Microsoft Band
     */
    private BandClient bandClient = null;

    /**
     * The minimum duration in milliseconds between sensor readings.
     */
    private static final int MIN_TIME = 5000;

    /**
     * Defines the minimum distance in meters between sequential sensor readings.
     */
    private static final float MIN_DISTANCE = 0f;

    /**
     * Manages the GPS sensor.
     */
    private LocationManager locationManager;
    @Override
    protected void onServiceStarted() {
        broadcastMessage(Constants.MESSAGE.BAND_SERVICE_STARTED);
    }

    @Override
    protected void onServiceStopped() {
        broadcastMessage(Constants.MESSAGE.BAND_SERVICE_STOPPED);
    }

    /**
     * Called when the location has changed.
     * <p>
     * <p> There are no restrictions on the use of the supplied Location object.
     *
     * @param location The new location, as a Location object.
     */
    @Override
    public void onLocationChanged(Location location) {
        mClient.sendSensorReading(new GPSReading(mUserID, "MOBILE", "", location.getTime(), location.getLatitude(), location.getLongitude()));
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    /**
     * Called when the provider is enabled by the user.
     *
     * @param provider the name of the location provider associated with this
     *                 update.
     */
    @Override
    public void onProviderEnabled(String provider) {

    }

    /**
     * Called when the provider is disabled by the user. If requestLocationUpdates
     * is called on an already disabled provider, this method is called
     * immediately.
     *
     * @param provider the name of the location provider associated with this
     *                 update.
     */
    @Override
    public void onProviderDisabled(String provider) {

    }

    /**
     * Asynchronous task for connecting to the Microsoft Band accelerometer and
     * gyroscope sensors. Errors may arise if the Band does not support the Band
     * SDK version or the Microsoft Health application is not installed on the
     * mobile device.
     * *
     *
     * @see com.microsoft.band.BandErrorType#UNSUPPORTED_SDK_VERSION_ERROR
     * @see com.microsoft.band.BandErrorType#SERVICE_ERROR
     * @see BandClient#getSensorManager()
     * @see com.microsoft.band.sensors.BandSensorManager
     */
    private class SensorSubscriptionTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            try {
                if (getConnectedBandClient()) {
                    broadcastStatus(getString(R.string.status_connected));
                    bandClient.getSensorManager().registerGyroscopeEventListener(BandService.this, SampleRate.MS16);
                    bandClient.getSensorManager().registerHeartRateEventListener(BandService.this);
                } else {
                    broadcastStatus(getString(R.string.status_not_connected));
                }
            } catch (BandException e) {
                String exceptionMessage;
                switch (e.getErrorType()) {
                    case UNSUPPORTED_SDK_VERSION_ERROR:
                        exceptionMessage = getString(R.string.err_unsupported_sdk_version);
                        break;
                    case SERVICE_ERROR:
                        exceptionMessage = getString(R.string.err_service);
                        break;
                    default:
                        exceptionMessage = getString(R.string.err_default) + e.getMessage();
                        break;
                }
                Log.e(TAG, exceptionMessage);
                broadcastStatus(exceptionMessage);

            } catch (Exception e) {
                broadcastStatus(getString(R.string.err_default) + e.getMessage());
            }
            return null;
        }
    }


    /**
     * Connects the mobile device to the Microsoft Band
     *
     * @return True if successful, False otherwise
     * @throws InterruptedException if the connection is interrupted
     * @throws BandException        if the band SDK version is not compatible or the Microsoft Health band is not installed
     */
    private boolean getConnectedBandClient() throws InterruptedException, BandException {
        if (bandClient == null) {
            BandInfo[] devices = BandClientManager.getInstance().getPairedBands();
            if (devices.length == 0) {
                broadcastStatus(getString(R.string.status_not_paired));
                return false;
            }
            bandClient = BandClientManager.getInstance().create(getBaseContext(), devices[0]);
        } else if (ConnectionState.CONNECTED == bandClient.getConnectionState()) {
            return true;
        }

        broadcastStatus(getString(R.string.status_connecting));
        return ConnectionState.CONNECTED == bandClient.connect().await();
    }

    @Override
    protected void registerSensors() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        //make sure we have permission to access location before requesting the sensor.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Log.w(TAG, "Starting location manager");
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME,
                MIN_DISTANCE,
                this,
                getMainLooper());
        new SensorSubscriptionTask().execute();
    }

    /**
     * unregisters the sensors from the sensor service
     */
    @Override
    public void unregisterSensors() {
        if (bandClient != null) {
            try {
                bandClient.getSensorManager().unregisterAllListeners();
                disconnectBand();
            } catch (BandIOException e) {
                broadcastStatus(getString(R.string.err_default) + e.getMessage());
            }
        }

        //make sure we have permission to access location before requesting the sensor.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        locationManager.removeUpdates(this);
    }

    @Override
    protected int getNotificationID() {
        return Constants.NOTIFICATION_ID.ACCELEROMETER_SERVICE;
    }

    @Override
    protected String getNotificationContentText() {
        return getString(R.string.activity_service_notification);
    }

    @Override
    protected int getNotificationIconResourceID() {
        return R.drawable.ic_running_white_24dp;
    }

    /**
     * disconnects the sensor service from the Microsoft Band
     */
    public void disconnectBand() {
        if (bandClient != null) {
            try {
                bandClient.disconnect().await();
            } catch (InterruptedException | BandException e) {
                // Do nothing as this is happening during destroy
            }
        }
    }

    @Override
    public void onBandHeartRateChanged(BandHeartRateEvent bandHeartRateEvent) {
//        broadcastStatus(bandHeartRateEvent.getHeartRate()+"");
        HeartBeatReading heartBeatReading = new HeartBeatReading(mUserID, "", "", bandHeartRateEvent.getTimestamp(), bandHeartRateEvent.getHeartRate());
        mClient.sendSensorReading(heartBeatReading);
}

    @Override
    public void onBandGyroscopeChanged(BandGyroscopeEvent event) {
        //TODO: Remove code from starter code
        float[] filterValues = convertToFloatArray(bufferingFilter.getFilteredValues(event.getAccelerationX(), event.getAccelerationY(), event.getAccelerationZ()));
        AccelerometerReading reading  = new AccelerometerReading(mUserID,"Mobile","",event.getTimestamp(),label,filterValues);
        mClient.sendSensorReading(reading);
//        broadcastAccelerometerReading(event.getTimestamp(),
//                event.getAccelerationX(), event.getAccelerationY(), event.getAccelerationZ());
    }

    private float[] convertToFloatArray(double[] doubleArray) {
        float[] floatArray = new float[doubleArray.length];
        for (int i = 0 ; i < doubleArray.length; i++)
        {
            floatArray[i] = (float) doubleArray[i];
        }
        return  floatArray;
    }

    /**
     * Broadcasts the accelerometer reading to other application components, e.g.
     * the main UI.
     *
     * @param accelerometerReadings the x, y, and z accelerometer readings
     */
    public void broadcastAccelerometerReading(final long timestamp, final float... accelerometerReadings) {
        Intent intent = new Intent();
        intent.putExtra(Constants.KEY.TIMESTAMP, timestamp);
        intent.putExtra(Constants.KEY.ACCELEROMETER_DATA, accelerometerReadings);
        intent.setAction(Constants.ACTION.BROADCAST_ACCELEROMETER_DATA);
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.sendBroadcast(intent);
    }

    public static void changeLabel(int newlabel) {
        label = newlabel;
    }
}