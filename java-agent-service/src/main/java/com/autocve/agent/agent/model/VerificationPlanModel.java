package com.autocve.agent.agent.model;

import tools.jackson.databind.JsonNode;

public interface VerificationPlanModel {
    VerificationPlan plan(JsonNode finding);

    String runtimeName();
}
