package edu.cuny.hunter.optionalrefactoring.core.refactorings;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IInitializer;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

import edu.cuny.hunter.optionalrefactoring.core.analysis.Entities;
import edu.cuny.hunter.optionalrefactoring.core.analysis.Entities.Instance;
import edu.cuny.hunter.optionalrefactoring.core.analysis.PreconditionFailure;
import edu.cuny.hunter.optionalrefactoring.core.analysis.RefactoringSettings;
import edu.cuny.hunter.optionalrefactoring.core.exceptions.HarvesterException;
import edu.cuny.hunter.optionalrefactoring.core.messages.Messages;
import edu.cuny.hunter.optionalrefactoring.core.utils.Util;

/**
 *
 * @author <a href="mailto:ofriedman@acm.org">Oren Friedman</a>
 *
 *         This class controls the parsing and accumulation of NullLiteral
 *         dependent program elements from the AST. It uses static factory
 *         methods for each type of IJavaElement from the model which the plugin
 *         can be run on.
 *
 *         It's main driver method harvestRefactorableContexts() produces a
 *         Set<Entities> which is passed to the caller. It also retains a
 *         TypeDependentElementSet for all of the program elements which are
 *         null dependent but that do not meet the criteria for refactoring, for
 *         example, due to being dependent on generated code or code in read
 *         only resources.
 *
 */
public class RefactorableHarvester {

	public static RefactorableHarvester of(final ICompilationUnit i, final CompilationUnit c,
			final IJavaSearchScope scope, final RefactoringSettings settings, final IProgressMonitor monitor) {
		return new RefactorableHarvester(c, scope, settings, monitor);
	}

	public static RefactorableHarvester of(final IField f, final CompilationUnit c, final IJavaSearchScope scope,
			final RefactoringSettings settings, final IProgressMonitor monitor) throws JavaModelException {
		final FieldDeclaration fieldDecl = Util.findASTNode(f, c);
		return new RefactorableHarvester(fieldDecl, scope, settings, monitor);
	}

	public static RefactorableHarvester of(final IInitializer i, final CompilationUnit c, final IJavaSearchScope scope,
			final RefactoringSettings settings, final IProgressMonitor monitor) throws JavaModelException {
		final Initializer initializer = Util.findASTNode(i, c);
		return new RefactorableHarvester(initializer, scope, settings, monitor);
	}

	public static RefactorableHarvester of(final IMethod m, final CompilationUnit c, final IJavaSearchScope scope,
			final RefactoringSettings settings, final IProgressMonitor monitor) throws JavaModelException {
		final MethodDeclaration methodDecl = Util.findASTNode(m, c);
		return new RefactorableHarvester(methodDecl, scope, settings, monitor);
	}

	public static RefactorableHarvester of(final IType t, final CompilationUnit c, final IJavaSearchScope scope,
			final RefactoringSettings settings, final IProgressMonitor monitor) throws JavaModelException {
		final TypeDeclaration typeDecl = Util.findASTNode(t, c);
		return new RefactorableHarvester(typeDecl, scope, settings, monitor);
	}

	private final ASTNode refactoringRootNode;
	private final IJavaSearchScope scopeRoot;
	private final RefactoringSettings settings;
	private final IProgressMonitor monitor;
	private final SearchEngine searchEngine = new SearchEngine();
	private final WorkList workList = new WorkList();
	private final Set<IJavaElement> notRefactorable = new LinkedHashSet<>();
	private final Set<Instance> instances = new LinkedHashSet<>();
	private final Set<Entities> entities = new LinkedHashSet<>();

	private RefactorableHarvester(final ASTNode rootNode, final IJavaSearchScope scope,
			final RefactoringSettings settings, final IProgressMonitor m) {
		this.refactoringRootNode = rootNode;
		this.monitor = m;
		this.scopeRoot = scope;
		this.settings = settings;
	}

	public Set<Entities> getEntities() {
		return this.entities;
	}

