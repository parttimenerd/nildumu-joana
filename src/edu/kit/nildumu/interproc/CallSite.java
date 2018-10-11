package edu.kit.nildumu.interproc;

import edu.kit.joana.ifc.sdg.graph.SDGNode;
import edu.kit.nildumu.Method;

public class CallSite {
	
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
	
	public final Method method;

	public CallSite(Method method) {
		this.method = method;
	}
}