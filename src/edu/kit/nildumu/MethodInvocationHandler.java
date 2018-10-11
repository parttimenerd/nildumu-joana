package edu.kit.nildumu;

import static edu.kit.nildumu.Context.INFTY;
import static edu.kit.nildumu.Context.d;
import static edu.kit.nildumu.Context.v;
import static edu.kit.nildumu.Lattices.bl;
import static edu.kit.nildumu.Lattices.ds;
import static edu.kit.nildumu.Lattices.vl;
import static edu.kit.nildumu.Lattices.B.U;
import static edu.kit.nildumu.util.Util.p;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import edu.kit.joana.ifc.sdg.graph.SDGNode;
import edu.kit.nildumu.Dominators.Node;
import edu.kit.nildumu.Lattices.Bit;
import edu.kit.nildumu.Lattices.DependencySet;
import edu.kit.nildumu.Lattices.Value;
import edu.kit.nildumu.util.DefaultMap;
import edu.kit.nildumu.util.NildumuException;
import edu.kit.nildumu.util.Pair;
import edu.kit.nildumu.util.Util.Box;
import guru.nidi.graphviz.engine.Format;
import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.model.Graph;

/**
 * Handles the analysis of methods → implements the interprocedural part of the analysis.
 *
 * Handler classes can be registered and configured via property strings.
 */
public abstract class MethodInvocationHandler {

    private static Map<String, Pair<PropertyScheme, Function<Properties, MethodInvocationHandler>>> registry = new HashMap<>();

    private static List<String> examplePropLines = new ArrayList<>();

    /**
     * Regsiter a new class of handlers
     */
    private static void register(String name, Consumer<PropertyScheme> propSchemeCreator, Function<Properties, MethodInvocationHandler> creator){
        PropertyScheme scheme = new PropertyScheme();
        propSchemeCreator.accept(scheme);
        scheme.add("handler", null);
        registry.put(name, p(scheme, creator));
    }

    public static class PropertiesParser {

        public final static int EOF = -1;

        private final String input;
        private int cur = 0;

        public PropertiesParser(String input) {
            this.input = input;
            this.cur = 0;
        }

        public boolean is(int c){
            return c == cur();
        }

        public int cur(){
            return cur < input.length() ? input.charAt(cur) : -1;
        }

        public void error(String msg){
            throw new MethodInvocationHandlerInitializationException(String.format("%s[%s]%s",input.substring(0, cur), msg, cur >= input.length() ? "" : input.substring(cur)));
        }

        private void expect(char c){
            if (cur() != c){
                error(String.format("expected %s", c));
            }
            next();
        }

        public int next(){
            cur += 1;
            while (Character.isWhitespace(cur())){
                next();
            }
            return cur();
        }

        public Properties parse(){
            Properties props = new Properties();
            while (!atEnd()){
                Pair<String, String> prop = parseProperty();
                props.setProperty(prop.first, prop.second);
                if (is(';')){
                    next();
                }
            }
            return props;
        }

        private Pair<String, String> parseProperty(){
            String id = parseIdentifier();
            expect('=');
            String argument = parseArgument();
            return p(id, argument);
        }

        private String parseIdentifier(){
            StringBuilder builder = new StringBuilder();
            while (Character.isJavaIdentifierPart(cur())){
                builder.appendCodePoint(cur());
                next();
            }
            return builder.toString();
        }

        private String parseArgument(){
            StringBuilder builder = new StringBuilder();
            int braceCounter = 0;
            while (!((is(';') || atEnd()) && braceCounter == 0)){
                switch (cur()){
                    case EOF:
                        error("unexpected end");
                    case '{':
                        braceCounter++;
                        break;
                    case '}':
                        braceCounter--;
                }
                builder.appendCodePoint(cur());
                next();
            }
            String ret = builder.toString();
            if (ret.charAt(0) == '{'){
                return ret.substring(1, ret.length() - 1);
            }
            return ret;
        }

        private boolean atEnd(){
            return cur() == EOF;
        }
    }

