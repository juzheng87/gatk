/*
 * Copyright (c) 2010 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers.varianteval;

import org.apache.log4j.Logger;
import org.broad.tribble.util.variantcontext.MutableVariantContext;
import org.broad.tribble.util.variantcontext.VariantContext;
import org.broad.tribble.vcf.VCFConstants;
import org.broad.tribble.vcf.VCFWriter;
import org.broad.tribble.vcf.VCFHeader;
import org.broad.tribble.vcf.VCFHeaderLine;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.contexts.variantcontext.VariantContextUtils;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.ReferenceOrderedDataSource;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.refdata.utils.helpers.DbSNPHelper;
import org.broadinstitute.sting.gatk.walkers.Reference;
import org.broadinstitute.sting.gatk.walkers.RodWalker;
import org.broadinstitute.sting.gatk.walkers.Window;
import org.broadinstitute.sting.gatk.walkers.TreeReducible;
import org.broadinstitute.sting.gatk.walkers.variantrecalibration.Tranche;
import org.broadinstitute.sting.gatk.walkers.variantrecalibration.VariantRecalibrator;
import org.broadinstitute.sting.utils.SampleUtils;
import org.broadinstitute.sting.utils.report.ReportMarshaller;
import org.broadinstitute.sting.utils.report.VE2ReportFactory;
import org.broadinstitute.sting.utils.report.templates.ReportFormat;
import org.broadinstitute.sting.utils.report.utils.Node;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;
import org.broadinstitute.sting.utils.classloader.PluginManager;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.commandline.Argument;
import org.broadinstitute.sting.commandline.Output;
import org.broadinstitute.sting.utils.exceptions.DynamicClassResolutionException;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.text.XReadLines;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.util.*;

// todo -- evalations should support comment lines
// todo -- add Mendelian variable explanations (nDeNovo and nMissingTransmissions)

// todo -- site frequency spectrum eval (freq. of variants in eval as a function of their AC and AN numbers)
// todo -- clustered SNP counter
// todo -- HWEs
// todo -- indel metrics [count of sizes in/del should be in CountVariants]

// todo -- port over SNP density walker:
// todo -- see walker for WG calc but will need to make it work with intervals correctly

// Todo -- should really include argument parsing @annotations from subclass in this walker.  Very
// todo -- useful general capability.  Right now you need to add arguments to VariantEval2 to handle new
// todo -- evaluation arguments (which is better than passing a string!)

// todo -- these really should be implemented as default select expression
// todo Extend VariantEval, our general-purpose tool for SNP evaluation, to differentiate Ti/Tv at CpG islands and also
// todo classify (and count) variants into coding, non-coding, synonomous/non-symonomous, 2/4 fold degenerate sites, etc.
// todo Assume that the incoming VCF has the annotations (you don't need to do this) but VE2 should split up results by
// todo these catogies automatically (using the default selects)

// todo -- this is really more a documentation issue.  Really would be nice to have a pre-defined argument packet that
// todo -- can be provided to the system
// todo -- We agreed to report two standard values for variant evaluation from here out. One, we will continue to report
// todo -- the dbSNP 129 rate. Additionally, we will start to report the % of variants found that have already been seen in
// todo -- 1000 Genomes. This should be implemented as another standard comp_1kg binding, pointing to only variants
// todo -- discovered and released by 1KG.  Might need to make this data set ourselves and keep it in GATK/data like
// todo -- dbsnp rod
//
// todo -- implement as select statment, but it's hard for multi-sample calls.
// todo -- Provide separate dbsnp rates for het only calls and any call where there is at least one hom-var genotype,
// todo -- since hets are much more likely to be errors
//
// todo -- Add Heng's hom run metrics -- single sample haplotype block lengths


/**
 * General-purpose tool for variant evaluation (% in dbSNP, genotype concordance, Ts/Tv ratios, and a lot more)
 */
@Reference(window=@Window(start=-50,stop=50))
public class VariantEvalWalker extends RodWalker<Integer, Integer> implements TreeReducible<Integer> {
    @Output
    protected PrintStream out;

    // --------------------------------------------------------------------------------------------------------------
    //
    // walker arguments
    //
    // --------------------------------------------------------------------------------------------------------------

    @Argument(shortName="select", doc="One or more stratifications to use when evaluating the data", required=false)
    protected ArrayList<String> SELECT_EXPS = new ArrayList<String>();

    @Argument(shortName="selectName", doc="Names to use for the list of stratifications (must be a 1-to-1 mapping)", required=false)
    protected ArrayList<String> SELECT_NAMES = new ArrayList<String>();

