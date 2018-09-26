package edu.kit.nildumu;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Stream;

import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;
import com.ibm.wala.util.graph.GraphIntegrity.UnsoundGraphException;

import edu.kit.joana.api.IFCAnalysis;
import edu.kit.joana.api.sdg.ConstructionNotifier;
import edu.kit.joana.api.sdg.SDGBuildPreparation;
import edu.kit.joana.api.sdg.SDGConfig;
import edu.kit.joana.api.sdg.SDGProgram;
import edu.kit.joana.api.test.util.ApiTestException;
import edu.kit.joana.api.test.util.BuildSDG;
import edu.kit.joana.api.test.util.DumpTestSDG;
import edu.kit.joana.api.test.util.JoanaPath;
import edu.kit.joana.ifc.sdg.graph.SDG;
import edu.kit.joana.ifc.sdg.graph.SDGSerializer;
import edu.kit.joana.ifc.sdg.graph.slicer.graph.threads.MHPAnalysis;
import edu.kit.joana.ifc.sdg.lattice.IStaticLattice;
import edu.kit.joana.ifc.sdg.mhpoptimization.CSDGPreprocessor;
import edu.kit.joana.ifc.sdg.mhpoptimization.MHPType;
import edu.kit.joana.ifc.sdg.mhpoptimization.PruneInterferences;
import edu.kit.joana.ifc.sdg.util.JavaMethodSignature;
import edu.kit.joana.ui.annotations.EntryPoint;
import edu.kit.joana.util.Stubs;
import edu.kit.joana.util.io.IOFactory;
import edu.kit.joana.wala.core.NullProgressMonitor;
import edu.kit.joana.wala.core.SDGBuildArtifacts;
import edu.kit.joana.wala.core.SDGBuilder;
import edu.kit.joana.wala.core.SDGBuilder.ExceptionAnalysis;
import edu.kit.joana.wala.core.SDGBuilder.FieldPropagation;
import edu.kit.joana.wala.core.SDGBuilder.PointsToPrecision;
import edu.kit.nildumu.ui.*;
import edu.kit.nildumu.util.Pair;

/**
 * This class provides utility methods. Heavily based on {@link JoanaPath}, {@link SDGBuilder} and
 * {@link SDGProgram} (code from these classes is copied almost directly)
 */
public class IOUtil {
	
	public static final String PROPERTIES_FILE = "classpaths.properties";
	public static final String TEST_DATA_CLASSPATH;
	public static final String TEST_DATA_GRAPHS;
	
