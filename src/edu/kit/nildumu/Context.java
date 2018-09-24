package edu.kit.nildumu;

import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

import edu.kit.joana.ifc.sdg.graph.SDGEdge;
import edu.kit.joana.ifc.sdg.graph.SDGNode;
import edu.kit.joana.ifc.sdg.graph.SDGNode.Kind;
import edu.kit.nildumu.ui.CodeUI;
import edu.kit.nildumu.util.DefaultMap;
import edu.kit.nildumu.util.NildumuError;
import edu.kit.nildumu.util.Pair;

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
        public Operator defaultValue(Map<SDGNode, Operator> map, SDGNode key) {
            //Operator op = key.getOperator(Context.this); // TODO
        	if (key.getLabel().equals("return")) {
        		return null;
        	}
        	Operator op = null;
            if (op == null){
                throw new NildumuError(String.format("No operator for %s implemented", key));
            }
            return op;
        }
    }, FORBID_DELETIONS, FORBID_VALUE_UPDATES);

    public static class CallPath {
        final List<SDGNode> path;

        CallPath(){
            this(Collections.emptyList());
        }

        CallPath(List<SDGNode> path) {
            this.path = path;
        }

        public CallPath push(SDGNode callSite){
            List<SDGNode> newPath = new ArrayList<>(path);
            newPath.add(callSite);
            return new CallPath(newPath);
        }

        public CallPath pop(){
            List<SDGNode> newPath = new ArrayList<>(path);
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

        public SDGNode peek() {
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
    	// TODO
       /* if (node instanceof ParameterAccessNode){
            return getVariableValue(((ParameterAccessNode) node).definition);
        } else if (node instanceof VariableAccessNode){
            return nodeValue(((VariableAccessNode) node).definingExpression);
        } else if (node instanceof WrapperNode){
            return ((WrapperNode<Value>) node).wrapped;
        }*/
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

    @SuppressWarnings("unchecked")
    public List<SDGNode> paramNode(SDGNode node){
    	return Collections.emptyList();
    }

    private final Map<SDGNode, List<Integer>> lastParamVersions = new HashMap<>();

    private boolean compareAndStoreParamVersion(SDGNode node){
    	System.err.println(program.toString(node));
        List<Integer> curVersions = paramNode(node).stream().map(nodeValueState.nodeVersionMap::get).collect(Collectors.toList());
        boolean somethingChanged = true;
        if (lastParamVersions.containsKey(node)){
            somethingChanged = lastParamVersions.get(node).equals(curVersions);
        }
        lastParamVersions.put(node, curVersions);
        return somethingChanged;
    }

    public boolean evaluate(SDGNode node){
        log(() -> "Evaluate node " + node + " " + nodeValue(node).get(1).deps().size());

        boolean paramsChanged = compareAndStoreParamVersion(node);
        if (!paramsChanged){
            return false;
        }

        List<SDGNode> paramNodes = paramNode(node);

        List<Value> args = paramNodes.stream().map(this::nodeValue).collect(Collectors.toList());
        Value newValue = op(node, args);
        boolean somethingChanged = false;
        if (nodeValue(node) != vl.bot()) { // dismiss first iteration
            Value oldValue = nodeValue(node);
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
                nodeValueState.nodeVersionMap.put(node, nodeValueState.nodeVersionMap.get(node) + 1);
            }
        } else {
            somethingChanged = nodeValue(node).valueEquals(vl.bot());
        }
        nodeValue(node, newValue);
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

    public void pushNewMethodInvocationState(SDGNode callSite, List<Value> arguments){
        pushNewMethodInvocationState(callSite, arguments.stream().flatMap(Value::stream).collect(Collectors.toSet()));
    }

    public void pushNewMethodInvocationState(SDGNode callSite, Set<Bit> argumentBits){
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
    		return getVariableValue(node.getLocalUseNames()[0]);
    	}
    	for (SDGEdge.Kind kind : Arrays.asList(SDGEdge.Kind.DATA_DEP)) {
    		List<SDGEdge> edges = program.sdg.getIncomingEdgesOfKind(node, kind);
    		if (edges.size() > 0) {
    			return nodeValueRec(edges.get(0).getTarget());
    		}
    	}
    	return vl.parse(program.getConstantInLabel(node.getLabel()));
    }
    
    private void handleOutputCall(SDGNode callSite) {
		assert isOutputCall(callSite);
		List<SDGNode> param = program.getParamNodes(callSite);
		Value value = nodeValueRec(param.get(0));
		Sec<?> sec = sl.parse(program.getConstantInLabel(param.get(1).getLabel()));
		addOutputValue(sec, value);
    }
	
	private boolean isOutputCall(SDGNode callSite) {
		return callSite.kind == Kind.CALL && program.isOutputMethodCall(callSite);
	}
	
	/**
	 * Extension of {@link Program#workList(SDGNode, Predicate)} that handles {@link CodeUI#output(int, String)} calls
	 * @param entryNode
	 * @param nodeConsumer
	 */
	public void workList(SDGNode entryNode, Predicate<SDGNode> nodeConsumer) {
		program.workList(entryNode, n -> {
			if (isOutputCall(n)) {
				handleOutputCall(n);
				return false;
			} else {
				return nodeConsumer.test(n);
			}
		});
	}
	
	public void fixPointIteration(SDGNode entryNode) {
		workList(entryNode, n -> {
			if (n.getLabel().equals("many2many")) {
				return false;
			}
			return evaluate(n);
		});
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
}
