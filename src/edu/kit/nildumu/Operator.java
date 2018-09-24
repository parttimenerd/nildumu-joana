package edu.kit.nildumu;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.stream.*;

import edu.kit.joana.ifc.sdg.graph.SDGNode;
import edu.kit.nildumu.util.NildumuError;
import edu.kit.nildumu.util.Pair;

import static edu.kit.nildumu.Context.v;
import static edu.kit.nildumu.Lattices.*;
import static edu.kit.nildumu.Lattices.B.*;
import static edu.kit.nildumu.util.Util.*;

public interface Operator {

    public static class WrongArgumentNumber extends NildumuError {
        WrongArgumentNumber(String op, int actualNumber, int expectedNumber){
            super(String.format("%s, expected %d, but got %d argument(s)", op, expectedNumber, actualNumber));
        }
    }

    public static class LiteralOperator implements Operator {
        private final Value literal;

        public LiteralOperator(Value literal) {
            this.literal = literal;
        }

        @Override
        public Value compute(Context c, List<Value> arguments) {
            return literal;
        }

        @Override
        public String toString(List<Value> arguments) {
            return literal.toString();
        }

        void checkArguments(List<Value> arguments){
            if (arguments.size() != 0){
                throw new WrongArgumentNumber(literal.toString(), arguments.size(), 0);
            }
        }
    }

    public static abstract class UnaryOperator implements Operator {

        public final String symbol;

        public UnaryOperator(String symbol) {
            this.symbol = symbol;
        }

        @Override
        public Value compute(Context c, List<Value> arguments) {
            checkArguments(arguments);
            return compute(c, arguments.get(0));
        }

        abstract Value compute(Context c, Value argument);

        @Override
        public String toString(List<Value> arguments) {
            checkArguments(arguments);
            return String.format("%s%s", symbol, arguments.get(0).toString());
        }

        void checkArguments(List<Value> arguments){
            if (arguments.size() != 1){
                throw new WrongArgumentNumber(symbol, arguments.size(), 1);
            }
        }
    }

    public static abstract class BinaryOperator implements Operator {

        public final String symbol;

        SDGNode currentNode;

        public BinaryOperator(String symbol) {
            this.symbol = symbol;
        }

        @Override
        public Value compute(Context c, SDGNode node, List<Value> arguments) {
            checkArguments(arguments);
            currentNode = node;
            return compute(c, arguments.get(0), arguments.get(1));
        }

        abstract Value compute(Context c, Value first, Value second);

        @Override
        public String toString(List<Value> arguments) {
            checkArguments(arguments);
            return String.format("%s%s%s", arguments.get(0), symbol, arguments.get(1));
        }

        void checkArguments(List<Value> arguments){
            if (arguments.size() != 2){
                throw new WrongArgumentNumber(symbol, arguments.size(), 2);
            }
        }
    }

    public static abstract class BitWiseBinaryOperator extends BinaryOperator {

        public BitWiseBinaryOperator(String symbol) {
            super(symbol);
        }

        @Override
        Value compute(Context c, Value first, Value second) {
            return new Value(first.lattice().mapBits(first, second, (a, b) -> compute(c, a, b)));
        }

        abstract Bit compute(Context c, Bit first, Bit second);
    }

    /**
     * A bit wise operator that uses a preset computation structure. Computation steps:
     *operatorPerNode
     * <ol>
     * <li>computation of the bit value</li>
     * <li>computation of the dependencies → automatic computation of the security level and the control dependencies</li>
     * <li>computation of the bit modifications</li>
     * </ol>
     *
     * <b>Only usable for operators that don't add control dependencies</b>
     */
    public static abstract class BitWiseBinaryOperatorStructured extends BitWiseBinaryOperator {

        public BitWiseBinaryOperatorStructured(String symbol) {
            super(symbol);
        }

        @Override
        Bit compute(Context c, Bit x, Bit y) {
            Lattices.B bitValue = computeBitValue(x, y);
            if (bitValue.isConstant()) {
                return bl.create(bitValue);
            }
            DependencySet dataDeps = computeDataDependencies(x, y, bitValue);
            Bit r = bl.create(bitValue, dataDeps);
            return r;
        }

