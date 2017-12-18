/*
 * Copyright (c) 2017, Joyent, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.joyent.manta.archiver;

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarStyle;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TransferQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Class responsible for managing the ingestion of the compressed file queue
 * and the allocation of uploader threads.
 */
public class TransferManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TransferManager.class);

    private static final int EXECUTOR_SHUTDOWN_WAIT_SECS = 5;

    private static final int WAIT_MILLIS_FOR_COMPLETION_CHECK = 1000;

    private final TransferClient client;
    private final Path localRoot;

    /**
     * Creates a new instance backed by a transfer client mapped to a remote
     * filesystem root path and a local filesystem root path.
     *
     * @param client client used to transfer files
     * @param localRoot local filesystem working directory
     */
    public TransferManager(final TransferClient client, final Path localRoot) {
        this.client = client;

        if (localRoot != null) {
            this.localRoot = localRoot.toAbsolutePath().normalize();
        } else {
            this.localRoot = null;
        }
    }

    /**
     * Uploads all the files from the local working directory to the remote
     * working directory. It won't upload files that are identical, but it will
     * overwrite files that are different.
     *
     * @throws InterruptedException thrown when a blocking operation is interrupted
     */
    @SuppressWarnings("EmptyStatement")
    void uploadAll() throws InterruptedException {
        final ForkJoinPool loaderPool = new ForkJoinPool(
                ForkJoinPool.getCommonPoolParallelism(),
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                new LoggingUncaughtExceptionHandler("ObjectCompressorThreadPool"),
                true);

        final int concurrentUploaders = Math.max(client.getMaximumConcurrentConnections() - 2, 1);
        final ExecutorService uploaderExecutor = Executors.newFixedThreadPool(
                concurrentUploaders, new NamedThreadFactory(
                        "uploader-thread-%d", "uploaders",
                        "UploaderThreadPool"));

        final int noOfinitialAsyncObjectsToProcess = concurrentUploaders * 2;
        final ObjectUploadQueueLoader loader = new ObjectUploadQueueLoader(loaderPool,
                noOfinitialAsyncObjectsToProcess);
        final TransferQueue<ObjectUpload> queue = loader.getQueue();

        final TotalTransferDetails transferDetails = loader.uploadDirectoryContents(localRoot);
        final long noOfObjectToUpload = transferDetails.numberOfObjects;

        if (noOfObjectToUpload < 1) {
            return;
        }

        System.err.println("Maven Archiver - Upload");
        System.err.println();

        System.err.printf("Bulk upload to Manta : [%s] --> [%s]%s",
                localRoot, client.getRemotePath(), System.lineSeparator());
        System.err.printf("Total files to upload: %d%s", transferDetails.numberOfObjects,
                System.lineSeparator());
        System.err.printf("Total size to upload : %s (%d)%s",
                FileUtils.byteCountToDisplaySize(transferDetails.numberOfBytes),
                transferDetails.numberOfBytes, System.lineSeparator());

        System.err.println();

        final String uploadMsg = "Uploading";
        final ProgressBar pb = new ProgressBar(uploadMsg,
                transferDetails.numberOfBytes, ProgressBarStyle.ASCII);

        final AtomicLong totalUploads = new AtomicLong();
        final Runnable uploader = new ObjectUploadRunnable(totalUploads,
                queue, noOfObjectToUpload, client, localRoot, pb);

        pb.start();

        for (int i = 0; i < concurrentUploaders; i++) {
            uploaderExecutor.execute(uploader);
        }

        LOG.debug("Shutting down object compression thread pool");
        loaderPool.shutdown();
        while (!loaderPool.awaitTermination(EXECUTOR_SHUTDOWN_WAIT_SECS, TimeUnit.SECONDS));

        LOG.debug("Shutting down object uploader thread pool");
        uploaderExecutor.shutdown();
        while (!uploaderExecutor.awaitTermination(EXECUTOR_SHUTDOWN_WAIT_SECS, TimeUnit.SECONDS));

        if (totalUploads.get() != noOfObjectToUpload) {
            pb.stop();

            String msg = "Actual number of objects uploads differs from expected number";
            TransferClientException e = new TransferClientException(msg);
            e.setContextValue("expectNumberOfUploads", noOfObjectToUpload);
            e.setContextValue("actualNumberOfUploads", totalUploads.get());
            throw e;
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("All uploads [{}] have completed", totalUploads.get(),
                    noOfObjectToUpload);
        }

        pb.stop();
    }

    /**
     * Downloads all files from a remote Manta path into the local working
     * directory.
     *
     * @throws InterruptedException thrown when a blocking operation is interrupted
     */
    void downloadAll() throws InterruptedException {
        final int concurrentDownloaders = Math.max(client.getMaximumConcurrentConnections() - 2, 1);
        final ExecutorService downloadExecutor = Executors.newFixedThreadPool(
                concurrentDownloaders, new NamedThreadFactory(
                        "download-thread-%d", "downloaders",
                        "DownloaderThreadPool"));

        System.err.println("Maven Archiver - Download");
        System.err.println();

        final AtomicBoolean verificationSuccess = new AtomicBoolean(true);
        final AtomicLong totalObjects = new AtomicLong(0L);
        final AtomicLong totalObjectsProcessed = new AtomicLong(0L);

        try (Stream<FileDownload> downloads = client.find()) {
            downloads.forEach(remoteObject -> {
                totalObjects.incrementAndGet();

                final Path path = client.convertRemotePathToLocalPath(remoteObject.getRemotePath(), localRoot);
                final File file = path.toFile();

                if (remoteObject.isDirectory()) {
                    if (file.exists() && file.lastModified() != remoteObject.getLastModified()) {
                        if (!file.setLastModified(remoteObject.getLastModified())) {
                            LOG.warn("Unable to set last modified time for directory: {}",
                                    file);
                        }
                    } else {
                        file.mkdirs();
                    }

                    totalObjectsProcessed.incrementAndGet();
                } else {
                    if (!file.exists()) {
                        final Path parent = path.getParent();
                        if (!parent.toFile().exists()) {
                            parent.toFile().mkdirs();
                        }
                    }

                    Runnable download = new ObjectDownloadRunnable(
                            path, client, remoteObject.getRemotePath(), verificationSuccess, totalObjectsProcessed);
                    downloadExecutor.execute(download);
                }
            });
        }

        downloadExecutor.shutdown();

        while (totalObjectsProcessed.get() < totalObjects.get()) {
            downloadExecutor.awaitTermination(EXECUTOR_SHUTDOWN_WAIT_SECS, TimeUnit.SECONDS);
        }

        System.err.println();
        System.err.printf("Downloaded %d/%d objects%s",
                totalObjectsProcessed.get(), totalObjects.get(), System.lineSeparator());
    }

    /**
     * Verifies that all of the files in the specified local directory
     * and subdirectories are identical to the files on Manta.
     *
     * @return true when all files verified successfully
     * @throws InterruptedException thrown when a blocking operation is interrupted
     */
    boolean verifyLocal() throws InterruptedException {
        System.err.println("Maven Archiver - Verify Local");
        System.err.println();

        final AtomicBoolean verificationSuccess = new AtomicBoolean(true);
        final int statusMsgSize = 19;

        final String format = "[%s] %s <-> %s" + System.lineSeparator();

        try (Stream<Path> contents = LocalFileUtils.directoryContentsStream(localRoot)) {
            contents.forEach(localPath -> {
                String mantaPath = client.convertLocalPathToRemotePath(localPath, localRoot);

                final VerificationResult result;

                if (localPath.toFile().isDirectory()) {
                    result = client.verifyDirectory(mantaPath);
                } else {
                    final File file = localPath.toFile();
                    final byte[] checksum = LocalFileUtils.checksum(localPath);
                    result = client.verifyFile(mantaPath, file.length(), checksum);
                }

                if (verificationSuccess.get() && !result.equals(VerificationResult.OK)) {
                    verificationSuccess.set(false);
                }

                System.err.printf(format, StringUtils.center(result.toString(), statusMsgSize),
                        localPath, mantaPath);
            });
        }

        return verificationSuccess.get();
    }

    /**
     * Verifies that all of the files in the specified remote directory
     * and subdirectories are identical to the files on Manta.
     *
     * @return true when all files verified successfully
     * @throws InterruptedException thrown when a blocking operation is interrupted
     */
    boolean verifyRemote() throws InterruptedException {
        final int concurrentVerifiers = Math.max(client.getMaximumConcurrentConnections() - 2, 1);
        final ExecutorService verifyExecutor = Executors.newFixedThreadPool(
                concurrentVerifiers, new NamedThreadFactory(
                        "verify-thread-%d", "verifiers",
                        "VerifierThreadPool"));

        System.err.println("Maven Archiver - Verify Remote");
        System.err.println();

        final AtomicBoolean verificationSuccess = new AtomicBoolean(true);

        final String format = "[%s] %s" + System.lineSeparator();

        final AtomicLong totalFiles = new AtomicLong(0L);
        final AtomicLong totalFilesProcessed = new AtomicLong(0L);

        try (Stream<FileDownload> files = client.find();
             OutputStream out = new NullOutputStream()) {
            // Only process files and no directories
            files.filter(f -> !f.isDirectory()).forEach(file -> {
                totalFiles.incrementAndGet();

                final Runnable verify = () -> {
                    final VerificationResult result = client.download(
                            file.getRemotePath(), out, Optional.empty());

                    if (verificationSuccess.get() && !result.equals(VerificationResult.OK)) {
                        verificationSuccess.set(false);
                    }

                    String centered = StringUtils.center(result.toString(), VerificationResult.MAX_STRING_SIZE);
                    System.err.printf(format, centered, file);

                    totalFilesProcessed.incrementAndGet();
                };

                verifyExecutor.execute(verify);
            });
        } catch (IOException e) {
            String msg = "Unable to verify remote files";
            TransferClientException tce = new TransferClientException(msg, e);
            throw tce;
        }

        verifyExecutor.shutdown();

        while (totalFilesProcessed.get() != totalFiles.get()) {
            verifyExecutor.awaitTermination(EXECUTOR_SHUTDOWN_WAIT_SECS, TimeUnit.SECONDS);
        }

        System.err.printf("%d/%d files verified%s", totalFilesProcessed.get(), totalFiles.get(),
                System.lineSeparator());

        return verificationSuccess.get();
    }

    @SuppressWarnings("EmptyStatement")
    @Override
    public void close() {
        client.close();
    }
}
