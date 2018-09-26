package edu.kit.nildumu;

import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

import com.google.common.collect.Iterators;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction;
import com.ibm.wala.shrikeBT.IConditionalBranchInstruction;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction.IOperator;
import com.ibm.wala.shrikeBT.IUnaryOpInstruction;
import com.ibm.wala.ssa.ConstantValue;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAComparisonInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAConversionInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInstruction.Visitor;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSAMonitorInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.ssa.SymbolTable;

import edu.kit.joana.api.sdg.SDGInstruction;
import edu.kit.joana.ifc.sdg.graph.SDGEdge;
import edu.kit.joana.ifc.sdg.graph.SDGNode;
import edu.kit.joana.ifc.sdg.graph.SDGNode.Kind;
import edu.kit.joana.ifc.sdg.graph.SDGNode.Operation;
import edu.kit.joana.wala.core.PDGNode;
import edu.kit.nildumu.Lattices.Value;
import edu.kit.nildumu.MethodInvocationHandler.CallSite;
import edu.kit.nildumu.MethodInvocationHandler.NodeBasedCallSite;
import edu.kit.nildumu.ui.CodeUI;
import edu.kit.nildumu.util.DefaultMap;
import edu.kit.nildumu.util.NildumuError;
import edu.kit.nildumu.util.Pair;
import edu.kit.nildumu.util.Util.Box;

import static edu.kit.nildumu.util.DefaultMap.ForbiddenAction.*;
import static edu.kit.nildumu.Lattices.*;

/**
 * The context contains the global state and the global functions from the thesis.
 * <p/>
 * This is this basic idea version, but with the loop extension.
 */
public class Context {

    public static class NotAnInputBit extends NildumuError {
        NotAnInputBit(Bit offendingBit, String reason){
            super(String.format("%s is not an input bit: %s", offendingBit.repr(), reason));
        }
    }

    public static class InvariantViolationError extends NildumuError {
        InvariantViolationError(String msg){
            super(msg);
        }
    }

    public static final Logger LOG = Logger.getLogger("Analysis");
    static {
        LOG.setLevel(Level.INFO);
    }
    public final Program program;
    
    public final SecurityLattice<?> sl;

    public final int maxBitWidth;

    public final IOValues input = new IOValues();

    public final IOValues output = new IOValues();

    private final Stack<State> variableStates = new Stack<>();

    private final DefaultMap<Bit, Sec<?>> secMap =
            new DefaultMap<>(
                    new IdentityHashMap<>(),
                    new DefaultMap.Extension<Bit, Sec<?>>() {
                        @Override
                        public Sec<?> defaultValue(Map<Bit, Sec<?>> map, Bit key) {
                            return sl.bot();
                        }
                    },
                    FORBID_DELETIONS,
                    FORBID_VALUE_UPDATES);

    private final DefaultMap<SDGNode, Operator> operatorPerNode = new DefaultMap<>(new IdentityHashMap<>(), new DefaultMap.Extension<SDGNode, Operator>() {

        @Override
        public Operator defaultValue(Map<SDGNode, Operator> map, SDGNode node) {
          	return operatorForNodeNotCached(node);
        }
    }, FORBID_DELETIONS, FORBID_VALUE_UPDATES);

    public static class CallPath {
        final List<CallSite> path;

        CallPath(){
            this(Collections.emptyList());
        }

        CallPath(List<CallSite> path) {
            this.path = path;
        }

        public CallPath push(CallSite callSite){
            List<CallSite> newPath = new ArrayList<>(path);
            newPath.add(callSite);
            return new CallPath(newPath);
        }

        public CallPath pop(){
            List<CallSite> newPath = new ArrayList<>(path);
            newPath.remove(newPath.size() - 1);
            return new CallPath(newPath);
        }

        @Override
        public int hashCode() {
            return path.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof CallPath && ((CallPath) obj).path.equals(path);
        }

