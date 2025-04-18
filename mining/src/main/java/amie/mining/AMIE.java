/**
 * @author lgalarra
 * @date Aug 8, 2012 AMIE Version 0.1
 */
package amie.mining;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import amie.data.*;
import amie.data.remote.Caching;
import amie.mining.utils.AMIEOptions;
import org.apache.commons.cli.*;

import amie.mining.assistant.MiningAssistant;
import amie.mining.assistant.MiningAssistantFactory;
import amie.mining.assistant.DefaultMiningAssistantWithOrder;
import amie.mining.assistant.variableorder.AppearanceOrder;
import amie.mining.assistant.variableorder.FunctionalOrder;
import amie.mining.assistant.variableorder.InverseOrder;
import amie.mining.assistant.variableorder.VariableOrder;
import amie.rules.PruningMetric;
import amie.rules.Rule;
import it.unimi.dsi.fastutil.ints.IntCollection;
import amie.data.javatools.administrative.Announce;

import amie.data.javatools.datatypes.MultiMap;
import org.apache.commons.lang.StringUtils;

/**
 * Main class that implements the AMIE algorithm for rule mining on ontologies.
 * The ontology must be provided as a list of TSV files where each line has the
 * format SUBJECT&lt;TAB&gt;RELATION&lt;TAB&gt;OBJECT.
 *
 * @author lgalarra
 */
public class AMIE {

    /**
     * Default standard confidence threshold
     */
    protected static final double DEFAULT_STD_CONFIDENCE = 0.1;

    /**
     * Default PCA confidence threshold
     */
    protected static final double DEFAULT_PCA_CONFIDENCE = 0.1;

    /**
     * Default Head coverage threshold
     */
    protected static final double DEFAULT_HEAD_COVERAGE = 0.01;

    /**
     * The default minimum size for a relation to be used as a head relation
     */
    protected static final int DEFAULT_INITIAL_SUPPORT = 100;

    /**
     * The default support threshold
     */
    protected static final int DEFAULT_SUPPORT = 100;

    /**
     * It implements all the operators defined for the mining process: ADD-EDGE,
     * INSTANTIATION, SPECIALIZATION and CLOSE-CIRCLE
     */
    protected MiningAssistant assistant;

    /**
     * Support threshold for relations.
     */
    protected double minInitialSupport;

    /**
     * Threshold for refinements. It can hold either an absolute support number
     * or a head coverage threshold.
     */
    private double minSignificanceThreshold;

    /**
     * Metric used to prune the mining tree
     */
    protected PruningMetric pruningMetric;

    /**
     * Preferred number of threads
     */
    protected int nThreads;

    /**
     * If true, print the rules as they are discovered.
     */
    protected boolean realTime;

    /**
     * List of target head relations.
     */
    protected IntCollection seeds;
    protected Set<String> rulePrefixes = new HashSet<>();
    /**
     * Output stream for the rules (stdout by default)
     */
    protected PrintStream rulesOutputStream;

    /**
     * @param assistant         An object that implements the logic of the mining
     *                          operators.
     * @param minInitialSupport If head coverage is defined as pruning metric,
     *                          it is the minimum size for a relation to be
     *                          considered in the mining.
     * @param threshold         The minimum support threshold: it can be either a
     *                          head
     *                          coverage ratio threshold or an absolute number
     *                          depending on the 'metric'
     *                          argument.
     * @param metric            Head coverage or support.
     */
    public AMIE(MiningAssistant assistant, int minInitialSupport, double threshold, PruningMetric metric,
            int nThreads) {
        this.assistant = assistant;
        this.minInitialSupport = minInitialSupport;
        this.minSignificanceThreshold = threshold;
        this.pruningMetric = metric;
        this.nThreads = nThreads;
        this.realTime = true;
        this.seeds = null;
        this.rulesOutputStream = System.out;
    }

    public MiningAssistant getAssistant() {
        return assistant;
    }

    public boolean isVerbose() {
        return assistant.isVerbose();
    }

    public boolean isRealTime() {
        return realTime;
    }

    public void setRealTime(boolean realTime) {
        this.realTime = realTime;
    }

    public IntCollection getSeeds() {
        return seeds;
    }

    public void setSeeds(IntCollection seeds) {
        this.seeds = seeds;
        this.seeds = seeds;
    }

