package com.freightflow.logistics.quote;

public class InvalidRouteException extends RuntimeException {

    private final String slug;
    private final String title;
    private final String origin;
    private final String destination;
    private final String mode;

    public InvalidRouteException(String slug, String title, String origin, String destination, String mode, String message) {
        super(message);
        this.slug = slug;
        this.title = title;
        this.origin = origin;
        this.destination = destination;
        this.mode = mode;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public String getOrigin() {
        return origin;
    }

    public String getDestination() {
        return destination;
    }

    public String getMode() {
        return mode;
    }
}
