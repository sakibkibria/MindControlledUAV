package com.codeminders.ardrone.data.decoder.ardrone10;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.codeminders.ardrone.ARDrone;
import com.codeminders.ardrone.NavDataDecoder;
import com.codeminders.ardrone.data.ARDroneDataReader;
import com.codeminders.ardrone.data.decoder.ardrone10.navdata.ARDrone10NavData;
import com.codeminders.ardrone.data.navdata.NavDataFormatException;

public class ARDrone10NavDataDecoder extends NavDataDecoder {

    private Logger log = Logger.getLogger(this.getClass().getName());
    
    private boolean              done = false;

    byte[]                       buffer;
    
    public ARDrone10NavDataDecoder(ARDrone drone, int buffer_size) {
        super(drone);
        setName("ARDrone 1.0 NavData decodding thread");
        buffer = new byte[buffer_size];
    }

    @Override
    public void run() {
        
        super.run();
        ARDroneDataReader reader = getDataReader();
        while (!done) {
            try {
                
                pauseCheck();
                
                int len = reader.readDataBlock(buffer);

                if (len > 0) {
                    try {
                        notifyDroneWithDecodedNavdata(ARDrone10NavData.createFromData(ByteBuffer.wrap(buffer), len));
                    } catch (NavDataFormatException e) {
                        log.info("Failed to decode receivd navdata information: " + e.getMessage());
                    } catch (Exception ex) {
                        log.info("Failed to decode receivd navdata information: " + ex.getMessage());
                    }
                }
            } catch (IOException e) {
                log.info(" Error reading data from data input stream. Stopping decoding thread: " + e.getMessage());
                try {
                    reader.reconnect();
                } catch (IOException e1) {
                    log.info(" Error reconnecting data reader: " + e.getMessage());
                }
            }
        } 
        log.info("Decodding thread is stopped"); 
    }

    @Override
    public void finish() {
        done = true;        
    }
}
