package edu.kit.nildumu;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;

/**
 * Runs the program on the command line
 */
@Parameters(commandDescription="Basic quantitative information flow analysis")
public class Main {

	@Parameter(names="--handler", description="Method invocation handler configuration, see README")
	private String handler = "all";
	
	@Parameter(names="--classpath", description="Class path, by default uses the path from classpaths.properties")
	private String classPath = Builder.TEST_DATA_CLASSPATH;
	
	@Parameter(names="--dumppath", description="Dump path, by default uses the path from classpaths.properties") 
	private String dumpPath = Builder.TEST_DATA_GRAPHS;
	
	@Parameter(names="--dump", description="Dump graphs")
	private boolean dump = false;
	
	@Parameter(description="class name, class has to contain a 'program' method that is called in the main method", required=true)
	private String className;	
	
	@Parameter(names="--help", help=true)
	private boolean help;
	
	public static void main(String[] args) {
		Main main = new Main();
		JCommander com = JCommander.newBuilder().addObject(main).build();
		try {
			com.parse(args);
		} catch (ParameterException ex) {
			com.usage();
			return;
		}
		if (main.help) {
			com.usage();
			return;
		}
		Builder builder = new Builder().classpath(main.classPath)
				.methodInvocationHandler(main.handler)
				.entry(main.className).dumpDir(main.dumpPath);
		if (main.dump) {
			builder.enableDumpAfterBuild();
			BasicLogger.enable();
		} else {
			BasicLogger.disable();
		}
		try {
			Program program = builder.buildProgramOrDie();
			Context context = program.analyze();
			context.printLeakages();
		} finally {
			if (main.dump) {
				builder.dumpDotGraphs();
			}
		}
	}
}
