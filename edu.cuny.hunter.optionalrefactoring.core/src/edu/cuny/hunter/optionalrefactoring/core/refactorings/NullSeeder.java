package edu.cuny.hunter.optionalrefactoring.core.refactorings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.internal.ui.text.correction.ASTResolving;

import com.google.common.collect.Sets;

import edu.cuny.hunter.optionalrefactoring.core.exceptions.HarvesterJavaModelPreconditionException;
import edu.cuny.hunter.optionalrefactoring.core.exceptions.HarvesterASTException;
import edu.cuny.hunter.optionalrefactoring.core.exceptions.HarvesterASTPreconditionException;
import edu.cuny.hunter.optionalrefactoring.core.utils.Util;

/**
 * @author <a href="mailto:ofriedman@acm.org">Oren Friedman</a>
 *
 */
class NullSeeder {

	private final SearchEngine searchEngine = new SearchEngine();
	private final ASTNode node;
	private final Map<IJavaElement, Boolean> candidates = new LinkedHashMap<>();
	private final Set<IJavaElement> notRefactorable = new LinkedHashSet<>(); 

	public NullSeeder(ASTNode node) {
		this.node = node;
	}
	
	/**
	 * @return Map of IJavaElement to whether it is implicitly null field
	 */
	public Map<IJavaElement, Boolean> seedNulls() {
		ASTVisitor visitor = new ASTVisitor() {
			@Override
			public boolean visit(NullLiteral nl) {
				NullSeeder.this.process(nl.getParent());
				return super.visit(nl);
			}
			@Override
			public boolean visit(VariableDeclarationFragment vdf) {
				IVariableBinding b = vdf.resolveBinding();
				if (b != null) {
					if (b.getJavaElement() instanceof IField)
						if (vdf.getInitializer() == null)
							/*this element gets added to the Map candidates with 
							 * boolean true indicating an implict null*/
							candidates.put(b.getJavaElement(),Boolean.TRUE);
				} else throw new HarvesterASTException(
						"While trying to process an uninitialized VariableDeclarationFragment: ", 
						vdf);
				return super.visit(vdf);
			}
		};
		node.accept(visitor);
		return candidates;
	}

	private <T extends ASTNode> ASTNode getContaining(Class<T> type, ASTNode node) {
		ASTNode curr = node;
		while (curr != null && (curr.getClass() != type)) {
			curr = curr.getParent();
		}
		if (curr != null) return curr;
		throw new HarvesterASTException("While finding the declaring block for: ", node);
	}

	/**
	 * @param node Any of the possible AST nodes where a null literal could appear as an immediate child
	 */
	private void process(ASTNode node) {
		try {
			switch (node.getNodeType()) {
			case ASTNode.ASSIGNMENT : this.process(((Assignment)node).getLeftHandSide());
			break;
			case ASTNode.RETURN_STATEMENT : this.process((ReturnStatement)node);
			break;
			case ASTNode.METHOD_INVOCATION : this.process((MethodInvocation)node);
			break;
			case ASTNode.SUPER_METHOD_INVOCATION : this.process((SuperMethodInvocation)node);
			break;
			case ASTNode.CONSTRUCTOR_INVOCATION : this.process((ConstructorInvocation)node);
			break;
			case ASTNode.SUPER_CONSTRUCTOR_INVOCATION : this.process((SuperConstructorInvocation)node);
			break;
			case ASTNode.CLASS_INSTANCE_CREATION : this.process((ClassInstanceCreation)node);
			break;
			case ASTNode.VARIABLE_DECLARATION_FRAGMENT : this.process((VariableDeclarationFragment)node);
			break;
			case ASTNode.ARRAY_INITIALIZER : this.process((ArrayInitializer)node);
			break;
			case ASTNode.PARENTHESIZED_EXPRESSION : this.process((ParenthesizedExpression)node);
			break;
			case ASTNode.CONDITIONAL_EXPRESSION : this.process((ConditionalExpression)node);
			break;
			case ASTNode.SINGLE_VARIABLE_DECLARATION : this.process((SingleVariableDeclaration)node);
			break;
			case ASTNode.CAST_EXPRESSION : this.process((CastExpression)node);
			break;
			case ASTNode.INFIX_EXPRESSION : /*This may appear in some edge cases, we do nothing*/
			break;
			default : throw new HarvesterASTException("While trying to process the parent of an encountered NullLiteral: ", node);
			}
		} catch (HarvesterJavaModelPreconditionException e) {
			Logger.getAnonymousLogger().warning("Unable to process an ASTNode in binary code: "+e+".");
		} catch (HarvesterASTPreconditionException e) {
			Logger.getAnonymousLogger().warning("Entity cannot be refactored: ");
			IJavaElement failing = Util.getEnclosingTypeDependentExpression(e.getNode());
			this.notRefactorable.add(failing);
		} catch (HarvesterASTException e) {
			Logger.getAnonymousLogger().warning("Problem with traversing the AST: "+e+".");
		}
	}

