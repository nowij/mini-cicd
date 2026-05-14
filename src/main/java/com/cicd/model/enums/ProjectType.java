package com.cicd.model.enums;

public enum ProjectType {
    MAVEN("Maven (Spring Boot)"),
    GRADLE("Gradle (Spring Boot)"),
    REACT("React / Node.js"),
    CUSTOM("Custom Command");

    private final String displayName;

    ProjectType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