        abstract B computeBitValue(Bit x, Bit y);

        abstract DependencySet computeDataDependencies(Bit x, Bit y, B computedBitValue);
    }

    public static abstract class BitWiseOperator implements Operator {

        private final String symbol;

        SDGNode currentNode;

        public BitWiseOperator(String symbol) {
            this.symbol = symbol;
        }

        @Override
        public Value compute(Context c, SDGNode node, List<Value> values) {
            currentNode = node;
            int maxWidth = values.stream().mapToInt(Value::size).max().getAsInt();
            return IntStream.range(1, values.size() + 1).mapToObj(i -> computeBit(c, values.stream().map(v -> v.get(i)).collect(Collectors.toList()))).collect(Value.collector());
        }

        abstract Bit computeBit(Context c, List<Bit> bits);

        @Override
        public String toString(List<Value> arguments) {
            return arguments.stream().map(Value::toString).collect(Collectors.joining(symbol));
        }
    }

    /**
     * A bit wise operator that uses a preset computation structure. Computation steps:
     *
     * <ol>
     * <li>computation of the bit value</li>
     * <li>computation of the dependencies → automatic computation of the security level and the control dependencies</li>
     * <li>computation of the bit modifications</li>
     * </ol>
     * <b>Only usable for operators that don't add control dependencies</b>
     */
    public static abstract class BitWiseOperatorStructured extends BitWiseOperator {
        public BitWiseOperatorStructured(String symbol) {
            super(symbol);
        }

        @Override
        Bit computeBit(Context c, List<Bit> bits) {
            Lattices.B bitValue = computeBitValue(bits);
            if (bitValue.isConstant()) {
                return bl.create(bitValue);
            }
            DependencySet dataDeps = computeDataDependencies(bits, bitValue);
            Bit r = bl.create(bitValue, dataDeps);
            return r;
        }

        abstract B computeBitValue(List<Bit> bits);

        abstract DependencySet computeDataDependencies(List<Bit> bits, B computedBitValue);
    }

    public static abstract class BinaryOperatorStructured extends BinaryOperator {

        public BinaryOperatorStructured(String symbol) {
            super(symbol);
        }

        @Override
        public Value compute(Context c, Value x, Value y) {
            List<B> bitValues = computeBitValues(x, y);
            List<DependencySet> dataDeps = computeDataDependencies(x, y, bitValues);
            List<Bit> bits = new ArrayList<>();
            for (int i = 0; i < x.size(); i++){
                if (bitValues.get(i).isConstant()){
                    bits.add(bl.create(bitValues.get(i)));
                } else {
                    Bit r = bl.create(bitValues.get(i), dataDeps.get(i));
                    bits.add(r);
                }
            }
            return new Value(bits);
        }

        public List<B> computeBitValues(Value x, Value y) {
            List<B> bs = new ArrayList<>();
            for (int i = 1; i <= x.size(); i++){
                bs.add(computeBitValue(i, x, y));
            }
            return bs;
        }

        abstract B computeBitValue(int i, Value x, Value y);

        List<DependencySet> computeDataDependencies(Value x, Value y, List<B> computedBitValues) {
            return IntStream.range(1, Math.max(x.size(), y.size()) + 1).mapToObj(i -> computeDataDependencies(i, x, y, computedBitValues)).collect(Collectors.toList());
        }

        abstract DependencySet computeDataDependencies(int i, Value x, Value y, List<B> computedBitValues);
    }

    /**
     * the bitwise or operator (can be used for booleans (ints of which only the first bit matters) too)
     */
    static final BitWiseBinaryOperatorStructured OR = new BitWiseBinaryOperatorStructured("|") {

        @Override
        public B computeBitValue(Bit x, Bit y) {
            if (x.val() == ONE || y.val() == ONE) {
                return ONE;
            }
            if (x.val() == ZERO && y.val() == ZERO) {
                return ZERO;
            }
            return U;
        }

        @Override
        public DependencySet computeDataDependencies(Bit x, Bit y, Lattices.B computedBitValue) {
            return permutatePair(x, y).stream()
                    .filter(p -> p.first.val() == U && p.second.val() != ONE)
                    .flatMap(Pair::firstStream).collect(DependencySet.collector());
        }
    };

