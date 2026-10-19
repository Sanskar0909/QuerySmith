package com.querysmith;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(
        name = "querysmith",
        mixinStandardHelpOptions = true,
        version = "querysmith 0.1.0",
        description = "Finds the queries costing a Postgres database the most, and why.",
        subcommands = TopCommand.class)
public final class QuerySmith implements Runnable {

    @Spec
    CommandSpec spec;

    /** Runs when no subcommand is given. */
    @Override
    public void run() {
        throw new ParameterException(spec.commandLine(), "Missing command, e.g. 'querysmith top'");
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /**
     * The command line with its error handling, built here so tests run
     * exactly what main runs.
     *
     * A failure while running (database down, wrong password) prints one line
     * and exits 1, instead of a Java stack trace. Usage mistakes are picocli's
     * job: it prints the message and the help, and exits 2.
     */
    static CommandLine commandLine() {
        CommandLine cmd = new CommandLine(new QuerySmith());
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            commandLine.getErr().println("querysmith: " + ex.getMessage());
            return 1;
        });
        return cmd;
    }
}
