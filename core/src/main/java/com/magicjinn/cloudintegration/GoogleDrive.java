package com.magicjinn.cloudintegration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.magicjinn.chronos.core.Core;
import com.magicjinn.chronos.core.config.Config;
import com.magicjinn.chronos.shell.ChronosConstants;

/**
 * Google Drive OAuth for the Chronos Backups Google Cloud project (installed
 * app).
 *
 * <p>
 * End-user flow: enable Google Drive in config > console prints a URL > sign in
 * once > done. User tokens are stored under {@code google-drive-tokens/} on
 * that
 * machine and are never shipped in the mod jar.
 *
 * <p>
 * The OAuth client id/secret identify the Chronos app (normal for desktop-style
 * clients). They are not per-user credentials.
 */
public final class GoogleDrive implements CloudIntegration {
    private static final Logger LOG = LogManager.getLogger(ChronosConstants.LOG_NAME);

    private static final String APPLICATION_NAME = "Chronos Backups";
    /** Chronos Backups Google Drive — project amplified-cache-502710-a0. */
    private static final String CLIENT_ID = "810775279009-4dmgnra5umq4s9gct36o5998srao69tj.apps.googleusercontent.com";
    private static final String CLIENT_SECRET = "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";

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE_FILE);
    private static final String TOKENS_DIR_NAME = "google-drive-tokens";
    private static final String USER_ID = "user";
    private static final int OAUTH_LOCAL_PORT = 8888;

    private static final AtomicBoolean authInProgress = new AtomicBoolean(false);

    private static volatile Drive drive;

    private static volatile boolean ready = false;

    public static final GoogleDrive INSTANCE = new GoogleDrive();

    private GoogleDrive() {
    }

    @Override
    public String getId() {
        return "gdrive";
    }

    @Override
    public boolean isEnabled() {
        return Config.isGoogleDriveEnabled();
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void initialize() {
        if (!Config.isGoogleDriveEnabled()) {
            LOG.info("Google Drive is disabled in config.");
            return;
        }
        try {
            Credential existing = loadStoredCredential();
            if (existing != null) {
                drive = buildDriveService(existing);
                ready = true;
                LOG.info("Google Drive authorized from stored tokens.");
                return;
            }
            LOG.info(
                    "Google Drive enabled with no linked account. "
                            + "Starting authorization. A URL will be printed below.");
            startInteractiveAuthorizationAsync();
        } catch (Exception e) {
            LOG.error("Google Drive initialization failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void uploadBackup(Path localBackup, String worldName) throws IOException {
        if (!ready || drive == null) {
            throw new IOException("Google Drive is not ready");
        }
        // TODO: upload localBackup under a Chronos folder for worldName
        throw new IOException("Google Drive upload is not implemented yet");
    }

    @Override
    public void trimOldBackups(String worldName, int maxStored) throws IOException {
        if (maxStored < 1) {
            return;
        }
        if (!ready || drive == null) {
            throw new IOException("Google Drive is not ready");
        }
        // TODO: list remote backups for worldName and delete oldest past maxStored
        throw new IOException("Google Drive retention trim is not implemented yet");
    }

    @Override
    public void shutdown() {
        authInProgress.set(false);
        // OAuth worker is daemon. Nothing else to cancel until uploads exist.
    }

    /**
     * Installed-app OAuth: logs the URL to the console (no automatic browser open).
     * Prefer {@link #startInteractiveAuthorizationAsync()} from game threads.
     */
    public static Drive authorizeInteractively() throws IOException, GeneralSecurityException {
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        GoogleAuthorizationCodeFlow flow = buildFlow(httpTransport);
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(OAUTH_LOCAL_PORT).build();
        AuthorizationCodeInstalledApp.Browser consoleBrowser = new AuthorizationCodeInstalledApp.Browser() {
            @Override
            public void browse(String url) {
                LOG.info("============================================================");
                LOG.info("Google Drive authorization");
                LOG.info("Open this URL in a browser, sign in, and allow Chronos Backups:");
                LOG.info(url);
                LOG.info("============================================================");
            }
        };
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver, consoleBrowser).authorize(USER_ID);
        drive = buildDriveService(credential);
        ready = true;
        LOG.info("Google Drive authorization completed successfully.");
        return drive;
    }

    /**
     * Starts {@link #authorizeInteractively()} on a daemon worker if none is
     * already running.
     */
    public static boolean startInteractiveAuthorizationAsync() {
        if (!authInProgress.compareAndSet(false, true)) {
            LOG.info("Google Drive OAuth is already in progress.");
            return false;
        }
        Thread worker = new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            authorizeInteractively();
                        } catch (Exception e) {
                            LOG.error(
                                    "Google Drive authorization failed: " + e.getMessage(), e);
                        } finally {
                            authInProgress.set(false);
                        }
                    }
                },
                "chronos-gdrive-oauth");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public static Drive getDrive() {
        return drive;
    }

    private static GoogleClientSecrets buildClientSecrets() {
        GoogleClientSecrets.Details details = new GoogleClientSecrets.Details();
        details.setClientId(CLIENT_ID);
        details.setClientSecret(CLIENT_SECRET);
        GoogleClientSecrets clientSecrets = new GoogleClientSecrets();
        clientSecrets.setInstalled(details);
        return clientSecrets;
    }

    private static Credential loadStoredCredential() throws IOException, GeneralSecurityException {
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        GoogleAuthorizationCodeFlow flow = buildFlow(httpTransport);
        return flow.loadCredential(USER_ID);
    }

    private static Drive buildDriveService(Credential credential)
            throws GeneralSecurityException, IOException {
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        return new Drive.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private static GoogleAuthorizationCodeFlow buildFlow(NetHttpTransport httpTransport)
            throws IOException {
        File tokensDir = Core.RunningDirectory.resolve(TOKENS_DIR_NAME).toFile();
        return new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, buildClientSecrets(), SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(tokensDir))
                .setAccessType("offline")
                .build();
    }
}
