package org.dynmap.neoforge_26_2;

import org.dynmap.utils.DynmapLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NeoForgeLogger implements DynmapLogger {
    Logger log;
    public static final String DM = "[Dynmap] ";

    NeoForgeLogger() {
        log = LoggerFactory.getLogger("Dynmap");
    }

    @Override
    public void info(String s) {
        log.info(DM + s);
    }

    @Override
    public void severe(Throwable t) {
        log.error(DM, t);
    }

    @Override
    public void severe(String s) {
        log.error(DM + s);
    }

    @Override
    public void severe(String s, Throwable t) {
        log.error(DM + s, t);
    }

    @Override
    public void verboseinfo(String s) {
        log.info(DM + s);
    }

    @Override
    public void warning(String s) {
        log.warn(DM + s);
    }

    @Override
    public void warning(String s, Throwable t) {
        log.warn(DM + s, t);
    }
}
