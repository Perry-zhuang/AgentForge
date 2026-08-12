package io.agentforge.model;

public enum JavaRole {
    CONTROLLER("controller"), SERVICE("service"), REPOSITORY("repository"), MAPPER("mapper"), COMPONENT("component"), OTHER("other");

    private final String layer;

    JavaRole(String layer) {
        this.layer = layer;
    }

    public String layer() {
        return layer;
    }
}