    @Argument(shortName="known", doc="Name of ROD bindings containing variant sites that should be treated as known when splitting eval rods into known and novel subsets", required=false)
    protected String[] KNOWN_NAMES = {DbSNPHelper.STANDARD_DBSNP_TRACK_NAME};

    @Argument(shortName="sample", doc="Derive eval and comp contexts using only these sample genotypes, when genotypes are available in the original context", required=false)
    protected String[] SAMPLES = {};
    private List<String> SAMPLES_LIST = null;

    //
    // Arguments for choosing which modules to run
    //
    @Argument(fullName="evalModule", shortName="E", doc="One or more specific eval modules to apply to the eval track(s) (in addition to the standard modules, unless -noStandard is specified)", required=false)
    protected String[] modulesToUse = {};

    @Argument(fullName="doNotUseAllStandardModules", shortName="noStandard", doc="Do not use the standard modules by default (instead, only those that are specified with the -E option)")
    protected Boolean NO_STANDARD = false;

    @Argument(fullName="list", shortName="ls", doc="List the available eval modules and exit")
    protected Boolean LIST = false;

    //
    // Arguments for Mendelian Violation calculations
    //
    @Argument(shortName="family", doc="If provided, genotypes in will be examined for mendelian violations: this argument is a string formatted as dad+mom=child where these parameters determine which sample names are examined", required=false)
    protected String FAMILY_STRUCTURE;

    @Argument(shortName="MVQ", fullName="MendelianViolationQualThreshold", doc="Minimum genotype QUAL score for each trio member required to accept a site as a violation", required=false)
    protected double MENDELIAN_VIOLATION_QUAL_THRESHOLD = 50;

    @Output(shortName="outputVCF", fullName="InterestingSitesVCF", doc="If provided, interesting sites emitted to this vcf and the INFO field annotated as to why they are interesting", required=false)
    protected VCFWriter writer = null;

    @Argument(shortName="gcLog", fullName="GenotypeCocordanceLog", doc="If provided, sites with genotype concordance problems (e.g., FP and FNs) will be emitted ot this file", required=false)
    protected PrintStream gcLog = null;

    private static double NO_MIN_QUAL_SCORE = -1.0;
    @Argument(shortName = "Q", fullName="minPhredConfidenceScore", doc="Minimum confidence score to consider an evaluation SNP a variant", required=false)
    public double minQualScore = NO_MIN_QUAL_SCORE;
    @Argument(shortName = "Qcomp", fullName="minPhredConfidenceScoreForComp", doc="Minimum confidence score to consider a comp SNP a variant", required=false)
    public double minCompQualScore = NO_MIN_QUAL_SCORE;
    @Argument(shortName = "dels", fullName="indelCalls", doc="evaluate indels rather than SNPs", required = false)
    public boolean dels = false;

    // Right now we will only be looking at SNPS
    // todo -- enable INDEL variant contexts, there's no reason not to but the integration tests need to be updated
    EnumSet<VariantContext.Type> ALLOW_VARIANT_CONTEXT_TYPES = EnumSet.of(VariantContext.Type.SNP, VariantContext.Type.NO_VARIATION);

    @Argument(shortName="rsID", fullName="rsID", doc="If provided, list of rsID and build number for capping known snps by their build date", required=false)
    protected String rsIDFile = null;

    @Argument(shortName="maxRsIDBuild", fullName="maxRsIDBuild", doc="If provided, only variants with rsIDs <= maxRsIDBuild will be included in the set of known snps", required=false)
    protected int maxRsIDBuild = Integer.MAX_VALUE;

    @Argument(shortName="reportType", fullName="reportType", doc="If provided, set the template type", required=false)
    protected VE2ReportFactory.VE2TemplateType reportType = VE2ReportFactory.defaultReportFormat;

    @Output(shortName="reportLocation", fullName="reportLocation", doc="If provided, set the base file for reports (Required for output formats with more than one file per analysis)", required=false)
    protected File outputLocation = null;

    @Argument(shortName="nSamples", fullName="nSamples", doc="If provided, analyses that need the number of samples in an eval track that has no genotype information will receive this number as the number of samples", required=false)
    protected int nSamples = -1;

    Set<String> rsIDsToExclude = null;

    @Argument(shortName="aatk", fullName="aminoAcidTransitionKey", doc="required for the amino acid transition table; this is the key in the info field for the VCF for the transition", required = false)
    protected String aminoAcidTransitionKey = null;