    /**
     * Returns the handler for the passed string, the property "handler" defines the handler class
     * to be used
     */
    public static MethodInvocationHandler parse(String props){
        Properties properties = new PropertyScheme().add("handler").parse(props, true);
        String handlerName = properties.getProperty("handler");
        if (!registry.containsKey(handlerName)){
            throw new MethodInvocationHandlerInitializationException(String.format("unknown handler %s, possible handlers are: %s", handlerName, registry.keySet()));
        }
        try {
            Pair<PropertyScheme, Function<Properties, MethodInvocationHandler>> pair = registry.get(handlerName);
            return pair.second.apply(pair.first.parse(props));
        } catch (MethodInvocationHandlerInitializationException error){
            throw error;
        } catch (Error error){
            throw new MethodInvocationHandlerInitializationException(String.format("parsing \"%s\": %s", props, error.getMessage()));
        }
    }
    
    public static MethodInvocationHandler parseAndSetup(Program program, String props){
    	MethodInvocationHandler handler = parse(props);
    	handler.setup(program);
    	return handler;
    }

    public static List<String> getExamplePropLines(){
        return Collections.unmodifiableList(examplePropLines);
    }

    public static class MethodInvocationHandlerInitializationException extends NildumuException {

        public MethodInvocationHandlerInitializationException(String message) {
            super("Error initializing the method invocation handler: " + message);
        }
    }

    /**
     * A basic scheme that defines the properties (with their possible default values) for each
     * handler class
     */
    public static class PropertyScheme {
        final char SEPARATOR = ';';
        private final Map<String, String> defaultValues;

        PropertyScheme() {
            defaultValues = new HashMap<>();
        }

        public PropertyScheme add(String param, String defaultValue){
            defaultValues.put(param, defaultValue);
            return this;
        }

        public PropertyScheme add(String param){
            return add(param, null);
        }

        public Properties parse(String props){
            return parse(props, false);
        }

        public Properties parse(String props, boolean allowAnyProps){
            if (!props.contains("=")){
                props = String.format("handler=%s", props);
            }
            Properties properties = new PropertiesParser(props).parse();
            for (Map.Entry<String, String> defaulValEntry : defaultValues.entrySet()) {
                if (!properties.containsKey(defaulValEntry.getKey())){
                    if (defaulValEntry.getValue() == null){
                        throw new MethodInvocationHandlerInitializationException(String.format("for string \"%s\": property %s not set", props, defaulValEntry.getKey()));
                    }
                    properties.setProperty(defaulValEntry.getKey(), defaulValEntry.getValue());
                }
            }
            if (!allowAnyProps) {
                for (String prop : properties.stringPropertyNames()) {
                    if (!defaultValues.containsKey(prop)) {
                        throw new MethodInvocationHandlerInitializationException(String.format("for string \"%s\": property %s unknown, valid properties are: %s", props, prop, defaultValues.keySet().stream().sorted().collect(Collectors.joining(", "))));
                    }
                }
            }
            return properties;
        }
    }

    public static class CallSite {
    	final Method method;

		public CallSite(Method method) {
			this.method = method;
		}
    }
    
    public static class NodeBasedCallSite extends CallSite {

    	final SDGNode callSite;
    	
		public NodeBasedCallSite(Method method, SDGNode callSite) {
			super(method);
			this.callSite = callSite;
		}
    	
		@Override
		public int hashCode() {
			return callSite.hashCode();
		}
		
		@Override
		public boolean equals(Object obj) {
			return obj instanceof NodeBasedCallSite && ((NodeBasedCallSite)obj).callSite.equals(callSite);
		}
    }
    
    /**
     * A call string based handler that just inlines a function.
     * If a function was inlined in the current call path more than a defined number of times,
     * then another handler is used to compute a conservative approximation.
     */
    public static class CallStringHandler extends MethodInvocationHandler {
    	
        final int maxRec;

        final MethodInvocationHandler botHandler;

        private DefaultMap<Method, Integer> methodCallCounter = new DefaultMap<>((map, method) -> 0);

        private Program program = null;
        
        CallStringHandler(int maxRec, MethodInvocationHandler botHandler) {
            this.maxRec = maxRec;
            this.botHandler = botHandler;
        }

        @Override
        public void setup(Program program) {
            botHandler.setup(program);
            this.program = program;
        }

        @Override
        public Value analyze(Context c, CallSite callSite, List<Value> arguments) {
            Method method = callSite.method;
            System.err.printf("                Arguments for rec depth %d: %s\n", methodCallCounter.get(method), arguments);
            if (methodCallCounter.get(method) < maxRec) {
                methodCallCounter.put(method, methodCallCounter.get(method) + 1);
                c.pushNewMethodInvocationState(callSite, arguments);
                for (int i = 0; i < arguments.size(); i++) {
                    c.setParamValue(i + 1, arguments.get(i));
                }
                c.fixPointIteration(method.entry);
                Value ret = c.getReturnValue();
                c.popMethodInvocationState();
                methodCallCounter.put(method, methodCallCounter.get(method) - 1);
                return ret;
            }
            return botHandler.analyze(c, callSite, arguments);
        }
        
