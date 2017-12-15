package com.joyent.manta.archiver;/*
 * Copyright (c) 2017, Joyent, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import com.joyent.manta.client.MantaClient;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

@Test
public class TransferManagerIT {
    private static final String MANTA_ROOT = "~~/stor/manta-archiver-test";
    private static final Supplier<MantaClient> MANTA_CLIENT_SUPPLIER =
            new MantaClientSupplier();

    @AfterMethod
    public void cleanUp() throws IOException {
        try (MantaClient client = MANTA_CLIENT_SUPPLIER.get()) {
            String home = client.getContext().getMantaHomeDirectory();

            client.deleteRecursive(MantaTransferClient.substituteHomeDirectory(
                    MANTA_ROOT, home));
        }
    }

    public void canTransferToManta() throws IOException {
        final Path root = Files.createTempDirectory("archiver-");
        FakeDirectoryStructureCreator.createFakeDirectoriesAndFiles(root);


        final TransferClient client = new MantaTransferClient(MANTA_CLIENT_SUPPLIER, MANTA_ROOT);

        try (TransferManager manager = new TransferManager(client, root)) {
            manager.uploadAll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}