        @Override
        public String toString() {
            return path.stream().map(Object::toString).collect(Collectors.joining(" → "));
        }

        public boolean isEmpty() {
            return path.isEmpty();
        }

        public CallSite peek() {
            return path.get(path.size() - 1);
        }
    }

    public static class NodeValueState {

        long nodeValueUpdateCount = 0;

        final DefaultMap<SDGNode, Value> nodeValueMap = new DefaultMap<>(new LinkedHashMap<>(), new DefaultMap.Extension<SDGNode, Value>() {

            @Override
            public void handleValueUpdate(DefaultMap<SDGNode, Value> map, SDGNode key, Value value) {
                if (vl.mapBits(map.get(key), value, (a, b) -> a != b).stream().anyMatch(p -> p)){
                    nodeValueUpdateCount++;
                }
            }

            @Override
            public Value defaultValue(Map<SDGNode, Value> map, SDGNode key) {
                return vl.bot();
            }
        }, FORBID_DELETIONS);

        long nodeVersionUpdateCount = 0;

        final DefaultMap<SDGNode, Integer> nodeVersionMap = new DefaultMap<>(new LinkedHashMap<>(), new DefaultMap.Extension<SDGNode, Integer>() {

            @Override
            public void handleValueUpdate(DefaultMap<SDGNode, Integer> map, SDGNode key, Integer value) {
                if (map.get(key) != value){
                    nodeVersionUpdateCount++;
                }
            }

            @Override
            public Integer defaultValue(Map<SDGNode, Integer> map, SDGNode key) {
                return 0;
            }
        }, FORBID_DELETIONS);

        final CallPath path;

        public NodeValueState(CallPath path) {
            this.path = path;
        }
    }
    

    private final DefaultMap<CallPath, NodeValueState> nodeValueStates = new DefaultMap<>((map, path) -> new NodeValueState(path));

    private CallPath currentCallPath = new CallPath();

    private NodeValueState nodeValueState = nodeValueStates.get(currentCallPath);

    /*-------------------------- loop mode specific -------------------------------*/

    private final HashMap<Bit, Integer> weightMap = new HashMap<>();

    public static final int INFTY = Integer.MAX_VALUE;

    /*-------------------------- methods -------------------------------*/

    private MethodInvocationHandler methodInvocationHandler;

    private Stack<Set<Bit>> methodParameterBits = new Stack<>();

    /*-------------------------- unspecific -------------------------------*/

    public Context(Program program) {
        this.sl = BasicSecLattice.get();
        this.maxBitWidth = program.intWidth;
        this.variableStates.push(new State());
        ValueLattice.get().bitWidth = maxBitWidth;
        this.program = program;
    }

    public static B v(Bit bit) {
        return bit.val();
    }

    public static DependencySet d(Bit bit) {
        return bit.deps();
    }

    /**
     * Returns the security level of the bit
     *
     * @return sec or bot if not assigned
     */
    public Sec sec(Bit bit) {
        return secMap.get(bit);
    }

    /**
     * Sets the security level of the bit
     *
     * <p><b>Important note: updating the security level of a bit is prohibited</b>
     *
     * @return the set level
     */
    private Sec sec(Bit bit, Sec<?> level) {
        return secMap.put(bit, level);
    }

    public Value addInputValue(Sec<?> sec, Value value){
        input.add(sec, value);
        for (Bit bit : value){
            if (bit.val() == B.U){
                if (!bit.deps().isEmpty()){
                    throw new NotAnInputBit(bit, "has dependencies");
                }
                sec(bit, sec);
            }
        }
        return value;
    }

    public Value addOutputValue(Sec<?> sec, Value value){
        output.add(sec, value);
        return value;
    }

    public boolean checkInvariants(Bit bit) {
        return (sec(bit) == sl.bot() || (!v(bit).isConstant() && d(bit).isEmpty()))
                && (!v(bit).isConstant() || (d(bit).isEmpty() && sec(bit) == sl.bot()));
    }