    static final BitWiseBinaryOperatorStructured AND = new BitWiseBinaryOperatorStructured("&") {

        @Override
        public B computeBitValue(Bit x, Bit y) {
            if (x.val() == ONE && y.val() == ONE) {
                return ONE;
            }
            if (x.val() == ZERO || y.val() == ZERO) {
                return ZERO;
            }
            return U;
        }

        @Override
        public DependencySet computeDataDependencies(Bit x, Bit y, Lattices.B computedBitValue) {
            return permutatePair(x, y).stream()
                    .filter(p -> p.first.val() == U && p.second.val() != ZERO)
                    .flatMap(Pair::firstStream).collect(DependencySet.collector());
        }
    };

    static final BitWiseBinaryOperator XOR = new BitWiseBinaryOperatorStructured("^") {

        @Override
        public B computeBitValue(Bit x, Bit y) {
            if (x.val() != y.val() && x.isConstant() && y.isConstant()) {
                return ONE;
            }
            if (x.val() == y.val() && y.isConstant()) {
                return ZERO;
            }
            return U;
        }

        @Override
        public DependencySet computeDataDependencies(Bit x, Bit y, Lattices.B computedBitValue) {
            return permutatePair(x, y).stream()
                    .filter(p -> p.first.val() == U)
                    .flatMap(Pair::firstStream).collect(DependencySet.collector());
        }
    };

    static final UnaryOperator NOT = new UnaryOperator("~") {
        @Override
        public Value compute(Context c, Value x) {
            return x.stream().map(b -> {
                B val = v(b).neg();
                DependencySet dataDeps = b.isConstant() ? ds.empty() : ds.create(b);
                Bit r = bl.create(val, dataDeps);
                return r;
            }).collect(Value.collector());
        }
    };

    static final BinaryOperator EQUALS = new BinaryOperatorStructured("==") {
        @Override
        public Lattices.B computeBitValue(int i, Value x, Value y) {
            if (i > 1) {
                return ZERO;
            }
            if (x.lattice().mapBits(x, y, (a, b) -> a.val().equals(b.val()) && a.isConstant()).stream().allMatch(Boolean::booleanValue)) {
                return ONE;
            }
            if (x.lattice().mapBits(x, y, (a, b) -> !a.val().equals(b.val()) && a.isConstant() && b.isConstant()).stream().anyMatch(Boolean::booleanValue)) {
                return ZERO;
            }
            return U;
        }

        @Override
        public DependencySet computeDataDependencies(int i, Value x, Value y, List<Lattices.B> computedBitValues) {
            if (i > 1 || computedBitValues.get(0).isConstant()) {
                return DependencySetLattice.get().empty();
            }
            return Stream.concat(x.stream().filter(b -> b.val() == U),
                    y.stream().filter(b -> b.val() == U)).collect(DependencySet.collector());
        }
    };

    static final BinaryOperator UNEQUALS = new BinaryOperatorStructured("!=") {

        @Override
        public Lattices.B computeBitValue(int i, Value x, Value y) {
            if (i > 1) {
                return ZERO;
            }
            if (x.lattice().mapBits(x, y, (a, b) -> a.val().equals(b.val()) && a.isConstant()).stream().allMatch(Boolean::booleanValue)) {
                return ZERO;
            }
            if (x.lattice().mapBits(x, y, (a, b) -> !a.val().equals(b.val()) && a.isConstant() && b.isConstant()).stream().anyMatch(Boolean::booleanValue)) {
                return ONE;
            }
            return U;
        }

        @Override
        public DependencySet computeDataDependencies(int i, Value x, Value y, List<Lattices.B> computedBitValues) {
            if (i > 1) {
                return ds.bot();
            }
            if (computedBitValues.get(0).isConstant()){
                return ds.empty();
            }
            return Stream.concat(x.stream().filter(b -> b.val() == U),
                    y.stream().filter(b -> b.val() == U)).collect(DependencySet.collector());
        }
    };

