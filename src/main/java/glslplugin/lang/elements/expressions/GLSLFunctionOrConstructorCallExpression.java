/*
 *     Copyright 2010 Jean-Paul Balabanian and Yngve Devik Hammersland
 *
 *     This file is part of glsl4idea.
 *
 *     Glsl4idea is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as
 *     published by the Free Software Foundation, either version 3 of
 *     the License, or (at your option) any later version.
 *
 *     Glsl4idea is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with glsl4idea.  If not, see <http://www.gnu.org/licenses/>.
 */

package glslplugin.lang.elements.expressions;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveState;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import glslplugin.lang.elements.GLSLElement;
import glslplugin.lang.elements.GLSLTokenTypes;
import glslplugin.lang.elements.declarations.GLSLArraySpecifier;
import glslplugin.lang.elements.declarations.GLSLFunctionDeclaration;
import glslplugin.lang.elements.declarations.GLSLFunctionDefinition;
import glslplugin.lang.elements.declarations.GLSLStructDefinition;
import glslplugin.lang.elements.declarations.GLSLTypeSpecifier;
import glslplugin.lang.elements.preprocessor.GLSLDefineDirective;
import glslplugin.lang.elements.reference.GLSLAbstractReference;
import glslplugin.lang.elements.reference.GLSLBuiltInPsiUtilService;
import glslplugin.lang.elements.reference.GLSLReferenceUtil;
import glslplugin.lang.elements.reference.GLSLReferencingElement;
import glslplugin.lang.elements.types.GLSLArrayType;
import glslplugin.lang.elements.types.GLSLBasicFunctionType;
import glslplugin.lang.elements.types.GLSLMatrixType;
import glslplugin.lang.elements.types.GLSLScalarType;
import glslplugin.lang.elements.types.GLSLStructType;
import glslplugin.lang.elements.types.GLSLType;
import glslplugin.lang.elements.types.GLSLTypeCompatibilityLevel;
import glslplugin.lang.elements.types.GLSLTypes;
import glslplugin.lang.elements.types.GLSLVectorType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import static com.intellij.util.ArrayUtil.EMPTY_INT_ARRAY;

/**
 * GLSLFunctionOrConstructorCallExpression is a function call expression or a constructor expression.
 * What it really is determines what methods (of this class) are sensible to call.
 * <p>
 * There are three possible valid children states:
 *  TYPE_SPECIFIER                      = Constructor mode of built-in types or their arrays
 *  FUNCTION_NAME ARRAY_DECLARATOR*     = Constructor mode of struct arrays
 *  FUNCTION_NAME                       = Constructor mode of structs or an actual function call, determined by references in scope
 * All modes always followed by LEFT_PAREN parameter list RIGHT_PAREN.
 * (That is, if the tree is syntactically valid.)
 * <p>
 * Examples:
 * int A = int(4);
 * int[] B = int[3](1,2,3);
 * MyVec C = MyVec(3.5,6.7);
 * MyVec[] D = MyVec[2]( MyVec(1,2), MyVec(3,4) );
 * bool E = randomBoolean();
 *
 * @author Yngve Devik Hammersland
 *         Date: Jan 29, 2009
 *         Time: 10:34:04 AM
 */
public class GLSLFunctionOrConstructorCallExpression extends GLSLExpression implements GLSLReferencingElement {
    public GLSLFunctionOrConstructorCallExpression(@NotNull ASTNode astNode) {
        super(astNode);
    }

    //region Tree mining
    //Constructor only
    @Nullable
    private GLSLTypeSpecifier getConstructorTypeSpecifier(){
        return findChildByClass(GLSLTypeSpecifier.class);
    }

    private GLSLArraySpecifier @NotNull [] getConstructorArraySpecifiers(){
        return findChildrenByClass(GLSLArraySpecifier.class);
    }

    private int @NotNull [] getConstructorArrayDimensions() {
        final GLSLArraySpecifier[] arraySpecifiers = getConstructorArraySpecifiers();
        if (arraySpecifiers.length == 0) {
            return EMPTY_INT_ARRAY;
        }
        int[] dimensions = new int[arraySpecifiers.length];
        for (int i = 0; i < arraySpecifiers.length; i++) {
            dimensions[i] = arraySpecifiers[i].getDimensionSize();
        }
        return dimensions;
    }

    //Shared
    @Nullable
    public PsiElement getFunctionOrConstructedTypeNameIdentifier() {
        return findChildByType(GLSLTokenTypes.IDENTIFIER);
    }