    @Argument(shortName="aats", fullName="aminoAcidTransitionSplit", doc="required for the amino acid transition table, this is the key on which to split the info field value to get the reference and alternate amino acids", required=false)
    protected String aminoAcidTransitionSplit = null;

    @Argument(shortName="aatUseCodons", fullName="aminoAcidsRepresentedByCodons", doc="for the amino acid table, specifiy that the transitions are represented as codon changes, and not directly amino acid names", required = false)
    protected boolean aatUseCodons = false;

    @Argument(shortName="disI", fullName="discordantInteresting", doc="If passed, write discordant sites as interesting", required=false)
    protected boolean DISCORDANT_INTERESTING = false;

    @Argument(fullName="tranchesFile", shortName="tf", doc="The input tranches file describing where to cut the data", required=false)
    private String TRANCHE_FILENAME = null;

    // For GenotypePhasingEvaluator:
    @Argument(fullName = "minPhaseQuality", shortName = "minPQ", doc = "The minimum phasing quality (PQ) score required to consider phasing; [default:0]", required = false)
    protected Double minPhaseQuality = 0.0; // accept any positive value of PQ

    @Argument(shortName="min", fullName="minimalComparisons", doc="If passed, filters and raw site values won't be computed", required=false)
    protected boolean MINIMAL = false;


    // --------------------------------------------------------------------------------------------------------------
    //
    // private walker data
    //
    // --------------------------------------------------------------------------------------------------------------

    /** private class holding all of the information about a single evaluation group (e.g., for eval ROD) */
    public class EvaluationContext implements Comparable<EvaluationContext> {
        // useful for typing
        public String evalTrackName, compTrackName, novelty, filtered;
        public boolean enableInterestingSiteCaptures = false;
        VariantContextUtils.JexlVCMatchExp selectExp;
        Set<VariantEvaluator> evaluations;

        public boolean isIgnoringFilters()      { return filtered.equals(RAW_SET_NAME); }
        public boolean requiresFiltered()       { return filtered.equals(FILTERED_SET_NAME); }
        public boolean requiresNotFiltered()    { return filtered.equals(RETAINED_SET_NAME); }
        public boolean isIgnoringNovelty()      { return novelty.equals(ALL_SET_NAME); }
        public boolean requiresNovel()          { return novelty.equals(NOVEL_SET_NAME); }
        public boolean requiresKnown()          { return novelty.equals(KNOWN_SET_NAME); }

        public boolean isSelected() { return selectExp == null; }

        public String getDisplayName() {
            return Utils.join(CONTEXT_SEPARATOR, Arrays.asList(evalTrackName, compTrackName, selectExp == null ? "all" : selectExp.name, filtered, novelty));
        }

        public String toString() { return getDisplayName(); }

        public int compareTo(EvaluationContext other) {
            return this.getDisplayName().compareTo(other.getDisplayName());
        }

        public EvaluationContext( String evalName, String compName, String novelty, String filtered, VariantContextUtils.JexlVCMatchExp selectExp ) {
            this.evalTrackName = evalName;
            this.compTrackName = compName;
            this.novelty = novelty;
            this.filtered = filtered;
            this.selectExp = selectExp;
            this.enableInterestingSiteCaptures = selectExp == null;
            this.evaluations = instantiateEvalationsSet();
        }
    }

    private List<EvaluationContext> contexts = null;

    // lists of all comp and eval ROD track names
    private Set<String> compNames = new HashSet<String>();
    private Set<String> evalNames = new HashSet<String>();

    private List<String> variantEvaluationNames = new ArrayList<String>();

    private static String RAW_SET_NAME      = "raw";
    private static String RETAINED_SET_NAME = "called";
    private static String FILTERED_SET_NAME = "filtered";
    private static String ALL_SET_NAME      = "all";
    private static String KNOWN_SET_NAME    = "known";
    private static String NOVEL_SET_NAME    = "novel";

    private static String NO_COMP_NAME = "N/A";

    private final static String CONTEXT_SEPARATOR = "XXX";
    //private final static String CONTEXT_SEPARATOR = "\\.";
    private final static String CONTEXT_HEADER = Utils.join(CONTEXT_SEPARATOR, Arrays.asList("eval", "comp", "select", "filter", "novelty"));
    private final static int N_CONTEXT_NAME_PARTS = CONTEXT_HEADER.split(CONTEXT_SEPARATOR).length;
    private static int[] nameSizes = new int[N_CONTEXT_NAME_PARTS];
    static {
        int i = 0;
        for ( String elt : CONTEXT_HEADER.split(CONTEXT_SEPARATOR) )
            nameSizes[i++] = elt.length();
    }