    static final BinaryOperatorStructured LESS = new BinaryOperatorStructured("<") {

        Stack<DependencySet> dependentBits = new Stack<>();

        @Override
        public B computeBitValue(int i, Value x, Value y) {
            if (i > 1) {
                return ZERO;
            }
            Lattices.B val = U;
            DependencySet depBits = Stream.concat(x.stream(), y.stream()).filter(Bit::isUnknown).collect(DependencySet.collector());
            Bit a_n = x.signBit();
            Bit b_n = y.signBit();
            B v_x_n = a_n.val();
            B v_y_n = b_n.val();
            Optional<Integer> differingNonConstantIndex = firstNonMatching(x, y, (c, d) -> x.isConstant() && c == d);
            if (v_x_n.isConstant() && v_y_n.isConstant() && v_x_n != v_y_n) {
                depBits = ds.empty();
                if (v_x_n == ONE) { // x is negative
                    val = ONE;
                } else {
                    depBits = ds.empty();
                    val = ZERO;
                }
            } else if (v_x_n == ZERO && v_y_n == ZERO && differingNonConstantIndex.isPresent() &&
                    x.get(differingNonConstantIndex.get()).isConstant() && y.get(differingNonConstantIndex.get()).isConstant()) {
                val = y.get(differingNonConstantIndex.get()).val();
                depBits = ds.empty();
            }
            dependentBits.push(depBits);
            return val;
        }

        Optional<Integer> firstNonMatching(Value x, Value y, BiPredicate<B, B> pred) {
            int j = x.size() - 1;
            while (j >= 1 && pred.test(x.get(j).val(), y.get(j).val())) {
                j--;
            }
            if (j > 1) {
                return Optional.of(j);
            }
            return Optional.empty();
        }

        IntStream indexesWithBitValue(Value value, B b){
            IntStream.Builder ints = IntStream.builder();
            int j = value.size() - 1;
            while (j >= 1 && value.get(j).val() == b) {
                ints.add(j);
                j--;
            }
            return ints.build();
        }

        @Override
        public DependencySet computeDataDependencies(int i, Value x, Value y, List<Lattices.B> computedBitValues) {
            if (i > 1) {
                return ds.bot();
            }
            if (computedBitValues.get(0).isConstant()){
                return ds.empty();
            }
            return dependentBits.pop();
        }
    };

    static final BitWiseBinaryOperatorStructured PHI = new BitWiseBinaryOperatorStructured("phi") {

        @Override
        Bit compute(Context c, Bit x, Bit y) {
        	// TODO implement phi
           /* if (x.val() == X){
                return y;
            } else if (y.val() == X){
                return x;
            } else if (x == y){
                return x;
            }
            Parser.PhiNode phi = (Parser.PhiNode)currentNode;
            if (phi.controlDeps.size() == 1){
                B condVal = c.nodeValue(phi.controlDeps.get(0)).get(1).val();
                switch (condVal){
                    case ONE:
                        return x;
                    case ZERO:
                        return y;
                }
            }
            Lattices.B bitValue = computeBitValue(x, y);
            if (bitValue.isConstant()) {
                return bl.create(bitValue);
            }
            DependencySet dataDeps = computeDataDependencies(x, y, bitValue);
            Bit r = bl.create(bitValue, dataDeps);
            r.addDependencies(computeControlDeps(c, phi, U, null));
            return r;*/
        	return null;
        }

        @Override
        public Lattices.B computeBitValue(Bit x, Bit y) {
            return bs.sup(x.val(), y.val());
        }

        @Override
        public DependencySet computeDataDependencies(Bit x, Bit y, Lattices.B computedBitValue) {
            return Stream.of(x, y).filter(Bit::isUnknown).collect(DependencySet.collector());
        }

        public DependencySet computeControlDeps(Context context, SDGNode node, B computedBitValue, DependencySet computedDataDependencies) {
            // TODO
        	/*if (computedBitValue == B.U) {
                return ((Parser.PhiNode) node).controlDeps.stream().map(n -> context.nodeValue(n).get(1)).collect(DependencySet.collector());
            }*/
            return ds.empty();
        }

        @Override
        void checkArguments(List<Value> arguments) {
        }
    };