	static {
		TEST_DATA_CLASSPATH = loadProperty("joana.api.testdata.classpath", "bin");
		TEST_DATA_GRAPHS = loadProperty("joana.api.testdata.graphs", "graphs");
		Stream.of(TEST_DATA_CLASSPATH, TEST_DATA_GRAPHS).forEach(p -> {
			try {
				Files.createDirectories(Paths.get(p));
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}
	
	static String loadProperty(String key, String defaultValue) {
		Properties p = new Properties();
		try {
			p.load(new FileInputStream(new File(PROPERTIES_FILE)));
		} catch (IOException e) {
			e.printStackTrace();
		}
		if (p.containsKey(key)) {
			return p.getProperty(key);
		}
		return defaultValue;
	}
	
	public static void saveSDGProgram(SDG sdg, String name) throws FileNotFoundException {
		SDGSerializer.toPDGFormat(sdg, new BufferedOutputStream(new FileOutputStream(TEST_DATA_GRAPHS + "/" + name)));
	}
	
	public static void saveSDGProgram(SDG sdg, Path path) throws FileNotFoundException {
		SDGSerializer.toPDGFormat(sdg, new BufferedOutputStream(new FileOutputStream(path.toFile())));
	}

	public static <T> Method getEntryMethod(Class<T> clazz) {
		return Arrays.stream(clazz.getMethods())
				.filter(m -> m.getAnnotationsByType(EntryPoint.class).length > 0 && 
						Modifier.isStatic(m.getModifiers())).findFirst().get();
	}
	
	public static void dump(SDG sdg, String name) {
		try {
			DumpTestSDG.dumpSDG(sdg, name + ".pdg");
			DumpTestSDG.dumpGraphML(sdg, name);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Modified version of {@link SDGProgram#createSDGProgram(String, String, Stubs, boolean, MHPType, PrintStream, IProgressMonitor)}
	 */
	private static <T> Pair<SDGBuilder, SDGProgram> createSDGProgram(SDGConfig config) throws ClassHierarchyException, UnsoundGraphException, CancelException, IOException{
		PrintStream out = IOFactory.createUTF8PrintStream(new ByteArrayOutputStream());
		IProgressMonitor monitor = NullProgressMonitor.INSTANCE;
		monitor.beginTask("build SDG", 20);
		ConstructionNotifier notifier = config.getNotifier();
		if (notifier != null) {
			notifier.sdgStarted();
		}
		final com.ibm.wala.util.collections.Pair<SDG, SDGBuilder> p =
				SDGBuildPreparation.computeAndKeepBuilder(out, SDGProgram.makeBuildPreparationConfig(config), monitor);
		final SDG sdg = p.fst;
		final SDGBuilder buildArtifacts = p.snd;

		if (config.computeInterferences()) {
			CSDGPreprocessor.preprocessSDG(sdg);
		}
		
		final MHPAnalysis mhpAnalysis = config.getMhpType().getMhpAnalysisConstructor().apply(sdg);
		assert (mhpAnalysis == null) == (config.getMhpType() == MHPType.NONE);
		
		if (config.computeInterferences()) {
			PruneInterferences.pruneInterferences(sdg, mhpAnalysis);
		}
		
		if (notifier != null) {
			notifier.sdgFinished();
			notifier.numberOfCGNodes(buildArtifacts.getNonPrunedWalaCallGraph().getNumberOfNodes(), buildArtifacts.getWalaCallGraph().getNumberOfNodes());
		}
		if (config.getIgnoreIndirectFlows()) {
			if (notifier != null) {
				notifier.stripControlDepsStarted();
			}
			SDGProgram.throwAwayControlDeps(sdg);
			if (notifier != null) {
				notifier.stripControlDepsFinished();
			}
			
		}
		final SDGProgram ret = new SDGProgram(sdg, mhpAnalysis);
		
		if (config.isSkipSDGProgramPart()) {
			return new Pair<>(buildArtifacts, ret);
		}
		
		
		final IClassHierarchy ch  = buildArtifacts.getClassHierarchy();
		final CallGraph callGraph = buildArtifacts.getWalaCallGraph(); 
		ret.fillWithAnnotations(ch, SDGProgram.findClassesRelevantForAnnotation(ch, callGraph));
		return new Pair<>(buildArtifacts, ret);
	}
	
	/**
	 * Creates an analysis object for the passed class using the first method annotated with {@link EntryPoint} as its
	 * entry point
	 */
	public static <T> BuildResult build(Class<T> clazz) throws ClassHierarchyException, IOException, UnsoundGraphException, CancelException {
		SDGConfig config = new SDGConfig(TEST_DATA_CLASSPATH, true, JavaMethodSignature.mainMethodOfClass(clazz.getName()).toBCString(), Stubs.JRE_15,
				ExceptionAnalysis.IGNORE_ALL, FieldPropagation.OBJ_GRAPH, PointsToPrecision.TYPE_BASED, false, // no
				false, // no interference
				MHPType.NONE);
		Pair<SDGBuilder, SDGProgram> pair = createSDGProgram(config);
		return new BuildResult(pair.first, new IFCAnalysis(pair.second));
	}

	public static <T> BuildResult buildAndUseJavaAnnotations(Class<T> clazz)
				throws ApiTestException, ClassHierarchyException, IOException, UnsoundGraphException, CancelException {
			BuildResult res = build(clazz);
			res.analysis.addAllJavaSourceAnnotations();
			return res;
	}

	public static <T> BuildResult buildAndUseJavaAnnotations(Class<T> clazz, IStaticLattice<String> l)
				throws ApiTestException, ClassHierarchyException, IOException, UnsoundGraphException, CancelException {
			BuildResult res = buildAndUseJavaAnnotations(clazz);
			res.analysis.setLattice(l);
			res.analysis.addAllJavaSourceAnnotations(l);
			return res;
	}
	
	public static <T> BuildResult buildOrDie(Class<T> clazz) {
		try {
			return buildAndUseJavaAnnotations(clazz);
		} catch (ClassHierarchyException | ApiTestException | IOException | UnsoundGraphException | CancelException e) {
			e.printStackTrace();
			System.exit(1);
		}
		return null;
	}
	
	public static <T> BuildResult buildAndDump(Class<T> clazz) {
		BuildResult res = IOUtil.buildOrDie(clazz);
		dump(res.analysis.getProgram().getSDG(), clazz.getName());
		return res;
	}
}
