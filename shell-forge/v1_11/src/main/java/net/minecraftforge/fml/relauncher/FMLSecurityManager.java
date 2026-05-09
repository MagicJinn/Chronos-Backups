package net.minecraftforge.fml.relauncher;

import com.magicjinn.chronos.shell.AbstractFmlSecurityManager;

public class FMLSecurityManager extends AbstractFmlSecurityManager {
    public FMLSecurityManager() {
        super("net.minecraftforge.fml.");
    }
}
