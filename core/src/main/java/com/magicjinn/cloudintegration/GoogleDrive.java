package com.magicjinn.cloudintegration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.magicjinn.chronos.core.BackupRuntimeContext;
import com.magicjinn.chronos.core.ChronosBackupArtifacts;
import com.magicjinn.chronos.core.Core;
import com.magicjinn.chronos.core.Scheduler;
import com.magicjinn.chronos.core.config.Config;
import com.magicjinn.chronos.shell.ChronosConstants;

/** Google Drive integration using OAuth2 and Google Drive API. */
public final class GoogleDrive implements CloudIntegration {
    private static final Logger LOG = LogManager.getLogger(ChronosConstants.LOG_NAME);

    private static final String APPLICATION_NAME = ChronosConstants.NAME;
    private static final String REMOTE_ROOT_FOLDER_NAME = ChronosConstants.NAME;
    /**
     * Chronos Backups Google Drive. Project: amplified-cache-502710-a0.
     * These have to be public, and shipped with the jar. That's just the way it has to be.
    */
    private static final String CLIENT_ID = "810775279009-4dmgnra5umq4s9gct36o5998srao69tj.apps.googleusercontent.com";
    private static final String CLIENT_SECRET = "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";

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE_FILE);
    private static final String TOKENS_DIR_NAME = "google-drive-tokens";
    private static final String USER_ID = "user";
    private static final int OAUTH_LOCAL_PORT = 8888;
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    /** Default Google client timeouts (20s) are too short for large backup uploads. */
    private static final int HTTP_CONNECT_TIMEOUT_MS = 60_000;
    private static final int HTTP_READ_TIMEOUT_MS = 10 * 60_000;
    /** Resumable upload chunk size (must be a multiple of {@link MediaHttpUploader#MINIMUM_CHUNK_SIZE}). */
    private static final int UPLOAD_CHUNK_SIZE = 32 * MediaHttpUploader.MINIMUM_CHUNK_SIZE;

    private static final AtomicBoolean authInProgress = new AtomicBoolean(false);
    private static final AtomicBoolean syncCancelRequested = new AtomicBoolean(false);
    /** True when OAuth finished before a world was loaded. Finish alias setup later. */
    private static final AtomicBoolean aliasSetupPending = new AtomicBoolean(false);

    private static volatile Drive drive;

    private static volatile boolean ready = false;

    public static final GoogleDrive INSTANCE = new GoogleDrive();

    private GoogleDrive() {
    }

    /**
     * Force-resolve Drive HTTP / OpenCensus / gRPC classes without OAuth.
     * Logs a fixed success line for testServers readyMarkers.
     */
    @Override
    public void probeClasspath() {
        ClassLoader cl = GoogleDrive.class.getClassLoader();
        try {
            // Compile-time type: Forge jar-relocator rewrites this to the repack name.
            Class.forName(HttpRequestInitializer.class.getName(), true, cl);
            Class.forName("io.opencensus.trace.propagation.TextFormat", true, cl);
            Class.forName("io.grpc.Context", true, cl);

            LOG.info("Google Drive classpath probe OK");
        } catch (Throwable t) {
            LOG.error("Google Drive classpath probe failed", t);
        }
    }

    @Override
    public String getId() {
        return "gdrive";
    }

    @Override
    public String getDisplayName() {
        return "Google Drive";
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
                tryEnsureWorldFolderAliasSetup();
                CloudSync.requestSync();
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
    public void synchronize() throws IOException {
        // Sanity check
        syncCancelRequested.set(false);
        if (!ready || drive == null)
            throw new IOException("Google Drive is not ready");

        if (!ensureWorldFolderAliasSetup()) {
            LOG.info("Google Drive sync deferred until a world is loaded (alias setup).");
            return;
        }

        Path backupRoot = Core.RunningDirectory.resolve(Config.getBackupFolderName());
        if (!Files.isDirectory(backupRoot)) {
            LOG.info("Google Drive sync: no local backup folder at " + backupRoot);
            return;
        }

        String rootFolderId = findOrCreateFolder(null, REMOTE_ROOT_FOLDER_NAME);
        int maxStored = Config.getMaxStoredBackups();
        boolean shouldKeepLocal = Config.shouldKeepLocalBackups();

        try (DirectoryStream<Path> worlds = Files.newDirectoryStream(backupRoot)) {
            for (Path worldDir : worlds) {
                checkCancelled();
                if (!Files.isDirectory(worldDir)) 
                    continue;
                
                String worldName = worldDir.getFileName().toString();
                if (worldName.startsWith("."))
                    continue;

                syncWorld(worldDir, worldName, rootFolderId, maxStored, shouldKeepLocal);
            }
        }
    }

    /** Synchronizes a world directory to Google Drive. */
    private void syncWorld(
            Path worldDir,
            String worldName,
            String rootFolderId,
            int maxStored,
            boolean keepLocal) throws IOException {
        List<Path> localBackups = listLocalChronosBackups(worldDir, worldName);
        if (localBackups.isEmpty() && maxStored < 1)
            return;

        String remoteWorldFolder = CloudBackupAlias.remoteFolderName(worldName);
        String worldFolderId = findOrCreateFolder(rootFolderId, remoteWorldFolder);
        Map<String, RemoteFile> remoteByName = listRemoteChronosBackups(worldFolderId, worldName);

        // Oldest first so catch-up fills history in order.
        localBackups.sort(Comparator.comparingLong(
                (Path pa) -> ChronosBackupArtifacts.timestampSortKey(pa.getFileName().toString())));

        for (Path local : localBackups) {
            checkCancelled();
            String remoteName = ChronosBackupArtifacts.remoteFileName(local);
            if (remoteByName.containsKey(remoteName))
                continue;

            uploadLocalBackup(local, remoteName, worldFolderId, remoteByName);

            if (!keepLocal)
                deleteLocalBackup(local);

            if (maxStored >= 1)
                trimRemoteToAtMost(remoteByName, maxStored);
        }

        // Cap remotes even when nothing new uploaded (e.g. maxStored lowered).
        if (maxStored >= 1)
            trimRemoteToAtMost(remoteByName, maxStored);
    }

    /** Deletes oldest Chronos remotes until {@code remoteByName.size() <= maxCount}. */
    private void trimRemoteToAtMost(Map<String, RemoteFile> remoteByName, int maxCount)
            throws IOException {
        while (remoteByName.size() > maxCount) {
            checkCancelled();
            RemoteFile oldest = oldestRemote(remoteByName);
            if (oldest == null) {
                break;
            }
            deleteRemoteFile(oldest, remoteByName);
        }
    }

    private void uploadLocalBackup(
            Path local,
            String remoteName,
            String worldFolderId,
            Map<String, RemoteFile> remoteByName) throws IOException {
        Path uploadFile = local;
        Path tempZip = null;
        try {
            if (Files.isDirectory(local)) {
                tempZip = Files.createTempFile("chronos-gdrive-", ".zip");
                zipDirectory(local, tempZip);
                uploadFile = tempZip;
            } else if (!Files.isRegularFile(local)) {
                LOG.warn("Google Drive sync: skipping non-file backup " + local);
                return;
            }

            long bytes = Files.size(uploadFile);
            LOG.info("Google Drive uploading " + remoteName + " (" + bytes + " bytes)...");

            File meta = new File()
                    .setName(remoteName)
                    .setParents(Collections.singletonList(worldFolderId));

            FileContent content = new FileContent("application/zip", uploadFile.toFile());
            Drive.Files.Create create = drive.files()
                    .create(meta, content)
                    .setFields("id, name");
            MediaHttpUploader uploader = create.getMediaHttpUploader();
            uploader.setDirectUploadEnabled(false);
            uploader.setChunkSize(UPLOAD_CHUNK_SIZE);
            File created = create.execute();

            remoteByName.put(remoteName, new RemoteFile(created.getId(), remoteName));
            LOG.info("Google Drive uploaded " + remoteName);
        } finally {
            if (tempZip != null)
                Files.deleteIfExists(tempZip);
        }
    }

    private void deleteLocalBackup(Path local) throws IOException {
        if (Files.isDirectory(local)) {
            deleteDirectory(local);
        } else {
            Files.deleteIfExists(local);
        }

        LOG.info("Google Drive sync: removed local backup after upload: " + local.getFileName());
    }

    private void deleteRemoteFile(RemoteFile file, Map<String, RemoteFile> remoteByName)
            throws IOException {
        drive.files().delete(file.id).execute();
        remoteByName.remove(file.name);
        LOG.info("Google Drive deleted oldest remote backup: " + file.name);
    }

    private static RemoteFile oldestRemote(Map<String, RemoteFile> remoteByName) {
        RemoteFile oldest = null;
        long oldestKey = Long.MAX_VALUE;
        for (RemoteFile file : remoteByName.values()) {
            long key = ChronosBackupArtifacts.timestampSortKey(file.name);
            if (key < oldestKey) {
                oldestKey = key;
                oldest = file;
            }
        }
        return oldest;
    }

    private List<Path> listLocalChronosBackups(Path worldDir, String worldName) throws IOException {
        List<Path> out = new ArrayList<Path>();
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(worldDir)) {
            for (Path pa : dirStream) {
                String name = pa.getFileName().toString();
                if (!ChronosBackupArtifacts.isChronosBackupName(name, worldName))
                    continue;
                
                if (Files.isRegularFile(pa) && name.endsWith(".zip")) {
                    out.add(pa);
                } else if (Files.isDirectory(pa)) {
                    out.add(pa);
                }
            }
        }
        return out;
    }

    private Map<String, RemoteFile> listRemoteChronosBackups(String parentId, String worldName)
            throws IOException {
        Map<String, RemoteFile> out = new HashMap<String, RemoteFile>();
        String pageToken = null;
        do {
            checkCancelled();
            // I barely understand this
            FileList result = drive.files()
                    .list()
                    .setQ("'"
                            + escapeDriveQuery(parentId)
                            + "' in parents and trashed = false and mimeType != '"
                            + FOLDER_MIME
                            + "'")
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(id, name)")
                    .setPageToken(pageToken)
                    .execute();
            List<File> files = result.getFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (ChronosBackupArtifacts.isChronosBackupName(name, worldName)
                            && name.endsWith(".zip"))
                        out.put(name, new RemoteFile(f.getId(), name));
                }
            }
            pageToken = result.getNextPageToken();
        } while (pageToken != null);
        return out;
    }

    private String findOrCreateFolder(String parentId, String name) throws IOException {
        StringBuilder q = new StringBuilder();
        q.append("name = '").append(escapeDriveQuery(name)).append("'");
        q.append(" and mimeType = '").append(FOLDER_MIME).append("'");
        q.append(" and trashed = false");
        if (parentId == null) {
            q.append(" and 'root' in parents");
        } else {
            q.append(" and '").append(escapeDriveQuery(parentId)).append("' in parents");
        }

        FileList existing = drive.files()
                .list()
                .setQ(q.toString())
                .setSpaces("drive")
                .setFields("files(id, name)")
                .setPageSize(1)
                .execute();
        List<File> files = existing.getFiles();
        if (files != null && !files.isEmpty())
            return files.get(0).getId();

        File meta = new File().setName(name).setMimeType(FOLDER_MIME);
        if (parentId != null)
            meta.setParents(Collections.singletonList(parentId));

        File created = drive.files()
                .create(meta)
                .setFields("id")
                .execute();
        LOG.info("Google Drive created folder: " + name);
        return created.getId();
    }

    /**
     * Best-effort alias reservation after OAuth / token resume. Defers when no
     * world session is active yet.
     */
    public static void tryEnsureWorldFolderAliasSetup() {
        if (!ready || drive == null)
            return;
        
        BackupRuntimeContext context = Scheduler.tryGetRuntimeContext();
        if (context == null) {
            aliasSetupPending.set(true);
            LOG.info(
                    "Google Drive alias setup deferred until a world is loaded.");
            return;
        }
        try {
            INSTANCE.ensureWorldFolderAliasSetup(context.getWorldName());
        } catch (Exception e) {
            aliasSetupPending.set(true);
            LOG.error("Google Drive alias setup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Called from world start. Marks alias setup pending when needed so the
     * cloud sync worker (not the server thread) can reserve the remote folder.
     */
    @Override
    public void onWorldAvailable() {
        if (!ready || drive == null)
            return;

        if (!aliasSetupPending.get() && CloudBackupAlias.aliasFileExists())
            return;
        
        aliasSetupPending.set(true);
    }

    /**
     * @return {@code false} when alias reservation must wait for a world session
     *         (caller should skip sync without treating it as a hard failure)
     */
    private boolean ensureWorldFolderAliasSetup() throws IOException {
        if (CloudBackupAlias.aliasFileExists()) {
            BackupRuntimeContext context = Scheduler.tryGetRuntimeContext();
            if (context == null)
                // Alias known; remote folder ensure can wait for a world session.
                return true;
            
            ensureWorldFolderAliasSetup(context.getWorldName());
            return true;
        }
        BackupRuntimeContext context = Scheduler.tryGetRuntimeContext();
        if (context == null) {
            aliasSetupPending.set(true);
            return false;
        }
        ensureWorldFolderAliasSetup(context.getWorldName());
        return true;
    }

    private void ensureWorldFolderAliasSetup(String worldName) throws IOException {
        if (drive == null)
            throw new IOException("Google Drive is not ready");
        
        syncCancelRequested.set(false);

        String rootFolderId = findOrCreateFolder(null, REMOTE_ROOT_FOLDER_NAME);

        if (!CloudBackupAlias.aliasFileExists()) {
            Set<String> existing = listChildFolderNames(rootFolderId);
            String alias = CloudBackupAlias.pickAlias(existing, worldName);
            CloudBackupAlias.writeAlias(alias);
            if (alias.isEmpty()) {
                LOG.info(
                        "Google Drive world folder alias: none (using "
                                + ChronosBackupArtifacts.sanitizeWorldDirName(worldName)
                                + ")");
            } else {
                LOG.info("Google Drive world folder alias reserved: " + alias);
            }
        }

        String remoteFolder = CloudBackupAlias.remoteFolderName(worldName);
        findOrCreateFolder(rootFolderId, remoteFolder);
        aliasSetupPending.set(false);
        LOG.info("Google Drive ensured remote world folder: " + remoteFolder);
    }

    private Set<String> listChildFolderNames(String parentId) throws IOException {
        Set<String> names = new HashSet<String>();
        String pageToken = null;
        do {
            checkCancelled();
            FileList result = drive.files()
                    .list()
                    .setQ("'"
                            + escapeDriveQuery(parentId)
                            + "' in parents and trashed = false and mimeType = '"
                            + FOLDER_MIME
                            + "'")
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(id, name)")
                    .setPageToken(pageToken)
                    .execute();
            List<File> files = result.getFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName() != null) {
                        names.add(f.getName());
                    }
                }
            }
            pageToken = result.getNextPageToken();
        } while (pageToken != null);
        return names;
    }

    private void checkCancelled() throws IOException {
        if (syncCancelRequested.get())
            throw new IOException("Google Drive sync cancelled");
    }

    /** Escapes a string for use in a Google Drive query. */
    private static String escapeDriveQuery(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static void zipDirectory(Path sourceDir, Path zipOutputPath) throws IOException {
        Path zipRoot = sourceDir.toAbsolutePath().normalize();
        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(zipOutputPath))) {
            Files.walkFileTree(
                    zipRoot,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            Path relative = zipRoot.relativize(file.toAbsolutePath().normalize());
                            String entryName = relative.toString().replace('\\', '/');
                            zipOut.putNextEntry(new ZipEntry(entryName));
                            try (InputStream in = Files.newInputStream(file)) {
                                copy(in, zipOut);
                            }
                            zipOut.closeEntry();
                            return FileVisitResult.CONTINUE;
                        }
                    });
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (path == null || !Files.exists(path))
            return;
        
        Files.walkFileTree(
                path,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                            throws IOException {
                        Files.deleteIfExists(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    @Override
    public void shutdown() {
        authInProgress.set(false);
        syncCancelRequested.set(true);
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
                LOG.info(ChronosConstants.DIVIDER);
                LOG.info("Google Drive authorization");
                LOG.info("Open this URL in a browser, sign in, and allow Chronos Backups:");
                LOG.info(url);
                LOG.info(ChronosConstants.DIVIDER);
            }
        };
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver, consoleBrowser).authorize(USER_ID);
        drive = buildDriveService(credential);
        ready = true;
        LOG.info("Google Drive authorization completed successfully.");
        tryEnsureWorldFolderAliasSetup();
        CloudSync.requestSync();
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
        return new Drive.Builder(httpTransport, JSON_FACTORY, withUploadTimeouts(credential))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    /** Credential plus long connect/read timeouts for multi-hundred-MB resumable uploads. */
    private static HttpRequestInitializer withUploadTimeouts(final Credential credential) {
        return new HttpRequestInitializer() {
            @Override
            public void initialize(HttpRequest request) throws IOException {
                credential.initialize(request);
                request.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
                request.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            }
        };
    }

    private static GoogleAuthorizationCodeFlow buildFlow(NetHttpTransport httpTransport)
            throws IOException {
        return new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, buildClientSecrets(), SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(
                        Core.RunningDirectory.resolve(TOKENS_DIR_NAME).toFile()))
                .setAccessType("offline")
                .build();
    }

    private static final class RemoteFile {
        final String id;
        final String name;

        RemoteFile(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
