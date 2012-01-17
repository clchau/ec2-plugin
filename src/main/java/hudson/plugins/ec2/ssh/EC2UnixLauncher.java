/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hudson.plugins.ec2.ssh;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.Session;

import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.KeyPairInfo;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.PrintStream;
/**
 *
 * @author jrisch
 */
public class EC2UnixLauncher extends EC2Launcher {
    String getInitFile()
    {
        return "init.sh";
    }
    String getInitPath()
    {
        return getInitFolder()+getInitFile();
    }        
    String getInitFolder()
    {
        return "/tmp/";        
    }
    
    String getSlaveFile()
    {
        return "slave.jar";
    }
    String getSlavePath()
    {
        return getSlaveFolder()+getSlaveFile();
    }
    String getSlaveFolder()
    {
        return "/tmp/";
    }
    
    protected int bootstrap(Connection bootstrapConn, EC2Computer computer, PrintStream logger) throws IOException, InterruptedException, EC2Exception {

            int result=authenticate(bootstrapConn,computer,logger);
            
            if (!computer.getRemoteAdmin().equals("root")) {
                // Get root working, so we can scp in etc.
                Session sess = bootstrapConn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand("cp ~/.ssh/authorized_keys /tmp/ && "
                                 + computer.getRootCommandPrefix() + "chown root:root /tmp/authorized_keys && "
                                 + computer.getRootCommandPrefix() + "mv /tmp/authorized_keys /root/.ssh/");
                sess.getStdin().close(); // nothing to write here
                sess.getStderr().close(); // we are not supposed to get anything from stderr
                IOUtils.copy(sess.getStdout(), logger);
                int exitStatus = waitCompletion(sess);
                if (exitStatus != 0) {
                    logger.println("init script failed: exit code=" + exitStatus);
                    return FAILED;
                }
                // connect fresh as ROOT
                bootstrapConn.close();
                bootstrapConn = connectToSsh(computer, logger);
                KeyPairInfo key = EC2Cloud.get().getKeyPair();
                if (!bootstrapConn.authenticateWithPublicKey("root", key.getKeyMaterial().toCharArray(), "")) {
                    logger.println("Authentication failed");
                    return FAILED;
                }
            } 
            return RECONNECT;
    }
}
