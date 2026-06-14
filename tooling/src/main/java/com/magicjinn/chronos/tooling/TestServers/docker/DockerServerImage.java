package com.magicjinn.chronos.tooling.TestServers.docker;

import com.magicjinn.chronos.tooling.TestServers.ChronosJavaMatrix;
import com.magicjinn.chronos.tooling.TestServers.TestServers;

import java.io.IOException;

/**
 * Resolves itzg/minecraft-server image refs ({@code image:java<major>}) from
 * the Java matrix.
 */
public final class DockerServerImage {
    private static final String DOCKER_IMAGE = "itzg/minecraft-server";
    private static final ChronosJavaMatrix JAVA = loadJavaMatrix();

    private DockerServerImage() {
    }

    public static String imageFor(String minecraftVersion) {
        return DOCKER_IMAGE + ":java" + JAVA.runtimeJavaMajor(minecraftVersion);
    }

    private static ChronosJavaMatrix loadJavaMatrix() {
        try {
            return ChronosJavaMatrix.load(TestServers.ROOT.resolve("gradle/chronos-java-matrix.json"));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
