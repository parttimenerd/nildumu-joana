package edu.kit.nildumu;

import java.util.*;
import java.util.stream.Collectors;

import edu.kit.nildumu.Lattices.Bit;
import edu.kit.nildumu.Lattices.Sec;
import edu.kit.nildumu.Lattices.Value;
import edu.kit.nildumu.util.DefaultMap;
import edu.kit.nildumu.util.NildumuError;
import edu.kit.nildumu.util.Pair;

import static edu.kit.nildumu.util.DefaultMap.ForbiddenAction.*;
import static edu.kit.nildumu.Lattices.*;

/**
 * Contains the bits that are marked as input or output, that have an unknown value
 */
public class IOValues {

    public static class MultipleLevelsPerValue extends NildumuError {
        MultipleLevelsPerValue(Value value){
            super(String.format("Multiple security levels per value are not supported, attempted it for value %s", value));
        }
    }

    private final Map<Sec<?>, Set<Value>> valuesPerSec;
    private final Map<Value, Sec<?>> secPerValue;
    private final Map<Bit, Sec<?>> secPerBit;
    private final Set<Bit> bits;

    IOValues() {
        this.valuesPerSec = new DefaultMap<>(new LinkedHashMap<>(), new DefaultMap.Extension<Sec<?>, Set<Value>>() {
            @Override
            public Set<Value> defaultValue(Map<Sec<?>, Set<Value>> map, Sec<?> key) {
                return new LinkedHashSet<>();
            }
        }, FORBID_DELETIONS);
        this.secPerValue = new DefaultMap<>(new HashMap<>(), FORBID_VALUE_UPDATES);
        this.secPerBit = new DefaultMap<>(new HashMap<>(), FORBID_DELETIONS);
        this.bits = new LinkedHashSet<>();
    }


    public void add(Sec<?> sec, Value value){
        if (contains(value) && !getSec(value).equals(sec)){
            throw new MultipleLevelsPerValue(value);
        }
        valuesPerSec.get(sec).add(value);
        value.forEach(b -> {
            add(b);
            secPerBit.put(b, sec);
        });
        secPerValue.put(value, sec);
    }

    private void add(Bit bit){
        if (bit.val() == B.U){
            bits.add(bit);
        }
    }

    public List<Pair<Sec, Value>> getValues(){
        return valuesPerSec.entrySet().stream()
                .flatMap(e -> e.getValue().stream()
                        .map(v -> new Pair<>((Sec)e.getKey(), v)))
                .collect(Collectors.toList());
    }

    public boolean contains(Bit bit){
        return bits.contains(bit);
    }

    public boolean contains(Value value){
        return valuesPerSec.values().stream().anyMatch(vs -> vs.contains(value));
    }

    public List<Pair<Sec, Bit>> getBits(){
        return bits.stream().map(b -> new Pair<>((Sec)getSec(b), b)).collect(Collectors.toList());
    }

    public List<Bit> getBits(Sec sec){
        return bits.stream().filter(b -> getSec(b) == sec).collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return valuesPerSec.entrySet().stream().map(e -> String.format(" level %s: %s",e.getKey(), e.getValue().stream().map(Value::toString).collect(Collectors.joining(", ")))).collect(Collectors.joining("\n"));
    }

    public Sec<?> getSec(Value value){
        return secPerValue.get(value);
    }

    public Sec<?> getSec(Bit bit){
        return secPerBit.get(bit);
    }

    public boolean hasBitWithoutValue(){
        return bits.stream().anyMatch(b -> b.value() == null);
    }
    
    public boolean hasValuesForSec(Sec<?> sec) {
    	return valuesPerSec.get(sec).size() > 0;
    }
}
