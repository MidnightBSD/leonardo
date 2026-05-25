/*
 * Copyright 2026 The Leonardo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Operator CLI for offline management tasks: rotating API keys, clearing the
 * immutable flag on a bucket, dumping inventory, repairing metadata after a
 * crash, etc.
 *
 * <p>Subcommands are added in their respective milestones. M0 ships only the
 * skeleton + {@code version}.
 */
@Command(
        name = "leonardo-admin",
        mixinStandardHelpOptions = true,
        version = "leonardo-admin 0.1.0-SNAPSHOT",
        description = "Operator CLI for Leonardo S3-compatible object storage.",
        subcommands = {
                LeonardoAdminCli.VersionCommand.class
        }
)
public final class LeonardoAdminCli implements Callable<Integer> {

    public static void main(final String[] args) {
        final int exit = new CommandLine(new LeonardoAdminCli()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() {
        // No subcommand supplied: print usage. Picocli does this when we
        // return a non-zero code without action.
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @Command(name = "version", description = "Print the leonardo-admin version.")
    public static final class VersionCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            System.out.println("leonardo-admin 0.1.0-SNAPSHOT");
            return 0;
        }
    }
}