    static final BitWiseOperator PHI_GENERIC = new BitWiseOperatorStructured("phi") {

        @Override
        Bit computeBit(Context c, List<Bit> bits) {
            List<Bit> nonBots = bits.stream().filter(b -> b.val() == X).collect(Collectors.toList());
            if (nonBots.size() == 1){
                return nonBots.get(0);
            }
            if (bits.size() > 0 && bits.stream().filter(b -> b != bits.get(0)).count() == 0){
                return bits.get(0);
            }
            Lattices.B bitValue = computeBitValue(bits);
            if (bitValue.isConstant()) {
                return bl.create(bitValue);
            }
            DependencySet dataDeps = computeDataDependencies(bits, bitValue);
            Bit r = bl.create(bitValue, dataDeps);
            r.addDependencies(computeControlDeps(c, currentNode, bitValue, dataDeps));
            return r;
        }

        @Override
        public Lattices.B computeBitValue(List<Bit> bits) {
            return bs.sup(bits.stream().map(b -> b.val()));
        }

        @Override
        public DependencySet computeDataDependencies(List<Bit> bits, Lattices.B computedBitValue) {
            return bits.stream().filter(Bit::isUnknown).collect(DependencySet.collector());
        }

        public DependencySet computeControlDeps(Context context, SDGNode node, B computedBitValue, DependencySet computedDataDependencies) {
            // TODO
        	/*if (computedBitValue == B.U) {
                return ((Parser.PhiNode) node).controlDeps.stream().map(n -> context.nodeValue(n).get(1)).collect(DependencySet.collector());
            }*/
            return ds.empty();
        }
    };

    public static class PlaceBit extends UnaryOperator {

        final int index;

        public PlaceBit(int index) {
            super(String.format("[%d]·", index));
            this.index = index;
        }

        @Override
        Value compute(Context c, Value argument) {
            return IntStream.range(1, index + 2).mapToObj(i -> i == index ? argument.get(1) : bl.create(ZERO)).collect(Value.collector());
        }
    }

    public static class SelectBit extends UnaryOperator {

        final int index;

        public SelectBit(int index) {
            super(String.format("·[%d]", index));
            this.index = index;
        }

        @Override
        Value compute(Context c, Value argument) {
            return new Value(argument.get(index));
        }
    }

    public static class MethodInvocation implements Operator {

        final SDGNode callSite;

        public MethodInvocation(SDGNode callSite) {
            this.callSite = callSite;
        }

        @Override
        public Value compute(Context c, List<Value> arguments) {
            return c.methodInvocationHandler().analyze(c, callSite, arguments);
        }

        @Override
        public String toString(List<Value> arguments) {
            //return String.format("%s(%s)", callSite.getPossibleTargets(), arguments.stream().map(Value::toString).collect(Collectors.joining(",")));
        	return null; // TODO
        }
    }

    static final BinaryOperator ADD = new BinaryOperator("+") {
        @Override
        Value compute(Context c, Value first, Value second) {
            List<Bit> res = new ArrayList<>();
            Box<Bit> carry = new Box<>(bl.create(ZERO));
            return  vl.mapBitsToValue(first, second, (a, b) -> {
                Pair<Bit, Bit> add = fullAdder(c, a, b, carry.val);
                carry.val = add.second;
                return add.first;
            }, vl.bitWidth);
        }

        Pair<Bit, Bit> fullAdder(Context context, Bit a, Bit b, Bit c) {
            Pair<Bit, Bit> pair = halfAdder(context, a, b);
            Pair<Bit, Bit> pair2 = halfAdder(context, pair.first, c);
            Bit carry = OR.compute(context, pair.second, pair2.second);
            return new Pair<>(pair2.first, carry);
        }

        Pair<Bit, Bit> halfAdder(Context context, Bit first, Bit second) {
            return new Pair<>(XOR.compute(context, first, second), AND.compute(context, first, second));
        }
    };

