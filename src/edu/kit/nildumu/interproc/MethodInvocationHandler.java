package edu.kit.nildumu.interproc;

import static edu.kit.nildumu.Lattices.bl;
import static edu.kit.nildumu.Lattices.vl;
import static edu.kit.nildumu.Lattices.B.U;
import static edu.kit.nildumu.util.Util.p;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import edu.kit.joana.ifc.sdg.graph.SDGNode;
import edu.kit.nildumu.Context;
import edu.kit.nildumu.Lattices;
import edu.kit.nildumu.Lattices.DependencySet;
import edu.kit.nildumu.Lattices.Value;
import edu.kit.nildumu.Method;
import edu.kit.nildumu.Program;
import edu.kit.nildumu.util.Pair;

/**
 * Handles the analysis of methods → implements the interprocedural part of the analysis.
 *
 * Handler classes can be registered and configured via property strings.
 */
public abstract class MethodInvocationHandler {

    private static Map<String, Pair<HandlerConfigSchema, Function<Properties, MethodInvocationHandler>>> registry = new HashMap<>();

    private static List<String> examplePropLines = new ArrayList<>();

    /**
     * Regsiter a new class of handlers
     */
    private static void register(String name, Consumer<HandlerConfigSchema> propSchemeCreator, Function<Properties, MethodInvocationHandler> creator){
        HandlerConfigSchema scheme = new HandlerConfigSchema();
        propSchemeCreator.accept(scheme);
        scheme.add("handler", null);
        registry.put(name, p(scheme, creator));
    }

    
    /**
     * Returns the handler for the passed string, the property "handler" defines the handler class
     * to be used
     */
    public static MethodInvocationHandler parse(String props){
        Properties properties = new HandlerConfigSchema().add("handler").parse(props, true);
        String handlerName = properties.getProperty("handler");
        if (!registry.containsKey(handlerName)){
            throw new MethodInvocationHandlerInitializationException(String.format("unknown handler %s, possible handlers are: %s", handlerName, registry.keySet()));
        }
        try {
            Pair<HandlerConfigSchema, Function<Properties, MethodInvocationHandler>> pair = registry.get(handlerName);
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
        Consumer<HandlerConfigSchema> propSchemeCreator = s ->
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
