package glslplugin.annotation.impl;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import glslplugin.annotation.Annotator;
import glslplugin.lang.elements.declarations.GLSLFunctionDeclaration;
import glslplugin.lang.elements.declarations.GLSLParameterDeclaration;
import glslplugin.lang.elements.expressions.GLSLFunctionOrConstructorCallExpression;
import glslplugin.lang.elements.types.GLSLType;
import glslplugin.lang.elements.types.GLSLTypeCompatibilityLevel;
import org.jetbrains.annotations.NotNull;

public class FunctionCallArgumentAnnotation extends Annotator<GLSLFunctionOrConstructorCallExpression> {
    @Override
    public void annotate(GLSLFunctionOrConstructorCallExpression expr, AnnotationHolder holder) {
        if (expr.isConstructor()) return;

        final PsiElement identifier = expr.getFunctionOrConstructedTypeNameIdentifier();
        if (identifier == null) return;

        final GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference.FunctionCandidate[] candidates = expr.getFunctionCandidates();
        if (candidates.length == 0) return;

        for (GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference.FunctionCandidate candidate : candidates) {
            if (candidate.isApplicable()) {
                return;
            }
            if (candidate.hasInvalidArgumentType) {
                return;
            }
        }

        final String message = "None of the following candidates is applicable";
        holder.newAnnotation(HighlightSeverity.ERROR, message)
                .range(identifier.getTextRange())
                .tooltip(buildTooltip(candidates))
                .create();
    }

    @NotNull
    @Override
    public Class<GLSLFunctionOrConstructorCallExpression> getElementType() {
        return GLSLFunctionOrConstructorCallExpression.class;
    }

    private static String buildTooltip(GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference.FunctionCandidate[] candidates) {
        final StringBuilder sb = new StringBuilder("<html><body>");
        sb.append("None of the following candidates is applicable:<br/><br/>");
        for (GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference.FunctionCandidate candidate : candidates) {
            sb.append("<code>").append(StringUtil.escapeXmlEntities(formatSignature(candidate.declaration))).append("</code><br/>");
            appendCandidateProblems(sb, candidate);
            sb.append("<br/>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String formatSignature(@NotNull GLSLFunctionDeclaration declaration) {
        final StringBuilder sb = new StringBuilder();
        sb.append(declaration.getReturnType().getTypename()).append(' ');
        sb.append(declaration.getFunctionName()).append('(');
        final GLSLParameterDeclaration[] parameters = declaration.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i != 0) sb.append(", ");
            sb.append(parameters[i].getTypeSpecifierNodeTypeName());
            final String parameterName = parameters[i].getParameterName();
            if (parameterName != null) {
                sb.append(' ').append(parameterName);
            }
        }
        sb.append(')');
        return sb.toString();
    }

    private static void appendCandidateProblems(
            @NotNull StringBuilder sb,
            @NotNull GLSLFunctionOrConstructorCallExpression.FunctionCallOrConstructorReference.FunctionCandidate candidate
    ) {
        boolean added = false;
        final GLSLParameterDeclaration[] parameters = candidate.declaration.getParameters();
        for (int i = candidate.argumentTypes.length; i < parameters.length; i++) {
            sb.append(StringUtil.escapeXmlEntities("No value passed for parameter '" + parameterName(parameters[i], i) + "'.")).append("<br/>");
            added = true;
        }

        if (candidate.extraArgumentCount > 0) {
            sb.append("Too many arguments: expected ")
                    .append(candidate.parameterTypes.length)
                    .append(", got ")
                    .append(candidate.argumentTypes.length)
                    .append(".<br/>");
            added = true;
        }

        final int common = Math.min(candidate.argumentTypes.length, candidate.parameterTypes.length);
        for (int i = 0; i < common; i++) {
            final GLSLType argumentType = candidate.argumentTypes[i];
            final GLSLType parameterType = candidate.parameterTypes[i];
            if (GLSLTypeCompatibilityLevel.getCompatibilityLevel(argumentType, parameterType) == GLSLTypeCompatibilityLevel.INCOMPATIBLE) {
                sb.append(StringUtil.escapeXmlEntities(
                        "Argument type mismatch for parameter '" + parameterName(parameters[i], i) +
                                "'. Required: " + parameterType.getTypename() + ", found: " + argumentType.getTypename() + "."
                )).append("<br/>");
                added = true;
            }
        }

        if (!added) {
            sb.append("Argument list is not applicable.<br/>");
        }
    }

    private static String parameterName(@NotNull GLSLParameterDeclaration parameter, int index) {
        final String name = parameter.getParameterName();
        return name == null ? "#" + (index + 1) : name;
    }
}