    // Dynamically determined variantEvaluation classes
    private Set<Class<? extends VariantEvaluator>> evaluationClasses = null;

    // --------------------------------------------------------------------------------------------------------------
    //
    // initialize
    //
    // --------------------------------------------------------------------------------------------------------------

    public boolean printInterestingSites() { return writer != null; }

    public void initialize() {
        if ( dels ) {
            ALLOW_VARIANT_CONTEXT_TYPES = EnumSet.of(VariantContext.Type.INDEL, VariantContext.Type.NO_VARIATION);
        }
        ReportFormat.AcceptableOutputType type = (outputLocation == null) ? ReportFormat.AcceptableOutputType.STREAM : ReportFormat.AcceptableOutputType.FILE;
        if (!VE2ReportFactory.isCompatibleWithOutputType(type,reportType))
            throw new UserException.CommandLineException("The report format requested is not compatible with your output location.  You specified a " + type + " output type which isn't an option for " + reportType);
        if ( LIST )
            listModulesAndExit();

        SAMPLES_LIST = SampleUtils.getSamplesFromCommandLineInput(Arrays.asList(SAMPLES));

        determineEvalations();

        if ( TRANCHE_FILENAME != null ) {
            // we are going to build a few select names automatically from the tranches file
            for ( Tranche t : Tranche.readTraches(new File(TRANCHE_FILENAME)) ) {
                logger.info("Adding select for all variant above the pCut of : " + t);
                SELECT_EXPS.add(String.format(VariantRecalibrator.VQS_LOD_KEY + " >= %.2f", t.minVQSLod));
                SELECT_NAMES.add(String.format("FDR-%.2f", t.fdr));
            }
        }

        if ( SELECT_NAMES.size() > 0 ) {
            logger.info("Selects: " + SELECT_NAMES);
            logger.info("Selects: " + SELECT_EXPS);
        }
        List<VariantContextUtils.JexlVCMatchExp> selectExps = VariantContextUtils.initializeMatchExps(SELECT_NAMES, SELECT_EXPS);

        for ( ReferenceOrderedDataSource d : this.getToolkit().getRodDataSources() ) {
            if ( d.getName().startsWith("eval") ) {
                evalNames.add(d.getName());
            } else if ( d.getName().startsWith("comp") ) {
                compNames.add(d.getName());
            } else if ( d.getName().startsWith(DbSNPHelper.STANDARD_DBSNP_TRACK_NAME) || d.getName().startsWith("hapmap") ) {
                compNames.add(d.getName());
            } else {
                logger.info("Not evaluating ROD binding " + d.getName());
            }
        }

        // if no comp rod was provided, we still want to be able to do evaluations, so use a default comp name
        if ( compNames.size() == 0 )
            compNames.add(NO_COMP_NAME);

        contexts = initializeEvaluationContexts(evalNames, compNames, selectExps);
        determineContextNamePartSizes();

        if ( rsIDFile != null ) {
            if ( maxRsIDBuild == Integer.MAX_VALUE )
                throw new IllegalArgumentException("rsIDFile " + rsIDFile + " was given but associated max RSID build parameter wasn't available");
            rsIDsToExclude = getrsIDsToExclude(new File(rsIDFile), maxRsIDBuild);
        }

        if ( writer != null ) {
            Set<String> samples = SampleUtils.getUniqueSamplesFromRods(getToolkit(), evalNames);
            final VCFHeader vcfHeader = new VCFHeader(new HashSet<VCFHeaderLine>(),  samples);
            writer.writeHeader(vcfHeader);
        }
    }

    private void listModulesAndExit() {
        List<Class<? extends VariantEvaluator>> veClasses = new PluginManager<VariantEvaluator>( VariantEvaluator.class ).getPlugins();
        out.println("\nAvailable eval modules:");
        out.println("(Standard modules are starred)");
        for (Class<? extends VariantEvaluator> veClass : veClasses)
            out.println("\t" + veClass.getSimpleName() + (StandardEval.class.isAssignableFrom(veClass) ? "*" : ""));
        out.println();
        System.exit(0);
    }