    public void checkInvariants(){
        List<String> errorMessages = new ArrayList<>();
        walkBits(b -> {
            if (!checkInvariants(b)){
              //  errorMessages.add(String.format("Invariants don't hold for %s", b.repr()));
            }
        }, p -> false);
        throw new InvariantViolationError(String.join("\n", errorMessages));
    }

    public static void log(Supplier<String> msgProducer){
        if (LOG.isLoggable(Level.FINE)){
            System.out.println(msgProducer.get());
        }
    }

    public boolean isInputBit(Bit bit) {
        return input.contains(bit);
    }

    public Value nodeValue(SDGNode node){
        return nodeValueState.nodeValueMap.get(node);
    }

    public Value nodeValue(SDGNode node, Value value){
        return nodeValueState.nodeValueMap.put(node, value);
    }

    Operator operatorForNode(SDGNode node){
        return operatorPerNode.get(node);
    }

    public Value op(SDGNode node, List<Value> arguments){
        /*if (node instanceof VariableAccessNode){
            return replace(nodeValue(((VariableAccessNode) node).definingExpression));
        }*/
    	Operator operator = operatorForNode(node);
    	if (operator == null) {
    		return new Value(bl.create(B.X));
    	}
        return operatorForNode(node).compute(this, node, arguments);
    }

    public List<Value> opArgs(SDGNode node){
    	System.err.println(node.getLabel());
    	SSAInstruction instr = program.getInstruction(node);
    	if (instr == null) {
    		return Collections.emptyList();
    	}
    	SymbolTable st = program.getProcSymbolTable(node);
    	List<SDGEdge> edges = program.sdg.getIncomingEdgesOfKind(node, SDGEdge.Kind.DATA_DEP);
    	Box<Integer> edgeIndex = new Box<>(0); 
    	return IntStream.range(0, instr.getNumberOfUses()).map(instr::getUse).mapToObj(i -> {
    		if (st.isConstant(i)) {
    			Object val = ((ConstantValue)st.getValue(i)).getValue();
    			if (st.isNumberConstant(i)) {
    				return vl.parse(((Number)val).intValue());
    			}
    			if (st.isBooleanConstant(i)) {
    				return vl.parse(((Boolean)val).booleanValue() ? 1 : 0);
    			}
    			throw new NildumuError(String.format("Unsupported constant type %s", val.getClass()));
    		}
    		if (st.isParameter(i)) {
    			return getParamValue(node, i);
    		}
    		return nodeValue(edges.get(edgeIndex.val++).getSource());
    	}).collect(Collectors.toList());
    }

    public boolean evaluate(SDGNode node){
    	final SDGNode resNode;
    	if (node.kind == Kind.CALL) {
    		PDGNode pdgNode = program.getPDG(node).getReturnOut(program.getPDG(node).getNodeWithId(node.getId()));
    		resNode = program.sdg.getNode(pdgNode.getId());
    	} else {
    		resNode = node;
    	}
    	
    	System.err.println(" ### " +resNode.getLabel());
        
        log(() -> "Evaluate node " + node + " " + nodeValue(resNode).get(1).deps().size());
        Value newValue = null;
        if (node.kind == Kind.CALL) {
        	newValue = evaluateCall(node);
        } else {
        	List<Value> args = opArgs(node);
    		
        	newValue = op(node, args);
        }
        
        boolean somethingChanged = false;
        if (nodeValue(resNode) != vl.bot()) { // dismiss first iteration
            Value oldValue = nodeValue(resNode);
            List<Bit> newBits = new ArrayList<>();
            somethingChanged = vl.mapBits(oldValue, newValue, (a, b) -> {
                boolean changed = false;
                if (a.value() == null){
                    a.value(oldValue); // newly created bit
                    changed = true;
                    newBits.add(a);
                }
                return merge(a, b) || changed;
            }).stream().anyMatch(p -> p);
            if (newBits.size() > 0){
                newValue = Stream.concat(oldValue.stream(), newBits.stream()).collect(Value.collector());
            } else {
                newValue = oldValue;
            }
            if (somethingChanged){
                nodeValueState.nodeVersionMap.put(resNode, nodeValueState.nodeVersionMap.get(resNode) + 1);
            }
        } else {
            somethingChanged = nodeValue(resNode).valueEquals(vl.bot());
        }
        nodeValue(resNode, newValue);
        newValue.description(node.getLabel()).node(node);
        return somethingChanged;
    }

