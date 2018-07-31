package edu.cuny.hunter.optionalrefactoring.core.utils;

public interface Visitor {
	public void visit(ComputationNode node);

	public void visit(UnionComputationNode node);

	public void visit(ValuedComputationNode node);
}
