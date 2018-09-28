package edu.kit.nildumu;

import static edu.kit.nildumu.Lattices.bl;
import static edu.kit.nildumu.Lattices.vl;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Iterators;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SymbolTable;

import edu.kit.joana.api.IFCAnalysis;
import edu.kit.joana.api.annotations.AnnotationType;
import edu.kit.joana.api.sdg.SDGFormalParameter;
import edu.kit.joana.api.sdg.SDGMethod;
import edu.kit.joana.api.sdg.SDGProgram;
import edu.kit.joana.api.sdg.SDGProgramPartVisitor;
import edu.kit.joana.ifc.sdg.graph.SDG;
import edu.kit.joana.ifc.sdg.graph.SDGEdge;
import edu.kit.joana.ifc.sdg.graph.SDGNode;
import edu.kit.joana.ifc.sdg.graph.SDGNode.Kind;
import edu.kit.joana.ifc.sdg.lattice.IStaticLattice;
import edu.kit.joana.ifc.sdg.util.JavaMethodSignature;
import edu.kit.joana.ifc.sdg.util.JavaType;
import edu.kit.joana.ui.annotations.Source;
import edu.kit.joana.util.Pair;
import edu.kit.joana.wala.core.PDG;
import edu.kit.joana.wala.core.PDGNode;
import edu.kit.joana.wala.core.SDGBuilder;
import edu.kit.nildumu.Lattices.B;
import edu.kit.nildumu.Lattices.Sec;
import edu.kit.nildumu.Lattices.Value;
import edu.kit.nildumu.ui.CodeUI;
import edu.kit.nildumu.ui.Config;
import edu.kit.nildumu.util.NildumuError;
import edu.kit.nildumu.util.Util;
import edu.kit.nildumu.util.Util.Box;

/**
 * Contains all static information on the program and helper methods
 */
public class Program {
	
	/**
	 * Combines a method with its entry and some helper methods
	 */
	public static class Method {
		public final Program program;
		public final SDGMethod method;
		public final SDGNode entry;
		public final IR ir;
		
		/**
		 * Dominators and loop headers for the cfg
		 */
		public final Dominators<ISSABasicBlock> doms;
		
		public Method(Program program, SDGMethod method, SDGNode entry, Dominators<ISSABasicBlock> doms) {
			this.program = program;
			this.method = method;
			this.entry = entry;
			this.doms = doms;
			this.ir = program.getProcIR(entry);
			this.doms.registerDotGraph("cfg", method.getSignature().toStringHRShort(), 
					b -> {
						List<String> strs = new ArrayList<>();
						Stream.of(b.getFirstInstructionIndex(), b.getLastInstructionIndex())
							.filter(i -> i > 0).map(i -> ir.getInstructions()[i])
							.filter(Objects::nonNull)
							.forEach(instr -> strs.add(instr.toString()));
						strs.add(b.getNumber() + "");
						return strs.stream().collect(Collectors.joining("|"));
					});
		}
		
		@Override
		public String toString() {
			return toBCString();
		}
		
		public boolean isOutputMethod() {
			return classForType(method.getSignature().getDeclaringType()).equals(CodeUI.class);
		}
		
		public boolean isMainMethod() {
			return method.getSignature().getMethodName().equals(MAIN_METHOD_NAME);
		}
		
		public String toBCString() {
			return method.getSignature().toBCString();
		}
		
		public boolean hasReturnValue() {
			return method.getSignature().getReturnType() != null && !method.getSignature().getReturnType().toHRString().equals("void");
		}
		
		public List<SDGFormalParameter> getParameters(){
			return method.getParameters().stream().sorted(Comparator.comparingInt(SDGFormalParameter::getIndex)).collect(Collectors.toList());
		}
		
		public int getLoopDepth(SDGNode node) {
			return doms.loopDepth(program.getBlock(node));
		}
	}

	public static class MainMethod extends Method {

		public MainMethod(Method method) {
			super(method.program, method.method, method.entry, method.doms);
		}
		
	}
	
	public static class UnsupportedType extends NildumuError {
		public UnsupportedType(JavaType type) {
			super(String.format("Type %s is not supported", type.toHRString()));
		}
	}
	
	private static class Tmp {
		@Config
		void func() {}
	}
	
	public static final String MAIN_METHOD_NAME = "program";

	public final IFCAnalysis ana;
	
	public final SDGBuilder builder;
	
	public final SDG sdg;
	
	public final MainMethod main;
	
	public final IStaticLattice<String> lattice;
	
	public final BiMap<SDGNode, Method> entryToMethod;
	
	public final BiMap<String, Method> bcNameToMethod;
	
	public final int intWidth;
	
