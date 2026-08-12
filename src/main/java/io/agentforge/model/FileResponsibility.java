package io.agentforge.model;

public enum FileResponsibility {
    CONTROLLER,
    SERVICE,
    BUSINESS,
    COMPONENT,
    MODEL,
    DTO,
    REQUEST,
    RESPONSE,
    ENUM,
    CONFIG,
    UTIL,
    OTHER;

    public boolean passive() {
        return switch (this) {
            case MODEL, DTO, REQUEST, RESPONSE, ENUM, CONFIG -> true;
            default -> false;
        };
    }

    public boolean taskData() {
        return switch (this) {
            case MODEL, DTO, REQUEST, RESPONSE -> true;
            default -> false;
        };
    }
}
