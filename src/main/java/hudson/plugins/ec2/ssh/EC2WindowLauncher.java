/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hudson.plugins.ec2.ssh;

import com.trilead.ssh2.Connection;

import com.trilead.ssh2.ServerHostKeyVerifier;
import hudson.plugins.ec2.EC2Computer;
import com.xerox.amazonws.ec2.EC2Exception;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Launcher to handle initiation of an EC2 windows based slave.
 * 
 * The slave AMI must meet the following criteria
 * 1) EC2-SSHD is installed as a service
 * 2) GNU Utils must be installed and added to the system path.
 * 3) c:\tmp must exist and have permissions for all users.
 * 
 * @author jrisch
 */
public class EC2WindowLauncher extends EC2Launcher {

    String getInitFile() {
        return "init.bat";
    }

    String getInitPath() {
        return getInitFolder() + getInitFile();
    }

    String getInitFolder() {
        return "c:\\tmp\\";
    }

    String getSlaveFile() {
        return "slave.jar";
    }

    String getSlavePath() {
        return getSlaveFolder() + getSlaveFile();
    }

    String getSlaveFolder() {
        return "c:\\tmp\\";
    }

    
    void initialSleep(PrintStream logger) throws InterruptedException {
        //Sleep XXX minutes
        //This is needed as AWS reboots the windows system once after it's initial launch in 
        //order to set the system name.
        //TODO: Look into what AWS does to the machine during first launch.
        //Is there a way to wait for a specific event? Edited file or other?
        //Need a better check than a blind wait.
        logger.println("Waiting for system launch to complete.");
        for (int i = 15; i > 0; i--) {
            logger.println("Sleeping " + i + " minutes.");
            Thread.sleep(60000);
        }

    }

    protected int bootstrap(Connection bootstrapConn, EC2Computer computer, PrintStream logger) throws IOException, InterruptedException, EC2Exception {
        return authenticate(bootstrapConn, computer, logger);
    }

    protected Connection connectToSsh(EC2Computer computer, PrintStream logger) throws EC2Exception, InterruptedException {
        Connection conn = trySshConnection(computer, logger);
        if (conn == null) {
            initialSleep(logger);
        }
        while (true) {
            conn = trySshConnection(computer, logger);
            if (conn == null) {
                // keep retrying until SSH comes up
                logger.println("Waiting for SSH to come up. Sleeping 5.");
                Thread.sleep(5000);
            }
        }
    }

    private Connection trySshConnection(EC2Computer computer, PrintStream logger) throws EC2Exception, InterruptedException {

        try {
            String host = computer.updateInstanceDescription().getPrivateIpAddress();
            if ("0.0.0.0".equals(host)) {
                logger.println("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                throw new IOException("goto sleep");
            }
            int port = computer.getSshPort();
            logger.println("Connecting to " + host + " on port " + port + ". ");
            Connection conn = new Connection(host, port);

            // currently OpenSolaris offers no way of verifying the host certificate, so just accept it blindly,
            // hoping that no man-in-the-middle attack is going on.
            conn.connect(new ServerHostKeyVerifier() {

                public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
                    return true;
                }
            });
            logger.println("Connected via SSH.");
            return conn; // successfully connected
        } catch (IOException e) {
            return null;
        }
    }
}