    private static Set<String> getrsIDsToExclude(File rsIDFile, int maxRsIDBuild) {
        List<String> toExclude = new LinkedList<String>();

        int n = 1;
        try {
            for ( String line : new XReadLines(rsIDFile) ) {
                String parts[] = line.split(" ");
                if ( parts.length != 2 )
                    throw new UserException.MalformedFile(rsIDFile, "Invalid rsID / build pair at " + n + " line = " + line );
                //System.out.printf("line %s %s %s%n", line, parts[0], parts[1]);
                if ( Integer.valueOf(parts[1]) > maxRsIDBuild ) {
                    //System.out.printf("Excluding %s%n", line);
                    toExclude.add("rs"+parts[0]);
                }
                n++;

                if ( n % 1000000 == 0 )
                    logger.info(String.format("Read %d rsIDs from rsID -> build file", n));
            }
        } catch (FileNotFoundException e) {
            throw new UserException.CouldNotReadInputFile(rsIDFile, e);
        }

        logger.info(String.format("Excluding %d of %d (%.2f%%) rsIDs found from builds > %d",
                toExclude.size(), n, ((100.0 * toExclude.size())/n), maxRsIDBuild));

        return new HashSet<String>(toExclude);
    }

    private boolean excludeComp(VariantContext vc) {
        String id = vc != null && vc.hasID() ? vc.getID() : null;
        boolean ex = rsIDsToExclude != null && id != null && rsIDsToExclude.contains(id);
        //System.out.printf("Testing id %s ex=%b against %s%n", id, ex, vc);
        return ex;
    }

    private void determineEvalations() {
        // create a map for all eval modules for easy lookup
        HashMap<String, Class<? extends VariantEvaluator>> classMap = new HashMap<String, Class<? extends VariantEvaluator>>();
        for ( Class<? extends VariantEvaluator> c : new PluginManager<VariantEvaluator>( VariantEvaluator.class ).getPlugins() )
            classMap.put(c.getSimpleName(), c);

        evaluationClasses = new HashSet<Class<? extends VariantEvaluator>>();

        // by default, use standard eval modules
        if ( !NO_STANDARD ) {
            for ( Class<? extends StandardEval> myClass : new PluginManager<StandardEval>( StandardEval.class ).getPlugins() ) {
                if ( classMap.containsKey(myClass.getSimpleName()) )
                    evaluationClasses.add(classMap.get(myClass.getSimpleName()));
            }
        }

        // get the specific classes provided
        for ( String module : modulesToUse ) {
            if ( !classMap.containsKey(module) )
                throw new UserException.CommandLineException("Module " + module + " could not be found; please check that you have specified the class name correctly");
            evaluationClasses.add(classMap.get(module));
        }

        for ( VariantEvaluator e : instantiateEvalationsSet() ) {
            // for collecting purposes
            variantEvaluationNames.add(e.getName());
            logger.debug("Including VariantEvaluator " + e.getName() + " of class " + e.getClass());
        }

        Collections.sort(variantEvaluationNames);
    }

    private <T> List<T> append(List<T> selectExps, T elt) {
        List<T> l = new ArrayList<T>(selectExps);
        l.add(elt);
        return l;
    }

    private List<EvaluationContext> initializeEvaluationContexts(Set<String> evalNames, Set<String> compNames, List<VariantContextUtils.JexlVCMatchExp> selectExps) {
        List<EvaluationContext> contexts = new ArrayList<EvaluationContext>();

        // todo -- add another for loop for each sample (be smart about the selection here -
        // honor specifications of just one or a few samples), and put an "all" in here so
        // that we don't lose multi-sample evaluations

        List<String> filterTypes = MINIMAL ? Arrays.asList(RETAINED_SET_NAME) : Arrays.asList(RAW_SET_NAME, RETAINED_SET_NAME, FILTERED_SET_NAME);


        selectExps = append(selectExps, null);
        for ( String evalName : evalNames ) {
            for ( String compName : compNames ) {
                for ( VariantContextUtils.JexlVCMatchExp e : selectExps ) {
                    for ( String filteredName : filterTypes ) {
                        for ( String novelty : Arrays.asList(ALL_SET_NAME, KNOWN_SET_NAME, NOVEL_SET_NAME) ) {
                            EvaluationContext context = new EvaluationContext(evalName, compName, novelty, filteredName, e);
                            contexts.add(context);
                        }
                    }
                }
            }
        }

        Collections.sort(contexts);
        return contexts;
    }

    private Set<VariantEvaluator> instantiateEvalationsSet() {
        Set<VariantEvaluator> evals = new HashSet<VariantEvaluator>();
        Object[] args = new Object[]{this};
        Class[] argTypes = new Class[]{this.getClass()};

        for ( Class c : evaluationClasses ) {
            try {
                Constructor constructor = c.getConstructor(argTypes);
                VariantEvaluator eval = (VariantEvaluator)constructor.newInstance(args);
                evals.add(eval);
            } catch (Exception e) {
                throw new DynamicClassResolutionException(c, e);
            }
        }

        return evals;
    }