    /**
     * Returns the unknown output bits with lower or equal security level
     */
    public List<Bit> getOutputBits(Sec<?> maxSec){
        return output.getBits().stream().filter(p -> ((SecurityLattice)sl).lowerEqualsThan(p.first, (Sec)maxSec)).map(p -> p.second).collect(Collectors.toList());
    }

    /**
     * Returns the unknown input bits with not lower security or equal level
     */
    public List<Bit> getInputBits(Sec<?> minSecEx){
        return input.getBits().stream().filter(p -> !(((SecurityLattice)sl).lowerEqualsThan(p.first, (Sec)minSecEx))).map(p -> p.second).collect(Collectors.toList());
    }

    public Value setVariableValue(String variable, Value value){
        if (variableStates.size() == 1) {
            if (!variableStates.get(0).get(variable).equals(vl.bot())) {
                throw new UnsupportedOperationException(String.format("Setting an input variable (%s)", variable));
            }
        }
        variableStates.peek().set(variable, value);
        return value;
    }

    public Value getVariableValue(String variable){
        return variableStates.peek().get(variable);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Variable states\n");
        for (int i = 0; i < variableStates.size(); i++){
            builder.append(variableStates.get(i));
        }
        builder.append("Input\n" + input.toString()).append("Output\n" + output.toString());
        return builder.toString();
    }

    public boolean isInputValue(Value value){
        return input.contains(value);
    }

    public Sec<?> getInputSecLevel(Value value){
        assert isInputValue(value);
        return input.getSec(value);
    }

    public Sec<?> getInputSecLevel(Bit bit){
        return input.getSec(bit);
    }

    /**
     * Walk in pre order
     * @param ignoreBit ignore bits (and all that depend on it, if not reached otherwise)
     */
    public void walkBits(Consumer<Bit> consumer, Predicate<Bit> ignoreBit){
        Set<Bit> alreadyVisitedBits = new HashSet<>();
        for (Pair<Sec, Bit> secBitPair : output.getBits()){
            BitLattice.get().walkBits(secBitPair.second, consumer, ignoreBit, alreadyVisitedBits);
        }
    }

    private Map<Sec<?>, MinCut.ComputationResult> leaks = null;


    public Map<Sec<?>, MinCut.ComputationResult> computeLeakage(){
        if (leaks == null){
            leaks = MinCut.compute(this);
        }
        return leaks;
    }

    public Set<SDGNode> nodes(){
        return nodeValueState.nodeValueMap.keySet();
    }

    public List<String> variableNames(){
        List<String> variables = new ArrayList<>();
        for (int i = variableStates.size() - 1; i >= 0; i--){
            variables.addAll(variableStates.get(i).variableNames());
        }
        return variables;
    }
    
    public int c1(Bit bit){
        return c1(bit, new HashSet<>());
    }

    private int c1(Bit bit, Set<Bit> alreadyVisitedBits){
        if (!currentCallPath.isEmpty() && methodParameterBits.peek().contains(bit)){
            return 1;
        }
        if (isInputBit(bit) && sec(bit) != sl.bot()){
            return 1;
        }
        return bit.deps().stream().filter(Bit::isUnknown).filter(b -> {
            if (alreadyVisitedBits.contains(b)) {
                return false;
            }
            alreadyVisitedBits.add(b);
            return true;
        }).mapToInt(b -> c1(b, alreadyVisitedBits)).sum();
    }