	private void process(Expression node) throws HarvesterASTException {
		switch (node.getNodeType()) {
		case ASTNode.QUALIFIED_NAME : process((Name)node);
		break;
		case ASTNode.SIMPLE_NAME : process((Name)node);
		break;
		case ASTNode.ARRAY_ACCESS : process((ArrayAccess)node);
		break;
		case ASTNode.FIELD_ACCESS : process((FieldAccess)node);
		break;
		case ASTNode.SUPER_FIELD_ACCESS : process((SuperFieldAccess)node);
		break;
		default : throw new HarvesterASTException("While trying to process left side of assignment: ", node);
		}
	}
	
	private void process(CastExpression node) {
		// Cast expressions cannot be refactored as Optionals
		throw new HarvesterASTPreconditionException("Null-dependent CastExpression node encountered: ", node);
	}
	
	private void process(ConditionalExpression node2) {
		ASTNode parent = node2.getParent();
		if (parent != null) process(parent);
		else throw new HarvesterASTException("While trying to process a Conditional Expression node: ", node2);
	}

	private void process(ParenthesizedExpression node2) {
		ASTNode parent = node2.getParent();
		if (parent != null) process(parent);
		else throw new HarvesterASTException("While trying to process a Parenthesized Expression node: ", node2);
	}

	private void process(ReturnStatement node) throws HarvesterASTException {
		ASTNode methodDecl = getContaining(MethodDeclaration.class, node); 
		if (methodDecl instanceof MethodDeclaration){
			IMethodBinding imb = ((MethodDeclaration)methodDecl).resolveBinding();
			if (imb != null) {
				IJavaElement im = imb.getJavaElement();
				if (im != null) {
					this.candidates.put(im,Boolean.FALSE);
					return;
				}
			}
		}
		throw new HarvesterASTException("While trying to process a null return statement in a Method Declaration: ", node);
	}

	private void process(Name node) throws HarvesterASTException {
		IBinding b = node.resolveBinding();
		if (b != null) {
			IJavaElement element = b.getJavaElement();
			if (element != null) {
				this.candidates.put(element,Boolean.FALSE);
				return;
			}
		}
		throw new HarvesterASTException("While trying to process a Name node: ", node);
	}

	private void process(SuperFieldAccess node) throws HarvesterASTException {
		switch (node.getNodeType()) {
		case ASTNode.SUPER_FIELD_ACCESS : {
			IBinding ib = ((SuperFieldAccess)node).resolveFieldBinding();
			if (ib != null) {
				IJavaElement element = ib.getJavaElement();
				if (element != null) {
					this.candidates.put(element,Boolean.FALSE);
					return;
				}
			}
		}
		default : throw new HarvesterASTException("While trying to process a Super-field Access Node: ", node);
		}
	}