        @Override
        public String getName() {
        	return "call_string";
        }
    }

    static class BitGraph {

        final Context context;
        final List<Value> parameters;
        private final Set<Bit> parameterBits;
        /**
         * bit → parameter number, index
         */
        private final Map<Bit, Pair<Integer, Integer>> bitInfo;

        final Value returnValue;

        final List<Integer> paramBitsPerReturnValue;

        BitGraph(Context context, List<Value> parameters, Value returnValue) {
            this.context = context;
            this.parameters = parameters;
            this.parameterBits = parameters.stream().flatMap(Value::stream).collect(Collectors.toSet());
            this.bitInfo = new HashMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                Value param = parameters.get(i);
                for (int j = 1; j <= param.size(); j++) {
                    bitInfo.put(param.get(j), p(i, j));
                }
            }
            this.returnValue = returnValue;
            assertThatAllBitsAreNotNull();
            paramBitsPerReturnValue = returnValue.stream().map(b -> calcReachableParamBits(b).size()).collect(Collectors.toList());
        }

        private void assertThatAllBitsAreNotNull(){
            returnValue.forEach(b -> {
                assert b != null: "Return bits shouldn't be null";
            });
            vl.walkBits(Arrays.asList(returnValue), b -> {
                assert b != null: "Bits shouldn't be null";
            });
            vl.walkBits(parameters, b -> {
                assert b != null: "Parameters bits shouldn't null";
            });
        }

        public static Bit cloneBit(Context context, Bit bit, DependencySet deps){
            Bit clone;
            if (bit.isUnknown()) {
                clone = bl.create(U, deps);
            } else {
                clone = bl.create(v(bit));
            }
            return clone;
        }

        public Value applyToArgs(Context context, List<Value> arguments){
            List<Value> extendedArguments = arguments;
            Map<Bit, Bit> newBits = new HashMap<>();
            // populate
            vl.walkBits(returnValue, bit -> {
                if (parameterBits.contains(bit)){
                    Pair<Integer, Integer> loc = bitInfo.get(bit);
                    Bit argBit = extendedArguments.get(loc.first).get(loc.second);
                    newBits.put(bit, argBit);
                } else {
                    Bit clone = cloneBit(context, bit, d(bit));
                    clone.value(bit.value());
                    newBits.put(bit, clone);
                }
            });
            DefaultMap<Value, Value> newValues = new DefaultMap<Value, Value>((map, value) -> {
                if (parameters.contains(value)){
                    return arguments.get(parameters.indexOf(value));
                }
                Value clone = value.map(b -> {
                    if (!parameterBits.contains(b)) {
                        return newBits.get(b);
                    }
                    return b;
                });
                clone.node(value.node());
                return value;
            });
            // update dependencies
            newBits.forEach((old, b) -> {
                if (!parameterBits.contains(old)) {
                    b.alterDependencies(newBits::get);
                }
                //b.value(old.value());
            });
            return returnValue.map(newBits::get);
        }

        /**
         * Returns the bit of the passed set, that are reachable from the bit
         */
        public Set<Bit> calcReachableBits(Bit bit, Set<Bit> bits){
            Set<Bit> reachableBits = new HashSet<>();
            bl.walkBits(bit, b -> {
                if (bits.contains(b)){
                    reachableBits.add(b);
                }
            }, b -> false);
            return reachableBits;
        }

        public Set<Bit> calcReachableParamBits(Bit bit){
            return calcReachableBits(bit, parameterBits);
        }

        public Set<Bit> minCutBits(){
            return minCutBits(returnValue.bitSet(), parameterBits);
        }

        public Set<Bit> minCutBits(Set<Bit> outputBits, Set<Bit> inputBits){
            return MinCut.compute(outputBits, inputBits, context::weight).minCut;
        }

        public Set<Bit> minCutBits(Set<Bit> outputBits, Set<Bit> inputBits, int outputWeight){
            return MinCut.compute(outputBits, inputBits, b -> outputBits.contains(b) ? outputWeight : context.weight(b)).minCut;
        }

