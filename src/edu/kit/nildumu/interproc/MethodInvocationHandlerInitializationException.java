package edu.kit.nildumu.interproc;

import edu.kit.nildumu.util.NildumuException;

public class MethodInvocationHandlerInitializationException extends NildumuException {

    public MethodInvocationHandlerInitializationException(String message) {
        super("Error initializing the method invocation handler: " + message);
    }
}