    @Override
    public @Nullable PsiElement getReferencingIdentifierForRenaming() {
        // Could also be something from getConstructorTypeSpecifier, but that would be built-in and would not be able to be renamed
        return getFunctionOrConstructedTypeNameIdentifier();
    }

    @Nullable
    public GLSLParameterList getParameterList() {
        return findChildByClass(GLSLParameterList.class);
    }

    @NotNull
    public GLSLType[] getParameterTypes(){
        GLSLParameterList parameterList = getParameterList();
        if(parameterList != null)return parameterList.getParameterTypes();
        else return GLSLType.EMPTY_ARRAY;
    }

    @NotNull
    public FunctionCallOrConstructorReference.FunctionCandidate[] getFunctionCandidates() {
        final FunctionCallOrConstructorReference reference = getReference();
        return reference == null ? FunctionCallOrConstructorReference.FunctionCandidate.EMPTY_ARRAY : reference.getFunctionCandidates();
    }
    //endregion

    //region Shared

    public boolean isConstructor() {
        if(getConstructorTypeSpecifier() != null) return true;
        if(getConstructorArraySpecifiers().length > 0) return true;

        final FunctionCallOrConstructorReference reference = getReference();
        if (reference == null) return false;
        // Constructor always resolves into a struct, even if it is a dummy struct
        return reference.resolve() instanceof GLSLStructDefinition;
    }

    @NotNull
    @Override
    public GLSLType getType() {
        final FunctionCallOrConstructorReference reference = getReference();
        if (reference == null) return GLSLTypes.UNKNOWN_TYPE;
        final FunctionCallOrConstructorReference.ResolveResult[] glslResolveResults = reference.multiResolve(false);
        GLSLType onlyValidType = GLSLTypes.UNKNOWN_TYPE;
        int validResults = 0;
        for (FunctionCallOrConstructorReference.ResolveResult glslResolveResult : glslResolveResults) {
            if (!glslResolveResult.isValidResult()) continue;
            onlyValidType = glslResolveResult.resultType;
            validResults++;
        }
        return validResults == 1 ? onlyValidType : GLSLTypes.UNKNOWN_TYPE;
    }
    //endregion
    private static void clarifyConstructorArrayDimensions(final int[] dimensions, GLSLParameterList parameterList){
        if(parameterList == null) return;
        if (dimensions.length >= 1) {
            if (dimensions[0] == GLSLArrayType.UNDEFINED_SIZE_DIMENSION) {
                dimensions[0] = parameterList.getParameters().length;
            }
        }
        for (int i = 1; i < dimensions.length; i++) {
            if (dimensions[i] == GLSLArrayType.UNDEFINED_SIZE_DIMENSION) {
                //Clarify further
                //TODO
            }
        }
    }

    @Nullable
    public String getFunctionOrConstructedTypeName() {
        return GLSLElement.text(getFunctionOrConstructedTypeNameIdentifier());
    }
    //endregion