	public RefactoringStatus harvestRefactorableContexts() throws CoreException {

		this.reset();
		// this worklist starts with the immediate type-dependent entities on
		// null
		// expressions.
		final NullSeeder seeder = new NullSeeder(this.refactoringRootNode, this.settings, this.monitor, this.scopeRoot);
		// if no nulls pass the preconditions, return an Error status
		if (!seeder.process())
			return RefactoringStatus.createFatalErrorStatus(Messages.NoNullsHavePassedThePreconditions);
		// otherwise get the passing null type dependent entities
		this.instances.addAll(seeder.getInstances());
		// and put just the IJavaElements into the workList
		this.workList.addAll(seeder.getCandidates());

		// while there's more work to do.
		while (this.workList.hasNext()) {
			// grab the next element.
			final IJavaElement searchElement = this.workList.next();

			// build a search pattern to find all occurrences of the
			// searchElement.
			final SearchPattern pattern = SearchPattern.createPattern(searchElement,
					IJavaSearchConstants.ALL_OCCURRENCES, SearchPattern.R_EXACT_MATCH);

			final SearchRequestor requestor = new SearchRequestor() {
				@Override
				public void acceptSearchMatch(final SearchMatch match) throws CoreException {
					if (match.getAccuracy() == SearchMatch.A_ACCURATE && !match.isInsideDocComment()
					// We are finding import declarations for some reason, they
					// should be ignored
							&& ((IJavaElement) match.getElement())
									.getElementType() != IJavaElement.IMPORT_DECLARATION) {
						// here, we have search match.
						// convert the matchingElement to an ASTNode.
						final ASTNode node = Util.getExactASTNode(match, RefactorableHarvester.this.monitor);

						// now we have the ASTNode corresponding to the match.
						// process the matching ASTNode.
						final NullPropagator processor = new NullPropagator(node, (IJavaElement) match.getElement(),
								RefactorableHarvester.this.scopeRoot, RefactorableHarvester.this.settings,
								RefactorableHarvester.this.monitor);

						processor.process();

						// add to the workList all of the type-dependent stuff
						// we found.
						RefactorableHarvester.this.workList.addAll(processor.getCandidates());
						// add to the set of Instances all of the instances of the entities we found
						RefactorableHarvester.this.instances.addAll(processor.getInstances());
					}
				}
			};

			// here, we're actually doing the search.
			try {
				this.searchEngine.search(pattern,
						new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() }, this.scopeRoot,
						requestor, this.monitor);

			} catch (final HarvesterException e) {
				if (e.getFailure().contains(PreconditionFailure.JAVA_MODEL_ERROR)
						|| e.getFailure().contains(PreconditionFailure.MISSING_BINDING))
					throw e;
				/*
				 * we move the current WorkList entities to the set of entities with appropriate
				 * RefactoringStatus
				 */
				this.entities.addAll(Util
						.getElementForest(this.trimForest(this.workList.getComputationForest(), this.notRefactorable))
						.stream().map(set -> Entities.create(set, this.instances, this.settings))
						.collect(Collectors.toSet()));
				this.notRefactorable.addAll(this.workList.getCurrentComputationTreeElements());
				this.workList.removeAll(this.notRefactorable);
				this.instances.removeIf(instance -> this.notRefactorable.contains(instance.element));
				continue;
			}
		}

		final Set<ComputationNode> computationForest = this.trimForest(this.workList.getComputationForest(),
				this.notRefactorable);

		final Set<Set<IJavaElement>> candidateSets = Util.getElementForest(computationForest);

		// Convert the set of passing type dependent sets into sets of Entities
		/*
		 * It is a set of sets of type-dependent entities. You start with the seeds, you
		 * grow the seeds into these sets.
		 */
		this.entities.addAll(candidateSets.stream().map(set -> Entities.create(set, this.instances, this.settings))
				.collect(Collectors.toSet()));

		return this.entities.stream().map(Entities::status).collect(RefactoringStatus::new, RefactoringStatus::merge,
				RefactoringStatus::merge);

	}

	private void reset() {
		this.workList.clear();
		this.notRefactorable.clear();
		this.instances.clear();
	}

	private Set<ComputationNode> trimForest(final Set<ComputationNode> computationForest,
			final Set<IJavaElement> nonEnumerizableList) {
		final Set<ComputationNode> ret = new LinkedHashSet<>(computationForest);
		final TreeTrimingVisitor visitor = new TreeTrimingVisitor(ret, nonEnumerizableList);
		// for each root in the computation forest
		for (final ComputationNode root : computationForest)
			root.accept(visitor);
		return ret;
	}

}