	public final Context context;
	
	public Program(BuildResult build) {
		super();
		this.ana = build.analysis;
		this.builder = build.builder;
		this.sdg = ana.getProgram().getSDG();
		this.entryToMethod = HashBiMap.create(ana.getProgram().getSDG().sortByProcedures().keySet().stream().collect(Collectors.toMap(n -> n, 
				n -> new Method(this,
						ana.getProgram().getAllMethods().stream()
							.filter(m -> 
								n.getBytecodeMethod().equals(m.getSignature().toBCString())
								).findFirst().get(), n, calculateCFGDoms(n))
				)));
		this.bcNameToMethod = HashBiMap.create(entryToMethod.values().stream().collect(Collectors.toMap(m -> m.toBCString(), m -> m)));
		this.main = new MainMethod(entryToMethod.values().stream().filter(m -> m.isMainMethod()).findFirst().get());
		lattice = ana.getLattice();
		Config defaultConfig = null;
		try {
			defaultConfig = Tmp.class.getDeclaredMethods()[0].getAnnotation(Config.class);
		} catch (SecurityException e) {
			e.printStackTrace();
		}
		java.lang.reflect.Method mainMethod = getJavaMethodForSignature(main.method.getSignature());
		Config config = mainMethod.getAnnotationsByType(Config.class).length > 0 ? 
				mainMethod.getAnnotationsByType(Config.class)[0] : 
			    defaultConfig;
		this.intWidth = config.intWidth();
		this.context = new Context(this);
		check();
		initContext();
	}

	private void initContext(){
		java.lang.reflect.Method mainMethod = Arrays.asList(getMainClass().getMethods()).stream()
				.filter(m -> m.getName().equals(MAIN_METHOD_NAME)).findFirst().get();
		main.getParameters().forEach(p -> {
				Pair<Source, String> ap = Util.get(ana.getJavaSourceAnnotations().getFirst().get(p));
				Parameter param = mainMethod.getParameters()[p.getIndex() - 1];
				int bitWidth = bitWidthForType(p.getType());
				Value val;
				if (param.isAnnotationPresent(edu.kit.nildumu.ui.Value.class)) {
					val = vl.parse(param.getAnnotation(edu.kit.nildumu.ui.Value.class).value());
				} else {
					val = createUnknownValue(bitWidth);
				}
				Sec<?> sec = context.sl.parse(ap.getFirst().level());
				context.setVariableValue(p.getIndex() + "", val);
				context.addInputValue(sec, val);
			});
	}
	
	public Value createUnknownValue(JavaType type) {
		return createUnknownValue(bitWidthForType(type));
	}
	
	public int bitWidthForType(JavaType type) {
		int bitWidth = intWidth;
		switch (type.toHRString()) {
		case "boolean":
			bitWidth = 1;
			break;
		case "byte":
		case "char":
			bitWidth = 8;
			break;
		case "short":
			bitWidth = 16;
		}
		return bitWidth;
	}
	
	private Dominators<ISSABasicBlock> calculateCFGDoms(SDGNode entry){
		SSACFG cfg = getProcIR(entry).getControlFlowGraph();
		return new Dominators<>(cfg.entry(), b -> Util.toList(cfg.getSuccNodes(b)));
	}
	
	private void check() {
		List<String> errors = new ArrayList<>();
		ana.getAnnotations().stream().forEach(a -> {
			if (a.getProgramPart().getClass() != SDGFormalParameter.class || !((SDGFormalParameter)a.getProgramPart()).getOwningMethod().equals(main.method)) {
				errors.add(String.format("Annotations are only allowed for parameters"
						+ " of the %s method, annotation: %s",
						MAIN_METHOD_NAME, a));
			}
			if (a.getType() != AnnotationType.SOURCE) {
				errors.add(String.format("Only SOURCE annotations are allowed, annotation: %s", a));
			}
		});
		long mmCount = entryToMethod.values().stream().filter(m -> m.isMainMethod()).count();
		if (mmCount != 1) {
			throw new RuntimeException(String.format("Expected exactly one entry method named %s, got %d", MAIN_METHOD_NAME, mmCount));
		}
		main.method.getParameters().stream()
		.filter(p -> !ana.getAnnotations().stream().anyMatch(a -> a.getProgramPart().equals(p)))
		.forEach(p -> errors.add(String.format("Parameter %s of method %s is not annotated",
				p, MAIN_METHOD_NAME)));
		if (errors.size() > 0) {
			throw new NildumuError(String.join("\n", errors));
		}
	}
	
	private Class<?> getMainClass() {
		return classForType(main.method.getSignature().getDeclaringType());
	}
	