    public static class FunctionCallOrConstructorReference
            extends GLSLAbstractReference.Poly<GLSLFunctionOrConstructorCallExpression> {

        public FunctionCallOrConstructorReference(@NotNull GLSLFunctionOrConstructorCallExpression source, TextRange range) {
            super(source, range);
        }

        public static class ResolveResult extends PsiElementResolveResult {

            public static final ResolveResult[] EMPTY_ARRAY = new ResolveResult[0];
            public final GLSLType resultType;

            public ResolveResult(@NotNull PsiElement element,
                                 boolean validResult,
                                 GLSLType resultType) {
                super(element, validResult);
                this.resultType = resultType;
            }
        }

        public static final class FunctionCandidate {
            public static final FunctionCandidate[] EMPTY_ARRAY = new FunctionCandidate[0];

            public final GLSLFunctionDeclaration declaration;
            public final GLSLBasicFunctionType functionType;
            public final GLSLTypeCompatibilityLevel compatibilityLevel;
            public final GLSLType[] argumentTypes;
            public final GLSLType[] parameterTypes;
            public final int missingArgumentCount;
            public final int extraArgumentCount;
            public final int incompatibleArgumentCount;
            public final int implicitConversionCount;
            public final boolean hasInvalidArgumentType;

            private FunctionCandidate(@NotNull GLSLFunctionDeclaration declaration, @NotNull GLSLType[] argumentTypes) {
                this.declaration = declaration;
                this.functionType = declaration.getFunctionType();
                this.argumentTypes = argumentTypes;
                this.parameterTypes = functionType.getParameterTypes();
                this.compatibilityLevel = functionType.getParameterCompatibilityLevel(argumentTypes);
                this.missingArgumentCount = Math.max(0, parameterTypes.length - argumentTypes.length);
                this.extraArgumentCount = Math.max(0, argumentTypes.length - parameterTypes.length);

                int incompatible = 0;
                int implicit = 0;
                boolean invalid = false;
                final int common = Math.min(argumentTypes.length, parameterTypes.length);
                for (int i = 0; i < common; i++) {
                    final GLSLType argumentType = argumentTypes[i];
                    if (!argumentType.isValidType()) {
                        invalid = true;
                        continue;
                    }
                    final GLSLTypeCompatibilityLevel level = GLSLTypeCompatibilityLevel.getCompatibilityLevel(argumentType, parameterTypes[i]);
                    if (level == GLSLTypeCompatibilityLevel.INCOMPATIBLE) {
                        incompatible++;
                    } else if (level == GLSLTypeCompatibilityLevel.COMPATIBLE_WITH_IMPLICIT_CONVERSION) {
                        implicit++;
                    }
                }
                this.incompatibleArgumentCount = incompatible;
                this.implicitConversionCount = implicit;
                this.hasInvalidArgumentType = invalid;
            }

            public boolean isApplicable() {
                return compatibilityLevel != GLSLTypeCompatibilityLevel.INCOMPATIBLE;
            }

            public boolean isPrefixApplicable() {
                return extraArgumentCount == 0 && incompatibleArgumentCount == 0 && !hasInvalidArgumentType;
            }

            private boolean dominates(@NotNull FunctionCandidate other) {
                if (!isApplicable() || !other.isApplicable()) return false;

                boolean better = false;
                for (int i = 0; i < argumentTypes.length; i++) {
                    final int comparison = compareConversions(argumentTypes[i], parameterTypes[i], other.parameterTypes[i]);
                    if (comparison > 0) return false;
                    if (comparison < 0) better = true;
                }
                return better;
            }

            private static int compareConversions(
                    @NotNull GLSLType source,
                    @NotNull GLSLType firstTarget,
                    @NotNull GLSLType secondTarget
            ) {
                final boolean firstExact = source.typeEquals(firstTarget);
                final boolean secondExact = source.typeEquals(secondTarget);
                if (firstExact != secondExact) return firstExact ? -1 : 1;
                if (firstExact) return 0;

                final GLSLType sourceBase = source.getBaseType();
                final GLSLType firstBase = firstTarget.getBaseType();
                final GLSLType secondBase = secondTarget.getBaseType();
                if (sourceBase == GLSLScalarType.FLOAT) {
                    if (firstBase == GLSLScalarType.DOUBLE && secondBase != GLSLScalarType.DOUBLE) return -1;
                    if (secondBase == GLSLScalarType.DOUBLE && firstBase != GLSLScalarType.DOUBLE) return 1;
                }
                if (sourceBase == GLSLScalarType.INT || sourceBase == GLSLScalarType.UINT) {
                    if (firstBase == GLSLScalarType.FLOAT && secondBase == GLSLScalarType.DOUBLE) return -1;
                    if (firstBase == GLSLScalarType.DOUBLE && secondBase == GLSLScalarType.FLOAT) return 1;
                }
                return 0;
            }

            private int bestMatchRank() {
                if (isApplicable()) {
                    return compatibilityLevel == GLSLTypeCompatibilityLevel.DIRECTLY_COMPATIBLE ? 0 : 1;
                }
                if (isPrefixApplicable()) {
                    return 2;
                }
                return 3;
            }

            private int bestMatchPenalty() {
                return missingArgumentCount * 100 + extraArgumentCount * 100 + incompatibleArgumentCount * 10 + implicitConversionCount;
            }
        }

        private static final Comparator<FunctionCandidate> BEST_MATCH_ORDER =
                Comparator.comparingInt(FunctionCandidate::bestMatchRank)
                        .thenComparingInt(FunctionCandidate::bestMatchPenalty)
                        .thenComparing(candidate -> candidate.functionType.getTypename());

        @NotNull
        public FunctionCandidate[] getFunctionCandidates() {
            final GLSLFunctionOrConstructorCallExpression element = getElement();
            if (element.getConstructorTypeSpecifier() != null || element.getConstructorArraySpecifiers().length != 0) {
                return FunctionCandidate.EMPTY_ARRAY;
            }

            final String functionName = element.getFunctionOrConstructedTypeName();
            if (functionName == null) {
                return FunctionCandidate.EMPTY_ARRAY;
            }

            final WalkResult walk = WalkResult.walkPossibleReferences(element, functionName);
            return getFunctionCandidates(walk);
        }

        private FunctionCandidate[] getFunctionCandidates(@NotNull WalkResult walk) {
            if (!walk.structDefinitions.isEmpty() || walk.functionDeclarations.isEmpty()) return FunctionCandidate.EMPTY_ARRAY;
            final GLSLType[] argumentTypes = getElement().getParameterTypes();
            final List<FunctionCandidate> candidates = new ArrayList<>(walk.functionDeclarations.size());
            for (GLSLFunctionDeclaration declaration : walk.functionDeclarations.values()) {
                candidates.add(new FunctionCandidate(declaration, argumentTypes));
            }
            candidates.sort(BEST_MATCH_ORDER);
            return candidates.toArray(FunctionCandidate.EMPTY_ARRAY);
        }

        @Override
        public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
            final GLSLFunctionOrConstructorCallExpression element = getElement();
            final GLSLTypeSpecifier typeSpec = element.getConstructorTypeSpecifier();
            final String customTypeOrName = element.getFunctionOrConstructedTypeName();

            if (typeSpec != null) {
                // Built-in type constructor, it does not matter what the array specifiers are
                GLSLType type = typeSpec.getType();

                // NOTE: Built-in types have different handling of array sizes, it is a part of their type specifier
                // Structs don't have that - their array sizes are separate elements after specifier
                GLSLType baseType;
                if (type instanceof GLSLArrayType) {
                    baseType = type.getBaseType();
                } else {
                    baseType = type;
                }

                final GLSLBuiltInPsiUtilService bipus = element.getProject().getService(GLSLBuiltInPsiUtilService.class);
                final GLSLStructDefinition builtInType;
                if (baseType instanceof GLSLScalarType) {
                    builtInType = bipus.getScalarDefinition((GLSLScalarType) baseType);
                } else if (baseType instanceof GLSLVectorType) {
                    builtInType = bipus.getVecDefinition((GLSLVectorType) baseType);
                } else if (baseType instanceof GLSLMatrixType) {
                    builtInType = bipus.getMatrixDefinition((GLSLMatrixType) baseType);
                } else {
                    return ResolveResult.EMPTY_ARRAY;
                }

                if (type instanceof GLSLArrayType arrayType) {
                    // Array may be implicitly sized: if so, clarify it using parameter list
                    final int[] dimensions = arrayType.getDimensions();
                    final int[] newDimensions = Arrays.copyOf(dimensions, dimensions.length);
                    final GLSLParameterList parameterList = element.getParameterList();
                    clarifyConstructorArrayDimensions(newDimensions, parameterList);
                    type = new GLSLArrayType(type.getBaseType(), newDimensions);
                }

                return new ResolveResult[]{
                        new ResolveResult(builtInType, true, type)
                };
            } else if (customTypeOrName != null) {
                // Lookup the struct or function
                final WalkResult walk = WalkResult.walkPossibleReferences(element, customTypeOrName);

                final ArrayList<ResolveResult> results = new ArrayList<>();

                final int @NotNull[] constructorArraySpecifiers = element.getConstructorArrayDimensions();
                if (walk.structDefinitions.isEmpty()
                        && !walk.functionDeclarations.isEmpty()
                        && constructorArraySpecifiers.length == 0) {
                    final FunctionCandidate[] candidates = getFunctionCandidates(walk);
                    for (FunctionCandidate candidate : candidates) {
                        boolean valid = candidate.isApplicable();
                        for (FunctionCandidate other : candidates) {
                            if (candidate != other && other.isApplicable() && !candidate.dominates(other)) {
                                valid = false;
                                break;
                            }
                        }
                        results.add(new ResolveResult(candidate.declaration, valid, candidate.functionType.getReturnType()));
                    }
                }

                clarifyConstructorArrayDimensions(constructorArraySpecifiers, element.getParameterList());
                for (GLSLStructDefinition definition : walk.structDefinitions) {
                    final GLSLStructType structType = definition.getType();
                    final GLSLType resultType;
                    if (constructorArraySpecifiers.length == 0) {
                        // This is a direct instantiation
                        resultType = structType;
                    } else {
                        // This is an array instantiation
                        resultType = new GLSLArrayType(structType, constructorArraySpecifiers);
                    }
                    results.add(new ResolveResult(definition, true, resultType));
                }

                return results.toArray(ResolveResult.EMPTY_ARRAY);
            } else {
                // Broken
                return ResolveResult.EMPTY_ARRAY;
            }
        }