    /**
     * The key method which returns a set of rules mined from the KB based on
     * the AMIE object's configuration.
     *
     * @return
     * @throws Exception
     */
    public List<Rule> mine() throws Exception {
        List<Rule> result = new ArrayList<>();
        MultiMap<Integer, Rule> indexedResult = new MultiMap<>();
        RuleConsumer consumerObj = null;
        Thread consumerThread = null;
        Lock resultsLock = new ReentrantLock();
        Condition resultsCondVar = resultsLock.newCondition();
        Collection<Rule> seedRules = new ArrayList<>();

        // Queue initialization
        if (seeds == null || seeds.isEmpty()) {
            seedRules = assistant.getInitialAtoms(minInitialSupport);
        } else {
            seedRules = assistant.getInitialAtomsFromSeeds(seeds, minInitialSupport);
        }

        AMIEQueue queue = new AMIEQueue(seedRules, nThreads);

        if (realTime) {
            consumerObj = new RuleConsumer(result, resultsLock, resultsCondVar, this.rulesOutputStream);
            consumerThread = new Thread(consumerObj);
            consumerThread.start();
        }

        System.out.println("Using " + nThreads + " threads");
        // Create as many threads as available cores
        ArrayList<Thread> currentJobs = new ArrayList<>();
        ArrayList<RDFMinerJob> jobObjects = new ArrayList<>();
        for (int i = 0; i < nThreads; ++i) {
            RDFMinerJob jobObject = new RDFMinerJob(queue, result, resultsLock, resultsCondVar, indexedResult);
            Thread job = new Thread(jobObject);
            currentJobs.add(job);
            jobObjects.add(jobObject);

        }

        for (Thread job : currentJobs) {
            job.start();
        }

        for (Thread job : currentJobs) {
            job.join();
        }

        if (realTime) {
            consumerObj.terminate();
            consumerThread.join();
        }

        if (assistant.isVerbose())
            queue.printStats();

        for (Rule rule : result) {
            for (int[] triple : rule.getTriples()) {
                String subject = rule.kb.unmap(triple[0]);
                printRulePrefix(subject);
                String predicate = rule.kb.unmap(triple[1]);
                printRulePrefix(predicate);
                String object = rule.kb.unmap(triple[2]);
                printRulePrefix(object);
            }
        }
        return result;
    }

    private void printRulePrefix(String input) {
        List<String> prefixList = Arrays.asList(input.split(":"));
        for (String s : prefixList) {
            if (!rulePrefixes.contains(s) && StringUtils.isNotBlank(assistant.kb.schema.prefixMap.get(s))) {
                System.out.println(s + ":" + assistant.kb.schema.prefixMap.get(s));

                synchronized (rulePrefixes) {
                    rulePrefixes.add(s);
                }
            }
        }
    }

    /**
     * It removes and prints rules from a shared list (a list accessed by
     * multiple threads).
     *
     * @author galarrag
     */
    protected class RuleConsumer implements Runnable {

        protected List<Rule> consumeList;

        protected int lastConsumedIndex;

        protected Lock consumeLock;

        protected Condition conditionVariable;

        protected PrintStream outStream;

        public RuleConsumer(List<Rule> consumeList, Lock consumeLock, Condition conditionVariable,
                PrintStream outStream) {
            this.consumeList = consumeList;
            this.lastConsumedIndex = -1;
            this.consumeLock = consumeLock;
            this.conditionVariable = conditionVariable;
            this.outStream = outStream;
        }

