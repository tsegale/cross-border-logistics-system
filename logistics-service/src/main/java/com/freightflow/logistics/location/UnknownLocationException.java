package com.freightflow.logistics.location;

public class UnknownLocationException extends RuntimeException {

    private final String field;
    private final String unlocode;

    public UnknownLocationException(String field, String unlocode) {
        super("Unknown UN/LOCODE '" + unlocode + "' for " + field);
        this.field = field;
        this.unlocode = unlocode;
    }

    public String getField() {
        return field;
    }

    public String getUnlocode() {
        return unlocode;
    }
}
