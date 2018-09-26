package edu.kit.nildumu;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

import edu.kit.nildumu.util.DefaultMap;
import guru.nidi.graphviz.attribute.*;
import guru.nidi.graphviz.model.*;

import static guru.nidi.graphviz.model.Factory.*;
import static edu.kit.nildumu.util.Util.Box;

/**
 * Dominator calculation on arbitrary graphs
 */
public class Dominators<T> {

    public static class Node<T> {
        final T elem;
        private final Set<Node<T>> outs;
        private final Set<Node<T>> ins;
        final boolean isEntryNode;

		public Node(T elem, Set<Node<T>> outs, Set<Node<T>> ins, boolean isEntryNode) {
			this.elem = elem;
			this.outs = outs;
			this.ins = ins;
			this.isEntryNode = isEntryNode;
		}

		public Node(T elem){
            this(elem, new HashSet<>(), new HashSet<>(), false);
        }

        private void addOut(Node<T> node){
            outs.add(node);
            node.ins.add(this);
        }

        @Override
        public String toString() {
            return elem.toString();
        }

        public Set<Node<T>> transitiveOutHull(){
            Set<Node<T>> alreadyVisited = new LinkedHashSet<>();
            Queue<Node<T>> queue = new ArrayDeque<>();
            queue.add(this);
            while (queue.size() > 0){
                Node<T> cur = queue.poll();
                if (!alreadyVisited.contains(cur)){
                    alreadyVisited.add(cur);
                    queue.addAll(cur.outs);
                }
            }
            return alreadyVisited;
        }

        public Set<Node<T>> transitiveOutHullAndSelf() {
            Set<Node<T>> nodes = transitiveOutHull();
            nodes.add(this);
            return nodes;
        }

        public List<Node<T>> transitiveOutHullAndSelfInPostOrder(){
            return transitiveOutHullAndSelfInPostOrder(new HashSet<>());
        }

        private List<Node<T>> transitiveOutHullAndSelfInPostOrder(Set<Node<T>> alreadyVisited){
            alreadyVisited.add(this);
            return Stream.concat(outs.stream()
                    .filter(n -> !alreadyVisited.contains(n))
                    .flatMap(n -> n.transitiveOutHullAndSelfInPostOrder(alreadyVisited).stream()),
                    Stream.of(this)).collect(Collectors.toList());
        }

        public Graph createDotGraph(Function<Node<T>, Attributes> attrSupplier){
            return graph().graphAttr().with(RankDir.TOP_TO_BOTTOM).directed().with((guru.nidi.graphviz.model.Node[])transitiveOutHullAndSelf()
                    .stream().map(n -> node(n.toString())
                            .link((String[])n.outs.stream()
                            .map(m -> m.toString()).toArray(i -> new String[i])).with(attrSupplier.apply(n))
                     ).toArray(i -> new guru.nidi.graphviz.model.Node[i]));
        }

        public Set<Node<T>> getOuts() {
            return Collections.unmodifiableSet(outs);
        }

        public Set<Node<T>> getIns() {
            return Collections.unmodifiableSet(ins);
        }

        public T getElem() {
            return elem;
        }

		@Override
		public int hashCode() {
			return elem.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			return obj.getClass() == Node.class && ((Node<T>)obj).elem.equals(elem);
		}
        
    }
    
    final Node<T> entryNode;
    final Function<T, Collection<T>> outs;
    final Map<T, Node<T>> elemToNode;
    final Map<Node<T>, Set<Node<T>>> dominators;
    final Map<Node<T>, Integer> loopDepths;

    public Dominators(T entryElem, Function<T, Collection<T>> outs) {
        this.outs = outs;
        this.entryNode =
                new Node<T>(entryElem,
                        new HashSet<>(),
                        Collections.emptySet(),
                        true);
        this.elemToNode =
                Stream.concat(transitiveHull(entryElem, outs).stream().map(Node<T>::new),
                		Stream.of(entryNode))
                        .collect(Collectors.toMap(n -> n.elem, n -> n));
        elemToNode
                .entrySet()
                .forEach(
                        e -> {
                            outs.apply(e.getKey())
                                    .forEach(m -> e.getValue().addOut(elemToNode.get(m)));
                        });
        dominators = dominators(entryNode);
        loopDepths = calcLoopDepth(entryNode, dominators);
    } 
  
    public void registerDotGraph(String topic, String name) {
        DotRegistry.get().store(topic, name,
                () -> () -> entryNode.createDotGraph(n -> Records.of(loopDepths.get(n) + "", n.toString())));
    }
    
    static <T> Set<T> transitiveHull(T elem, Function<T, Collection<T>> outs){
    	 Set<T> alreadyVisited = new LinkedHashSet<>();
         Queue<T> queue = new ArrayDeque<>();
         queue.add(elem);
         while (queue.size() > 0){
             T cur = queue.poll();
             if (!alreadyVisited.contains(cur)){
                 alreadyVisited.add(cur);
                 queue.addAll(outs.apply(cur));
             }
         }
         alreadyVisited.remove(elem);
         return alreadyVisited;
    }