        @Override
        public @Nullable PsiElement resolve() {
            final GLSLFunctionOrConstructorCallExpression element = getElement();
            if (element.getConstructorTypeSpecifier() == null) {
                final String macroName = element.getFunctionOrConstructedTypeName();
                if (macroName != null) {
                    final GLSLDefineDirective defineDirective = GLSLDefineDirective.findActiveDefinitionBefore(element, macroName);
                    if (defineDirective != null) {
                        return defineDirective;
                    }
                }
            }

            final com.intellij.psi.ResolveResult[] resolveResults = multiResolve(false);
            PsiElement onlyValidElement = null;
            int validElements = 0;
            for (com.intellij.psi.ResolveResult resolveResult : resolveResults) {
                if (!resolveResult.isValidResult()) continue;
                onlyValidElement = resolveResult.getElement();
                validElements++;
            }
            if (validElements == 1) {
                return onlyValidElement;
            }
            return null;
        }

        @Override
        public boolean isReferenceTo(@NotNull PsiElement element) {
            final PsiElement resolved = resolve();
            if (resolved != null) {
                return getElement().getManager().areElementsEquivalent(resolved, element);
            }
            for (ResolveResult result : multiResolve(false)) {
                if (result.isValidResult()
                        && getElement().getManager().areElementsEquivalent(result.getElement(), element)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final class WalkResult implements PsiScopeProcessor {

        public static WalkResult walkPossibleReferences(PsiElement from, String onlyNamed) {
            final WalkResult result = new WalkResult(onlyNamed);
            PsiTreeUtil.treeWalkUp(result, from, null, ResolveState.initial());
            return result;
        }

        private final String onlyNamed;
        public final LinkedHashMap<String, GLSLFunctionDeclaration> functionDeclarations = new LinkedHashMap<>();
        public final ArrayList<GLSLStructDefinition> structDefinitions = new ArrayList<>();

        public WalkResult(String onlyNamed) {
            this.onlyNamed = onlyNamed;
        }


        @Override
        public boolean execute(@NotNull PsiElement element, @NotNull ResolveState state) {
            final String onlyNamed = this.onlyNamed;
            if (element instanceof GLSLFunctionDeclaration dec) {
                if (onlyNamed == null || onlyNamed.equals(dec.getFunctionName())) {
                    final GLSLBasicFunctionType funcType = dec.getFunctionType();
                    final StringBuilder signature = new StringBuilder(funcType.getName()).append('(');
                    for (GLSLType parameterType : funcType.getParameterTypes()) {
                        signature.append(parameterType.getTypename()).append(';');
                    }
                    signature.append(')');
                    final GLSLFunctionDeclaration displaced = functionDeclarations.put(signature.toString(), dec);
                    if (displaced instanceof GLSLFunctionDefinition && !(dec instanceof GLSLFunctionDefinition)) {
                        // We have removed definition for just declaration, put it back
                        functionDeclarations.put(signature.toString(), displaced);
                    }
                }
            } else if (element instanceof GLSLStructDefinition def) {
                if (structDefinitions.isEmpty() && (onlyNamed == null || onlyNamed.equals(def.getStructName()))) {
                    structDefinitions.add(def);
                }
            }
            return true;// Continue
        }
    }

    @Override
    public FunctionCallOrConstructorReference getReference() {
        final TextRange range;
        final GLSLTypeSpecifier constructorType = getConstructorTypeSpecifier();
        if (constructorType != null) {
            range = GLSLReferenceUtil.rangeOfIn(constructorType, this);
        } else {
            final PsiElement identifier = getFunctionOrConstructedTypeNameIdentifier();
            if (identifier != null) {
                range = GLSLReferenceUtil.rangeOfIn(identifier, this);
            } else {
                return null;
            }
        }
        return new FunctionCallOrConstructorReference(this, range);
    }

    @Override
    public PsiReference @NotNull [] getReferences() {
        final FunctionCallOrConstructorReference reference = getReference();
        return reference == null ? PsiReference.EMPTY_ARRAY : new PsiReference[]{reference};
    }

    @Override
    public String toString() {
        if (isConstructor()) {
            return "Constructor call: "+getType().getTypename();
        }else{
            return "Function call: " + getFunctionOrConstructedTypeName();
        }
    }

    @Override
    public String getName() {
        if (isConstructor()) {
            return getType().getTypename();
        } else {
            return getFunctionOrConstructedTypeName();
        }
    }
}