    public Bit choose(Bit a, Bit b){
        if (c1(a) <= c1(b) || a.isConstant()){
            return a;
        }
        return b;
    }

    public Bit notChosen(Bit a, Bit b){
        if (choose(a, b) == b){
            return a;
        }
        return b;
    }

    /* -------------------------- loop mode specific -------------------------------*/

    public int weight(Bit bit){
        return weightMap.getOrDefault(bit, 1);
    }

    public void weight(Bit bit, int weight){
        assert weight == 1 || weight == INFTY;
        if (weight == 1){
            return;
        }
        weightMap.put(bit, weight);
    }

    public boolean hasInfiniteWeight(Bit bit){
        return weight(bit) == INFTY;
    }

    /**
     * merges n into o
     * @param o
     * @param n
     * @return true if o value equals the merge result
     */
    public boolean merge(Bit o, Bit n){
        B vt = bs.sup(v(o), v(n));
        int oldDepsCount = o.deps().size();
        o.addDependencies(d(n));
        if (oldDepsCount == o.deps().size() && vt == v(o)){
            return false;
        }
        o.setVal(vt);
        return true;
    }

    public void setReturnValue(Value value){
        variableStates.get(variableStates.size() - 1).setReturnValue(value);
    }

    public Value getReturnValue(){
        return variableStates.get(variableStates.size() - 1).getReturnValue();
    }

    public long getNodeVersionUpdateCount(){
        return nodeValueState.nodeVersionUpdateCount;
    }

    /*-------------------------- methods -------------------------------*/

    public Context forceMethodInvocationHandler(MethodInvocationHandler handler) {
        methodInvocationHandler = handler;
        return this;
    }

    public void methodInvocationHandler(MethodInvocationHandler handler) {
        assert methodInvocationHandler == null;
        methodInvocationHandler = handler;
    }

    public MethodInvocationHandler methodInvocationHandler(){
        if (methodInvocationHandler == null){
            methodInvocationHandler(MethodInvocationHandler.createDefault());
        }
        return methodInvocationHandler;
    }

    public void pushNewMethodInvocationState(CallSite callSite, List<Value> arguments){
        pushNewMethodInvocationState(callSite, arguments.stream().flatMap(Value::stream).collect(Collectors.toSet()));
    }

    public void pushNewMethodInvocationState(CallSite callSite, Set<Bit> argumentBits){
        currentCallPath = currentCallPath.push(callSite);
        variableStates.push(new State());
        methodParameterBits.push(argumentBits);
        nodeValueState = nodeValueStates.get(currentCallPath);
    }

    public void popMethodInvocationState(){
        currentCallPath = currentCallPath.pop();
        variableStates.pop();
        methodParameterBits.pop();
        nodeValueState = nodeValueStates.get(currentCallPath);
    }

    public CallPath callPath(){
        return currentCallPath;
    }

    public int numberOfMethodFrames(){
        return nodeValueStates.size();
    }

    public int numberOfinfiniteWeightNodes(){
        return weightMap.size();
    }

    public void resetNodeValueStates(){
        nodeValueStates.clear();
        nodeValueState = nodeValueStates.get(currentCallPath);
    }

    public Set<Bit> sources(Sec<?> sec){
        return  sl
                .elements()
                .stream()
                .map(s -> (Sec<?>) s)
                .filter(s -> ((Lattice) sl).lowerEqualsThan(s, sec))
                .flatMap(s -> output.getBits((Sec) s).stream())
                .collect(Collectors.toSet());
    }

    public Set<Bit> sinks(Sec<?> sec){
        // an attacker at level sec can see all outputs with level <= sec
        return   sl
                .elements()
                .stream()
                .map(s -> (Sec<?>) s)
                .filter(s -> !((Lattice) sl).lowerEqualsThan(s, sec))
                .flatMap(s -> input.getBits((Sec) s).stream())
                .collect(Collectors.toSet());
    }
    