    private boolean captureInterestingSitesOfEvalSet(EvaluationContext group) {
        //System.out.printf("checking %s%n", name);
        return group.requiresNotFiltered() && group.isIgnoringNovelty();
    }

    // --------------------------------------------------------------------------------------------------------------
    //
    // map
    //
    // --------------------------------------------------------------------------------------------------------------

    // todo -- call a single function to build a map from track name -> variant context / null for all
    //      -- eval + comp names.  Use this data structure to get data throughout rest of the loops here
    public Integer map(RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context) {
        //System.out.printf("map at %s with %d skipped%n", context.getLocation(), context.getSkippedBases());

        Map<String, VariantContext> vcs = getVariantContexts(ref, tracker, context);
        //System.out.println("vcs has size "+vcs.size());
        //Collection<VariantContext> comps = getCompVariantContexts(tracker, context);

        // to enable walking over pairs where eval or comps have no elements
        for ( EvaluationContext group : contexts ) {
            VariantContext vc = vcs.get(group.evalTrackName);

            //logger.debug(String.format("Updating %s with variant", vc));
            Set<VariantEvaluator> evaluations = group.evaluations;
            boolean evalWantsVC = applyVCtoEvaluation(vc, vcs, group);
            VariantContext interestingVC = vc;
            List<String> interestingReasons = new ArrayList<String>();

            for ( VariantEvaluator evaluation : evaluations ) {
                synchronized ( evaluation ) {
                    if ( evaluation.enabled() ) {
                        // we always call update0 in case the evaluation tracks things like number of bases covered
                        evaluation.update0(tracker, ref, context);

                        // the other updateN methods don't see a null context
                        if ( tracker == null )
                            continue;

                        // now call the single or paired update function
                        switch ( evaluation.getComparisonOrder() ) {
                            case 1:
                                if ( evalWantsVC && vc != null ) {
                                    String interesting = evaluation.update1(vc, tracker, ref, context, group);
                                    if ( interesting != null ) interestingReasons.add(interesting);
                                }
                                break;
                            case 2:
                                VariantContext comp = vcs.get(group.compTrackName);
                                if ( comp != null &&
                                        minCompQualScore != NO_MIN_QUAL_SCORE &&
                                        comp.hasNegLog10PError() &&
                                        comp.getNegLog10PError() < (minCompQualScore / 10.0) )
                                    comp = null;

                                String interesting = evaluation.update2( evalWantsVC ? vc : null, comp, tracker, ref, context, group );

                                /** TODO
                                 -- for Eric: Fix me (current implementation causes GenotypeConcordance
                                 to treat sites that don't match JEXL as no-calls)

                                 String interesting = null;
                                 if (evalWantsVC)
                                 {
                                 interesting = evaluation.update2( evalWantsVC ? vc : null, comp, tracker, ref, context, group );
                                 }
                                 **/


                                if ( interesting != null ) {
                                    interestingVC = interestingVC == null ? ( vc == null ? comp : vc ) : interestingVC;
                                    interestingReasons.add(interesting);
                                }
                                break;
                            default:
                                throw new ReviewedStingException("BUG: Unexpected evaluation order " + evaluation);
                        }
                    }
                }
            }

            if ( tracker != null && group.enableInterestingSiteCaptures && captureInterestingSitesOfEvalSet(group) )
                writeInterestingSite(interestingReasons, interestingVC, ref.getBase());
        }

        return 0;
    }

    private void writeInterestingSite(List<String> interestingReasons, VariantContext vc, byte ref) {
        if ( vc != null && writer != null && interestingReasons.size() > 0 ) {
            // todo -- the vc == null check is because you can be interesting because you are a FN, and so VC == null
            MutableVariantContext mvc = new MutableVariantContext(vc);

            for ( String why : interestingReasons ) {
                String key, value;
                String[] parts = why.split("=");

                switch ( parts.length ) {
                    case 1:
                        key = parts[0];
                        value = "1";
                        break;
                    case 2:
                        key = parts[0];
                        value = parts[1];
                        break;
                    default:
                        throw new IllegalStateException("BUG: saw a interesting site reason sting with multiple = signs " + why);
                }

                mvc.putAttribute(key, value);
            }


            writer.add(mvc, ref);
            //interestingReasons.clear();
        }
    }

