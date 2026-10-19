package com.querysmith;

import java.io.PrintWriter;
import java.io.StringWriter;

import picocli.CommandLine;

/** Runs the command line as `querysmith ...` would, capturing what it prints. */
final class Cli {

    record Run(int exitCode, String out, String err) {}

    private Cli() {}

    static Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = QuerySmith.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        int exitCode = cmd.execute(args);
        return new Run(exitCode, out.toString(), err.toString());
    }
}