        private Graph createDotGraph(String name, boolean withMinCut){
            return DotRegistry.createDotGraph(context, name, IntStream.range(0, parameters.size())
                    .mapToObj(i -> new DotRegistry.Anchor(String.format("param %d", i), parameters.get(i))
                    ).collect(Collectors.toList()),
                    new DotRegistry.Anchor("return", returnValue),
                    withMinCut ? minCutBits(returnValue.bitSet(), parameterBits, INFTY) : Collections.emptySet());
        }

        public void writeDotGraph(Path folder, String name, boolean withMinCut){
            Path path = folder.resolve(name + ".dot");
            try {
                Files.createDirectories(folder);
                Graphviz.fromGraph(createDotGraph(name, withMinCut)).render(Format.PLAIN).toFile(path.toFile());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof BitGraph){
                //assert ((BitGraph)obj).parameterBits == this.parameterBits;
                return paramBitsPerReturnValue.equals(((BitGraph)obj).paramBitsPerReturnValue);
            }
            return false;
        }
    }

    /**
     * A summary-edge based handler. It creates for each function beforehand summary edges:
     * these edges connect the parameter bits and the return bits. The analysis assumes that all
     * parameter bits might have a statically unknown value. The summary-edge analysis builds the
     * summary edges using a fix point iteration over the call graph. Each analysis of a method
     * runs the normal analysis of the method body and uses the prior summary edges if a method is
     * called in the body. The resulting bit graph is then reduced.
     * <p/>
     * It supports coinduction ("mode=coind") and induction ("mode=ind").
     * <p/>
     * Induction starts with no edges between parameter bits and return bits and iterates till no
     * new connection between a return bit and a parameter bit is added. It only works for programs
     * without recursion.
     * <p/>
     * Coinduction starts with the an over approximation produced by another handler ("bot" property)
     * and iterates at most a configurable number of times ("maxiter" property), by default this
     * number is {@value Integer#MAX_VALUE}.
     * <p/>
     * The default reduction policy is to connect all return bits with all parameter bits that they
     * depend upon ("reduction=all").
     * And improved version ("reduction=mincut") includes the minimal cut bits of the bit graph from
     * the return to the parameter bits, assuming that the return bits have infinite weights.
     */
    public static class SummaryHandler extends MethodInvocationHandler {

        public static enum Mode {
            COINDUCTION,
            /**
             * The induction mode doesn't work with recursion and has spurious errors
             */
            INDUCTION,
            AUTO
        }

        public static enum Reduction {
            BASIC,
            MINCUT;
        }

        final int maxIterations;

        final Mode mode;

        final MethodInvocationHandler botHandler;

        final Path dotFolder;

        final Reduction reductionMode;

        final int callStringMaxRec;

        Map<Method, BitGraph> methodGraphs;

        Dominators<Method> callGraph;
        
        Map<Method, CallSite> callSites;

        public SummaryHandler(int maxIterations, Mode mode, MethodInvocationHandler botHandler, Path dotFolder, Reduction reductionMode, int callStringMaxRec) {
            this.maxIterations = maxIterations;
            this.mode = mode;
            this.reductionMode = reductionMode;
            this.callStringMaxRec = callStringMaxRec;
            assert !(mode == Mode.INDUCTION || mode == Mode.AUTO) || (maxIterations == Integer.MAX_VALUE);
            this.botHandler = botHandler;
            this.dotFolder = dotFolder;
        }

        @Override
        public void setup(Program program) {
            Mode _mode = mode;
            callGraph = program.getMethodDominators();
            callSites = new DefaultMap<Method, CallSite>((map, m) -> {
            	return new CallSite(m);
            });
            if (_mode == Mode.AUTO){
                _mode = Mode.INDUCTION;
            }
            
           /* if (callGraph.containsRecursion()){
                if (_mode == Mode.AUTO){
                    _mode = Mode.COINDUCTION;
                }
                if (_mode == Mode.INDUCTION){
                    System.err.println("Induction cannot be used for programs with reduction");
                  //  throw new MethodInvocationHandlerInitializationError("Induction cannot be used for programs with reduction");
                }
            }*/
            DotRegistry.get().storeFiles();
            Mode usedMode = _mode;
            Context c = program.context;
            Map<Node<Method>, BitGraph> state = new HashMap<>();
            MethodInvocationHandler handler = createHandler(m -> state.get(callGraph.elemToNode.get(m)));
            Box<Integer> iteration = new Box<>(0);
            callGraph.<BitGraph>worklist((node, s) -> {
                if (node.isEntryNode || iteration.val > maxIterations){
                    return s.get(node);
                }
                iteration.val += 1;
                BitGraph graph = methodIteration(program.context, node.elem, handler, s.get(node).parameters);
                String name = String.format("%3d %s", iteration.val, node.elem.toBCString());
                if (dotFolder != null){
                    graph.writeDotGraph(dotFolder, name, true);
                }
                DotRegistry.get().store("summary", name,
                        () -> () -> graph.createDotGraph("", true));
                BitGraph reducedGraph = reduce(c, graph);
                if (dotFolder != null){
                    graph.writeDotGraph(dotFolder, name + " [reduced]", false);
                }
                DotRegistry.get().store("summary",  name + " [reduced]",
                        () -> () -> reducedGraph.createDotGraph("", false));
                return reducedGraph;
            }, node ->  {
                BitGraph graph = bot(program, node.elem, usedMode);
                String name = String.format("%3d %s", iteration.val, node.elem.toBCString());
                if (dotFolder != null){
                    graph.writeDotGraph(dotFolder, name, false);
                }
                DotRegistry.get().store("summary", name,
                        () -> () -> graph.createDotGraph("", false));
                return graph;
            }
            , node -> node.getIns().stream().filter(n -> !n.isEntryNode).collect(Collectors.toSet()),
            state);
            methodGraphs = state.entrySet().stream().collect(Collectors.toMap(e -> e.getKey().elem, Map.Entry::getValue));
        }

        BitGraph bot(Program program, Method method, Mode usedMode){
            List<Value> parameters = generateParameters(program, method);
            if (usedMode == Mode.COINDUCTION) {
                Value returnValue = botHandler.analyze(program.context, callSites.get(method), parameters);
                return new BitGraph(program.context, parameters, returnValue);
            }
            return new BitGraph(program.context, parameters, program.createUnknownValue(method.method.getSignature().getReturnType()));
        }

        List<Value> generateParameters(Program program, Method method){
            System.err.println(method);
        	return method.getParameters().stream().map(p ->
                program.createUnknownValue(p.getType())
            ).collect(Collectors.toList());
        }

        BitGraph methodIteration(Context c, Method method, MethodInvocationHandler handler, List<Value> parameters){
            c.pushNewMethodInvocationState(callSites.get(method), parameters.stream().flatMap(Value::stream).collect(Collectors.toSet()));
            for (int i = 0; i < parameters.size(); i++) {
                c.setParamValue(i + 1, parameters.get(i));
            }
            c.forceMethodInvocationHandler(handler);
            c.fixPointIteration(method.entry);
            Value ret = c.getReturnValue();
            c.popMethodInvocationState();
            c.forceMethodInvocationHandler(this);
            return new BitGraph(c, parameters, ret);
        }

        MethodInvocationHandler createHandler(Function<Method, BitGraph> curVersion){
            MethodInvocationHandler handler = new MethodInvocationHandler() {
                @Override
                public Value analyze(Context c, CallSite callSite, List<Value> arguments) {
                    return curVersion.apply(callSite.method).applyToArgs(c, arguments);
                }
                
                @Override
                public String getName() {
                	return "internal";
                }
            };
            if (callStringMaxRec > 0){
                return new CallStringHandler(callStringMaxRec, handler);
            }
            return handler;
        }

        BitGraph reduce(Context context, BitGraph bitGraph){
            switch (reductionMode) {
                case BASIC:
                    return basicReduce(context, bitGraph);
                case MINCUT:
                    return minCutReduce(context, bitGraph);
            }
            return null;
        }

        /**
         * basic implementation, just connects a result bit with all reachable parameter bits
         */
        BitGraph basicReduce(Context context, BitGraph bitGraph){
            DefaultMap<Bit, Bit> newBits = new DefaultMap<Bit, Bit>((map, bit) -> {
                return BitGraph.cloneBit(context, bit, ds.create(bitGraph.calcReachableParamBits(bit)));
            });
            Value ret = bitGraph.returnValue.map(newBits::get);
            ret.node(bitGraph.returnValue.node());
            return new BitGraph(context, bitGraph.parameters, ret);
        }

        BitGraph minCutReduce(Context context, BitGraph bitGraph) {
            Set<Bit> anchorBits = new HashSet<>(bitGraph.parameterBits);
            Set<Bit> minCutBits = bitGraph.minCutBits(bitGraph.returnValue.bitSet(), bitGraph.parameterBits, INFTY);
            anchorBits.addAll(minCutBits);
            Map<Bit, Bit> newBits = new HashMap<>();
            // create the new bits
            Stream.concat(bitGraph.returnValue.stream(), minCutBits.stream()).forEach(b -> {
                Set<Bit> reachable = bitGraph.calcReachableBits(b, anchorBits);
                if (!b.deps().contains(b)){
                    reachable.remove(b);
                }
                Bit newB = BitGraph.cloneBit(context, b, ds.create(reachable));
                newB.value(b.value());
                newBits.put(b, newB);
            });
            bitGraph.parameterBits.forEach(b -> newBits.put(b, b));
            // update the control dependencies
            newBits.forEach((o, b) -> {
                b.alterDependencies(newBits::get);
            });
            Value ret = bitGraph.returnValue.map(newBits::get);
            ret.node(bitGraph.returnValue.node());
            BitGraph newGraph = new BitGraph(context, bitGraph.parameters, ret);
            //assert !isReachable || newGraph.calcReachableBits(newGraph.returnValue.get(1), newGraph.parameters.get(0).bitSet()).size() > 0;
            return newGraph;
        }

        @Override
        public Value analyze(Context c, CallSite callSite, List<Value> arguments) { 
        	return methodGraphs.get(callSite.method).applyToArgs(c, arguments);
        }
        
        @Override
        public String getName() {
        	return "summary";
        }
    }

    static {
        register("all", s -> {}, ps -> new MethodInvocationHandler(){
            @Override
            public Value analyze(Context c, CallSite callSite, List<Value> arguments) {
                if (arguments.isEmpty() || !callSite.method.hasReturnValue()){
                    return vl.bot();
                }
                DependencySet set = arguments.stream().flatMap(Value::stream).collect(DependencySet.collector());
                return IntStream.range(0, arguments.stream().mapToInt(Value::size).max().getAsInt()).mapToObj(i -> bl.create(U, set)).collect(Value.collector());
            }
            
            public String getName() {
            	return "all";
            }
        });
        examplePropLines.add("handler=all");
        register("call_string", s -> s.add("maxrec", "2").add("bot", "all"), ps -> {
            return new CallStringHandler(Integer.parseInt(ps.getProperty("maxrec")), parse(ps.getProperty("bot")));
        });
        examplePropLines.add("handler=call_string;maxrec=2;bot=all");
        examplePropLines.add("handler=call_string;maxrec=2;bot={handler=summary;bot=call_string}");
        Consumer<PropertyScheme> propSchemeCreator = s ->
                s.add("maxiter", "1")
                        .add("bot", "all")
                        .add("mode", "auto")
                        .add("reduction", "mincut")
                        .add("csmaxrec", "0")
                        .add("dot", "");
        register("summary", propSchemeCreator, ps -> {
            Path dotFolder = ps.getProperty("dot").equals("") ? null : Paths.get(ps.getProperty("dot"));
            return new SummaryHandler(ps.getProperty("mode").equals("coind") ? Integer.parseInt(ps.getProperty("maxiter")) : Integer.MAX_VALUE,
                    ps.getProperty("mode").equals("ind") ? SummaryHandler.Mode.INDUCTION : (ps.getProperty("mode").equals("auto") ? SummaryHandler.Mode.AUTO : SummaryHandler.Mode.COINDUCTION),
                    parse(ps.getProperty("bot")), dotFolder, SummaryHandler.Reduction.valueOf(ps.getProperty("reduction").toUpperCase()), Integer.parseInt(ps.getProperty("csmaxrec")));
        });
        examplePropLines.add("handler=summary;bot=all;reduction=basic");
        examplePropLines.add("handler=summary;bot=all;reduction=mincut");
        //examplePropLines.add("handler=summary_mc;mode=ind");
    }

    public static MethodInvocationHandler createDefault(){
        return parse(getDefaultPropString());
    }

    public static String getDefaultPropString(){
        return "handler=call_string;maxrec=2;bot=all";
    }

    public void setup(Program program){
    }

    public abstract Lattices.Value analyze(Context c, CallSite callSite, List<Value> arguments);

    public abstract String getName();
}