    private boolean applyVCtoEvaluation(VariantContext vc, Map<String, VariantContext> vcs, EvaluationContext group) {
        if ( vc == null )
            return true;

        if ( minQualScore != NO_MIN_QUAL_SCORE &&
                vc.hasNegLog10PError() &&
                vc.getNegLog10PError() < (minQualScore / 10.0) ) {
            //System.out.printf("exclude %s%n", vc);
            return false;
        }

        if ( group.requiresFiltered() && vc.isNotFiltered() )
            return false;

        if ( group.requiresNotFiltered() && vc.isFiltered() )
            return false;

        boolean vcKnown = vcIsKnown(vc, vcs, KNOWN_NAMES);
        if ( group.requiresKnown() && ! vcKnown )
            return false;
        else if ( group.requiresNovel() && vcKnown )
            return false;

        if ( group.selectExp != null && ! VariantContextUtils.match(getToolkit().getGenomeLocParser(),vc, group.selectExp) )
            return false;

        // nothing invalidated our membership in this set
        return true;
    }

    private boolean vcIsKnown(VariantContext vc, Map<String, VariantContext> vcs, String[] knownNames ) {
        for ( String knownName : knownNames ) {
            VariantContext known = vcs.get(knownName);
            if ( known != null && known.isNotFiltered() && known.getType() == vc.getType() ) {
                return true;
            }
        }

        return false;
    }

// can't handle this situation
// todo -- warning, this leads to some missing SNPs at complex loci, such as:
// todo -- 591     1       841619  841620  rs4970464       0       -       A       A       -/C/T   genomic mixed   unknown 0       0       near-gene-3     exact   1
// todo -- 591     1       841619  841620  rs62677860      0       +       A       A       C/T     genomic single  unknown 0       0       near-gene-3     exact   1
//
//logger.info(String.format("Ignore second+ events at locus %s in rod %s => rec is %s", context.getLocation(), rodList.getName(), rec));

    private Map<String, VariantContext> getVariantContexts(ReferenceContext ref, RefMetaDataTracker tracker, AlignmentContext context) {
        // todo -- we need to deal with dbSNP where there can be multiple records at the same start site.  A potential solution is to
        // todo -- allow the variant evaluation to specify the type of variants it wants to see and only take the first such record at a site
        Map<String, VariantContext> bindings = new HashMap<String, VariantContext>();
        if ( tracker != null ) {
            //System.out.println("Tracker is not null");
            bindVariantContexts(ref, bindings, evalNames, tracker, context, false);
            bindVariantContexts(ref, bindings, compNames, tracker, context, true);
        }
        return bindings;
    }

    private void bindVariantContexts(ReferenceContext ref, Map<String, VariantContext> map, Collection<String> names,
                                     RefMetaDataTracker tracker, AlignmentContext context, boolean allowExcludes ) {
        for ( String name : names ) {
            Collection<VariantContext> contexts = tracker.getVariantContexts(ref, name, ALLOW_VARIANT_CONTEXT_TYPES, context.getLocation(), true, true);
            if ( contexts.size() > 1 )
                throw new UserException.CommandLineException("Found multiple variant contexts found in " + name + " at " + context.getLocation() + "; VariantEval assumes one variant per position");

            VariantContext vc = contexts.size() == 1 ? contexts.iterator().next() : null;

	    if ( SAMPLES_LIST.size() > 0 && vc != null ) {
		boolean hasGenotypes = vc.hasGenotypes(SAMPLES_LIST);
		if ( hasGenotypes ) {
		    //if ( ! name.equals("eval") ) logger.info(String.format("subsetting VC %s", vc));
		    vc = vc.subContextFromGenotypes(vc.getGenotypes(SAMPLES_LIST).values());
		    HashMap<String,Object> newAts = new HashMap<String,Object>(vc.getAttributes());
		    VariantContextUtils.calculateChromosomeCounts(vc,newAts,true);
		    vc = VariantContext.modifyAttributes(vc,newAts);
		    logger.debug(String.format("VC %s subset to %s AC%n",vc.getSource(),vc.getAttributeAsString(VCFConstants.ALLELE_COUNT_KEY)));
		    //if ( ! name.equals("eval") ) logger.info(String.format("  => VC %s", vc));
		} else if ( !hasGenotypes && !name.equals("dbsnp")  ) {
		    throw new UserException(String.format("Genotypes for the variant context %s do not contain all the provided samples %s",vc.getSource(), getMissingSamples(SAMPLES_LIST,vc)));
		}
	    }

            map.put(name, allowExcludes && excludeComp(vc) ? null : vc);
        }
    }