    public <R> Map<Node<T>, R> worklist(
            BiFunction<Node<T>, Map<Node<T>, R>, R> action,
            Function<Node<T>, R> bot,
            Function<Node<T>, Set<Node<T>>> next,
            Map<Node<T>, R> state) {
        return worklist(entryNode, action, bot, next, loopDepths::get, state);
    }

    public Set<T> dominators(T elem){
        return dominators.get(elemToNode.get(elem)).stream().map(Node<T>::getElem).collect(Collectors.toSet());
    }

    public int loopDepth(T elem){
        return loopDepths.get(elemToNode.get(elem));
    }

    public boolean containsLoops(){
        return loopDepths.values().stream().anyMatch(l -> l > 0);
    }

    public static <T> Map<Node<T>, Set<Node<T>>> dominators(Node<T> entryNode) {
        Set<Node<T>> bot = entryNode.transitiveOutHullAndSelf();
        return worklist(
        		entryNode,
                (n, map) -> {
                        Set<Node<T>> nodes = new HashSet<>(n.ins.stream().filter(n2 -> n != n2).map(map::get).reduce((s1, s2) -> {
                            Set<Node<T>> intersection = new HashSet<>(s1);
                            intersection.retainAll(s2);
                            return intersection;
                        }).orElseGet(() -> new HashSet<>()));
                        nodes.add(n);
                        return nodes;
                },
                n -> n == entryNode ? Collections.singleton(n) : bot,
                Node<T>::getOuts,
                n -> 1,
                new HashMap<>());
    }

    public static <T> Map<Node<T>, Integer> calcLoopDepth(Node<T> mainNode, Map<Node<T>, Set<Node<T>>> dominators){
        Set<Node<T>> loopHeaders = new HashSet<>();
        dominators.forEach((n, dom) -> {
            dom.forEach(d -> {
                if (n.outs.contains(d)){
                    loopHeaders.add(d);
                }
            });
        });
        Map<Node<T>, Set<Node<T>>> dominates = new DefaultMap<>((n, map) -> new HashSet<>());
        dominators.forEach((n, dom) -> {
            dom.forEach(d -> dominates.get(d).add(n));
        });
        Map<Node<T>, Set<Node<T>>> dominatesDirectly = new DefaultMap<>((n, map) -> new HashSet<>());
        dominates.entrySet().forEach(e -> {
            for (Node<T> dominated : e.getValue()){
                if (e.getValue().stream().filter(d -> d != dominated && d != e.getKey()).allMatch(d -> !(dominates.get(d).contains(dominated)))){
                    dominatesDirectly.get(e.getKey()).add(dominated);
                }
            }
        });

        Map<Node<T>, Integer> loopDepths = new HashMap<>();
        Box<BiConsumer<Node<T>, Integer>> action = new Box<>(null);
        action.val = (node, depth) -> {
            if (loopDepths.containsKey(node)){
                return;
            }
            if (loopHeaders.contains(node)) {
                depth += 1;
            }
            loopDepths.put(node, depth);
            for (Node<T> Node : dominatesDirectly.get(node)) {
                if (node != Node) {
                    action.val.accept(Node, depth);
                }
            }
        };
        action.val.accept(mainNode, 0);
        return loopDepths;
    }

    /**
     * Basic extendable worklist algorithm implementation
     *
     * @param mainNode<T> node to start (only methods that this node transitively calls, are considered)
     * @param action transfer function
     * @param bot start element creator
     * @param next next nodes for current node
     * @param priority priority of each node, usable for an inner loop optimization of the iteration
     *     order
     * @param <T> type of the data calculated for each node
     * @return the calculated values
     */
    public static <T, R> Map<Node<T>, R> worklist(
            Node<T> entryNode,
            BiFunction<Node<T>, Map<Node<T>, R>, R> action,
            Function<Node<T>, R> bot,
            Function<Node<T>, Set<Node<T>>> next,
            Function<Node<T>, Integer> priority,
            Map<Node<T>, R> state) {
        PriorityQueue<Node<T>> queue =
                new PriorityQueue<>(new TreeSet<>(Comparator.comparingInt(priority::apply)));
        queue.addAll(entryNode.transitiveOutHullAndSelfInPostOrder());
        Context.log(() -> String.format("Initial order: %s", queue.toString()));
        queue.forEach(n -> state.put(n, bot.apply(n)));
        while (queue.size() > 0) {
            Node<T> cur = queue.poll();
            R newRes = action.apply(cur, state);
            if (!state.get(cur).equals(newRes)) {
                state.put(cur, newRes);
                queue.addAll(next.apply(cur));
            }
        }
        return state;
    }
}
