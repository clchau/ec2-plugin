/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hudson.plugins.ec2.ssh;

/**
 * Launcher factory responsible for determining which launcher to use
 * currently this is determined via entries in label.
 * 
 * TODO: add a property to hold the enum values available isntead of using labels
 * @author jrisch
 */
public class LauncherFactory {
    public static EC2Launcher getLauncher(String labelString)
    {
        if(labelString.toLowerCase().contains("windows"))
            return new EC2WindowLauncher();
        else
            return new EC2UnixLauncher();
    }
}
