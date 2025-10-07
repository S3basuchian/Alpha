package at.ac.tuwien.kr.alpha;

import at.ac.tuwien.kr.alpha.api.Alpha;
import at.ac.tuwien.kr.alpha.api.Solver;
import at.ac.tuwien.kr.alpha.api.config.AlphaConfig;
import at.ac.tuwien.kr.alpha.api.config.InputConfig;
import at.ac.tuwien.kr.alpha.api.impl.AlphaImpl;
import at.ac.tuwien.kr.alpha.api.programs.ASPCore2Program;
import at.ac.tuwien.kr.alpha.app.config.CommandLineParser;
import org.apache.commons.cli.ParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class IncrementalMain {
    private static final String DEFAULT_COMMAND_LINE = "java -jar Alpha-bundled.jar";
    private static final Consumer<String> DEFAULT_ABORT_ACTION = (msg) -> { };

    public static void main(String[] args) throws ParseException, IOException {

        CommandLineParser parser = new CommandLineParser(DEFAULT_COMMAND_LINE, DEFAULT_ABORT_ACTION);
        AlphaConfig ctx = parser.parseCommandLine(new String[] {"-i", "examples/innocence.asp"});
        Alpha alpha = new AlphaImpl(ctx.getSystemConfig());
        ASPCore2Program program = alpha.readProgram(ctx.getInputConfig());

        alpha.solve(program).findFirst().ifPresent(answerSet -> {
            System.out.println("Answer set: " + answerSet);
        });
    }

    private static List<String> instantiateStep(int t) throws IOException {
        List<String> instantiatedLines = new ArrayList<>();
        List<String> templateLines = Files.readAllLines(Paths.get("examples/increment/step_template.asp"));

        for (String line : templateLines) {
            String instantiated = line.replaceAll("@@T@@", String.valueOf(t));
            instantiatedLines.add(instantiated);
        }

        return instantiatedLines;
    }

    private static void exitWithMessage(String msg, int exitCode) {
        System.out.println(msg);
        System.exit(exitCode);
    }
}
