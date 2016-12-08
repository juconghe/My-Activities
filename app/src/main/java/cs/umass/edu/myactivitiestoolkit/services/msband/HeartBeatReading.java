package cs.umass.edu.myactivitiestoolkit.services.msband;

import org.json.JSONException;
import org.json.JSONObject;

import edu.umass.cs.MHLClient.sensors.SensorReading;

/**
 * Created by Jucong on 12/7/16.
 */

public class HeartBeatReading extends SensorReading {

    private int heartBeat;
    public HeartBeatReading(String userID, String deviceType, String deviceID, long t, int value) {
        super(userID, deviceType, deviceID, "SENSOR_HEARTBEAT", t);
        heartBeat = value;
    }

    /**
     * Defines how the data is converted to a JSON object.
     *
     * @return a JSON object encoding the sensor reading.
     */
    @Override
    protected JSONObject toJSONObject() {
        JSONObject obj = getBaseJSONObject();
        JSONObject data = new JSONObject();
        try {
            data.put("t", timestamp);
            data.put("heartbeat",heartBeat);
            obj.put("data", data);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return obj;
    }
}
