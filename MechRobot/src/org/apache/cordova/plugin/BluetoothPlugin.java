/*
   Copyright 2012 Wolfgang Koller - http://www.gofg.at/

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package org.apache.cordova.plugin;

import org.apache.cordova.api.CordovaInterface;
import org.apache.cordova.api.Plugin;
import org.apache.cordova.api.PluginResult;
import org.json.JSONArray;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.UUID;

import org.json.JSONException;
import org.json.JSONObject;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Parcelable;
import android.util.Log;

public class BluetoothPlugin extends Plugin {
	
	private static final String ACTION_ENABLE = "enable";
	private static final String ACTION_DISABLE = "disable";
	private static final String ACTION_DISCOVERDEVICES = "discoverDevices";
	private static final String ACTION_GETUUIDS = "getUUIDs";
	private static final String ACTION_CONNECT = "connect";
	private static final String ACTION_READ = "read";
	private static final String ACTION_WRITE = "write";
	private static final String ACTION_DISCONNECT = "disconnect";
	BufferedOutputStream TxBufStream;
	
	private static String ACTION_UUID = "";
	private static String EXTRA_UUID = "";
	
	private static final int BUFFER_LENGTH = 32;
	
	private BluetoothAdapter m_bluetoothAdapter = null;
	private BPBroadcastReceiver m_bpBroadcastReceiver = null;
	private ConnectedThread mConnectedThread;
	
	private boolean m_discovering = false;
	private boolean m_gettingUuids = false;
	private boolean m_connected = false;
	private boolean m_stateChanging = false;

	private JSONArray m_discoveredDevices = null;
	private JSONArray m_gotUUIDs = null;

	// Data Arrays
    byte[] TxArray;
	
	private ArrayList<BluetoothSocket> m_bluetoothSockets = new ArrayList<BluetoothSocket>();

	/**
	 * Constctor for Bluetooth plugin
	 */
	public BluetoothPlugin() {
		m_bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		m_bpBroadcastReceiver = new BPBroadcastReceiver();
		
		try {
			Field actionUUID = BluetoothDevice.class.getDeclaredField("ACTION_UUID");
			BluetoothPlugin.ACTION_UUID = (String) actionUUID.get(null);
			Log.d("BluetoothPlugin", "actionUUID: " + actionUUID.getName() + " / " + actionUUID.get(null));

			Field extraUUID = BluetoothDevice.class.getDeclaredField("EXTRA_UUID");
			BluetoothPlugin.EXTRA_UUID = (String) extraUUID.get(null);
			Log.d("BluetoothPlugin", "extraUUID: " + extraUUID.getName() + " / " + extraUUID.get(null));
		}
		catch( Exception e ) {
			Log.e("BluetoothPlugin", e.getMessage() );
		}
	}
	
	/**
	 * Register receiver as soon as we have the context
	 */
	@Override
	public void setContext(CordovaInterface ctx) {
		super.setContext(ctx);

		// Register for necessary bluetooth events
		((Context) ctx).registerReceiver(m_bpBroadcastReceiver, new IntentFilter(
				BluetoothAdapter.ACTION_DISCOVERY_FINISHED));
		((Context) ctx).registerReceiver(m_bpBroadcastReceiver, new IntentFilter(
				BluetoothDevice.ACTION_FOUND));
		((Context) ctx).registerReceiver(m_bpBroadcastReceiver, new IntentFilter(BluetoothPlugin.ACTION_UUID));
		//ctx.registerReceiver(m_bpBroadcastReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
	}

	/**
	 * Execute a bluetooth function
	 */
	@Override
	public PluginResult execute(String action, JSONArray args, String callbackId) {
		PluginResult pluginResult = null;
		
		Log.d("BluetoothPlugin", "Action: " + action);

		if (ACTION_ENABLE.equals(action)) {
			// Check if bluetooth isn't disabled already
			if( !m_bluetoothAdapter.isEnabled() ) {
				m_stateChanging = true;
				ctx.startActivityForResult(this, new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 1);
				while(m_stateChanging) {};
			}
			
			// Check if bluetooth is enabled now
			if(m_bluetoothAdapter.isEnabled()) {
				pluginResult = new PluginResult(PluginResult.Status.OK);
			}
			else {
				pluginResult = new PluginResult(PluginResult.Status.ERROR);
			}
		}
		// Want to disable bluetooth?
		else if (ACTION_DISABLE.equals(action)) {
			if( !m_bluetoothAdapter.disable() && m_bluetoothAdapter.isEnabled() ) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR);
			}
			else {
				pluginResult = new PluginResult(PluginResult.Status.OK);
			}
			
		}
		else if (ACTION_DISCOVERDEVICES.equals(action)) {
			m_discoveredDevices = new JSONArray();

			if (!m_bluetoothAdapter.startDiscovery()) {
				pluginResult = new PluginResult(PluginResult.Status.ERROR,
						"Unable to start discovery");
			} else {
				m_discovering = true;

				// Wait for discovery to finish
				while (m_discovering) {}
				
				Log.d("BluetoothPlugin", "DiscoveredDevices: " + m_discoveredDevices.length());
				
				pluginResult = new PluginResult(PluginResult.Status.OK, m_discoveredDevices);
			}
		}
		// Want to list UUIDs of a certain device
		else if( ACTION_GETUUIDS.equals(action) ) {
			
			try {
				String address = args.getString(0);
				Log.d("BluetoothPlugin", "Listing UUIDs for: " + address);
					
				// Fetch UUIDs from bluetooth device
				BluetoothDevice bluetoothDevice = m_bluetoothAdapter.getRemoteDevice(address);
				Method m = bluetoothDevice.getClass().getMethod("fetchUuidsWithSdp");
				Log.d("BluetoothPlugin", "Method: " + m);
				m.invoke(bluetoothDevice);
				
				m_gettingUuids = true;
				
				while(m_gettingUuids) {}
				
				pluginResult = new PluginResult(PluginResult.Status.OK, m_gotUUIDs);
				
			}
			catch( Exception e ) {
				Log.e("BluetoothPlugin", e.toString() + " / " + e.getMessage() );
				
				pluginResult = new PluginResult(PluginResult.Status.JSON_EXCEPTION, e.getMessage());
			}
		}
		// Connect to a given device & uuid endpoint
		else if( ACTION_CONNECT.equals(action) ) {
			try {
				String address = args.getString(0);
				UUID uuid = UUID.fromString(args.getString(1));
				
				Log.d( "BluetoothPlugin", "Connecting..." );

				BluetoothDevice bluetoothDevice = m_bluetoothAdapter.getRemoteDevice(address);
				BluetoothSocket bluetoothSocket = bluetoothDevice.createRfcommSocketToServiceRecord(uuid);
				
				bluetoothSocket.connect();
				
				m_bluetoothSockets.add(bluetoothSocket);
				int socketId = m_bluetoothSockets.indexOf(bluetoothSocket);

				m_connected = true;
				
				pluginResult = new PluginResult(PluginResult.Status.OK, socketId);
				
				BluetoothSocket btSocket = m_bluetoothSockets.get(socketId);
				mConnectedThread = new ConnectedThread(btSocket);
				mConnectedThread.start();
				/*
				TxArray = new byte[BUFFER_LENGTH];
				TxArray[0] = 's';
				int TxIndex = 0;
				
				BluetoothSocket btSocket = m_bluetoothSockets.get(socketId);
				OutputStream outputStream = btSocket.getOutputStream();
				TxBufStream = new BufferedOutputStream(outputStream);
				
				while (true) {
					try {
	            		TxBufStream.write(TxArray[TxIndex]);
	            		Log.d( "BluetoothPlugin", "Buffer: " + TxArray[TxIndex] );
	                } catch (IOException e) {
	                    Log.e("Connect", "Exception during write", e);
	                }
	            	TxIndex++;
	            	if (TxIndex >= BUFFER_LENGTH)
	            		TxIndex = 0;
				}
				*/
			}
			catch( Exception e ) {
				Log.e("BluetoothPlugin", e.toString() + " / " + e.getMessage() );
				
				pluginResult = new PluginResult(PluginResult.Status.JSON_EXCEPTION, e.getMessage());
			}
		}
		else if( ACTION_READ.equals(action) ) {
			try {
				int socketId = args.getInt(0);
				
				BluetoothSocket bluetoothSocket = m_bluetoothSockets.get(socketId);
				InputStream inputStream = bluetoothSocket.getInputStream();
				
				char[] buffer = new char[128];
				for( int i = 0; i < buffer.length - 1; i++ ) {
					buffer[i] = (char) inputStream.read();
				}
				
				//Log.d( "BluetoothPlugin", "Buffer: " + String.valueOf(buffer) );
				pluginResult = new PluginResult(PluginResult.Status.OK, String.valueOf(buffer));
			}
			catch( Exception e ) {
				Log.e("BluetoothPlugin", e.toString() + " / " + e.getMessage() );
				
				pluginResult = new PluginResult(PluginResult.Status.JSON_EXCEPTION, e.getMessage());
			}
		}
		else if( ACTION_WRITE.equals(action) ) {
			try {
        		int socketId = args.getInt(0);
				
        		int center_forward = args.getInt(1);
				int center_backward = args.getInt(2);
				int pivot_left = args.getInt(3);
				int pivot_right = args.getInt(4);
				int stop = args.getInt(5);
				int step_cw = args.getInt(6);
				int step_ccw = args.getInt(7);
				int claw_open = args.getInt(8);
				int claw_close = args.getInt(9);
				int pause = args.getInt(10);
				int reset = args.getInt(11);
				
				/*
				int micro_forward args.getInt(12)
				int micro_left args.getInt(13)
				 */
				
				for (int m = 1; m > 12; m++) {
					Log.d("Array", "Buffer: " + args.getInt(m));
				}
				
        		// Build the Tx Byte Array
        		TxArray[1] = (byte) ((center_forward & 0xFF00) >>> 8);
        		TxArray[2] = (byte) (center_forward & 0xFF);
        		TxArray[3] = (byte) ((center_backward & 0xFF00) >>> 8);
        		TxArray[4] = (byte) (center_backward & 0xFF);
        		TxArray[5] = (byte) ((pivot_left & 0xFF00) >>> 8);
        		TxArray[6] = (byte) (pivot_left & 0xFF);
        		TxArray[7] = (byte) ((pivot_right & 0xFF00) >>> 8);
        		TxArray[8] = (byte) (pivot_right & 0xFF);
        		TxArray[9] = (byte) ((stop & 0xFF00) >>> 8);
        		TxArray[10] = (byte) (stop & 0xFF);
        		TxArray[11] = (byte) ((step_cw & 0xFF00) >>> 8);
        		TxArray[12] = (byte) (step_cw & 0xFF);
        		TxArray[13] = (byte) ((step_ccw & 0xFF00) >>> 8);
        		TxArray[14] = (byte) (step_ccw & 0xFF);
        		TxArray[15] = (byte) ((claw_open & 0xFF00) >>> 8);
        		TxArray[16] = (byte) (claw_open & 0xFF);
        		TxArray[17] = (byte) ((claw_close & 0xFF00) >>> 8);
        		TxArray[18] = (byte) (claw_close & 0xFF);
        		TxArray[19] = (byte) ((pause & 0xFF00) >>> 8);
        		TxArray[20] = (byte) (pause & 0xFF);
        		TxArray[21] = (byte) ((reset & 0xFF00) >>> 8);
        		TxArray[22] = (byte) (reset & 0xFF);
        		/*
        		TxArray[23] = (byte) ((micro_forward & 0xFF00) >>> 8);
        		TxArray[24] = (byte) (micro_forward & 0xFF00);
        		TxArray[25] = (byte) ((micro_left & 0xFF00) >>> 8);
        		TxArray[26] = (byte) (micro_left & 0xFF00);
        		*/
        		TxArray[23] = (byte) ((reset & 0xFF00) >>> 8);
        		TxArray[24] = (byte) ((reset & 0xFF00) >>> 8);
        		TxArray[25] = (byte) ((reset & 0xFF00) >>> 8);
        		TxArray[26] = (byte) ((reset & 0xFF00) >>> 8);
        		TxArray[27] = (byte) ((reset & 0xFF00) >>> 8);
        		TxArray[28] = (byte) ((reset & 0xFF00) >>> 8);
        		TxArray[29] = (byte) ((reset & 0xFF00) >>> 8);
        		TxArray[31] = (byte) ((reset & 0xFF00) >>> 8);
        		
        		//TxBufStream.flush();

            	pluginResult = new PluginResult(PluginResult.Status.OK, String.valueOf(TxArray));
			}
			catch( Exception e ) {
				Log.e("BluetoothPlugin", e.toString() + " / " + e.getMessage() );
				
				pluginResult = new PluginResult(PluginResult.Status.JSON_EXCEPTION, e.getMessage());
			}
		}
		else if( ACTION_DISCONNECT.equals(action) ) {
			try {
				int socketId = args.getInt(0);
				
				// Fetch socket & close it
				BluetoothSocket bluetoothSocket = m_bluetoothSockets.get(socketId);
				bluetoothSocket.close();
				
				// Remove socket from internal list
				m_bluetoothSockets.remove(socketId);
				
				// Everything went fine...
				pluginResult = new PluginResult(PluginResult.Status.OK);
			}
			catch( Exception e ) {
				Log.e("BluetoothPlugin", e.toString() + " / " + e.getMessage() );
				
				pluginResult = new PluginResult(PluginResult.Status.JSON_EXCEPTION, e.getMessage());
			}
		}

		return pluginResult;
	}

	/**
	 * Receives activity results
	 */
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent intent) {
		if( requestCode == 1 ) {
			m_stateChanging = false;
		}
	}

	/**
	 * Helper class for handling all bluetooth based events
	 */
	private class BPBroadcastReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			
			//Log.d( "BluetoothPlugin", "Action: " + action );

			// Check if we found a new device
			if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				BluetoothDevice bluetoothDevice = intent
						.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

				try {
					JSONObject deviceInfo = new JSONObject();
					deviceInfo.put("name", bluetoothDevice.getName());
					deviceInfo.put("address", bluetoothDevice.getAddress());
					
					m_discoveredDevices.put(deviceInfo);
				} catch (JSONException e) {
					Log.e("BluetoothPlugin", e.getMessage());
				}
			}
			// Check if we finished discovering devices
			else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				m_discovering = false;
			}
			// Check if we found UUIDs
			else if(BluetoothPlugin.ACTION_UUID.equals(action)) {
				m_gotUUIDs = new JSONArray();
				
				Parcelable[] parcelUuids = intent.getParcelableArrayExtra(BluetoothPlugin.EXTRA_UUID);
				if( parcelUuids != null ) {
					Log.d("BluetoothPlugin", "Found UUIDs: " + parcelUuids.length);
	
					// Sort UUIDs into JSON array and return it
					for( int i = 0; i < parcelUuids.length; i++ ) {
						m_gotUUIDs.put( parcelUuids[i].toString() );
					}
	
					m_gettingUuids = false;
				}
			}
		}
	}

	private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final OutputStream mmOutStream;
        
        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            OutputStream tmpOut = null;

            TxArray = new byte[BUFFER_LENGTH];
            
            // Get the BluetoothSocket input and output streams
            try {
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e("BluetoothPlugin", "temp sockets not created", e);
            }

            mmOutStream = tmpOut;
        }

        public void run() {
            Log.i("BluetoothPlugin", "BEGIN mConnectedThread");

            int TxIndex = 0;
            TxBufStream = new BufferedOutputStream(mmOutStream);


			//TxArray = new byte[BUFFER_LENGTH];
			TxArray[0] = 's';
			
			//BluetoothSocket btSocket = m_bluetoothSockets.get(socketId);
			//OutputStream outputStream = btSocket.getOutputStream();
			//TxBufStream = new BufferedOutputStream(outputStream);
			
			while (true) {
				try {
            		TxBufStream.write(TxArray[TxIndex]);
            		TxBufStream.flush();
            		Log.d( "BluetoothPlugin", "Buffer: " + TxArray[TxIndex] );
                } catch (IOException e) {
                    Log.e("Connect", "Exception during write", e);
                }
            	TxIndex++;
            	if (TxIndex >= BUFFER_LENGTH)
            		TxIndex = 0;
			}
        }

      
    }
}