    static final BinaryOperator LEFT_SHIFT = new BinaryOperator("<<") {
        @Override
        Value compute(Context c, Value first, Value second) {
            if (second.isConstant()){
                int shift = second.asInt();
                if (shift >= c.maxBitWidth){
                    return vl.parse(0);
                }
                if (shift < 0){
                    return RIGHT_SHIFT.compute(c, first, vl.parse(-shift));
                }
                return IntStream.range(1, c.maxBitWidth).mapToObj(i -> {
                    if (i - shift < 1){
                        return bl.create(ZERO);
                    }
                    return first.get(i - shift);
                }).collect(Value.collector());
            }
            return createUnknownValue(first, second);
        }
    };

    static final BinaryOperator RIGHT_SHIFT = new BinaryOperator(">>") {


        @Override
        Value compute(Context c, Value first, Value second) {
            if (second.isConstant()){
                int shift = second.asInt();
                if (shift < 0){
                    return LEFT_SHIFT.compute(c, first, vl.parse(-shift));
                }
                return IntStream.range(1, c.maxBitWidth + 1).mapToObj(i -> {
                    if (i + shift > c.maxBitWidth){
                        return bl.create(ZERO);
                    }
                    return first.get(i + shift);
                }).collect(Value.collector());
            }
            return createUnknownValue(first, second);
        }
    };

    static Value setMultSign(Value first, Value second, Value result){
        assert second.isConstant();
        Bit sign = null;
        if (first.signBit().val() == second.signBit().val()){
            sign = bl.create(ZERO);
        } else if (first.signBit().isConstant()){
            sign = bl.create(ONE);
        } else {
            sign = first.signBit();
        }
        Bit _sign = sign;
        return IntStream.range(1, result.size() + 1).mapToObj(i -> i == result.size() ? _sign : result.get(i)).collect(Value.collector());
    }

    static Value createUnknownValue(Value... deps){
        int size = Stream.of(deps).mapToInt(Value::size).max().getAsInt();
        DependencySet depBits = Stream.of(deps).flatMap(Value::stream).filter(Bit::isUnknown).collect(DependencySet.collector());
        return IntStream.range(0, size).mapToObj(i -> bl.create(U, depBits)).collect(Value.collector());
    }

    static final BinaryOperator MULTIPLY = new BinaryOperator("+") {
        @Override
        Value compute(Context c, Value first, Value second) {
            if (second.isPowerOfTwo()){
                return setMultSign(first, second, LEFT_SHIFT.compute(c, first, vl.parse(Math.abs((int) log2(second.asInt())))));
            }
            if (first.isPowerOfTwo()){
                return MULTIPLY.compute(c, second, first);
            }
            if (first.isConstant() && second.isConstant()){
                return vl.parse(first.asInt() * second.asInt());
            }
            return createUnknownValue(first, second);
        }
    };

    static final BinaryOperator DIVIDE = new BinaryOperator("+") {
        @Override
        Value compute(Context c, Value first, Value second) {
            if (second.isPowerOfTwo()){
                return setMultSign(first, second, RIGHT_SHIFT.compute(c, first, vl.parse((int)log2(second.asInt()))));
            }
            if (first.isPowerOfTwo()){
                return DIVIDE.compute(c, second, first);
            }
            if (first.isConstant() && second.isConstant()){
                return vl.parse(first.asInt() * second.asInt());
            }
            return createUnknownValue(first, second);
        }
    };

    static final BinaryOperator MODULO = new BinaryOperator("+") {
        @Override
        Value compute(Context c, Value first, Value second) {
            if (second.isPowerOfTwo() && !second.isNegative()){
                return IntStream.range(1, c.maxBitWidth).mapToObj(i -> {
                    if (i > log2(second.asInt())){
                        return bl.create(ZERO);
                    }
                    return first.get(i);
                }).collect(Value.collector());
            }
            if (first.isConstant() && second.isConstant()){
                return vl.parse(first.asInt() % second.asInt());
            }
            return createUnknownValue(first, second);
        }
    };

    default Value compute(Context c, List<Value> arguments){
        throw new NildumuError("Not implemented");
    }

    default Value compute(Context c, SDGNode node, List<Value> arguments){
        return compute(c, arguments);
    }

    public String toString(List<Value> arguments);
}