	public static Class<?> classForType(JavaType type){
		switch (type.toHRString()) {
		case "int":
			return int.class;
		case "short":
			return short.class;
		case "char":
			return char.class;
		case "byte":
			return byte.class;
		case "boolean":
			return boolean.class;
		}
		try {
			return Class.forName(type.toHRString());
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
			assert false;
			return null;
		}
	}

	public SDGProgram getProgram() {
		return ana.getProgram();
	}
	
	public <R, D> R accept(SDGProgramPartVisitor<R, D> visitor, D data) {
		return main.method.acceptVisitor(visitor, data);
	}
	
	public SDG getSDG() {
		return getProgram().getSDG();
	}
	
	public void tryRun(SDGNode entryNode) {
		workList(entryNode, n -> {
			System.out.println(n.getLabel());
			return false;
		}, n -> false);
	}
	
	public void workList(SDGNode entryNode, 
			Predicate<SDGNode> nodeConsumer,
			Predicate<SDGNode> ignore) {
		Predicate<SDGNode> filter = n -> Arrays.asList(Kind.NORMAL, Kind.EXPRESSION, Kind.PREDICATE, Kind.CALL).contains(n.kind)
				&& !n.getLabel().endsWith("_exception_") && !Arrays.asList("CALL_RET").contains(n.getLabel());
		Set<SDGNode> procNodes = getSDG().getNodesOfProcedure(entryNode);

		//Queue<SDGNode> q = new ArrayDeque<>();
		Method method = method(entryNode);
		PriorityQueue<SDGNode> q =
				new PriorityQueue<>(new TreeSet<>(Comparator.comparingInt(n -> -method.getLoopDepth((SDGNode)n))));

		//topOrder(entryNode).forEach(q::offer);
		topOrder(entryNode).stream().filter((Predicate<? super SDGNode>) filter).forEach(q::add);
		/*while (!q.isEmpty()) {
			System.err.println(" ### "+ q.poll().getLabel());
		}*/
		while (!q.isEmpty()) {
			SDGNode cur = q.poll();
			if (ignore.test(cur)) {
				continue;
			}
			if (nodeConsumer.test(cur)) {
				getSDG().outgoingEdgesOf(cur).stream()
					.map(e -> e.getTarget()).filter((Predicate<? super SDGNode>) filter)
					.filter(procNodes::contains)
					.forEach(q::offer);
			}
		}
	}
	
	/**
	 * Based on the depth-first algorithm (ignores cycles): 
	 * https://en.wikipedia.org/wiki/Topological_sorting
	 */
	public List<SDGNode> topOrder(SDGNode entryNode){
		Set<SDGNode> unmarked = new HashSet<>(getSDG().getNodesOfProcedure(entryNode));
		List<SDGNode> l = new ArrayList<>();
		Box<Consumer<SDGNode>> visit = new Box<>(null);
		visit.val = n -> {
			if (!unmarked.contains(n)) {
				return;
			}
			unmarked.remove(n);
			getSDG().outgoingEdgesOf(n).stream()
				.map(SDGEdge::getTarget)
				.filter(getSDG().getNodesOfProcedure(entryNode)::contains)
				.forEach(visit.val::accept);
			l.add(n);
		};
		while (!unmarked.isEmpty()) {
			visit.val.accept(Util.get(unmarked));
		}
		Collections.reverse(l);
		return l;
	}
	
	public Method method(SDGNode entry) {
		assert entry.getKind() == Kind.ENTRY;
		return entryToMethod.get(entry);
	}
	
	public Method method(String bcString) {
		return bcNameToMethod.get(bcString);
	}
	
	public void checkType(JavaType type) {
		if (!Arrays.asList("int", "boolean").contains(type.toHRString())) {
			throw new UnsupportedType(type);
		}
	}
	
	public Method getMethodForCallSite(SDGNode callSite) {
		assert callSite.kind == Kind.CALL;
		if (callSite.getUnresolvedCallTarget() != null) {
			return method(callSite.getUnresolvedCallTarget()); 
		}
		return method(((SSAInvokeInstruction)getInstruction(callSite)).getDeclaredTarget().getSignature());
	}
	
	public boolean isOutputMethodCall(SDGNode callSite) {
		java.lang.reflect.Method method = getJavaMethodCallTarget(callSite);
		return method.getDeclaringClass().equals(CodeUI.class) && method.getName().equals("output");
	}
	
	public JavaMethodSignature parseSignature(String signature) {
		return JavaMethodSignature.fromString(signature);
	}
	