	private void process(FieldAccess node) throws HarvesterASTException {
		switch (node.getNodeType()) {
		case ASTNode.FIELD_ACCESS : {
			IBinding ib = ((FieldAccess)node).resolveFieldBinding();
			if (ib != null) {
				IJavaElement element = ib.getJavaElement();
				if (element != null) {
					this.candidates.put(element,Boolean.FALSE);
					return;
				}
			}
		}
		default : throw new HarvesterASTException("While trying to process a Field Access node: ", node);
		}
	}

	private void process(ArrayInitializer node) {
		ASTNode arrayCreationOrVariableDeclarationFragment = node.getParent();
		switch (arrayCreationOrVariableDeclarationFragment.getNodeType()) {
		case ASTNode.ARRAY_CREATION : {
			ASTNode target = arrayCreationOrVariableDeclarationFragment.getParent();
			if (target != null) {
				process(target);
				break;
			}
		}
		case ASTNode.VARIABLE_DECLARATION_FRAGMENT : process(arrayCreationOrVariableDeclarationFragment);
		break;
		default : throw new HarvesterASTException("While trying to process an Array Initializer node: ", node);
		}
	}

	private void process(ArrayAccess node) throws HarvesterASTException {
		switch (node.getNodeType()) {
		case ASTNode.ARRAY_ACCESS : {
			Expression e = ((ArrayAccess)node).getArray();
			process(e);
		} break;
		default : throw new HarvesterASTException("While trying to process an Array Access node: ", node);
		}
	}

	private void process(ClassInstanceCreation cic) throws HarvesterASTException {
		List<Integer> argPositions = getParamPositions(cic);
		IMethodBinding binding = cic.resolveConstructorBinding();
		if (binding != null) {
			IMethod method = (IMethod)binding.getJavaElement();
			if (method != null) 
				process(argPositions, method);
		} else throw new HarvesterASTException("While trying to process a Class Instance Creation node: ", cic);
	}
	
	private void process(MethodInvocation mi) throws HarvesterASTException {
		List<Integer> argPositions = getParamPositions(mi);
		IMethodBinding binding = mi.resolveMethodBinding();
		if (binding != null) {
			IMethod method = (IMethod)binding.getJavaElement();
			if (method != null)
				process(argPositions, method);
		} else throw new HarvesterASTException("While trying to process a Method Invocation node: ", mi);
	}
	
	private void process(SuperMethodInvocation smi) throws HarvesterASTException {
		List<Integer> argPositions = getParamPositions(smi);
		IMethodBinding binding = smi.resolveMethodBinding();
		if (binding != null) {
			IMethod method = (IMethod)binding.getJavaElement();
			if (method != null)
				process(argPositions, method);
		} else throw new HarvesterASTException("While trying to process a Super Method Invocation node: ", smi);
	}
	
	private void process(ConstructorInvocation ci) throws HarvesterASTException {
		List<Integer> argPositions = getParamPositions(ci);
		IMethodBinding binding = ci.resolveConstructorBinding();
		if (binding != null) {
			IMethod method = (IMethod)binding.getJavaElement();
			if (method != null)
				process(argPositions, method);
		} else throw new HarvesterASTException("While trying to process a Constructor Invocation node: ", ci);
	}
	
	private void process(SuperConstructorInvocation sci) throws HarvesterASTException {
		List<Integer> argPositions = getParamPositions(sci);
		IMethodBinding binding = sci.resolveConstructorBinding();
		if (binding != null) {
			IMethod method = (IMethod)binding.getJavaElement();
			if (method != null)
				process(argPositions, method);
		} else throw new HarvesterASTException("While trying to process a Super Constructor Invocation node: ", sci);
	}

