package hudson.plugins.ec2.ssh;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;

import com.xerox.amazonws.ec2.EC2Exception;
import com.xerox.amazonws.ec2.KeyPairInfo;
import com.xerox.amazonws.ec2.ReservationDescription.Instance;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;
import org.apache.commons.io.IOUtils;
import org.jets3t.service.S3ServiceException;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;

/**
 * {@link ComputerLauncher} that connects to a Unix slave on EC2 by using SSH.
 * 
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2Launcher extends EC2ComputerLauncher {

    protected final int FAILED = -1;
    protected final int SAMEUSER = 0;
    protected final int RECONNECT = -2;

    abstract int bootstrap(Connection bootstrapConn, EC2Computer computer, PrintStream logger) throws IOException, InterruptedException, EC2Exception;

    abstract String getInitFile();

    abstract String getInitPath();

    abstract String getInitFolder();

    abstract String getSlaveFile();

    abstract String getSlavePath();

    abstract String getSlaveFolder();

    protected void launch(EC2Computer computer, PrintStream logger, Instance inst) throws IOException, EC2Exception, InterruptedException, S3ServiceException {

        final PrintStream finalLogger = logger;
        Connection conn;

        try {

            conn = connectToSsh(computer, logger);
            int bootstrapResult = bootstrap(conn, computer, logger);
            if (bootstrapResult == FAILED) {
                conn = null; // bootstrap closed for us.
                logger.println("Failed to connect to SSHD server");
            }

            String initScript = computer.getNode().initScript;

            if (initScript != null && initScript.trim().length() > 0 && runCommand(conn, "test -e /.hudson-run-init", logger) != 0) {
                SCPClient scp = conn.createSCPClient();
                logger.println("Executing init script");
                scp.put(initScript.getBytes("UTF-8"), getInitFile(), getInitFolder(), "0700");
                Session sess = conn.openSession();
                sess.requestDumbPTY(); // so that the remote side bundles stdout and stderr
                sess.execCommand(getInitPath());

                sess.getStdin().close();    // nothing to write here
                sess.getStderr().close();   // we are not supposed to get anything from stderr
                IOUtils.copy(sess.getStdout(), logger);

                int exitStatus = waitCompletion(sess);
                if (exitStatus != 0) {
                    logger.println("init script failed: exit code=" + exitStatus);
                    return;
                }

                // leave the completion marker
                scp.put(new byte[0], ".hudson-run-init", "/", "0600");

            }

            // TODO: parse the version number. maven-enforcer-plugin might help
            logger.println("Verifying that java exists");
            if (runCommand(conn, "java -fullversion", logger) != 0) {
                logger.println("Installing Java");

                String jdk = "java1.6.0_12";
                String path = "/hudson-ci/jdk/linux-i586/" + jdk + ".tgz";

                URL url = EC2Cloud.get().buildPresignedURL(path);
                String javaInstallCmd = "wget -nv -O /usr/" + jdk + ".tgz '" + url + "'"
                        + " && " + "tar xz -C /usr -f /usr/" + jdk + ".tgz" + " && "
                        + "ln -s /usr/" + jdk + "/bin/java /bin/java";

                if (runCommand(conn, javaInstallCmd, logger) != 0) {
                    logger.println("Unable to install Java");
                    return;
                }

            }

            //Use wget instead of SCP.
            //On a windows system running ec2-sshd the chanel closes unexpectedly.
            String wget = "wget --output-document=" + getSlavePath() + " " + Hudson.getInstance().getRootUrl() + "/jnlpJars/slave.jar";
            runCommand(conn, wget, logger);

            logger.println("Prepare to launch slave agent");

            final Session sess = conn.openSession();

            String jvmOpts = computer.getNode().jvmopts;
            if (jvmOpts == null) {
                jvmOpts = "";
            }

            logger.println("Execute client");
            String cmd = "java " + jvmOpts + " -jar " + getSlavePath();
            logger.println(cmd);
            sess.execCommand(cmd);

            logger.println("Register channel)");
            Listener listener = new Listener() {

                public void onClosed(Channel channel, IOException cause) {
                    finalLogger.println("Channel is being closed, " + cause.getMessage());
                    sess.close();
                }
            };
            computer.setChannel(sess.getStdout(), sess.getStdin(), logger, listener);

        } catch (Throwable t) {
            logger.println("Something terrible happened. I caught a " + t.getClass().getName() + " that said: " + t.getMessage());
            if (t.getCause() != null) {
                logger.println("Caused by " + t.getCause().getMessage());
            }
            logger.println(t.getStackTrace().toString());
        } finally {
        }
    }

    protected int authenticate(Connection conn, EC2Computer computer, PrintStream logger) throws EC2Exception, IOException, InterruptedException {
        int tries = 20;
        boolean isAuthenticated = false;
        KeyPairInfo key = EC2Cloud.get().getKeyPair();
        while (tries-- > 0) {
            logger.println("Authenticating as " + computer.getRemoteAdmin());
            isAuthenticated = conn.authenticateWithPublicKey(computer.getRemoteAdmin(), key.getKeyMaterial().toCharArray(), "");
            if (isAuthenticated) {
                break;
            }
            logger.println("Authentication failed. Trying again...");
            Thread.currentThread().sleep(10000);
        }
        if (!isAuthenticated) {
            logger.println("Authentication failed");
            return FAILED;
        }
        return SAMEUSER;
    }

    protected int runCommand(Connection connection, String command, PrintStream logger) throws IOException, InterruptedException {

        logger.println("Prepairing to run cmd: " + command);
        Session session = connection.openSession();
        logger.println("Execute command");
        session.execCommand(command);
        session.getStdin().close();    // nothing to write here
        IOUtils.copy(session.getStdout(), logger);
        IOUtils.copy(session.getStderr(), logger);
        logger.println("Waiting for complete");
        return waitCompletion(session);
    }

    protected Connection connectToSsh(EC2Computer computer, PrintStream logger) throws EC2Exception, InterruptedException {
        while (true) {
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
                // keep retrying until SSH comes up
                logger.println("Waiting for SSH to come up. Sleeping 5.");
                Thread.sleep(5000);
            }
        }
    }

    protected int waitCompletion(Session session) throws InterruptedException {
        // I noticed that the exit status delivery often gets delayed. Wait up to 1 sec.
        for (int i = 0; i < 10; i++) {
            Integer r = session.getExitStatus();
            if (r != null) {
                return r;
            }
            Thread.sleep(100);
        }
        return -1;
    }

    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