        @Override
        public void run() {
            this.outStream.print(assistant.getFormatter().header());
            while (!Thread.currentThread().isInterrupted()) {
                consumeLock.lock();
                try {
                    while (lastConsumedIndex == consumeList.size() - 1) {
                        conditionVariable.await();
                        for (int i = lastConsumedIndex + 1; i < consumeList.size(); ++i) {
                            String outputStr = assistant.formatRule(consumeList.get(i));
                            this.outStream.println(outputStr);
                        }
                        lastConsumedIndex = consumeList.size() - 1;
                        if (done) {
                            consumeLock.unlock();
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    consumeLock.unlock();
                    this.outStream.flush();
                    break;
                }
            }
        }

        private boolean done = false;

        /**
         * Use to nicely terminate reader thread.
         */
        public void terminate() {
            consumeLock.lock();
            done = true;
            conditionVariable.signalAll();
            consumeLock.unlock();
            this.outStream.flush();

        }
    }

    /**
     * This class implements the AMIE algorithm in a single thread.
     *
     * @author lgalarra
     */
    protected class RDFMinerJob implements Runnable {

        protected List<Rule> outputSet;

        // A version of the output set thought for search.
        protected MultiMap<Integer, Rule> indexedOutputSet;

        protected AMIEQueue queryPool;

        protected Lock resultsLock;

        protected Condition resultsCondition;

        /**
         * @param seedsPool
         * @param outputSet
         * @param resultsLock      Lock associated to the output buffer were mined
         *                         rules are added
         * @param resultsCondition Condition variable associated to the results
         *                         lock
         * @param indexedOutputSet
         */
        public RDFMinerJob(AMIEQueue seedsPool,
                List<Rule> outputSet, Lock resultsLock,
                Condition resultsCondition,
                MultiMap<Integer, Rule> indexedOutputSet) {
            this.queryPool = seedsPool;
            this.outputSet = outputSet;
            this.resultsLock = resultsLock;
            this.resultsCondition = resultsCondition;
            this.indexedOutputSet = indexedOutputSet;
        }

        @Override
        public void run() {
            while (true) {
                Rule currentRule = null;
                try {
                    currentRule = queryPool.dequeue();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (currentRule == null) {
                    this.queryPool.decrementMaxThreads();
                    break;
                } else {
                    // Check if the rule meets the language bias and confidence thresholds and
                    // decide whether to output it.
                    boolean outputRule = false;
                    if (assistant.shouldBeOutput(currentRule)) {
                        boolean ruleSatisfiesConfidenceBounds = assistant
                                .calculateConfidenceBoundsAndApproximations(currentRule);
                        if (ruleSatisfiesConfidenceBounds) {
                            this.resultsLock.lock();
                            assistant.setAdditionalParents(currentRule, indexedOutputSet);
                            this.resultsLock.unlock();
                            // Calculate the metrics
                            assistant.calculateConfidenceMetrics(currentRule);
                            // Check the confidence threshold and skyline technique.
                            outputRule = assistant.testConfidenceThresholds(currentRule);
                        } else {
                            outputRule = false;
                        }
                    }

                    // Check if we should further refine the rule
                    boolean furtherRefined = !currentRule.isFinal();
                    if (assistant.isEnablePerfectRules()) {
                        furtherRefined = !currentRule.isPerfect();
                    }

                    // If so specialize it
                    if (furtherRefined) {
                        double threshold = getCountThreshold(currentRule);

                        // Application of the mining operators
                        Map<String, Collection<Rule>> temporalOutputMap = null;
                        try {
                            temporalOutputMap = assistant.applyMiningOperators(currentRule, threshold);
                        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                            e.printStackTrace();
                        }

                        for (Map.Entry<String, Collection<Rule>> entry : temporalOutputMap.entrySet()) {
                            String operator = entry.getKey();
                            Collection<Rule> items = entry.getValue();
                            if (!operator.equals("dangling")) {
                                queryPool.queueAll(items);
                            }
                        }

                        // Addition of the specializations to the queue
                        // queryPool.queueAll(temporalOutput);
                        if (currentRule.getRealLength() < assistant.getMaxDepth() - 1) {
                            if (temporalOutputMap.containsKey("dangling")) {
                                queryPool.queueAll(temporalOutputMap.get("dangling"));
                            }
                        }
                    }

                    // Output the rule
                    if (outputRule) {
                        this.resultsLock.lock();
                        Set<Rule> outputQueries = indexedOutputSet.get(currentRule.alternativeParentHashCode());
                        if (outputQueries != null) {
                            if (!outputQueries.contains(currentRule)) {
                                this.outputSet.add(currentRule);
                                outputQueries.add(currentRule);
                            } else {
                                throw new IllegalStateException("A query cannot be added twice");
                            }
                        } else {
                            this.outputSet.add(currentRule);
                            this.indexedOutputSet.put(currentRule.alternativeParentHashCode(), currentRule);
                        }
                        this.resultsCondition.signal();
                        this.resultsLock.unlock();
                    }
                }
            }
        }

        /**
         * Based on AMIE's configuration, it returns the absolute support
         * threshold that should be applied to the rule.
         *
         * @param query
         * @return
         */
        protected double getCountThreshold(Rule query) {
            switch (pruningMetric) {
                case Support:
                    return minSignificanceThreshold;
                case HeadCoverage:
                    return Math.ceil((minSignificanceThreshold
                            * (double) assistant.getHeadCardinality(query)));
                default:
                    return 0;
            }
        }
    }

    private static class InitElements {
        public HelpFormatter formatter = new HelpFormatter();
        // Create the command line parser and define the supported options
        public CommandLineParser parser = new PosixParser();
        public Options commandLineOptions = AMIEOptions.DefineArgOptions();
        public CommandLine cli = null;

        public Schema schema = null;
    }

    private static InitElements initFromArgs(String[] args) {

        InitElements initElements = new InitElements();

        try {
            initElements.cli = initElements.parser.parse(initElements.commandLineOptions, args);
        } catch (ParseException e) {
            System.out.println("Unexpected exception: " + e.getMessage());
            initElements.formatter.printHelp(AMIEOptions.AMIE_CMD_LINE_SYNTAX, initElements.commandLineOptions);
            System.exit(1);
        }

        if (!AMIEOptions.CheckForConflictingArguments(initElements.cli, initElements.commandLineOptions))
            System.exit(1);

        return initElements;
    }

    /**
     * Gets an instance of AMIE configured according to the command line
     * arguments.
     *
     * @param initElement
     * @return
     * @throws IOException
     * @throws InvocationTargetException
     * @throws IllegalArgumentException
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    public static AMIE getInstance(InitElements initElement)
            throws IOException, InstantiationException,
            IllegalAccessException, IllegalArgumentException,
            InvocationTargetException {

        // Setting up instance
        HelpFormatter formatter = initElement.formatter;

        // Create the command line parser and define the supported options
        Options options = initElement.commandLineOptions;
        CommandLine cli = initElement.cli;
        KB schemaSource = null;

        List<File> dataFiles = new ArrayList<>();
        List<File> targetFiles = new ArrayList<>();
        List<File> schemaFiles = new ArrayList<>();

        AbstractKB dataSource = new KB();

        // Caching
        if (cli.hasOption(AMIEOptions.CPOL.getOpt())) {
            Caching.EnableCache(cli.getOptionValue(AMIEOptions.CPOL.getOpt()));
        } else if (cli.hasOption(AMIEOptions.CSIZE.getOpt()) || cli.hasOption(AMIEOptions.CACHE.getOpt())) {
            Caching.EnableDefaultCache();
        }

        if (cli.hasOption(AMIEOptions.CSIZE.getOpt())) {
            Caching.SetScale(Integer.parseInt(cli.getOptionValue(AMIEOptions.CSIZE.getOpt())));
        } else if (Caching.IsEnabled()) {
            System.out.println("Unspecified cache scaling value. Using default: " + Caching.DEFAULT_CACHE_SIZE);
        }

        if (Caching.IsEnabled()) {
            System.out.println("Note: Query caching is enabled, but make sure communication layer relies on it.");
        }

        if (cli.hasOption(AMIEOptions.MULTILINGUAL.getOpt())) {
            dataSource = new MultilingualKB();
        }

        if (cli.hasOption(AMIEOptions.DELIMITER.getOpt())) {
            dataSource.setDelimiter(cli.getOptionValue(AMIEOptions.DELIMITER.getOpt()));
        }

        if (cli.hasOption(AMIEOptions.NO_KB_REWRITE.getOpt())) {
            dataSource.setOptimConnectedComponent(false);
        }

        if (cli.hasOption(AMIEOptions.NO_KB_EXISTS_DETECTION.getOpt())) {
            dataSource.setOptimExistentialDetection(false);
        }

        double minStdConf = DEFAULT_STD_CONFIDENCE;
        double minPCAConf = DEFAULT_PCA_CONFIDENCE;
        int minSup = DEFAULT_SUPPORT;
        int minInitialSup = DEFAULT_INITIAL_SUPPORT;
        double minHeadCover = DEFAULT_HEAD_COVERAGE;
        int maxDepth = 3;
        int maxDepthConst = 3;
        int recursivityLimit = 3;
        boolean realTime = true;
        String outputFormat = "default";
        boolean countAlwaysOnSubject = false;
        double minMetricValue = 0.0;
        boolean allowConstants = false;
        boolean enableConfidenceUpperBounds = true;
        boolean enableFunctionalityHeuristic = true;
        boolean verbose = false;
        boolean enforceConstants = false;
        boolean avoidUnboundTypeAtoms = true;
        boolean enableStdConfidence = false;
        boolean ommitPCAConfidence = false;
        boolean adaptiveInstantiations = false;
        /**
         * System performance measure *
         */
        boolean exploitMaxLengthForRuntime = true;
        boolean enableQueryRewriting = true;
        boolean enablePerfectRulesPruning = true;
        /**
         * ******************************
         */
        int nProcessors = Runtime.getRuntime().availableProcessors();
        String bias = AMIEOptions.Bias.LAZY; // Counting support on the two head variables.
        PruningMetric metric = PruningMetric.HeadCoverage; // Metric used to prune the search space.
        VariableOrder variableOrder = new FunctionalOrder();
        MiningAssistant mineAssistant = null;
        IntCollection bodyExcludedRelations = null;
        IntCollection headExcludedRelations = null;
        IntCollection instantiationExcludedRelations = null;
        IntCollection headTargetRelations = null;
        IntCollection bodyTargetRelations = null;
        IntCollection instantiationTargetRelations = null;
        String outputFilePath = null;

        int nThreads = nProcessors; // By default, use as many threads as processors.

        // These configurations override any other option
        boolean onlyOutput = cli.hasOption(AMIEOptions.ONLY_OUTPUT.getOpt());
        boolean full = cli.hasOption(AMIEOptions.FULL.getOpt());

        if (cli.hasOption(AMIEOptions.MIN_SUPPORT.getOpt())) {
            String minSupportStr = cli.getOptionValue(AMIEOptions.MIN_SUPPORT.getOpt());
            try {
                minSup = Integer.parseInt(minSupportStr);
            } catch (NumberFormatException e) {
                System.err.println("The option -mins (support threshold) requires an integer as argument");
                System.err.println(AMIEOptions.AMIE_PLUS_CMD_LINE_SYNTAX);
                formatter.printHelp(AMIEOptions.AMIE_PLUS, options);
                System.exit(1);
            }
        }

        if (cli.hasOption(AMIEOptions.MIN_INITIAL_SUPPORT.getOpt())) {
            String minInitialSupportStr = cli.getOptionValue(AMIEOptions.MIN_INITIAL_SUPPORT.getOpt());
            try {
                minInitialSup = Integer.parseInt(minInitialSupportStr);
            } catch (NumberFormatException e) {
                System.err.println("The option -minis (initial support threshold) requires an integer as argument");
                System.err.println(AMIEOptions.AMIE_PLUS_CMD_LINE_SYNTAX);
                formatter.printHelp(AMIEOptions.AMIE_PLUS, options);
                System.exit(1);
            }
        }

        if (cli.hasOption(AMIEOptions.MIN_HEAD_COVERAGE.getOpt())) {
            String minHeadCoverage = cli.getOptionValue(AMIEOptions.MIN_HEAD_COVERAGE.getOpt());
            try {
                minHeadCover = Double.parseDouble(minHeadCoverage);
            } catch (NumberFormatException e) {
                System.err.println("The option -minhc (head coverage threshold) requires a real number as argument");
                System.err.println(AMIEOptions.AMIE_CMD_LINE_SYNTAX);
                System.err.println(AMIEOptions.AMIE_PLUS_CMD_LINE_SYNTAX);
                System.exit(1);
            }
        }

        minMetricValue = minHeadCover;

        if (cli.hasOption(AMIEOptions.MIN_STD_CONFIDENCE.getOpt())) {
            String minConfidenceStr = cli.getOptionValue(AMIEOptions.MIN_STD_CONFIDENCE.getOpt());
            try {
                minStdConf = Double.parseDouble(minConfidenceStr);
            } catch (NumberFormatException e) {
                System.err.println("The option -minc (confidence threshold) requires a real number as argument");
                System.err.println(AMIEOptions.AMIE_CMD_LINE_SYNTAX);
                System.err.println(AMIEOptions.AMIE_PLUS_CMD_LINE_SYNTAX);
                System.exit(1);
            }
        }

        if (cli.hasOption(AMIEOptions.MIN_PCA_CONFIDENCE.getOpt())) {
            String minicStr = cli.getOptionValue(AMIEOptions.MIN_PCA_CONFIDENCE.getOpt());
            try {
                minPCAConf = Double.parseDouble(minicStr);
            } catch (NumberFormatException e) {
                System.err.println(
                        "The argument for option -minpca (PCA confidence threshold) must be an integer greater than 2");
                System.err.println(AMIEOptions.AMIE_CMD_LINE_SYNTAX);
                System.err.println(AMIEOptions.AMIE_PLUS_CMD_LINE_SYNTAX);
                System.exit(1);
            }
        }

        bodyExcludedRelations = dataSource.MapOptionValues(cli, AMIEOptions.BODY_EXCLUDED.getOpt());
        bodyTargetRelations = dataSource.MapOptionValues(cli, AMIEOptions.BODY_TARGET_RELATIONS.getOpt());
        headTargetRelations = dataSource.MapOptionValues(cli, AMIEOptions.HEAD_TARGET_RELATIONS.getOpt());
        headExcludedRelations = dataSource.MapOptionValues(cli, AMIEOptions.HEAD_EXCLUDED.getOpt());
        instantiationTargetRelations = dataSource.MapOptionValues(cli,
                AMIEOptions.INSTANTIATION_TARGET_RELATIONS.getOpt());
        instantiationExcludedRelations = dataSource.MapOptionValues(cli, AMIEOptions.INSTANTIATION_EXCLUDED.getOpt());

        if (cli.hasOption(AMIEOptions.MAX_DEPTH.getOpt())) {
            String maxDepthStr = cli.getOptionValue(AMIEOptions.MAX_DEPTH.getOpt());
            try {
                maxDepth = Integer.parseInt(maxDepthStr);
            } catch (NumberFormatException e) {
                System.err.println("The argument for option -maxad (maximum depth) must be an integer greater than 2");
                System.err.println(AMIEOptions.AMIE_CMD_LINE_SYNTAX);
                formatter.printHelp(AMIEOptions.AMIE_PLUS, options);
                System.exit(1);
            }

            if (maxDepth < 2) {
                System.err.println("The argument for option -maxad (maximum depth) must be greater or equal than 2");
                System.err.println(AMIEOptions.AMIE_CMD_LINE_SYNTAX);
                formatter.printHelp(AMIEOptions.AMIE_PLUS, options);
                System.exit(1);
            }
        }

        if (cli.hasOption(AMIEOptions.MAX_DEPTH_CONST.getOpt())) {
            String maxDepthConstStr = cli.getOptionValue(AMIEOptions.MAX_DEPTH_CONST.getOpt());
            try {
                maxDepthConst = Integer.parseInt(maxDepthConstStr);
            } catch (NumberFormatException e) {
                System.err.println(
                        "The argument for option -maxadc (maximum depth for rules with constants) must be an integer greater than 2");
                System.err.println(AMIEOptions.AMIE_CMD_LINE_SYNTAX);
                formatter.printHelp(AMIEOptions.AMIE_PLUS, options);
                System.exit(1);
            }

            if (maxDepthConst < 2) {
                System.err.println(
                        "The argument for option -maxad (maximum depth for rules with constants) must be greater or equal than 2");
                System.err.println(AMIEOptions.AMIE_CMD_LINE_SYNTAX);
                formatter.printHelp("AMIE+", options);
                System.exit(1);
            }
        }

        if (cli.hasOption(AMIEOptions.N_THREADS.getOpt())) {
            String nCoresStr = cli.getOptionValue(AMIEOptions.N_THREADS.getOpt());
            try {
                nThreads = Integer.parseInt(nCoresStr);
            } catch (NumberFormatException e) {
                System.err.println("The argument for option -nc (number of threads) must be an integer");
                System.err.println(AMIEOptions.AMIE_CMD_LINE_SYNTAX);
                System.err.println(AMIEOptions.AMIE_PLUS_CMD_LINE_SYNTAX);
                System.exit(1);
            }

            if (nThreads > nProcessors) {
                nThreads = nProcessors;
            }
        }

        if (cli.hasOption(AMIEOptions.VARIABLE_ORDER.getOpt())) {
            switch (cli.getOptionValue(AMIEOptions.VARIABLE_ORDER.getOpt())) {
                case "app":
                    variableOrder = new AppearanceOrder();
                    break;
                case "fun":
                    variableOrder = new FunctionalOrder();
                    break;
                case "ifun":
                    variableOrder = InverseOrder.of(new FunctionalOrder());
                    break;
                default:
                    System.err.println("The argument for option -vo must be among \"app\", \"fun\" and \"ifun\".");
                    System.exit(1);
            }
        }

        avoidUnboundTypeAtoms = cli.hasOption(AMIEOptions.AVOID_UNBOUND_TYPE_ATOMS.getOpt());
        exploitMaxLengthForRuntime = !cli.hasOption(AMIEOptions.DO_NOT_EXPLOIT_MAX_LENGTH.getOpt());
        enableQueryRewriting = !cli.hasOption(AMIEOptions.DISABLE_QUERY_REWRITING.getOpt());
        enablePerfectRulesPruning = !cli.hasOption(AMIEOptions.DISABLE_PERFECT_RULES.getOpt());
        String[] leftOverArgs = cli.getArgs();

        if (leftOverArgs.length < 1 && !AMIEOptions.isClientMode(cli)) {
            System.err.println("No input file has been provided");
            System.err.println(AMIEOptions.AMIE_CMD_LINE_SYNTAX);
            System.err.println(AMIEOptions.AMIE_PLUS_CMD_LINE_SYNTAX);
            System.exit(1);
        }

        // Enabling live metrics
        if (cli.hasOption(AMIEOptions.LIVE_METRICS.getOpt()) &&
                (AMIEOptions.isClientMode(cli) || AMIEOptions.isServerMode(cli)))
            AbstractKB.EnableLiveMetrics();

        // Formatting configuration identifier
        String config = AMIEOptions.FormatConfigIndentifier(cli);

        if ((AMIEOptions.isServerMode(cli) || AMIEOptions.isClientMode(cli))
                && cli.hasOption(AMIEOptions.INVALIDATE_CACHE.getOpt()))
            Caching.InvalidateCache();

        // Client
        if (AMIEOptions.isClientMode(cli)) {
            try {

                if (cli.hasOption(AMIEOptions.SERVER_ADDRESS.getOpt()))
                    AbstractKB.SetServerAddress(cli.getOptionValue(AMIEOptions.SERVER_ADDRESS.getOpt()));
                else
                    System.out.println("Unspecified server address ; using default " +
                            AbstractKB.DEFAULT_SERVER_ADDRESS);

                // See AbstractKB.NewKBClient description
                dataSource = AbstractKB.NewKBClient(config);
            } catch (Exception e) {
                System.err.println("Internal error while initiating KB client.");
                e.printStackTrace();
                System.exit(1);
            }
        } else {
            // Load database
            for (int i = 0; i < leftOverArgs.length; ++i) {
                if (leftOverArgs[i].startsWith(":t")) {
                    targetFiles.add(new File(leftOverArgs[i].substring(2)));
                } else if (leftOverArgs[i].startsWith(":s")) {
                    schemaFiles.add(new File(leftOverArgs[i].substring(2)));
                } else {
                    dataFiles.add(new File(leftOverArgs[i]));
                }
            }
            if (AMIEOptions.isServerMode(cli)) {
                if (cli.hasOption(AMIEOptions.PORT.getOpt()))
                    AbstractKB.SetPort(Integer.parseInt(cli.getOptionValue(AMIEOptions.PORT.getOpt())));
                else
                    System.out.println("Unspecified port ; using default " +
                            AbstractKB.DEFAULT_PORT);
                try {
                    // See AbstractKB.NewKBServer description
                    dataSource = AbstractKB.NewKBServer(config);
                } catch (Exception e) {
                    System.err.println("Internal error while initiating KB server.");
                    e.printStackTrace();
                    System.exit(1);
                }
            }
            ((KB) dataSource).load(dataFiles);
            KB targetSource;
            if (!targetFiles.isEmpty()) {
                targetSource = new KB();
                targetSource.load(targetFiles);
            }

            if (!schemaFiles.isEmpty()) {
                schemaSource = new KB();
                schemaSource.load(schemaFiles);
            }

            if (AMIEOptions.isServerMode(cli)) {
                return null;
            }
        }

        if (cli.hasOption(AMIEOptions.MIN_SUPPORT.getOpt()) != cli.hasOption(AMIEOptions.MIN_HEAD_COVERAGE.getOpt())) {
            if (cli.hasOption(AMIEOptions.MIN_SUPPORT.getOpt())) {
                metric = PruningMetric.Support;
                minMetricValue = minSup;
                if (!cli.hasOption(AMIEOptions.MIN_INITIAL_SUPPORT.getOpt())) {
                    minInitialSup = minSup;
                }
            } else {
                metric = PruningMetric.HeadCoverage;
                minMetricValue = minHeadCover;
            }
        }

        if (cli.hasOption(AMIEOptions.PRUNING_METRIC.getOpt())) {
            switch (cli.getOptionValue(AMIEOptions.PRUNING_METRIC.getOpt())) {
                case "support":
                    metric = PruningMetric.Support;
                    minMetricValue = minSup;
                    if (!cli.hasOption(AMIEOptions.MIN_INITIAL_SUPPORT.getOpt())) {
                        minInitialSup = minSup;
                    }
                    break;
                default:
                    metric = PruningMetric.HeadCoverage;
                    minMetricValue = minHeadCover;
                    break;
            }
        }
        System.out.println("Using " + metric + " as pruning metric with minimum threshold " + minMetricValue);

        if (cli.hasOption(AMIEOptions.BIAS.getOpt())) {
            bias = cli.getOptionValue(AMIEOptions.BIAS.getOpt());
        }

        verbose = cli.hasOption(AMIEOptions.VERBOSE.getOpt());

        if (cli.hasOption(AMIEOptions.RECURSIVITY_LIMIT.getOpt())) {
            try {
                recursivityLimit = Integer.parseInt(cli.getOptionValue(AMIEOptions.RECURSIVITY_LIMIT.getOpt()));
            } catch (NumberFormatException e) {
                System.err.println("The argument for option -rl (recursivity limit) must be an integer");
                System.err.println(AMIEOptions.AMIE_CMD_LINE_SYNTAX);
                System.err.println(AMIEOptions.AMIE_PLUS_CMD_LINE_SYNTAX);
                System.exit(1);
            }
        }
        System.out.println("Using recursivity limit " + recursivityLimit);

        enableConfidenceUpperBounds = cli.hasOption(AMIEOptions.OPTIM_CONFIDENCE_BOUNDS.getOpt());
        if (enableConfidenceUpperBounds) {
            System.out.println("Enabling standard and PCA confidences upper "
                    + "bounds for pruning");
        }

        enableFunctionalityHeuristic = cli.hasOption(AMIEOptions.OPTIM_FUNC_HEURISTIC.getOpt());
        mineAssistant = MiningAssistantFactory.getAssistantFactory().getAssistant(bias, dataSource, schemaSource);

        if (mineAssistant instanceof DefaultMiningAssistantWithOrder) {
            ((DefaultMiningAssistantWithOrder) mineAssistant).setVariableOrder(variableOrder);
        }

        allowConstants = cli.hasOption(AMIEOptions.ALLOW_CONSTANTS.getOpt());
        countAlwaysOnSubject = cli.hasOption(AMIEOptions.COUNT_ALWAYS_ON_SUBJECT.getOpt());
        realTime = !cli.hasOption(AMIEOptions.OUTPUT_AT_END.getOpt());
        enforceConstants = cli.hasOption(AMIEOptions.ONLY_CONSTANTS.getOpt());
        enableStdConfidence = cli.hasOption(AMIEOptions.ENABLE_STD_CONF.getOpt());
        ommitPCAConfidence = cli.hasOption(AMIEOptions.OMMIT_PCA_CONF.getOpt());
        adaptiveInstantiations = cli.hasOption(AMIEOptions.ADAPTATIVE_INSTANTIATIONS.getOpt());

        // These configurations override others
        if (onlyOutput) {
            System.out.println("Using the only output enhacements configuration.");
            enablePerfectRulesPruning = false;
            enableQueryRewriting = false;
            exploitMaxLengthForRuntime = false;
            enableConfidenceUpperBounds = true;
            enableFunctionalityHeuristic = true;
        }

        if (full) {
            System.out.println("Using the FULL configuration.");
            enablePerfectRulesPruning = true;
            enableQueryRewriting = true;
            exploitMaxLengthForRuntime = true;
            enableConfidenceUpperBounds = true;
            enableFunctionalityHeuristic = true;
        }

        if (cli.hasOption(AMIEOptions.NO_HEURISTICS.getOpt())) {
            enableFunctionalityHeuristic = false;
        }

        if (cli.hasOption(AMIEOptions.OUTPUT_FORMAT.getOpt())) {
            outputFormat = cli.getOptionValue(AMIEOptions.OUTPUT_FORMAT.getOpt());
        }

        if (enableFunctionalityHeuristic) {
            System.out.println("Enabling functionality heuristic with ratio "
                    + "for pruning of low confident rules");
            Announce.doing("Building overlap tables for confidence approximation...");
            long time = System.currentTimeMillis();
            ((KB) dataSource).buildOverlapTables(nThreads);
            Announce.done("Overlap tables computed in " + formatDuration(System.currentTimeMillis() - time)
                    + " using " + nThreads + " threads.");
        }

        // Setup Mining assistant
        mineAssistant.setEnabledConfidenceUpperBounds(enableConfidenceUpperBounds);
        mineAssistant.setEnabledFunctionalityHeuristic(enableFunctionalityHeuristic);
        mineAssistant.setMaxDepth(maxDepth);
        mineAssistant.setMaxDepthConst(maxDepthConst);
        mineAssistant.setStdConfidenceThreshold(minStdConf);
        mineAssistant.setPcaConfidenceThreshold(minPCAConf);
        mineAssistant.setAllowConstants(allowConstants);
        mineAssistant.setEnforceConstants(enforceConstants);
        mineAssistant.setBodyExcludedRelations(bodyExcludedRelations);
        mineAssistant.setHeadExcludedRelations(headExcludedRelations);
        mineAssistant.setInstantiationExcludedRelations(instantiationExcludedRelations);
        mineAssistant.setTargetBodyRelations(bodyTargetRelations);
        mineAssistant.setInstantiationTargetRelations(instantiationTargetRelations);
        mineAssistant.setCountAlwaysOnSubject(countAlwaysOnSubject);
        mineAssistant.setRecursivityLimit(recursivityLimit);
        mineAssistant.setAvoidUnboundTypeAtoms(avoidUnboundTypeAtoms);
        mineAssistant.setExploitMaxLengthOption(exploitMaxLengthForRuntime);
        mineAssistant.setEnableQueryRewriting(enableQueryRewriting);
        mineAssistant.setEnablePerfectRules(enablePerfectRulesPruning);
        mineAssistant.setVerbose(verbose);
        mineAssistant.setEnableStdConfidence(enableStdConfidence);
        mineAssistant.setOmmitPCAConfidence(ommitPCAConfidence);
        mineAssistant.setOptimAdaptiveInstantiations(adaptiveInstantiations);
        mineAssistant.setUseSkylinePruning(!cli.hasOption(AMIEOptions.NO_SKYLINE.getOpt()));
        mineAssistant.setFormatter(outputFormat);

        if (cli.hasOption(AMIEOptions.OUTPUT_FILE.getOpt())) {
            outputFilePath = cli.getOptionValue(AMIEOptions.OUTPUT_FILE.getOpt());
        }

        System.out.println(mineAssistant.getDescription());

        AMIE miner = new AMIE(mineAssistant, minInitialSup, minMetricValue, metric, nThreads);
        miner.setRealTime(realTime);
        miner.setSeeds(headTargetRelations);

        if (minStdConf > 0.0 && enableStdConfidence) {
            System.out.println("Filtering on standard confidence with minimum threshold " + minStdConf);
        } else {
            System.out.println("No minimum threshold on standard confidence");
        }

        if (minPCAConf > 0.0 && !ommitPCAConfidence) {
            System.out.println("Filtering on PCA confidence with minimum threshold " + minPCAConf);
        } else {
            System.out.println("No minimum threshold on PCA confidence");
        }

        String constantsStateMessage = "Constants in the arguments of relations are ";
        if (enforceConstants) {
            System.out.println(constantsStateMessage + "enforced");
        } else if (allowConstants) {
            System.out.println(constantsStateMessage + "enabled");
        } else {
            System.out.println(constantsStateMessage + "disabled");
        }

        if (exploitMaxLengthForRuntime && enableQueryRewriting && enablePerfectRulesPruning) {
            System.out.println("Lossless (query refinement) heuristics enabled");
        } else {
            if (!exploitMaxLengthForRuntime) {
                System.out.println("Pruning by maximum rule length disabled");
            }

            if (!enableQueryRewriting) {
                System.out.println("Query rewriting and caching disabled");
            }

            if (!enablePerfectRulesPruning) {
                System.out.println("Perfect rules pruning disabled");
            }
        }

        if (verbose) {
            mineAssistant.outputOperatorHierarchy(System.err);
        }

        if (outputFilePath != null) {
            try {
                miner.setRulesOutputStream(new PrintStream(outputFilePath));
                System.out.println("Writing rules to file " + outputFilePath);
            } catch (FileNotFoundException e) {
                System.err.println("The output file " + outputFilePath + " could not be found. Outputting" +
                        " the rules to stdout");
            }
        }

        return miner;
    }

    /**
     * It defines the stream on which the mined rules will be written.
     * By default this is System.out
     * 
     * @param outStream
     */
    public void setRulesOutputStream(PrintStream outStream) {
        this.rulesOutputStream = outStream;
    }

    public void outputHeader() {
        this.rulesOutputStream.println();
        this.rulesOutputStream.print(assistant.getFormatter().header());
    }

    public void outputRule(Rule rule) {
        this.rulesOutputStream.println(assistant.formatRule(rule));
    }

    public void closeOutput() {
        if (this.rulesOutputStream != System.out) {
            this.rulesOutputStream.close();
        }
    }

    private static String formatDuration(long durationInMillis) {
        final long millisInSecond = 1000;
        final long millisInMinute = millisInSecond * 60;
        final long millisInHour = millisInMinute * 60;
        final long millisInDay = millisInHour * 24;

        long days = durationInMillis / millisInDay;
        long remainingMillisAfterDays = durationInMillis % millisInDay;

        long hours = remainingMillisAfterDays / millisInHour;
        long remainingMillisAfterHours = remainingMillisAfterDays % millisInHour;

        long minutes = remainingMillisAfterHours / millisInMinute;
        long remainingMillisAfterMinutes = remainingMillisAfterHours % millisInMinute;

        long seconds = remainingMillisAfterMinutes / millisInSecond;
        long milliseconds = remainingMillisAfterMinutes % millisInSecond;

        StringBuilder formattedDuration = new StringBuilder();

        if (days > 0) {
            formattedDuration.append(days).append(" days ");
        }
        if (hours > 0 || formattedDuration.length() > 0) {
            formattedDuration.append(hours).append(" h ");
        }
        if (minutes > 0 || formattedDuration.length() > 0) {
            formattedDuration.append(minutes).append(" min ");
        }
        if (milliseconds > 0 || seconds > 0 || formattedDuration.length() > 0) {
            formattedDuration.append(String.format("%d.%03d s ", seconds, milliseconds));
        }

        return formattedDuration.toString().trim();
    }

    /**
     * AMIE's main program
     *
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        InitElements initElements = initFromArgs(args);

        if (initElements.cli.getArgs().length < 1
                && !(initElements.cli.hasOption(AMIEOptions.REMOTE_KB_MODE_CLIENT.getOpt())
                        || initElements.cli.hasOption(AMIEOptions.SERVER_ADDRESS.getOpt()))) {
            System.err.println("No input file has been provided");
            System.err.println(AMIEOptions.AMIE_CMD_LINE_SYNTAX);
            System.err.println(AMIEOptions.AMIE_PLUS_CMD_LINE_SYNTAX);
            System.exit(1);
        }

        Schema schema = new Schema();
        schema.loadSchemaConf();
        initElements.schema = schema;

        System.out.println("Assuming " + schema.unmap(schema.typeRelationBS) + " as type relation");
        long loadingStartTime = System.currentTimeMillis();

        AMIE miner = AMIE.getInstance(initElements);
        if (miner == null)
            return;

        long loadingTime = System.currentTimeMillis() - loadingStartTime;

        System.out.println("MRT calls: " + KB.STAT_NUMBER_OF_CALL_TO_MRT.get());
        Announce.doing("Starting the mining phase");

        long time = System.currentTimeMillis();
        List<Rule> rules = miner.mine();

        if (!miner.isRealTime()) {
            miner.outputHeader();
            for (Rule rule : rules) {
                miner.outputRule(rule);
            }
        }

        miner.closeOutput();

        long miningTime = System.currentTimeMillis() - time;
        System.out.println("Mining done in " + formatDuration(miningTime));
        Announce.done("Total time " + formatDuration(miningTime + loadingTime));
        System.out.println(rules.size() + " rules mined.");
    }

}
