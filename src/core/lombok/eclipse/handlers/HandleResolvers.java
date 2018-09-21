/*
 * Copyright (C) 2009-2017 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.eclipse.handlers;

import static lombok.core.handlers.HandlerUtil.*;
import static lombok.eclipse.Eclipse.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.toAllSetterNames;
import static lombok.eclipse.handlers.EclipseHandlerUtil.toSetterName;
import static lombok.eclipse.handlers.EclipseHandlerUtil.MemberExistsResult.*;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import lombok.AccessLevel;
import lombok.ConfigurationKeys;
import lombok.Resolvers;
import lombok.Setter;
import lombok.core.AST.Kind;
import lombok.core.handlers.HandlerUtil;
import lombok.core.AST;
import lombok.core.AnnotationValues;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;
import lombok.experimental.Accessors;

import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.internal.codeassist.complete.CompletionParser;
import org.eclipse.jdt.internal.codeassist.impl.AssistParser;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.NameReference;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedSingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.TrueLiteral;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;
import org.mangosdk.spi.ProviderFor;

/**
 * Handles the {@code lombok.Setter} annotation for eclipse.
 */
@ProviderFor(EclipseAnnotationHandler.class)
public class HandleResolvers extends EclipseAnnotationHandler<Resolvers> {
	public boolean generateResolversForType(EclipseNode typeNode, EclipseNode pos, AccessLevel level, boolean checkForTypeLevelResolvers, List<Annotation> onMethod, List<Annotation> onParam) {
		if (checkForTypeLevelResolvers) {
			if (hasAnnotation(Resolvers.class, typeNode)) {
				//The annotation will make it happen, so we can skip it.
				return true;
			}
		}
		
		TypeDeclaration typeDecl = null;
		if (typeNode.get() instanceof TypeDeclaration) typeDecl = (TypeDeclaration) typeNode.get();
		int modifiers = typeDecl == null ? 0 : typeDecl.modifiers;
		boolean notAClass = (modifiers &
				(ClassFileConstants.AccInterface | ClassFileConstants.AccAnnotation | ClassFileConstants.AccEnum)) != 0;
		
		if (typeDecl == null || notAClass) {
			pos.addError("@Resolvers is only supported on a class or a field.");
			return false;
		}
		
		for (EclipseNode field : typeNode.down()) {
			if (field.getKind() != Kind.FIELD) continue;
			FieldDeclaration fieldDecl = (FieldDeclaration) field.get();
			if (!filterField(fieldDecl)) continue;
			
			//Skip final fields.
			if ((fieldDecl.modifiers & ClassFileConstants.AccFinal) != 0) continue;
			
			generateResolversForField(field, pos, level, onMethod, onParam);
		}
		return true;
	}
	
	/**
	 * Generates a Resolvers on the stated field.
	 * 
	 * Used by {@link HandleData}.
	 * 
	 * The difference between this call and the handle method is as follows:
	 * 
	 * If there is a {@code lombok.Resolvers} annotation on the field, it is used and the
	 * same rules apply (e.g. warning if the method already exists, stated access level applies).
	 * If not, the Resolvers is still generated if it isn't already there, though there will not
	 * be a warning if its already there. The default access level is used.
	 */
	public void generateResolversForField(EclipseNode fieldNode, EclipseNode sourceNode, AccessLevel level, List<Annotation> onMethod, List<Annotation> onParam) {
		if (hasAnnotation(Resolvers.class, fieldNode)) {
			//The annotation will make it happen, so we can skip it.
			return;
		}
		createResolversForField(level, fieldNode, sourceNode, false, onMethod, onParam);
	}
	
