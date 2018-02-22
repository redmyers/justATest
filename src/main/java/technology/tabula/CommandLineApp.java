package technology.tabula;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.DefaultParser;
import org.apache.pdfbox.pdmodel.PDDocument;

import technology.tabula.detectors.DetectionAlgorithm;
import technology.tabula.detectors.NurminenDetectionAlgorithm;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;
import technology.tabula.writers.CSVWriter;
import technology.tabula.writers.JSONWriter;
import technology.tabula.writers.TSVWriter;
import technology.tabula.writers.Writer;
import technology.tabula.detectors.RegexSearch;

/*
 * CommandLineApp
 *
 *    TODO: Small blurb about this class
 *
 *    TODO: Large blurb about this class
 *    2/20/2018 BHT, edited.
 */

public class CommandLineApp {

    private static String VERSION = "1.0.3";
    private static String VERSION_STRING = String.format("tabula %s (c) 2012-2017 Manuel Aristarán", VERSION);
    private static String BANNER = "\nTabula helps you extract tables from PDFs\n\n";

    private Appendable defaultOutput;
    private Rectangle pageArea;
    private List<Integer> pages;
    private OutputFormat outputFormat;
    private String password;
    private TableExtractor tableExtractor;

    public CommandLineApp(Appendable defaultOutput, CommandLine line) throws ParseException {
        this.defaultOutput = defaultOutput;
        this.pageArea = CommandLineApp.whichArea(line); //TODO: incorporate found rectangles into whichArea
        this.pages = CommandLineApp.whichPages(line);
        this.outputFormat = CommandLineApp.whichOutputFormat(line);
        this.tableExtractor = CommandLineApp.createExtractor(line);

        if (line.hasOption('s')) {
            this.password = line.getOptionValue('s');
        }
    }

    public static void main(String[] args) {
        // creates DefaultParser object
        CommandLineParser parser = new DefaultParser();
        try {
            // parse the command line arguments --> collects options and puts into variable 'line'
            CommandLine line = parser.parse(buildOptions(), args);

            if (line.hasOption('h')) {
                printHelp();
                System.exit(0);
            }

            if (line.hasOption('v')) {
                System.out.println(VERSION_STRING);
                System.exit(0);
            }

            // TODO: Figure out a way to parse the five arguments needed for RegexSearch from CLI
            // TODO: Find a way to parse multiple REGEX pairs on CLI
            // TODO: Figure out how -a/--area argument is handled
            // TODO: Figure out which JSON-to-JAVA API to use (most likely GSON)
            // TODO: Find best area to put -r/--regex option (leave here? or move it?)
            if (line.hasOption('r')){
                // HARDCODED for testing/debugging purposes
                System.out.println("Reached the regex option.");

                //Loading existing document
                File file = new File("C:/Users/tenja/Desktop/Test_PDFs/Test.pdf");

                try {
                    PDDocument testdoc = PDDocument.load(file);
                    RegexSearch rs = new RegexSearch("From:", "0",
                            "To:", "0", testdoc);
                    // String extractedContent = "";
                    // TODO: key-value pairs being sent to CLI, now have to parse for coords
                    // TODO: Figure out how Rectangles are parsed
                    List<Rectangle> ma = rs.getAllMatchingAreas();
                    System.out.println(ma);
                }
                catch (IOException e) {
                    e.printStackTrace();
                }

                System.exit(0);
            }
            // where the magic happens (I think)
            new CommandLineApp(System.out, line).extractTables(line);
        } catch (ParseException exp) {
            System.err.println("Error: " + exp.getMessage());
            System.exit(1);
        }
        System.exit(0);
    }

    // begin the table extraction
    public void extractTables(CommandLine line) throws ParseException {
        if (line.hasOption('b')) {
            if (line.getArgs().length != 0) {
                throw new ParseException("Filename specified with batch\nTry --help for help");
            }

            File pdfDirectory = new File(line.getOptionValue('b'));
            if (!pdfDirectory.isDirectory()) {
                throw new ParseException("Directory does not exist or is not a directory");
            }
            extractDirectoryTables(line, pdfDirectory);
            return;
        }

        if (line.getArgs().length != 1) {
            throw new ParseException("Need exactly one filename\nTry --help for help");
        }

        File pdfFile = new File(line.getArgs()[0]);
        if (!pdfFile.exists()) {
            throw new ParseException("File does not exist");
        }
        extractFileTables(line, pdfFile);
    }