    /**
     * Does handle parameters, data dependencies and constants to return the
     * correct value
     * @param node
     * @return
     */
    public Value nodeValueRec(SDGNode node) {
    	if (nodeValueState.nodeValueMap.containsKey(node)) {
    		return nodeValueState.nodeValueMap.get(node);
    	}
    	if (node.kind == Kind.ACTUAL_IN) {
        	for (SDGEdge.Kind kind : Arrays.asList(SDGEdge.Kind.DATA_DEP)) {
        		List<SDGEdge> edges = program.sdg.getIncomingEdgesOfKind(node, kind);
        		if (edges.size() > 0) {
        			return nodeValueRec(edges.get(0).getSource());
        		}
        	}
    	}
    	return vl.bot();
    	/*System.err.println(node.getLabel());
    	assert false;
    	return vl.parse(program.getConstantInLabel(node.getLabel()));*/
    }
    
    /**
     * From the Java SE 8 vm spec:
     * 
     * The Java Virtual Machine uses local variables to pass parameters 
     * on method invocation. On class method invocation, any parameters
     * are passed in consecutive local variables starting from local 
     * variable 0. On instance method invocation, local variable 0 is 
     * always used to pass a reference to the object on which the 
     * instance method is being invoked (this in the Java programming 
     * language). Any parameters are subsequently passed in consecutive 
     * local variables starting from local variable 1. 
     */
    private Value getParamValue(SDGNode base, int useId) {
    	return getVariableValue(useId + "");
    }
    
    private void handleOutputCall(SDGNode callSite) {
		assert isOutputCall(callSite);
		List<SDGNode> param = program.getParamNodes(callSite);
		SSAInvokeInstruction instr = (SSAInvokeInstruction)program.getInstruction(callSite);
    	SymbolTable st = program.getProcSymbolTable(callSite);
		Value value = null;
		if (st.isParameter(instr.getUse(0))){
			value = getParamValue(callSite, instr.getUse(0));
		} else {
			value = nodeValueRec(param.get(0));
		}
		assert st.isStringConstant(instr.getUse(1));
		Sec<?> sec = sl.parse(st.getStringValue(instr.getUse(1)));
		addOutputValue(sec, value);
    }
	
	private boolean isOutputCall(SDGNode callSite) {
		return callSite.kind == Kind.CALL && program.isOutputMethodCall(callSite);
	}
	
	private Value evaluateCall(SDGNode callSite) {
		assert callSite.kind == Kind.CALL;
		List<SDGNode> param = program.getParamNodes(callSite);
		SSAInvokeInstruction instr = (SSAInvokeInstruction)program.getInstruction(callSite);
    	SymbolTable st = program.getProcSymbolTable(callSite);
		List<Value> args = IntStream.range(0, instr.getNumberOfUses()).mapToObj(use -> {
			if (st.isParameter(instr.getUse(0))){
				return getParamValue(callSite, instr.getUse(0));
			}
			return nodeValueRec(param.get(0));
		}).collect(Collectors.toList());
		return methodInvocationHandler.analyze(this, new NodeBasedCallSite(program.getMethodForCallSite(callSite), callSite), args);
	}
	
	/**
	 * Extension of {@link Program#workList(SDGNode, Predicate)} that handles {@link CodeUI#output(int, String)} calls
	 * @param entryNode
	 * @param nodeConsumer
	 */
	public void workList(SDGNode entryNode, Predicate<SDGNode> nodeConsumer,
			Predicate<SDGNode> ignore) {
		program.workList(entryNode, n -> {
			if (isOutputCall(n)) {
				handleOutputCall(n);
				return false;
			} else {
				return nodeConsumer.test(n);
			}
		}, ignore);
	}
	
