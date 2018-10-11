package edu.kit.nildumu.interproc;

import java.util.List;

import edu.kit.nildumu.Context;
import edu.kit.nildumu.Method;
import edu.kit.nildumu.Program;
import edu.kit.nildumu.Lattices.Value;
import edu.kit.nildumu.util.DefaultMap;

/**
 * A call string based handler that just inlines a function.
 * If a function was inlined in the current call path more than a defined number of times,
 * then another handler is used to compute a conservative approximation.
 */
public class CallStringHandler extends MethodInvocationHandler {
	
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