	private void process(List<Integer> argPositions, IMethod invocation) {
		
		Set<SingleVariableDeclaration> svd = new LinkedHashSet<>();
			SearchRequestor requestor = new SearchRequestor() {
				@SuppressWarnings("unchecked")
				@Override
				public void acceptSearchMatch(SearchMatch match) throws CoreException {
					
					IMethod element = (IMethod) match.getElement();
					if (element.isReadOnly()) {
						throw new HarvesterJavaModelPreconditionException("Match found a dependency in a non-writable location.", element);
					}
					
					if (element.getResource().isDerived()) {
						throw new HarvesterJavaModelPreconditionException("Match found a dependency in generated code.", element);
					}
					
					MethodDeclaration methodDecl = Util.getMethodDeclaration(Util.getExactASTNode(match, new NullProgressMonitor()));
					List<SingleVariableDeclaration> params = methodDecl.parameters();					
					for (Integer i : argPositions) {
						svd.add(params.get(i));
					}
				}
			};

			try {
				this.searchEngine.search(
						SearchPattern.createPattern(invocation, IJavaSearchConstants.DECLARATIONS),
						new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() }, 
						SearchEngine.createWorkspaceScope(),
						requestor, new NullProgressMonitor());
			} catch (CoreException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			for (SingleVariableDeclaration node : svd) {
				IVariableBinding b = node.resolveBinding();
				if (b != null) {
					ILocalVariable e = (ILocalVariable) b.getJavaElement();
					if (e.exists()) {
						this.candidates.put(e,Boolean.FALSE);
					}
				}
			}
	}

	@SuppressWarnings("unchecked")
	private <T extends ASTNode> List<Integer> getParamPositions(T invocation) {
		List<Expression> args;
		switch (invocation.getNodeType()) {
		case ASTNode.METHOD_INVOCATION : args = ((MethodInvocation)invocation).arguments();
		break;
		case ASTNode.CONSTRUCTOR_INVOCATION : args = ((ConstructorInvocation)invocation).arguments();
		break;
		case ASTNode.SUPER_CONSTRUCTOR_INVOCATION : args = ((SuperConstructorInvocation)invocation).arguments();
		break;
		case ASTNode.CLASS_INSTANCE_CREATION : args = ((ClassInstanceCreation)invocation).arguments();
		break;
		default : throw new HarvesterASTException("Tried processing parameters for something other than an invocation.", invocation);
		}
		
		List<Integer> argPositions = new ArrayList<>();
		Integer pos = -1;
		for (Expression arg : args) {
			pos += 1;
			if (arg instanceof NullLiteral) argPositions.add(new Integer(pos));
		}
		return argPositions;
	}

	@SuppressWarnings("unchecked")
	private void process(VariableDeclarationFragment vdf) throws HarvesterASTException {
		ASTNode node = vdf.getParent();
		List<VariableDeclarationFragment> fragments;
		switch (node.getNodeType()) {
		case ASTNode.FIELD_DECLARATION : fragments = ((FieldDeclaration)node).fragments();
		break;
		case ASTNode.VARIABLE_DECLARATION_EXPRESSION :	fragments = ((VariableDeclarationExpression)node).fragments();
		break;
		case ASTNode.VARIABLE_DECLARATION_STATEMENT : fragments = ((VariableDeclarationStatement)node).fragments();
		break;
		default : throw new HarvesterASTException("While trying to process the parent of a Variable Declaration Fragment: ", node);
		}
		Map<IJavaElement,Boolean> elements = new LinkedHashMap<>();
		for (Object o : fragments) {
			IBinding ib = ((VariableDeclarationFragment)o).resolveBinding();
			if (ib != null) {
				IJavaElement element = ib.getJavaElement();
				if (element != null) elements.put(element,Boolean.FALSE);
			}
			else throw new HarvesterASTException("While trying to process the fragments in a Variable Declaration Expression: ", vdf);
		}
		this.candidates.putAll(elements);
	}

	private void process(SingleVariableDeclaration node) throws HarvesterASTException {
		// Single variable declaration nodes are used in a limited number of places, including formal parameter lists and catch clauses. They are not used for field declarations and regular variable declaration statements. 
		IBinding b = node.resolveBinding();
		if (b != null) {
			IJavaElement element = b.getJavaElement();
			if (element != null) {
				this.candidates.put(element,Boolean.FALSE);
				return;
			}
		}
		throw new HarvesterASTException("While trying to process a Single Variable Declaration: ", node);
	}
}
