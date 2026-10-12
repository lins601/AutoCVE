package com.autocve.agent.agent.model;

public interface InvestigatorDecisionModel {
    InvestigatorDecision decide(InvestigatorModelRequest request);

    String runtimeName();
}
