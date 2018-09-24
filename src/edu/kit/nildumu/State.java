package edu.kit.nildumu;

import java.util.*;
import java.util.stream.Collectors;

import edu.kit.nildumu.util.DefaultMap;

import static edu.kit.nildumu.Lattices.*;

/**
 * State of the variables
 */
class State {

    private Value returnValue = vl.bot();

    private final DefaultMap<String, Value> map = new DefaultMap<>(new HashMap<>(), new DefaultMap.Extension<String, Value>() {
        @Override
        public Value defaultValue(Map<String, Value> map, String key) {
            return vl.bot();
        }
    });

    public Value get(String variable){
        return map.get(variable);
    }

    public void set(String variable, Value value){
        this.map.put(variable, value);
    }

    @Override
    public String toString() {
        return map.entrySet().stream().map(e -> String.format("%s => %s",e.getKey(), e.getValue().repr())).collect(Collectors.joining("\n"));
    }

    public Set<String> variableNames(){
        return map.keySet();
    }

    public Value getReturnValue(){
        return returnValue;
    }

    public void setReturnValue(Value value){
        this.returnValue = value;
    }
}