	public void registerLeakageGraphs() {
		sl.elements().forEach(s -> {
            DotRegistry.get().store("main", "Attacker level: " + s,
                    () -> () -> LeakageCalculation.visuDotGraph(this, "", s));
			});
	}
	
	public void storeLeakageGraphs() {
		registerLeakageGraphs();
		DotRegistry.get().storeFiles();
	}
	
	/**
	 * Returns the operator for the passed node or {@code null} if the node can be ignored.
	 * @param node
	 * @return
	 */
	Operator operatorForNodeNotCached(SDGNode node) {
       	if (node.getLabel().equals("return")) {
    		return null;
    	}
       	Box<Operator> op = new Box<>(null);
       	System.err.println(node.getLabel());
       	//program.getProcIR(node).getPD
       	SSAInstruction instr = program.getInstruction(node);
       	instr.visit(new Visitor() {
       		@Override
       		public void visitBinaryOp(SSABinaryOpInstruction instruction) {
       			IOperator wop = instruction.getOperator();
       			switch ((IBinaryOpInstruction.Operator)wop) {
				case OR:
					op.val = Operator.OR;
					break;
				case ADD:
					op.val = Operator.ADD;
					break;
				case AND:
					op.val = Operator.AND;
					break;
				case DIV:
					op.val = Operator.DIVIDE;
					break;
				case MUL:
					op.val = Operator.MULTIPLY;
					break;
				case SUB:
					op.val = new Operator() {
						
						public Value compute(Context c, SDGNode node, java.util.List<Value> arguments) {
							return Operator.ADD.compute(c, node, Arrays.asList(Operator.ADD.compute(c, node, Arrays.asList(arguments.get(0), Operator.NOT.compute(c, node, Collections.singletonList(arguments.get(0))))), vl.parse(1)));
						}
						
						@Override
						public String toString(List<Value> arguments) {
							return String.format("(%s - %s)", arguments.get(0), arguments.get(1));
						}
					};
				case XOR:
					op.val = Operator.XOR;
					break;
				default:
					break;
				}
       		}
       		
       		@Override
       		public void visitUnaryOp(SSAUnaryOpInstruction instruction) {
       			switch ((IUnaryOpInstruction.Operator)instruction.getOpcode()) {
       			case NEG:
       				op.val = Operator.NOT;
       			}
       		}
       		
       		@Override
       		public void visitConditionalBranch(SSAConditionalBranchInstruction instruction) {
       			switch ((IConditionalBranchInstruction.Operator)instruction.getOperator()) {
				case EQ:
					op.val = Operator.EQUALS;
					break;
				case GE:
					op.val = new Operator() {
						
						public Value compute(Context c, SDGNode node, java.util.List<Value> arguments) {
							return Operator.NOT.compute(c, node, Arrays.asList(Operator.LESS.compute(c, node, Arrays.asList(arguments.get(0), arguments.get(1)))));
						}
						
						@Override
						public String toString(List<Value> arguments) {
							return String.format("(%s >= %s)", arguments.get(0), arguments.get(1));
						}
					};
					break;
				case GT:
					op.val = new Operator() {
						
						public Value compute(Context c, SDGNode node, java.util.List<Value> arguments) {
							return Operator.LESS.compute(c, node, Arrays.asList(arguments.get(1), arguments.get(0)));
						}
						
						@Override
						public String toString(List<Value> arguments) {
							return String.format("(%s > %s)", arguments.get(0), arguments.get(1));
						}
					};
					break;
				case LE:
					op.val = op.val = new Operator() {
						
						public Value compute(Context c, SDGNode node, java.util.List<Value> arguments) {
							return Operator.NOT.compute(c, node, Arrays.asList(Operator.LESS.compute(c, node, Arrays.asList(arguments.get(1), arguments.get(0)))));
						}
						
						@Override
						public String toString(List<Value> arguments) {
							return String.format("(%s >= %s)", arguments.get(0), arguments.get(1));
						}
					};
					break;
				case LT:
					op.val = Operator.LESS;
					break;
				case NE:
					op.val = Operator.UNEQUALS;
					break;
				}
       		}
       		
       		@Override
       		public void visitPhi(SSAPhiInstruction instruction) {
       			op.val = Operator.PHI_GENERIC;
       		}
       		
       		@Override
       		public void visitReturn(SSAReturnInstruction instruction) {
       			op.val = Operator.RETURN;
       		}
       	});
       	System.err.println(instr);
        if (op.val == null){
            throw new NildumuError(String.format("No operator for %s implemented", Program.toString(node)));
        }
        return op.val;
	}
	