    private static String getMissingSamples(Collection<String> soughtSamples, VariantContext vc) {
        StringBuffer buf = new StringBuffer();
        buf.append("Missing samples are:");
        for ( String s : soughtSamples ) {
            if ( ! vc.getGenotypes().keySet().contains(s) ) {
                buf.append(String.format("%n%s",s));    
            }
        }

        return buf.toString();
    }

    // --------------------------------------------------------------------------------------------------------------
    //
    // reduce
    //
    // --------------------------------------------------------------------------------------------------------------
    public Integer reduceInit() {
        return 0;
    }

    public Integer reduce(Integer point, Integer sum) {
        return point + sum;
    }

    public Integer treeReduce(Integer point, Integer sum) {
        return point + sum;
    }

    public VariantEvaluator getEvalByName(String name, Set<VariantEvaluator> s) {
        for ( VariantEvaluator e : s )
            if ( e.getName().equals(name) )
                return e;
        return null;
    }

    private void determineContextNamePartSizes() {
        for ( EvaluationContext group : contexts ) {
            String[] parts = group.getDisplayName().split(CONTEXT_SEPARATOR);
            if ( parts.length != N_CONTEXT_NAME_PARTS ) {
                throw new ReviewedStingException("Unexpected number of eval name parts " + group.getDisplayName() + " length = " + parts.length + ", expected " + N_CONTEXT_NAME_PARTS);
            } else {
                for ( int i = 0; i < parts.length; i++ )
                    nameSizes[i] = Math.max(nameSizes[i], parts[i].length());
            }
        }
    }

    public void onTraversalDone(Integer result) {
        // our report mashaller
        ReportMarshaller marshaller;

        // create the report marshaller early, so that we can fail-fast if something is wrong with the output sources
        if (outputLocation == null)
            marshaller = VE2ReportFactory.createMarhsaller(new OutputStreamWriter(out), reportType, createExtraOutputTags());
        else
            marshaller = VE2ReportFactory.createMarhsaller(outputLocation, reportType, createExtraOutputTags());
        for ( String evalName : variantEvaluationNames ) {
            for ( EvaluationContext group : contexts ) {
                VariantEvaluator eval = getEvalByName(evalName, group.evaluations);
                // finalize the evaluation
                eval.finalizeEvaluation();

                if ( eval.enabled() )
                    marshaller.write(createPrependNodeList(group),eval);
            }
        }
        marshaller.close();
    }

    /**
     * create some additional output lines about the analysis
     * @return a list of nodes to attach to the report as tags
     */
    private List<Node> createExtraOutputTags() {
        List<Node> list = new ArrayList<Node>();
        list.add(new Node("reference file",getToolkit().getArguments().referenceFile.getName(),"The reference sequence file"));
        for (String binding : getToolkit().getArguments().RODBindings)
            list.add(new Node("ROD binding",binding,"The reference sequence file"));
        return list;
    }


    /**
     * given the evaluation name, and the context, create the list of pre-pended nodes for the output system.
     * Currently it expects the the following list: jexl_expression, evaluation_name, comparison_name, filter_name,
     * novelty_name
     * @param group the evaluation context
     * @return a list of Nodes to prepend the analysis module output with
     */
    private List<Node> createPrependNodeList(EvaluationContext group) {
        // add the branching nodes: jexl expression, comparison track, eval track etc
        Node jexlNode = new Node("jexl_expression",(group.selectExp != null) ? group.selectExp.name : "none","The jexl filtering expression");
        Node compNode = new Node("comparison_name",group.compTrackName,"The comparison track name");
        Node evalNode = new Node("evaluation_name",group.evalTrackName,"The evaluation name");
        Node filterNode = new Node("filter_name",group.filtered,"The filter name");
        Node noveltyNode = new Node("novelty_name",group.novelty,"The novelty name");
        // the ordering is important below, this is the order the columns will appear in any output format
        return Arrays.asList(evalNode,compNode,jexlNode,filterNode,noveltyNode);
    }

    //
    // utility functions
    //

    /**
     * Takes an eval generated VariantContext and attempts to return the number of samples in the
     * VC. If there are genotypes, it returns that value first.  Otherwise it returns nSamples, if
     * provided.  Returns -1 if no sample counts can be obtained.
     *
     * @param eval
     * @return
     */
    public int getNSamplesForEval( VariantContext eval ) {
        return eval.hasGenotypes() ? eval.getNSamples() : nSamples;
    }

    protected Logger getLogger() { return logger; }
}
