package edu.kit.nildumu;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import edu.kit.nildumu.Lattices.Bit;
import edu.kit.nildumu.Lattices.Sec;
import edu.kit.nildumu.Lattices.Value;
import edu.kit.nildumu.util.DefaultMap;
import guru.nidi.graphviz.model.Graph;

public class LeakageCalculation {


    /** An abstract leakage graph */
    public abstract static class AbstractLeakageGraph {

        final Context context;

        protected AbstractLeakageGraph(Context context) {
            this.context = context;
        }

        public abstract int leakage(Sec<?> outputLevel);

        public Map<Sec<?>, Integer> leakages() {
            return context.sl.elements().stream().collect(Collectors.toMap(s -> (Sec<?>)s, s -> s == context.sl.top() ? 0 : leakage(s)));
        }

        public abstract Set<Bit> minCutBits(Sec<?> sec);
    }

    public static class MinCutLeakageGraph extends AbstractLeakageGraph {

        private Map<Sec<?>, MinCut.ComputationResult> compRes;

        protected MinCutLeakageGraph(Context context) {
            super(context);
            compRes = new DefaultMap<>((map, sec) -> {
                return MinCut.compute(context.sources(sec), context.sinks(sec), context::weight);
            });
        }

        @Override
        public int leakage(Sec<?> outputLevel) {
            return compRes.get(outputLevel).maxFlow;
        }

        @Override
        public Set<Bit> minCutBits(Sec<?> sec) {
            return compRes.get(sec).minCut;
        }
    }


    public static Graph visuDotGraph(Context context, String name, Sec<?> sec){
        Set<Bit> minCut = context.computeLeakage().get(sec).minCut;
        return DotRegistry.createDotGraph(context, name,
                Collections.singletonList(new DotRegistry.Anchor("input", context.sinks(sec).stream().collect(Value.collector()))),
                new DotRegistry.Anchor("output", context.sources(sec).stream().collect(Value.collector())), minCut);
    }
}
