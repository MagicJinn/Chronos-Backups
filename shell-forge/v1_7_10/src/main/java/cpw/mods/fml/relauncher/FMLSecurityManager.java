package cpw.mods.fml.relauncher;

import com.magicjinn.chronos.shell.AbstractFmlSecurityManager;

public class FMLSecurityManager extends AbstractFmlSecurityManager {
    public FMLSecurityManager() {
        super("cpw.mods.fml.");
    }
}