    public void extractDirectoryTables(CommandLine line, File pdfDirectory) throws ParseException {
        File[] pdfs = pdfDirectory.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".pdf");
            }
        });

        for (File pdfFile : pdfs) {
            File outputFile = new File(getOutputFilename(pdfFile));
            extractFileInto(pdfFile, outputFile);
        }
    }

    public void extractFileTables(CommandLine line, File pdfFile) throws ParseException {
        Appendable outFile = this.defaultOutput;
        if (!line.hasOption('o')) {
            extractFile(pdfFile, this.defaultOutput);
            return;
        }

        File outputFile = new File(line.getOptionValue('o'));
        extractFileInto(pdfFile, outputFile);
    }

    public void extractFileInto(File pdfFile, File outputFile) throws ParseException {
        BufferedWriter bufferedWriter = null;
        try {
            FileWriter fileWriter = new FileWriter(outputFile.getAbsoluteFile());
            bufferedWriter = new BufferedWriter(fileWriter);

            outputFile.createNewFile();
            extractFile(pdfFile, bufferedWriter);
        } catch (IOException e) {
            throw new ParseException("Cannot create file " + outputFile);
        } finally {
            if (bufferedWriter != null) {
                try {
                    bufferedWriter.close();
                } catch (IOException e) {
                    System.out.println("Error in closing the BufferedWriter" + e);
                }
            }
        }
    }

    private void extractFile(File pdfFile, Appendable outFile) throws ParseException {
        PDDocument pdfDocument = null;
        try {
            pdfDocument = this.password == null ? PDDocument.load(pdfFile) : PDDocument.load(pdfFile, this.password);
            PageIterator pageIterator = getPageIterator(pdfDocument);
            List<Table> tables = new ArrayList<>();

            while (pageIterator.hasNext()) {
                Page page = pageIterator.next();

                if (pageArea != null) {
                    page = page.getArea(pageArea);
                }

                tables.addAll(tableExtractor.extractTables(page));
            }
            writeTables(tables, outFile);
        } catch (IOException e) {
            throw new ParseException(e.getMessage());
        } finally {
            try {
                if (pdfDocument != null) {
                    pdfDocument.close();
                }
            } catch (IOException e) {
                System.out.println("Error in closing pdf document" + e);
            }
        }
    }

    private PageIterator getPageIterator(PDDocument pdfDocument) throws IOException {
        ObjectExtractor extractor = new ObjectExtractor(pdfDocument);
        return (pages == null) ?
                extractor.extract() :
                extractor.extract(pages);
    }

    // CommandLine parsing methods

    private static OutputFormat whichOutputFormat(CommandLine line) throws ParseException {
        if (!line.hasOption('f')) {
            return OutputFormat.CSV;
        }

        try {
            return OutputFormat.valueOf(line.getOptionValue('f'));
        } catch (IllegalArgumentException e) {
            throw new ParseException(String.format(
                    "format %s is illegal. Available formats: %s",
                    line.getOptionValue('f'),
                    Utils.join(",", OutputFormat.formatNames())));
        }
    }

    private static Rectangle whichArea(CommandLine line) throws ParseException {
        if (!line.hasOption('a')) {
            return null;
        }

        List<Float> f = parseFloatList(line.getOptionValue('a'));
        if (f.size() != 4) {
            throw new ParseException("area parameters must be top,left,bottom,right");
        }
        return new Rectangle(f.get(0), f.get(1), f.get(3) - f.get(1), f.get(2) - f.get(0));
    }

    private static List<Integer> whichPages(CommandLine line) throws ParseException {
        String pagesOption = line.hasOption('p') ? line.getOptionValue('p') : "1";
        return Utils.parsePagesOption(pagesOption);
    }

    private static ExtractionMethod whichExtractionMethod(CommandLine line) {
        // -r/--spreadsheet [deprecated, -r used for regex] or -l/--lattice
        if (/*line.hasOption('r') || */line.hasOption('l')) {
            return ExtractionMethod.SPREADSHEET;
        }

        // -n/--no-spreadsheet [deprecated -r used for regex; use -t] or  -c/--columns or -g/--guess or -t/--stream
        // NOTE: from personal experience, -g/--guess does not work very well (outputted blank csv files)
        if (/*line.hasOption('n') || */line.hasOption('c') || line.hasOption('g') || line.hasOption('t')) {
            return ExtractionMethod.BASIC;
        }
        return ExtractionMethod.DECIDE;
    }

    // TableExtractor goes through 4 methods to return params to needed to extract tables
    private static TableExtractor createExtractor(CommandLine line) throws ParseException {
        TableExtractor extractor = new TableExtractor();
        // setGuess sees if user wants areas-to-be-analyzed to be 'guessed' by Tabula
        // from personal experience, -g--guess does not work very well (outputted blank csv files)
        extractor.setGuess(line.hasOption('g'));
        // setMethod sees which extractor should be used (user picks SPREADSHEET or BASIC, or the method DECIDEs for you)
        extractor.setMethod(CommandLineApp.whichExtractionMethod(line));
        // setUseLineReturns checks if the use-line-returns option was selected
        extractor.setUseLineReturns(line.hasOption('u'));
        // setVerticalRulingPosition checks if column (verticalRulingPositions) option was selected
        if (line.hasOption('c')) {
            extractor.setVerticalRulingPositions(parseFloatList(line.getOptionValue('c')));
        }
        return extractor;
    }

    // utilities, etc.

    public static List<Float> parseFloatList(String option) throws ParseException {
        String[] f = option.split(",");
        List<Float> rv = new ArrayList<>();
        try {
            for (int i = 0; i < f.length; i++) {
                rv.add(Float.parseFloat(f[i]));
            }
            return rv;
        } catch (NumberFormatException e) {
            throw new ParseException("Wrong number syntax");
        }
    }

    private static void printHelp() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("tabula", BANNER, buildOptions(), "", true);
    }

    public static Options buildOptions() {
        Options o = new Options();

        o.addOption("v", "version", false, "Print version and exit.");
        o.addOption("h", "help", false, "Print this help text.");
        o.addOption("g", "guess", false, "Guess the portion of the page to analyze per page.");
        o.addOption("n", "no-spreadsheet", false, "[Deprecated in favor of -t/--stream] Force PDF not to be extracted using spreadsheet-style extraction (if there are no ruling lines separating each cell)");
        o.addOption("l", "lattice", false, "Force PDF to be extracted using lattice-mode extraction (if there are ruling lines separating each cell, as in a PDF of an Excel spreadsheet)");
        o.addOption("t", "stream", false, "Force PDF to be extracted using stream-mode extraction (if there are no ruling lines separating each cell)");
        o.addOption("i", "silent", false, "Suppress all stderr output.");
        o.addOption("u", "use-line-returns", false, "Use embedded line returns in cells. (Only in spreadsheet mode.)");
        o.addOption("d", "debug", false, "Print detected table areas instead of processing.");
        o.addOption(Option.builder("b")
                .longOpt("batch")
                .desc("Convert all .pdfs in the provided directory.")
                .hasArg()
                .argName("DIRECTORY")
                .build());
        o.addOption(Option.builder("o")
                .longOpt("outfile")
                .desc("Write output to <file> instead of STDOUT. Default: -")
                .hasArg()
                .argName("OUTFILE")
                .build());
        o.addOption(Option.builder("f")
                .longOpt("format")
                .desc("Output format: (" + Utils.join(",", OutputFormat.formatNames()) + "). Default: CSV")
                .hasArg()
                .argName("FORMAT")
                .build());
        o.addOption(Option.builder("s")
                .longOpt("password")
                .desc("Password to decrypt document. Default is empty")
                .hasArg()
                .argName("PASSWORD")
                .build());
        o.addOption(Option.builder("c")
                .longOpt("columns")
                .desc("X coordinates of column boundaries. Example --columns 10.1,20.2,30.3")
                .hasArg()
                .argName("COLUMNS")
                .build());
        o.addOption(Option.builder("a")
                .longOpt("area")
                .desc("Portion of the page to analyze (top,left,bottom,right). Example: --area 269.875,12.75,790.5,561. Default is entire page")
                .hasArg()
                .argName("AREA")
                .build());
        o.addOption(Option.builder("p")
                .longOpt("pages")
                .desc("Comma separated list of ranges, or all. Examples: --pages 1-3,5-7, --pages 3 or --pages all. Default is --pages 1")
                .hasArg()
                .argName("PAGES")
                .build());
        o.addOption(Option.builder("r")
                .longOpt("regex")
                .desc("Find areas to extract using regex. Example: --regex regexbefore,incl/excl,regexafter,incl/excl")
                .hasArg()
                .argName("REGEX")
                .build());

        return o;
    }

    private static class TableExtractor {
        private boolean guess = false;
        private boolean useLineReturns = false;
        private BasicExtractionAlgorithm basicExtractor = new BasicExtractionAlgorithm();
        private SpreadsheetExtractionAlgorithm spreadsheetExtractor = new SpreadsheetExtractionAlgorithm();
        private List<Float> verticalRulingPositions = null;
        private ExtractionMethod method = ExtractionMethod.BASIC;

        public TableExtractor() {
        }

        public void setVerticalRulingPositions(List<Float> positions) {
            this.verticalRulingPositions = positions;
        }

        public void setGuess(boolean guess) {
            this.guess = guess;
        }

        public void setUseLineReturns(boolean useLineReturns) {
            this.useLineReturns = useLineReturns;
        }

        public void setMethod(ExtractionMethod method) {
            this.method = method;
        }

        public List<Table> extractTables(Page page) {
            ExtractionMethod effectiveMethod = this.method;
            if (effectiveMethod == ExtractionMethod.DECIDE) {
                effectiveMethod = spreadsheetExtractor.isTabular(page) ?
                        ExtractionMethod.SPREADSHEET :
                        ExtractionMethod.BASIC;
            }
            switch (effectiveMethod) {
                case BASIC:
                    return extractTablesBasic(page);
                case SPREADSHEET:
                    return extractTablesSpreadsheet(page);
                default:
                    return new ArrayList<>();
            }
        }

        public List<Table> extractTablesBasic(Page page) {
            if (guess) {
                // guess the page areas to extract using a detection algorithm
                // currently we only have a detector that uses spreadsheets to find table areas
                DetectionAlgorithm detector = new NurminenDetectionAlgorithm();
                List<Rectangle> guesses = detector.detect(page);
                List<Table> tables = new ArrayList<>();

                for (Rectangle guessRect : guesses) {
                    Page guess = page.getArea(guessRect);
                    tables.addAll(basicExtractor.extract(guess));
                }
                return tables;
            }

            if (verticalRulingPositions != null) {
                return basicExtractor.extract(page, verticalRulingPositions);
            }
            return basicExtractor.extract(page);
        }

        public List<Table> extractTablesSpreadsheet(Page page) {
            // TODO add useLineReturns
            return spreadsheetExtractor.extract(page);
        }
    }

    private void writeTables(List<Table> tables, Appendable out) throws IOException {
        Writer writer = null;
        switch (outputFormat) {
            case CSV:
                writer = new CSVWriter();
                break;
            case JSON:
                writer = new JSONWriter();
                break;
            case TSV:
                writer = new TSVWriter();
                break;
        }
        writer.write(out, tables);
    }

    private String getOutputFilename(File pdfFile) {
        String extension = ".csv";
        switch (outputFormat) {
            case CSV:
                extension = ".csv";
                break;
            case JSON:
                extension = ".json";
                break;
            case TSV:
                extension = ".tsv";
                break;
        }
        return pdfFile.getPath().replaceFirst("(\\.pdf|)$", extension);
    }

    private enum OutputFormat {
        CSV,
        TSV,
        JSON;

        static String[] formatNames() {
            OutputFormat[] values = OutputFormat.values();
            String[] rv = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                rv[i] = values[i].name();
            }
            return rv;
        }
    }

    private enum ExtractionMethod {
        BASIC,
        SPREADSHEET,
        DECIDE
    }

    private class DebugOutput {
        private boolean debugEnabled;

        public DebugOutput(boolean debug) {
            this.debugEnabled = debug;
        }

        public void debug(String msg) {
            if (this.debugEnabled) {
                System.err.println(msg);
            }
        }
    }
}