	public void fixPointIteration(SDGNode entryNode) {
		new FixpointIteration(entryNode).run();
	}
	
	class FixpointIteration extends Visitor {
		
		final SDGNode entryNode;
		final Map<SDGNode, ISSABasicBlock> omittedBlocks = new HashMap<>();
		boolean changed = false;
		SDGNode node = null;
		Set<SDGNode> openLoops = new HashSet<>();
		
		public FixpointIteration(SDGNode entryNode) {
			super();
			this.entryNode = entryNode;
		}

		public void run() {
			workList(entryNode, n -> {
				if (n.getLabel().equals("many2many")) {
					return false;
				}
				node = n;
				program.getInstruction(n).visit(this);
				return changed;
			}, n -> omittedBlocks.containsValue(program.getBlock(n)));	
		}
		
		@Override
		public void visitBinaryOp(SSABinaryOpInstruction instruction) {
			evaluate(node);
		}
		
		@Override
		public void visitUnaryOp(SSAUnaryOpInstruction instruction) {
			evaluate(node);
		}
		
		@Override
		public void visitConditionalBranch(SSAConditionalBranchInstruction instruction) {
			if (isLoop()) {
				openLoops.add(node);
			}
			if (evaluate(node)) {
				Value cond = nodeValue(node);
				Bit condBit = cond.get(1);
                B condVal = condBit.val();
                if (condVal == B.U && openLoops.size() > 0){
                    weight(condBit, Context.INFTY);
                }
                if (condVal == B.ZERO && condVal != B.U) {
                    omittedBlocks.put(node, program.blockForId(node, instruction.getTarget()));
                }
                if (condVal == B.ONE && condVal != B.U) {
                	omittedBlocks.put(node, program.getNextBlock(node));
               }
			} else {
				omittedBlocks.put(node, program.blockForId(node, instruction.getTarget()));
            	omittedBlocks.put(node, program.getNextBlock(node));
			}
		}
		
		boolean isLoop() {
			Set<SDGNode> alreadyVisited = new HashSet<>();
			Stack<SDGNode> q = new Stack<>();
			q.add(node);
			while (!q.isEmpty()) {
				SDGNode cur = q.pop();
				if (cur == node) {
					return true;
				}
				if (!alreadyVisited.contains(cur)) {
					alreadyVisited.add(cur);
					q.addAll(program.sdg.getOutgoingEdgesOfKind(cur, SDGEdge.Kind.CONTROL_FLOW).stream().map(SDGEdge::getTarget).collect(Collectors.toList()));
				}
			}
			return false;
		}
		
		@Override
		public void visitPhi(SSAPhiInstruction instruction) {
			evaluate(node);
			program.getControlDeps(node).forEach(n -> {
				omittedBlocks.remove(n);
				openLoops.add(n);
			});
		}
		
		@Override
		public void visitInvoke(SSAInvokeInstruction instruction) {
			evaluate(node);
		}
   		
   		@Override
   		public void visitReturn(SSAReturnInstruction instruction) {
   			evaluate(node);
   		}
		
		boolean evaluate(SDGNode node) {
			changed = Context.this.evaluate(node);
			System.err.println(changed);
			return changed;
		}
	}
}
