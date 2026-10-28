package de.tum.bgu.msm.health.regression;

import de.tum.bgu.msm.health.RunExposureHealthOffline;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Regression test of the health exposure model on the small test area created by CreateRegressionTestArea:
 * 1. runs RunExposureHealthOffline on the test area
 * 2. saves the outputs to regression/runs/[time]_[git commit]_[label]
 * 3. compares them with the previous run (or the run given as baseline) and writes comparison.txt
 *
 * Run with the test area directory as working directory, e.g. F:/models/jibe/manchester_test
 * Usage: RunExposureRegressionTest [label] [baselineRunName]
 */
public class RunExposureRegressionTest {

    private static final String PROPERTIES = "siloMCR_reference.properties";
    private static final String CONFIG = "configBase.xml";
    private static final Path RUNS = Paths.get("regression", "runs");

    public static void main(String[] args) throws IOException {
        String label = args.length > 0 ? args[0].replaceAll("[^A-Za-z0-9_-]", "-") : "";
        String baselineName = args.length > 1 ? args[1] : null;
        if (!Files.exists(Paths.get("regressionTestArea.txt"))) {
            throw new IllegalStateException("Working directory " + Paths.get("").toAbsolutePath() + " is not a regression test area (see CreateRegressionTestArea)");
        }
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(Paths.get(PROPERTIES))) {
            properties.load(in);
        }
        Path scenarioOutput = Paths.get("scenOutput", properties.getProperty("scenario.name").trim());

        String commit = gitCommit();
        String runName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + "_" + commit + (label.isEmpty() ? "" : "_" + label);
        Path runDir = RUNS.resolve(runName);

        Instant start = Instant.now();
        RunExposureHealthOffline.main(new String[]{PROPERTIES, CONFIG});
        Duration runtime = Duration.between(start, Instant.now());

        saveOutputs(scenarioOutput, runDir, start);
        try (BufferedWriter out = Files.newBufferedWriter(runDir.resolve("run.txt"), StandardCharsets.UTF_8)) {
            out.write("commit: " + commit + "\nlabel: " + label + "\nruntime: " + runtime.toMinutes() + " min " + runtime.toSecondsPart() + " s\n");
        }

        Path baseline = baselineName != null ? RUNS.resolve(baselineName) : previousRun(runDir);
        if (baseline == null) {
            System.out.println("First regression run saved to " + runDir.toAbsolutePath() + ", nothing to compare with yet.");
            return;
        }
        CompareExposureRuns comparison = new CompareExposureRuns(baseline, runDir);
        comparison.setBackground(Double.parseDouble(properties.getProperty("background.pm25").trim()),
                Double.parseDouble(properties.getProperty("background.no2").trim()));
        String report = comparison.compare();
        Files.writeString(runDir.resolve("comparison.txt"), report, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("Run took " + runtime.toMinutes() + " min " + runtime.toSecondsPart() + " s. Outputs and comparison saved to " + runDir.toAbsolutePath());
    }

    // copies all files written by this run, i.e. modified since it started; inputs in the scenario folder are not touched by the model
    private static void saveOutputs(Path scenarioOutput, Path runDir, Instant start) throws IOException {
        Files.createDirectories(runDir);
        Files.walkFileTree(scenarioOutput, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.lastModifiedTime().toInstant().isAfter(start.minusSeconds(1))) {
                    Path copy = runDir.resolve(scenarioOutput.relativize(file));
                    Files.createDirectories(copy.getParent());
                    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path previousRun(Path runDir) throws IOException {
        try (Stream<Path> runs = Files.list(RUNS)) {
            List<Path> previous = runs.filter(Files::isDirectory)
                    .filter(run -> !run.equals(runDir))
                    .sorted()
                    .collect(Collectors.toList());
            return previous.isEmpty() ? null : previous.get(previous.size() - 1);
        }
    }

    // short commit of the code that was run, "+dirty" if there are uncommitted changes
    private static String gitCommit() {
        try {
            Path dir = Paths.get(RunExposureRegressionTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            while (dir != null && !Files.exists(dir.resolve(".git"))) {
                dir = dir.getParent();
            }
            if (dir == null) return "nogit";
            String commit = git(dir, "rev-parse", "--short", "HEAD");
            return git(dir, "status", "--porcelain", "--untracked-files=no").isEmpty() ? commit : commit + "+dirty";
        } catch (Exception e) {
            return "nogit";
        }
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        process.waitFor();
        return output;
    }
}
