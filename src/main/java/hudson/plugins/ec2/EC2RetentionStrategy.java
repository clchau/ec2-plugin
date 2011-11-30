package hudson.plugins.ec2;

import com.xerox.amazonws.ec2.EC2Exception;
import hudson.model.Descriptor;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.logging.Logger;

/**
 * {@link RetentionStrategy} for EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2RetentionStrategy extends RetentionStrategy<EC2Computer> {

    @DataBoundConstructor
    public EC2RetentionStrategy() {
    }

    public synchronized long check(EC2Computer c) {

        if (c.isIdle() && !disabled) {

            long minToNextBilledHour = -1;
            try {
                long minUptime = TimeUnit2.MILLISECONDS.toMinutes(c.getUptime());
                minToNextBilledHour = 60 - (minUptime % 60);
            } catch (EC2Exception e) {
                LOGGER.warning("Unable to calculate time till next billing hour: " + e.getMessage());
            }

            final long idleMin =
                TimeUnit2.MILLISECONDS.toMinutes(System.currentTimeMillis() - c.getIdleStartMilliseconds());
            if (minToNextBilledHour > -1) {
                final long minIdle = 5;
                final long minBill = 5;
                if (minToNextBilledHour <= minBill) {
                    if (idleMin > minIdle) {
                        LOGGER.info("Disconnecting " + c.getName() + " with " + minToNextBilledHour
                            + "m till full hour and being idle for " + idleMin + "m");
                        c.getNode().terminate();
                    }
                }
            } else {
                // default original - fallback - strategy
                final long minIdle = 10;
                if (idleMin > minIdle) {
                    LOGGER.info("Disconnecting " + c.getName() + " after being idle for " + idleMin + "m");
                    c.getNode().terminate();
                }
            }
        }

        return 1; // re-check every minute
    }

    /**
     * Try to connect to it ASAP.
     */
    @Override
    public void start(EC2Computer c) {
        c.connect(false);
    }

    // no registration since this retention strategy is used only for EC2 nodes that we provision automatically.
    // @Extension
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        public String getDisplayName() {
            return "EC2";
        }
    }

    private static final Logger LOGGER = Logger.getLogger(EC2RetentionStrategy.class.getName());

    public static boolean disabled = Boolean.getBoolean(EC2RetentionStrategy.class.getName()+".disabled");
}