	@Override public void handle(AnnotationValues<Resolvers> annotation, Annotation ast, EclipseNode annotationNode) {
		EclipseNode node = annotationNode.up();
		AccessLevel level = annotation.getInstance().level();
		if (level == AccessLevel.NONE || node == null) return;
		
		String resolverClassName = annotation.getRawExpression("value");
		
		List<Annotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@Resolvers(onMethod", annotationNode);
		List<Annotation> onParam = unboxAndRemoveAnnotationParameter(ast, "onParam", "@Resolvers(onParam", annotationNode);
		
		switch (node.getKind()) {
		case FIELD:
			createResolversForFields(level, annotationNode.upFromAnnotationToFields(), annotationNode, true, onMethod, onParam);
			break;
		case TYPE:
			generateResolversForType(node, annotationNode, level, false, onMethod, onParam);
			break;
		}
	}
	
	public void createResolversForFields(AccessLevel level, Collection<EclipseNode> fieldNodes, EclipseNode sourceNode, boolean whineIfExists, List<Annotation> onMethod, List<Annotation> onParam) {
		for (EclipseNode fieldNode : fieldNodes) {
			createResolversForField(level, fieldNode, sourceNode, whineIfExists, onMethod, onParam);
		}
	}
	
	public void createResolversForField(
			AccessLevel level, EclipseNode fieldNode, EclipseNode sourceNode,
			boolean whineIfExists, List<Annotation> onMethod,
			List<Annotation> onParam) {
		
		ASTNode source = sourceNode.get();
		if (fieldNode.getKind() != Kind.FIELD) {
			sourceNode.addError("@Resolvers is only supported on a class or a field.");
			return;
		}
		
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		String resolverName = toResolverName(fieldNode);
		boolean shouldReturnThis = shouldReturnThis(fieldNode);
		
		if (resolverName == null) {
			fieldNode.addWarning("Not generating resolver for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		
		int modifier = toEclipseModifier(level) | (field.modifiers & ClassFileConstants.AccStatic);
		
		MemberExistsResult existsReturn = methodExists(resolverName, fieldNode, false, 1);
		if (existsReturn == EXISTS_BY_LOMBOK) {
			return;
		}
		if (existsReturn == EXISTS_BY_USER) {
			if (whineIfExists) {
				fieldNode.addWarning(String.format("Not generating %s(): A method with that name already exists", resolverName));
			}
			return;
		}
		
		MethodDeclaration method = createResolver((TypeDeclaration) fieldNode.up().get(), false, fieldNode, resolverName, null, shouldReturnThis, modifier, sourceNode, onMethod, onParam);
		injectMethod(fieldNode.up(), method);
	}

	static MethodDeclaration createResolver(TypeDeclaration parent, boolean deprecate, EclipseNode fieldNode, String name, char[] booleanFieldToSet, boolean shouldReturnThis, int modifier, EclipseNode sourceNode, List<Annotation> onMethod, List<Annotation> onParam) {
		ASTNode source = sourceNode.get();
		int pS = source.sourceStart, pE = source.sourceEnd;
		
		TypeReference returnType = null;
		ReturnStatement returnThis = null;
		if (shouldReturnThis) {
			returnType = cloneSelfType(fieldNode, source);
			ThisReference thisRef = new ThisReference(pS, pE);
			returnThis = new ReturnStatement(thisRef, pS, pE);
		}
		
		return createResolver(parent, deprecate, fieldNode, name, booleanFieldToSet, returnType, returnThis, modifier, sourceNode, onMethod, onParam);
	}
	
	static MethodDeclaration createResolver(TypeDeclaration parent, boolean deprecate, EclipseNode fieldNode, String name, char[] booleanFieldToSet, TypeReference returnType, Statement returnStatement, int modifier, EclipseNode sourceNode, List<Annotation> onMethod, List<Annotation> onParam) {
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		ASTNode source = sourceNode.get();
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long)pS << 32 | pE;
		MethodDeclaration method = new MethodDeclaration(parent.compilationResult);
		method.modifiers = modifier;
		if (returnType != null) {
			method.returnType = returnType;
		} else {
			method.returnType = TypeReference.baseTypeReference(TypeIds.T_void, 0);
			method.returnType.sourceStart = pS; method.returnType.sourceEnd = pE;
		}
		Annotation[] deprecated = null;
		if (isFieldDeprecated(fieldNode) || deprecate) {
			deprecated = new Annotation[] { generateDeprecatedAnnotation(source) };
		}
		method.annotations = copyAnnotations(source, onMethod.toArray(new Annotation[0]), deprecated);
		
		
		TypeReference[] args = new TypeReference[1];
		args[0] = copyType(field.type, source);
		TypeReference typeRef = new ParameterizedSingleTypeReference("Resolver".toCharArray(), args, 0, p);
		setGeneratedBy(typeRef, source);
		
		Argument param = new Argument(field.name, p, typeRef, Modifier.FINAL);
		param.sourceStart = pS; param.sourceEnd = pE;
		method.arguments = new Argument[] { param };
		method.selector = name.toCharArray();
		method.binding = null;
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		Expression fieldRef = createFieldAccessor(fieldNode, FieldAccess.ALWAYS_FIELD, source);
		
		MessageSend createBuilderInvoke = new MessageSend();
		createBuilderInvoke.receiver = new SingleNameReference(field.name, 0L);
		createBuilderInvoke.selector = "resolve".toCharArray();
		
		//NameReference fieldNameRef = new SingleNameReference((new String(field.name) + ".resolve()").toCharArray(), p);
		Assignment assignment = new Assignment(fieldRef, createBuilderInvoke, (int)p);
		assignment.sourceStart = pS; assignment.sourceEnd = assignment.statementEnd = pE;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		
		Annotation[] nonNulls = findAnnotations(field, NON_NULL_PATTERN);
		Annotation[] nullables = findAnnotations(field, NULLABLE_PATTERN);
		List<Statement> statements = new ArrayList<Statement>(5);
		if (nonNulls.length == 0) {
			statements.add(assignment);
		} else {
			Statement nullCheck = generateNullCheck(field, sourceNode);
			if (nullCheck != null) statements.add(nullCheck);
			statements.add(assignment);
		}
		
		if (booleanFieldToSet != null) {
			statements.add(new Assignment(new SingleNameReference(booleanFieldToSet, p), new TrueLiteral(pS, pE), pE));
		}
		
		if (returnType != null && returnStatement != null) {
			statements.add(returnStatement);
		}
		method.statements = statements.toArray(new Statement[0]);
		param.annotations = copyAnnotations(source, nonNulls, nullables, onParam.toArray(new Annotation[0]));
		
		method.traverse(new SetGeneratedByVisitor(source), parent.scope);
		return method;
	}
	
	private String toResolverName(EclipseNode field) {
		String fieldName = field.getName().toString();
		if (fieldName.length() == 0) return null;
		return buildAccessorName("resolve", fieldName);
	}
}
