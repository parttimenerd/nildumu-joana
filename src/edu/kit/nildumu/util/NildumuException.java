package edu.kit.nildumu.util;

/**
 * Base class for all exceptions in this project.
 * 
 * Why extend the RuntimeException?
 * 
 * The main point is that the streaming API doesn't allow
 * throwing checked exceptions from lambdas used with it.
 * Therefore only unchecked exceptions can be thrown.
 * NildumuExceptions are also only thrown on fatal errors 
 * that the analysis cannot recover from.
 * Catching these exceptions is prohibited.
 */
public class NildumuException extends RuntimeException {

    public NildumuException(String message) {
        super(message);
    }
}
