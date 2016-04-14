import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;

import org.json.JSONArray;
import org.json.JSONObject;

import com.codeminders.ardrone.ARDrone;
import com.codeminders.ardrone.NavData;
import com.codeminders.ardrone.NavDataListener;

/* WARNING: Good old EMOTIV never felt like adding cognitive
 * training and detection support to their OSX libraries so 
 * that WILL NOT work here. Trying to control the drone with
 * mental commands for this setup WILL NOT work unless 
 * (theoretically) this is run from Windows or Linux. Windows
 * throws a fit for some reason claiming it can't find the main
 * method even when it's in Main.java right in the damn src 
 * folder. Windows is for paste-eaters anyway.
 * 
 * Try running the ugly Main.java from the original 
 * MindControlledUAV concurrently with this and EmoKey to
 * still be able to actually call this a mind-controlled
 * drone demo. This will take care of everything that doesn't
 * deal with controlling it via cognitive commands.
 */
public class Main implements NavDataListener {
	static ARDrone drone;
	double phi, theta, gaz, psi;
	FloatBuffer fb;
	IntBuffer ib;

	public static void main(String args[]) {
		Main self = new Main();
		self.run();
	}

	public void run() {
		//run API_Main for the EPOC server socket
		Thread t = new Thread(new API_Main());
		t.start();
		ByteBuffer bb = ByteBuffer.allocate(4);
		fb = bb.asFloatBuffer();
		ib = bb.asIntBuffer();
		String JSONResponse;
		String line = "";
		HashMap<String, String[]> configMap = new HashMap<String, String[]>();

		// read in the config file for controlling the drone
		BufferedReader br = new BufferedReader(
				new InputStreamReader(
						API_Main.class.getResourceAsStream("config.txt")));
		try {
			while ((line = br.readLine()) != null) {
				String parts[] = line.split("\t");
				configMap.put(parts[0], new String[]{parts[1], parts[2]});
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		try {
			// connect to the Emotiv-JSON API sockets
			Socket clientSocket = new Socket("localhost", 4444);
			Socket clientTrainingSocket = new Socket("localhost", 4445);
			DataOutputStream outToServer = new DataOutputStream(clientSocket.getOutputStream());
			BufferedReader inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
			DataOutputStream trainingOutToServer = new DataOutputStream(clientTrainingSocket.getOutputStream());
			BufferedReader trainingInFromServer = new BufferedReader(new InputStreamReader(clientTrainingSocket.getInputStream()));
			outToServer.writeBytes("expressive, gyros" + '\n');
			new Thread(() -> handleTraining(trainingInFromServer, trainingOutToServer)).start();
			br = new BufferedReader(new InputStreamReader(System.in));
		
			// set up the drone
			drone = new ARDrone();
			drone.connect();
			drone.clearEmergencySignal();
			drone.trim();
			//drone.setCombinedYawMode(true);
			drone.setConfigOption(ARDrone.ConfigOption.ALTITUDE_MAX, "3000"); // set height limit to 3 meters
			drone.addNavDataListener(this);

			// start reading from the headset and controlling the drone
			while ((JSONResponse = inFromServer.readLine()) != null) {
				// make drone land on hitting enter and quit
				// with a non-blocking while loop
				while (br.ready()) { 
					System.out.println("force land");
					drone.land();
					//close all resources
					clientSocket.close();
					clientTrainingSocket.close();
					inFromServer.close();
					outToServer.close();
					drone.disconnect();
					System.exit(0);
				}
				JSONObject obj = new JSONObject(JSONResponse);
				System.out.println(obj); //debug
				boolean useXGyro = configMap.containsKey("GyroX");
				boolean useYGyro = configMap.containsKey("GyroY");
				if (useXGyro || useYGyro) {
					JSONObject gyros = obj.getJSONObject("EmoStateData").getJSONObject("Gyros");
					if (useXGyro) {
						float Xgyro_val = (float) gyros.getDouble("GyroX");
						//System.out.println(Xgyro_val);
						if (Xgyro_val != 0) control(configMap.get("GyroX")[0], Xgyro_val);
					}
					if (useYGyro) {
						float Ygyro_val = (float) gyros.getDouble("GyroY");
						//System.out.println(Ygyro_val);
						if (Ygyro_val != 0) control(configMap.get("GyroY")[0], Ygyro_val);
					}
				}
				for (String token : configMap.keySet()) {
					//for expressiv and affectiv events, which are contained in JSONArrays
					if (API_Main.getAffectivMap().containsKey(token) || API_Main.getExpressivMap().containsKey(token)){
						JSONArray array = (API_Main.getAffectivMap().containsKey(token)) ? 
								obj.getJSONObject("EmoStateData").getJSONArray("Affective") : 
									obj.getJSONObject("EmoStateData").getJSONArray("Expressive");
								for (int i = 0; i < array.length(); i++) {
									if (array.optJSONObject(i).has(token)) {
										float param_val = (float) array.getJSONObject(i).getDouble(token);
										if (param_val > Double.parseDouble(configMap.get(token)[1])) {
											control(configMap.get(token)[0], param_val);
										}
									}
								}
					}
					//for cognitiv events, which are contained in a JSONObject
					else if (API_Main.getCogntivMap().containsKey(token)) {
						String cog_action = obj.getJSONObject("EmoStateData").getString("Cognitive");
						if (cog_action.equals(token)) {
							float param_val = (float) obj.getJSONObject("EmoStateData").getDouble("Cognitive");
							if (param_val > Double.parseDouble(configMap.get(token)[1])) {
								control(configMap.get(token)[0], param_val);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} 
	}
	
	public int intOfFloat(float f) {
		fb.put(0, f);
		return ib.get(0);
	}

	public void control(String command, float val) throws Exception {
		System.out.println(command + " " + val);
		float cal_speed = 0.0f;
		switch (command) {

		// critical commands
		case "takeoff":
			System.out.println("Taking off...");
			drone.takeOff();
			break;
		case "land":
			System.out.println("Landing...");
			drone.land();
			break;

			// basic movement (6 axes)
			// move(float left_right_tilt, float front_back_tilt, float vertical_speed, float angular_speed)
		case "forward":
			drone.move(0, val, 0, 0); // negative val to go forward
			break;
		case "backward":
			drone.move(0, val, 0, 0); // positive val to go backward
			break;
		case "left":
			drone.move(val, 0, 0, 0); // negative val to go left
			break;
		case "right":
			drone.move(val, 0, 0, 0); // positive val to go right
			break;
		case "turn":
			//lets try gyro magnitude to control speed, use 500 as the max and floor if any head jerks occur
			if ((Math.abs(val)) > 300) {
				cal_speed = 1.0f;
				System.out.println(cal_speed);
				drone.move(0,0, 0, intOfFloat(cal_speed)); // positive val to spin right, negative to spin left
			}
			else if ((Math.abs(val)) > 20) {
				cal_speed = (float) Math.round(val / 300.0 * 100) / 100; //will be negative for left
				System.out.println(cal_speed);
				drone.move(0,0, 0, intOfFloat(cal_speed)); // positive val to spin right, negative to spin left
			}			
			break;
		case "lift":
			//lets try gyro magnitude to control speed, use 500 as the max and floor if any head jerks occur
			if ((Math.abs(val)) > 300) {
				cal_speed = 1.0f;
				System.out.println(cal_speed);
				drone.move(0,0, intOfFloat(cal_speed), 0); // positive val to go up, negative to go down
			}
			else if ((Math.abs(val)) > 20) {
				cal_speed = (float) Math.round(val / 300.0 * 100) / 100; //will be negative for left
				System.out.println(cal_speed);
				drone.move(0,0, intOfFloat(cal_speed), 0); // positive val to go up, negative to go down
			}
			break;

			// tricks
			/* NOTE: For the A.R. Drone 2.0 these NEED to be sent as a CONFIG command
			 * with control:flight_anim and NOT an ANIM command like the ones commented out. 
			 * It should look like the following:
			 * AT*CONFIG=1,"control:flight_anim","3,1000"
			 * where 3 is the animation number and 1000 is the duration in msecs
			 */
		case "yaw_dance": // like shaking head
			//drone.playAnimation(ARDrone.Animation.YAW_DANCE, 5);
			drone.setConfigOption("control:flight_anim", 
					ARDrone.Animation.YAW_DANCE + ",5000");
			break;
		case "phi_dance": // tilt side to side
			//drone.playAnimation(ARDrone.Animation.PHI_DANCE, 5);
			drone.setConfigOption("control:flight_anim", 
					ARDrone.Animation.PHI_DANCE + ",5000");
			break;
		case "theta_dance": // forward and backward
			//drone.playAnimation(ARDrone.Animation.THETA_DANCE, 5);
			drone.setConfigOption("control:flight_anim", 
					ARDrone.Animation.THETA_DANCE + ",5000");
			break;
		case "vz_dance": // doesn't seem to do anything... 
			//drone.playAnimation(ARDrone.Animation.VZ_DANCE, 5);
			drone.setConfigOption("control:flight_anim", 
					ARDrone.Animation.VZ_DANCE +",5000");
			break;
		case "combo_dance": // dances forward and backward and side to side
			//drone.playAnimation(ARDrone.Animation.PHI_THETA_MIXED, 5);
			drone.setConfigOption("control:flight_anim", 
					ARDrone.Animation.PHI_THETA_MIXED + ",5000");
			break;
		case "double_combo_dance":
			//drone.playAnimation(ARDrone.Animation.PHI_THETA_MIXED, 5);
			drone.setConfigOption("control:flight_anim", 
					ARDrone.Animation.PHI_THETA_MIXED + ",5000");
			break;
		case "wave": // looks more like it's flying in a tornado
			//drone.playAnimation(ARDrone.Animation.WAVE, 5);
			drone.setConfigOption("control:flight_anim", 
					ARDrone.Animation.WAVE + ",5000");
			break;
		case "yaw_shake":
			//drone.playAnimation(ARDrone.Animation.YAW_SHAKE, 5);
			drone.setConfigOption("control:flight_anim", 
					ARDrone.Animation.YAW_SHAKE + ",5000");
			break;
		case "180":
			//drone.playAnimation(ARDrone.Animation.TURNAROUND, 5);
			drone.setConfigOption("control:flight_anim", 
					ARDrone.Animation.TURNAROUND + ",5000");
			break;
		case "mayday": // probably crashes the drone dramatically... must test this
			//drone.playAnimation(ARDrone.Animation.ANIM_MAYDAY, 5);
			drone.setConfigOption("control:flight_anim", 
					ARDrone.Animation.ANIM_MAYDAY + ",5000");
			break;
		// TODO: find the flip command
		default:
			break;
		}
	}

	@Override
	public void navDataReceived(NavData nd) {
		phi = nd.getRoll();
		theta = nd.getPitch();
		gaz = nd.getAltitude();
		psi = nd.getYaw();		
	}

	public static void handleTraining(BufferedReader trainingInFromServer, DataOutputStream trainingOutToServer) {
		String response = "";
		//InputStreamReader fileInputStream = new InputStreamReader(System.in);
		//BufferedReader br = new BufferedReader(fileInputStream);
		while(true) {
			try {
				while (trainingInFromServer.ready()) {
					response = trainingInFromServer.readLine();
					System.out.println(response);
					if (response.contains("Enter username:")) {
						trainingOutToServer.writeBytes("Ashley\n");
					}
				}
				/*while (br.ready()){
					trainingOutToServer.writeBytes(br.readLine() + '\n');
				}*/
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
