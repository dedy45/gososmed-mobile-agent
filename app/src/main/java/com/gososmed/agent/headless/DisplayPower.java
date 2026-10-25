package com.gososmed.agent.headless;

import java.io.File;
import java.lang.reflect.Method;

public class DisplayPower {
    private static final String LOCK_FILE = "/data/local/tmp/headless_display.lock";

    public static void main(String[] args) {
        try {
            boolean turnOff = args.length > 0 && args[0].equalsIgnoreCase("off");
            int displayId = 0; // default display
            Class<?> dmg = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Method getInstance = dmg.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object dmgInstance = getInstance.invoke(null);

            Method requestDisplayPower = dmg.getMethod("requestDisplayPower", int.class, boolean.class);
            requestDisplayPower.setAccessible(true);

            if (!turnOff) {
                File lock = new File(LOCK_FILE);
                if (lock.exists()) lock.delete();
                requestDisplayPower.invoke(dmgInstance, displayId, true);
                System.out.println("SUCCESS: Display turned ON");
                System.exit(0);
            }

            File lock = new File(LOCK_FILE);
            if (!lock.exists()) {
                boolean created = lock.createNewFile();
                System.out.println("Lock file created: " + created);
            } else {
                System.out.println("Lock file already existed");
            }

            boolean ok = (boolean) requestDisplayPower.invoke(dmgInstance, displayId, false);
            System.out.println("SUCCESS: Display turned OFF (holding daemon alive, ok=" + ok + ")");

            int count = 0;
            while (lock.exists()) {
                Thread.sleep(500);
                count++;
                if (count % 10 == 0) {
                    System.out.println("Daemon holding display off... (" + (count / 2) + "s)");
                }
            }

            System.out.println("Lock file disappeared! Exiting and restoring display power...");
            requestDisplayPower.invoke(dmgInstance, displayId, true);
            System.out.println("SUCCESS: Daemon exiting, restored display power");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