	public java.lang.reflect.Method getJavaMethodForSignature(JavaMethodSignature signature){
		try {
			return classForType(signature.getDeclaringType())
					.getMethod(signature.getMethodName(), 
							signature.getArgumentTypes().stream().map(Program::classForType).toArray(i -> new Class[i]));
		} catch (NoSuchMethodException | SecurityException e) {
			e.printStackTrace();
			throw new NildumuError(String.format("Method %s not found", signature.getFullyQualifiedMethodName()));
		}
	}
	
	public java.lang.reflect.Method getJavaMethodCallTarget(SDGNode callSite){
		if (callSite.getUnresolvedCallTarget() != null) {
			return getJavaMethodForSignature(parseSignature(callSite.getUnresolvedCallTarget()));
		}
		return getJavaMethodForSignature(parseSignature(((SSAInvokeInstruction)getInstruction(callSite)).getDeclaredTarget().getSignature()));
	}
	
	public void tryWorkListRun(SDGNode entryNode) {
		workList(entryNode, Program::print, n -> false); 
	}
	
	public static String toString(SDGNode node) {
		return String.format("%s:%s", node.toString(), node.getLabel());
	}
	
	/**
	 * Prints the node and returns {@code false}
	 */
	public static boolean print(SDGNode node) {
		System.out.println(toString(node));
		return false;
	}
	
	public List<SDGNode> getParamNodes(SDGNode callSite){
		assert callSite.kind == Kind.CALL;
		return new ArrayList<>(sdg.getAllActualInsForCallSiteOf(callSite));
	}
	
	/**
	 * Constants in labels start with {@code #(} and end with {@code )}
	 * @param label
	 * @return
	 */
	public String getConstantInLabel(String label) {
		return label.split("#")[1].substring(1).split("\\)")[0];
	}
	
	Value createUnknownValue(int width){
		assert width <= intWidth;
        return IntStream.range(0, width).mapToObj(i -> bl.create(B.U)).collect(Value.collector());
	}
	
	public void fixPointIteration() {
		context.fixPointIteration(main.entry);
	}
	
	public PDG getPDG(SDGNode node) {
		return builder.getPDGforMethod(getCGNode(node));
	}
	
	public SSAInstruction getInstruction(SDGNode node) {
		return getPDG(node).getInstruction(getPDGNode(node));
	}
	
	public PDGNode getPDGNode(SDGNode node) {
		return getPDG(node).getNodeWithId(node.getId());
	}
	
	public SSAInstruction getNextInstruction(SDGNode node) {
		SSAInstruction instr = getInstruction(node);
		return  Iterators.filter(getProcIR(node).iterateAllInstructions(), i -> i.iindex > instr.iindex).next();
	}
	
	public ISSABasicBlock getNextBlock(SDGNode node) {
		return getProcIR(node).getBasicBlockForInstruction(getNextInstruction(node));
	}
	
	/**
	 * 
	 * @param node used to get the method
	 * @return
	 */
	public IR getProcIR(SDGNode node) {
		return getCGNode(node).getIR();
	}
	
	public CGNode getCGNode(SDGNode node) {
		return builder.getAllPDGs().stream().filter(n -> n.getId() == node.getProc()).map(n -> n.cgNode).findFirst().get();
	}
	
	public ISSABasicBlock getBlock(SDGNode node) {
		return getPDG(node).cgNode.getIR().getBasicBlockForInstruction(getInstruction(node));
	}
	
	public ISSABasicBlock blockForId(SDGNode base, int id) {
		return Iterators.filter(getProcIR(base).getBlocks(), b -> b.getNumber() == id).next();
	}
	
	/**
	 * 
	 * @param node used to get the method
	 * @return
	 */
	public SymbolTable getProcSymbolTable(SDGNode node) {
		return getProcIR(node).getSymbolTable();
	}
	
	public List<SDGNode> getControlDeps(SDGNode node) {
		return sdg.getIncomingEdgesOfKind(node, SDGEdge.Kind.CONTROL_DEP_COND).stream().map(SDGEdge::getSource).filter(n -> n.kind == Kind.PREDICATE).collect(Collectors.toList());
	}
	
	public Dominators<Method> getMethodDominators(){
		return new Dominators<>(main, m -> {
			Set<Method> called = new HashSet<>();
			builder.getNonPrunedWalaCallGraph().getSuccNodes(getCGNode(m.entry)).forEachRemaining(c -> called.add(entryToMethod.get(sdg.getNode(builder.getPDGforMethod(c).entry.getId()))));
			return called;
		});
	}
	
	public Program setMethodInvocationHandler(String props) {
		context.forceMethodInvocationHandler(MethodInvocationHandler.parseAndSetup(this, props));
		return this;
	}
	
	public Context analyze() {
		context.fixPointIteration(main.entry);
		return context;
	}
}
